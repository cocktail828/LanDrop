package io.github.landrop

import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.FileProvider
import io.github.landrop.settings.AppSettings
import io.github.landrop.ui.ThemeColors
import java.io.File
import java.util.concurrent.Executors

/** D-pad file picker for TVs without DocumentsUI. Never requests all-files access. */
class LocalFilePickerActivity : LocalizedActivity() {
    private data class Folder(val label: String, val file: File? = null, val tree: Uri? = null, val downloads: Boolean = false)
    private data class Entry(val label: String, val uri: Uri? = null, val folder: Folder? = null)
    private val worker = Executors.newSingleThreadExecutor()
    private val stack = mutableListOf<Folder>()
    private val selected = linkedMapOf<String, String>()
    private var entries = emptyList<Entry>()
    private lateinit var list: ListView
    private lateinit var title: TextView
    private lateinit var send: Button
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getStringArrayList("uris")?.forEach { selected[it] = it }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        title = TextView(this).apply { textSize = 20f }
        content.addView(title)
        content.addView(TextView(this).apply { setText(R.string.local_picker_scope) })
        content.addView(Button(this).apply {
            setText(R.string.navigate_up)
            setOnClickListener { goUp() }
        })
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            setOnItemClickListener { _, _, position, _ ->
                val entry = entries[position]
                if (entry.folder != null) {
                    stack += entry.folder
                    load()
                } else entry.uri?.toString()?.let { uri ->
                    if (selected.remove(uri) == null) selected[uri] = entry.label
                    setItemChecked(position, uri in selected)
                    updateSend()
                }
            }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        send = Button(this).apply {
            setOnClickListener {
                val uris = selected.keys.map(Uri::parse)
                if (uris.isNotEmpty()) {
                    val clips = ClipData.newUri(contentResolver, getString(R.string.receiver_send_files), uris.first())
                    uris.drop(1).forEach { clips.addItem(ClipData.Item(it)) }
                    setResult(RESULT_OK, Intent().apply {
                        data = uris.first()
                        clipData = clips
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                    finish()
                }
            }
        }
        content.addView(send)
        setContentView(content)
        ThemeColors.apply(this)
        updateSend()
        load()
    }

    private fun updateSend() {
        send.text = getString(R.string.local_picker_selected, selected.size)
        send.isEnabled = selected.isNotEmpty()
    }

    private fun load() {
        val request = ++generation
        val folder = stack.lastOrNull()
        title.text = folder?.label ?: getString(R.string.receiver_send_files)
        list.isEnabled = false
        worker.execute {
            val result = runCatching { folder?.let(::children) ?: roots() }
            runOnUiThread {
                if (request != generation || isFinishing || isDestroyed) return@runOnUiThread
                entries = result.getOrDefault(emptyList())
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice,
                    entries.map { if (it.folder != null) "▸ ${it.label}" else it.label })
                entries.forEachIndexed { index, entry -> list.setItemChecked(index, entry.uri?.toString() in selected) }
                list.isEnabled = true
                if (entries.isEmpty()) title.setText(if (result.isFailure) R.string.local_picker_failed else R.string.local_picker_empty)
                list.requestFocus()
            }
        }
    }

    private fun roots(): List<Entry> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Entry(getString(R.string.local_picker_downloads), folder = Folder(getString(R.string.local_picker_downloads), downloads = true)))
        } else {
            @Suppress("DEPRECATION")
            val shared = Environment.getExternalStorageDirectory()
            if (shared.canRead()) add(Entry(shared.path, folder = Folder(shared.path, file = shared)))
        }
        getExternalFilesDirs(null).filterNotNull().filter {
            it.canRead() && runCatching { FileProvider.getUriForFile(this@LocalFilePickerActivity, "$packageName.fileprovider", it) }.isSuccess
        }.forEach { file ->
            add(Entry(file.path, folder = Folder(file.path, file = file)))
        }
        AppSettings(this@LocalFilePickerActivity).receiveDirectoryUri()?.let { tree ->
            add(Entry(getString(R.string.settings_selected_receive_directory), folder = Folder(
                getString(R.string.settings_selected_receive_directory), tree = tree)))
        }
    }

    private fun children(folder: Folder): List<Entry> {
        folder.file?.let { parent ->
            return parent.listFiles().orEmpty().filter { it.canRead() }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).map { file ->
                if (file.isDirectory) Entry(file.name, folder = Folder(file.path, file = file))
                else Entry(file.name, FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
            }
        }
        if (folder.downloads && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val result = mutableListOf<Entry>()
            contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                arrayOf(packageName), "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { cursor ->
                while (cursor.moveToNext()) result += Entry(cursor.getString(1), ContentUris.withAppendedId(collection, cursor.getLong(0)))
            }
            return result
        }
        folder.tree?.let { document ->
            val id = if (DocumentsContract.isDocumentUri(this, document)) DocumentsContract.getDocumentId(document)
                else DocumentsContract.getTreeDocumentId(document)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(document, id)
            val result = mutableListOf<Entry>()
            contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(document, cursor.getString(0))
                    val name = cursor.getString(1)
                    result += if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                        Entry(name, folder = Folder(name, tree = uri)) else Entry(name, uri)
                }
            }
            return result.sortedWith(compareBy<Entry> { it.folder == null }.thenBy { it.label.lowercase() })
        }
        return emptyList()
    }

    private fun goUp() {
        if (stack.isEmpty()) finish() else { stack.removeAt(stack.lastIndex); load() }
    }

    @Deprecated("Legacy Activity API supports Android 5.0")
    override fun onBackPressed() = goUp()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("uris", ArrayList(selected.keys))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
