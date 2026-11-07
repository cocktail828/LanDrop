package io.github.landrop

import android.Manifest
import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.model.ReceivedFile
import io.github.landrop.model.RemoteDevice
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.PendingSendFile
import io.github.landrop.model.PendingSendQueue
import io.github.landrop.discovery.LocalNetworkAddress
import io.github.landrop.settings.AppSettings
import io.github.landrop.sharing.ShareIntentParser
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.IncomingReceiveOptions
import io.github.landrop.transfer.TransferService
import io.github.landrop.transfer.TransferServiceState
import io.github.landrop.ui.DeviceAdapter
import io.github.landrop.ui.PendingSendAdapter
import io.github.landrop.ui.SystemBars
import io.github.landrop.ui.ThemeColors
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : LocalizedActivity(), TransferService.Listener {
    private lateinit var statusText: TextView
    private lateinit var pendingSendBar: android.view.View
    private lateinit var pendingSendSummary: TextView
    private lateinit var localEndpointDeviceName: TextView
    private var currentPage = "home"
    private var selectedFiles: List<Uri> = emptyList()
    private var selectedRelativeNames: Map<Uri, String> = emptyMap()
    private var selectedMessageText: String? = null
    private var appliedLanguage: String? = null
    private var transferService: TransferService? = null
    private var bound = false
    private val settings by lazy { AppSettings(this) }
    private var discoveredDevices: List<RemoteDevice> = emptyList()
    private val deviceAdapter = DeviceAdapter(::sendToDevice)
    private val contentExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSendQueue = PendingSendQueue()
    private val pendingSendAdapter = PendingSendAdapter(::removePendingFile)
    private var pendingSendSheet: android.app.Dialog? = null
    private var appliedDarkMode: Boolean? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transferService = (binder as TransferService.LocalBinder).service()
            transferService?.addListener(this@MainActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedDarkMode = ThemeColors.isDark(this)
        SystemBars.apply(this)
        setContentView(R.layout.activity_main)
        ThemeColors.apply(this)
        statusText = findViewById(R.id.discovery_status)
        pendingSendBar = findViewById(R.id.pending_send_bar)
        pendingSendSummary = findViewById(R.id.pending_send_summary)
        localEndpointDeviceName = findViewById(R.id.local_endpoint_device_name)
        appliedLanguage = AppSettings(this).language()
        findViewById<android.view.View>(R.id.history_button).setOnClickListener {
            openTransferHistory()
        }
        findViewById<android.view.View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.pending_send_details).setOnClickListener { showPendingSendSheet() }
        findViewById<android.view.View>(R.id.device_pending_send_bar).setOnClickListener { showPendingSendSheet() }
        findViewById<android.view.View>(R.id.choose_receiver_button).setOnClickListener { showPage("devices") }
        findViewById<RecyclerView>(R.id.device_list).apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = deviceAdapter }
        findViewById<android.view.View>(R.id.refresh_button).setOnClickListener { transferService?.refreshDevices() }
        findViewById<android.view.View>(R.id.change_content_button).setOnClickListener { showPage("home") }
        findViewById<android.view.View>(R.id.enable_receiver_button).setOnClickListener {
            val restart = !settings.serverEnabled()
            settings.setBackgroundReceiverEnabled(true)
            settings.setServerEnabled(true)
            startTransferService()
            if (restart) startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_RELOAD_SETTINGS))
            updateLocalEndpoint()
        }
        findViewById<android.view.View>(R.id.background_button).setOnClickListener { moveTaskToBack(true) }
        findViewById<android.view.View>(R.id.devices_back_button).setOnClickListener { showPage("home") }
        findViewById<android.view.View>(R.id.content_action_file).setOnClickListener { chooseFile() }
        findViewById<android.view.View>(R.id.content_action_folder).setOnClickListener { chooseFolder() }
        findViewById<android.view.View>(R.id.content_action_clipboard).setOnClickListener { chooseClipboard() }
        restorePendingSendState(savedInstanceState)
        onDevicesChanged(emptyList())
        updateLocalEndpoint()
        requestLegacyStoragePermission()
        requestNotificationPermission()
        settings.initializeReceiver()
        if (settings.backgroundReceiverEnabled()) startTransferService()
        if (savedInstanceState == null) handleShareIntent(intent)
        showPage(savedInstanceState?.getString("tv_page") ?: "home")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        SystemBars.apply(this)
        ThemeColors.apply(this)
        if (!bound) { bindService(Intent(this, TransferService::class.java), connection, BIND_AUTO_CREATE); bound = true }
    }

    override fun onResume() {
        super.onResume()
        val darkMode = ThemeColors.isDark(this)
        if (ThemeColors.needsActivityRecreate(appliedDarkMode, darkMode)) {
            appliedDarkMode = darkMode
            recreate()
            return
        }
        appliedDarkMode = darkMode
        SystemBars.apply(this)
        ThemeColors.apply(this)
        val language = AppSettings(this).language()
        if (language != appliedLanguage) {
            appliedLanguage = language
            recreate()
            return
        }
        updateLocalEndpoint()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ThemeColors.apply(this)
            SystemBars.apply(this)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        val sharedContent = intent?.let(ShareIntentParser::parse) ?: return
        if (sharedContent.uris.isNotEmpty()) {
            persistReadPermissions(sharedContent.uris)
            setSelectedFiles(sharedContent.uris, sharedContent.text)
        } else {
            sharedContent.text?.let(::createOutgoingText)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tv_page", currentPage)
        if (selectedFiles.isNotEmpty()) {
            outState.putParcelableArrayList(KEY_PENDING_SEND_URIS, ArrayList(selectedFiles))
            outState.putStringArrayList("pending_relative_names", ArrayList(selectedFiles.map { selectedRelativeNames[it].orEmpty() }))
            selectedMessageText?.let { outState.putString(KEY_PENDING_SEND_MESSAGE, it) }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        incomingDialog?.dismiss()
        incomingDialog = null
        incomingRequest = null
        transferService?.removeListener(this)
        if (bound) { unbindService(connection); bound = false }
        super.onStop()
    }

    override fun onDestroy() {
        pendingSendSheet?.dismiss()
        contentExecutor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Legacy Activity back handling supports Android 5.0")
    override fun onBackPressed() {
        if (io.github.landrop.ui.ReceivedTextPrompt.dismiss(this)) return
        when (currentPage) {
            "devices" -> showPage("home")
            else -> moveTaskToBack(true)
        }
    }

    @Deprecated("Legacy Activity result API supports Android 5.0")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_REQUEST && resultCode == RESULT_OK) {
            val files = buildList {
                data?.clipData?.let { clips -> for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri) }
                    ?: data?.data?.let(::add)
            }
            persistReadPermissions(files)
            setSelectedFiles(files)
            return
        }
        if (requestCode == FOLDER_REQUEST && resultCode == RESULT_OK) {
            val treeUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            statusText.text = getString(R.string.content_action_folder_scanning)
            contentExecutor.execute {
                try {
                    val files = collectFolderFiles(treeUri)
                    mainHandler.post {
                        if (files.isEmpty()) {
                            Toast.makeText(this, R.string.content_action_folder_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            setSelectedFiles(files.keys.toList(), relativeNames = files)
                        }
                    }
                } catch (_: Exception) {
                    mainHandler.post { Toast.makeText(this, R.string.content_action_folder_failed, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun chooseFile() = launchFilePicker(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        type = "*/*"
    })

    private fun launchFilePicker(picker: Intent) {
        try {
            startActivityForResult(picker, FILE_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, FILE_REQUEST)
            } catch (_: android.content.ActivityNotFoundException) {
                startActivityForResult(Intent(this, LocalFilePickerActivity::class.java), FILE_REQUEST)
            }
        }
    }

    private fun chooseFolder() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, FOLDER_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.directory_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun setSelectedFiles(files: List<Uri>, messageText: String? = null, relativeNames: Map<Uri, String> = emptyMap()) {
        selectedFiles = files
        selectedRelativeNames = relativeNames
        selectedMessageText = messageText
        pendingSendQueue.clear()
        if (files.isNotEmpty()) {
            statusText.text = getString(R.string.files_selected, files.size)
            refreshPendingSendBar()
            showPage("home")
            contentExecutor.execute {
                val loaded = files.map { uri -> pendingFile(uri).let { file -> relativeNames[uri]?.let { file.copy(displayName = it) } ?: file } }
                mainHandler.post {
                    if (selectedFiles == files) {
                        pendingSendQueue.replace(loaded)
                        refreshPendingSendBar()
                        pendingSendSheet?.findViewById<RecyclerView>(R.id.pending_send_list)?.let {
                            pendingSendAdapter.submitFiles(loaded)
                            resizePendingSendList(it)
                        }
                    }
                }
            }
        } else {
            refreshPendingSendBar()
        }
    }

    @Suppress("DEPRECATION")
    private fun restorePendingSendState(state: Bundle?) {
        val files = state?.getParcelableArrayList<Uri>(KEY_PENDING_SEND_URIS).orEmpty()
        if (files.isNotEmpty()) {
            val names = state?.getStringArrayList("pending_relative_names").orEmpty()
            setSelectedFiles(files, state?.getString(KEY_PENDING_SEND_MESSAGE), files.mapIndexedNotNull { index, uri ->
                names.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { uri to it }
            }.toMap())
        }
    }

    private fun pendingFile(uri: Uri): PendingSendFile {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { getString(R.string.shared_file) }
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: name
                size = cursor.getLong(1)
            }
        }
        return PendingSendFile(uri, name, size)
    }

    private fun refreshPendingSendBar() {
        val visible = selectedFiles.isNotEmpty()
        pendingSendBar.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.device_pending_send_bar).visibility = pendingSendBar.visibility
        if (!visible) return
        val pendingFiles = pendingSendQueue.snapshot()
        val total = pendingFiles.sumOf { it.size }
        pendingSendSummary.text = if (pendingFiles.size == selectedFiles.size && pendingFiles.all { it.size >= 0 }) {
            getString(R.string.pending_send_summary_with_size, selectedFiles.size, formatBytes(total))
        } else {
            getString(R.string.pending_send_summary, selectedFiles.size)
        }
        findViewById<TextView>(R.id.device_pending_send_summary).text = pendingSendSummary.text
        findViewById<TextView>(R.id.pending_send_names).text = pendingFiles.joinToString("、") { it.displayName }
    }

    private fun removePendingFile(file: PendingSendFile) {
        selectedFiles = selectedFiles.filterNot { it == file.uri }
        pendingSendQueue.remove(file.uri)
        if (selectedFiles.size != 1) selectedMessageText = null
        refreshPendingSendBar()
        pendingSendSheet?.findViewById<RecyclerView>(R.id.pending_send_list)?.let {
            pendingSendAdapter.submitFiles(pendingSendQueue.snapshot())
            resizePendingSendList(it)
        }
        if (selectedFiles.isEmpty()) { pendingSendSheet?.dismiss(); showPage("home") }
    }

    private fun clearPendingFiles() {
        selectedFiles = emptyList()
        selectedRelativeNames = emptyMap()
        selectedMessageText = null
        pendingSendQueue.clear()
        refreshPendingSendBar()
        pendingSendSheet?.dismiss()
        showPage("home")
    }

    private fun showPendingSendSheet() {
        if (selectedFiles.isEmpty()) return
        pendingSendSheet?.dismiss()
        val content = layoutInflater.inflate(R.layout.dialog_pending_send, null)
        val dialog = android.app.Dialog(this)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        content.findViewById<TextView>(R.id.pending_send_summary).text = pendingSendSummary.text
        content.findViewById<RecyclerView>(R.id.pending_send_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = pendingSendAdapter
            itemAnimator = null
            pendingSendAdapter.submitFiles(pendingSendQueue.snapshot())
            resizePendingSendList(this)
        }
        content.findViewById<android.view.View>(R.id.pending_send_close).setOnClickListener { dialog.dismiss() }
        content.findViewById<android.view.View>(R.id.pending_send_done).setOnClickListener { dialog.dismiss() }
        content.findViewById<android.view.View>(R.id.pending_send_clear).setOnClickListener { clearPendingFiles() }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                gravity = Gravity.BOTTOM
                dimAmount = 0.20f
            }
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        ThemeColors.apply(content)
        pendingSendSheet = dialog
        dialog.setOnDismissListener { if (pendingSendSheet === dialog) pendingSendSheet = null }
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun resizePendingSendList(list: RecyclerView) {
        val density = resources.displayMetrics.density
        val rowHeight = (48 * density).toInt()
        val listPadding = (8 * density).toInt()
        val maximumHeight = (resources.displayMetrics.heightPixels * 9 / 16).toInt()
        list.layoutParams = list.layoutParams.apply {
            height = (pendingSendQueue.snapshot().size * rowHeight + listPadding)
                .coerceAtMost(maximumHeight)
        }
    }

    private fun openTransferHistory(message: String? = null) {
        startActivity(Intent(this, TransferHistoryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (message != null) putExtra(TransferHistoryActivity.EXTRA_RECEIVED_TEXT, message) })
    }

    private fun persistReadPermissions(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
    }

    private fun collectFolderFiles(treeUri: Uri): Map<Uri, String> {
        val files = linkedMapOf<Uri, String>()
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
        val rootName = contentResolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw java.io.IOException("Unable to read selected folder")
        val visited = hashSetOf<String>()
        fun visit(documentId: String, prefix: String) {
            if (!visited.add(documentId)) throw java.io.IOException("Cyclic document provider")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val childType = cursor.getString(1)
                    val name = cursor.getString(2)
                    val path = "$prefix/$name"
                    io.github.landrop.transfer.TransferPath.segments(path)
                    if (childType == DocumentsContract.Document.MIME_TYPE_DIR) visit(childId, path)
                    else files[DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)] = path
                }
            } ?: throw java.io.IOException("Unable to read folder contents")
        }
        io.github.landrop.transfer.TransferPath.segments(rootName)
        visit(rootDocumentId, rootName)
        return files
    }

    private fun chooseClipboard() {
        @Suppress("DEPRECATION")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, R.string.content_action_no_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        val text = clip.getItemAt(0).takeIf { it.uri == null }?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
        if (text != null) {
            createOutgoingText(text)
            return
        }
        val uris = buildList { for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add) }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.content_action_no_clipboard, Toast.LENGTH_SHORT).show()
            return
        }
        contentExecutor.execute {
            try {
                val copied = uris.mapIndexed { index, uri -> copyToCache(uri, "clipboard_$index") }
                mainHandler.post { setSelectedFiles(copied) }
            } catch (_: Exception) {
                mainHandler.post { Toast.makeText(this, R.string.content_action_no_clipboard, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun createOutgoingText(text: String) {
        contentExecutor.execute {
            try {
                val file = File(cacheDir, "message_${UUID.randomUUID()}.txt")
                file.writeText(text, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                mainHandler.post { setSelectedFiles(listOf(uri), text) }
            } catch (_: Exception) {
                mainHandler.post { Toast.makeText(this, R.string.content_action_text_empty, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun copyToCache(sourceUri: Uri, prefix: String): Uri {
        var name = sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty()
        contentResolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
        }
        if (name.isBlank()) name = "$prefix.bin"
        val file = File(cacheDir, "${prefix}_${UUID.randomUUID()}_${File(name).name}")
        contentResolver.openInputStream(sourceUri)?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            ?: throw IllegalStateException(getString(R.string.upload_cannot_read_file))
        return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }

    private fun showPage(page: String) {
        currentPage = if (page == "devices" && selectedFiles.isNotEmpty()) "devices" else "home"
        findViewById<android.view.View>(R.id.receive_home).visibility = if (currentPage == "home") android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.send_devices).visibility = if (currentPage == "devices") android.view.View.VISIBLE else android.view.View.GONE
        val target = when {
            currentPage == "devices" -> R.id.refresh_button
            selectedFiles.isNotEmpty() -> R.id.choose_receiver_button
            else -> R.id.content_action_file
        }
        findViewById<android.view.View>(target).requestFocus()
    }

    private fun sendToDevice(device: RemoteDevice) {
        if (selectedFiles.isEmpty()) { Toast.makeText(this, R.string.select_file_first, Toast.LENGTH_SHORT).show(); return }
        val files = selectedFiles
        val message = selectedMessageText
        transferService?.let {
            if (it.isBusy()) { Toast.makeText(this, R.string.receiver_busy, Toast.LENGTH_SHORT).show(); return }
            pendingSendSheet?.dismiss()
            it.send(files, device, message, selectedRelativeNames)
            showPage("home")
            openTransferHistory()
        } ?: Toast.makeText(this, R.string.service_starting, Toast.LENGTH_SHORT).show()
    }

    private fun startTransferService() {
        // This is called while the Activity is visible. Starting normally avoids
        // Xiaomi Android 16 treating the foreground-service promotion as late.
        TransferServiceState.start(this)
    }

    override fun onDevicesChanged(devices: List<RemoteDevice>) {
        discoveredDevices = devices.toList()
        deviceAdapter.submitDevices(discoveredDevices)
        statusText.visibility = if (discoveredDevices.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        statusText.setText(R.string.no_receivers_found)
        updateLocalEndpoint()
    }
    override fun onDiscoveryError(message: String) { statusText.text = getString(R.string.discovery_error, message); updateLocalEndpoint() }
    override fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest) = Unit
    override fun onOutgoingSessionPreparing(sessionId: String, files: List<ActiveTransferFile>) = Unit
    override fun onOutgoingChecksumProgress(sessionId: String, current: Int, total: Int) = Unit
    override fun onOutgoingSessionPrepared(sessionId: String, files: List<ActiveTransferFile>) = Unit
    override fun onOutgoingSessionStarted(preparationSessionId: String, sessionId: String, files: List<ActiveTransferFile>) = Unit
    override fun onActiveTransfersRestored(files: List<ActiveTransferFile>) = Unit
    override fun onUploadStatus(message: String) = Unit
    override fun onPinRequired(device: RemoteDevice, attempt: Int, reply: (String?) -> Unit) {
        if (isFinishing || isDestroyed) {
            reply(null)
            return
        }
        val content = layoutInflater.inflate(R.layout.dialog_pin, null)
        val message = content.findViewById<TextView>(R.id.pin_dialog_message)
        val input = content.findViewById<EditText>(R.id.pin_dialog_editor)
        input.inputType = InputType.TYPE_CLASS_TEXT
        message.text = getString(R.string.pin_required_message, device.alias, attempt)
        var replied = false
        fun respond(pin: String?) {
            if (replied) return
            replied = true
            reply(pin)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_required_title)
            .setView(content)
            .setNegativeButton(R.string.cancel) { _, _ -> respond(null) }
            .setPositiveButton(R.string.submit) { _, _ -> respond(input.text.toString()) }
            .setOnCancelListener { respond(null) }
            .create()
        dialog.show()
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = input.text.toString().trim()
            if (pin.isEmpty()) {
                input.error = getString(R.string.invalid_pin)
                return@setOnClickListener
            }
            dialog.dismiss()
            respond(pin)
        }
    }
    override fun onTransferStateRestored(title: String, percent: Int) = Unit
    override fun onUploadProgress(fileName: String, fileIndex: Int, fileCount: Int, sent: Long, total: Long, totalSent: Long, totalBytes: Long) = Unit
    override fun onUploadCompleted(names: List<String>) { clearPendingFiles() }
    override fun onUploadError(message: String) = Unit
    override fun onTransferFinished(message: String) = Unit

    private var incomingDialog: AlertDialog? = null
    private var incomingRequest: IncomingTransferManager.PrepareUploadRequest? = null

    override fun onIncomingTransferRequest(request: IncomingTransferManager.PrepareUploadRequest, decide: (IncomingReceiveOptions?) -> Unit) {
        if (isFinishing || isDestroyed) { decide(null); return }
        incomingDialog?.dismiss()
        incomingRequest = request
        incomingDialog = io.github.landrop.ui.IncomingDialogs.show(this, request, decide,
            onFilesAccepted = { openTransferHistory() }, onTextAccepted = { openTransferHistory(it) })
    }

    override fun onIncomingRequestResolved(request: IncomingTransferManager.PrepareUploadRequest) {
        if (incomingRequest !== request) return
        incomingDialog?.dismiss()
        incomingDialog = null
        incomingRequest = null
    }

    override fun onFileReceiveProgress(file: ActiveTransferFile) = Unit
    override fun onFileSendProgress(file: ActiveTransferFile) = Unit
    override fun onFileReceiveCancelled(file: ActiveTransferFile, sessionComplete: Boolean) = Unit
    override fun onFileReceived(sessionId: String, fileId: String, file: ReceivedFile, sessionComplete: Boolean) {
        val message = getString(R.string.download_completed, file.displayName)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    override fun onIncomingSessionCompleted(sessionId: String) = Unit
    override fun onOutgoingSessionCompleted(sessionId: String) = Unit

    private fun updateLocalEndpoint() {
        localEndpointDeviceName.text = settings.deviceName()
        val enabled = settings.backgroundReceiverEnabled() && settings.serverEnabled()
        val connected = LocalNetworkAddress.endpoints(this).isNotEmpty()
        findViewById<TextView>(R.id.receiver_status).setText(when {
            !enabled -> R.string.tv_paused
            !settings.serverEnabled() -> R.string.tv_receive_unavailable
            !connected -> R.string.tv_no_network
            !TransferServiceState.isRunning() -> R.string.tv_starting
            else -> R.string.tv_ready
        })
        findViewById<TextView>(R.id.receiver_description).setText(if (enabled) R.string.tv_receiver_description else R.string.tv_paused_description)
        findViewById<android.view.View>(R.id.enable_receiver_button).visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<TextView>(R.id.receiver_save_path).text = settings.receiveDirectoryName() ?: getString(R.string.tv_default_directory)
        findViewById<TextView>(R.id.receiver_footer).setText(if (enabled) R.string.tv_background_enabled else R.string.tv_paused)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
    }
    @TargetApi(Build.VERSION_CODES.M)
    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), LEGACY_STORAGE_PERMISSION_REQUEST)
    }
    private fun formatBytes(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0); bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0)); else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0)) }

    private companion object {
        const val KEY_PENDING_SEND_URIS = "pending_send_uris"
        const val KEY_PENDING_SEND_MESSAGE = "pending_send_message"
        const val FILE_REQUEST = 1001
        const val LEGACY_STORAGE_PERMISSION_REQUEST = 1002
        const val NOTIFICATION_PERMISSION_REQUEST = 1003
        const val FOLDER_REQUEST = 1007
    }
}
