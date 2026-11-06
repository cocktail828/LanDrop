package io.github.landrop.receiver

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.TransferService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BootReceiverInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private var original: Map<String, *> = emptyMap<String, Any?>()

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        override fun startService(service: Intent): ComponentName {
            starts += service
            return ComponentName(this, TransferService::class.java)
        }
        override fun startForegroundService(service: Intent): ComponentName = startService(service)
    }

    @Before fun setUp() {
        original = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    @After fun tearDown() {
        preferences.edit().clear().apply {
            original.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }.commit()
    }

    @Test fun neverStartsBeforeFirstLaunch() {
        val recorder = RecordingContext(context)
        BootReceiver().onReceive(recorder, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(recorder.starts.isEmpty())
    }

    @Test fun bootStartsEnabledReceiverWithoutAnActivity() {
        AppSettings(context).initializeReceiver()
        val recorder = RecordingContext(context)
        BootReceiver().onReceive(recorder, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(TransferService::class.java.name, recorder.starts.single().component?.className)
        assertEquals(0, recorder.starts.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun userStopSurvivesBootAndPackageUpdate() {
        AppSettings(context).apply { initializeReceiver(); setBackgroundReceiverEnabled(false) }
        val recorder = RecordingContext(context)
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            BootReceiver().onReceive(recorder, Intent(action))
        }
        assertTrue(recorder.starts.isEmpty())
    }

    @Test fun disabledAutoStartAndUnrelatedBroadcastDoNotStartReceiver() {
        AppSettings(context).apply { initializeReceiver(); setStartOnBoot(false) }
        val recorder = RecordingContext(context)
        BootReceiver().onReceive(recorder, Intent(Intent.ACTION_BOOT_COMPLETED))
        AppSettings(context).setStartOnBoot(true)
        BootReceiver().onReceive(recorder, Intent(Intent.ACTION_SCREEN_ON))
        assertTrue(recorder.starts.isEmpty())
    }
}
