package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportTelemetryTest {

    @Test
    fun `loading saving counts and rates use saved items rather than speculative pages`() {
        var now = 0L
        val telemetry = SourceImportTelemetry(8) { now }
        telemetry.waiting()
        telemetry.requestStarted()
        now = 2_000L
        telemetry.requestCompleted(2_000L, true)
        telemetry.received()
        telemetry.saving()
        now = 2_100L
        telemetry.items(10, 15)
        telemetry.saved(true)
        telemetry.requestStarted()
        telemetry.requestCompleted(500L, true)
        now = 6_000L

        val stats = telemetry.snapshot()
        assertEquals(2, stats.loadedPages)
        assertEquals(1, stats.savedPages)
        assertEquals(10, stats.added)
        assertEquals(15, stats.existing)
        assertEquals(1_250L, stats.averageLoadMillis)
        assertEquals(100L, stats.averageSaveMillis)
        assertEquals(2_000L, stats.waitingMillis)
        assertEquals(25.0 / 6, stats.averageItemsPerSecond, 0.001)
        assertEquals(25.0 / 6, stats.recentItemsPerSecond, 0.001)
        assertEquals(SourceImportStats.Bottleneck.LOADING, stats.bottleneck)
    }

    @Test
    fun `recent rate falls to zero after ten idle seconds and final snapshots stop changing`() {
        var now = 0L
        val telemetry = SourceImportTelemetry(8) { now }
        telemetry.items(10, 0)
        now = 1_000L
        assertEquals(10.0, telemetry.snapshot().recentItemsPerSecond, 0.001)
        now = 11_000L
        assertEquals(0.0, telemetry.snapshot().recentItemsPerSecond, 0.001)
        telemetry.finish()
        val finalStats = telemetry.snapshot()
        now = 99_000L

        assertEquals(finalStats, telemetry.snapshot())
        assertTrue(finalStats.finished)
    }

    @Test
    fun `backpressure and long saves identify saving while retries identify recovery`() {
        var now = 0L
        val telemetry = SourceImportTelemetry(8) { now }
        telemetry.waiting()
        now = 1_000L
        telemetry.received()
        telemetry.saving()
        now = 1_200L
        telemetry.ready()
        now = 5_000L
        telemetry.sent()
        telemetry.taken()
        now = 6_000L
        telemetry.items(25, 0)
        telemetry.saved(true)

        assertEquals(SourceImportStats.Bottleneck.SAVING, telemetry.snapshot().bottleneck)
        assertEquals(3_800L, telemetry.snapshot().queueWaitMillis)
        telemetry.retry(3_000L)
        assertEquals(SourceImportStats.Bottleneck.RECOVERY, telemetry.snapshot().bottleneck)
        assertEquals(1, telemetry.snapshot().recoveries)
        telemetry.retry(null)
        assertEquals(SourceImportStats.Bottleneck.SAVING, telemetry.snapshot().bottleneck)
    }

    @Test
    fun `selected preload limits bound actual concurrent loading`() = runTest {
        for (selected in listOf(0, 1, 4, 16, 64)) {
            val limit = selected.coerceIn(1, 16)
            val telemetry = SourceImportTelemetry(selected) { testScheduler.currentTime }
            var active = 0
            var maximum = 0
            val saved = mutableListOf<Int>()
            val loader = SourceImportLoader(
                slowMode = false,
                usesCursor = false,
                onRetry = {},
                nowMillis = { testScheduler.currentTime },
                preloadPages = selected,
                telemetry = telemetry,
            )
            loader.run(
                request = { key ->
                    active++
                    maximum = maxOf(maximum, active)
                    try {
                        delay(100L)
                        MangasPage(
                            listOf(SManga.create().apply { url = key.toString() }),
                            key < 34,
                        )
                    } finally {
                        active--
                    }
                },
            ) { page ->
                saved.add(page.mangas.single().url.toInt())
                telemetry.items(1, 0)
                true
            }

            assertEquals(limit, loader.preloadPages)
            assertEquals(limit, maximum)
            assertEquals((1..34).toList(), saved)
            assertEquals(0, telemetry.snapshot().activeLoads)
            assertEquals(0, telemetry.snapshot().bufferedPages)
            assertEquals(34, telemetry.snapshot().savedPages)
        }
    }

    @Test
    fun `cursor and slow mode explain the effective limit instead of reporting configured parallelism`() = runTest {
        for (usesCursor in listOf(true, false)) {
            val telemetry = SourceImportTelemetry(32) { testScheduler.currentTime }
            val loader = SourceImportLoader(
                slowMode = !usesCursor,
                usesCursor = usesCursor,
                onRetry = {},
                nowMillis = { testScheduler.currentTime },
                preloadPages = 32,
                telemetry = telemetry,
            )
            loader.run(
                request = { key ->
                    MetadataMangasPage(
                        listOf(SManga.create().apply { url = key.toString() }),
                        key == 1,
                        emptyList(),
                        if (key == 1) 2L else null,
                    )
                },
            ) { true }

            val stats = telemetry.snapshot()
            assertEquals(32, stats.preloadPages)
            assertEquals(1, stats.effectiveConcurrency)
            assertEquals(if (usesCursor) SourceImportStats.Mode.CURSOR else SourceImportStats.Mode.SLOW, stats.mode)
        }
    }
}
