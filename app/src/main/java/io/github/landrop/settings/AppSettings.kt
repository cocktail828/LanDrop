package io.github.landrop.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.landrop.protocol.LocalSendProtocol

/** Persistent user choices that affect LocalSend identity and transfers. */
class AppSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    fun deviceName(): String = preferences.getString(DEVICE_NAME_KEY, null)?.trim().orEmpty()
        .ifBlank { defaultDeviceName() }

    fun saveDeviceName(name: String) {
        val normalized = name.trim()
        preferences.edit().apply {
            if (normalized.isBlank()) remove(DEVICE_NAME_KEY) else putString(DEVICE_NAME_KEY, normalized)
        }.apply()
    }

    fun deviceType(): String = DeviceType.fromValue(preferences.getString(DEVICE_TYPE_KEY, null)).value

    fun deviceModel(): String = preferences.getString(DEVICE_MODEL_KEY, null)?.trim().orEmpty()
        .ifBlank { defaultDeviceModel() }

    fun keepScreenAwakeDuringTransfer(): Boolean = preferences.getBoolean(KEEP_SCREEN_AWAKE_KEY, false)


    fun darkMode(): String = DarkModePreference.fromId(preferences.getString(DARK_MODE_KEY, null)).id


    fun language(): String = when (preferences.getString(LANGUAGE_KEY, AppLocale.SYSTEM)) {
        AppLocale.CHINESE -> AppLocale.CHINESE
        AppLocale.ENGLISH -> AppLocale.ENGLISH
        else -> AppLocale.SYSTEM
    }

    fun setLanguage(language: String) = preferences.edit().putString(
        LANGUAGE_KEY,
        when (language) {
            AppLocale.CHINESE -> AppLocale.CHINESE
            AppLocale.ENGLISH -> AppLocale.ENGLISH
            else -> AppLocale.SYSTEM
        }
    ).apply()

    fun createChecksums(): Boolean = preferences.getBoolean(CREATE_CHECKSUMS_KEY, true)

    /** A non-blank PIN enables the LocalSend prepare-upload PIN gate. */
    fun receivePin(): String? = preferences.getString(RECEIVE_PIN_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun receiveDirectoryUri(): Uri? = preferences.getString(RECEIVE_DIRECTORY_URI_KEY, null)?.let(Uri::parse)
    fun receiveDirectoryName(): String? = preferences.getString(RECEIVE_DIRECTORY_NAME_KEY, null)
    fun saveReceiveDirectory(uri: Uri, name: String) = preferences.edit()
        .putString(RECEIVE_DIRECTORY_URI_KEY, uri.toString())
        .putString(RECEIVE_DIRECTORY_NAME_KEY, name)
        .apply()
    fun clearReceiveDirectory() = preferences.edit()
        .remove(RECEIVE_DIRECTORY_URI_KEY)
        .remove(RECEIVE_DIRECTORY_NAME_KEY)
        .apply()

    fun saveReceiveHistory(): Boolean = preferences.getBoolean(SAVE_HISTORY_KEY, true)

    fun verifyReceivedChecksums(): Boolean = preferences.getBoolean(VERIFY_RECEIVED_CHECKSUMS_KEY, true)

    fun receiverInitialized(): Boolean = preferences.getBoolean("receiver_initialized", false)
    fun initializeReceiver() = preferences.edit().putBoolean("receiver_initialized", true).apply()
    fun backgroundReceiverEnabled(): Boolean = preferences.getBoolean("background_receiver", true)
    fun setBackgroundReceiverEnabled(enabled: Boolean) = preferences.edit().putBoolean("background_receiver", enabled).apply()
    fun startOnBoot(): Boolean = preferences.getBoolean("start_on_boot", true)
    fun setStartOnBoot(enabled: Boolean) = preferences.edit().putBoolean("start_on_boot", enabled).apply()
    fun overlayConfirmation(): Boolean = preferences.getBoolean("overlay_confirmation", false)
    fun setOverlayConfirmation(enabled: Boolean) = preferences.edit().putBoolean("overlay_confirmation", enabled).apply()

    fun serverEnabled(): Boolean = preferences.getBoolean(SERVER_ENABLED_KEY, true)
    fun setServerEnabled(enabled: Boolean) = preferences.edit().putBoolean(SERVER_ENABLED_KEY, enabled).apply()

    fun port(): Int = preferences.getInt(PORT_KEY, LocalSendProtocol.DEFAULT_PORT).takeIf { it in 1..65535 }
        ?: LocalSendProtocol.DEFAULT_PORT

    fun multicastAddress(): String = preferences.getString(MULTICAST_ADDRESS_KEY, LocalSendProtocol.MULTICAST_ADDRESS)
        ?.takeIf(::isIpv4Multicast) ?: LocalSendProtocol.MULTICAST_ADDRESS

    private fun defaultDeviceName(): String = runCatching {
        android.provider.Settings.Global.getString(resolver, "device_name")
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: Build.MODEL.ifBlank { "Android" }

    private fun defaultDeviceModel(): String = Build.MANUFACTURER.ifBlank { Build.MODEL.ifBlank { "Android" } }

    private fun isIpv4Multicast(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4) return false
        val values = parts.map { it.toIntOrNull() ?: return false }
        return values.all { it in 0..255 } && values.first() in 224..239
    }

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
        const val DEVICE_NAME_KEY = "device_name"
        const val DEVICE_TYPE_KEY = "device_type"
        const val DEVICE_MODEL_KEY = "device_model"
        const val KEEP_SCREEN_AWAKE_KEY = "keep_screen_awake_during_transfer"
        const val DARK_MODE_KEY = "dark_mode"
        const val LANGUAGE_KEY = "language"
        const val CREATE_CHECKSUMS_KEY = "create_checksums"
        const val RECEIVE_PIN_KEY = "receive_pin"
        const val RECEIVE_DIRECTORY_URI_KEY = "receive_directory_uri"
        const val RECEIVE_DIRECTORY_NAME_KEY = "receive_directory_name"
        const val SAVE_HISTORY_KEY = "save_receive_history"
        const val VERIFY_RECEIVED_CHECKSUMS_KEY = "verify_received_checksums"
        const val SERVER_ENABLED_KEY = "server_enabled"
        const val PORT_KEY = "port"
        const val MULTICAST_ADDRESS_KEY = "multicast_address"
    }
}
