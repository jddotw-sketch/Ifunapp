package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.repository.MangaRepository
import java.time.Instant

class ImportMangaToLibrary(private val repository: MangaRepository) {

    suspend fun await(mangaIds: List<Long>, categoryId: Long, onSaved: (Int, Int) -> Unit) {
        for (batch in mangaIds.distinct().chunked(10)) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val added = repository.importToLibrary(batch, categoryId, Instant.now().toEpochMilli())
                onSaved(added, batch.size - added)
            }
        }
    }
}
