package io.github.landrop.ui

import android.app.Activity
import android.app.AlertDialog
import io.github.landrop.R

/** A single remote-friendly action menu shared by transfer and history rows. */
object RowActionsDialog {
    fun show(activity: Activity, title: String, actions: List<Pair<Int, () -> Unit>>): AlertDialog {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(actions.map { activity.getString(it.first) }.toTypedArray()) { _, position ->
                actions[position].second()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        ThemeColors.apply(dialog)
        dialog.listView.apply {
            requestFocus()
            setSelection(0)
        }
        return dialog
    }
}
