package io.github.landrop.transfer

import android.content.Context
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import io.github.landrop.settings.AppSettings
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Stores incoming LocalSend uploads without buffering entire files in memory. */
class IncomingTransferManager(
    context: Context,
    private val onTransferRequested: (PrepareUploadRequest, (IncomingReceiveOptions?) -> Unit) -> Unit,
    private val onSessionPrepared: (String, PrepareUploadRequest) -> Unit,
    private val onTransferCancelRequested: (DeviceInfo, String, String) -> Unit,
    private val onFileProgress: (String, String, String, Long, Long) -> Unit,
    private val onFileReceiveCancelled: (String, String, String, Boolean) -> Unit,
    private val onFileReceived: (String, String, ReceivedFile, Boolean) -> Unit,
    private val onPreparationCancelled: (PrepareUploadRequest) -> Unit = {},
    private val onFileReceiveFailed: (String, String, String, Boolean) -> Unit = onFileReceiveCancelled
) {
    private val fileStore = IncomingFileStore(context)
    private val settings = AppSettings(context)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val lifecycleLock = Any()
    private var cancellationGeneration = 0L
    // Use the map keys as a set; ConcurrentHashMap.newKeySet() requires API 24.
    private val cancelledSessions = ConcurrentHashMap<String, Boolean>()

    fun prepare(request: PrepareUploadRequest, remoteAddress: String): PrepareUploadResponse? {
        val decisionLatch = CountDownLatch(1)
        val decisionReceived = AtomicBoolean(false)
        val optionsReference = AtomicReference<IncomingReceiveOptions?>(null)
        val pendingId = UUID.randomUUID().toString()
        val pendingRequest = PendingRequest(request, remoteAddress) {
            if (decisionReceived.compareAndSet(false, true)) {
                optionsReference.set(null)
                decisionLatch.countDown()
                onPreparationCancelled(request)
            }
        }
        val generation = synchronized(lifecycleLock) {
            pending[pendingId] = pendingRequest
            cancellationGeneration
        }
        try {
            onTransferRequested(request) { options ->
                if (decisionReceived.compareAndSet(false, true)) {
                    optionsReference.set(options)
                    decisionLatch.countDown()
                }
            }
            if (!decisionLatch.await(DECISION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                decisionReceived.set(true)
                return null
            }
        } finally {
            pending.remove(pendingId)
        }
        val options = optionsReference.get() ?: return null
        val selectedFiles = request.files
        if (selectedFiles.isEmpty()) return null

        // LocalSend messages are represented by one text item whose content
        // is carried in the optional preview field. They are acknowledged at
        // prepare time and must never create a .txt destination file.
        if (request.messageText() != null) {
            return PrepareUploadResponse("", emptyMap())
        }

        val sessionId = UUID.randomUUID().toString()
        val targets = LinkedHashMap<String, Target>()
        try {
            selectedFiles.forEach { (id, file) ->
                val token = UUID.randomUUID().toString()
                targets[id] = Target(token, file, fileStore.create(file.fileName, file.fileType, file.size,
                    options.receiveDirectoryUri))
            }
        } catch (_: Exception) {
            targets.values.forEach { runCatching { fileStore.discard(it.destination) } }
            return null
        }
        val prepared = synchronized(lifecycleLock) {
            if (generation != cancellationGeneration) false else {
                sessions[sessionId] = Session(targets, request.info, remoteAddress)
                activeSessionId = sessionId
                onSessionPrepared(sessionId, request)
                true
            }
        }
        if (!prepared) {
            targets.values.forEach { runCatching { fileStore.discard(it.destination) } }
            return null
        }
        return PrepareUploadResponse(sessionId, targets.mapValues { it.value.token })
    }

    fun write(
        sessionId: String,
        fileId: String,
        token: String,
        input: InputStream,
        contentLength: Long?,
        isChunked: Boolean
    ): WriteResult {
        val session = sessions[sessionId]
        val target = session?.files?.get(fileId)
            ?: return if (cancelledSessions.remove(sessionId) == true) WriteResult.CANCELLED else WriteResult.REJECTED
        return synchronized(target) {
            if (!isSessionActive(sessionId, fileId, target) || target.completed || target.cancelled) WriteResult.REJECTED
            else writeTarget(sessionId, fileId, token, input, contentLength, isChunked, target)
        }
    }

    private fun writeTarget(sessionId: String, fileId: String, token: String, input: InputStream,
                            contentLength: Long?, isChunked: Boolean, target: Target): WriteResult {
        if (target.token != token || contentLength != null && contentLength != target.file.size) return WriteResult.REJECTED
        val digest = MessageDigest.getInstance("SHA-256")
        val source = if (isChunked) ChunkedInputStream(input) else input
        try {
            fileStore.withOutput(target.destination) { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var received = 0L
                if (isChunked) {
                    // Chunked bodies have no Content-Length. Read through the terminal chunk.
                    while (true) {
                        if (!isSessionActive(sessionId, fileId, target)) return discardAsCancelled(sessionId, target)
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (received + count > target.file.size) {
                            return discardAsCancelled(sessionId, target, failed = true)
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        onFileProgress(sessionId, fileId, target.file.fileName, received, target.file.size)
                    }
                } else {
                    // A keep-alive HTTP connection does not close after the request body.
                    // For a Content-Length request, stop exactly at the declared file size.
                    while (received < target.file.size) {
                        if (!isSessionActive(sessionId, fileId, target)) return discardAsCancelled(sessionId, target)
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), target.file.size - received).toInt())
                        if (count < 0) {
                            return discardAsCancelled(sessionId, target, failed = true)
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        onFileProgress(sessionId, fileId, target.file.fileName, received, target.file.size)
                    }
                }
                if (received != target.file.size) {
                    return discardAsCancelled(sessionId, target, failed = true)
                }
            }
        } catch (_: Exception) {
            return discardAsCancelled(sessionId, target, failed = isSessionActive(sessionId, fileId, target))
        }
        if (!isSessionActive(sessionId, fileId, target)) return discardAsCancelled(sessionId, target)
        if (settings.verifyReceivedChecksums() && target.file.sha256 != null && !target.file.sha256.equals(digest.digest().joinToString("") { "%02x".format(it) }, true)) {
            target.checksumFailures++
            if (target.checksumFailures >= 3) discardAsCancelled(sessionId, target, failed = true)
            // Keep the same session and token for the sender's whole-file retry.
            // The destination remains unpublished until a matching digest arrives.
            return WriteResult.CHECKSUM_MISMATCH
        }
        try {
            fileStore.complete(target.destination)
        } catch (_: Exception) {
            return discardAsCancelled(sessionId, target, failed = isSessionActive(sessionId, fileId, target))
        }
        target.completed = true
        val sessionComplete = sessions[sessionId]?.files?.values?.all { it.completed || it.cancelled } == true
        if (sessionComplete) sessions.remove(sessionId)
        if (sessionComplete && activeSessionId == sessionId) activeSessionId = null
        onFileReceived(sessionId, fileId, target.destination.file, sessionComplete)
        return WriteResult.COMPLETED
    }

    /** A sender awaiting confirmation has not received its session ID yet. */
    fun cancelPending(remoteAddress: String) {
        pending.values.filter { it.remoteAddress == remoteAddress }.forEach { it.cancel() }
    }

    fun cancel(sessionId: String) {
        cancelledSessions[sessionId] = true
        sessions.remove(sessionId)?.let { cancelTargets(sessionId, it) }
        if (activeSessionId == sessionId) activeSessionId = null
    }

    private fun cancelTargets(sessionId: String, session: Session) {
        session.files.values.filterNot { it.completed || it.cancelled }.forEach {
            it.cancelled = true
            fileStore.discard(it.destination)
            onFileReceiveCancelled(sessionId, it.file.id, it.file.fileName, true)
        }
    }

    fun cancelFile(sessionId: String, fileId: String): Boolean {
        val target = sessions[sessionId]?.files?.get(fileId) ?: return false
        if (target.completed || target.cancelled) return false
        target.cancelled = true
        fileStore.discard(target.destination)
        val sessionComplete = sessions[sessionId]?.files?.values?.all { it.completed || it.cancelled } == true
        onFileReceiveCancelled(sessionId, fileId, target.file.fileName, sessionComplete)
        if (sessionComplete) {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) activeSessionId = null
        }
        return true
    }

    fun cancelCurrent(): Boolean {
        val sessionId = activeSessionId ?: return false
        return cancelSession(sessionId)
    }

    fun cancelSession(sessionId: String): Boolean {
        val session = sessions.remove(sessionId) ?: return false
        cancelledSessions[sessionId] = true
        if (activeSessionId == sessionId) activeSessionId = null
        cancelTargets(sessionId, session)
        onTransferCancelRequested(session.info, session.remoteAddress, sessionId)
        return true
    }

    fun cancelAll(): Boolean {
        val (waiting, active) = synchronized(lifecycleLock) {
            cancellationGeneration++
            pending.values.toList() to sessions.keys.toList()
        }
        waiting.forEach { it.cancel() }
        var cancelled = waiting.isNotEmpty()
        active.forEach { if (cancelSession(it)) cancelled = true }
        return cancelled
    }

    private fun isSessionActive(sessionId: String, fileId: String, target: Target): Boolean =
        sessions[sessionId]?.files?.get(fileId) === target && !target.cancelled

    private fun discardAsCancelled(sessionId: String, target: Target, failed: Boolean = false): WriteResult {
        runCatching { fileStore.discard(target.destination) }
        cancelledSessions.remove(sessionId)
        target.cancelled = true
        val session = sessions[sessionId]
        val sessionComplete = session == null || session.files.values.all { it.completed || it.cancelled }
        if (sessionComplete) {
            sessions.remove(sessionId)
            if (activeSessionId == sessionId) activeSessionId = null
        }
        if (failed) onFileReceiveFailed(sessionId, target.file.id, target.file.fileName, sessionComplete)
        else onFileReceiveCancelled(sessionId, target.file.id, target.file.fileName, sessionComplete)
        return if (failed) WriteResult.REJECTED else WriteResult.CANCELLED
    }

    data class PrepareUploadRequest(val info: DeviceInfo, val files: Map<String, IncomingFile>) {
        fun messageText(): String? {
            if (files.size != 1) return null
            val file = files.values.first()
            if (!file.fileType.equals("text", ignoreCase = true) && !file.fileType.startsWith("text/", ignoreCase = true)) return null
            return file.preview
        }
    }
    data class IncomingFile(val id: String, val fileName: String, val size: Long, val fileType: String, val sha256: String?, val preview: String? = null)
    data class PrepareUploadResponse(val sessionId: String, val files: Map<String, String>)
    enum class WriteResult { COMPLETED, CANCELLED, REJECTED, CHECKSUM_MISMATCH }
    @Volatile private var activeSessionId: String? = null
    private data class PendingRequest(val request: PrepareUploadRequest, val remoteAddress: String, val cancel: () -> Unit)
    private data class Session(val files: Map<String, Target>, val info: DeviceInfo, val remoteAddress: String)
    private data class Target(val token: String, val file: IncomingFile, val destination: IncomingFileStore.Destination, var completed: Boolean = false, @Volatile var cancelled: Boolean = false, var checksumFailures: Int = 0)
    companion object {
        private const val BUFFER_SIZE = 32 * 1024
        const val DECISION_TIMEOUT_SECONDS = 60L
    }
}
