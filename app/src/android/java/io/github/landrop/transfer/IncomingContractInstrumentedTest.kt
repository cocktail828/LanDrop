package io.github.landrop.transfer

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IncomingContractInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sender = DeviceInfo("LocalSend", "2.2", "test", "desktop", "A".repeat(64), 53317, "https")

    @Test fun pendingCancelIsMatchedBySenderAndCannotBeAcceptedLater() {
        val offered = CountDownLatch(1)
        val reply = AtomicReference<((IncomingReceiveOptions?) -> Unit)?>()
        val cancelled = CountDownLatch(1)
        val manager = IncomingTransferManager(context, { _, decide -> reply.set(decide); offered.countDown() },
            { _, _ -> fail("Cancelled request was prepared") }, { _, _, _ -> }, { _, _, _, _, _ -> },
            { _, _, _, _ -> }, { _, _, _, _ -> fail("Cancelled request saved a file") }, { cancelled.countDown() })
        val worker = Executors.newSingleThreadExecutor()
        val request = IncomingTransferManager.PrepareUploadRequest(sender, mapOf(
            "text" to IncomingTransferManager.IncomingFile("text", "message.txt", 5, "text/plain", null, "hello")))
        try {
            val result = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(request, "192.0.2.1") }
            assertTrue(offered.await(3, TimeUnit.SECONDS))
            manager.cancelPending("192.0.2.2")
            assertFalse(result.isDone)
            manager.cancelPending("192.0.2.1")
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
            reply.get()!!.invoke(IncomingReceiveOptions(null))
            assertNull(result.get(3, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun nestedSameNameFilesKeepPathsAndBytes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val saved = mutableListOf<ReceivedFile>()
        val manager = IncomingTransferManager(context, { _, decide -> decide(IncomingReceiveOptions(null)) },
            { _, _ -> }, { _, _, _ -> }, { _, _, _, _, _ -> }, { _, _, _, _ -> fail("Unexpected cancellation") },
            { _, _, file, _ -> saved += file })
        val root = "landrop-test-${System.nanoTime()}"
        val payloads = linkedMapOf("a" to byteArrayOf(1, 2, 3), "b" to byteArrayOf(4, 5, 6))
        val files = payloads.mapValues { (id, bytes) ->
            IncomingTransferManager.IncomingFile(id, "$root/$id/readme.txt", bytes.size.toLong(), "text/plain",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        }
        val session = requireNotNull(manager.prepare(IncomingTransferManager.PrepareUploadRequest(sender, files), "192.0.2.1"))
        try {
            for ((id, bytes) in payloads) {
                assertEquals(IncomingTransferManager.WriteResult.COMPLETED,
                    manager.write(session.sessionId, id, session.files.getValue(id), ByteArrayInputStream(bytes), bytes.size.toLong(), false))
            }
            assertEquals(files.values.map { it.fileName }, saved.map { it.displayName })
            saved.zip(payloads.values).forEach { (file, expected) ->
                context.contentResolver.openInputStream(file.uri)!!.use { assertArrayEquals(expected, it.readBytes()) }
            }
        } finally {
            manager.cancel(session.sessionId)
            saved.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
    }
    @Test fun checksumMismatchRetriesSameSessionAndTokenBeforePublishing() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val saved = mutableListOf<ReceivedFile>()
        var failures = 0
        val manager = IncomingTransferManager(context, { _, decide -> decide(IncomingReceiveOptions(null)) },
            { _, _ -> }, { _, _, _ -> }, { _, _, _, _, _ -> }, { _, _, _, _ -> fail("Unexpected cancellation") },
            { _, _, file, _ -> saved += file }, onFileReceiveFailed = { _, _, _, _ -> failures++ })
        val expected = byteArrayOf(1, 2, 3)
        val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
        val file = IncomingTransferManager.IncomingFile("retry", "retry-${System.nanoTime()}.bin", 3, "application/octet-stream", digest)
        val request = IncomingTransferManager.PrepareUploadRequest(sender, mapOf("retry" to file))
        val session = requireNotNull(manager.prepare(request, "192.0.2.1"))
        try {
            assertEquals(IncomingTransferManager.WriteResult.CHECKSUM_MISMATCH,
                manager.write(session.sessionId, "retry", session.files.getValue("retry"), ByteArrayInputStream(byteArrayOf(9, 9, 9)), 3L, false))
            assertTrue(saved.isEmpty())
            assertEquals(0, failures)
            assertEquals(IncomingTransferManager.WriteResult.COMPLETED,
                manager.write(session.sessionId, "retry", session.files.getValue("retry"), ByteArrayInputStream(expected), 3L, false))
            assertEquals(1, saved.size)
            context.contentResolver.openInputStream(saved.single().uri)!!.use { assertArrayEquals(expected, it.readBytes()) }
        } finally {
            manager.cancel(session.sessionId)
            saved.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
        val exhausted = requireNotNull(manager.prepare(request, "192.0.2.1"))
        try {
            repeat(3) {
                assertEquals(IncomingTransferManager.WriteResult.CHECKSUM_MISMATCH,
                    manager.write(exhausted.sessionId, "retry", exhausted.files.getValue("retry"), ByteArrayInputStream(byteArrayOf(9, 9, 9)), 3L, false))
            }
            assertEquals(1, failures)
            assertEquals(1, saved.size)
            assertFalse(manager.cancelCurrent())
        } finally { manager.cancel(exhausted.sessionId) }
    }
}
