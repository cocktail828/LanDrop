package io.github.landrop.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView
import io.github.landrop.R
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.IncomingReceiveOptions
import io.github.landrop.transfer.IncomingTransferManager

/** The foreground receiver and transfer screen use the same explicit accept/reject flow. */
object IncomingDialogs {
    fun show(activity: Activity, request: IncomingTransferManager.PrepareUploadRequest,
             decide: (IncomingReceiveOptions?) -> Unit, onFilesAccepted: () -> Unit,
             onTextAccepted: ((String) -> Unit)? = null): AlertDialog {
        val message = request.messageText()
        val settings = AppSettings(activity)
        val text = TextView(activity).apply {
            textSize = 17f
            setTextColor(ThemeColors.primaryTextColor(activity))
            setPadding(TvUi.dp(activity, 24), TvUi.dp(activity, 12), TvUi.dp(activity, 24), TvUi.dp(activity, 12))
            this.text = message ?: activity.getString(R.string.tv_incoming_summary, request.files.size,
                formatBytes(request.files.values.sumOf { it.size }),
                settings.receiveDirectoryName() ?: activity.getString(R.string.tv_default_directory)) +
                "\n\n" + request.files.values.take(8).joinToString("\n") { "${it.fileName} · ${formatBytes(it.size)}" } +
                if (request.files.size > 8) "\n…" else ""
        }
        text.measure(android.view.View.MeasureSpec.makeMeasureSpec(TvUi.dp(activity, 420), android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val contentHeight = text.measuredHeight.coerceAtMost(TvUi.dp(activity, 220))
        val scroll = ScrollView(activity).apply { addView(text); isFocusable = true }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(if (message == null) R.string.incoming_request_title else R.string.incoming_message_title, request.info.alias))
            .setView(scroll)
            .setNegativeButton(R.string.incoming_request_reject) { _, _ -> decide(null) }
            .setPositiveButton(R.string.incoming_request_accept) { _, _ ->
                decide(IncomingReceiveOptions(settings.receiveDirectoryUri()))
                if (message == null) onFilesAccepted()
                else if (onTextAccepted != null) onTextAccepted(message)
                else ReceivedTextPrompt.show(activity, message)
            }
            .setOnCancelListener { decide(null) }.create()
        dialog.show()
        scroll.layoutParams = scroll.layoutParams.apply { height = contentHeight }
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        return dialog
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
