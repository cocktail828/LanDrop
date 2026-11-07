package io.github.landrop.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import io.github.landrop.R

/** An explicit copy action, displayed in the page without opening another dialog or app. */
object ReceivedTextPrompt {
    fun show(activity: Activity, message: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val container = activity.findViewById<ViewGroup>(R.id.received_text_container) ?: return
        container.removeAllViews()
        val content = activity.layoutInflater.inflate(R.layout.view_received_text_prompt, container, false)
        content.findViewById<TextView>(R.id.received_text_preview).text = message
        content.findViewById<View>(R.id.received_text_copy).setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("LanDrop", message))
                Toast.makeText(activity, R.string.content_action_copied, Toast.LENGTH_SHORT).show()
                dismiss(activity)
            }
        }
        ThemeColors.apply(content)
        container.addView(content)
        container.visibility = View.VISIBLE
    }

    fun dismiss(activity: Activity): Boolean {
        val container = activity.findViewById<ViewGroup>(R.id.received_text_container) ?: return false
        if (container.childCount == 0) return false
        container.removeAllViews()
        container.visibility = View.GONE
        return true
    }
}
