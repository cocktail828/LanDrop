package io.github.landrop.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.R
import io.github.landrop.history.ReceiveHistoryText
import io.github.landrop.model.ReceiveHistoryEntry
import java.util.Locale

class ReceiveHistoryAdapter(
    private val onClick: (ReceiveHistoryEntry) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<Row> = emptyList()

    fun submitEntries(entries: List<ReceiveHistoryEntry>) {
        val old = rows
        val next = listOf(false, true).flatMap { outgoing ->
            val group = entries.filter { it.outgoing == outgoing }
            if (group.isEmpty()) emptyList() else listOf(Row.Header(outgoing)) + group.map { Row.Entry(it) }
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].key == next[b].key
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == next[b]
        })
        rows = next
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 0) R.layout.item_history_section else R.layout.item_receive_history
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        ThemeColors.apply(view)
        return if (viewType == 0) HeaderHolder(view) else EntryHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder.itemView as TextView).setText(
                if (row.outgoing) R.string.history_recent_sent else R.string.history_recent_received)
            is Row.Entry -> (holder as EntryHolder).bind(row.entry, onClick)
        }
    }

    private sealed class Row {
        abstract val key: String
        data class Header(val outgoing: Boolean) : Row() { override val key = "header:$outgoing" }
        data class Entry(val entry: ReceiveHistoryEntry) : Row() { override val key = "entry:${entry.id}" }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view)

    class EntryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.history_file_icon)
        private val name: TextView = view.findViewById(R.id.history_file_name)
        private val details: TextView = view.findViewById(R.id.history_file_details)
        private val status: TextView = view.findViewById(R.id.history_file_status)

        fun bind(entry: ReceiveHistoryEntry, onClick: (ReceiveHistoryEntry) -> Unit) {
            val context = itemView.context
            icon.setImageResource(when {
                entry.isMessage -> R.drawable.ic_content_paste
                entry.outgoing -> R.drawable.ic_send
                else -> R.drawable.ic_description
            })
            icon.imageTintList = android.content.res.ColorStateList.valueOf(ThemeColors.primaryTextColor(context))
            name.text = entry.displayName
            val peer = ReceiveHistoryText.sender(context, entry.senderAlias)
            val direction = context.getString(if (entry.outgoing) R.string.history_to_peer else R.string.history_from_peer, peer)
            details.text = context.getString(R.string.history_entry_metadata, direction,
                ReceiveHistoryText.receivedAt(context, entry.receivedAt), formatBytes(entry.size))
            status.setText(when (entry.result) {
                "sent" -> R.string.transfer_sent
                "failed" -> R.string.transfer_failed
                "cancelled" -> R.string.transfer_cancelled
                else -> R.string.history_received
            })
            status.setTextColor(if (!entry.outgoing && entry.result == "saved") {
                Color.parseColor(if (ThemeColors.isDark(context)) "#83DBB1" else "#147D50")
            } else TvUi.muted(context))
            itemView.setOnClickListener { onClick(entry) }
        }

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
