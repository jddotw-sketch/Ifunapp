package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceImportIncreaseFirstTest {
    private var clock = 0L
    private var key = 1
    private val tuner = SourceImportAutoTuner({ clock }, maximum = 64, startingLimit = 16)

    private fun sample(itemsPerPage: Int) {
        clock += 8_001
        repeat(8) { tuner.saved(++key, itemsPerPage) }
    }

    private fun advance() = tuner.applyNext(key + 1)

    @Test
    fun `tries increasing first then decreasing when slower`() {
        sample(30)
        assertEquals(32, tuner.target())
        advance()
        sample(20)
        assertEquals(8, tuner.target())
        advance()
        sample(40)
        assertEquals(4, tuner.target())
        advance()
        sample(15)
        assertFalse(tuner.searching)
        assertEquals(8, tuner.target())
        advance()
        assertEquals(8, tuner.limit)
    }

    @Test
    fun `keeps doubling while throughput improves and settles at maximum`() {
        sample(20)
        assertEquals(32, tuner.target())
        advance()
        sample(30)
        assertEquals(64, tuner.target())
        advance()
        sample(40)
        assertFalse(tuner.searching)
        assertFalse(tuner.hasChange())
        assertEquals(64, tuner.limit)
    }

    @Test
    fun `restores baseline if both probes are slower`() {
        sample(30)
        advance()
        sample(20)
        advance()
        sample(10)
        assertFalse(tuner.searching)
        assertEquals(16, tuner.target())
        advance()
        assertEquals(16, tuner.limit)
    }

    @Test
    fun `upward recheck starts by doubling without single-step adjustment`() {
        sample(30)
        advance()
        sample(20)
        advance()
        sample(10)
        advance()
        clock += 121_000
        sample(30)
        assertTrue(tuner.searching)
        assertEquals(32, tuner.target())
    }
}

class SourceImportFiveThousandCategoryTest {
    @Test
    fun `new categories start only after five thousand entries`() {
        val ids = (1L..10_002L).toList()
        val groups = categoryBatches(ids, completedBefore = 0)
        assertEquals(listOf(0, 1, 2), groups.map { it.categoryIndex })
        assertEquals(listOf(5_000, 5_000, 2), groups.map { it.ids.size })
        assertEquals(ids, groups.flatMap { it.ids })
    }

    @Test
    fun `resume offset rolls over at five thousand without losing items`() {
        val ids = listOf(99L, 100L, 101L)
        val groups = categoryBatches(ids, completedBefore = 4_999)
        assertEquals(listOf(0, 1), groups.map { it.categoryIndex })
        assertEquals(listOf(listOf(99L), listOf(100L, 101L)), groups.map { it.ids })
        assertEquals("Example", sourceImportCategoryName("Example", 0))
        assertEquals("Example_2", sourceImportCategoryName("Example", 1))
    }
}
