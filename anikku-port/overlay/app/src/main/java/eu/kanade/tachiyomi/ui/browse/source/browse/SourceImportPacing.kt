package eu.kanade.tachiyomi.ui.browse.source.browse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Paces catalog requests and retries transient failures without losing the
 * committed pagination checkpoint. Unlike the generic extension rate-limit
 * bypass, this protects sources that throttle or temporarily disconnect.
 */
internal class SourceImportPacing(
    private val slowMode: Boolean,
    initialPageDelayMillis: Long = 0,
) {
    var pageDelayMillis: Long = if (slowMode) 2_000L else initialPageDelayMillis.coerceIn(0L, 60_000L)
        private set

    private fun isThrottled(error: Throwable?): Boolean {
        if (error == null) return false
        val detail = "${error.message.orEmpty()} ${error.javaClass.simpleName}"
        return Regex("""\\b(429|too many requests|rate limit|throttl)\\b""", RegexOption.IGNORE_CASE).containsMatchIn(detail)
    }

    private suspend fun cooldown(onRetry: (Long?) -> Unit, error: Throwable?, attempt: Int) {
        // 429 responses need both a gentle ramp and a substantial cooldown.
        // Baseline: 30 seconds server cooldown + 3 seconds retry interval.
        val retryMillis = (3_000L * (1L shl attempt.coerceAtMost(3))).coerceAtMost(24_000L)
        val serverCooldown = if (isThrottled(error)) 30_000L else 0L
        val total = retryMillis + serverCooldown
        onRetry(retryMillis)
        try {
            delay(total)
        } finally {
            onRetry(null)
        }
        if (!slowMode) pageDelayMillis = (pageDelayMillis + 250L).coerceAtMost(5_000L)
    }

    suspend fun recover(onRetry: (Long?) -> Unit, error: Throwable? = null) {
        cooldown(onRetry, error, attempt = 0)
    }

    suspend fun <T> fetch(onRetry: (Long?) -> Unit, request: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return request()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attempt >= 5) throw error
                cooldown(onRetry, error, attempt++)
            }
        }
    }
}
