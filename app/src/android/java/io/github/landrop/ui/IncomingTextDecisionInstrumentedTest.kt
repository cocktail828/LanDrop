package io.github.landrop.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import io.github.landrop.MainActivity
import io.github.landrop.R
import io.github.landrop.model.DeviceInfo
import io.github.landrop.transfer.IncomingTransferManager
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.onIncomingTransferRequest(request()) { options ->
                decisions.incrementAndGet(); if (options != null) accepted.incrementAndGet()
            } }
            assertEquals(0, decisions.get())
            onView(withId(android.R.id.button2)).check(matches(hasFocus()))
            pressBack()
            assertEquals(1, decisions.get()); assertEquals(0, accepted.get())
            scenario.onActivity { activity -> activity.onIncomingTransferRequest(request()) { options ->
                decisions.incrementAndGet(); if (options != null) accepted.incrementAndGet()
            } }
            onView(withId(android.R.id.button1)).perform(click())
            assertEquals(2, decisions.get()); assertEquals(1, accepted.get())
            onView(withText(R.string.tv_text_received)).check(matches(isDisplayed()))
            onView(withText(R.string.tv_copy)).check(matches(isDisplayed()))
        }
    }
}
