package io.github.landrop.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.landrop.history.ReceiveHistoryStore
import java.io.FileNotFoundException
import java.io.IOException

/** File operations for MediaStore, SAF documents, and legacy FileProvider URIs. */
class ReceivedFileAccess(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver

    fun checkReadable(uri: Uri) {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException()
        descriptor.use { }
    }

    /** Returns whether the associated history records were also removed. */
    fun delete(uri: Uri): Boolean {
        val deleted = if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null) > 0
        }
        if (!deleted) throw IOException("The file provider did not delete the file")
        // Notify the live service even if history cleanup fails after the file was deleted.
        context.sendBroadcast(Intent(ACTION_FILE_DELETED).setPackage(context.packageName)
            .putExtra(EXTRA_URI, uri.toString()))
        return runCatching {
            ReceiveHistoryStore(context).use { it.deleteFileRecords(uri) }
        }.isSuccess
    }

    companion object {
        const val ACTION_FILE_DELETED = "io.github.landrop.action.FILE_DELETED"
        const val EXTRA_URI = "file_uri"
    }
}
