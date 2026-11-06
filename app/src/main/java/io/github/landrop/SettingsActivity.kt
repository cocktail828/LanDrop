package io.github.landrop

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.landrop.settings.AppSettings
import io.github.landrop.settings.AppLocale
import io.github.landrop.transfer.TransferService
import io.github.landrop.transfer.TransferServiceState
import io.github.landrop.ui.SystemBars
import io.github.landrop.ui.ThemeColors

/** Television settings: essential receiver controls, no protocol or appearance configuration. */
class SettingsActivity : LocalizedActivity() {
    private lateinit var settings: AppSettings
    private lateinit var deviceNameValue: TextView
    private lateinit var languageValue: TextView
    private lateinit var receiveDirectoryValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        deviceNameValue = findViewById(R.id.device_name_value)
        languageValue = findViewById(R.id.language_value)
        receiveDirectoryValue = findViewById(R.id.receive_directory_value)
        addReceiverSettings()
        findViewById<View>(R.id.settings_back_button).setOnClickListener { finish() }
        findViewById<View>(R.id.language_row).setOnClickListener { showLanguagePicker() }
        findViewById<View>(R.id.device_info_row).setOnClickListener { showDeviceNameEditor() }
        findViewById<View>(R.id.receive_directory_row).setOnClickListener { chooseReceiveDirectory() }
        findViewById<View>(R.id.about_button).setOnClickListener {
            AlertDialog.Builder(this).setTitle("LanDrop ${BuildConfig.VERSION_NAME}")
                .setItems(arrayOf(getString(R.string.settings_license), getString(R.string.settings_third_party_licenses))) { _, index ->
                    startActivity(TextDocumentActivity.intent(this,
                        getString(if (index == 0) R.string.settings_license else R.string.settings_third_party_licenses),
                        if (index == 0) "LICENSE" else "NOTICE"))
                }.setNegativeButton(R.string.close, null).show().also(ThemeColors::apply)
        }
        ThemeColors.apply(this)
        SystemBars.apply(this)
        refreshValues()
        findViewById<View>(R.id.receiver_background_row).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        ThemeColors.apply(this)
        SystemBars.apply(this)
        refreshValues()
    }

    private fun addReceiverSettings() {
        val background = findViewById<Switch>(R.id.receiver_background_switch).apply {
            isChecked = settings.backgroundReceiverEnabled()
            setOnCheckedChangeListener { _, enabled ->
                settings.setBackgroundReceiverEnabled(enabled)
                if (enabled) TransferServiceState.start(this@SettingsActivity)
                else TransferServiceState.stop(this@SettingsActivity)
                refreshFooter()
            }
        }
        val boot = findViewById<Switch>(R.id.receiver_boot_switch).apply {
            isChecked = settings.startOnBoot()
            setOnCheckedChangeListener { _, enabled -> settings.setStartOnBoot(enabled) }
        }
        val overlay = findViewById<Switch>(R.id.receiver_overlay_switch).apply {
            isChecked = settings.overlayConfirmation()
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && android.os.Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this@SettingsActivity)) {
                    isChecked = false
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                        Toast.makeText(this@SettingsActivity, R.string.receiver_overlay_retry, Toast.LENGTH_LONG).show()
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(this@SettingsActivity, R.string.receiver_overlay_unavailable, Toast.LENGTH_LONG).show()
                    }
                } else settings.setOverlayConfirmation(enabled)
            }
        }
        findViewById<View>(R.id.receiver_background_row).setOnClickListener { background.toggle() }
        findViewById<View>(R.id.receiver_boot_row).setOnClickListener { boot.toggle() }
        findViewById<View>(R.id.receiver_overlay_row).apply {
            visibility = if (io.github.landrop.receiver.IncomingConfirmationOverlay.isTelevision(this@SettingsActivity)) View.VISIBLE else View.GONE
            setOnClickListener { overlay.toggle() }
        }
        // This receiver always asks before saving.

    }

    private fun showDeviceNameEditor() {
        val content = layoutInflater.inflate(R.layout.dialog_edit_device_name, null)
        val input = content.findViewById<EditText>(R.id.device_name_editor).apply {
            filters = arrayOf(InputFilter.LengthFilter(64))
            setText(settings.deviceName())
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_device_name)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm_device_name, null)
            .create()
        dialog.show()
        ThemeColors.apply(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.error = getString(R.string.device_name_required)
                return@setOnClickListener
            }
            if (name != settings.deviceName()) {
                settings.saveDeviceName(name)
                deviceNameValue.text = settings.deviceName()
                // Identity reads the current preference; announce without restarting sockets.
                if (TransferServiceState.isRunning()) {
                    startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_ANNOUNCE_IDENTITY))
                }
                Toast.makeText(this, R.string.device_name_saved, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    private fun showLanguagePicker() {
        val languages = arrayOf(
            getString(R.string.language_system), getString(R.string.language_chinese), getString(R.string.language_english)
        )
        val codes = arrayOf(AppLocale.SYSTEM, AppLocale.CHINESE, AppLocale.ENGLISH)
        val selected = codes.indexOf(settings.language()).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(languages, selected) { dialog, which ->
                settings.setLanguage(codes[which])
                if (TransferServiceState.isRunning()) {
                    startService(Intent(this, TransferService::class.java).setAction(TransferService.ACTION_REFRESH_LANGUAGE))
                }
                dialog.dismiss()
                recreate()
            }
            .show()
        ThemeColors.apply(dialog)
    }

    private fun refreshValues() {
        deviceNameValue.text = settings.deviceName()
        languageValue.text = when(settings.language()) {
            AppLocale.CHINESE -> getString(R.string.language_chinese)
            AppLocale.ENGLISH -> getString(R.string.language_english)
            else -> getString(R.string.language_system)
        }
        receiveDirectoryValue.text = settings.receiveDirectoryName() ?: getString(R.string.tv_default_directory)
    }

    private fun chooseReceiveDirectory() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, RECEIVE_DIRECTORY_REQUEST)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.directory_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy Activity result API supports Android 5.0")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RECEIVE_DIRECTORY_REQUEST || resultCode != RESULT_OK) return
        val resultData = data ?: return
        val uri = resultData.data ?: return
        try {
            @Suppress("WrongConstant")
            contentResolver.takePersistableUriPermission(
                uri,
                resultData.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            )
            settings.saveReceiveDirectory(uri, directoryName(uri))
            refreshValues()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.settings_receive_directory_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun directoryName(uri: Uri): String = try {
        DocumentsContract.getTreeDocumentId(uri).substringAfterLast('/').ifBlank {
            getString(R.string.settings_selected_receive_directory)
        }
    } catch (_: Exception) {
        getString(R.string.settings_selected_receive_directory)
    }

    private companion object { const val RECEIVE_DIRECTORY_REQUEST = 1002 }
}
