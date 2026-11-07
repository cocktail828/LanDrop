package io.github.landrop.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import io.github.landrop.R
import io.github.landrop.TransferHistoryActivity
import io.github.landrop.model.ActiveTransferFile
import io.github.landrop.model.TransferSession
import org.junit.Assert.*
import org.junit.Test

class TransferCancellationUiInstrumentedTest {
    @Test fun globalStopRequiresConfirmationAndDefaultsToKeepingTransfers() {
        val labels = mutableMapOf<String, String>()
        ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.onActiveTransfersRestored(listOf(ActiveTransferFile(
                    "ui-confirmation-test", "file", "confirmation.bin", 0, 3, ActiveTransferFile.Status.WAITING)))
                labels["title"] = activity.getString(R.string.cancel_all_transfers_title)
                labels["keep"] = activity.getString(R.string.keep_transferring)
                labels["stop"] = activity.getString(R.string.stop_all_transfers)
            }
            onView(withId(R.id.transfer_history_cancel_all)).perform(click())
            onView(withText(labels.getValue("title"))).check(matches(isDisplayed()))
            onView(withId(android.R.id.button2)).check(matches(withText(labels.getValue("keep"))))
                .check(matches(hasFocus()))
            onView(withId(android.R.id.button1)).check(matches(withText(labels.getValue("stop"))))
            pressBack()
            onView(withId(android.R.id.button1)).check(doesNotExist())
            onView(withId(R.id.transfer_history_cancel_all)).check(matches(isDisplayed())).perform(click())
            onView(withId(android.R.id.button2)).perform(click())
            onView(withId(android.R.id.button1)).check(doesNotExist())
            onView(withId(R.id.transfer_history_cancel_all)).check(matches(isDisplayed()))
        }
    }

    @Test fun rowCallbacksKeepSessionDirectionAndFileIdsWithoutInlineButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = android.view.ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_LanDrop)
            val parent = FrameLayout(context)
            val screen = LayoutInflater.from(context).inflate(R.layout.activity_transfer_history, parent, false)
            val all = screen.findViewById<Button>(R.id.transfer_history_cancel_all)
            assertSame(screen.findViewById<View>(R.id.transfer_history_back_button).parent, all.parent)
            assertEquals(context.getString(R.string.stop_all_transfers), all.text.toString())
            val sessions = mutableListOf<TransferSession>()
            val files = mutableListOf<ActiveTransferFile>()
            val adapter = ActiveTransferAdapter({ files += it }, { sessions += it })
            val incoming = ActiveTransferFile("same-id", "incoming-file", "in.bin", 0, 3, ActiveTransferFile.Status.WAITING)
            val outgoing = incoming.copy(fileId = "outgoing-file", fileName = "out.bin", direction = ActiveTransferFile.Direction.OUTGOING)
            adapter.submitFiles(listOf(incoming, outgoing))
            for (position in listOf(0, 2)) {
                val header = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))
                adapter.onBindViewHolder(header, position)
                assertTrue(header.itemView.isFocusable)
                assertTrue(header.itemView.isClickable)
                assertFalse(hasButton(header.itemView))
                header.itemView.performClick()
            }
            assertEquals(listOf(ActiveTransferFile.Direction.INCOMING, ActiveTransferFile.Direction.OUTGOING), sessions.map { it.direction })
            assertEquals(listOf("same-id", "same-id"), sessions.map { it.sessionId })
            val incomingRow = adapter.onCreateViewHolder(parent, adapter.getItemViewType(1))
            adapter.onBindViewHolder(incomingRow, 1)
            incomingRow.itemView.performClick()
            assertEquals(listOf(incoming), files)
            assertFalse(hasButton(incomingRow.itemView))
            val outgoingRow = adapter.onCreateViewHolder(parent, adapter.getItemViewType(3))
            adapter.onBindViewHolder(outgoingRow, 3)
            outgoingRow.itemView.performClick()
            assertEquals(listOf(incoming, outgoing), files)
            assertFalse(hasButton(outgoingRow.itemView))
        }
    }

    @Test fun incomingFileRowShowsStopMenuThenConfirmation() {
        ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                activity.onActiveTransfersRestored(listOf(ActiveTransferFile(
                    "row-stop-test", "file", "stop-this-file.bin", 1, 3, ActiveTransferFile.Status.TRANSFERRING)))
            }
            onView(withText("stop-this-file.bin")).perform(click())
            onView(withText(R.string.stop_transfer)).check(matches(isDisplayed())).perform(click())
            onView(withText(R.string.stop_transfer_file_title)).check(matches(isDisplayed()))
            onView(withId(android.R.id.button2)).check(matches(hasFocus())).perform(click())
            onView(withText("stop-this-file.bin")).check(matches(isDisplayed()))
        }
    }

    private fun hasButton(view: View): Boolean = view is Button ||
        (view is android.view.ViewGroup && (0 until view.childCount).any { hasButton(view.getChildAt(it)) })
}
