package io.github.landrop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import io.github.landrop.settings.AppSettings
import io.github.landrop.settings.AppLocale

/** Applies the saved app language before Android inflates an Activity's views. */
abstract class LocalizedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        findViewById<View>(R.id.history_button)?.setOnClickListener {
            if (this !is ReceiveHistoryActivity) startActivity(Intent(this, ReceiveHistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        findViewById<View>(R.id.settings_button)?.setOnClickListener {
            if (this !is SettingsActivity) startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshFooter()
    }

    protected fun refreshFooter() {
        findViewById<TextView>(R.id.tv_footer_status)?.setText(
            if (AppSettings(this).backgroundReceiverEnabled()) R.string.tv_background_enabled else R.string.tv_paused)
    }
}
