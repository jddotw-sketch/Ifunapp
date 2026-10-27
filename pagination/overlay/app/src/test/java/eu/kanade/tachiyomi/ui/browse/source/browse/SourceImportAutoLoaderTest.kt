package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportAutoLoaderTest {

    @Test
    fun `scalable source reaches thirty two without losing order or counts`() = runTest {
        val stats = SourceImportTelemetry(64, automatic = true) { testScheduler.currentTime }
        val saved = mutableListOf<Int>()
        var active = 0
        var maximum = 0
        SourceImportLoader(false, false, {}, { testScheduler.currentTime }, 64, stats, automatic = true).run(
            request = { key ->
                active++
                maximum = maxOf(maximum, active)
                try {
                    delay(1_000)
                    page(key, 1_500)
                } finally {
                    active--
                }
            },
        ) {
            saved.add(it.mangas.single().url.toInt())
            delay(1)
            true
        }
        assertEquals((1..1_500).toList(), saved)
        assertTrue(maximum in 16..64)
        assertEquals(0, active)
        assertTrue(stats.snapshot().effectiveConcurrency in 1..64)
    }

    @Test
    fun `serialized source remains within request ceiling`() = runTest {
        val mutex = Mutex()
        val stats = SourceImportTelemetry(64, automatic = true) { testScheduler.currentTime }
        val saved = mutableListOf<Int>()
        SourceImportLoader(false, false, {}, { testScheduler.currentTime }, 64, stats, automatic = true).run(
            request = { key -> mutex.withLock { delay(1_000); page(key, 80) } },
        ) {
            saved.add(it.mangas.single().url.toInt())
            true
        }
        assertEquals((1..80).toList(), saved)
        assertTrue(stats.snapshot().effectiveConcurrency in 1..64)
    }

    @Test
    fun `auto mode still pauses and retries HTTP errors sequentially`() = runTest {
        val stats = SourceImportTelemetry(64, automatic = true) { testScheduler.currentTime }
        val saved = mutableListOf<Int>()
        val pauses = mutableListOf<Long?>()
        var failed = false
        SourceImportLoader(false, false, pauses::add, { testScheduler.currentTime }, 64, stats, automatic = true).run(
            request = { key ->
                delay(1_000)
                if (key == 12 && !failed) {
                    failed = true
                    throw HttpException(429)
                }
                page(key, 20)
            },
        ) {
            saved.add(it.mangas.single().url.toInt())
            true
        }
        assertEquals((1..20).toList(), saved)
        assertTrue(3_000L in pauses)
        assertEquals(SourceImportStats.Mode.PARALLEL, stats.snapshot().mode)
        assertTrue(stats.snapshot().effectiveConcurrency in 1..16)
    }

    @Test
    fun `slow mode overrides automatic search`() = runTest {
        val stats = SourceImportTelemetry(64, automatic = true) { testScheduler.currentTime }
        SourceImportLoader(true, false, {}, { testScheduler.currentTime }, 64, stats, automatic = true).run(
            request = { key -> delay(100); page(key, 30) },
        ) { true }
        assertEquals(SourceImportStats.Mode.SLOW, stats.snapshot().mode)
        assertEquals(1, stats.snapshot().effectiveConcurrency)
    }

    private fun page(key: Int, last: Int) = MangasPage(
        listOf(SManga.create().apply { url = key.toString(); title = "Manga $key" }),
        key < last,
    )

    @Test
    fun `cursor source keeps its real next keys with auto enabled`() = runTest {
        val stats = SourceImportTelemetry(64, automatic = true) { testScheduler.currentTime }
        val keys = mutableListOf<Int>()
        SourceImportLoader(false, true, {}, { testScheduler.currentTime }, 64, stats, automatic = true).run(
            request = { key ->
                keys.add(key)
                delay(1_000)
                val next = when (key) {
                    1 -> 5_000L
                    5_000 -> 4_000L
                    4_000 -> null
                    else -> throw AssertionError("Invalid cursor $key")
                }
                MetadataMangasPage(page(key, 6_000).mangas, next != null, emptyList(), next)
            },
        ) { true }
        assertEquals(listOf(1, 5_000, 4_000), keys)
        assertEquals(SourceImportStats.Mode.CURSOR, stats.snapshot().mode)
        assertEquals(1, stats.snapshot().effectiveConcurrency)
    }

    @Test
    fun `stopping during tuning cancels every outstanding request`() = runTest {
        var active = 0
        val job = launch {
            SourceImportLoader(false, false, {}, { testScheduler.currentTime }, 64, automatic = true).run(
                request = { key ->
                    active++
                    try {
                        delay(1_000)
                        page(key, 10_000)
                    } finally {
                        active--
                    }
                },
            ) { true }
        }
        delay(40_000)
        assertTrue(active > 0)
        job.cancelAndJoin()
        assertEquals(0, active)
    }
}
