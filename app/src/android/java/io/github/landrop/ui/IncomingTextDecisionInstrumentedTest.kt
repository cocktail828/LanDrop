package io.github.landrop.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import io.github.landrop.MainActivity
import io.github.landrop.TransferHistoryActivity
import io.github.landrop.R
import io.github.landrop.model.DeviceInfo
import io.github.landrop.transfer.IncomingTransferManager
import io.github.landrop.transfer.TransferService
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IncomingTextDecisionInstrumentedTest {
    private fun request() = IncomingTransferManager.PrepareUploadRequest(
        DeviceInfo("Peer", "2.0", "PC", "desktop", "peer", 53317, "http"),
        mapOf("message" to IncomingTransferManager.IncomingFile("message", "message.txt", 5, "text/plain", null, "hello")))

    @Test fun backRejectsTextAndAcceptRequiresExplicitConfirmation() {
        val decisions = AtomicInteger()
        val accepted = AtomicInteger()
        ActivityScenario.launch(TransferHistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                activity.onIncomingTransferRequest(request()) { options ->
                    decisions.incrementAndGet(); if (options != null) accepted.incrementAndGet()
                }
            }
            assertEquals(0, decisions.get())
            onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(hasFocus()))
            pressBack()
            assertEquals(1, decisions.get()); assertEquals(0, accepted.get())
            scenario.onActivity { activity -> activity.onIncomingTransferRequest(request()) { options ->
                decisions.incrementAndGet(); if (options != null) accepted.incrementAndGet()
            } }
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            assertEquals(2, decisions.get()); assertEquals(1, accepted.get())
            onView(withText(R.string.tv_text_received)).check(matches(isDisplayed()))
            onView(withId(R.id.received_text_copy)).check(matches(isDisplayed()))
            onView(withId(R.id.received_text_preview)).check(matches(withText("hello")))
        }
    }

    @Test fun staleResolutionCannotDismissTheNextConfirmation() {
        val currentReplies = AtomicInteger()
        val previous = request().let { it.copy(info = it.info.copy(alias = "Previous peer")) }
        val current = request().let { it.copy(info = it.info.copy(alias = "Current peer")) }
        var title = ""
        for (screen in listOf(MainActivity::class.java, TransferHistoryActivity::class.java)) {
            currentReplies.set(0)
            ActivityScenario.launch(screen).use { scenario ->
                scenario.onActivity { activity ->
                    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                    val listener = activity as TransferService.Listener
                    listener.onIncomingTransferRequest(previous) { }
                    listener.onIncomingTransferRequest(current) { currentReplies.incrementAndGet() }
                    listener.onIncomingRequestResolved(previous)
                    val field = screen.getDeclaredField("incomingDialog").apply { isAccessible = true }
                    assertTrue("Current confirmation was closed by the previous request", (field.get(activity) as? android.app.AlertDialog)?.isShowing == true)
                    title = activity.getString(R.string.incoming_message_title, current.info.alias)
                }
                onView(withText(title)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withId(android.R.id.button2)).inRoot(isDialog()).check(matches(hasFocus()))
                assertEquals(0, currentReplies.get())
                pressBack()
                assertEquals(1, currentReplies.get())
            }
        }
    }
}
