package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SourceImportPageOverlapTest {
    @Test
    fun partiallyOverlappingPagesDoNotDuplicateIdsInJournal() {
        val tracker = SourceImportPageTracker(emptySet())
        val first = tracker.prepare(1, 2, listOf(1L, 2L, 3L).map { SourceImportItem(it, true) })
        val second = tracker.prepare(2, 3, listOf(2L, 3L, 4L).map { SourceImportItem(it, true) })
        val third = tracker.prepare(3, 4, listOf(3L, 4L, 5L).map { SourceImportItem(it, true) })
        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
        assertEquals(listOf(1L, 2L, 3L), first!!.items.map { it.id })
        assertEquals(listOf(4L), second!!.items.map { it.id })
        assertEquals(listOf(5L), third!!.items.map { it.id })
        val dir = Files.createTempDirectory("source-import-overlap").toFile()
        try {
            val journal = SourceImportJournal(File(dir, "journal"))
            journal.planBatch(listOf(first, second, third))
            journal.commitBatch()
            assertEquals(5, journal.added)
            assertEquals(3, journal.pages)
            assertEquals(setOf(1L, 2L, 3L, 4L, 5L), journal.seenIds)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun checkpointReplayExcludesPreviouslyCommittedItemsAndOverlap() {
        val tracker = SourceImportPageTracker(setOf(1L, 2L))
        val first = tracker.prepare(3, 4, listOf(1L, 2L, 3L).map { SourceImportItem(it, true) })
        val second = tracker.prepare(4, 5, listOf(3L, 4L).map { SourceImportItem(it, true) })
        assertEquals(listOf(3L), first!!.items.map { it.id })
        assertEquals(listOf(4L), second!!.items.map { it.id })
    }

    @Test
    fun fullyRepeatedPageTriggersSequentialFallback() {
        val tracker = SourceImportPageTracker(emptySet())
        tracker.prepare(1, 2, listOf(SourceImportItem(1L, true), SourceImportItem(2L, true)))
        val repeat = tracker.prepare(2, 3, listOf(SourceImportItem(1L, true), SourceImportItem(2L, true)))
        assertNull(repeat)
    }

    @Test
    fun retryCountResetsWhenCheckpointAdvances() {
        val dir = Files.createTempDirectory("source-import-retry").toFile()
        try {
            val tracker = SourceImportRetryTracker(File(dir, "retry-state"))
            assertEquals(1, tracker.record(100))
            assertEquals(2, tracker.record(100))
            assertEquals(1, tracker.record(101))
            assertEquals(2, SourceImportRetryTracker(File(dir, "retry-state")).record(101))
            tracker.clear()
            assertEquals(1, tracker.record(101))
        } finally {
            dir.deleteRecursively()
        }
    }
}
