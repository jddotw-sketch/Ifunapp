package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val preloadPages = preloadPages.coerceIn(1, 64)
    private val tuner = if (automatic && !slowMode && !usesCursor) SourceImportAutoTuner(nowMillis, this.preloadPages) else null

    private fun retry(delayMillis: Long?) {
        telemetry?.retry(delayMillis)
        onRetry(delayMillis)
    }

    private suspend fun requestPage(key: Int, request: suspend (Int) -> MangasPage): MangasPage {
        val startedAt = nowMillis()
        telemetry?.requestStarted()
        try {
            val page = try {
                withTimeout(120_000L) { request(key) }
            } catch (error: TimeoutCancellationException) {
                throw IOException("Source import page $key timed out after 120 seconds", error)
            }
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
            if (usesCursor || slowMode || parallelDisabled || preloadPages == 1) {
                error("Catalog page $retryKey repeated or contained no new entries even in sequential mode")
            }
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
        if (usesCursor || slowMode || parallelDisabled || (preloadPages == 1 && tuner == null)) {
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
        val maxLookahead = 512
        var upperBound: Int? = null
        var recoveringFromError = false
        var successfulRecoveryPages = 0

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
            scheduler@ while (true) {
                val (key, result) = completed.receive()
                // A prior generation can leave completed responses in the channel
                // when recovery cancels speculative work. Never accept stale results.
                if (inFlight.remove(key) == null || key < nextEmitKey) continue@scheduler
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
                        // A transient failure should lower the request rate, not permanently
                        // disable parallel pagination or poison the saved checkpoint.
                        // Drain speculative requests and retry the exact missing page in order.
                        concurrency = (concurrency / 2).coerceAtLeast(1)
                        recoveringFromError = true
                        successfulRecoveryPages = 0
                        tuner?.pauseForRecovery()
                        telemetry?.mode(SourceImportStats.Mode.ERROR_FALLBACK, 1)
                        inFlight.values.forEach { it.cancel() }
                        inFlight.values.forEach { it.join() }
                        inFlight.clear()
                        ready.clear()
                        // Cancelled jobs may have queued responses just before cancellation.
                        // Drain those responses before reusing page keys for the retry generation.
                        while (completed.tryReceive().isSuccess) {}
                        pacing.recover(::retry)
                        val recovered = pacing.fetch(::retry) { requestPage(nextEmitKey, request) }
                        if (recovered.mangas.isEmpty()) return@coroutineScope
                        emit(nextEmitKey, recovered)
                        if (!recovered.hasNextPage) return@coroutineScope
                        nextEmitKey++
                        nextRequestKey = nextEmitKey
                        upperBound = null
                        launchRequests()
                        continue@scheduler
                    }
                    val page = next.getOrThrow()
                    if (page.mangas.isEmpty()) return@coroutineScope
                    emit(nextEmitKey, page)
                    if (!page.hasNextPage) return@coroutineScope
                    nextEmitKey++
                    // Only clamp ramp-up after an actual error. Do not interfere
                    // with normal automatic tuning, which must be free to scale and settle.
                    if (recoveringFromError && ++successfulRecoveryPages >= 16) {
                        val recoveryTarget = tuner?.limit ?: preloadPages
                        if (concurrency < recoveryTarget) concurrency++
                        successfulRecoveryPages = 0
                        if (concurrency >= recoveryTarget) {
                            recoveringFromError = false
                            tuner?.resumeAfterRecovery(nextEmitKey)
                        }
                    }
                }

                if (!recoveringFromError && tuner?.hasChange() == true) {
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
