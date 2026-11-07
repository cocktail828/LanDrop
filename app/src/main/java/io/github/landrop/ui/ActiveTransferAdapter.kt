package io.github.landrop.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.R
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.TransferSession
import io.github.landrop.model.groupTransferSessions
import io.github.landrop.model.isCancelled

class ActiveTransferAdapter(
    private val onFileClick: (ActiveTransferFile) -> Unit,
    private val onSessionClick: (TransferSession) -> Unit
) : RecyclerView.Adapter<ActiveTransferAdapter.ViewHolder>() {
    private val rows = mutableListOf<Row>()
    private val checksumProgress = LinkedHashMap<String, ChecksumProgress>()
    private val speedSamples = LinkedHashMap<String, SpeedSample>()

    fun setChecksumProgress(sessionId: String, current: Int, total: Int) {
        checksumProgress[sessionId] = ChecksumProgress(current, total)
        val position = rows.indexOfFirst { it is Row.Header && it.session.sessionId == sessionId }
        if (position >= 0) notifyItemChanged(position)
    }

    fun clearChecksumProgress(sessionId: String) {
        if (checksumProgress.remove(sessionId) != null) {
            val position = rows.indexOfFirst { it is Row.Header && it.session.sessionId == sessionId }
            if (position >= 0) notifyItemChanged(position)
        }
    }

    fun submitFiles(newFiles: List<ActiveTransferFile>) {
        val now = android.os.SystemClock.elapsedRealtime()
        val keys = newFiles.map { "${it.direction}:${it.sessionId}:${it.fileId}" }.toSet()
        speedSamples.keys.retainAll(keys)
        newFiles.forEach { file ->
            val key = "${file.direction}:${file.sessionId}:${file.fileId}"
            val previous = speedSamples[key]
            if (previous == null || previous.bytes != file.receivedBytes) {
                val speed = if (previous != null && now > previous.at && file.receivedBytes >= previous.bytes)
                    (file.receivedBytes - previous.bytes) * 1000 / (now - previous.at) else 0L
                speedSamples[key] = SpeedSample(file.receivedBytes, now, speed)
            }
        }
        val oldRows = rows.toList()
        val newRows = groupTransferSessions(newFiles).flatMap { session ->
            listOf(Row.Header(session)) + session.files.map { Row.File(it) }
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = oldRows[oldPosition].key == newRows[newPosition].key
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = oldRows[oldPosition] == newRows[newPosition]
        })
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (viewType == VIEW_TYPE_HEADER) R.layout.item_transfer_session else R.layout.item_active_transfer
        val itemView = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        ThemeColors.apply(itemView)
        return if (viewType == VIEW_TYPE_HEADER) HeaderViewHolder(itemView) else FileViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderViewHolder).bind(row.session, checksumProgress[row.session.sessionId], onSessionClick)
            is Row.File -> (holder as FileViewHolder).bind(row.file,
                speedSamples["${row.file.direction}:${row.file.sessionId}:${row.file.fileId}"]?.speed ?: 0L, onFileClick)
        }
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) VIEW_TYPE_HEADER else VIEW_TYPE_FILE
    override fun getItemCount() = rows.size

    private sealed class Row {
        abstract val key: String

        data class Header(val session: TransferSession) : Row() {
            override val key = "header:${session.direction}:${session.sessionId}"
        }

        data class File(val file: ActiveTransferFile) : Row() {
            override val key = "file:${file.direction}:${file.sessionId}:${file.fileId}"
        }
    }

    open class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    class HeaderViewHolder(itemView: View) : ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.transfer_session_title)
        private val summary: TextView = itemView.findViewById(R.id.transfer_session_summary)
        private val preparationProgress: ProgressBar = itemView.findViewById(R.id.transfer_session_progress)

        fun bind(session: TransferSession, checksumProgress: ChecksumProgress?, onSessionClick: (TransferSession) -> Unit) {
            val isPreparing = session.direction == ActiveTransferFile.Direction.OUTGOING && checksumProgress != null
            if (isPreparing) {
                title.text = itemView.context.getString(R.string.transfer_checksum_progress, checksumProgress!!.current, checksumProgress.total)
            } else {
                title.setText(if (session.direction == ActiveTransferFile.Direction.OUTGOING) R.string.history_sending else R.string.history_receiving)
            }
            val total = session.files.sumOf { it.totalBytes }
            val transferred = session.files.sumOf { it.receivedBytes.coerceAtLeast(0L) }
            val peer = session.files.firstOrNull()?.peerAlias
            summary.text = if (!peer.isNullOrBlank()) {
                itemView.context.getString(if (session.direction == ActiveTransferFile.Direction.OUTGOING)
                    R.string.history_to_peer else R.string.history_from_peer, peer)
            } else if (session.isCancelled()) {
                itemView.context.getString(R.string.transfer_cancelled)
            } else if (session.files.all { it.totalBytes >= 0L }) {
                itemView.context.getString(R.string.transfer_session_summary, session.files.size, formatBytes(transferred), formatBytes(total))
            } else {
                itemView.context.getString(R.string.transfer_session_summary_unknown, session.files.size)
            }
            summary.visibility = if (isPreparing) View.GONE else View.VISIBLE
            preparationProgress.visibility = if (isPreparing) View.VISIBLE else View.GONE
            if (isPreparing) {
                preparationProgress.progress = (((checksumProgress!!.current - 1) * 100L) / checksumProgress.total.coerceAtLeast(1)).toInt().coerceIn(0, 100)
            }
            val canCancel = session.files.any {
                it.status == ActiveTransferFile.Status.WAITING || it.status == ActiveTransferFile.Status.TRANSFERRING
            }
            itemView.setOnClickListener(if (canCancel) View.OnClickListener { onSessionClick(session) } else null)
            itemView.isFocusable = canCancel
            itemView.isClickable = canCancel
            itemView.background = TvUi.focusBackground(itemView.context)
        }
    }

    data class ChecksumProgress(val current: Int, val total: Int)
    private data class SpeedSample(val bytes: Long, val at: Long, val speed: Long)

    class FileViewHolder(itemView: View) : ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.active_transfer_name)
        private val status: TextView = itemView.findViewById(R.id.active_transfer_status)
        private val progressLabel: TextView = itemView.findViewById(R.id.active_transfer_progress_label)
        private val progress: ProgressBar = itemView.findViewById(R.id.active_transfer_progress)
        private val percent: TextView = itemView.findViewById(R.id.active_transfer_percent)

        fun bind(file: ActiveTransferFile, speed: Long, onFileClick: (ActiveTransferFile) -> Unit) {
            name.text = file.fileName
            status.text = when (file.status) {
                ActiveTransferFile.Status.COMPLETED -> itemView.context.getString(when {
                    file.fileDeleted -> R.string.file_deleted
                    file.direction == ActiveTransferFile.Direction.OUTGOING -> R.string.transfer_sent
                    else -> R.string.transfer_completed
                })
                ActiveTransferFile.Status.CANCELLED -> ""
                ActiveTransferFile.Status.FAILED -> ""
                ActiveTransferFile.Status.WAITING, ActiveTransferFile.Status.TRANSFERRING -> ""
            }
            status.visibility = if (file.status == ActiveTransferFile.Status.WAITING || file.status == ActiveTransferFile.Status.TRANSFERRING || file.status == ActiveTransferFile.Status.FAILED || file.status == ActiveTransferFile.Status.CANCELLED) View.GONE else View.VISIBLE
            progress.progress = if (file.totalBytes > 0) ((file.receivedBytes * 100L) / file.totalBytes).toInt().coerceIn(0, 100) else 0
            percent.text = if (file.status == ActiveTransferFile.Status.WAITING) itemView.context.getString(R.string.history_waiting)
                else java.text.NumberFormat.getPercentInstance().format(if (file.status == ActiveTransferFile.Status.COMPLETED) 1.0 else progress.progress / 100.0)
            percent.visibility = if (file.totalBytes < 0 || file.status == ActiveTransferFile.Status.CANCELLED || file.status == ActiveTransferFile.Status.FAILED) View.GONE else View.VISIBLE
            progress.visibility = if (file.status == ActiveTransferFile.Status.WAITING || file.status == ActiveTransferFile.Status.TRANSFERRING) View.VISIBLE else View.GONE
            progressLabel.text = if (file.status == ActiveTransferFile.Status.FAILED) {
                itemView.context.getString(R.string.transfer_failed)
            } else if (file.status == ActiveTransferFile.Status.CANCELLED) {
                itemView.context.getString(R.string.transfer_cancelled)
            } else if (file.totalBytes >= 0) {
                itemView.context.getString(R.string.transfer_progress_bytes, formatBytes(file.receivedBytes.coerceAtLeast(0L)), formatBytes(file.totalBytes))
            } else {
                itemView.context.getString(R.string.transfer_progress_bytes_unknown, formatBytes(file.receivedBytes.coerceAtLeast(0L)))
            }
            if (file.status == ActiveTransferFile.Status.TRANSFERRING && speed > 0L) {
                progressLabel.text = itemView.context.getString(R.string.history_progress_speed, progressLabel.text, formatBytes(speed))
            }
            val canAct = file.status == ActiveTransferFile.Status.WAITING ||
                file.status == ActiveTransferFile.Status.TRANSFERRING ||
                (file.direction == ActiveTransferFile.Direction.INCOMING &&
                    file.status == ActiveTransferFile.Status.COMPLETED && file.receivedFile != null && !file.fileDeleted)
            itemView.setOnClickListener(if (canAct) View.OnClickListener { onFileClick(file) } else null)
            itemView.isFocusable = canAct
            itemView.isClickable = canAct
            itemView.background = TvUi.focusBackground(itemView.context, panel = true)
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_FILE = 1

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
