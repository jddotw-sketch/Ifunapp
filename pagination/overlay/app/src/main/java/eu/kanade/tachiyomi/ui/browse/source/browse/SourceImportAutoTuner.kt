package eu.kanade.tachiyomi.ui.browse.source.browse

/**
 * Feedback tuner for independent catalog page requests.
 *
 * Network recovery temporarily suspends sampling. Periodic rechecks probe a
 * neighboring concurrency limit rather than restarting at one every two minutes.
 * Only committed pages contribute to a rate sample.
 */
internal class SourceImportAutoTuner(
    private val nowMillis: () -> Long,
    maximum: Int = 16,
) {
    private val maximum = maximum.coerceIn(1, 16)

    @Volatile
    var limit = 1
        private set

    @Volatile
    var searching = true
        private set

    @Volatile
    var nextLimit: Int? = null
        private set

    private var bestLimit = 1
    private var bestRate = 0.0
    private var startedAt = nowMillis()
    private var settledAt: Long? = null
    private var firstKey = 2
    private var pages = 0
    private var items = 0L
    private var pausedForRecovery = false

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
            if (bestRate == 0.0 || rate > bestRate * 1.10) {
                bestRate = rate
                bestLimit = limit
                if (limit < maximum) {
                    nextLimit = (limit * 2).coerceAtMost(maximum)
                } else {
                    searching = false
                    settledAt = now
                }
            } else {
                searching = false
                settledAt = now
                nextLimit = bestLimit
            }
        } else {
            val slowing = bestRate > 0.0 && rate < bestRate * 0.65
            if ((recheckDue || slowing) && maximum > 1) {
                // Anchor the comparison in the current source's performance.
                // Rechecking from one wastes time on large catalogs.
                bestRate = rate
                bestLimit = limit
                searching = true
                settledAt = null
                nextLimit = when {
                    slowing && limit > 1 -> (limit / 2).coerceAtLeast(1)
                    limit < maximum -> (limit * 2).coerceAtMost(maximum)
                    else -> (limit / 2).coerceAtLeast(1)
                }
            } else {
                // Do not slide the recheck deadline forward after each sample.
                // It is measured from the last settled concurrency decision.
                bestRate = rate
            }
        }

        if (nextLimit == limit) nextLimit = null
        if (nextLimit == null) resetWindow(key + 1)
    }

    /**
     * Pause rate measurement while requests back off or the scheduler refills.
     * Keep the previous preferred limit so we can recover gradually.
     */
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
        // Re-evaluate after a short stable sample, not another full two minutes.
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
