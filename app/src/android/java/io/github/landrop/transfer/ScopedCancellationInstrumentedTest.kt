package io.github.landrop.transfer

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScopedCancellationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sender = DeviceInfo("Cancellation test", "2.2", "test", "desktop", "test-peer", 53317, "http")

    @Test fun stoppingOneIncomingSessionKeepsOtherSessionsAndCompletedFiles() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val saved = mutableListOf<ReceivedFile>()
        val cancelledPeers = mutableListOf<String>()
        val manager = manager(saved, cancelledPeers)
        val a = prepare(manager, "a", 2)
        val b = prepare(manager, "b", 1)
        val bytes = byteArrayOf(1, 2, 3)
        try {
            assertEquals(IncomingTransferManager.WriteResult.COMPLETED,
                manager.write(a.sessionId, "0", a.files.getValue("0"), ByteArrayInputStream(bytes), 3, false))
            assertTrue(manager.cancelSession(a.sessionId))
            assertFalse(manager.cancelSession(a.sessionId))
            assertEquals(listOf(a.sessionId), cancelledPeers)
            assertEquals(IncomingTransferManager.WriteResult.CANCELLED,
                manager.write(a.sessionId, "1", a.files.getValue("1"), ByteArrayInputStream(bytes), 3, false))
            assertEquals(IncomingTransferManager.WriteResult.COMPLETED,
                manager.write(b.sessionId, "0", b.files.getValue("0"), ByteArrayInputStream(bytes), 3, false))
            assertEquals(2, saved.size)
            saved.forEach { file -> context.contentResolver.openInputStream(file.uri)!!.use { assertArrayEquals(bytes, it.readBytes()) } }
        } finally {
            manager.cancelAll()
            saved.forEach { context.contentResolver.delete(it.uri, null, null) }
        }
    }

    @Test fun stoppingAllIncomingSessionsCancelsEveryActiveSession() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val saved = mutableListOf<ReceivedFile>()
        val cancelledPeers = mutableListOf<String>()
        val manager = manager(saved, cancelledPeers)
        val a = prepare(manager, "a", 1)
        val b = prepare(manager, "b", 1)
        try {
            assertTrue(manager.cancelAll())
            assertEquals(setOf(a.sessionId, b.sessionId), cancelledPeers.toSet())
            for (session in listOf(a, b)) {
                assertEquals(IncomingTransferManager.WriteResult.CANCELLED,
                    manager.write(session.sessionId, "0", session.files.getValue("0"), ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, false))
            }
            assertFalse(manager.cancelAll())
            assertTrue(saved.isEmpty())
        } finally { manager.cancelAll() }
    }

    @Test fun stoppingAllAlsoRejectsRequestsStillAwaitingConfirmation() {
        val offered = CountDownLatch(2)
        val manager = IncomingTransferManager(context, { _, _ -> offered.countDown() },
            { _, _ -> fail("Cancelled request was accepted") }, { _, _, _ -> },
            { _, _, _, _, _ -> }, { _, _, _, _ -> }, { _, _, _, _ -> fail("Unexpected saved file") })
        val workers = Executors.newFixedThreadPool(2)
        val request = IncomingTransferManager.PrepareUploadRequest(sender, mapOf(
            "text" to IncomingTransferManager.IncomingFile("text", "message.txt", 5, "text/plain", null, "hello")))
        try {
            val pending = (1..2).map { index -> workers.submit<IncomingTransferManager.PrepareUploadResponse?> {
                manager.prepare(request, "192.0.2.$index")
            } }
            assertTrue(offered.await(3, TimeUnit.SECONDS))
            assertTrue(manager.cancelAll())
            pending.forEach { assertNull(it.get(3, TimeUnit.SECONDS)) }
        } finally { manager.cancelAll(); workers.shutdownNow() }
    }

    @Test fun globalStopCannotResurrectAnAcceptedButNotYetPreparedRequest() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = IncomingTransferManager(context, { _, decide ->
            decide(IncomingReceiveOptions(null))
            accepted.countDown()
            release.await(5, TimeUnit.SECONDS)
        }, { _, _ -> fail("Request was prepared after global stop") }, { _, _, _ -> },
            { _, _, _, _, _ -> }, { _, _, _, _ -> }, { _, _, _, _ -> fail("Unexpected saved file") })
        val worker = Executors.newSingleThreadExecutor()
        val request = IncomingTransferManager.PrepareUploadRequest(sender, mapOf(
            "file" to IncomingTransferManager.IncomingFile("file", "cancel-race-${UUID.randomUUID()}.bin", 3, "application/octet-stream", null)))
        try {
            val response = worker.submit<IncomingTransferManager.PrepareUploadResponse?> { manager.prepare(request, "192.0.2.1") }
            assertTrue(accepted.await(3, TimeUnit.SECONDS))
            assertTrue(manager.cancelAll())
            release.countDown()
            assertNull(response.get(5, TimeUnit.SECONDS))
            assertFalse(manager.cancelCurrent())
        } finally { release.countDown(); manager.cancelAll(); worker.shutdownNow() }
    }

    private fun manager(saved: MutableList<ReceivedFile>, cancelled: MutableList<String>) = IncomingTransferManager(
        context, { _, decide -> decide(IncomingReceiveOptions(null)) }, { _, _ -> },
        { _, _, id -> cancelled += id }, { _, _, _, _, _ -> }, { _, _, _, _ -> },
        { _, _, file, _ -> saved += file })

    private fun prepare(manager: IncomingTransferManager, label: String, count: Int): IncomingTransferManager.PrepareUploadResponse {
        val prefix = "cancel-test-${UUID.randomUUID()}-$label"
        val files = (0 until count).associate { index -> index.toString() to IncomingTransferManager.IncomingFile(
            index.toString(), "$prefix-$index.bin", 3, "application/octet-stream", null) }
        return requireNotNull(manager.prepare(IncomingTransferManager.PrepareUploadRequest(sender, files), "192.0.2.1"))
    }
}
