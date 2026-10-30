package eu.kanade.tachiyomi.ui.browse.source.browse

/**
 * Keeps aggressive page prefetch from repeatedly triggering rate limits.
 * Recovery starts with few requests, halves on errors, then slowly probes back
 * up after consecutive successful pages. The configured limit is still a hard
 * ceiling. Checkpoint/retry logic remains owned by SourceImportWorker.
 */
internal class SourceImportCongestionControl(
    maximum: Int,
    previousRecoveryDelayMillis: Long = 0,
) {
    private val maximum = maximum.coerceIn(1, 64)

    var ceiling: Int = if (previousRecoveryDelayMillis > 0) minOf(4, this.maximum) else this.maximum
        private set

    private var cleanPages = 0

    fun clamp(requested: Int): Int = requested.coerceIn(1, ceiling)

    fun onFailure(current: Int): Int {
        ceiling = minOf(ceiling, (current / 2).coerceAtLeast(1))
        cleanPages = 0
        return clamp(current)
    }

    fun onSuccess() {
        if (ceiling >= maximum) return
        if (++cleanPages >= 64) {
            cleanPages = 0
            ceiling = (ceiling + 1).coerceAtMost(maximum)
        }
    }
}
