package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

internal class SourceImportLoader(
    private val slowMode: Boolean,
    private val usesCursor: Boolean,
    private val onRetry: (Long?) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    preloadPages: Int = 8,
    private val telemetry: SourceImportTelemetry? = null,
    automatic: Boolean = false,
    private val firstRequestKey: Int = 1,
    initialPageDelayMillis: Long = 0,
    forceSequential: Boolean = false,
    private val onSequentialFallback: () -> Unit = {},
) {

    private val pacing = SourceImportPacing(slowMode, initialPageDelayMillis)
    private var parallelDisabled = forceSequential
    val preloadPages = preloadPages.coerceIn(1, 16)
    private val tuner = if (automatic && !slowMode && !usesCursor) SourceImportAutoTuner(nowMillis, this.preloadPages) else null

    private fun retry(delayMillis: Long?) {
        telemetry?.retry(delayMillis)
        onRetry(delayMillis)
    }

    private suspend fun requestPage(key: Int, request: suspend (Int) -> MangasPage): MangasPage {
        val startedAt = nowMillis()
        telemetry?.requestStarted()
        try {
            val page = request(key)
            telemetry?.requestCompleted(nowMillis() - startedAt, page.mangas.isNotEmpty())
            return page
        } catch (error: CancellationException) {
            telemetry?.requestCancelled()
            throw error
        } catch (error: Exception) {
            telemetry?.requestFailed()
            throw error
        }
    }

    suspend fun run(request: suspend (Int) -> MangasPage, save: suspend (MangasPage) -> Boolean) =
        runWithKeys(request) { _, page -> save(page) }

    suspend fun runWithKeys(request: suspend (Int) -> MangasPage, save: suspend (Int, MangasPage) -> Boolean) = coroutineScope {
        var firstKey = firstRequestKey
        while (true) {
            val pages = Channel<Pair<Int, MangasPage>>(preloadPages)
            val loader = launch {
                try {
                    load(firstKey, request) { key, page ->
                        telemetry?.ready()
                        try {
                            pages.send(key to page)
                        } catch (error: CancellationException) {
                            telemetry?.taken()
                            throw error
                        } finally {
                            telemetry?.sent()
                        }
                    }
                } finally {
                    pages.close()
                }
            }
            var repeatedPageKey: Int? = null
            try {
                while (true) {
                    telemetry?.waiting()
                    val received = try {
                        pages.receiveCatching()
                    } finally {
                        telemetry?.received()
                    }
                    if (received.isClosed) break
                    val (key, page) = received.getOrThrow()
                    telemetry?.taken()
                    telemetry?.saving()
                    var keepLoading = false
                    try {
                        keepLoading = save(key, page)
                        if (keepLoading) tuner?.saved(key, page.mangas.distinctBy { it.url }.size)
                    } finally {
                        telemetry?.saved(keepLoading)
                    }
                    if (!keepLoading) {
                        repeatedPageKey = key
                        break
                    }
                }
            } finally {
                loader.cancel()
                pages.cancel()
                loader.join()
                telemetry?.clearQueue()
            }
            val retryKey = repeatedPageKey ?: break
            if (usesCursor || slowMode || pacing.pageDelayMillis > 0 || parallelDisabled || preloadPages == 1) break
            parallelDisabled = true
            onSequentialFallback()
            telemetry?.mode(SourceImportStats.Mode.REPEATED_FALLBACK, 1)
            firstKey = retryKey
        }
    }

    private suspend fun load(
        firstKey: Int,
        request: suspend (Int) -> MangasPage,
        emit: suspend (Int, MangasPage) -> Unit,
    ) = coroutineScope {
        val firstPage = pacing.fetch(::retry) { requestPage(firstKey, request) }
        if (usesCursor || slowMode || pacing.pageDelayMillis > 0 || parallelDisabled || (preloadPages == 1 && tuner == null)) {
            val mode = when {
                usesCursor -> SourceImportStats.Mode.CURSOR
                slowMode -> SourceImportStats.Mode.SLOW
                pacing.pageDelayMillis > 0 -> SourceImportStats.Mode.ERROR_FALLBACK
                parallelDisabled -> SourceImportStats.Mode.REPEATED_FALLBACK
                else -> SourceImportStats.Mode.SINGLE
            }
            telemetry?.mode(mode, 1)
            loadSequential(firstKey, firstPage, request, emit)
            return@coroutineScope
        }
        if (firstPage.mangas.isEmpty()) return@coroutineScope
        telemetry?.mode(SourceImportStats.Mode.PARALLEL, tuner?.target() ?: preloadPages)
        tuner?.applyNext(firstKey + 1)
        tuner?.let { telemetry?.tuning(it.searching) }
        emit(firstKey, firstPage)
        if (!firstPage.hasNextPage) return@coroutineScope

        // Completion-driven request scheduler. Fetches replenish on *any* completion,
        // while the consumer receives strictly consecutive pages for journal safety.
        val completed = Channel<Pair<Int, Result<MangasPage>>>(Channel.UNLIMITED)
        val inFlight = mutableMapOf<Int, Job>()
        val ready = java.util.TreeMap<Int, Result<MangasPage>>()
        var nextRequestKey = firstKey + 1
        var nextEmitKey = firstKey + 1
        var concurrency = tuner?.limit ?: preloadPages
        var lookahead = (concurrency * 3).coerceAtLeast(16)
        val maxLookahead = 128
        var upperBound: Int? = null
        var failureStreak = 0
        var resumeParallelAt = 0L

        fun launchRequests() {
            // Both the active request count and the speculative window are bounded.
            while (inFlight.size < concurrency &&
                nextRequestKey - nextEmitKey < lookahead &&
                (upperBound == null || nextRequestKey <= upperBound!!)
            ) {
                val key = nextRequestKey++
                inFlight[key] = launch {
                    val result = try {
                        Result.success(requestPage(key, request))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    completed.send(key to result)
                }
            }
        }

        try {
            launchRequests()
            while (true) {
                val (key, result) = completed.receive()
                inFlight.remove(key)
                ready[key] = result

                // A slow earlier page must not leave the request slots idle.
                // Expand lookahead in small increments only when its buffer is blocking us.
                if (nextRequestKey - nextEmitKey >= lookahead && inFlight.size < concurrency &&
                    ready.size >= concurrency && lookahead < maxLookahead
                ) {
                    lookahead = (lookahead + concurrency * 2).coerceAtMost(maxLookahead)
                }
                if (result.isSuccess && !result.getOrThrow().hasNextPage) {
                    upperBound = minOf(upperBound ?: Int.MAX_VALUE, key)
                }

                while (true) {
                    val next = ready.remove(nextEmitKey) ?: break
                    if (next.isFailure) {
                        // Recover the first missing page without discarding completed pages.
                        failureStreak++
                        concurrency = (concurrency / 2).coerceAtLeast(1)
                        telemetry?.mode(SourceImportStats.Mode.ERROR_FALLBACK, 1)
                        // Stop speculative requests before retrying a throttled source.
                        inFlight.values.forEach { it.cancel() }
                        inFlight.values.forEach { it.join() }
                        inFlight.clear()
                        ready.clear()
                        parallelDisabled = true
                        onSequentialFallback()
                        pacing.recover(::retry)
                        val recovered = pacing.fetch(::retry) { requestPage(nextEmitKey, request) }
                        loadSequential(nextEmitKey, recovered, request, emit)
                        return@coroutineScope
                    }
                    val page = next.getOrThrow()
                    if (page.mangas.isEmpty()) return@coroutineScope
                    emit(nextEmitKey, page)
                    if (!page.hasNextPage) return@coroutineScope
                    nextEmitKey++
                }

                if (tuner?.hasChange() == true) {
                    tuner.applyNext(nextEmitKey)
                    concurrency = tuner.limit.coerceIn(1, preloadPages)
                    lookahead = maxOf(lookahead, concurrency * 3).coerceAtMost(maxLookahead)
                }
                telemetry?.mode(SourceImportStats.Mode.PARALLEL, concurrency)
                tuner?.let { telemetry?.tuning(it.searching) }
                if (upperBound != null && nextEmitKey > upperBound!!) return@coroutineScope
                launchRequests()
            }
        } finally {
            inFlight.values.forEach { it.cancel() }
            completed.cancel()
        }
    }

    private suspend fun loadSequential(
        firstKey: Int,
        firstPage: MangasPage,
        request: suspend (Int) -> MangasPage,
        emit: suspend (Int, MangasPage) -> Unit,
    ) {
        var requestKey = firstKey
        var page = firstPage
        while (page.mangas.isNotEmpty()) {
            val receivedAt = nowMillis()
            emit(requestKey, page)
            requestKey = nextSourceImportPage(page, requestKey, usesCursor) ?: return
            val queuedMillis = nowMillis() - receivedAt
            delay((pacing.pageDelayMillis - queuedMillis).coerceAtLeast(0))
            page = pacing.fetch(::retry) { requestPage(requestKey, request) }
        }
    }
}
