package io.github.landrop.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.R
import io.github.landrop.SettingsActivity
import io.github.landrop.ui.ThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReadabilityInstrumentedTest {
    @Test
    fun sectionAndReceiverControlsHaveReadableTextInBothModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val previous = preferences.getString("dark_mode", null)
        try {
            for (mode in listOf(DarkModePreference.ENABLED, DarkModePreference.DISABLED)) {
                preferences.edit().putString("dark_mode", mode.id).commit()
                ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val labels = listOf(R.string.settings_general_section,
                            R.string.receiver_background_enabled, R.string.receiver_boot_enabled)
                        val texts = textViews(activity.findViewById(android.R.id.content)).toList()
                        for (label in labels) {
                            val text = texts.single { it.text.toString() == activity.getString(label) }
                            assertTrue("Unreadable ${text.text} in $mode",
                                ColorUtils.calculateContrast(text.currentTextColor, ThemeColors.backgroundColor(activity)) >= 4.5)
                        }
                        assertEquals(ThemeColors.primaryTextColor(activity),
                            texts.single { it.text.toString() == activity.getString(R.string.receiver_background_enabled) }.currentTextColor)
                    }
                }
            }
        } finally {
            preferences.edit().putString("dark_mode", previous).commit()
        }
    }

    @Test
    fun deviceNameChangesOnlyAfterConfirmAndRejectsBlankInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppSettings(context)
        val original = settings.deviceName()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                onView(withId(R.id.device_info_row)).perform(scrollTo(), click())
                onView(withId(R.id.device_name_editor)).perform(replaceText("Cancelled name"), closeSoftKeyboard())
                onView(withId(android.R.id.button2)).perform(click())
                assertEquals(original, settings.deviceName())
                onView(withId(R.id.device_info_row)).perform(scrollTo(), click())
                onView(withId(R.id.device_name_editor)).perform(replaceText("   "), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).perform(click())
                assertEquals(original, settings.deviceName())
                onView(withId(R.id.device_name_editor)).perform(replaceText("LanDrop test device"), closeSoftKeyboard())
                onView(withId(android.R.id.button1)).perform(click())
                assertEquals("LanDrop test device", settings.deviceName())
                scenario.onActivity { activity ->
                    assertEquals("LanDrop test device", activity.findViewById<TextView>(R.id.device_name_value).text.toString())
                }
            }
        } finally {
            settings.saveDeviceName(original)
        }
    }

    private fun textViews(view: View): Sequence<TextView> = sequence {
        if (view is TextView) yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(textViews(view.getChildAt(index)))
    }
}
