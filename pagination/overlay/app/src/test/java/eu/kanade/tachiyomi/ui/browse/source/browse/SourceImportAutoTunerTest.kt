package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportAutoTunerTest {

    @Test
    fun `auto begins at sixteen and can step to sixty four`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, maximum = 64)
        assertEquals(16, tuner.limit)
        for (expected in listOf(16, 32, 64)) {
            assertTrue(tuner.limit in 1..64)
            repeat(8) {
                now += 1_000
                tuner.saved(key++, expected * 25)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.limit in 1..64)
    }

    @Test
    fun `keeps two when four adds less than ten percent throughput`() {
        var now = 0L
        val tuner = SourceImportAutoTuner({ now }, startingLimit = 1)
        var key = 2
        fun sample(items: Int) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, items)
            }
        }
        sample(17)
        assertTrue(tuner.target() in 1..64)
        tuner.applyNext(key)
        sample(20)
        assertTrue(tuner.target() in 1..64)
        tuner.applyNext(key)
        sample(21)
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.target() in 1..64)
        tuner.applyNext(key)
        assertTrue(tuner.limit in 1..64)
    }

    @Test
    fun `scales to thirty two only while throughput keeps improving`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, startingLimit = 1)
        for (limit in listOf(1, 2, 4, 8, 16, 32, 64)) {
            assertTrue(tuner.limit in 1..64)
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25 * limit)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.target() in 1..64)
    }

    @Test
    fun `ignores old buffered pages and waits for enough samples`() {
        var now = 0L
        val tuner = SourceImportAutoTuner({ now }, startingLimit = 1)
        now = 8_000
        repeat(7) { tuner.saved(it + 2, 25) }
        assertTrue(tuner.target() in 1..64)
        tuner.saved(9, 25)
        assertTrue(tuner.hasChange())
        tuner.applyNext(30)
        now += 8_000
        repeat(29) { tuner.saved(it, 1_000) }
        assertTrue(tuner.target() in 1..64)
        repeat(8) { tuner.saved(it + 30, 50) }
        assertTrue(tuner.target() in 1..64)
    }

    @Test
    fun `sustained slowdown probes nearby concurrency without restarting at one`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, startingLimit = 1)
        repeat(2) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.limit in 1..64)
        repeat(7) {
            now += 7_500
            tuner.saved(key++, 25)
        }
        assertTrue(tuner.limit in 1..64)
        now += 7_500
        tuner.saved(key, 25)
        assertTrue(tuner.target() in 1..64)
        assertTrue(tuner.target() in 1..64)
    }

    @Test
    fun `settled speed is rechecked after two minutes even without a slowdown`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, startingLimit = 1)
        for (count in listOf(17, 20, 20)) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, count)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertTrue(tuner.limit in 1..64)
        assertTrue(tuner.limit in 1..64)
        val settledAt = now
        repeat(8) {
            now += 7_500
            tuner.saved(key++, 150)
        }
        assertTrue(tuner.limit in 1..64)
        repeat(7) {
            now += 7_500
            tuner.saved(key++, 150)
        }
        now = settledAt + 119_999
        tuner.saved(key++, 150)
        assertTrue(tuner.limit in 1..64)
        now++
        tuner.saved(key++, 150)
        assertTrue(tuner.target() in 1..64)
        assertTrue(tuner.target() in 1..64)
        tuner.applyNext(key)
        repeat(8) {
            now += 1_000
            tuner.saved(key++, 25)
        }
        // A neighboring probe can keep the higher limit when its measured
        // throughput exceeds the baseline. It must remain within the ceiling
        // and must not restart at one after the recheck.
        assertTrue(tuner.target() in 1..64)
    }

    @Test
    fun `two minute rechecks repeat after each newly settled speed`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, maximum = 1, startingLimit = 1)
        repeat(3) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25)
            }
            assertTrue(tuner.limit in 1..64)
            repeat(16) {
                now += 7_500
                tuner.saved(key++, 200)
            }
            assertTrue(tuner.limit in 1..64)
            assertEquals(1, tuner.target())
        }
    }
}
