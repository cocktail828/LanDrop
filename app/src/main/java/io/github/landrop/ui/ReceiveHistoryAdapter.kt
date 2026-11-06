package io.github.landrop.ui

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.R
import io.github.landrop.history.ReceiveHistoryText
import io.github.landrop.model.ReceiveHistoryEntry
import java.util.Locale

class ReceiveHistoryAdapter(
    private val onOpen: (ReceiveHistoryEntry) -> Unit,
    private val onDetails: (ReceiveHistoryEntry) -> Unit,
    private val onDelete: (ReceiveHistoryEntry) -> Unit
) : RecyclerView.Adapter<ReceiveHistoryAdapter.ViewHolder>() {
    private val entries = mutableListOf<ReceiveHistoryEntry>()

    fun submitEntries(newEntries: List<ReceiveHistoryEntry>) {
        val oldEntries = entries.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldEntries.size
            override fun getNewListSize() = newEntries.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = oldEntries[oldPosition].id == newEntries[newPosition].id
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) = oldEntries[oldPosition] == newEntries[newPosition]
        })
        entries.clear()
        entries.addAll(newEntries)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_receive_history, parent, false)
        ThemeColors.apply(itemView)
        return ViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(entries[position], onOpen, onDetails, onDelete)
    override fun getItemCount() = entries.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.history_file_icon)
        private val name: TextView = itemView.findViewById(R.id.history_file_name)
        private val details: TextView = itemView.findViewById(R.id.history_file_details)
        private val more: View = itemView.findViewById(R.id.history_more_button)

        fun bind(
            entry: ReceiveHistoryEntry,
            onOpen: (ReceiveHistoryEntry) -> Unit,
            onDetails: (ReceiveHistoryEntry) -> Unit,
            onDelete: (ReceiveHistoryEntry) -> Unit
        ) {
            icon.setImageResource(if (entry.outgoing) R.drawable.ic_send else R.drawable.ic_background_receive)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(TvUi.accent(itemView.context))
            name.text = entry.displayName
            val peer = ReceiveHistoryText.sender(itemView.context, entry.senderAlias)
            val time = ReceiveHistoryText.receivedAt(itemView.context, entry.receivedAt)
            val result = itemView.context.getString(when(entry.result) {
                "sent" -> R.string.transfer_sent
                "failed" -> R.string.transfer_failed
                "cancelled" -> R.string.transfer_cancelled
                else -> if (entry.isMessage) R.string.tv_text_received else R.string.transfer_completed
            })
            details.text = (if (entry.outgoing) itemView.context.getString(R.string.tv_outgoing_history, peer, time)
                else itemView.context.getString(R.string.tv_from, peer) + " · " + time) + " · " + result
            itemView.setOnClickListener { onOpen(entry) }
            more.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menu.add(0, MENU_OPEN, 0, if (entry.isMessage) R.string.view_message else R.string.open_file)
                    menu.add(0, MENU_DETAILS, 1, R.string.file_details)
                    menu.add(0, MENU_DELETE, 2, R.string.delete_history_item)
                    setOnMenuItemClickListener { item: MenuItem ->
                        when (item.itemId) {
                            MENU_OPEN -> onOpen(entry)
                            MENU_DETAILS -> onDetails(entry)
                            MENU_DELETE -> onDelete(entry)
                        }
                        true
                    }
                }.show()
            }
        }

    }

    private companion object {
        const val MENU_OPEN = 1
        const val MENU_DETAILS = 2
        const val MENU_DELETE = 3
        fun formatBytes(bytes: Long): String = when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
            else -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
