package io.github.landrop

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.ReceivedFile
import io.github.landrop.model.RemoteDevice
import io.github.landrop.model.TransferSession
import io.github.landrop.transfer.IncomingReceiveOptions
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.TransferService
import io.github.landrop.settings.AppSettings
import io.github.landrop.ui.ActiveTransferAdapter
import io.github.landrop.ui.SystemBars
import io.github.landrop.ui.ThemeColors

/** Displays active incoming and outgoing sessions independently of the home screen. */
class TransferCenterActivity : LocalizedActivity(), TransferService.Listener {
    private lateinit var empty: TextView
    private val files = LinkedHashMap<String, ActiveTransferFile>()
    private val adapter = ActiveTransferAdapter(
        onCancelFile = ::confirmCancelFile,
        onCancelSession = ::confirmCancelSession
    )
    private var transferService: TransferService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            transferService = (binder as TransferService.LocalBinder).service()
            transferService?.addListener(this@TransferCenterActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.apply(this)
        setContentView(R.layout.activity_transfer_center)
        ThemeColors.apply(this)
        empty = findViewById(R.id.transfer_center_empty)
        findViewById<android.view.View>(R.id.transfer_center_back_button).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.transfer_center_cancel_all).setOnClickListener { confirmCancelAll() }
        findViewById<RecyclerView>(R.id.transfer_center_list).apply {
            layoutManager = LinearLayoutManager(this@TransferCenterActivity)
            adapter = this@TransferCenterActivity.adapter
            itemAnimator = null
        }
        findViewById<android.view.View>(R.id.transfer_done).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.transfer_history).setOnClickListener { startActivity(Intent(this, ReceiveHistoryActivity::class.java)) }
        refresh()
        findViewById<android.view.View>(R.id.transfer_center_back_button).requestFocus()
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

    override fun onStop() {
        incomingDialog?.dismiss()
        incomingDialog = null
        transferService?.removeListener(this)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onDevicesChanged(devices: List<RemoteDevice>) = Unit
    override fun onDiscoveryError(message: String) = Unit

    override fun onIncomingTransferRequest(
        request: IncomingTransferManager.PrepareUploadRequest,
        decide: (IncomingReceiveOptions?) -> Unit
    ) {
        incomingDialog?.dismiss()
        incomingDialog = io.github.landrop.ui.IncomingDialogs.show(this, request, decide) { }
        refresh()
    }

    private var incomingDialog: AlertDialog? = null
    override fun onIncomingRequestResolved() { incomingDialog?.dismiss(); incomingDialog = null; refresh() }

    override fun onIncomingSessionPrepared(sessionId: String, request: IncomingTransferManager.PrepareUploadRequest) {
        request.files.forEach { (fileId, file) ->
            put(ActiveTransferFile(sessionId, fileId, file.fileName, 0L, file.size, ActiveTransferFile.Status.WAITING))
        }
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
            put(it.copy(receivedBytes = it.totalBytes, status = ActiveTransferFile.Status.COMPLETED))
        }
    }

    override fun onIncomingSessionCompleted(sessionId: String) = refresh()
    override fun onOutgoingSessionCompleted(sessionId: String) {
        adapter.clearChecksumProgress(sessionId)
        refresh()
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
    override fun onUploadError(message: String) { lastError = message; refresh() }

    private var lastError: String? = null

    private fun put(file: ActiveTransferFile) {
        files["${file.sessionId}:${file.fileId}"] = file
        refresh()
    }

    private fun refresh() {
        val active = files.values.any(::isActive) || transferService?.isBusy() == true
        findViewById<android.view.View>(R.id.transfer_center_cancel_all).visibility =
            if (active) android.view.View.VISIBLE else android.view.View.GONE
        val latest = transferService?.latestTransferSessionId()
        val visibleFiles = files.values.filter { latest == null || it.sessionId == latest }
        adapter.submitFiles(visibleFiles)
        val hasFiles = visibleFiles.isNotEmpty()
        empty.visibility = if (hasFiles) android.view.View.GONE else android.view.View.VISIBLE
        findViewById<RecyclerView>(R.id.transfer_center_list).visibility = if (hasFiles) android.view.View.VISIBLE else android.view.View.GONE
        val values = visibleFiles
        lastError = transferService?.lastTransferError()
        val terminal = (hasFiles || lastError != null) && transferService?.isBusy() != true && values.none { it.status == ActiveTransferFile.Status.WAITING || it.status == ActiveTransferFile.Status.TRANSFERRING }
        val result = findViewById<android.view.View>(R.id.transfer_result)
        val wasVisible = result.visibility == android.view.View.VISIBLE
        result.visibility = if (terminal) android.view.View.VISIBLE else android.view.View.GONE
        if (terminal) {
            val failed = lastError != null || values.any { it.status == ActiveTransferFile.Status.FAILED }
            val cancelled = values.any { it.status == ActiveTransferFile.Status.CANCELLED }
            val received = values.any { it.direction == ActiveTransferFile.Direction.INCOMING }
            findViewById<TextView>(R.id.transfer_result_title).setText(when {
                failed -> R.string.tv_transfer_error
                cancelled -> R.string.transfer_cancelled
                received -> R.string.tv_saved
                else -> R.string.transfer_sent
            })
            findViewById<TextView>(R.id.transfer_result_detail).text = when {
                failed -> lastError ?: getString(R.string.transfer_failed)
                cancelled -> getString(R.string.transfer_cancelled)
                received -> AppSettings(this).receiveDirectoryName() ?: getString(R.string.tv_default_directory)
                else -> getString(R.string.transfer_sent)
            }
            if (!wasVisible) findViewById<android.view.View>(R.id.transfer_done).requestFocus()
        }
    }

    private fun isActive(file: ActiveTransferFile): Boolean =
        file.status == ActiveTransferFile.Status.WAITING || file.status == ActiveTransferFile.Status.TRANSFERRING

    private fun confirmCancelAll() {
        if (files.values.none(::isActive) && transferService?.isBusy() != true) return
        showStopConfirmation(R.string.cancel_all_transfers_title, getString(R.string.cancel_all_transfers_message),
            R.string.stop_all_transfers) { transferService?.cancelCurrent() }
    }

    private fun confirmCancelSession(session: TransferSession) {
        if (session.files.none(::isActive)) return
        showStopConfirmation(R.string.stop_transfer_title, getString(R.string.stop_transfer_message),
            R.string.stop_transfer) { transferService?.cancelSession(session.sessionId, session.direction) }
    }

    private fun confirmCancelFile(sessionId: String, fileId: String) {
        val file = files.values.firstOrNull {
            it.sessionId == sessionId && it.fileId == fileId && it.direction == ActiveTransferFile.Direction.INCOMING
        } ?: return
        if (!isActive(file)) return
        showStopConfirmation(R.string.stop_transfer_file_title, getString(R.string.stop_transfer_file_message, file.fileName),
            R.string.stop_transfer_file) { transferService?.cancelIncomingFile(sessionId, fileId) }
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
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

}
