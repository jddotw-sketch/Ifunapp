package tachiyomi.data.manga

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.MangaWithChapterCount
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.manga.repository.SourceImportManga
import java.time.LocalDate
import java.time.ZoneId

class MangaRepositoryImpl(
    private val handler: DatabaseHandler,
) : MangaRepository {

    override suspend fun getMangaById(id: Long): Manga {
        return handler.awaitOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByIdAsFlow(id: Long): Flow<Manga> {
        return handler.subscribeToOne { mangasQueries.getMangaById(id, MangaMapper::mapManga) }
    }

    override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
        return handler.awaitOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
                url,
                sourceId,
                MangaMapper::mapManga,
            )
        }
    }

    override fun getMangaByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Manga?> {
        return handler.subscribeToOneOrNull {
            mangasQueries.getMangaByUrlAndSource(
                url,
                sourceId,
                MangaMapper::mapManga,
            )
        }
    }

    override suspend fun getFavorites(): List<Manga> {
        return handler.awaitList { mangasQueries.getFavorites(MangaMapper::mapManga) }
    }

    override suspend fun getReadMangaNotInLibrary(): List<Manga> {
        return handler.awaitList { mangasQueries.getReadMangaNotInLibrary(MangaMapper::mapManga) }
    }

    override suspend fun getLibraryManga(): List<LibraryManga> {
        return handler.awaitList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getLibraryMangaAsFlow(): Flow<List<LibraryManga>> {
        return handler.subscribeToList { libraryViewQueries.library(MangaMapper::mapLibraryManga) }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Manga>> {
        return handler.subscribeToList { mangasQueries.getFavoriteBySourceId(sourceId, MangaMapper::mapManga) }
    }

    override suspend fun getDuplicateLibraryManga(id: Long, title: String): List<MangaWithChapterCount> {
        return handler.awaitList {
            mangasQueries.getDuplicateLibraryManga(id, title, MangaMapper::mapMangaWithChapterCount)
        }
    }

    override suspend fun getUpcomingManga(statuses: Set<Long>): Flow<List<Manga>> {
        val epochMillis = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond() * 1000
        return handler.subscribeToList {
            mangasQueries.getUpcomingManga(epochMillis, statuses, MangaMapper::mapManga)
        }
    }

    override suspend fun resetViewerFlags(): Boolean {
        return try {
            handler.await { mangasQueries.resetViewerFlags() }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
        handler.await(inTransaction = true) {
            mangas_categoriesQueries.deleteMangaCategoryByMangaId(mangaId)
            categoryIds.map { categoryId ->
                mangas_categoriesQueries.insert(mangaId, categoryId)
            }
        }
    }

    override suspend fun importToLibrary(mangaIds: List<Long>, categoryId: Long, dateAdded: Long): Int {
        if (mangaIds.isEmpty()) return 0
        return handler.await(inTransaction = true) {
            val ids = mangaIds.distinct()
            val added = mangasQueries.countSourceImportAdditions(ids).executeAsOne().toInt()
            // Replacing category assignments set-wise avoids two prepared statements
            // per manga, while retaining the old replace-all category semantics.
            mangas_categoriesQueries.deleteSourceImportCategories(ids, categoryId)
            mangas_categoriesQueries.insertSourceImportCategories(categoryId, ids)
            mangasQueries.addSourceImportToLibrary(dateAdded, ids)
            added
        }
    }

    /**
     * Same library/category updates as importToLibrary, but without counting
     * additions that the importer already tracks in its durable journal.
     */
    override suspend fun importToLibraryFast(mangaIds: List<Long>, categoryId: Long, dateAdded: Long) {
        if (mangaIds.isEmpty()) return
        handler.await(inTransaction = true) {
            val ids = mangaIds.distinct()
            mangas_categoriesQueries.deleteSourceImportCategories(ids, categoryId)
            mangas_categoriesQueries.insertSourceImportCategories(categoryId, ids)
            mangasQueries.addSourceImportToLibrary(dateAdded, ids)
        }
    }

    override suspend fun update(update: MangaUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
        return try {
            partialUpdate(*mangaUpdates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    /**
     * Insert new source entries without the two redundant statements and full-row
     * mapping used by regular browsing. Preserve existing library records exactly.
     * All inserts and the compact lookup run in one transaction per page.
     */
    override suspend fun insertNetworkMangaForImport(manga: List<Manga>): List<SourceImportManga> {
        if (manga.isEmpty()) return emptyList()
        val unique = manga.distinctBy { it.url }
        val sourceId = unique.first().source
        check(unique.all { it.source == sourceId }) { "Source import must have one source per batch" }
        return handler.await(inTransaction = true) {
            unique.forEach {
                mangasQueries.insertSourceImportNetworkManga(
                    source = it.source,
                    url = it.url,
                    title = it.ogTitle,
                    artist = it.ogArtist,
                    author = it.ogAuthor,
                    thumbnailUrl = it.ogThumbnailUrl,
                    description = it.ogDescription,
                    genre = it.ogGenre,
                    status = it.ogStatus,
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    memo = it.memo,
                )
            }
            // Keep bound IN clauses under Android SQLite's older 999-parameter limit.
            val found = unique.chunked(400).flatMap { entries ->
                mangasQueries.getSourceImportNetworkManga(
                    sourceId,
                    entries.map { it.url },
                ) { id, url, favorite -> SourceImportManga(id, url, favorite) }.executeAsList()
            }
            // Match the previous SELECT ... LIMIT 1 behavior if an old database
            // happens to contain multiple records with a source/url pair.
            val byUrl = linkedMapOf<String, SourceImportManga>()
            found.forEach { byUrl.putIfAbsent(it.url, it) }
            unique.map { checkNotNull(byUrl[it.url]) { "Source import record missing after insert: ${it.url}" } }
        }
    }

    override suspend fun insertNetworkManga(
        manga: List<Manga>,
        // KMK -->
        updateInfo: Boolean,
        // KMK <--
    ): List<Manga> {
        return handler.await(inTransaction = true) {
            manga.map {
                mangasQueries.insertNetworkManga(
                    source = it.source,
                    url = it.url,
                    // SY -->
                    title = it.ogTitle,
                    artist = it.ogArtist,
                    author = it.ogAuthor,
                    thumbnailUrl = it.ogThumbnailUrl,
                    description = it.ogDescription,
                    genre = it.ogGenre,
                    status = it.ogStatus,
                    // SY <--
                    favorite = it.favorite,
                    lastUpdate = it.lastUpdate,
                    nextUpdate = it.nextUpdate,
                    calculateInterval = it.fetchInterval.toLong(),
                    initialized = it.initialized,
                    viewerFlags = it.viewerFlags,
                    chapterFlags = it.chapterFlags,
                    coverLastModified = it.coverLastModified,
                    dateAdded = it.dateAdded,
                    updateStrategy = it.updateStrategy,
                    version = it.version,
                    memo = it.memo,
                    // SY -->
                    updateTitle = it.ogTitle.isNotBlank(),
                    updateCover = !it.ogThumbnailUrl.isNullOrBlank(),
                    // SY <--
                    updateDetails = it.initialized,
                    // KMK -->
                    updateInfo = updateInfo,
                    // KMK <--
                    mapper = MangaMapper::mapManga,
                )
                    .executeAsOne()
            }
        }
    }

    private suspend fun partialUpdate(vararg mangaUpdates: MangaUpdate) {
        handler.await(inTransaction = true) {
            mangaUpdates.forEach { value ->
                mangasQueries.update(
                    source = value.source,
                    url = value.url,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre?.let(StringListColumnAdapter::encode),
                    title = value.title,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite,
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    calculateInterval = value.fetchInterval?.toLong(),
                    initialized = value.initialized,
                    viewer = value.viewerFlags,
                    chapterFlags = value.chapterFlags,
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    mangaId = value.id,
                    updateStrategy = value.updateStrategy?.let(UpdateStrategyColumnAdapter::encode),
                    version = value.version,
                    isSyncing = 0,
                    notes = value.notes,
                    memo = value.memo?.let(MemoColumnAdapter::encode),
                )
            }
        }
    }

    // SY -->
    override suspend fun getMangaBySourceId(sourceId: Long): List<Manga> {
        return handler.awaitList { mangasQueries.getBySource(sourceId, MangaMapper::mapManga) }
    }

    override suspend fun getAll(): List<Manga> {
        return handler.awaitList { mangasQueries.getAll(MangaMapper::mapManga) }
    }

    override suspend fun deleteManga(mangaId: Long) {
        handler.await { mangasQueries.deleteById(mangaId) }
    }

    override suspend fun getReadMangaNotInLibraryView(): List<LibraryManga> {
        return handler.awaitList { libraryViewQueries.readMangaNonLibrary(MangaMapper::mapLibraryManga) }
    }
    // SY <--
}
