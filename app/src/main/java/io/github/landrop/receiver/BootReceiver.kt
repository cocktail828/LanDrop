package io.github.landrop.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.TransferService

/** Starts only after the user has opened the app and enabled background reception. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AppSettings(context)
        if (!settings.receiverInitialized() || !settings.startOnBoot() || !settings.backgroundReceiverEnabled()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        } catch (exception: RuntimeException) {
            // OEM restrictions must not crash the boot receiver or silently change the user's preference.
            Log.w("LocalSendBoot", "Background start unavailable; open LocalSend to resume reception", exception)
        }
    }
}
