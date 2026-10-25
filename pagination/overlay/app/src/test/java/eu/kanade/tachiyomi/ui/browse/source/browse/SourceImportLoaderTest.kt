package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.MetadataMangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportLoaderTest {

    @Test
    fun `eight numbered pages load concurrently and are saved in order despite out of order responses`() = runTest {
        var active = 0
        var maximumActive = 0
        val saved = mutableListOf<Int>()
        val loader = SourceImportLoader(false, false, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    delay(if (key == 2) 2_000L else 1_000L)
                    page(key, key < 9)
                } finally {
                    active--
                }
            },
        ) { page ->
            saved.add(page.mangas.single().url.toInt())
            true
        }

        assertEquals(8, maximumActive)
        assertEquals((1..9).toList(), saved)
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(0, active)
    }

    @Test
    fun `cursor pages load while the saver is busy without inventing page keys`() = runTest {
        val requested = mutableListOf<Int>()
        val saved = mutableListOf<Int>()
        var loadedWhileSaving = false
        val loader = SourceImportLoader(false, true, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                requested.add(key)
                delay(100L)
                val nextKey = when (key) {
                    1 -> 4_000_001L
                    4_000_001 -> 3_999_900L
                    3_999_900 -> null
                    else -> throw AssertionError("Invalid cursor $key")
                }
                MetadataMangasPage(page(key, nextKey != null).mangas, nextKey != null, emptyList(), nextKey)
            },
        ) { page ->
            delay(1_000L)
            if (saved.isEmpty()) loadedWhileSaving = requested.size == 3
            saved.add(page.mangas.single().url.toInt())
            true
        }

        assertTrue(loadedWhileSaving)
        assertEquals(listOf(1, 4_000_001, 3_999_900), requested)
        assertEquals(requested, saved)
    }

    @Test
    fun `parallel HTTP failure retries the same page after cooldown and falls back to sequential loading`() = runTest {
        val saved = mutableListOf<Int>()
        val retryDelays = mutableListOf<Long?>()
        val attempts = mutableListOf<Pair<Int, Long>>()
        var failOnce = true
        var active = 0
        var maximumAfterRecovery = 0
        val loader = SourceImportLoader(false, false, retryDelays::add, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                attempts.add(key to testScheduler.currentTime)
                active++
                if (testScheduler.currentTime >= 33_000L) maximumAfterRecovery = maxOf(maximumAfterRecovery, active)
                try {
                    delay(10L)
                    if (key == 3 && failOnce) {
                        failOnce = false
                        throw HttpException(429)
                    }
                    page(key, key < 5)
                } finally {
                    active--
                }
            },
        ) { page ->
            saved.add(page.mangas.single().url.toInt())
            true
        }

        assertEquals((1..5).toList(), saved)
        val failedPageAttempts = attempts.filter { it.first == 3 }
        assertEquals(2, failedPageAttempts.size)
        assertTrue(failedPageAttempts[1].second - failedPageAttempts[0].second >= 33_000L)
        assertTrue(maximumAfterRecovery >= 1)
        assertTrue(3_000L in retryDelays)
        assertEquals(0, active)
    }

    @Test
    fun `slow mode makes one request at a time with two seconds between responses and next requests`() = runTest {
        val started = mutableListOf<Long>()
        var active = 0
        var maximumActive = 0
        val loader = SourceImportLoader(true, false, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                started.add(testScheduler.currentTime)
                active++
                maximumActive = maxOf(maximumActive, active)
                delay(100L)
                active--
                page(key, key < 3)
            },
        ) {
            delay(1_000L)
            true
        }

        assertEquals(listOf(0L, 2_100L, 4_200L), started)
        assertEquals(1, maximumActive)
    }

    @Test
    fun `stopping cancels all outstanding requests`() = runTest {
        var active = 0
        var requested = 0
        val loader = SourceImportLoader(false, false, {}, { testScheduler.currentTime })
        val job = launch {
            loader.run(
                request = { key ->
                    requested++
                    if (key == 1) {
                        page(1, true)
                    } else {
                        active++
                        try {
                            delay(10_000L)
                            page(key, true)
                        } finally {
                            active--
                        }
                    }
                },
            ) { awaitCancellation() }
        }
        testScheduler.runCurrent()
        assertEquals(8, active)
        job.cancelAndJoin()
        val requestsAtStop = requested
        testScheduler.advanceUntilIdle()

        assertEquals(0, active)
        assertEquals(requestsAtStop, requested)
    }

    @Test
    fun `a blocked saver cannot cause an unlimited page queue`() = runTest {
        var requested = 0
        val saving = CompletableDeferred<Unit>()
        val loader = SourceImportLoader(false, true, {}, { testScheduler.currentTime })
        val job = launch {
            loader.run(
                request = { key ->
                    requested++
                    MetadataMangasPage(page(key, true).mangas, true, emptyList(), key.toLong() + 1)
                },
            ) {
                saving.complete(Unit)
                awaitCancellation()
            }
        }
        saving.await()
        testScheduler.runCurrent()

        assertTrue(requested <= 10)
        job.cancelAndJoin()
    }

    @Test
    fun `an error on the first page keeps later requests sequential`() = runTest {
        var attempts = 0
        var active = 0
        var maximumActive = 0
        val saved = mutableListOf<Int>()
        val loader = SourceImportLoader(false, false, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                attempts++
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    delay(10L)
                    if (attempts == 1) throw HttpException(503)
                    page(key, key < 3)
                } finally {
                    active--
                }
            },
        ) { page ->
            saved.add(page.mangas.single().url.toInt())
            true
        }

        assertEquals(listOf(1, 2, 3), saved)
        assertEquals(1, maximumActive)
    }

    @Test
    fun `cursor errors retry the gallery key without losing saved pages`() = runTest {
        val requested = mutableListOf<Int>()
        val saved = mutableListOf<Int>()
        var failOnce = true
        val loader = SourceImportLoader(false, true, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                requested.add(key)
                if (key == 4_000_001 && failOnce) {
                    failOnce = false
                    throw HttpException(429)
                }
                val nextKey = if (key == 1) 4_000_001L else null
                MetadataMangasPage(page(key, nextKey != null).mangas, nextKey != null, emptyList(), nextKey)
            },
        ) { page ->
            saved.add(page.mangas.single().url.toInt())
            true
        }

        assertEquals(listOf(1, 4_000_001, 4_000_001), requested)
        assertEquals(listOf(1, 4_000_001), saved)
        assertEquals(33_000L, testScheduler.currentTime)
    }

    @Test
    fun `extensions repeating pages under parallel loading are retried sequentially without duplicates`() = runTest {
        val saved = mutableSetOf<Int>()
        var active = 0
        var repeated = false
        val loader = SourceImportLoader(false, false, {}, { testScheduler.currentTime })

        loader.run(
            request = { key ->
                active++
                val repeat = active > 1 && key == 3
                try {
                    delay(10L)
                    if (repeat) {
                        repeated = true
                        page(1, true)
                    } else {
                        page(key, key < 5)
                    }
                } finally {
                    active--
                }
            },
        ) { page ->
            saved.add(page.mangas.single().url.toInt())
        }

        assertTrue(repeated)
        assertEquals((1..5).toSet(), saved)
        assertEquals(0, active)
    }

    private fun page(key: Int, hasNext: Boolean): MangasPage {
        return MangasPage(listOf(SManga.create().apply { url = key.toString() }), hasNext)
    }
}
