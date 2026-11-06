package io.github.landrop.transfer

import android.content.ContentResolver
import android.content.Context
import androidx.annotation.StringRes
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import io.github.landrop.discovery.LocalIdentity
import io.github.landrop.model.RemoteDevice
import io.github.landrop.protocol.LocalSendProtocol
import io.github.landrop.R
import io.github.landrop.settings.AppLocale
import io.github.landrop.settings.AppSettings
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.security.MessageDigest
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS

class UploadClient(context: Context, private val identity: LocalIdentity) {
    private val appContext = context.applicationContext
    private fun text(@StringRes resource: Int, vararg arguments: Any): String = AppLocale.text(appContext, resource, *arguments)
    private val resolver: ContentResolver = appContext.contentResolver
    private val settings = AppSettings(appContext)
    private val httpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(8, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val peers by lazy { io.github.landrop.security.TlsPeerClient(
        io.github.landrop.security.LocalTlsIdentity.load(appContext), httpClient) }
    private val gson = Gson()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cancelExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val activeLock = Any()
    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var activePreparationId: String? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeDevice: RemoteDevice? = null
    @Volatile private var activeCancelFlag: AtomicBoolean? = null

    fun send(uri: Uri, device: RemoteDevice, listener: Listener) = send(listOf(uri), device, null, emptyMap(), listener)

    fun send(uris: List<Uri>, device: RemoteDevice, listener: Listener) = send(uris, device, null, emptyMap(), listener)

    fun send(uris: List<Uri>, device: RemoteDevice, messageText: String?, relativeNames: Map<Uri, String>, listener: Listener) {
        executor.execute {
            val cancelled = AtomicBoolean(false)
            try {
                require(uris.isNotEmpty()) { text(R.string.select_file_first) }
                synchronized(activeLock) {
                    activeDevice = device
                    activeCancelFlag = cancelled
                }
                listener.onStatus(text(R.string.upload_preparing_files, uris.size))
                val filesWithoutChecksums = uris.mapIndexed { index, uri ->
                    if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                    listener.onStatus(text(R.string.upload_preparing_file, index + 1, uris.size))
                    val file = readFile(uri, messageText.takeIf { uris.size == 1 })
                    file.copy(id = UUID.randomUUID().toString(), fileName = relativeNames[uri]?.let { TransferPath.segments(it).joinToString("/") } ?: file.fileName)
                }
                val preparationSessionId = "preparing-${UUID.randomUUID()}"
                synchronized(activeLock) { activePreparationId = preparationSessionId }
                listener.onPreparationStarted(preparationSessionId, filesWithoutChecksums.map { it.toQueueFile() })
                val files = if (settings.createChecksums()) {
                    filesWithoutChecksums.mapIndexed { index, file ->
                        if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                        listener.onChecksumProgress(preparationSessionId, index + 1, filesWithoutChecksums.size)
                        file.copy(sha256 = sha256(file.uri) { cancelled.get() })
                    }
                } else {
                    filesWithoutChecksums
                }
                if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                listener.onStatus(text(R.string.upload_requesting_receiver, device.alias))
                val prepareResult = prepareWithPin(device, files, listener, cancelled)
                if (prepareResult is PrepareResult.NoTransfer) {
                    if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                    // Message requests carry their content in the prepare-upload
                    // preview and are acknowledged with HTTP 204. No upload
                    // session or file body is created in that case.
                    listener.onCompleted(files.map { it.fileName })
                    return@execute
                }
                val prepared = (prepareResult as PrepareResult.Success).response
                activeTransfers[prepared.sessionId] = cancelled
                synchronized(activeLock) { activeSessionId = prepared.sessionId }
                val acceptedFiles = files.filter { it.id in prepared.files }
                if (acceptedFiles.isEmpty()) throw IllegalStateException(text(R.string.upload_not_accepted))
                if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                listener.onSessionPrepared(preparationSessionId, prepared.sessionId, acceptedFiles.map { it.toQueueFile() })
                try {
                    var totalSent = 0L
                    val totalBytes = acceptedFiles.sumOf { it.size }
                    acceptedFiles.forEachIndexed { index, file ->
                        val token = prepared.files.getValue(file.id)
                        listener.onStatus(text(R.string.upload_sending_file, index + 1, acceptedFiles.size, file.fileName))
                        upload(device, prepared.sessionId, file.id, token, file, cancelled) { sent ->
                            listener.onProgress(prepared.sessionId, file.id, file.fileName, index, acceptedFiles.size, sent, file.size, totalSent + sent, totalBytes)
                        }
                        totalSent += file.size
                        listener.onFileCompleted(prepared.sessionId, file.id)
                    }
                } finally {
                    activeTransfers.remove(prepared.sessionId, cancelled)
                }
                listener.onCompleted(acceptedFiles.map { it.fileName })
            } catch (exception: Exception) {
                val wasCancelled = cancelled.get()
                // Clean up the peer's remaining targets after a terminal upload failure.
                if (activeSessionId != null) cancelCurrent()
                if (wasCancelled) listener.onCancelled(text(R.string.upload_cancelled))
                else listener.onError(uploadErrorResource(exception)?.let { text(it) }
                    ?: exception.message ?: text(R.string.upload_failed))
            } finally {
                synchronized(activeLock) {
                    activeSessionId?.let { activeTransfers.remove(it, cancelled) }
                    activeCall = null
                    activeDevice = null
                    activeSessionId = null
                    activePreparationId = null
                    activeCancelFlag = null
                }
            }
        }
    }

    fun cancelCurrent() { cancelActive(null) }

    /** A stale row/dialog must never cancel a different, subsequently started upload. */
    fun cancelSession(sessionId: String): Boolean = cancelActive(sessionId)

    private fun cancelActive(expectedId: String?): Boolean {
        val target = synchronized(activeLock) {
            if (expectedId != null && expectedId != activeSessionId && expectedId != activePreparationId) return false
            val flag = activeCancelFlag ?: return false
            val device = activeDevice ?: return false
            flag.set(true)
            CancellationTarget(device, activeSessionId, activeCall)
        }
        target.call?.cancel()
        if (target.sessionId == null && target.call == null) return true
        cancelExecutor.execute {
            try {
                val endpoint = url(target.device, LocalSendProtocol.CANCEL_PATH).newBuilder().apply {
                    target.sessionId?.let { addQueryParameter("sessionId", it) }
                }.build()
                val request = Request.Builder().url(endpoint).post(ByteArray(0).toRequestBody()).build()
                client(target.device).newCall(request).execute().use { }
            } catch (_: Exception) { /* The local cancellation flag remains effective offline. */ }
        }
        return true
    }

    private fun prepareWithPin(
        device: RemoteDevice,
        files: List<FileInfo>,
        listener: Listener,
        cancelled: AtomicBoolean
    ): PrepareResult {
        var pin: String? = null
        var attempt = 0
        while (true) {
            if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
            when (val result = prepare(device, files, pin, cancelled)) {
                is PrepareResult.Success, PrepareResult.NoTransfer -> return result
                PrepareResult.PinRequired -> {
                    attempt++
                    if (attempt > MAX_PIN_ATTEMPTS) {
                        throw IllegalStateException(text(R.string.pin_too_many_attempts))
                    }
                    pin = awaitPin(device, attempt, listener, cancelled)
                        ?: throw IllegalStateException(text(R.string.pin_input_cancelled))
                }
            }
        }
    }

    private fun prepare(device: RemoteDevice, files: List<FileInfo>, pin: String?, cancelled: AtomicBoolean): PrepareResult {
        val prepareUrl = url(device, LocalSendProtocol.PREPARE_UPLOAD_PATH).newBuilder().apply {
            pin?.let { addQueryParameter(LocalSendProtocol.PIN_QUERY_PARAMETER, it) }
        }.build()
        val request = Request.Builder()
            .url(prepareUrl)
            .post(gson.toJson(PrepareRequest(identity.deviceInfo(), files.associateBy { it.id })).toRequestBody(JSON))
            .build()
        val call = client(device).newCall(request)
        synchronized(activeLock) { activeCall = call }
        if (cancelled.get()) call.cancel()
        try { call.execute().use { response ->
            if (response.code == 401) return PrepareResult.PinRequired
            if (response.code == 429) throw IllegalStateException(text(R.string.pin_too_many_attempts))
            if (response.code == 204) return PrepareResult.NoTransfer
            if (!response.isSuccessful) throw IllegalStateException(text(R.string.upload_request_rejected, response.code))
            return PrepareResult.Success(
                gson.fromJson(response.body?.charStream(), PrepareResponse::class.java)
                    ?: throw IllegalStateException(text(R.string.upload_invalid_response))
            )
        } } finally { synchronized(activeLock) { if (activeCall === call) activeCall = null } }
    }

    private fun awaitPin(device: RemoteDevice, attempt: Int, listener: Listener, cancelled: AtomicBoolean): String? {
        val latch = CountDownLatch(1)
        val enteredPin = java.util.concurrent.atomic.AtomicReference<String?>()
        listener.onPinRequired(device, attempt) { pin ->
            enteredPin.set(pin?.trim()?.takeIf { it.isNotEmpty() })
            latch.countDown()
        }
        val deadline = System.currentTimeMillis() + PIN_DIALOG_TIMEOUT_MS
        while (!cancelled.get() && System.currentTimeMillis() < deadline) {
            if (latch.await(PIN_WAIT_POLL_MS, MILLISECONDS)) break
        }
        return if (cancelled.get()) null else enteredPin.get()
    }

    private fun upload(device: RemoteDevice, sessionId: String, fileId: String, token: String, file: FileInfo, cancelled: AtomicBoolean, onProgress: (Long) -> Unit) {
        val uploadUrl = url(device, LocalSendProtocol.UPLOAD_PATH).newBuilder()
            .addQueryParameter("sessionId", sessionId).addQueryParameter("fileId", fileId).addQueryParameter("token", token).build()
        for (attempt in 1..3) {
            val body = StreamBody(
                file.fileType,
                file.size,
                source = { resolver.openInputStream(file.uri) ?: throw IllegalStateException(text(R.string.upload_cannot_read_file)) },
                progress = onProgress,
                shouldCancel = { cancelled.get() }
            )
            val request = Request.Builder().url(uploadUrl).post(body).build()
            val call = client(device).newCall(request)
            synchronized(activeLock) { activeCall = call }
            if (cancelled.get()) call.cancel()
            try { call.execute().use { response ->
                if (response.code == 422 && attempt < 3 && !cancelled.get()) return@use
                if (!response.isSuccessful) throw IllegalStateException(text(R.string.upload_http_failed, response.code))
                if (cancelled.get()) throw IOException(text(R.string.upload_cancelled))
                return
            } } finally { synchronized(activeLock) { if (activeCall === call) activeCall = null } }
        }
    }

    private fun client(device: RemoteDevice): OkHttpClient {
        return peers.client(device.protocol, device.fingerprint)
    }

    private fun url(device: RemoteDevice, path: String): HttpUrl {
        return io.github.landrop.discovery.DeviceEndpoint(device.address, device.port)
            .url(device.protocol, path).toHttpUrl()
    }

    private fun readFile(uri: Uri, preview: String? = null): FileInfo {
        var name = "shared-file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) { name = cursor.getString(0) ?: name; size = cursor.getLong(1) }
        }
        if (size < 0) throw IllegalArgumentException(text(R.string.upload_unknown_file_size))
        return FileInfo("", uri, name, size, resolver.getType(uri) ?: "application/octet-stream", null, preview)
    }

