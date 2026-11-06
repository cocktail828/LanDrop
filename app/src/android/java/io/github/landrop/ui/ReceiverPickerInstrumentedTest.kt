package io.github.landrop.ui

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.model.RemoteDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverPickerInstrumentedTest {
    @Test
    fun sendActionOpensDiscoveryListAndRefreshDoesNotAskForAnAddress() {
        withReceiverPicker {
            onView(withText(R.string.choose_receiver)).check(matches(isDisplayed()))
            onView(withId(R.id.send_devices)).check(matches(isDisplayed()))
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(R.string.choose_receiver)).check(matches(isDisplayed()))
            onView(withId(R.id.devices_back_button)).perform(click())
        }
    }

    @Test
    fun discoveredHttpAndHttpsReceiversBothAppearInThePicker() {
        val peers = listOf(
            RemoteDevice("HTTP receiver", "Linux", "desktop", "http-peer", "192.0.2.1", 53317, "http", false),
            RemoteDevice("HTTPS LocalSend", "Linux", "desktop", "A".repeat(64), "192.0.2.2", 53317, "https", false)
        )
        withReceiverPicker { scenario ->
            // Inspect the callback's UI update on the main thread so background
            // discovery cannot replace the injected snapshot between assertions.
            scenario.onActivity { activity ->
                activity.onDevicesChanged(peers)
                val list = activity.findViewById<RecyclerView>(R.id.device_list)
                val adapter = list.adapter as DeviceAdapter
                assertEquals(2, adapter.itemCount)
                peers.forEachIndexed { index, peer ->
                    val holder = adapter.onCreateViewHolder(list, 0)
                    adapter.onBindViewHolder(holder, index)
                    assertEquals(peer.alias, holder.itemView.findViewById<TextView>(R.id.device_alias).text.toString())
                }
                assertEquals(View.GONE, activity.findViewById<View>(R.id.discovery_status).visibility)
            }
        }
    }

    private fun withReceiverPicker(block: (ActivityScenario<MainActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("receiver-picker-", ".txt", context.cacheDir)
        try {
            file.writeText("receiver picker regression")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use(block)
        } finally {
            file.delete()
        }
    }
}
