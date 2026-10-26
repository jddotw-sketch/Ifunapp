package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import exh.source.isEhBasedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.ImportMangaToLibrary
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.FilterSerializer
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

class SourceImportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val sourceId = inputData.getLong("sourceId", 0)
    private val notificationId = 0x4F000000 or ((sourceId.hashCode() and 0x007FFFFF) shl 1)
    @Volatile
    private var added = 0
    @Volatile
    private var existing = 0
    @Volatile
    private var retrying = false

    override suspend fun getForegroundInfo() = ForegroundInfo(
        notificationId,
        notification(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    private fun notification() = applicationContext.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
        setSmallIcon(android.R.drawable.stat_sys_download)
        setContentTitle(inputData.getString("sourceName"))
        setContentText(
            applicationContext.stringResource(
                if (retrying) KMR.strings.source_import_background_retry else KMR.strings.source_import_background_counts,
                added,
                existing,
            ),
        )
        setContentIntent(
            PendingIntent.getActivity(
                applicationContext,
                notificationId,
                Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        addAction(
            android.R.drawable.ic_media_pause,
            applicationContext.stringResource(KMR.strings.source_import_pause),
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        )
        setOngoing(true)
        setOnlyAlertOnce(true)
        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        setProgress(0, 0, true)
    }.build()

    override suspend fun doWork(): Result = try {
        runImport()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        SourceImportManager.failed(sourceId, error.message ?: error.javaClass.simpleName, inputData.getString("jobId"))
        Result.failure()
    }

    private suspend fun runImport(): Result = withContext(Dispatchers.IO) {
        val store = SourceImportStore.current(applicationContext.filesDir, sourceId) ?: return@withContext Result.failure()
        if (store.config.id != inputData.getString("jobId")) return@withContext Result.success()
        try {
        RandomAccessFile(File(store.directory, "lock"), "rw").use { file ->
            var lock: FileLock? = null
            while (lock == null) {
                currentCoroutineContext().ensureActive()
                lock = try {
                    file.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock == null) delay(250)
            }
            lock.use {
                if (!SourceImportManager.isCurrent(store)) return@withContext Result.success()
                val journal = SourceImportJournal(File(store.directory, "journal"), repair = true)
                added = journal.added
                existing = journal.existing
                if (journal.status == SourceImportStatus.FINISHED) {
                    publish(store, journal, null)
                    return@withContext Result.success()
                }
                if (store.pauseRequested) {
                    journal.status(SourceImportStatus.PAUSED)
                    publish(store, journal, null)
                    return@withContext Result.success()
                }
                execute(store, journal)
            }
        }
        } finally {
            store.cleanIfReplaced()
        }
    }

    private suspend fun execute(store: SourceImportStore, journal: SourceImportJournal): Result = coroutineScope {
        added = journal.added
        existing = journal.existing
        val telemetry = SourceImportTelemetry(
            store.config.preloadPages,
            automatic = store.config.automatic,
            restoredAdded = journal.added,
            restoredExisting = journal.existing,
            restoredPages = journal.pages,
        )
        journal.status(SourceImportStatus.RUNNING)
        publish(store, journal, telemetry)
        val ticker = launch {
            while (isActive) {
                if (store.pauseRequested || !SourceImportManager.isCurrent(store)) {
                    this@coroutineScope.cancel()
                    break
                }
                publish(store, journal, telemetry)
                applicationContext.notify(notificationId, notification())
                delay(1_000)
            }
        }
        try {
            setForeground(getForegroundInfo())
            val sourceManager = Injekt.get<SourceManager>()
            sourceManager.isInitialized.first { it }
            val source = checkNotNull(sourceManager.get(sourceId)) {
                applicationContext.stringResource(KMR.strings.source_import_missing_source)
            }
            val getCategories = Injekt.get<GetCategories>()
            val category = getCategories.await().firstOrNull { it.id == store.config.categoryId }
                ?: getCategories.await().firstOrNull { !it.isSystemCategory && it.name.equals(source.name, true) }
                ?: when (val result = Injekt.get<CreateCategoryWithName>().await(source.name)) {
                    is CreateCategoryWithName.Result.Success -> getCategories.await()
                        .first { it.name.equals(source.name, true) }
                    is CreateCategoryWithName.Result.InternalError -> throw result.error
                }
            store.update(store.config.copy(categoryId = category.id))
            val filters = source.getFilterList()
            if (store.config.listing == "SEARCH") {
                val saved = Json.decodeFromString<JsonArray>(store.config.filters)
                val serializer = FilterSerializer()
                check(filters.size == saved.size) { applicationContext.stringResource(KMR.strings.source_import_changed_filters) }
                serializer.deserialize(filters, saved)
                check(serializer.serialize(filters) == saved) {
                    applicationContext.stringResource(KMR.strings.source_import_changed_filters)
                }
            }
            val importManga = Injekt.get<ImportMangaToLibrary>()
            suspend fun savePlan(plan: SourceImportPlan) {
                var processed = 0
                val baseAdded = journal.added
                val baseExisting = journal.existing
                importManga.await(plan.items.map { it.id }, category.id) { batchAdded, batchExisting ->
                    val count = batchAdded + batchExisting
                    val nextProcessed = processed + count
                    val batch = plan.items.subList(processed, nextProcessed)
                    processed = nextProcessed
                    telemetry.items(batch.count { it.added }, batch.count { !it.added })
                    added = baseAdded + plan.items.subList(0, processed).count { it.added }
                    existing = baseExisting + processed - (added - baseAdded)
                }
                withContext(NonCancellable) { journal.commit() }
                added = journal.added
                existing = journal.existing
                publish(store, journal, telemetry)
            }
            suspend fun saveBatch(plans: List<SourceImportPlan>) {
                val allItems = plans.flatMap(SourceImportPlan::items)
                importManga.await(allItems.map(SourceImportItem::id), category.id) { _, _ -> }
                // A replay after a crash is safe: the database operation is idempotent,
                // and the journal is only advanced after the database transaction.
                withContext(NonCancellable) { journal.commitBatch() }
                telemetry.items(allItems.count { it.added }, allItems.count { !it.added })
                added = journal.added
                existing = journal.existing
                publish(store, journal, telemetry)
            }
            journal.pendingBatch?.let { plans ->
                telemetry.saving()
                saveBatch(plans)
                telemetry.saved(true)
            }
            journal.pending?.let { plan ->
                telemetry.saving()
                savePlan(plan)
                telemetry.saved(true)
            }
            journal.nextKey?.let { firstKey ->
                val pageTracker = SourceImportPageTracker(journal.seenIds)
                val loader = SourceImportLoader(
                    slowMode = store.config.slowMode,
                    usesCursor = source.isEhBasedSource(),
                    onRetry = { delayMillis ->
                        retrying = delayMillis != null
                        if (delayMillis != null) store.update(store.config.copy(recoveryDelayMillis = delayMillis))
                    },
                    preloadPages = store.config.preloadPages,
                    telemetry = telemetry,
                    automatic = store.config.automatic,
                    firstRequestKey = firstKey,
                    initialPageDelayMillis = store.config.recoveryDelayMillis,
                    forceSequential = store.config.sequential && store.config.recoveryDelayMillis == 0L,
                    onSequentialFallback = { store.update(store.config.copy(sequential = true)) },
                )
                val pendingPages = mutableListOf<SourceImportPlan>()
                suspend fun flushPendingPages() {
                    if (pendingPages.isEmpty()) return
                    val plans = pendingPages.toList()
                    journal.planBatch(plans)
                    saveBatch(plans)
                    pendingPages.clear()
                }
                suspend fun requestCatalogPage(key: Int) = when (store.config.listing) {
                    "POPULAR" -> source.getPopularManga(key)
                    "LATEST" -> source.getLatestUpdates(key)
                    "SEARCH" -> source.getSearchManga(key, store.config.query, filters)
                    else -> error("Invalid saved listing")
                }
                suspend fun processPage(key: Int, next: Int?, pageMangas: List<eu.kanade.tachiyomi.source.model.SManga>): Boolean {
                    currentCoroutineContext().ensureActive()
                    val mangas = Injekt.get<NetworkToLocalManga>()(
                        pageMangas.distinctBy { it.url }.map { it.toDomainManga(sourceId) },
                        updateInfo = false,
                    )
                    val plan = pageTracker.prepare(key, next, mangas.map { SourceImportItem(it.id, !it.favorite) })
                    if (plan == null) {
                        flushPendingPages()
                        return false
                    }
                    pendingPages.add(plan)
                    if (pendingPages.size >= 4 || pendingPages.sumOf { it.items.size } >= 100 || next == null) {
                        flushPendingPages()
                    }
                    return true
                }
                val importMode = store.config.importMode
                if (importMode == "STREAM") {
                    loader.runWithKeys(::requestCatalogPage) { key, page ->
                        processPage(key, nextSourceImportPage(page, key, source.isEhBasedSource()), page.mangas)
                    }
                } else {
                    val spool = SourceImportDiskSpool(File(store.directory, "page-spool"))
                    val firstUncached = spool.firstUncachedKey(firstKey)
                    val downloaderDone = java.util.concurrent.atomic.AtomicBoolean(firstUncached == null)
                    val signal = Channel<Unit>(Channel.CONFLATED)
                    // Detect an extension returning an identical catalog page under
                    // parallel pagination before preserving it in the disk queue.
                    val pageSignatures = mutableSetOf<Pair<String, String>>()
                    coroutineScope {
                        val downloader = launch {
                            try {
                                if (firstUncached != null) {
                                    val downloadLoader = SourceImportLoader(
                                        slowMode = store.config.slowMode,
                                        usesCursor = source.isEhBasedSource(),
                                        onRetry = { delayMillis ->
                                            retrying = delayMillis != null
                                            if (delayMillis != null) store.update(store.config.copy(recoveryDelayMillis = delayMillis))
                                        },
                                        preloadPages = store.config.preloadPages,
                                        telemetry = telemetry,
                                        automatic = store.config.automatic,
                                        firstRequestKey = firstUncached,
                                        initialPageDelayMillis = store.config.recoveryDelayMillis,
                                        forceSequential = store.config.sequential && store.config.recoveryDelayMillis == 0L,
                                        onSequentialFallback = { store.update(store.config.copy(sequential = true)) },
                                    )
                                    downloadLoader.runWithKeys(::requestCatalogPage) { key, page ->
                                        while (importMode == "BUFFERED" && spool.cachedPageCount() >= 128) {
                                            currentCoroutineContext().ensureActive()
                                            delay(100)
                                        }
                                        val signature = page.mangas.firstOrNull()?.let { first ->
                                            first.url to page.mangas.last().url
                                        }
                                        if (signature != null && !pageSignatures.add(signature)) {
                                            return@runWithKeys false
                                        }
                                        val next = nextSourceImportPage(page, key, source.isEhBasedSource())
                                        spool.write(SourceImportDiskSpool.Page(key, next, page.mangas))
                                        signal.trySend(Unit)
                                        true
                                    }
                                }
                            } finally {
                                downloaderDone.set(true)
                                signal.trySend(Unit)
                            }
                        }
                        var partialSaveToken: String? = null
                        if (importMode == "DOWNLOAD_ALL") {
                            while (!downloaderDone.get()) {
                                currentCoroutineContext().ensureActive()
                                partialSaveToken = store.saveRequestToken
                                if (partialSaveToken != null) {
                                    // Stop new page loads at the request boundary. The pages
                                    // already atomically cached remain safe to import.
                                    downloader.cancel()
                                    downloader.join()
                                    break
                                }
                                delay(200)
                            }
                        }
                        var readKey: Int? = firstKey
                        var previousBatchKeys = mutableListOf<Int>()
                        while (readKey != null) {
                            currentCoroutineContext().ensureActive()
                            val cached = spool.read(readKey)
                            if (cached == null) {
                                if (downloaderDone.get()) break
                                signal.receive()
                                continue
                            }
                            val keepGoing = processPage(cached.key, cached.next, cached.mangas)
                            previousBatchKeys.add(cached.key)
                            // Discard only pages whose ids are committed in the journal.
                            if (pendingPages.isEmpty()) {
                                spool.remove(previousBatchKeys)
                                previousBatchKeys.clear()
                            }
                            if (!keepGoing) {
                                downloader.cancel()
                                break
                            }
                            readKey = cached.next
                        }
                        if (!downloaderDone.get()) downloader.join()
                        flushPendingPages()
                        if (partialSaveToken != null) {
                            store.acknowledgeSaveDownloaded(partialSaveToken)
                            journal.status(SourceImportStatus.PAUSED)
                            publish(store, journal, telemetry)
                            return@coroutineScope
                        }
                    }
                }
                // Sources may terminate on an empty response rather than on
                // hasNextPage=false. Commit the final partial group in that case.
                flushPendingPages()
            }
            if (journal.status == SourceImportStatus.PAUSED) return@coroutineScope Result.success()
            journal.status(SourceImportStatus.FINISHED)
            Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { journal.status(SourceImportStatus.PAUSED) }
            throw error
        } catch (error: Exception) {
            journal.status(SourceImportStatus.FAILED, error.message ?: error.javaClass.simpleName)
            Result.failure()
        } finally {
            ticker.cancel()
            telemetry.finish()
            publish(store, journal, telemetry)
        }
    }

    private fun publish(store: SourceImportStore, journal: SourceImportJournal, telemetry: SourceImportTelemetry?) {
        SourceImportManager.publish(
            store,
            SourceImportBackgroundState(
                id = store.config.id,
                status = journal.status,
                added = added,
                existing = existing,
                pages = journal.pages,
                downloadedPages = journal.pages + if (store.config.importMode == "STREAM") 0 else {
                    SourceImportDiskSpool(File(store.directory, "page-spool")).cachedPageCount()
                },
                importMode = store.config.importMode,
                saveRequested = store.saveRequestToken != null,
                canResume = journal.status != SourceImportStatus.FINISHED,
                error = journal.error,
                stats = telemetry?.snapshot(),
            ),
        )
    }
}
