package io.github.landrop.server

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.InfoResponse
import io.github.landrop.protocol.LocalSendProtocol
import io.github.landrop.security.PinAuthenticator
import io.github.landrop.transfer.IncomingTransferManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.HashMap
import android.util.Log

/** Minimal HTTP server needed for LocalSend's two-way device discovery. */
class LocalSendServer(
    private val gson: Gson,
    private val localDevice: () -> DeviceInfo,
    private val onDeviceRegistered: (DeviceInfo, String) -> Unit,
    private val incomingTransfers: IncomingTransferManager,
    private val port: Int,
    bindAddress: String,
    private val receivePin: () -> String?,
    private val onTransferCancelled: (String) -> Unit = {}
) : NanoHTTPD(bindAddress, port) {

    private val failedPinAttempts = HashMap<String, Int>()
    private val failedPinAttemptsLock = Any()

    override fun serve(session: IHTTPSession): Response {
        val contentLengthHeader = session.headers["content-length"]
        val transferEncodingHeader = session.headers["transfer-encoding"]
        Log.i(
            TAG,
            "Request method=${session.method} uri=${session.uri} remote=${session.remoteIpAddress} " +
                "contentLength=$contentLengthHeader transferEncoding=$transferEncodingHeader " +
                "parameters=${session.parms.keys}"
        )
        return try {
            // This read-only query must not register a peer or enter the PIN/transfer flow.
            if (session.method == Method.GET && session.uri == LocalSendProtocol.INFO_PATH) {
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json; charset=utf-8",
                    gson.toJson(InfoResponse.from(localDevice()))
                )
            }
            if (session.method != Method.POST) {
                Log.w(TAG, "Rejecting unsupported request method: ${session.method}")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
            if (session.uri == LocalSendProtocol.PREPARE_UPLOAD_PATH) return prepareUpload(session)
            if (session.uri == LocalSendProtocol.UPLOAD_PATH) return receiveFile(session)
            if (session.uri == LocalSendProtocol.CANCEL_PATH) return cancel(session)
            if (session.uri != LocalSendProtocol.REGISTER_PATH) {
                Log.w(TAG, "Rejecting unknown route: ${session.uri}")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
            val device = gson.fromJson(readUtf8Body(session), DeviceInfo::class.java)
                ?: return badRequest()
            if (!validIdentity(device)) return forbidden()

            onDeviceRegistered(device, session.remoteIpAddress)
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                gson.toJson(localDevice())
            )
        } catch (exception: Exception) {
            Log.e(TAG, "LocalSend request failed: ${session.uri}", exception)
            badRequest()
        }
    }

    private fun badRequest(reason: String = "Invalid body"): Response {
        Log.w(TAG, "Bad request: $reason")
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, reason).also { it.closeConnection(true) }
    }
    private fun pinRejected(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message).also {
            // The rejected prepare-upload body has not been consumed. Close the
            // keep-alive connection so NanoHTTPD does not parse that JSON as a
            // second HTTP request before the sender retries with the PIN.
            it.closeConnection(true)
        }
    private fun forbidden(): Response = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid LocalSend device identity")
    private fun rejected(): Response = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Rejected")

    private fun prepareUpload(session: IHTTPSession): Response {
        checkPin(session)?.let { return it }
        val request = gson.fromJson(readUtf8Body(session), IncomingTransferManager.PrepareUploadRequest::class.java) ?: return badRequest()
        Log.i(TAG, "Prepare upload: files=${request.files.size} ids=${request.files.keys}")
        if (!validIdentity(request.info)) {
            Log.w(TAG, "Prepare upload rejected because client HTTP identity is invalid")
            return forbidden()
        }
        val response = incomingTransfers.prepare(request, session.remoteIpAddress) ?: return rejected()
        if (response.files.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    /** LocalSend v2.1 authenticates the prepare request with a query parameter. */
    private fun checkPin(session: IHTTPSession): Response? {
        val remoteAddress = session.remoteIpAddress
        val suppliedPin = session.parms[LocalSendProtocol.PIN_QUERY_PARAMETER]
        val failedAttempts = synchronized(failedPinAttemptsLock) { failedPinAttempts[remoteAddress] ?: 0 }
        return when (PinAuthenticator.check(receivePin(), suppliedPin, failedAttempts)) {
            PinAuthenticator.Result.NOT_REQUIRED -> null
            PinAuthenticator.Result.ACCEPTED -> {
                synchronized(failedPinAttemptsLock) { failedPinAttempts.remove(remoteAddress) }
                null
            }
            PinAuthenticator.Result.INVALID -> {
                if (suppliedPin != null) {
                    synchronized(failedPinAttemptsLock) { failedPinAttempts[remoteAddress] = failedAttempts + 1 }
                }
                pinRejected(Response.Status.UNAUTHORIZED, "PIN required or invalid")
            }
            PinAuthenticator.Result.TOO_MANY_ATTEMPTS ->
                pinRejected(Response.Status.TOO_MANY_REQUESTS, "Too many requests")
        }
    }

    private fun receiveFile(session: IHTTPSession): Response {
        val sessionId = session.parms["sessionId"] ?: return badRequest("Missing sessionId")
        val fileId = session.parms["fileId"] ?: return badRequest("Missing fileId")
        val token = session.parms["token"] ?: return badRequest("Missing token")
        val isChunked = session.headers["transfer-encoding"]
            ?.split(',')
            ?.any { it.trim().equals("chunked", true) } == true
        val contentLength = session.headers["content-length"]?.toLongOrNull()
        if (!isChunked && contentLength == null) return badRequest("Missing Content-Length or chunked encoding")
        Log.i(TAG, "Receiving file: session=$sessionId file=$fileId bytes=$contentLength chunked=$isChunked")
        return when (incomingTransfers.write(sessionId, fileId, token, session.inputStream, contentLength, isChunked)) {
            IncomingTransferManager.WriteResult.COMPLETED -> {
                Log.i(TAG, "Upload completed: session=$sessionId file=$fileId")
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            }
            IncomingTransferManager.WriteResult.CANCELLED -> {
                Log.i(TAG, "Upload cancelled: session=$sessionId file=$fileId")
                // Never return 2xx here: LocalSend senders treat 200 as a
                // completed file. The reference implementation reports an
                // inactive/cancelled upload as a non-success response.
                newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Transfer cancelled").also { it.closeConnection(true) }
            }
            IncomingTransferManager.WriteResult.CHECKSUM_MISMATCH ->
                newFixedLengthResponse(CHECKSUM_MISMATCH_STATUS, MIME_PLAINTEXT, "Checksum mismatch")
            IncomingTransferManager.WriteResult.REJECTED -> {
                Log.w(TAG, "Upload rejected: unknown session/file, invalid token, truncated body, or checksum mismatch")
                newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid upload").also { it.closeConnection(true) }
            }
        }
    }

    private fun cancel(session: IHTTPSession): Response {
        val sessionId = session.parms["sessionId"]
        if (sessionId == null) incomingTransfers.cancelPending(session.remoteIpAddress)
        else {
            incomingTransfers.cancel(sessionId)
            onTransferCancelled(sessionId)
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
    }

    private fun validIdentity(device: DeviceInfo): Boolean = device.fingerprint.isNotBlank() && device.port in 1..65535 &&
        device.protocol in setOf("http", "https")

    /** NanoHTTPD 2.3.1 defaults JSON without charset to US-ASCII; protocol JSON is UTF-8. */
    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull()
            ?: throw IOException("Missing content length")
        val limit = if (session.uri == LocalSendProtocol.PREPARE_UPLOAD_PATH) MAX_PREPARE_BODY_BYTES else MAX_REGISTER_BODY_BYTES
        if (length < 0 || length > limit) {
            throw IOException("Invalid content length")
        }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = session.inputStream.read(bytes, offset, length - offset)
            if (count < 0) throw IOException("Unexpected end of request body")
            offset += count
        }
        return String(bytes, StandardCharsets.UTF_8).trim()
    }

    private companion object {
        val CHECKSUM_MISMATCH_STATUS = object : Response.IStatus {
            override fun getRequestStatus(): Int = 422
            override fun getDescription(): String = "422 Unprocessable Entity"
        }

        const val TAG = "LocalSendServer"
        const val MAX_REGISTER_BODY_BYTES = 64 * 1024
        const val MAX_PREPARE_BODY_BYTES = 4 * 1024 * 1024
    }
}
