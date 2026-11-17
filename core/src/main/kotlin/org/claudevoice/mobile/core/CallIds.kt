package org.claudevoice.mobile.core

/**
 * The call ids the phone has run, each kept [keepMillis] after it ran, as agent-protocol version 1
 * asks: "at least ten minutes". It holds at most [capacity]. When it is full of ids younger than
 * that, a new call is refused as busy rather than an id forgotten early, so no call can ever run
 * twice. With four connections at most, the cap is out of reach in practice; it is a bound, not a
 * limit anyone meets.
 *
 * [clock] is in milliseconds and must never go back: a monotonic one, not the wall clock. With
 * none given, [monotonicMillis] is used.
 */
class CallIds(
    private val clock: (() -> Long)? = null,
    private val keepMillis: Long = TEN_MINUTES,
    private val capacity: Int = CAPACITY,
) {
    /** Insertion order is time order, so the oldest is always first. */
    private val ran = LinkedHashMap<String, Long>()

    enum class Claim { RUN, DUPLICATE, BUSY }

    /** Claims [id] for a call about to run. Only [Claim.RUN] lets it run, and keeps the id. */
    @Synchronized
    fun claim(id: String): Claim {
        val now = clock?.invoke() ?: monotonicMillis()
        forgetOlderThan(now - keepMillis)
        return when {
            id in ran -> Claim.DUPLICATE
            ran.size >= capacity -> Claim.BUSY
            else -> {
                ran[id] = now
                Claim.RUN
            }
        }
    }

    private fun forgetOlderThan(oldest: Long) {
        val each = ran.values.iterator()
        while (each.hasNext() && each.next() <= oldest) each.remove()
    }

    companion object {
        const val TEN_MINUTES = 600_000L
        const val CAPACITY = 10_000
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Milliseconds from a clock that never goes back, unlike the wall clock. */
        fun monotonicMillis(): Long = System.nanoTime() / NANOS_PER_MILLI
    }
}
