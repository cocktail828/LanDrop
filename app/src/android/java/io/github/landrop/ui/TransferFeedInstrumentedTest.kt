package io.github.landrop.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.TransferHistoryActivity
import io.github.landrop.history.ReceiveHistoryStore
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.DeviceInfo
import io.github.landrop.model.ReceivedFile
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.TransferService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransferFeedInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val previous = preferences.all
    private val prefix = "feed-${System.nanoTime()}-"

    @Before fun isolateReceiver() {
        preferences.edit().putBoolean("background_receiver", true).putBoolean("server_enabled", false)
            .putBoolean("save_receive_history", true).commit()
    }

    @After fun cleanup() {
        context.stopService(Intent(context, TransferService::class.java))
        ReceiveHistoryStore(context).use { store ->
            store.list().filter { it.displayName.startsWith(prefix) }.forEach { store.delete(it.id) }
        }
        preferences.edit().apply {
            for (key in listOf("background_receiver", "server_enabled", "save_receive_history")) {
                val value = previous[key]
                if (value is Boolean) putBoolean(key, value) else remove(key)
            }
        }.commit()
    }

    @Test fun receivedHistoryIsNewestFirstAndVisibleWithCurrentTransfers() {
        val old = received("old.txt")
        val recent = received("recent.txt")
        ReceiveHistoryStore(context).use { store ->
            store.add(old, "Peer")
            store.add(recent, "Peer")
            store.addTransfer(prefix + "sent.txt", "Peer", 3, true, "sent")
        }
        ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
            awaitRows(scenario, 5)
            assertEquals(recent.displayName, rowText(scenario, 1, R.id.history_file_name))
            assertEquals(old.displayName, rowText(scenario, 2, R.id.history_file_name))
            assertEquals(prefix + "sent.txt", rowText(scenario, 4, R.id.history_file_name))
            scenario.onActivity { activity ->
                activity.onActiveTransfersRestored(listOf(
                    active("older", "older.bin"), active("newer", "newer.bin"),
                    active("outgoing", "sending.bin").copy(direction = ActiveTransferFile.Direction.OUTGOING)))
            }
            awaitRows(scenario, 11)
            assertEquals(prefix + "newer.bin", rowText(scenario, 1, R.id.active_transfer_name))
        }
    }

    @Test fun completingAReceiveMovesItIntoHistoryWithoutDuplicatingItsRow() {
        val old = received("old.txt")
        val completed = received("complete.txt")
        ReceiveHistoryStore(context).use { it.add(old, "Peer") }
        ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
            awaitRows(scenario, 2)
            scenario.onActivity { it.onActiveTransfersRestored(listOf(active("current", "complete.txt"))) }
            awaitRows(scenario, 4)
            ReceiveHistoryStore(context).use { it.add(completed, "Peer") }
            scenario.onActivity { it.onFileReceived("current", "file", completed, true) }
            awaitRows(scenario, 3)
            assertEquals(completed.displayName, rowText(scenario, 1, R.id.history_file_name))
            assertEquals(old.displayName, rowText(scenario, 2, R.id.history_file_name))
            onView(withText(completed.displayName)).perform(click())
            onView(withText(R.string.open_received_file)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.delete_received_file)).inRoot(isDialog()).check(matches(isDisplayed()))
        }
    }

    @Test fun acceptingFilesFromHomeAutomaticallyOpensTheUnifiedFeed() {
        val accepted = AtomicBoolean(false)
        val monitor = instrumentation.addMonitor(TransferHistoryActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    instrumentation.setInTouchMode(false)
                    val request = IncomingTransferManager.PrepareUploadRequest(
                        DeviceInfo("Peer", "2.0", "test", "desktop", "feed-peer", 53317, "http"),
                        mapOf("file" to IncomingTransferManager.IncomingFile("file", prefix + "incoming.bin", 3, "application/octet-stream", null)))
                    activity.onIncomingTransferRequest(request) { accepted.set(it != null) }
                }
                onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 3_000)
                assertTrue(accepted.get())
                assertTrue(opened is TransferHistoryActivity)
                instrumentation.runOnMainSync { opened?.finish() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun received(name: String) = ReceivedFile(prefix + name,
        Uri.parse("content://${context.packageName}.test/$prefix$name"), "text/plain", 3)

    private fun active(session: String, name: String) = ActiveTransferFile(session, "file", prefix + name,
        0, 3, ActiveTransferFile.Status.WAITING)

    private fun awaitRows(scenario: ActivityScenario<TransferHistoryActivity>, count: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        var actual = -1
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { actual = it.findViewById<RecyclerView>(R.id.transfer_history_list).adapter!!.itemCount }
            if (actual == count) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(20)
        }
        assertEquals(count, actual)
    }

    private fun rowText(scenario: ActivityScenario<TransferHistoryActivity>, position: Int, id: Int): String {
        var text: String? = null
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (text == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity {
                val list = it.findViewById<RecyclerView>(R.id.transfer_history_list)
                val row = list.findViewHolderForAdapterPosition(position)
                text = row?.itemView?.findViewById<TextView>(id)?.text?.toString()
                if (text == null) list.scrollToPosition(position)
            }
            if (text == null) Thread.sleep(20)
        }
        return requireNotNull(text) { "Row $position was never laid out" }
    }
}
