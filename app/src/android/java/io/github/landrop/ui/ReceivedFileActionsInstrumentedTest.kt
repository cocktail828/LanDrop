package io.github.landrop.ui

import android.content.Intent
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.R
import io.github.landrop.TransferHistoryActivity
import io.github.landrop.history.ReceiveHistoryStore
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import io.github.landrop.settings.AppSettings
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.TransferService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReceivedFileActionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun historyRowOnlyOpensMenuAndDeletionNeedsExplicitConfirmation() {
        val local = File.createTempFile("history-actions-", ".txt", context.cacheDir).apply { writeText("hello") }
        val received = receivedFile(local)
        ReceiveHistoryStore(context).use { store ->
            store.add(received, "Test peer")
            try {
                ActivityScenario.launch(TransferHistoryActivity::class.java).use {
                    onView(withText(local.name)).perform(click())
                    onView(withText(R.string.open_received_file)).check(matches(isDisplayed()))
                    onView(withText(R.string.delete_received_file)).check(matches(isDisplayed()))
                    assertTrue(local.exists())
                    pressBack()
                    assertTrue(local.exists())
                    onView(withText(local.name)).perform(click())
                    onView(withText(R.string.delete_received_file)).perform(click())
                    onView(withId(android.R.id.button2)).check(matches(hasFocus())).perform(click())
                    assertTrue(local.exists())
                    assertTrue(store.list().any { it.uri == received.uri })
                    onView(withText(local.name)).perform(click())
                    onView(withText(R.string.delete_received_file)).perform(click())
                    onView(withId(android.R.id.button1)).perform(click())
                    await { !local.exists() && store.list().none { it.uri == received.uri } }
                }
            } finally {
                local.delete()
                store.deleteFileRecords(received.uri)
            }
        }
    }

    @Test fun completedFileSurvivesRecreationWithoutHistoryAndDeletionUpdatesService() {
        val local = File.createTempFile("transfer-actions-", ".txt", context.cacheDir).apply { writeText("hello") }
        val received = receivedFile(local)
        val settings = AppSettings(context)
        val historyEnabled = settings.saveReceiveHistory()
        val preferences = context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
        val connected = CountDownLatch(1)
        lateinit var service: TransferService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as TransferService.LocalBinder).service()
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        preferences.edit().putBoolean("save_receive_history", false).commit()
        assertTrue(context.bindService(Intent(context, TransferService::class.java), connection, android.content.Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            service.onIncomingSessionPrepared("file-actions-test", IncomingTransferManager.PrepareUploadRequest(
                DeviceInfo("Peer", "2.0", "PC", "desktop", "peer", 53317, "http"),
                mapOf("file" to IncomingTransferManager.IncomingFile("file", local.name, local.length(), "text/plain", null))))
            service.onFileReceived("file-actions-test", "file", received, true)
            ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                onView(withText(local.name)).perform(click())
                onView(withText(R.string.open_received_file)).check(matches(isDisplayed()))
                onView(withText(R.string.delete_received_file)).perform(click())
                onView(withId(android.R.id.button1)).perform(click())
                await { !local.exists() }
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                onView(withText(R.string.file_deleted)).check(matches(isDisplayed()))
                ReceiveHistoryStore(context).use { store ->
                    assertTrue(store.list().none { it.uri == received.uri })
                }
            }
        } finally {
            context.unbindService(connection)
            context.stopService(Intent(context, TransferService::class.java))
            preferences.edit().putBoolean("save_receive_history", historyEnabled).commit()
            local.delete()
            ReceiveHistoryStore(context).use { it.deleteFileRecords(received.uri) }
        }
    }

    @Test fun recycledRowsDoNotKeepActionsForFailedOutgoingOrDeletedFiles() {
        instrumentation.runOnMainSync {
            val themed = android.view.ContextThemeWrapper(context, R.style.Theme_LanDrop)
            val parent = FrameLayout(themed)
            val clicked = mutableListOf<ActiveTransferFile>()
            val adapter = ActiveTransferAdapter({ clicked += it }, {})
            val received = ReceivedFile("row.txt", android.net.Uri.parse("content://test/row"), "text/plain", 5)
            val file = ActiveTransferFile("row-test", "file", received.displayName, 5, 5,
                ActiveTransferFile.Status.COMPLETED, receivedFile = received)
            adapter.submitFiles(listOf(file))
            val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))
            adapter.onBindViewHolder(holder, 1)
            assertTrue(holder.itemView.isFocusable)
            holder.itemView.performClick()
            assertEquals(listOf(file), clicked)
            for (inactive in listOf(
                file.copy(status = ActiveTransferFile.Status.FAILED),
                file.copy(status = ActiveTransferFile.Status.CANCELLED),
                file.copy(direction = ActiveTransferFile.Direction.OUTGOING),
                file.copy(receivedFile = null, fileDeleted = true))) {
                adapter.submitFiles(listOf(inactive))
                adapter.onBindViewHolder(holder, 1)
                assertFalse(holder.itemView.isFocusable)
                assertFalse(holder.itemView.isClickable)
                assertFalse(holder.itemView.performClick())
                assertEquals(1, clicked.size)
            }
        }
    }

    private fun receivedFile(file: File) = ReceivedFile(file.name,
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), "text/plain", file.length())

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
        assertTrue("Timed out waiting for the file operation", condition())
    }
}
