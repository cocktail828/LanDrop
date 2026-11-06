package io.github.landrop.transfer

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.model.DeviceInfo
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IncomingFailureInstrumentedTest {
    @Test
    fun failedLastFileReleasesTheSessionInsteadOfLeavingReceiverBusy() {
        // MediaStore ownership avoids changing the device's legacy storage permission grants.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val terminalEvents = mutableListOf<Boolean>()
        val manager = IncomingTransferManager(context,
            onTransferRequested = { _, reply ->
                reply(IncomingReceiveOptions(null))
            },
            onSessionPrepared = { _, _ -> },
            onTransferCancelRequested = { _, _, _ -> },
            onFileProgress = { _, _, _, _, _ -> },
            onFileReceiveCancelled = { _, _, _, complete -> terminalEvents += complete },
            onFileReceived = { _, _, _, _ -> fail("Truncated files must not be published") }
        )
        val suffix = System.nanoTime()
        val files = (1..2).associate { index ->
            val id = "file-$index"
            id to IncomingTransferManager.IncomingFile(id, "receiver-failure-$suffix-$index.bin", 10,
                "application/octet-stream", null)
        }
        val request = IncomingTransferManager.PrepareUploadRequest(
            DeviceInfo("Test sender", "2.0", "test", "mobile", "test-fingerprint", 53317, "http"), files)
        val session = requireNotNull(manager.prepare(request, "127.0.0.1"))
        try {
            files.keys.forEach { id ->
                assertEquals(IncomingTransferManager.WriteResult.REJECTED,
                    manager.write(session.sessionId, id, session.files.getValue(id),
                        ByteArrayInputStream(byteArrayOf(1, 2)), 10L, false))
            }
            assertEquals(listOf(false, true), terminalEvents)
            assertFalse(manager.cancelCurrent())
        } finally {
            manager.cancel(session.sessionId)
        }
    }
}
