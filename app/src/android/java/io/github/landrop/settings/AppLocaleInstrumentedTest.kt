package io.github.landrop.settings

import android.content.res.Resources
import android.os.Build
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLocaleInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    @Suppress("DEPRECATION")
    fun unsupportedLanguagesFallBackToEnglish() {
        for (language in listOf("fr", "de", "tr", "ja", "ar")) {
            val localized = AppLocale.wrap(context, language)
            assertEnglish(localized)
            assertEquals("en", localized.resources.configuration.locale.language)
            assertEquals(View.LAYOUT_DIRECTION_LTR, localized.resources.configuration.layoutDirection)
        }
    }

    @Test
    fun explicitEnglishAndChineseStillWork() {
        assertEnglish(AppLocale.wrap(context, AppLocale.ENGLISH))
        assertChinese(AppLocale.wrap(context, AppLocale.CHINESE))
    }

    @Suppress("DEPRECATION")
    @Test
    fun systemDefaultUsesChineseOnlyForChineseSystemLanguage() {
        val system = Resources.getSystem().configuration
        val language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            system.locales[0].language
        } else {
            system.locale.language
        }
        val localized = AppLocale.wrap(context, AppLocale.SYSTEM)
        if (language == AppLocale.CHINESE) assertChinese(localized) else assertEnglish(localized)
    }

    private fun assertEnglish(localized: android.content.Context) {
        assertEquals("Cancel", localized.getString(R.string.cancel))
        assertEquals("OK", localized.getString(R.string.ok))
        assertEquals("Settings", localized.getString(R.string.settings))
        assertEquals("Select content to send", localized.getString(R.string.tv_select_content))
        assertEquals("File transfers", localized.getString(R.string.notification_channel_transfer))
        assertEquals("1 device found", localized.resources.getQuantityString(R.plurals.device_count, 1, 1))
        assertEquals("2 devices found", localized.resources.getQuantityString(R.plurals.device_count, 2, 2))
    }

    private fun assertChinese(localized: android.content.Context) {
        assertEquals("取消", localized.getString(R.string.cancel))
        assertEquals("确定", localized.getString(R.string.ok))
        assertEquals("设置", localized.getString(R.string.settings))
        assertEquals("选择内容发送", localized.getString(R.string.tv_select_content))
        assertEquals("文件传输", localized.getString(R.string.notification_channel_transfer))
        assertEquals("发现 2 台设备", localized.resources.getQuantityString(R.plurals.device_count, 2, 2))
    }
}
