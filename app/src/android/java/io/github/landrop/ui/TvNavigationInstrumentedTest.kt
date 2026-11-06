package io.github.landrop.ui

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.settings.AppSettings
import org.junit.Test

class TvNavigationInstrumentedTest {
    @Test fun launcherDefaultsToReceivingAndBackFromSendReturnsHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        val enabled = settings.backgroundReceiverEnabled()
        try {
            settings.setBackgroundReceiverEnabled(true)
            ActivityScenario.launch(MainActivity::class.java).use {
                onView(withId(R.id.receive_home)).check(matches(isDisplayed()))
                onView(withId(R.id.send_devices)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.select_content_button)).check(matches(hasFocus())).perform(click())
                onView(withId(R.id.content_action_file)).check(matches(isDisplayed()))
                onView(withId(R.id.content_action_folder)).check(matches(isDisplayed()))
                onView(withId(R.id.content_action_clipboard)).check(matches(isDisplayed()))
                pressBack()
                onView(withId(R.id.receive_home)).check(matches(isDisplayed()))
                onView(withId(R.id.select_content_button)).check(matches(hasFocus()))
            }
        } finally { settings.setBackgroundReceiverEnabled(enabled) }
    }
}
