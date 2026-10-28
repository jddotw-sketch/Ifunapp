package eu.kanade.tachiyomi.ui.browse.source.browse

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.interactor.ImportMangaToLibrary
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException

class SourceImportBatchTest {

    @Test
    fun `eight hundred twenty five items use three saves and duplicate ids are counted once`() = runTest {
        val repository = mockk<MangaRepository>()
        val batches = mutableListOf<List<Long>>()
        coEvery { repository.importToLibrary(any(), 7L, any()) } answers {
            firstArg<List<Long>>().also { batches.add(it) }.size
        }
        val counts = mutableListOf<Pair<Int, Int>>()

        ImportMangaToLibrary(repository).await((1L..825L).toList() + 1L, 7L) { added, existing ->
            counts.add(added to existing)
        }

        assertEquals(listOf(400, 400, 25), batches.map { it.size })
        assertEquals((1L..825L).toList(), batches.flatten())
        assertEquals(listOf(400 to 0, 400 to 0, 25 to 0), counts)
        coVerify(exactly = 3) { repository.importToLibrary(any(), 7L, any()) }
    }

    @Test
    fun `existing items have their own count and failed batches are not reported as saved`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.importToLibrary((1L..400L).toList(), 7L, any()) } returns 360
        coEvery { repository.importToLibrary((401L..800L).toList(), 7L, any()) } throws IOException("Save failed")
        val counts = mutableListOf<Pair<Int, Int>>()
        var failure: IOException? = null

        try {
            ImportMangaToLibrary(repository).await((1L..825L).toList(), 7L) { added, existing ->
                counts.add(added to existing)
            }
        } catch (error: IOException) {
            failure = error
        }

        assertEquals("Save failed", failure?.message)
        assertEquals(listOf(360 to 40), counts)
        coVerify(exactly = 2) { repository.importToLibrary(any(), 7L, any()) }
    }

    @Test
    fun `stopping keeps the saved batch and prevents the next save`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.importToLibrary(any(), 7L, any()) } returns 400
        var saved = 0
        val job = launch {
            ImportMangaToLibrary(repository).await((1L..825L).toList(), 7L) { added, _ ->
                saved += added
                cancel()
            }
        }
        job.join()

        assertEquals(400, saved)
        coVerify(exactly = 1) { repository.importToLibrary(any(), 7L, any()) }
    }
}
