package io.github.landrop.transfer

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import io.github.landrop.BuildConfig
import io.github.landrop.model.ReceivedFile
import io.github.landrop.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Saves received files in shared Downloads while respecting scoped-storage rules. */
class IncomingFileStore(context: Context) {
    private val appContext = context.applicationContext
    @PublishedApi
    internal val resolver = appContext.contentResolver
    private val settings = AppSettings(appContext)

    fun create(
        displayName: String,
        mimeType: String,
        size: Long,
        receiveDirectoryUri: Uri? = settings.receiveDirectoryUri()
    ): Destination {
        val segments = TransferPath.segments(displayName)
        val safeName = segments.last()
        val parentSegments = segments.dropLast(1)
        receiveDirectoryUri?.let { treeUri ->
            try {
                return createInSelectedDirectory(treeUri, segments, mimeType, size)
            } catch (_: Exception) {
                // The user may have revoked access or removed the selected directory.
                settings.clearReceiveDirectory()
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH + parentSegments.joinToString("/", postfix = if (parentSegments.isEmpty()) "" else "/"))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Unable to create download entry")
            Destination(ReceivedFile(segments.joinToString("/"), uri, mimeType, size), null, mediaStorePending = true)
        } else {
            val root = legacyFolder().canonicalFile
            val folder = parentSegments.fold(root) { parent, name -> File(parent, name) }.canonicalFile
            require(folder == root || folder.path.startsWith(root.path + File.separator)) { "Invalid destination path" }
            if (!folder.isDirectory && !folder.mkdirs()) throw IOException("Unable to create download directory")
            val file = uniqueFile(folder, safeName)
            val uri = FileProvider.getUriForFile(appContext, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
            Destination(ReceivedFile((parentSegments + file.name).joinToString("/"), uri, mimeType, size), file, mediaStorePending = false)
        }
    }

    private fun createInSelectedDirectory(treeUri: Uri, segments: List<String>, mimeType: String, size: Long): Destination {
        var parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        for (name in segments.dropLast(1)) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(parent))
            var existing: Uri? = null
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) {
                        if (cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) throw IOException("Directory conflicts with a file")
                        existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                        break
                    }
                }
            } ?: throw IOException("Unable to query receive directory")
            parent = existing ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Unable to create receive subdirectory")
        }
        val uri = DocumentsContract.createDocument(resolver, parent, mimeType, segments.last())
            ?: throw IOException("Unable to create file in selected directory")
        return Destination(ReceivedFile(segments.joinToString("/"), uri, mimeType, size), null, mediaStorePending = false)
    }

    /** Opens the destination, runs the writer, and always closes the stream before returning. */
    @SuppressLint("Recycle")
    internal inline fun <T> withOutput(destination: Destination, writer: (OutputStream) -> T): T {
        val output = destination.legacyFile?.let(::FileOutputStream)
            ?: resolver.openOutputStream(destination.file.uri, "w")
            ?: throw IOException("Unable to open download entry")
        return output.use(writer)
    }

    fun complete(destination: Destination) {
        if (destination.mediaStorePending) {
            resolver.update(destination.file.uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
        } else {
            destination.legacyFile?.let { file ->
                MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(destination.file.mimeType), null)
            }
        }
    }

    fun discard(destination: Destination) {
        destination.legacyFile?.delete() ?: resolver.delete(destination.file.uri, null, null)
    }

    private fun legacyFolder(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        FOLDER_NAME
    )

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var index = 1
        while (candidate.exists()) candidate = File(folder, "${index++}_$name")
        return candidate
    }

    data class Destination(val file: ReceivedFile, val legacyFile: File?, val mediaStorePending: Boolean)

    companion object {
        const val FOLDER_NAME = "localsend"
        const val RELATIVE_PATH = "Download/$FOLDER_NAME/"
    }
}
