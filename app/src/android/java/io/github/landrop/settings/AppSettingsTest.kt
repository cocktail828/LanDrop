package io.github.landrop.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppSettingsTest {
    private lateinit var preferences: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun screenAwakeIsDisabledByDefault() {
        assertFalse(AppSettings(InstrumentationRegistry.getInstrumentation().targetContext).keepScreenAwakeDuringTransfer())
    }

    @Test
    fun screenAwakeSettingPersists() {
        val settings = AppSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        preferences.edit().putBoolean("keep_screen_awake_during_transfer", true).commit()
        assertTrue(settings.keepScreenAwakeDuringTransfer())
    }

    @Test
    fun darkModeDefaultsToFollowSystem() {
        org.junit.Assert.assertEquals(
            DarkModePreference.FOLLOW_SYSTEM.id,
            AppSettings(InstrumentationRegistry.getInstrumentation().targetContext).darkMode()
        )
    }

    @Test
    fun darkModeSettingPersists() {
        val settings = AppSettings(InstrumentationRegistry.getInstrumentation().targetContext)
        preferences.edit().putString("dark_mode", DarkModePreference.ENABLED.id).commit()
        org.junit.Assert.assertEquals(DarkModePreference.ENABLED.id, settings.darkMode())
    }

}
