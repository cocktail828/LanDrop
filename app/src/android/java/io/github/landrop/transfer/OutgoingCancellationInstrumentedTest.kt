package io.github.landrop.transfer

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import io.github.landrop.discovery.LocalIdentity
import io.github.landrop.model.RemoteDevice
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OutgoingCancellationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pendingCancellationRequiresTheMatchingPreparationId() = withPeer { peer, client, send ->
        peer.blockPreparation.set(true)
        val record = Recorder()
        send(record)
        assertTrue(record.preparing.await(5, TimeUnit.SECONDS))
        assertTrue(peer.prepareEntered.await(5, TimeUnit.SECONDS))
        assertFalse(client.cancelSession("unrelated-session"))
        assertTrue(peer.cancelled.isEmpty())
        assertTrue(client.cancelSession(record.preparationId.get()))
        assertTrue(record.finished.await(5, TimeUnit.SECONDS))
        assertEquals("cancelled", record.result.get())
        assertEquals("<pending>", peer.cancelRequests.poll(5, TimeUnit.SECONDS))
    }

    @Test fun staleSessionCannotStopNextUploadAndBothCurrentIdsRemainScoped() = withPeer { peer, client, send ->
        val old = Recorder()
        send(old)
        assertTrue(old.finished.await(5, TimeUnit.SECONDS))
        assertEquals("completed", old.result.get())
        peer.uploads.clear()
        peer.blockUploads.set(true)
        for (usePreparationId in listOf(true, false)) {
            val current = Recorder()
            send(current)
            assertTrue(current.prepared.await(5, TimeUnit.SECONDS))
            assertEquals(current.sessionId.get(), peer.uploads.poll(5, TimeUnit.SECONDS))
            assertFalse(client.cancelSession(old.preparationId.get()))
            assertFalse(client.cancelSession(old.sessionId.get()))
            assertFalse(peer.cancelled.contains(current.sessionId.get()))
            val target = if (usePreparationId) current.preparationId.get() else current.sessionId.get()
            assertTrue(client.cancelSession(target))
            assertTrue(current.finished.await(5, TimeUnit.SECONDS))
            assertEquals("cancelled", current.result.get())
        }
    }

    private fun withPeer(block: (Peer, UploadClient, (Recorder) -> Unit) -> Unit) {
        val file = File.createTempFile("outgoing-cancel-", ".bin", context.cacheDir)
        file.writeBytes(ByteArray(4096) { it.toByte() })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val peer = Peer().apply { start(15000, true) }
        val client = UploadClient(context, LocalIdentity(context))
        val device = RemoteDevice("Test peer", "loopback", "desktop", "http-test-peer", "127.0.0.1", peer.listeningPort, "http", false)
        try { block(peer, client) { client.send(uri, device, it) } }
        finally { client.cancelCurrent(); peer.release(); peer.stop(); file.delete() }
    }

    private class Recorder : UploadClient.Listener {
        val preparing = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val preparationId = AtomicReference<String>()
        val sessionId = AtomicReference<String>()
        val result = AtomicReference<String>()
        override fun onStatus(message: String) = Unit
        override fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit) { reply(null) }
        override fun onPreparationStarted(sessionId: String, files: List<UploadClient.QueueFile>) {
            preparationId.set(sessionId); preparing.countDown()
        }
        override fun onChecksumProgress(sessionId: String, current: Int, total: Int) = Unit
        override fun onSessionPrepared(preparationSessionId: String, sessionId: String, files: List<UploadClient.QueueFile>) {
            this.sessionId.set(sessionId); prepared.countDown()
        }
        override fun onProgress(sessionId: String, fileId: String, fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long) = Unit
        override fun onFileCompleted(sessionId: String, fileId: String) = Unit
        override fun onCompleted(names: List<String>) { result.set("completed"); finished.countDown() }
        override fun onError(message: String) { result.set("error: $message"); finished.countDown() }
        override fun onCancelled(message: String) { result.set("cancelled"); finished.countDown() }
    }

    private class Peer : NanoHTTPD("127.0.0.1", 0) {
        val blockPreparation = AtomicBoolean(false)
        val blockUploads = AtomicBoolean(false)
        val prepareEntered = CountDownLatch(1)
        val uploads = LinkedBlockingQueue<String>()
        val cancelRequests = LinkedBlockingQueue<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        private val preparationGate = CountDownLatch(1)
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val counter = AtomicInteger()
        override fun serve(session: IHTTPSession): Response = when {
            session.uri.endsWith("/prepare-upload") -> {
                val request = JsonParser.parseString(String(readBody(session), Charsets.UTF_8)).asJsonObject
                prepareEntered.countDown()
                if (blockPreparation.get()) preparationGate.await(10, TimeUnit.SECONDS)
                val id = "test-session-${counter.incrementAndGet()}"
                gates[id] = CountDownLatch(1)
                val files = request.getAsJsonObject("files").keySet().associateWith { "token" }
                newFixedLengthResponse(Response.Status.OK, "application/json", Gson().toJson(mapOf("sessionId" to id, "files" to files)))
            }
            session.uri.endsWith("/upload") -> {
                readBody(session)
                val id = session.parms.getValue("sessionId")
                uploads.add(id)
                if (blockUploads.get()) gates.getValue(id).await(10, TimeUnit.SECONDS)
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            }
            session.uri.endsWith("/cancel") -> {
                val id = session.parms["sessionId"] ?: "<pending>"
                cancelled.add(id); cancelRequests.add(id); gates[id]?.countDown()
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "")
        }
        private fun readBody(session: IHTTPSession): ByteArray {
            val bytes = ByteArray(session.headers.getValue("content-length").toInt())
            var read = 0
            while (read < bytes.size) {
                val count = session.inputStream.read(bytes, read, bytes.size - read)
                check(count > 0)
                read += count
            }
            return bytes
        }
        fun release() { preparationGate.countDown(); gates.values.forEach { it.countDown() } }
    }
}
