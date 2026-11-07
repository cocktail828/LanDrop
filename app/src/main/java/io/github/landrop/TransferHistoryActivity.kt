package io.github.landrop

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.history.ReceiveHistoryStore
import io.github.landrop.history.ReceiveHistoryText
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.ReceiveHistoryEntry
import io.github.landrop.model.ReceivedFile
import io.github.landrop.model.RemoteDevice
import io.github.landrop.model.TransferSession
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.IncomingFileStore
import io.github.landrop.transfer.IncomingReceiveOptions
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.TransferService
import io.github.landrop.ui.ActiveTransferAdapter
import io.github.landrop.ui.ReceiveHistoryAdapter
import io.github.landrop.ui.ReceivedFileActions
import io.github.landrop.ui.RowActionsDialog
import io.github.landrop.ui.SystemBars
import io.github.landrop.ui.ThemeColors
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** One feed for current transfers and saved history, with new incoming content first. */
class TransferHistoryActivity : LocalizedActivity(), TransferService.Listener {
    private lateinit var empty: TextView
    private lateinit var historyStore: ReceiveHistoryStore
    private lateinit var historyAdapter: ReceiveHistoryAdapter
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private var historyLoading = false
    private var historyReloadPending = false
    private var latestIncomingHistoryId = 0L
    private val settings by lazy { AppSettings(this) }
    private val files = LinkedHashMap<String, ActiveTransferFile>()
    private val adapter = ActiveTransferAdapter(
        onFileClick = ::showFileActions,
        onSessionClick = ::showSessionActions
    )
    private lateinit var fileActions: ReceivedFileActions
    private var rowDialog: AlertDialog? = null
    private var transferService: TransferService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transferService = (binder as TransferService.LocalBinder).service()
            transferService?.addListener(this@TransferHistoryActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.apply(this)
        setContentView(R.layout.activity_transfer_history)
        ThemeColors.apply(this)
        empty = findViewById(R.id.transfer_history_empty)
        historyStore = ReceiveHistoryStore(this)
        historyAdapter = ReceiveHistoryAdapter(::showHistoryActions)
        findViewById<View>(R.id.history_button).visibility = View.GONE
        fileActions = ReceivedFileActions(this) { file ->
            val updated = files.values.filter { it.receivedFile?.uri == file.uri }
            updated.forEach { put(it.copy(receivedFile = null, fileDeleted = true)) }
            reloadHistory()
        }
        findViewById<android.view.View>(R.id.transfer_history_back_button).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.transfer_history_cancel_all).setOnClickListener { confirmCancelAll() }
        findViewById<RecyclerView>(R.id.transfer_history_list).apply {
            layoutManager = LinearLayoutManager(this@TransferHistoryActivity)
            adapter = ConcatAdapter(this@TransferHistoryActivity.adapter, historyAdapter)
            itemAnimator = null
        }
        findViewById<View>(R.id.open_directory_button).setOnClickListener { openDirectory() }
        findViewById<View>(R.id.clear_history_button).setOnClickListener { confirmClear() }
        findViewById<View>(R.id.history_button).setOnClickListener {
            findViewById<RecyclerView>(R.id.transfer_history_list).scrollToPosition(0)
        }
        findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        refresh()
        findViewById<android.view.View>(R.id.transfer_history_back_button).requestFocus()
        showReceivedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showReceivedText(intent)
    }

    private fun showReceivedText(intent: Intent) {
        val message = intent.getStringExtra(EXTRA_RECEIVED_TEXT) ?: return
        intent.removeExtra(EXTRA_RECEIVED_TEXT)
        io.github.landrop.ui.ReceivedTextPrompt.show(this, message)
        findViewById<RecyclerView>(R.id.transfer_history_list).scrollToPosition(0)
    }

    override fun onStart() {
        super.onStart()
        SystemBars.apply(this)
        ThemeColors.apply(this)
        if (!bound) {
            bindService(Intent(this, TransferService::class.java), connection, BIND_AUTO_CREATE)
            bound = true
        }
    }

    override fun onResume() {
        super.onResume()
        reloadHistory()
    }

    override fun onStop() {
        rowDialog?.dismiss()
        rowDialog = null
        fileActions.dismiss()
        incomingDialog?.dismiss()
        incomingDialog = null
        incomingRequest = null
        transferService?.removeListener(this)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        fileActions.close()
        historyExecutor.execute { historyStore.close() }
        historyExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Legacy Activity back handling supports Android 5.0")
    override fun onBackPressed() {
        if (!io.github.landrop.ui.ReceivedTextPrompt.dismiss(this)) super.onBackPressed()
    }

    override fun onDevicesChanged(devices: List<RemoteDevice>) = Unit
    override fun onDiscoveryError(message: String) = Unit

    override fun onIncomingTransferRequest(
        request: IncomingTransferManager.PrepareUploadRequest,
        decide: (IncomingReceiveOptions?) -> Unit
    ) {
        incomingDialog?.dismiss()
        incomingRequest = request
        incomingDialog = io.github.landrop.ui.IncomingDialogs.show(this, request, decide, onFilesAccepted = { })
        refresh()
    }

    private var incomingDialog: AlertDialog? = null
    private var incomingRequest: IncomingTransferManager.PrepareUploadRequest? = null
    override fun onIncomingRequestResolved(request: IncomingTransferManager.PrepareUploadRequest) {
        if (incomingRequest !== request) return
        incomingDialog?.dismiss()
        incomingDialog = null
        incomingRequest = null
        refresh()
    }

    override fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest) {
        request.files.forEach { (fileId, file) ->
            put(ActiveTransferFile(sessionId, fileId, file.fileName, 0L, file.size, ActiveTransferFile.Status.WAITING,
                peerAlias = request.info.alias))
        }
        findViewById<RecyclerView>(R.id.transfer_history_list).scrollToPosition(0)
    }

    override fun onOutgoingSessionPreparing(sessionId: String, files: List<ActiveTransferFile>) {
        files.forEach(::put)
    }

    override fun onOutgoingChecksumProgress(sessionId: String, current: Int, total: Int) {
        adapter.setChecksumProgress(sessionId, current, total)
    }

    override fun onOutgoingSessionPrepared(sessionId: String, files: List<ActiveTransferFile>) {
        files.forEach(::put)
    }

    override fun onOutgoingSessionStarted(preparationSessionId: String, sessionId: String, files: List<ActiveTransferFile>) {
        adapter.clearChecksumProgress(preparationSessionId)
        this.files.entries.removeAll { it.value.sessionId == preparationSessionId }
        files.forEach(::put)
        refresh()
    }

    override fun onActiveTransfersRestored(files: List<ActiveTransferFile>) {
        this.files.clear()
        files.forEach(::put)
        refresh()
    }

    override fun onFileReceiveProgress(file: ActiveTransferFile) {
        put(file)
    }

    override fun onFileSendProgress(file: ActiveTransferFile) {
        put(file)
    }

    override fun onFileReceiveCancelled(file: ActiveTransferFile, sessionComplete: Boolean) {
        put(file)
    }

    override fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean) {
        this.files["$sessionId:$fileId"]?.let {
            put(it.copy(receivedBytes = it.totalBytes, status = ActiveTransferFile.Status.COMPLETED, receivedFile = file))
        }
        reloadHistory()
    }

    override fun onHistoryChanged() = reloadHistory()
    override fun onIncomingSessionCompleted(sessionId: String) { refresh(); reloadHistory() }
    override fun onOutgoingSessionCompleted(sessionId: String) {
        adapter.clearChecksumProgress(sessionId)
        refresh()
        reloadHistory()
    }

    override fun onUploadStatus(message: String) = Unit

    override fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit) {
        val input = EditText(this).apply {
            hint = getString(R.string.pin_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        var replied = false
        fun respond(value: String?) {
            if (replied) return
            replied = true
            reply(value)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_required_title)
            .setMessage(getString(R.string.pin_required_message, device.alias, attempt))
            .setView(input)
            .setNegativeButton(R.string.cancel) { _, _ -> respond(null) }
            .setPositiveButton(R.string.submit, null)
            .setOnCancelListener { respond(null) }
            .create()
        dialog.show()
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = input.text.toString().trim()
            if (pin.isEmpty()) input.error = getString(R.string.invalid_pin) else {
                dialog.dismiss()
                respond(pin)
            }
        }
    }

    override fun onUploadProgress(fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long) {
    }

    override fun onTransferStateRestored(title: String, percent: Int) { refresh() }
    override fun onTransferFinished(message: String) { refresh() }
    override fun onUploadCompleted(names: List<String>) { refresh() }
    override fun onUploadError(message: String) { refresh(); reloadHistory() }

    private fun put(file: ActiveTransferFile) {
        files["${file.sessionId}:${file.fileId}"] = file
        refresh()
    }

    private fun reloadHistory() {
        if (historyExecutor.isShutdown) return
        if (historyLoading) { historyReloadPending = true; return }
        historyLoading = true
        historyExecutor.execute {
            val entries = historyStore.list().sortedWith(
                compareBy<ReceiveHistoryEntry> { it.outgoing }
                    .thenByDescending { it.receivedAt }.thenByDescending { it.id })
            runOnUiThread {
                historyLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val newestIncoming = entries.firstOrNull { !it.outgoing }?.id ?: 0L
                val hasNewIncoming = latestIncomingHistoryId != 0L && newestIncoming > latestIncomingHistoryId
                latestIncomingHistoryId = maxOf(latestIncomingHistoryId, newestIncoming)
                historyAdapter.submitEntries(entries)
                refresh()
                if (hasNewIncoming) findViewById<RecyclerView>(R.id.transfer_history_list).scrollToPosition(0)
                if (historyReloadPending) {
                    historyReloadPending = false
                    reloadHistory()
                }
            }
        }
    }

    private fun refresh() {
        val active = files.values.any(::isActive) || transferService?.isBusy() == true
        findViewById<View>(R.id.transfer_history_cancel_all).visibility = if (active) View.VISIBLE else View.GONE
        val sessions = files.values.map { it.sessionId }.distinct().asReversed()
            .withIndex().associate { it.value to it.index }
        // Saved terminal records move into history instead of appearing twice.
        val current = files.values.filter { isActive(it) || !settings.saveReceiveHistory() }
            .sortedWith(compareBy<ActiveTransferFile> { it.direction != ActiveTransferFile.Direction.INCOMING }
                .thenBy { sessions[it.sessionId] ?: 0 })
        adapter.submitFiles(current)
        val hasRows = current.isNotEmpty() || historyAdapter.itemCount > 0
        empty.visibility = if (hasRows) View.GONE else View.VISIBLE
        empty.text = transferService?.lastTransferError() ?: getString(R.string.tv_history_empty)
        findViewById<RecyclerView>(R.id.transfer_history_list).visibility = if (hasRows) View.VISIBLE else View.GONE
    }

    private fun isActive(file: ActiveTransferFile): Boolean =
        file.status == ActiveTransferFile.Status.WAITING || file.status == ActiveTransferFile.Status.TRANSFERRING

    private fun showFileActions(selected: ActiveTransferFile) {
        val file = files["${selected.sessionId}:${selected.fileId}"] ?: return
        rowDialog?.dismiss()
        if (isActive(file)) {
            rowDialog = RowActionsDialog.show(this, file.fileName, listOf(
                R.string.stop_transfer to {
                    if (file.direction == ActiveTransferFile.Direction.INCOMING) {
                        confirmCancelFile(file.sessionId, file.fileId)
                    } else {
                        confirmCancelSession(TransferSession(file.sessionId, file.direction,
                            files.values.filter { it.sessionId == file.sessionId && it.direction == file.direction }))
                    }
                }
            ))
        } else if (file.status == ActiveTransferFile.Status.COMPLETED && file.direction == ActiveTransferFile.Direction.INCOMING) {
            file.receivedFile?.let(fileActions::show)
        }
    }

    private fun showSessionActions(session: TransferSession) {
        rowDialog?.dismiss()
        rowDialog = RowActionsDialog.show(this, getString(
            if (session.direction == ActiveTransferFile.Direction.INCOMING) R.string.transfer_session_receiving
            else R.string.transfer_session_sending), listOf(R.string.stop_transfer to { confirmCancelSession(session) }))
    }

    private fun confirmCancelAll() {
        if (files.values.none(::isActive) && transferService?.isBusy() != true) return
        showStopConfirmation(R.string.cancel_all_transfers_title, getString(R.string.cancel_all_transfers_message),
            R.string.stop_all_transfers) { transferService?.cancelCurrent() }
    }

    private fun confirmCancelSession(session: TransferSession) {
        if (files.values.none { it.sessionId == session.sessionId && it.direction == session.direction && isActive(it) }) return
        showStopConfirmation(R.string.stop_transfer_title, getString(R.string.stop_transfer_message),
            R.string.stop_transfer) {
                if (files.values.any { it.sessionId == session.sessionId && it.direction == session.direction && isActive(it) }) {
                    transferService?.cancelSession(session.sessionId, session.direction)
                }
            }
    }

    private fun confirmCancelFile(sessionId: String, fileId: String) {
        val file = files.values.firstOrNull {
            it.sessionId == sessionId && it.fileId == fileId && it.direction == ActiveTransferFile.Direction.INCOMING
        } ?: return
        if (!isActive(file)) return
        showStopConfirmation(R.string.stop_transfer_file_title, getString(R.string.stop_transfer_file_message, file.fileName),
            R.string.stop_transfer) {
                if (files["$sessionId:$fileId"]?.let(::isActive) == true) transferService?.cancelIncomingFile(sessionId, fileId)
            }
    }

    private fun showStopConfirmation(@androidx.annotation.StringRes title: Int, message: String,
                                     @androidx.annotation.StringRes action: Int, stop: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.keep_transferring, null)
            .setPositiveButton(action) { _, _ -> stop() }
            .create()
        dialog.show()
        rowDialog = dialog
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }


    private fun showHistoryActions(entry: ReceiveHistoryEntry) {
        rowDialog?.dismiss()
        entry.receivedFile?.let {
            fileActions.show(it)
            return
        }
        val actions = mutableListOf<Pair<Int, () -> Unit>>()
        if (entry.isMessage && entry.result != "cancelled" && entry.result != "failed") {
            actions += R.string.view_message to { showMessage(entry) }
        }
        actions += R.string.file_details to { showDetails(entry) }
        actions += R.string.delete_history_item to { deleteEntry(entry) }
        rowDialog = RowActionsDialog.show(this, entry.displayName, actions)
    }

    private fun showMessage(entry: ReceiveHistoryEntry) {
        io.github.landrop.ui.ReceivedTextPrompt.show(this, entry.displayName)
    }

    private fun openDirectory() {
        val selectedTree = settings.receiveDirectoryUri()
        val localDirectory = selectedTree?.let(::primaryExternalDirectory) ?: defaultDirectory()
        val localUri = localDirectory?.let(::fileProviderUri)
        val folderUri = localUri ?: selectedTree?.let { tree ->
            // SAF destinations are already content URIs. Convert the tree URI
            // to its document URI, matching LocalSend's Android implementation.
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } ?: defaultDirectoryDocumentUri()
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            // open_filex uses */* for a plain filesystem directory path.
            // This broad type is what makes the system show all apps that can
            // accept the location, matching the official LocalSend behavior.
            val mimeType = if (localUri != null) "*/*" else DocumentsContract.Document.MIME_TYPE_DIR
            setDataAndType(folderUri, mimeType)
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            // LocalSend opens this generic ACTION_VIEW directly. Android then
            // resolves all file managers that support browsing directories.
            startActivity(viewIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, selectedTree ?: folderUri)
                    }
                })
            } catch (_: Exception) {
                Toast.makeText(this, R.string.open_directory_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun defaultDirectory(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            IncomingFileStore.FOLDER_NAME
        )

    private fun primaryExternalDirectory(treeUri: Uri): File? = try {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        if (treeId.startsWith("primary:")) {
            File(
                Environment.getExternalStorageDirectory(),
                treeId.removePrefix("primary:")
            )
        } else null
    } catch (_: Exception) {
        null
    }

    private fun fileProviderUri(directory: File): Uri? {
        return try {
            if (!directory.exists()) directory.mkdirs()
            FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", directory)
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultDirectoryDocumentUri(): Uri = DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
        "primary:${IncomingFileStore.RELATIVE_PATH.trimEnd('/')}"
    )

    private fun showDetails(entry: ReceiveHistoryEntry) {
        val detailsView = layoutInflater.inflate(R.layout.dialog_receive_file_details, null)
        detailsView.findViewById<TextView>(R.id.file_info_name).text = entry.displayName
        detailsView.findViewById<TextView>(R.id.file_info_path).text = runCatching { displayPath(entry) }.getOrDefault(entry.uri.toString().ifBlank { "—" })
        detailsView.findViewById<TextView>(R.id.file_info_size).text = formatBytes(entry.size)
        detailsView.findViewById<TextView>(R.id.file_info_sender).text = ReceiveHistoryText.sender(this, entry.senderAlias)
        detailsView.findViewById<TextView>(R.id.file_info_time).text = ReceiveHistoryText.receivedAt(this, entry.receivedAt)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.file_info_title)
            .setView(detailsView)
            .setPositiveButton(R.string.close, null)
            .show()
        ThemeColors.apply(dialog)
    }

    private fun confirmClear() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_history_records) { _, _ ->
                historyExecutor.execute {
                    historyStore.clear()
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) reloadHistory()
                    }
                }
            }
            .show()
        ThemeColors.apply(dialog)
    }

    private fun deleteEntry(entry: ReceiveHistoryEntry) {
        historyExecutor.execute {
            historyStore.delete(entry.id)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) reloadHistory()
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    }

    @Suppress("DEPRECATION")
    private fun displayPath(entry: ReceiveHistoryEntry): String {
        if (entry.uri.toString().isBlank()) return "—"
        if (DocumentsContract.isDocumentUri(this, entry.uri)) {
            val documentId = DocumentsContract.getDocumentId(entry.uri)
            if (documentId.startsWith("primary:")) {
                return "${Environment.getExternalStorageDirectory().absolutePath}/${documentId.removePrefix("primary:")}"
            }
            return documentId
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.query(
                entry.uri,
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val relativePath = cursor.getString(0)
                    val displayName = cursor.getString(1) ?: entry.displayName
                    if (!relativePath.isNullOrBlank()) {
                        return "/storage/emulated/0/$relativePath$displayName"
                    }
                }
            }
        }
        return entry.uri.toString()
    }

    companion object {
        const val EXTRA_RECEIVED_TEXT = "received_text"
        private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
    }
}
