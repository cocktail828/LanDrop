package io.github.landrop.receiver

import android.app.AlertDialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import io.github.landrop.R
import io.github.landrop.settings.AppLocale
import io.github.landrop.transfer.IncomingTransferManager

/** Optional TV-only confirmation, authorized explicitly through the system overlay settings. */
internal class IncomingConfirmationOverlay(private val context: Context) {
    private var dialog: AlertDialog? = null

    fun show(request: IncomingTransferManager.PrepareUploadRequest, decide: (Boolean) -> Unit): Boolean {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context)
            else context.checkCallingOrSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!isTelevision(context) || !allowed) return false
        dismiss()
        return try {
            val wrapped = AppLocale.wrap(context)
            val summary = request.messageText()?.take(500) ?: request.files.values.take(8)
                .joinToString("\n") { it.fileName.take(120) }
            val details = if (request.messageText() == null) AppLocale.text(context,
                R.string.receiver_request_summary, request.files.size,
                "${request.files.values.sumOf { it.size } / 1024} KiB") + "\n" + summary else summary
            val title = AppLocale.text(context, R.string.notification_incoming_title, request.info.alias)
            val confirmation = AlertDialog.Builder(android.view.ContextThemeWrapper(wrapped, R.style.Theme_LanDrop))
                .setTitle(title)
                .setMessage(details)
                .setNegativeButton(R.string.incoming_request_reject) { _, _ -> decide(false) }
                .setPositiveButton(R.string.incoming_request_accept) { _, _ -> decide(true) }
                .setOnCancelListener { decide(false) }
                .create()
            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_PHONE
            confirmation.window?.setType(type)
            dialog = confirmation
            confirmation.show()
            io.github.landrop.ui.ThemeColors.apply(confirmation)
            confirmation.window?.apply {
                setGravity(android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                attributes = attributes.apply { x = io.github.landrop.ui.TvUi.dp(wrapped, 32) }
            }
            confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
            true
        } catch (_: RuntimeException) {
            dismiss()
            false // The notification remains available if a vendor blocks overlays.
        }
    }

    fun dismiss() {
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
    }

    companion object {
        fun isTelevision(context: Context): Boolean =
            (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature("android.software.leanback")
    }
}
