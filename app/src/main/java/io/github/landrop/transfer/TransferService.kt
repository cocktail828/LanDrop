package io.github.landrop.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import androidx.annotation.StringRes
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.SystemClock
import io.github.landrop.receiver.IncomingConfirmationOverlay
import java.util.UUID
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.discovery.DiscoveryListener
import io.github.landrop.discovery.DiscoveryManager
import io.github.landrop.discovery.LocalIdentity
import io.github.landrop.history.ReceiveHistoryStore
import io.github.landrop.model.ReceivedFile
import io.github.landrop.model.RemoteDevice
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.settings.AppLocale
import io.github.landrop.settings.AppSettings
import io.github.landrop.model.finalizeOutgoingSessions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns discovery and transfers independently of the Activity lifecycle. */
class TransferService : Service(), DiscoveryListener {
    interface Listener {
        fun onDevicesChanged(devices: List<RemoteDevice>)
        fun onDiscoveryError(message: String)
        fun onIncomingTransferRequest(request: IncomingTransferManager.PrepareUploadRequest, decide: (IncomingReceiveOptions?) -> Unit)
        fun onIncomingRequestResolved() {}
        fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest)
        fun onOutgoingSessionPreparing(sessionId: String, files: List<ActiveTransferFile>)
        fun onOutgoingChecksumProgress(sessionId: String, current: Int, total: Int)
        fun onOutgoingSessionPrepared(sessionId: String, files: List<ActiveTransferFile>)
        fun onOutgoingSessionStarted(preparationSessionId: String, sessionId: String, files: List<ActiveTransferFile>)
        fun onActiveTransfersRestored(files: List<ActiveTransferFile>)
        fun onFileReceiveProgress(file: ActiveTransferFile)
        fun onFileSendProgress(file: ActiveTransferFile)
        fun onFileReceiveCancelled(file: ActiveTransferFile, sessionComplete: Boolean)
        fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean)
        fun onIncomingSessionCompleted(sessionId: String)
        fun onOutgoingSessionCompleted(sessionId: String)
        fun onUploadStatus(message: String)
        fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit)
        fun onUploadProgress(fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long)
        fun onTransferStateRestored(title: String, percent: Int)
        fun onTransferFinished(message: String)
        fun onUploadCompleted(names: List<String>)
        fun onUploadError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun service(): TransferService = this@TransferService
    }

    private fun text(@StringRes resource: Int, vararg arguments: Any): String = AppLocale.text(this, resource, *arguments)

    private val binder = LocalBinder()
    private val listenerLock = Any()
    private val listeners = LinkedHashSet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var discoveryManager: DiscoveryManager? = null
    private lateinit var uploadClient: UploadClient
    private lateinit var receiveHistory: ReceiveHistoryStore
    private lateinit var settings: AppSettings
    private val cancellationExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val screenAwakeLock = Any()
    private val activeScreenAwakeSessions = LinkedHashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private val renewCpuWakeLock = Runnable { updateScreenAwakeLock() }
    @Volatile private var outgoingScreenAwakeSessionId: String? = null
    private var activeNotificationTitle: String? = null
    private var activeNotificationProgress = 0
    private var hasActiveTransfer = false
    @Volatile private var currentDevices: List<RemoteDevice> = emptyList()
    private var lastTransferMessage: String? = null
    @Volatile private var latestSessionId: String? = null
    @Volatile private var lastTransferWasError = false
    private var outgoingPeerAlias: String? = null
    private var outgoingMessageText: String? = null
    private var lastProgressDispatchAt = 0L
    private var lastProgressDispatchPercent = -1
    private var activeTransferredBytes = 0L
    private var activeTotalBytes = -1L
    private var progressStartedAt = 0L
    private var transferSpeedBytesPerSecond = 0L
    @Volatile private var notificationGeneration = 0L
    @Volatile private var cancellationRequested = false
    @Volatile private var serviceDestroyed = false
    private val cancellationInProgress = AtomicBoolean(false)
    @Volatile private var pendingIncoming: PendingIncoming? = null
    private val incomingFiles = LinkedHashMap<String, LinkedHashMap<String, ActiveTransferFile>>()
    private val incomingSenders = LinkedHashMap<String, String>()
    private val outgoingFiles = LinkedHashMap<String, LinkedHashMap<String, ActiveTransferFile>>()
    private val outgoingSessionsInProgress = LinkedHashSet<String>()
    private var lastIncomingActivityAt = 0L
    private val incomingIdleCheck = object : Runnable {
        override fun run() {
            val active = synchronized(incomingFiles) { incomingFiles.values.any { files -> files.values.any(::isActive) } }
            if (!active || serviceDestroyed) return
            if (SystemClock.elapsedRealtime() - lastIncomingActivityAt >= 120_000L) {
                cancellationExecutor.execute { discoveryManager?.cancelIncomingTransfer() }
            } else mainHandler.postDelayed(this, 30_000L)
        }
    }

    private val incomingOverlay by lazy { IncomingConfirmationOverlay(this) }
    private val networkRestart = Runnable {
        if (!serviceDestroyed && ::settings.isInitialized && settings.backgroundReceiverEnabled()) {
            if (hasActiveTransfer) scheduleNetworkRestart(5000) else restartNetwork()
        }
    }
    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!isInitialStickyBroadcast) scheduleNetworkRestart(1500)
        }
    }

    private fun scheduleNetworkRestart(delay: Long) {
        mainHandler.removeCallbacks(networkRestart)
        mainHandler.postDelayed(networkRestart, delay)
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        TransferServiceState.markRunning(settings.backgroundReceiverEnabled(), this)
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, baseNotification())
        uploadClient = UploadClient(this, LocalIdentity(this))
        receiveHistory = ReceiveHistoryStore(this)
        @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
        registerReceiver(connectivityReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
        restartNetwork()
        if (!settings.backgroundReceiverEnabled()) removeNotificationsAndStopForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.backgroundReceiverEnabled() && intent?.action != ACTION_STOP_SERVICE) {
            TransferServiceState.markRunning(false, this)
            removeNotificationsAndStopForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_STOP_SERVICE && !TransferServiceState.isRunning()) {
            TransferServiceState.markRunning(true, this)
            startForeground(NOTIFICATION_ID, baseNotification())
            restartNetwork()
        }
        when (intent?.action) {
            // Cancel only the active transfer. Keep the foreground receiver
            // alive so the next sender can still reach port 53317.
            ACTION_CANCEL -> cancelCurrent()
            ACTION_ACCEPT_INCOMING -> resolveIncoming(intent.getStringExtra(EXTRA_REQUEST_ID), true)
            ACTION_REJECT_INCOMING -> resolveIncoming(intent.getStringExtra(EXTRA_REQUEST_ID), false)
            ACTION_RELOAD_SETTINGS -> restartNetwork()
            ACTION_ANNOUNCE_IDENTITY -> announce()
            ACTION_REFRESH_SCREEN_AWAKE -> updateScreenAwakeLock()
            ACTION_REFRESH_LANGUAGE -> refreshLanguage()
            ACTION_STOP_SERVICE -> {
                settings.setBackgroundReceiverEnabled(false)
                resolveIncoming(false)
                cancelCurrent(stopService = true)
            }
        }
        return if (settings.backgroundReceiverEnabled()) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: Listener) {
        synchronized(listenerLock) { listeners += listener }
        mainHandler.post {
            listener.onDevicesChanged(currentDevices)
            if (hasActiveTransfer) listener.onTransferStateRestored(activeNotificationTitle ?: text(R.string.notification_service_title), activeNotificationProgress)
            else lastTransferMessage?.let(listener::onTransferFinished)
            pendingIncoming?.let { pending ->
                incomingOverlay.dismiss()
                listener.onIncomingTransferRequest(pending.request) { options -> resolveIncoming(pending.id, options) }
            }
            synchronized(outgoingFiles) {
                outgoingFiles.forEach { (sessionId, files) ->
                    listener.onOutgoingSessionPrepared(sessionId, files.values.toList())
                }
            }
            val activeSnapshot = synchronized(incomingFiles) {
                incomingFiles.values.flatMap { it.values }
            } + synchronized(outgoingFiles) {
                outgoingFiles.values.flatMap { it.values }
            }
            listener.onActiveTransfersRestored(activeSnapshot)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listenerLock) { listeners -= listener }
        if (synchronized(listenerLock) { listeners.isEmpty() }) pendingIncoming?.let(::showPendingIncoming)
    }

    fun announce() = discoveryManager?.announce()

    fun refreshDevices() = discoveryManager?.refresh()

    fun recordReceivedMessage(message: String, senderAlias: String?) {
        if (!settings.saveReceiveHistory()) return
        networkExecutor.execute {
            if (!serviceDestroyed) receiveHistory.addMessage(message, senderAlias)
        }
    }

    private fun restartNetwork() {
        if (serviceDestroyed) return
        resolveIncoming(false)
        networkExecutor.execute {
            discoveryManager?.stop()
            if (serviceDestroyed || !settings.backgroundReceiverEnabled()) return@execute
            val manager = DiscoveryManager(this, this)
            discoveryManager = manager
            if (settings.serverEnabled()) manager.start() else onDevicesChanged(emptyList())
        }
    }

    fun send(uri: Uri, device: RemoteDevice) = send(listOf(uri), device)

    fun latestTransferSessionId(): String? = latestSessionId
    fun lastTransferError(): String? = if (lastTransferWasError) lastTransferMessage else null

    fun isBusy(): Boolean = hasActiveTransfer || pendingIncoming != null || cancellationInProgress.get()

    fun send(uris: List<Uri>, device: RemoteDevice, messageText: String? = null, relativeNames: Map<Uri, String> = emptyMap()) {
        if (isBusy()) {
            notifyListeners { it.onUploadError(text(R.string.receiver_busy)) }
            return
        }
        notificationGeneration++
        cancellationRequested = false
        hasActiveTransfer = true
        lastTransferMessage = null
        lastTransferWasError = false
        resetProgressDispatch()
        resetTransferMetrics()
        activeNotificationTitle = text(R.string.notification_uploading)
        activeNotificationProgress = 0
        outgoingPeerAlias = device.alias
        outgoingMessageText = messageText
        uploadClient.send(uris, device, messageText, relativeNames, object : UploadClient.Listener {
            override fun onStatus(message: String) {
                if (cancellationRequested) return
                activeNotificationTitle = message
                notifyProgress()
                notifyListeners { it.onUploadStatus(message) }
            }

            override fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit) {
                val hasForegroundListener = synchronized(listenerLock) { listeners.isNotEmpty() }
                if (hasForegroundListener) {
                    notifyListeners { it.onPinRequired(device, attempt, reply) }
                } else {
                    reply(null)
                }
            }

            override fun onPreparationStarted(sessionId: String, files: List<UploadClient.QueueFile>) {
                latestSessionId = sessionId
                val queue = LinkedHashMap<String, ActiveTransferFile>()
                files.forEach { file ->
                    queue[file.id] = ActiveTransferFile(
                        sessionId, file.id, file.fileName, 0L, file.size,
                        ActiveTransferFile.Status.WAITING, ActiveTransferFile.Direction.OUTGOING
                    )
                }
                synchronized(outgoingFiles) {
                    outgoingFiles[sessionId] = queue
                    outgoingSessionsInProgress += sessionId
                }
                notifyListeners { it.onOutgoingSessionPreparing(sessionId, queue.values.toList()) }
            }

            override fun onChecksumProgress(sessionId: String, current: Int, total: Int) {
                notifyListeners { it.onOutgoingChecksumProgress(sessionId, current, total) }
            }

            override fun onSessionPrepared(preparationSessionId: String, sessionId: String, files: List<UploadClient.QueueFile>) {
                latestSessionId = sessionId
                beginScreenAwakeSession(sessionId)
                outgoingScreenAwakeSessionId = sessionId
                val queue = LinkedHashMap<String, ActiveTransferFile>()
                files.forEach { file ->
                    queue[file.id] = ActiveTransferFile(
                        sessionId, file.id, file.fileName, 0L, file.size,
                        ActiveTransferFile.Status.WAITING, ActiveTransferFile.Direction.OUTGOING
                    )
                }
                synchronized(outgoingFiles) {
                    outgoingFiles.remove(preparationSessionId)
                    outgoingFiles[sessionId] = queue
                    outgoingSessionsInProgress.remove(preparationSessionId)
                    outgoingSessionsInProgress += sessionId
                }
                notifyListeners { it.onOutgoingSessionStarted(preparationSessionId, sessionId, queue.values.toList()) }
            }

            override fun onProgress(sessionId: String, fileId: String, fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long) {
                if (cancellationRequested) return
                hasActiveTransfer = true
                activeNotificationTitle = text(R.string.notification_uploading)
                activeNotificationProgress = if (totalBytes > 0) ((totalSent * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                updateTransferMetrics(totalSent, totalBytes)
                dispatchProgressIfNeeded(totalSent, totalBytes) { listener ->
                    val state = synchronized(outgoingFiles) {
                        outgoingFiles[sessionId]?.get(fileId)?.copy(
                            receivedBytes = sent,
                            totalBytes = total,
                            status = ActiveTransferFile.Status.TRANSFERRING
                        )?.also { outgoingFiles[sessionId]?.set(fileId, it) }
                    }
                    if (state != null) {
                        listener.onFileSendProgress(state)
                    }
                    listener.onUploadProgress(fileName, fileIndex, fileCount, sent, total, totalSent, totalBytes)
                }
            }

            override fun onFileCompleted(sessionId: String, fileId: String) {
                val state = synchronized(outgoingFiles) {
                    outgoingFiles[sessionId]?.get(fileId)?.copy(
                        receivedBytes = outgoingFiles[sessionId]?.get(fileId)?.totalBytes ?: 0L,
                        status = ActiveTransferFile.Status.COMPLETED
                    )?.also { outgoingFiles[sessionId]?.set(fileId, it) }
                }
                if (state != null) {
                    persistOutgoingHistory(state)
                    notifyListeners { it.onFileSendProgress(state) }
                }
            }

            override fun onCompleted(names: List<String>) {
                outgoingScreenAwakeSessionId?.let(::endScreenAwakeSession)
                outgoingScreenAwakeSessionId = null
                resetProgressDispatch()
                lastTransferMessage = text(R.string.upload_completed, names.joinToString(text(R.string.file_name_separator)))
                finishOutgoingQueues(ActiveTransferFile.Status.COMPLETED)
                cancellationRequested = false
                hasActiveTransfer = hasActiveTransferEntries()
                notifyListeners { it.onUploadCompleted(names) }
                clearTransferNotification()
            }

            override fun onError(message: String) {
                outgoingScreenAwakeSessionId?.let(::endScreenAwakeSession)
                outgoingScreenAwakeSessionId = null
                val terminalStatus = if (cancellationRequested) ActiveTransferFile.Status.CANCELLED else ActiveTransferFile.Status.FAILED
                finishOutgoingQueues(terminalStatus)
                cancellationRequested = false
                hasActiveTransfer = hasActiveTransferEntries()
                resetProgressDispatch()
                lastTransferMessage = message
                lastTransferWasError = true
                notifyListeners { it.onUploadError(message) }
                clearTransferNotification()
            }
        })
    }

    fun cancelCurrent(stopService: Boolean = false) {
        if (stopService) {
            settings.setBackgroundReceiverEnabled(false)
            TransferServiceState.markRunning(false, this)
            resolveIncoming(false)
            mainHandler.removeCallbacks(networkRestart)
            networkExecutor.execute { discoveryManager?.stop() }
        }
        if (!cancellationInProgress.compareAndSet(false, true)) {
            if (stopService) { removeNotificationsAndStopForeground(); stopSelf() }
            return
        }
        // Block progress callbacks immediately while the remote cancellation
        // request is still in flight.
        cancellationRequested = true
        hasActiveTransfer = false
        endAllScreenAwakeSessions()
        if (stopService) removeNotificationsAndStopForeground()
        cancellationExecutor.execute {
            uploadClient.cancelCurrent()
            // DiscoveryManager performs the peer cancellation synchronously
            // on this worker, before the local session is removed.
            discoveryManager?.cancelIncomingTransfer()
            mainHandler.post {
                lastTransferMessage = text(R.string.upload_cancelled)
                if (!stopService) {
                    cancellationRequested = false
                    clearTransferNotification()
                }
                cancellationInProgress.set(false)
                if (stopService && !settings.backgroundReceiverEnabled()) {
                    removeNotificationsAndStopForeground()
                    stopSelf()
                } else if (stopService) {
                    cancellationRequested = false
                    clearTransferNotification()
                }
            }
        }
    }

    fun cancelIncomingFile(sessionId: String, fileId: String): Boolean = discoveryManager?.cancelIncomingFile(sessionId, fileId) == true

    override fun onDevicesChanged(devices: List<RemoteDevice>) {
        currentDevices = devices
        notifyListeners { it.onDevicesChanged(devices) }
    }
    override fun onDiscoveryError(message: String) {
        notifyListeners { it.onDiscoveryError(message) }
        // Boot can precede DHCP. Retry without repeatedly waking a stopped receiver.
        mainHandler.post { if (!serviceDestroyed && settings.backgroundReceiverEnabled()) scheduleNetworkRestart(30_000) }
    }

    override fun onIncomingPreparationCancelled(request: IncomingTransferManager.PrepareUploadRequest) {
        val pending = pendingIncoming ?: return
        if (pending.request === request) resolveIncoming(pending.id, false)
    }

    override fun onIncomingTransferRequest(
        request: IncomingTransferManager.PrepareUploadRequest,
        decide: (IncomingReceiveOptions?) -> Unit
    ) {
        if (serviceDestroyed || !settings.backgroundReceiverEnabled()) { decide(null); return }
        // Keep one decision owner regardless of which UI is currently visible.
        if (pendingIncoming != null || hasActiveTransfer) { decide(null); return }
        val pending = PendingIncoming(request, decide)
        pendingIncoming = pending
        mainHandler.postDelayed({ resolveIncoming(pending.id, false) }, IncomingTransferManager.DECISION_TIMEOUT_SECONDS * 1000)
        val listener = synchronized(listenerLock) { listeners.firstOrNull() }
        if (listener != null) {
            listener.onIncomingTransferRequest(request) { options -> resolveIncoming(pending.id, options) }
        } else showPendingIncoming(pending)
    }

    private fun showPendingIncoming(pending: PendingIncoming) {
        if (pendingIncoming?.id != pending.id) return
        showIncomingRequestNotification(pending.request)
        if (settings.overlayConfirmation()) incomingOverlay.show(pending.request) { accepted ->
            resolveIncoming(pending.id, accepted)
        }
    }

    override fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest) {
        latestSessionId = sessionId
        lastTransferWasError = false
        beginScreenAwakeSession(sessionId)
        val files = LinkedHashMap<String, ActiveTransferFile>()
        request.files.forEach { (fileId, file) ->
            files[fileId] = ActiveTransferFile(sessionId, fileId, file.fileName, 0L, file.size, ActiveTransferFile.Status.WAITING)
        }
        synchronized(incomingFiles) {
            incomingFiles[sessionId] = files
            incomingSenders[sessionId] = request.info.alias
        }
        hasActiveTransfer = true
        lastIncomingActivityAt = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(incomingIdleCheck)
        mainHandler.postDelayed(incomingIdleCheck, 120_000L)
        notifyListeners { it.onIncomingSessionPrepared(sessionId, request) }
    }

    override fun onFileReceiveProgress(sessionId: String, fileId: String, fileName: String, received: Long, total: Long) {
        if (cancellationRequested) return
        lastIncomingActivityAt = SystemClock.elapsedRealtime()
        val state = ActiveTransferFile(sessionId, fileId, fileName, received, total, ActiveTransferFile.Status.TRANSFERRING)
        synchronized(incomingFiles) { incomingFiles[sessionId]?.set(fileId, state) }
        hasActiveTransfer = true
        // Multiple files may upload concurrently. Keep the notification title
        // stable instead of replacing it on every file progress callback.
        activeNotificationTitle = text(R.string.notification_receiving_files)
        val aggregate = incomingProgress(sessionId)
        activeNotificationProgress = if (aggregate.second > 0) ((aggregate.first * 100L) / aggregate.second).toInt().coerceIn(0, 100) else 0
        updateTransferMetrics(aggregate.first, aggregate.second)
        dispatchProgressIfNeeded(aggregate.first, aggregate.second) { it.onFileReceiveProgress(state) }
    }

    override fun onFileReceiveCancelled(sessionId: String, fileId: String, fileName: String, sessionComplete: Boolean) =
        finishIncomingFile(sessionId, fileId, fileName, sessionComplete, ActiveTransferFile.Status.CANCELLED)

    override fun onFileReceiveFailed(sessionId: String, fileId: String, fileName: String, sessionComplete: Boolean) =
        finishIncomingFile(sessionId, fileId, fileName, sessionComplete, ActiveTransferFile.Status.FAILED)

    private fun finishIncomingFile(sessionId: String, fileId: String, fileName: String, sessionComplete: Boolean,
                                   status: ActiveTransferFile.Status) {
        val state = synchronized(incomingFiles) {
            val previous = incomingFiles[sessionId]?.get(fileId)
            if (previous != null && !isActive(previous)) return
            ActiveTransferFile(
                sessionId,
                fileId,
                fileName,
                previous?.receivedBytes ?: 0L,
                previous?.totalBytes ?: 0L,
                status
            ).also { incomingFiles[sessionId]?.set(fileId, it) }
        }
        if (settings.saveReceiveHistory()) {
            val peer = incomingSenders[sessionId]
            networkExecutor.execute {
                if (!serviceDestroyed) receiveHistory.addTransfer(state.fileName, peer, state.totalBytes, false, if (status == ActiveTransferFile.Status.FAILED) "failed" else "cancelled")
            }
        }
        lastTransferMessage = if (status == ActiveTransferFile.Status.FAILED) text(R.string.transfer_failed)
            else text(R.string.download_cancelled, fileName)
        if (status == ActiveTransferFile.Status.FAILED) lastTransferWasError = true
        notifyListeners { it.onFileReceiveCancelled(state, sessionComplete) }
        if (sessionComplete) {
            endScreenAwakeSession(sessionId)
            finishIncomingSession(sessionId, status)
            resetProgressDispatch()
            notifyListeners { it.onIncomingSessionCompleted(sessionId) }
            if (!cancellationRequested) clearTransferNotification()
        }
    }

    override fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean) {
        if (cancellationRequested) return
        synchronized(incomingFiles) {
            incomingFiles[sessionId]?.get(fileId)?.let { incomingFiles[sessionId]?.set(fileId, it.copy(receivedBytes = it.totalBytes, status = ActiveTransferFile.Status.COMPLETED)) }
            if (settings.saveReceiveHistory()) receiveHistory.add(file, incomingSenders[sessionId])
        }
        if (!lastTransferWasError) lastTransferMessage = text(R.string.download_completed, file.displayName)
        notifyListeners { it.onFileReceived(sessionId, fileId, file, sessionComplete) }
        if (sessionComplete) {
            endScreenAwakeSession(sessionId)
            finishIncomingSession(sessionId, ActiveTransferFile.Status.COMPLETED)
            resetProgressDispatch()
            notifyListeners { it.onIncomingSessionCompleted(sessionId) }
            clearTransferNotification()
            val location = settings.receiveDirectoryName() ?: text(R.string.settings_default_receive_directory)
            notificationManager().notify(RESULT_NOTIFICATION_ID,
                NotificationCompat.Builder(this, CHANNEL_TRANSFER)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(text(if (lastTransferWasError) R.string.transfer_failed else R.string.receiver_saved))
                    .setContentText("${file.displayName.take(160)} · $location")
                    .setContentIntent(PendingIntent.getActivity(this, 5,
                        Intent(this, io.github.landrop.ReceiveHistoryActivity::class.java), pendingIntentFlags()))
                    .setAutoCancel(true)
                    .build())
        }
    }

    private fun incomingProgress(sessionId: String): Pair<Long, Long> = synchronized(incomingFiles) {
        val files = incomingFiles[sessionId]?.values ?: return@synchronized 0L to 0L
        files.sumOf { it.receivedBytes } to files.sumOf { it.totalBytes }
    }

    private fun persistOutgoingHistory(file: ActiveTransferFile) {
        if (!settings.saveReceiveHistory()) return
        val message = outgoingMessageText
        val name = message ?: file.fileName
        val peer = outgoingPeerAlias
        val result = when(file.status) {
            ActiveTransferFile.Status.COMPLETED -> "sent"
            ActiveTransferFile.Status.CANCELLED -> "cancelled"
            else -> "failed"
        }
        networkExecutor.execute {
            if (!serviceDestroyed) receiveHistory.addTransfer(name, peer, file.totalBytes, true, result, message != null)
        }
    }

    private fun finishOutgoingQueues(status: ActiveTransferFile.Status) {
        val sessionIds = synchronized(outgoingFiles) {
            outgoingSessionsInProgress.toSet().also { outgoingSessionsInProgress.clear() }
        }
        val updated = synchronized(outgoingFiles) {
            finalizeOutgoingSessions(outgoingFiles, sessionIds, status)
        }
        updated.forEach { state ->
            persistOutgoingHistory(state)
            notifyListeners { it.onFileSendProgress(state) }
        }
        sessionIds.forEach { sessionId -> notifyListeners { it.onOutgoingSessionCompleted(sessionId) } }
    }

    private fun finishIncomingSession(sessionId: String, status: ActiveTransferFile.Status) {
        val updated = synchronized(incomingFiles) {
            incomingFiles[sessionId]?.values?.mapNotNull { file ->
                if (file.status != ActiveTransferFile.Status.WAITING && file.status != ActiveTransferFile.Status.TRANSFERRING) return@mapNotNull null
                file.copy(
                    receivedBytes = if (status == ActiveTransferFile.Status.COMPLETED) file.totalBytes else file.receivedBytes,
                    status = status
                ).also { incomingFiles[sessionId]?.set(file.fileId, it) }
            }.orEmpty()
        }
        updated.forEach { state ->
            if (settings.saveReceiveHistory() && status != ActiveTransferFile.Status.COMPLETED) {
                val peer = incomingSenders[sessionId]
                networkExecutor.execute {
                    if (!serviceDestroyed) receiveHistory.addTransfer(state.fileName, peer, state.totalBytes, false,
                        if (status == ActiveTransferFile.Status.CANCELLED) "cancelled" else "failed")
                }
            }
            notifyListeners { it.onFileReceiveProgress(state) }
        }
        hasActiveTransfer = hasActiveTransferEntries()
    }

    private fun hasActiveTransferEntries(): Boolean {
        val incomingActive = synchronized(incomingFiles) {
            incomingFiles.values.any { files -> files.values.any(::isActive) }
        }
        val outgoingActive = synchronized(outgoingFiles) {
            outgoingFiles.values.any { files -> files.values.any(::isActive) }
        }
        return incomingActive || outgoingActive
    }

    private fun isActive(file: ActiveTransferFile): Boolean =
        file.status == ActiveTransferFile.Status.WAITING || file.status == ActiveTransferFile.Status.TRANSFERRING

    private fun clearTransferHistory() {
        latestSessionId = null
        synchronized(incomingFiles) {
            incomingFiles.clear()
            incomingSenders.clear()
        }
        synchronized(outgoingFiles) {
            outgoingFiles.clear()
            outgoingSessionsInProgress.clear()
        }
    }

    /** Activity callbacks must always run on the main thread because they update Views. */
    private fun notifyListeners(callback: (Listener) -> Unit) {
        val snapshot = synchronized(listenerLock) { listeners.toList() }
        mainHandler.post { snapshot.forEach(callback) }
    }

    /**
     * Progress arrives once per 32KB chunk. Do not enqueue every chunk on the
     * main looper or synchronously update NotificationManager: large files can
     * otherwise starve Activity rendering and cause an ANR.
     */
    private fun dispatchProgressIfNeeded(received: Long, total: Long, callback: (Listener) -> Unit) {
        val now = android.os.SystemClock.elapsedRealtime()
        val percent = if (total > 0) ((received * 100L) / total).toInt().coerceIn(0, 100) else -1
        val shouldDispatch = synchronized(progressLock) {
            val due = now - lastProgressDispatchAt >= PROGRESS_INTERVAL_MS
            val changedEnough = percent >= 0 && percent != lastProgressDispatchPercent
            if (due || changedEnough || received >= total) {
                lastProgressDispatchAt = now
                lastProgressDispatchPercent = percent
                true
            } else false
        }
        if (!shouldDispatch) return
        notifyProgress()
        notifyListeners(callback)
    }

    private fun resetProgressDispatch() {
        synchronized(progressLock) {
            lastProgressDispatchAt = 0L
            lastProgressDispatchPercent = -1
        }
    }

    private fun resetTransferMetrics() {
        activeTransferredBytes = 0L
        activeTotalBytes = -1L
        progressStartedAt = 0L
        transferSpeedBytesPerSecond = 0L
    }

    private fun updateTransferMetrics(transferred: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (progressStartedAt == 0L || total != activeTotalBytes || transferred < activeTransferredBytes) {
            progressStartedAt = now
            transferSpeedBytesPerSecond = 0L
        }
        activeTransferredBytes = transferred
        activeTotalBytes = total
        val elapsed = now - progressStartedAt
        if (elapsed >= METRICS_MIN_SAMPLE_MS && transferred > 0L) {
            transferSpeedBytesPerSecond = transferred * 1000L / elapsed
        }
    }

    private fun resolveIncoming(accepted: Boolean) = resolveIncoming(pendingIncoming?.id, accepted)

    private fun resolveIncoming(id: String?, accepted: Boolean) {
        val pending = pendingIncoming ?: return
        if (id != pending.id) return
        resolveIncoming(id, if (accepted) IncomingReceiveOptions(settings.receiveDirectoryUri()) else null)
    }

    private fun resolveIncoming(id: String?, options: IncomingReceiveOptions?) {
        val pending = pendingIncoming ?: return
        if (id != pending.id) return
        pendingIncoming = null
        incomingOverlay.dismiss()
        notificationManager().cancel(INCOMING_NOTIFICATION_ID)
        val now = SystemClock.elapsedRealtime()
        val applied = pending.decision.resolve(id, now, options)
        if (applied && options != null && now < pending.expiresAt) {
            pending.request.messageText()?.let { recordReceivedMessage(it, pending.request.info.alias) }
        }
        notifyListeners { it.onIncomingRequestResolved() }
    }

    private fun showIncomingRequestNotification(request: IncomingTransferManager.PrepareUploadRequest) {
        val message = request.messageText()
        val files = (message ?: request.files.values.take(8).joinToString(", ") { it.fileName.take(120) }).take(800)
        val summary = if (message == null) text(R.string.receiver_request_summary,
            request.files.size, formatBytes(request.files.values.sumOf { it.size })) else files
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(if (message == null) R.string.notification_incoming_title else R.string.incoming_message_title, request.info.alias))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$files"))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, text(R.string.incoming_request_accept), if (message == null) acceptIncomingIntent() else openAppIntent())
            .addAction(0, text(R.string.incoming_request_reject), rejectIncomingIntent())
            .build()
        notificationManager().notify(INCOMING_NOTIFICATION_ID, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the UI must not disable the explicitly enabled receiver.
        if (!settings.backgroundReceiverEnabled()) cancelCurrent(stopService = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(connectivityReceiver)
        TransferServiceState.markRunning(false, this)
        resolveIncoming(false)
        endAllScreenAwakeSessions()
        cancellationExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        discoveryManager?.stop()
        clearTransferHistory()
        if (::receiveHistory.isInitialized) receiveHistory.close()
        stopForegroundAndRemoveNotification()
        notificationManager().cancel(NOTIFICATION_ID)
        notificationManager().cancel(PROGRESS_NOTIFICATION_ID)
        notificationManager().cancel(INCOMING_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun refreshLanguage() {
        notificationGeneration++
        createNotificationChannels()
        if (hasActiveTransfer) {
            // Progress callbacks will supply the next detailed title in the new language.
            activeNotificationTitle = text(R.string.notification_service_title)
            notifyProgress()
        } else {
            startForeground(NOTIFICATION_ID, baseNotification())
        }
        pendingIncoming?.let { showIncomingRequestNotification(it.request) }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = notificationManager()
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_TRANSFER, text(R.string.notification_channel_transfer), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = text(R.string.notification_channel_transfer_description)
                setSound(null, null)
                enableVibration(false)
            })
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_INCOMING, text(R.string.notification_channel_incoming), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = text(R.string.notification_channel_incoming_description)
            })
        }
    }

    private fun baseNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_TRANSFER)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(text(R.string.notification_service_title))
        .setContentText(text(R.string.notification_service_text))
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setProgress(0, 0, false)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(0, text(R.string.receiver_stop), PendingIntent.getService(this, 4,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP_SERVICE), pendingIntentFlags()))
        .build()

    private fun notifyProgress() {
        val generation = notificationGeneration
        val title = activeNotificationTitle ?: text(R.string.notification_service_title)
        val progress = activeNotificationProgress
        val transferred = activeTransferredBytes
        val total = activeTotalBytes
        val speed = transferSpeedBytesPerSecond
        mainHandler.post {
            if (generation != notificationGeneration || !hasActiveTransfer || !settings.backgroundReceiverEnabled() || serviceDestroyed) return@post
            val firstLine = if (total > 0L) {
                "${progress}% (${formatBytes(transferred)}/${formatBytes(total)})"
            } else {
                "${progress}% (${formatBytes(transferred)})"
            }
            val secondLine = if (speed > 0L && total >= transferred) {
                val remaining = (total - transferred) / speed
                text(R.string.notification_eta_speed, formatDuration(remaining), formatBytes(speed))
            } else {
                text(R.string.notification_eta_speed, "--:--", formatBytes(speed))
            }
            val builder = NotificationCompat.Builder(this, CHANNEL_TRANSFER)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(firstLine)
                .setSubText(secondLine)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, text(R.string.cancel_transfer), cancelIntent())
                .setStyle(NotificationCompat.BigTextStyle().bigText("$firstLine\n$secondLine"))
                .setProgress(100, progress, false)
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    private fun clearTransferNotification() {
        val generation = ++notificationGeneration
        mainHandler.post {
            if (generation != notificationGeneration || !settings.backgroundReceiverEnabled() || serviceDestroyed) return@post
            notificationManager().cancel(NOTIFICATION_ID)
            notificationManager().cancel(PROGRESS_NOTIFICATION_ID)
            // Return the foreground service to its idle notification after the
            // transfer progress has been removed.
            startForeground(NOTIFICATION_ID, baseNotification())
        }
    }

    private fun removeNotificationsAndStopForeground() {
        notificationManager().cancel(NOTIFICATION_ID)
        notificationManager().cancel(PROGRESS_NOTIFICATION_ID)
        notificationManager().cancel(INCOMING_NOTIFICATION_ID)
        stopForegroundAndRemoveNotification()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundAndRemoveNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun beginScreenAwakeSession(sessionId: String) {
        synchronized(screenAwakeLock) {
            activeScreenAwakeSessions += sessionId
            updateScreenAwakeLockLocked()
        }
    }

    private fun endScreenAwakeSession(sessionId: String) {
        synchronized(screenAwakeLock) {
            activeScreenAwakeSessions -= sessionId
            updateScreenAwakeLockLocked()
        }
    }

    private fun endAllScreenAwakeSessions() {
        synchronized(screenAwakeLock) {
            activeScreenAwakeSessions.clear()
            updateScreenAwakeLockLocked()
        }
    }

    private fun updateScreenAwakeLock() {
        synchronized(screenAwakeLock) {
            updateScreenAwakeLockLocked()
        }
    }

    @Suppress("DEPRECATION", "WakelockTimeout")
    private fun updateScreenAwakeLockLocked() {
        mainHandler.removeCallbacks(renewCpuWakeLock)
        if (activeScreenAwakeSessions.isNotEmpty() && !serviceDestroyed) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (cpuWakeLock?.isHeld != true) cpuWakeLock = power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "$packageName:active-transfer"
            ).apply { setReferenceCounted(false) }
            cpuWakeLock?.acquire(120_000L)
            mainHandler.postDelayed(renewCpuWakeLock, 60_000L)
        } else {
            cpuWakeLock?.let { if (it.isHeld) it.release() }
            cpuWakeLock = null
        }
        val shouldHold = settings.keepScreenAwakeDuringTransfer() && activeScreenAwakeSessions.isNotEmpty()
        if (shouldHold && wakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "$packageName:transfer-screen-awake"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!shouldHold) {
            wakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
            wakeLock = null
        }
    }

    @Suppress("DEPRECATION")
    private fun notificationManager(): NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingIntentFlags())
    private fun cancelIntent(): PendingIntent = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL), pendingIntentFlags())
    private fun incomingIntent(action: String, code: Int): PendingIntent {
        val id = pendingIncoming?.id.orEmpty()
        return PendingIntent.getService(this, code, Intent(this, TransferService::class.java)
            .setAction(action).setData(Uri.parse("localsend-incoming:$id"))
            .putExtra(EXTRA_REQUEST_ID, id), pendingIntentFlags())
    }
    private fun acceptIncomingIntent(): PendingIntent = incomingIntent(ACTION_ACCEPT_INCOMING, 2)
    private fun rejectIncomingIntent(): PendingIntent = incomingIntent(ACTION_REJECT_INCOMING, 3)
    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        const val ACTION_CANCEL = "io.github.landrop.CANCEL_TRANSFER"
        const val ACTION_ANNOUNCE_IDENTITY = "io.github.landrop.ANNOUNCE_IDENTITY"
        const val ACTION_RELOAD_SETTINGS = "io.github.landrop.RELOAD_SETTINGS"
        const val ACTION_REFRESH_LANGUAGE = "io.github.landrop.REFRESH_LANGUAGE"
        const val ACTION_REFRESH_SCREEN_AWAKE = "io.github.landrop.REFRESH_SCREEN_AWAKE"
        const val ACTION_STOP_SERVICE = "io.github.landrop.STOP_SERVICE"
        private const val ACTION_ACCEPT_INCOMING = "io.github.landrop.ACCEPT_INCOMING"
        private const val ACTION_REJECT_INCOMING = "io.github.landrop.REJECT_INCOMING"
        private const val CHANNEL_TRANSFER = "transfer_progress"
        private const val CHANNEL_INCOMING = "incoming_confirmation_v2"
        private const val EXTRA_REQUEST_ID = "incoming_request_id"
        private const val NOTIFICATION_ID = 53317
        private const val PROGRESS_NOTIFICATION_ID = 53319
        private const val INCOMING_NOTIFICATION_ID = 53318
        private const val RESULT_NOTIFICATION_ID = 53320
        private const val PROGRESS_INTERVAL_MS = 200L
        private const val METRICS_MIN_SAMPLE_MS = 500L
        private const val CANCEL_SHUTDOWN_DELAY_MS = 1500L
    }

    private val progressLock = Any()

    private data class PendingIncoming(
        val request: IncomingTransferManager.PrepareUploadRequest,
        val decide: (IncomingReceiveOptions?) -> Unit,
        val id: String = UUID.randomUUID().toString(),
        val expiresAt: Long = SystemClock.elapsedRealtime() + IncomingTransferManager.DECISION_TIMEOUT_SECONDS * 1000
    ) {
        val decision = TimedIncomingDecision(id, expiresAt, decide)
    }
}
