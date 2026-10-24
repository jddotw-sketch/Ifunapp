package eu.kanade.tachiyomi.ui.browse.source.browse

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
) {
    val averageItemsPerSecond: Double get() = (added + existing - restoredItems) * 1_000.0 / elapsedMillis.coerceAtLeast(1)
    val pagesPerMinute: Double get() = (savedPages - restoredPages) * 60_000.0 / elapsedMillis.coerceAtLeast(1)
    val bottleneck: Bottleneck
        get() = when {
            retryDelayMillis != null -> Bottleneck.RECOVERY
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
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val limit = preloadPages.coerceIn(1, 32)
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
    fun requestFailed() { active--; errors++ }

    @Synchronized
    fun requestCancelled() { active-- }

    @Synchronized
    fun retry(delayMillis: Long?) {
        retryDelay = delayMillis
        if (delayMillis != null) recoveries++
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
        )
    }

    private fun duration(since: Long?, now: Long) = since?.let { (now - it).coerceAtLeast(0) } ?: 0L
}
