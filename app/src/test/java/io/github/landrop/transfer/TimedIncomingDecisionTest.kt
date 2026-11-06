package io.github.landrop.transfer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TimedIncomingDecisionTest {
    @Test
    fun oldNotificationCannotAcceptNewRequest() {
        val replies = mutableListOf<String?>()
        val decision = TimedIncomingDecision<String>("new-request", 60_000, replies::add)
        assertFalse(decision.resolve("old-request", 100, "accept"))
        assertFalse(decision.resolve(null, 100, "accept"))
        assertTrue(replies.isEmpty())
        assertTrue(decision.resolve("new-request", 101, "accept"))
        assertEquals(listOf("accept"), replies)
    }

    @Test
    fun acceptanceAtOrAfterExpiryRejectsWithoutSaving() {
        for (time in listOf(60_000L, 60_001L)) {
            val replies = mutableListOf<String?>()
            val decision = TimedIncomingDecision<String>("request", 60_000, replies::add)
            assertTrue(decision.resolve("request", time, "accept"))
            assertEquals(listOf<String?>(null), replies)
            assertFalse(decision.resolve("request", time, "accept"))
        }
    }

    @Test
    fun explicitRejectionCannotLaterBeReversedByAnotherUi() {
        val replies = mutableListOf<String?>()
        val decision = TimedIncomingDecision<String>("request", 60_000, replies::add)
        assertTrue(decision.resolve("request", 10, null))
        assertFalse(decision.resolve("request", 11, "accept"))
        assertEquals(listOf<String?>(null), replies)
    }

    @Test
    fun simultaneousUiActionsProduceOnlyOneReply() {
        val replies = AtomicInteger()
        val decision = TimedIncomingDecision<String>("request", 60_000) { replies.incrementAndGet() }
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val actions = listOf<String?>("accept", null).map { value ->
                workers.submit<Boolean> {
                    start.await()
                    decision.resolve("request", 100, value)
                }
            }
            start.countDown()
            assertEquals(1, actions.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, replies.get())
        } finally { workers.shutdownNow() }
    }
}
