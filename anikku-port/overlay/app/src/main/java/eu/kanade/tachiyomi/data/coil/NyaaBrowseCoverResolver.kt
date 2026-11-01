package eu.kanade.tachiyomi.data.coil

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.getMangaDetails
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// ANK: Called by Coil only when a visible Nyaa item has no cover. Never loads episode lists.
class NyaaBrowseCoverResolver(
    private val getManga: GetManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
) {
    private val lookups = NyaaCoverLookupCache()

    suspend fun resolve(mangaId: Long, source: HttpSource?): String? {
        if (source == null || !isNyaaExtension(source.javaClass.name)) return null
        return withContext(Dispatchers.IO) {
            val manga = getManga.await(mangaId) ?: return@withContext null
            manga.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            lookups.resolve("${source.id}:${manga.url}") lookup@{
                val details = source.getMangaDetails(manga.toSManga())
                val cover = validCoverUrl(details.thumbnail_url) ?: return@lookup null
                // Only fill the missing thumbnail; leave title, library state and detail initialization alone.
                val current = getManga.await(mangaId) ?: return@lookup cover
                current.thumbnailUrl?.takeIf { it.isNotBlank() } ?: cover.also {
                    updateManga.await(MangaUpdate(id = mangaId, thumbnailUrl = it))
                }
            }
        }
    }

    companion object {
        fun isNyaaExtension(className: String) = className.startsWith("eu.kanade.tachiyomi.animeextension.all.nyaatorrent.")
        fun validCoverUrl(value: String?): String? = value?.trim()?.toHttpUrlOrNull()
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }
}

// Serialize and coalesce detail lookups; cache both covers and short-lived misses without unbounded growth.
internal class NyaaCoverLookupCache(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val url: String?, val expires: Long)
    private val entries = linkedMapOf<String, Entry>()
    private val lock = Mutex()

    suspend fun resolve(key: String, load: suspend () -> String?): String? = lock.withLock {
        entries[key]?.takeIf { it.expires > now() }?.let { return@withLock it.url }
        val url = try {
            withTimeoutOrNull(15_000) { load() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // A failed cover must not fail the browse list or trigger a request loop.
        }
        if (entries.size >= 256) entries.remove(entries.keys.first())
        entries[key] = Entry(url, now() + if (url == null) 60_000 else 86_400_000)
        url
    }
}
