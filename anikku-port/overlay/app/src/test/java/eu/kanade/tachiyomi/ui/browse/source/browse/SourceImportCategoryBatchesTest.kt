package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceImportCategoryBatchesTest {
    @Test
    fun `exactly five thousand ids stay in first category`() {
        val groups = categoryBatches((1L..5_000L).toList(), 0)
        assertEquals(1, groups.size)
        assertEquals(0, groups.single().categoryIndex)
        assertEquals(5_000, groups.single().ids.size)
    }

    @Test
    fun `partial batch crosses the five thousand boundary without losing ids`() {
        val ids = (1L..140L).toList()
        val groups = categoryBatches(ids, 4_970)
        assertEquals(listOf(0, 1), groups.map { it.categoryIndex })
        assertEquals(listOf(30, 110), groups.map { it.ids.size })
        assertEquals(ids, groups.flatMap { it.ids })
        assertEquals("Pururin", sourceImportCategoryName("Pururin", 0))
        assertEquals("Pururin_2", sourceImportCategoryName("Pururin", 1))
        assertEquals("Pururin_59", sourceImportCategoryName("Pururin", 58))
    }

    @Test
    fun `resume at twenty eight thousand nine hundred stays deterministic`() {
        val ids = (1L..250L).toList()
        val groups = categoryBatches(ids, 29_900)
        assertEquals(listOf(5, 6), groups.map { it.categoryIndex })
        assertEquals(listOf(100, 150), groups.map { it.ids.size })
        assertEquals(groups, categoryBatches(ids, 29_900))
    }
}
