package io.github.landrop.ui

import android.content.Intent
import androidx.core.content.FileProvider
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
import java.io.File
import org.junit.Test

class ReceiverPickerInstrumentedTest {
    @Test
    fun sendActionOpensDiscoveryListAndRefreshDoesNotAskForAnAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("receiver-picker-", ".txt", context.cacheDir)
        try {
            file.writeText("receiver picker regression")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ActivityScenario.launch<MainActivity>(intent).use {
                onView(withText(R.string.choose_receiver)).check(matches(isDisplayed()))
                onView(withId(R.id.send_devices)).check(matches(isDisplayed()))
                onView(withId(R.id.refresh_button)).perform(click())
                onView(withText(R.string.choose_receiver)).check(matches(isDisplayed()))
                onView(withId(R.id.devices_back_button)).perform(click())
            }
        } finally {
            file.delete()
        }
    }
}