    private fun sha256(uri: Uri, shouldCancel: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                if (shouldCancel()) throw IOException(text(R.string.upload_cancelled))
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: throw IllegalStateException(text(R.string.upload_cannot_read_file))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    interface Listener {
        fun onStatus(message: String)
        fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit)
        fun onPreparationStarted(sessionId: String, files: List<QueueFile>)
        fun onChecksumProgress(sessionId: String, current: Int, total: Int)
        fun onSessionPrepared(preparationSessionId: String, sessionId: String, files: List<QueueFile>)
        fun onProgress(sessionId: String, fileId: String, fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long)
        fun onFileCompleted(sessionId: String, fileId: String)
        fun onCompleted(names: List<String>)
        fun onError(message: String)
        fun onCancelled(message: String) = onError(message)
    }
    data class QueueFile(val id: String, val fileName: String, val size: Long)
    private data class CancellationTarget(val device: RemoteDevice, val sessionId: String?, val call: okhttp3.Call?)
    private data class PrepareRequest(val info: Any, val files: Map<String, FileInfo>)
    private data class PrepareResponse(val sessionId: String, val files: Map<String, String>)
    private sealed interface PrepareResult {
        data class Success(val response: PrepareResponse) : PrepareResult
        data object NoTransfer : PrepareResult
        data object PinRequired : PrepareResult
    }
    private data class FileInfo(val id: String, @Transient val uri: Uri, val fileName: String, val size: Long, val fileType: String, val sha256: String?, val preview: String? = null)
    private fun FileInfo.toQueueFile() = QueueFile(id, fileName, size)
    private inner class StreamBody(private val type: String, private val length: Long, private val source: () -> java.io.InputStream, private val progress: (Long) -> Unit, private val shouldCancel: () -> Boolean) : RequestBody() {
        override fun contentType(): MediaType? = type.toMediaTypeOrNull()
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) { source().use { input -> val buffer = ByteArray(BUFFER_SIZE); var sent = 0L; while (true) { if (shouldCancel()) throw IOException(text(R.string.upload_cancelled)); val count = input.read(buffer); if (count < 0) break; sink.write(buffer, 0, count); sent += count; progress(sent) } } }
    }
    companion object {
        private val activeTransfers = ConcurrentHashMap<String, AtomicBoolean>()
        fun cancel(sessionId: String): Boolean = activeTransfers[sessionId]?.let { it.set(true); true } == true
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val BUFFER_SIZE = 32 * 1024
        private const val MAX_PIN_ATTEMPTS = 3
        private const val PIN_DIALOG_TIMEOUT_MS = 120_000L
        private const val PIN_WAIT_POLL_MS = 250L
    }
}
