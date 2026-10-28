package eu.kanade.tachiyomi.ui.browse.source.browse

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class SourceImportBackgroundState(
    val id: String? = null,
    val status: SourceImportStatus? = null,
    val added: Int = 0,
    val existing: Int = 0,
    val pages: Int = 0,
    val downloadedPages: Int = 0,
    val importMode: String = "STREAM",
    val saveRequested: Boolean = false,
    val canResume: Boolean = false,
    val error: String? = null,
    val stats: SourceImportStats? = null,
) {
    val active get() = status == SourceImportStatus.QUEUED || status == SourceImportStatus.RUNNING
}

internal object SourceImportManager {
    private val context get() = Injekt.get<Application>()
    private val states = ConcurrentHashMap<Long, MutableStateFlow<SourceImportBackgroundState>>()
    private val commandMutex = Mutex()

    private fun state(sourceId: Long) = states.getOrPut(sourceId) { MutableStateFlow(SourceImportBackgroundState()) }

    fun observe(sourceId: Long): Flow<SourceImportBackgroundState> = flow {
        val state = state(sourceId)
        val initial = state.value
        if (initial.status == null) {
            val restored = runCatching {
                val store = SourceImportStore.current(context.filesDir, sourceId) ?: return@runCatching SourceImportBackgroundState()
                val journal = SourceImportJournal(File(store.directory, "journal"))
                val value = SourceImportBackgroundState(
                    id = store.config.id,
                    status = if (store.pauseRequested) SourceImportStatus.PAUSED else journal.status,
                    added = journal.added,
                    existing = journal.existing,
                    pages = journal.pages,
                    downloadedPages = journal.pages + SourceImportDiskSpool(File(store.directory, "page-spool")).cachedPageCount(),
                    importMode = store.config.importMode,
                    saveRequested = store.saveRequestToken != null,
                    canResume = journal.status != SourceImportStatus.FINISHED,
                    error = journal.error,
                )
                if (!store.pauseRequested && journal.status != SourceImportStatus.FINISHED) {
                    scheduleRecoveryWatch()
                }
                value
            }.getOrElse { SourceImportBackgroundState(status = SourceImportStatus.FAILED, error = it.message) }
            state.compareAndSet(initial, restored)
            // The previous APK may have left an old work request FAILED or
            // CANCELLED. Reconcile it immediately when this screen opens.
            if (restored.id != null && restored.status != SourceImportStatus.FINISHED) {
                recoverAbandonedImports()
            }
        }
        emitAll(state)
    }

    suspend fun start(config: SourceImportConfig) = commandMutex.withLock {
        val previous = runCatching { SourceImportStore.current(context.filesDir, config.sourceId) }.getOrNull()
        previous?.pause()
        val store = SourceImportStore.create(context.filesDir, config)
        state(config.sourceId).value = SourceImportBackgroundState(
            id = config.id,
            status = SourceImportStatus.QUEUED,
            canResume = true,
        )
        enqueue(store.config, ExistingWorkPolicy.REPLACE)
        scheduleRecoveryWatch()
        previous?.cleanIfReplaced()
    }

    suspend fun resume(sourceId: Long, minimumPages: Int? = null) = commandMutex.withLock {
        val store = checkNotNull(SourceImportStore.current(context.filesDir, sourceId))
        minimumPages?.let { store.update(store.config.copy(minimumPages = it.coerceIn(0, 1_000_000))) }
        store.resume()
        SourceImportRetryTracker(File(store.directory, "retry-state")).clear()
        state(sourceId).value = state(sourceId).value.copy(status = SourceImportStatus.QUEUED, error = null, stats = null)
        enqueue(store.config, ExistingWorkPolicy.REPLACE)
        scheduleRecoveryWatch()
    }

    suspend fun saveDownloaded(sourceId: Long) = commandMutex.withLock {
        val store = SourceImportStore.current(context.filesDir, sourceId) ?: return@withLock
        if (store.config.importMode != "DOWNLOAD_ALL") return@withLock
        store.requestSaveDownloaded()
        state(sourceId).value = state(sourceId).value.copy(saveRequested = true)
    }

    suspend fun pause(sourceId: Long) = commandMutex.withLock {
        val store = SourceImportStore.current(context.filesDir, sourceId) ?: return@withLock
        store.pause()
        WorkManager.getInstance(context).cancelUniqueWork(workName(sourceId)).result.get()
        state(sourceId).value = state(sourceId).value.copy(status = SourceImportStatus.PAUSED, canResume = true)
    }

    fun publish(store: SourceImportStore, value: SourceImportBackgroundState) {
        if (isCurrent(store)) state(store.config.sourceId).value = value
    }

    fun retrying(sourceId: Long, message: String, expectedId: String? = null) {
        val state = state(sourceId)
        val previous = state.value
        if (expectedId != null && previous.id != null && previous.id != expectedId) return
        state.value = previous.copy(status = SourceImportStatus.QUEUED, error = message, canResume = previous.id != null)
    }

    fun failed(sourceId: Long, message: String, expectedId: String? = null) {
        val state = state(sourceId)
        val previous = state.value
        if (expectedId != null && previous.id != null && previous.id != expectedId) return
        state.value = previous.copy(status = SourceImportStatus.FAILED, error = message, canResume = previous.id != null)
    }

    fun isCurrent(store: SourceImportStore): Boolean =
        File(store.directory.parentFile, "current").readText().trim() == store.config.id

    private fun enqueue(config: SourceImportConfig, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<SourceImportWorker>()
            .setInputData(workDataOf("sourceId" to config.sourceId, "jobId" to config.id, "sourceName" to config.sourceName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
            .addTag("SourceImport")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(config.sourceId), policy, request).result.get()
    }

    private fun workName(sourceId: Long) = "SourceImport-$sourceId"

    private fun scheduleRecoveryWatch() {
        // WorkManager and Android can terminate jobs while the app is closed.
        // An on-device periodic reconciliation restores orphaned imports.
        val request = PeriodicWorkRequestBuilder<SourceImportRecoveryWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "SourceImport-Recovery",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun recoverAbandonedImports() = commandMutex.withLock {
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            val folders = File(context.filesDir, "source-import").listFiles().orEmpty()
            for (folder in folders) {
                currentCoroutineContext().ensureActive()
                val sourceId = folder.name.toLongOrNull() ?: continue
                val store = runCatching { SourceImportStore.current(context.filesDir, sourceId) }.getOrNull() ?: continue
                if (store.pauseRequested) continue

                // Inspect the actual WorkManager state BEFORE opening the journal:
                // a running worker may still be writing its last journal record.
                val isActive = manager.getWorkInfosForUniqueWork(workName(sourceId)).get().any {
                    it.state == WorkInfo.State.RUNNING ||
                        it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED
                }
                if (isActive) continue
                val journal = runCatching {
                    SourceImportJournal(File(store.directory, "journal"), repair = true)
                }.getOrNull() ?: continue
                if (!sourceImportNeedsRecovery(journal.status, store.pauseRequested, isActive)) continue

                val current = state(sourceId).value
                if (current.id == null || current.id == store.config.id) {
                    state(sourceId).value = current.copy(
                        id = store.config.id,
                        status = SourceImportStatus.QUEUED,
                        canResume = true,
                        error = journal.error,
                    )
                }
                enqueue(store.config, ExistingWorkPolicy.REPLACE)
            }
        }
    }
}

// Restarts jobs that Android abandoned without touching explicitly paused
// imports or already completed catalogs.
internal class SourceImportRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        SourceImportManager.recoverAbandonedImports()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.retry()
    }
}
