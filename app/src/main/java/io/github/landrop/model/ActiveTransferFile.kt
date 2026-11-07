package io.github.landrop.model

/** Live state for one file inside an active transfer session. */
data class ActiveTransferFile(
    val sessionId: String,
    val fileId: String,
    val fileName: String,
    val receivedBytes: Long,
    val totalBytes: Long,
    val status: Status,
    val direction: Direction = Direction.INCOMING,
    val receivedFile: ReceivedFile? = null,
    val fileDeleted: Boolean = false,
    val peerAlias: String? = null
) {
    enum class Status { WAITING, TRANSFERRING, COMPLETED, CANCELLED, FAILED }
    enum class Direction { INCOMING, OUTGOING }
}
