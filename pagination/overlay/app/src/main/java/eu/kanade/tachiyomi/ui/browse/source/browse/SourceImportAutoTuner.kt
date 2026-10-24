package eu.kanade.tachiyomi.ui.browse.source.browse

internal class SourceImportAutoTuner(
    private val nowMillis: () -> Long,
    private val maximum: Int = 16,
) {
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

    @Synchronized
    fun saved(key: Int, count: Int) {
        if (key < firstKey || nextLimit != null) return
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
            if (recheckDue || rate < bestRate * 0.65) {
                searching = true
                settledAt = null
                bestRate = 0.0
                bestLimit = 1
                nextLimit = 1
            } else {
                bestRate = rate
            }
        }
        if (nextLimit == limit) nextLimit = null
        if (nextLimit == null) resetWindow(key + 1)
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
