package eu.kanade.tachiyomi.ui.browse.source.browse

import java.io.File

/**
 * One source-side slowdown or recovery action. The reason is intentionally
 * diagnostic-only and does not contain source URLs or authentication tokens.
 */
data class SourceImportSlowdown(
    val pageKey: Int?,
    val cause: String,
    val action: String,
    val atElapsedMillis: Long,
    val retryIntervalMillis: Long? = null,
)

internal fun sourceImportExplainFailure(error: Throwable?): String {
    if (error == null) return "Repeated page or no new catalog entries"
    val detail = error.message.orEmpty().lowercase()
    val type = error.javaClass.simpleName
    return when {
        ("429" in detail && ("http" in detail || "rate limit" in detail || type.contains("Http"))) ||
            "too many requests" in detail || "throttl" in detail -> "HTTP 429: source is rate-limiting requests"
        ("403" in detail && ("http" in detail || type.contains("Http"))) -> "HTTP 403: source blocked access"
        ("401" in detail && ("http" in detail || type.contains("Http"))) -> "HTTP 401: source authentication required"
        ("503" in detail && ("http" in detail || type.contains("Http"))) -> "HTTP 503: source is temporarily unavailable"
        ("500" in detail && ("http" in detail || type.contains("Http"))) ||
            ("502" in detail && ("http" in detail || type.contains("Http"))) ||
            ("504" in detail && ("http" in detail || type.contains("Http"))) -> "Source server error"
        "timeout" in type.lowercase() || "timed out" in detail -> "Source request timed out"
        "unknownhost" in type.lowercase() || "unable to resolve host" in detail ->
            "Source DNS lookup failed or network is offline"
        "connection refused" in detail || "connectexception" in type.lowercase() ->
            "Source connection failed"
        "connection reset" in detail || "socketexception" in type.lowercase() ->
            "Network connection interrupted"
        "sqlite" in detail || "sqlite" in type.lowercase() || "database is locked" in detail ->
            "Local database write failed"
        "duplicate" in detail || "repeated" in detail || "no new entries" in detail ->
            "Source returned a repeated catalog page"
        "empty page" in detail -> "Source returned empty pages"
        else -> "Unexpected source error ($type)"
    }
}

data class SourceImportStats(
    val preloadPages: Int,
    val elapsedMillis: Long,
    val finished: Boolean,
    val mode: Mode,
    val effectiveConcurrency: Int,
    val activeLoads: Int,
    val bufferedPages: Int,
    val loadedPages: Int,
    val savedPages: Int,
    val added: Int,
    val existing: Int,
    val errors: Int,
    val recoveries: Int,
    val retryDelayMillis: Long?,
    val averageLoadMillis: Long,
    val averageSaveMillis: Long,
    val waitingMillis: Long,
    val savingMillis: Long,
    val queueWaitMillis: Long,
    val recentItemsPerSecond: Double,
    val automatic: Boolean = false,
    val autoSearching: Boolean = true,
    val restoredItems: Int = 0,
    val restoredPages: Int = 0,
    val lastSlowdown: SourceImportSlowdown? = null,
    val slowdownHistory: List<SourceImportSlowdown> = emptyList(),
    val errorHistory: List<String> = emptyList(),
    val libraryWriteMillis: Long = 0,
    val libraryWriteItems: Int = 0,
    val libraryWriteBatches: Int = 0,
    val catalogStageMillis: Long = 0,
    val catalogStageItems: Int = 0,
) {
    val averageItemsPerSecond: Double get() = (added + existing - restoredItems) * 1_000.0 / elapsedMillis.coerceAtLeast(1)
    val pagesPerMinute: Double get() = (savedPages - restoredPages) * 60_000.0 / elapsedMillis.coerceAtLeast(1)
    val bottleneck: Bottleneck
        get() = when {
            retryDelayMillis != null || mode == Mode.ERROR_FALLBACK || mode == Mode.REPEATED_FALLBACK -> Bottleneck.RECOVERY
            savedPages - restoredPages < 3 && elapsedMillis < 5_000 -> Bottleneck.STARTING
            savingMillis > waitingMillis * 1.25 && queueWaitMillis > elapsedMillis / 10 -> Bottleneck.SAVING
            waitingMillis > savingMillis * 1.25 -> Bottleneck.LOADING
            else -> Bottleneck.BALANCED
        }

    enum class Mode { PREPARING, PARALLEL, SINGLE, SLOW, CURSOR, ERROR_FALLBACK, REPEATED_FALLBACK }
    enum class Bottleneck { STARTING, RECOVERY, LOADING, SAVING, BALANCED }
}

