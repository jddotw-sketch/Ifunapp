package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NyaaBrowseCoverResolverTest {
    @Test
    fun scopesCoverDiscoveryToTheProvidedNyaaExtensionAndHttpImages() {
        assertTrue(NyaaBrowseCoverResolver.isNyaaExtension("eu.kanade.tachiyomi.animeextension.all.nyaatorrent.a"))
        assertFalse(NyaaBrowseCoverResolver.isNyaaExtension("eu.kanade.tachiyomi.animeextension.en.other.Nyaa"))
        assertEquals("https://images.example/cover.jpg", NyaaBrowseCoverResolver.validCoverUrl(" https://images.example/cover.jpg "))
        assertNull(NyaaBrowseCoverResolver.validCoverUrl("javascript:alert(1)"))
        assertNull(NyaaBrowseCoverResolver.validCoverUrl("file:///private/file"))
        assertNull(NyaaBrowseCoverResolver.validCoverUrl("https://username:password@images.example/cover.jpg"))
    }

    @Test
    fun concurrentVisibleCardsCoalesceAndDifferentItemsStaySeparate() = runBlocking {
        val cache = NyaaCoverLookupCache()
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val one = async { cache.resolve("source:/view/1") { calls++; started.complete(Unit); release.await(); "cover-one" } }
        started.await()
        val duplicate = async { cache.resolve("source:/view/1") { error("duplicate lookup") } }
        release.complete(Unit)
        assertEquals("cover-one", one.await())
        assertEquals("cover-one", duplicate.await())
        assertEquals("cover-two", cache.resolve("source:/view/2") { calls++; "cover-two" })
        assertEquals(2, calls)
    }

    @Test
    fun missingOrFailedArtworkDoesNotLoopAndCanRetryAfterExpiry() = runBlocking {
        var time = 0L
        val cache = NyaaCoverLookupCache { time }
        var calls = 0
        assertNull(cache.resolve("missing") { calls++; throw IllegalStateException("source unavailable") })
        assertNull(cache.resolve("missing") { calls++; "unexpected" })
        assertEquals(1, calls)
        time = 60_001
        assertEquals("available", cache.resolve("missing") { calls++; "available" })
        assertEquals(2, calls)
    }

    @Test
    fun scrollingAwayCancelsLookupWithoutCachingAFailure() = runBlocking {
        val cache = NyaaCoverLookupCache()
        val started = CompletableDeferred<Unit>()
        val job = launch { cache.resolve("item") { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("retry-cover", cache.resolve("item") { "retry-cover" })
    }
}
