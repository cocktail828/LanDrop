package io.github.landrop.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import io.github.landrop.model.RemoteDevice
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class IncomingDecisionRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val peer = DeviceInfo("Recovery test", "2.2", "test", "desktop", "recovery-peer", 53317, "http")

    @Test fun confirmationTimeoutReleasesServiceAndOldReplyCannotAffectRetry() = withReceiver { service, offers, manager ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(message(), "192.0.2.1") }
            val old = requireNotNull(offers.poll(3, TimeUnit.SECONDS))
            assertNull(first.get(3, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertFalse("Timed-out confirmation kept the receiver busy", service.isBusy())
            val retry = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(message(), "192.0.2.1") }
            val current = requireNotNull(offers.poll(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { old.reply(IncomingReceiveOptions(null)) }
            assertFalse(retry.isDone)
            instrumentation.runOnMainSync { current.reply(IncomingReceiveOptions(null)) }
            assertNotNull(retry.get(3, TimeUnit.SECONDS))
            assertFalse(service.isBusy())
        } finally { worker.shutdownNow() }
    }

    @Test fun interruptedConfirmationReleasesServiceForNextRequest() = withReceiver { service, offers, manager ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(message(), "192.0.2.1") }
            requireNotNull(offers.poll(3, TimeUnit.SECONDS))
            first.cancel(true)
            // The next worker task starts after prepare's interruption cleanup finishes.
            worker.submit { }.get(3, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            assertFalse("Interrupted confirmation kept the receiver busy", service.isBusy())
            val retry = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(message(), "192.0.2.1") }
            val current = requireNotNull(offers.poll(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { current.reply(IncomingReceiveOptions(null)) }
            assertNotNull(retry.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun expiredUiTimerCannotBlockNextRequestAfterSleep() = withReceiver { service, offers, _ ->
        val oldReplies = mutableListOf<IncomingReceiveOptions?>()
        val currentReplies = mutableListOf<IncomingReceiveOptions?>()
        instrumentation.runOnMainSync {
            service.onIncomingTransferRequest(message(), oldReplies::add)
            val field = TransferService::class.java.getDeclaredField("pendingIncoming").apply { isAccessible = true }
            val pending = requireNotNull(field.get(service))
            // Simulate elapsed time advancing during sleep before the Handler timer runs.
            pending.javaClass.getDeclaredField("expiresAt").apply { isAccessible = true }
                .setLong(pending, android.os.SystemClock.elapsedRealtime() - 1)
            service.onIncomingTransferRequest(message(), currentReplies::add)
        }
        assertEquals(listOf<IncomingReceiveOptions?>(null), oldReplies)
        assertTrue("New request was rejected by an expired confirmation", currentReplies.isEmpty())
        assertEquals(2, offers.size)
        instrumentation.runOnMainSync { offers.last().reply(null) }
        assertFalse(service.isBusy())
    }

    @Test fun retryReplacesAcceptedSessionThatNeverStartedUploading() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        withReceiver(timeoutMillis = 3_000) { service, offers, manager ->
            val worker = Executors.newSingleThreadExecutor()
            try {
                val first = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(file(), "192.0.2.1") }
                val firstOffer = requireNotNull(offers.poll(3, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { firstOffer.reply(IncomingReceiveOptions(null)) }
                val previous = requireNotNull(first.get(3, TimeUnit.SECONDS))
                assertTrue(service.isBusy())
                val next = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(file(), "192.0.2.1") }
                val nextOffer = offers.poll(2, TimeUnit.SECONDS)
                assertNotNull("Retry was rejected instead of replacing the abandoned session", nextOffer)
                instrumentation.runOnMainSync { nextOffer!!.reply(IncomingReceiveOptions(null)) }
                val current = requireNotNull(next.get(3, TimeUnit.SECONDS))
                assertEquals(IncomingTransferManager.WriteResult.CANCELLED,
                    manager.write(previous.sessionId, "file", previous.files.getValue("file"), ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, false))
                assertEquals(IncomingTransferManager.WriteResult.COMPLETED,
                    manager.write(current.sessionId, "file", current.files.getValue("file"), ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, false))
                instrumentation.waitForIdleSync()
                assertFalse(service.isBusy())
            } finally { worker.shutdownNow() }
        }
    }

    @Test fun retryDoesNotCancelAFileWhoseUploadAlreadyStarted() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        withReceiver(timeoutMillis = 3_000) { service, offers, manager ->
            val workers = Executors.newFixedThreadPool(2)
            val reading = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                val prepared = workers.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(file(), "192.0.2.1") }
                val offer = requireNotNull(offers.poll(3, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { offer.reply(IncomingReceiveOptions(null)) }
                val session = requireNotNull(prepared.get(3, TimeUnit.SECONDS))
                val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        reading.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                }
                val upload = workers.submit<IncomingTransferManager.WriteResult> {
                    manager.write(session.sessionId, "file", session.files.getValue("file"), input, 3, false)
                }
                assertTrue(reading.await(3, TimeUnit.SECONDS))
                assertNull(workers.submit<IncomingTransferManager.PrepareUploadResponse?> {
                    manager.prepare(file(), "192.0.2.1")
                }.get(3, TimeUnit.SECONDS))
                assertTrue(offers.isEmpty())
                assertTrue(service.isBusy())
                release.countDown()
                assertEquals(IncomingTransferManager.WriteResult.COMPLETED, upload.get(3, TimeUnit.SECONDS))
            } finally { release.countDown(); workers.shutdownNow() }
        }
    }

    private fun message() = IncomingTransferManager.PrepareUploadRequest(peer, mapOf(
        "text" to IncomingTransferManager.IncomingFile("text", "message.txt", 5, "text/plain", null, "hello")))

    private fun file() = IncomingTransferManager.PrepareUploadRequest(peer, mapOf(
        "file" to IncomingTransferManager.IncomingFile("file", "recovery-${System.nanoTime()}.bin", 3, "application/octet-stream", null)))

    private fun withReceiver(timeoutMillis: Long = 300, test: (TransferService, LinkedBlockingQueue<Offer>, IncomingTransferManager) -> Unit) {
        val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val previous = preferences.all
        preferences.edit().putBoolean("background_receiver", true).putBoolean("server_enabled", false)
            .putBoolean("save_receive_history", false).commit()
        val ready = CountDownLatch(1)
        lateinit var service: TransferService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as TransferService.LocalBinder).service()
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val offers = LinkedBlockingQueue<Offer>()
        val listener = Recorder(offers)
        val saved = mutableListOf<ReceivedFile>()
        assertTrue(context.bindService(Intent(context, TransferService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val manager = IncomingTransferManager(context,
            onTransferRequested = { request, reply -> instrumentation.runOnMainSync { service.onIncomingTransferRequest(request, reply) } },
            onSessionPrepared = { id, request -> instrumentation.runOnMainSync { service.onIncomingSessionPrepared(id, request) } },
            onTransferCancelRequested = { _, _, _ -> },
            onFileProgress = { id, file, name, received, total -> instrumentation.runOnMainSync { service.onFileReceiveProgress(id, file, name, received, total) } },
            onFileReceiveCancelled = { id, file, name, complete -> instrumentation.runOnMainSync { service.onFileReceiveCancelled(id, file, name, complete) } },
            onFileReceived = { id, file, received, complete -> saved += received; instrumentation.runOnMainSync { service.onFileReceived(id, file, received, complete) } },
            onPreparationCancelled = { request -> instrumentation.runOnMainSync { service.onIncomingPreparationCancelled(request) } },
            decisionTimeoutMillis = timeoutMillis,
            replaceUnstartedSessionsOnRetry = true)
        try {
            instrumentation.runOnMainSync { service.addListener(listener) }
            instrumentation.waitForIdleSync()
            test(service, offers, manager)
        } finally {
            manager.cancelAll()
            instrumentation.runOnMainSync { service.removeListener(listener) }
            context.unbindService(connection)
            context.stopService(Intent(context, TransferService::class.java))
            instrumentation.waitForIdleSync()
            saved.forEach { context.contentResolver.delete(it.uri, null, null) }
            preferences.edit().apply {
                for (key in listOf("background_receiver", "server_enabled", "save_receive_history")) {
                    val value = previous[key]
                    if (value is Boolean) putBoolean(key, value) else remove(key)
                }
            }.commit()
        }
    }

    private data class Offer(val request: IncomingTransferManager.PrepareUploadRequest, val reply: (IncomingReceiveOptions?) -> Unit)

    private class Recorder(private val offers: LinkedBlockingQueue<Offer>) : TransferService.Listener {
        override fun onIncomingTransferRequest(request: IncomingTransferManager.PrepareUploadRequest, decide: (IncomingReceiveOptions?) -> Unit) { offers.add(Offer(request, decide)) }
        override fun onDevicesChanged(devices: List<RemoteDevice>) = Unit
        override fun onDiscoveryError(message: String) = Unit
        override fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest) = Unit
        override fun onOutgoingSessionPreparing(sessionId: String, files: List<ActiveTransferFile>) = Unit
        override fun onOutgoingChecksumProgress(sessionId: String, current: Int, total: Int) = Unit
        override fun onOutgoingSessionPrepared(sessionId: String, files: List<ActiveTransferFile>) = Unit
        override fun onOutgoingSessionStarted(preparationSessionId: String, sessionId: String, files: List<ActiveTransferFile>) = Unit
        override fun onActiveTransfersRestored(files: List<ActiveTransferFile>) = Unit
        override fun onFileReceiveProgress(file: ActiveTransferFile) = Unit
        override fun onFileSendProgress(file: ActiveTransferFile) = Unit
        override fun onFileReceiveCancelled(file: ActiveTransferFile, sessionComplete: Boolean) = Unit
        override fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean) = Unit
        override fun onIncomingSessionCompleted(sessionId: String) = Unit
        override fun onOutgoingSessionCompleted(sessionId: String) = Unit
        override fun onUploadStatus(message: String) = Unit
        override fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit) { reply(null) }
        override fun onUploadProgress(fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long) = Unit
        override fun onTransferStateRestored(title: String, percent: Int) = Unit
        override fun onTransferFinished(message: String) = Unit
        override fun onUploadCompleted(names: List<String>) = Unit
        override fun onUploadError(message: String) = Unit
    }
}