internal class SourceImportTelemetry(
    preloadPages: Int,
    private val automatic: Boolean = false,
    private val restoredAdded: Int = 0,
    private val restoredExisting: Int = 0,
    private val restoredPages: Int = 0,
    errorLogFile: File? = null,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val errorLog = errorLogFile?.let(::SourceImportErrorHistory)
    private val limit = preloadPages.coerceIn(1, 64)
    private val startedAt = nowMillis()
    private var endedAt: Long? = null
    private var mode = SourceImportStats.Mode.PREPARING
    private var concurrency = 1
    private var autoSearching = true
    private var active = 0
    private var ready = 0
    private var loaded = 0
    private var saved = restoredPages
    private var added = restoredAdded
    private var existing = restoredExisting
    private var errors = 0
    private var recoveries = 0
    private var retryDelay: Long? = null
    private var failedPageKey: Int? = null
    private var failedCause: String? = null
    private val slowdowns = ArrayDeque<SourceImportSlowdown>()
    private var libraryWriteMillis = 0L
    private var libraryWriteItems = 0
    private var libraryWriteBatches = 0
    private var catalogStageMillis = 0L
    private var catalogStageItems = 0
    private var loadMillis = 0L
    private var successfulRequests = 0
    private var waitingMillis = 0L
    private var savingMillis = 0L
    private var saveSamples = 0
    private var queueMillis = 0L
    private var waitingSince: Long? = null
    private var savingSince: Long? = null
    private var queueSince: Long? = null
    private val restoredItems = restoredAdded + restoredExisting
    private val samples = ArrayDeque<Pair<Long, Int>>().apply { addLast(startedAt to restoredItems) }

    @Synchronized
    fun mode(value: SourceImportStats.Mode, concurrency: Int) {
        mode = value
        this.concurrency = concurrency
    }

    @Synchronized
    fun tuning(searching: Boolean) { autoSearching = searching }

    @Synchronized
    fun requestStarted() { active++ }

    @Synchronized
    fun requestCompleted(millis: Long, hasItems: Boolean) {
        active--
        successfulRequests++
        loadMillis += millis.coerceAtLeast(0)
        if (hasItems) loaded++
    }

    @Synchronized
    fun requestFailed(pageKey: Int? = null, error: Throwable? = null) {
        active--
        errors++
        failedPageKey = pageKey
        failedCause = sourceImportExplainFailure(error)
        runCatching { errorLog?.add(pageKey, error, "Retrying failed page") }
    }

    @Synchronized
    fun slowdown(pageKey: Int?, error: Throwable?, action: String) {
        appendSlowdown(pageKey, sourceImportExplainFailure(error), action, null)
        runCatching { errorLog?.add(pageKey, error, action) }
    }

    private fun appendSlowdown(pageKey: Int?, cause: String, action: String, interval: Long?) {
        slowdowns.addLast(
            SourceImportSlowdown(
                pageKey = pageKey,
                cause = cause,
                action = action,
                atElapsedMillis = (nowMillis() - startedAt).coerceAtLeast(0),
                retryIntervalMillis = interval,
            ),
        )
        while (slowdowns.size > 5) slowdowns.removeFirst()
    }

    @Synchronized
    fun libraryWrite(count: Int, millis: Long) {
        libraryWriteItems += count
        libraryWriteMillis += millis.coerceAtLeast(0)
        libraryWriteBatches++
    }

    @Synchronized
    fun catalogStaging(count: Int, millis: Long) {
        catalogStageItems += count
        catalogStageMillis += millis.coerceAtLeast(0)
    }

    @Synchronized
    fun requestCancelled() { active-- }

    @Synchronized
    fun retry(delayMillis: Long?) {
        retryDelay = delayMillis
        if (delayMillis != null) {
            recoveries++
            // Keep the actual reason and page through the cooldown, not merely
            // a "retrying" counter. A previous sequential fallback may already
            // have recorded the slowdown, in which case attach its wait interval.
            val previous = slowdowns.lastOrNull()
            val elapsed = (nowMillis() - startedAt).coerceAtLeast(0)
            if (previous != null && previous.pageKey == failedPageKey &&
                elapsed - previous.atElapsedMillis < 15_000
            ) {
                slowdowns.removeLast()
                slowdowns.addLast(previous.copy(retryIntervalMillis = delayMillis))
            } else {
                appendSlowdown(
                    failedPageKey,
                    failedCause ?: "Temporary source request failure",
                    "Waiting before retrying; reducing request rate",
                    delayMillis,
                )
            }
        }
    }

    @Synchronized
    fun ready() { ready++; queueSince = nowMillis() }

    @Synchronized
    fun sent() {
        queueMillis += duration(queueSince, nowMillis())
        queueSince = null
    }

    @Synchronized
    fun taken() { ready = (ready - 1).coerceAtLeast(0) }

    @Synchronized
    fun clearQueue() { ready = 0 }

    @Synchronized
    fun waiting() { waitingSince = nowMillis() }

    @Synchronized
    fun received() {
        waitingMillis += duration(waitingSince, nowMillis())
        waitingSince = null
    }

    @Synchronized
    fun saving() { savingSince = nowMillis() }

    @Synchronized
    fun saved(success: Boolean) {
        savingMillis += duration(savingSince, nowMillis())
        saveSamples++
        savingSince = null
        if (success) saved++
    }

    @Synchronized
    fun items(added: Int, existing: Int) {
        this.added += added
        this.existing += existing
        val now = nowMillis()
        samples.addLast(now to (this.added + this.existing))
        while (samples.size > 2 && samples[1].first <= now - 10_000) samples.removeFirst()
    }

    @Synchronized
    fun finish() {
        val now = nowMillis()
        waitingMillis += duration(waitingSince, now)
        savingMillis += duration(savingSince, now)
        queueMillis += duration(queueSince, now)
        waitingSince = null
        savingSince = null
        queueSince = null
        endedAt = now
        ready = 0
    }

    @Synchronized
    fun snapshot(): SourceImportStats {
        val now = endedAt ?: nowMillis()
        val cutoff = (now - 10_000).coerceAtLeast(startedAt)
        val baseline = if (cutoff == startedAt) restoredItems else samples.lastOrNull { it.first <= cutoff }?.second ?: restoredItems
        return SourceImportStats(
            preloadPages = limit,
            elapsedMillis = (now - startedAt).coerceAtLeast(0),
            finished = endedAt != null,
            mode = mode,
            effectiveConcurrency = concurrency,
            activeLoads = active.coerceAtLeast(0),
            bufferedPages = ready.coerceIn(0, limit),
            loadedPages = loaded,
            savedPages = saved,
            added = added,
            existing = existing,
            errors = errors,
            recoveries = recoveries,
            retryDelayMillis = retryDelay,
            averageLoadMillis = loadMillis / successfulRequests.coerceAtLeast(1),
            averageSaveMillis = savingMillis / saveSamples.coerceAtLeast(1),
            waitingMillis = waitingMillis + duration(waitingSince, now),
            savingMillis = savingMillis + duration(savingSince, now),
            queueWaitMillis = queueMillis + duration(queueSince, now),
            recentItemsPerSecond = (added + existing - baseline) * 1_000.0 / (now - cutoff).coerceAtLeast(1),
            automatic = automatic,
            autoSearching = autoSearching,
            restoredItems = restoredItems,
            restoredPages = restoredPages,
            lastSlowdown = slowdowns.lastOrNull(),
            slowdownHistory = slowdowns.toList(),
            errorHistory = errorLog?.recent().orEmpty(),
            libraryWriteMillis = libraryWriteMillis,
            libraryWriteItems = libraryWriteItems,
            libraryWriteBatches = libraryWriteBatches,
            catalogStageMillis = catalogStageMillis,
            catalogStageItems = catalogStageItems,
        )
    }

    private fun duration(since: Long?, now: Long) = since?.let { (now - it).coerceAtLeast(0) } ?: 0L
}
