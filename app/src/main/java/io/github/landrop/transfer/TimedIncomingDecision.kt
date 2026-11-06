package io.github.landrop.transfer

import java.util.concurrent.atomic.AtomicBoolean

/** One request can be answered once; stale UI actions cannot authorize a later request. */
internal class TimedIncomingDecision<T>(
    val id: String,
    private val expiresAt: Long,
    private val reply: (T?) -> Unit
) {
    private val resolved = AtomicBoolean(false)

    fun resolve(requestId: String?, now: Long, value: T?): Boolean {
        if (requestId != id || !resolved.compareAndSet(false, true)) return false
        reply(if (now < expiresAt) value else null)
        return true
    }
}
