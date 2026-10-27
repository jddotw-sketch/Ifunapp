package eu.kanade.tachiyomi.ui.browse.source.browse

/**
 * Tunes the number of concurrent catalog requests using saved items per second.
 *
 * Always try more parallelism first (doubling, up to 64). Only if that stops
 * improving throughput, try less parallelism (halving). Never use +/-1 probes.
 * Only committed pages contribute to a rate sample.
 */
internal class SourceImportAutoTuner(
    private val nowMillis: () -> Long,
    maximum: Int = 64,
    startingLimit: Int = 16,
) {
    private val maximum = maximum.coerceIn(1, 64)

    private enum class ProbeDirection { INCREASE, DECREASE }

    @Volatile
    var limit = startingLimit.coerceIn(1, this.maximum)
        private set

    @Volatile
    var searching = true
        private set

    @Volatile
    var nextLimit: Int? = null
        private set

    private var bestLimit = limit
    private var bestRate = 0.0
    private var startingProbeLimit = limit
    private var direction = ProbeDirection.INCREASE
    private var startedAt = nowMillis()
    private var settledAt: Long? = null
    private var firstKey = 2
    private var pages = 0
    private var items = 0L
    private var pausedForRecovery = false

    private fun higher(from: Int): Int? =
        if (from < maximum) (from * 2).coerceAtMost(maximum) else null

    private fun lower(from: Int): Int? =
        if (from > 1) (from / 2).coerceAtLeast(1) else null

    private fun settle(now: Long) {
        searching = false
        settledAt = now
        nextLimit = bestLimit.takeIf { it != limit }
    }

    private fun startProbing(now: Long, rate: Double) {
        startingProbeLimit = limit
        bestLimit = limit
        bestRate = rate
        searching = true
        direction = ProbeDirection.INCREASE
        settledAt = null
        // If we are already at the ceiling, decrease is the only possible test.
        nextLimit = higher(limit) ?: run {
            direction = ProbeDirection.DECREASE
            lower(limit)
        }
        if (nextLimit == null) settle(now)
    }

    @Synchronized
    fun saved(key: Int, count: Int) {
        if (pausedForRecovery || key < firstKey || nextLimit != null) return
        pages++
        items += count
        val now = nowMillis()
        val elapsed = now - startedAt
        val recheckDue = !searching && settledAt?.let { now - it >= 120_000L } == true
        val minimumMillis = if (searching) 8_000L else 60_000L
        if (pages < 8 || (elapsed < minimumMillis && !recheckDue)) return
        val rate = items * 1_000.0 / elapsed.coerceAtLeast(1)

        if (searching) {
            if (bestRate == 0.0) {
                startProbing(now, rate)
            } else {
                val improved = rate > bestRate * 1.03
                if (improved) {
                    bestRate = rate
                    bestLimit = limit
                }
                nextLimit = when (direction) {
                    ProbeDirection.INCREASE -> {
                        if (improved) {
                            // Continue doubling while faster.
                            higher(limit)
                        } else {
                            // First upward trial lost: test below the original
                            // baseline rather than doing +/-1 around the peak.
                            direction = ProbeDirection.DECREASE
                            lower(startingProbeLimit)
                        }
                    }
                    ProbeDirection.DECREASE -> if (improved) lower(limit) else null
                }
                if (nextLimit == null) settle(now)
            }
        } else {
            val slowing = bestRate > 0.0 && rate < bestRate * 0.65
            if (recheckDue || slowing) {
                // Rechecks also test increasing first, rather than repeatedly
                // starting the search from one request.
                startProbing(now, rate)
            } else {
                bestRate = rate
            }
        }

        if (nextLimit == limit) nextLimit = null
        if (nextLimit == null) resetWindow(key + 1)
    }

    /** Suspend rate sampling during backoff, preserving the preferred limit. */
    @Synchronized
    fun pauseForRecovery() {
        pausedForRecovery = true
        nextLimit = null
        resetWindow(Int.MAX_VALUE)
    }

    @Synchronized
    fun resumeAfterRecovery(firstKey: Int) {
        pausedForRecovery = false
        searching = false
        nextLimit = null
        bestLimit = limit
        bestRate = 0.0
        settledAt = nowMillis() - 90_000L
        resetWindow(firstKey)
    }

    @Synchronized
    fun applyNext(firstKey: Int) {
        limit = nextLimit ?: limit
        nextLimit = null
        if (!searching) settledAt = nowMillis()
        resetWindow(firstKey)
    }

    private fun resetWindow(firstKey: Int) {
        this.firstKey = firstKey
        startedAt = nowMillis()
        pages = 0
        items = 0L
    }

    @Synchronized
    fun target(): Int = nextLimit ?: limit

    @Synchronized
    fun hasChange(): Boolean = nextLimit != null
}
