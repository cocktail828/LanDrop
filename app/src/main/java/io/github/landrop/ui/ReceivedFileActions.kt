package io.github.landrop.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import io.github.landrop.R
import io.github.landrop.model.ReceivedFile
import io.github.landrop.transfer.ReceivedFileAccess
import java.io.FileNotFoundException
import java.util.concurrent.Executors

class ReceivedFileActions(
    private val activity: Activity,
    private val onDeleted: (ReceivedFile) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val access = ReceivedFileAccess(activity)
    private val deleting = mutableSetOf<android.net.Uri>()
    private var dialog: AlertDialog? = null

    fun show(file: ReceivedFile) {
        dismiss()
        dialog = RowActionsDialog.show(activity, file.displayName, listOf(
            R.string.open_received_file to { open(file) },
            R.string.delete_received_file to { confirmDelete(file) }
        ))
    }

    private fun open(file: ReceivedFile) {
        executor.execute {
            val error = runCatching { access.checkReadable(file.uri) }.exceptionOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (error != null) {
                    showError(error, R.string.open_file_failed)
                    return@runOnUiThread
                }
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(file.uri, file.mimeType.ifBlank { "application/octet-stream" })
                        clipData = ClipData.newRawUri(file.displayName, file.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: ActivityNotFoundException) {
                    toast(R.string.no_app_to_open_file)
                } catch (error: SecurityException) {
                    showError(error, R.string.open_file_failed)
                }
            }
        }
    }

    private fun confirmDelete(file: ReceivedFile) {
        if (file.uri in deleting) return
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.delete_file_title)
            .setMessage(activity.getString(R.string.delete_file_message, file.displayName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_received_file) { _, _ ->
                if (deleting.add(file.uri)) delete(file)
            }
            .create().also {
                it.show()
                ThemeColors.apply(it)
                it.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            }
    }

    private fun delete(file: ReceivedFile) {
        executor.execute {
            val result = runCatching { access.delete(file.uri) }
            activity.runOnUiThread {
                deleting.remove(file.uri)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val error = result.exceptionOrNull()
                if (error == null) {
                    onDeleted(file)
                    toast(if (result.getOrThrow()) R.string.file_deleted else R.string.file_deleted_history_failed)
                } else showError(error, R.string.delete_file_failed)
            }
        }
    }

    private fun showError(error: Throwable, fallback: Int) = toast(when (error) {
        is SecurityException -> R.string.file_access_denied
        is FileNotFoundException -> R.string.file_no_longer_exists
        else -> fallback
    })

    private fun toast(message: Int) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    override fun close() {
        dismiss()
        executor.shutdown()
    }
}
