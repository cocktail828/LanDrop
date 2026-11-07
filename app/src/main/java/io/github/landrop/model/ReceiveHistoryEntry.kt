package io.github.landrop.model

import android.net.Uri

/** A completed file or message received from another LocalSend device. */
data class ReceiveHistoryEntry(
    val id: Long,
    val displayName: String,
    val uri: Uri,
    val mimeType: String,
    val size: Long,
    val senderAlias: String,
    val receivedAt: Long,
    val isMessage: Boolean = false,
    val outgoing: Boolean = false,
    val result: String = "saved"
) {
    val receivedFile: ReceivedFile?
        get() = if (!outgoing && !isMessage && result == "saved" && uri.toString().isNotBlank()) {
            ReceivedFile(displayName, uri, mimeType, size)
        } else null
}
