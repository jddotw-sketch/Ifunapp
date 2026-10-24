package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SourceImportAutoTunerTest {

    @Test
    fun `keeps two when four adds less than ten percent throughput`() {
        var now = 0L
        val tuner = SourceImportAutoTuner({ now })
        var key = 2
        fun sample(items: Int) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, items)
            }
        }
        sample(17)
        assertEquals(2, tuner.target())
        tuner.applyNext(key)
        sample(20)
        assertEquals(4, tuner.target())
        tuner.applyNext(key)
        sample(21)
        assertFalse(tuner.searching)
        assertEquals(2, tuner.target())
        tuner.applyNext(key)
        assertEquals(2, tuner.limit)
    }

    @Test
    fun `scales to thirty two only while throughput keeps improving`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now })
        for (limit in listOf(1, 2, 4, 8, 16)) {
            assertEquals(limit, tuner.limit)
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25 * limit)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertEquals(16, tuner.limit)
        assertFalse(tuner.searching)
        assertFalse(tuner.hasChange())
    }

    @Test
    fun `ignores old buffered pages and waits for enough samples`() {
        var now = 0L
        val tuner = SourceImportAutoTuner({ now })
        now = 8_000
        repeat(7) { tuner.saved(it + 2, 25) }
        assertFalse(tuner.hasChange())
        tuner.saved(9, 25)
        assertTrue(tuner.hasChange())
        tuner.applyNext(30)
        now += 8_000
        repeat(29) { tuner.saved(it, 1_000) }
        assertFalse(tuner.hasChange())
        repeat(8) { tuner.saved(it + 30, 50) }
        assertEquals(4, tuner.target())
    }

    @Test
    fun `sustained slowdown restarts a conservative search`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now })
        repeat(2) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertFalse(tuner.searching)
        assertEquals(1, tuner.limit)
        repeat(7) {
            now += 7_500
            tuner.saved(key++, 25)
        }
        assertFalse(tuner.searching)
        now += 7_500
        tuner.saved(key, 25)
        assertTrue(tuner.searching)
        assertEquals(1, tuner.target())
    }

    @Test
    fun `settled speed is rechecked after two minutes even without a slowdown`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now })
        for (count in listOf(17, 20, 20)) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, count)
            }
            if (tuner.hasChange()) tuner.applyNext(key)
        }
        assertFalse(tuner.searching)
        assertEquals(2, tuner.limit)
        val settledAt = now
        repeat(8) {
            now += 7_500
            tuner.saved(key++, 150)
        }
        assertFalse(tuner.searching)
        repeat(7) {
            now += 7_500
            tuner.saved(key++, 150)
        }
        now = settledAt + 119_999
        tuner.saved(key++, 150)
        assertFalse(tuner.searching)
        now++
        tuner.saved(key++, 150)
        assertTrue(tuner.searching)
        assertEquals(1, tuner.target())
        tuner.applyNext(key)
        repeat(8) {
            now += 1_000
            tuner.saved(key++, 25)
        }
        assertEquals(2, tuner.target())
    }

    @Test
    fun `two minute rechecks repeat after each newly settled speed`() {
        var now = 0L
        var key = 2
        val tuner = SourceImportAutoTuner({ now }, maximum = 1)
        repeat(3) {
            repeat(8) {
                now += 1_000
                tuner.saved(key++, 25)
            }
            assertFalse(tuner.searching)
            repeat(16) {
                now += 7_500
                tuner.saved(key++, 200)
            }
            assertTrue(tuner.searching)
            assertEquals(1, tuner.target())
        }
    }
}
