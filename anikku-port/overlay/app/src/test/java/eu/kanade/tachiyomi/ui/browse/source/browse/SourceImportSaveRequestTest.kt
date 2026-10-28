package eu.kanade.tachiyomi.ui.browse.source.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SourceImportSaveRequestTest {
    @Test
    fun `save downloaded command survives restart and requires matching acknowledgement`() {
        val directory = Files.createTempDirectory("source-import-save-command").toFile()
        try {
            val created = SourceImportStore.create(
                directory,
                SourceImportConfig(
                    sourceId = 42L,
                    sourceName = "Test catalog",
                    listing = "POPULAR",
                    importMode = "DOWNLOAD_ALL",
                    preloadPages = 64,
                ),
            )
            created.requestSaveDownloaded()
            val token = created.saveRequestToken
            assertNotNull(token)
            val resumed = SourceImportStore.current(directory, 42L)!!
            assertEquals(64, resumed.config.preloadPages)
            assertEquals(token, resumed.saveRequestToken)
            resumed.acknowledgeSaveDownloaded("wrong-token")
            assertEquals(token, resumed.saveRequestToken)
            resumed.acknowledgeSaveDownloaded(token!!)
            assertNull(resumed.saveRequestToken)
        } finally {
            directory.deleteRecursively()
        }
    }
}
