package io.github.landrop.ui

import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.settings.AppSettings
import org.junit.Test

class TvNavigationInstrumentedTest {
    @Test fun launcherShowsContentActionsWithRemoteFocus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        val enabled = settings.backgroundReceiverEnabled()
        try {
            settings.setBackgroundReceiverEnabled(true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                    activity.findViewById<View>(R.id.content_action_file).requestFocus()
                }
                onView(withId(R.id.receive_home)).perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))
                scenario.onActivity { activity ->
                    org.junit.Assert.assertFalse(activity.findViewById<View>(R.id.receive_home).isInTouchMode)
                    org.junit.Assert.assertTrue(activity.findViewById<View>(R.id.content_action_file).requestFocus())
                }
                onView(withId(R.id.receive_home)).check(matches(isDisplayed()))
                onView(withId(R.id.send_devices)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.content_action_file)).check(matches(isDisplayed()))
                onView(withId(R.id.content_action_folder)).check(matches(isDisplayed()))
                onView(withId(R.id.content_action_clipboard)).check(matches(isDisplayed()))
                onView(withId(R.id.content_action_file)).check(matches(hasFocus()))
                    .perform(pressKey(KeyEvent.KEYCODE_DPAD_RIGHT))
                onView(withId(R.id.content_action_folder)).check(matches(hasFocus()))
                    .perform(pressKey(KeyEvent.KEYCODE_DPAD_RIGHT))
                onView(withId(R.id.content_action_clipboard)).check(matches(hasFocus()))
                onView(withId(R.id.pending_send_bar)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            }
        } finally { settings.setBackgroundReceiverEnabled(enabled) }
    }
}
