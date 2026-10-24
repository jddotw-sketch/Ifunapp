package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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

        val pending = ArrayDeque<Pair<Int, Deferred<Result<MangasPage>>>>()
        val parallelFailure = AtomicBoolean(false)
        var nextRequestKey = firstKey + 1
        try {
            while (true) {
                if (pending.isEmpty() && tuner?.hasChange() == true) {
                    tuner.applyNext(nextRequestKey)
                }
                val limit = tuner?.limit ?: preloadPages
                telemetry?.mode(SourceImportStats.Mode.PARALLEL, limit)
                tuner?.let { telemetry?.tuning(it.searching) }
                while (pending.size < limit && tuner?.hasChange() != true && !parallelFailure.get()) {
                    val requestKey = nextRequestKey++
                    pending.addLast(
                        requestKey to async {
                            try {
                                Result.success(requestPage(requestKey, request))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                parallelFailure.set(true)
                                Result.failure(error)
                            }
                        },
                    )
                }
                if (pending.isEmpty()) continue
                val (requestKey, response) = pending.removeFirst()
                val result = response.await()
                if (result.isFailure) {
                    pending.forEach { it.second.cancel() }
                    pending.forEach { it.second.join() }
                    pending.clear()
                    parallelDisabled = true
                    onSequentialFallback()
                    telemetry?.mode(SourceImportStats.Mode.ERROR_FALLBACK, 1)
                    pacing.recover(::retry)
                    val page = pacing.fetch(::retry) { requestPage(requestKey, request) }
                    loadSequential(requestKey, page, request, emit)
                    return@coroutineScope
                }
                val page = result.getOrThrow()
                if (page.mangas.isEmpty()) return@coroutineScope
                emit(requestKey, page)
                if (!page.hasNextPage) return@coroutineScope
            }
        } finally {
            pending.forEach { it.second.cancel() }
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
