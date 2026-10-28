package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceImportDiskSpoolTest {
    @Test
    fun `disk cache round trips pages and traverses next keys`() {
        val dir = Files.createTempDirectory("import-spool-test").toFile()
        try {
            val spool = SourceImportDiskSpool(dir)
            val manga = SManga.create().apply { url = "/gallery/42"; title = "Test title" }
            spool.write(SourceImportDiskSpool.Page(1, 3, listOf(manga)))
            assertEquals(3, spool.firstUncachedKey(1))
            val cached = spool.read(1)!!
            assertEquals(3, cached.next)
            assertEquals("/gallery/42", cached.mangas.single().url)
            assertEquals("Test title", cached.mangas.single().title)
            spool.write(SourceImportDiskSpool.Page(3, null, listOf(manga)))
            assertNull(spool.firstUncachedKey(1))
            assertEquals(2, spool.cachedPageCount())
            spool.remove(listOf(1))
            assertFalse(spool.contains(1))
            assertTrue(spool.contains(3))
        } finally {
            dir.deleteRecursively()
        }
    }
}
