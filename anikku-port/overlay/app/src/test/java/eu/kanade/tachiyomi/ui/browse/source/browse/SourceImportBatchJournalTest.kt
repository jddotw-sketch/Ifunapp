package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class SourceImportBatchJournalTest {

    private fun pages() = (1..4).map { key ->
        SourceImportPlan(
            key,
            if (key == 4) 5 else key + 1,
            listOf(SourceImportItem(key.toLong(), key <= 2)),
        )
    }

    @Test
    fun `four pages have one recoverable pending batch and advance together`() {
        val dir = Files.createTempDirectory("source-import-batch").toFile()
        try {
            val file = File(dir, "journal")
            SourceImportJournal(file).planBatch(pages())
            val pending = SourceImportJournal(file)
            assertEquals(pages(), pending.pendingBatch)
            assertEquals(1, pending.nextKey)
            assertEquals(0, pending.pages)
            pending.commitBatch()
            val resumed = SourceImportJournal(file)
            assertNull(resumed.pendingBatch)
            assertEquals(5, resumed.nextKey)
            assertEquals(4, resumed.pages)
            assertEquals(2, resumed.added)
            assertEquals(2, resumed.existing)
            assertEquals(setOf(1L, 2L, 3L, 4L), resumed.seenIds)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `truncated batch commit is replayable without advancing pages`() {
        val dir = Files.createTempDirectory("source-import-batch").toFile()
        try {
            val file = File(dir, "journal")
            val writer = SourceImportJournal(file)
            writer.planBatch(pages())
            val preCommitLength = file.length()
            writer.commitBatch()
            RandomAccessFile(file, "rw").use { it.setLength(preCommitLength + 5) }
            val repaired = SourceImportJournal(file, repair = true)
            assertNotNull(repaired.pendingBatch)
            assertEquals(1, repaired.nextKey)
            assertEquals(0, repaired.pages)
            repaired.commitBatch()
            assertEquals(4, SourceImportJournal(file).pages)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `legacy single-page checkpoints remain compatible with new batches`() {
        val dir = Files.createTempDirectory("source-import-batch").toFile()
        try {
            val file = File(dir, "journal")
            val legacy = SourceImportJournal(file)
            legacy.plan(SourceImportPlan(1, 2, listOf(SourceImportItem(100L, true))))
            legacy.commit()
            val upgraded = SourceImportJournal(file)
            val batch = listOf(
                SourceImportPlan(2, 3, listOf(SourceImportItem(101L, true))),
                SourceImportPlan(3, null, listOf(SourceImportItem(102L, false))),
            )
            upgraded.planBatch(batch)
            upgraded.commitBatch()
            val reopened = SourceImportJournal(file)
            assertEquals(3, reopened.pages)
            assertNull(reopened.nextKey)
            assertEquals(2, reopened.added)
            assertEquals(1, reopened.existing)
            assertTrue(reopened.seenIds.containsAll(listOf(100L, 101L, 102L)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
