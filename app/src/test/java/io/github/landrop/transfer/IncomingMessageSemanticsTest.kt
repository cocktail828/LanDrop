package io.github.landrop.transfer

import io.github.landrop.model.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncomingMessageSemanticsTest {
    @Test
    fun textPreviewIsRecognizedAsMessageInsteadOfFile() {
        val request = IncomingTransferManager.PrepareUploadRequest(
            DeviceInfo("sender", "2.0", null, null, "fingerprint", 53317, "https"),
            linkedMapOf(
                "message" to IncomingTransferManager.IncomingFile(
                    "message", "message.txt", 5, "text/plain", null, "hello"
                )
            )
        )

        assertEquals("hello", request.messageText())
    }

    @Test
    fun textFileWithoutPreviewRemainsAFile() {
        val request = IncomingTransferManager.PrepareUploadRequest(
            DeviceInfo("sender", "2.0", null, null, "fingerprint", 53317, "https"),
            linkedMapOf(
                "document" to IncomingTransferManager.IncomingFile(
                    "document", "notes.txt", 5, "text/plain", null
                )
            )
        )

        assertNull(request.messageText())
    }
}
