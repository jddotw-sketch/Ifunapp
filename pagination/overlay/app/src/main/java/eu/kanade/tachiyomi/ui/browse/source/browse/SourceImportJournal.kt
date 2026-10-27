package eu.kanade.tachiyomi.ui.browse.source.browse

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.channels.OverlappingFileLockException
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32

internal data class SourceImportConfig(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: Long,
    val sourceName: String,
    val listing: String,
    val query: String = "",
    val filters: String = "[]",
    val slowMode: Boolean = false,
    val automatic: Boolean = true,
    val preloadPages: Int = 64,
    val categoryId: Long? = null,
    val recoveryDelayMillis: Long = 0,
    val sequential: Boolean = false,
    val importMode: String = "STREAM",
    val minimumPages: Int = 0,
)

internal data class SourceImportItem(val id: Long, val added: Boolean)

internal data class SourceImportPlan(val key: Int, val nextKey: Int?, val items: List<SourceImportItem>)

internal class SourceImportPageTracker(private val completedIds: Set<Long>) {
    private val observedIds = mutableSetOf<Long>()

    fun prepare(key: Int, nextKey: Int?, items: List<SourceImportItem>): SourceImportPlan? {
        val unique = items.distinctBy { it.id }
        if (unique.isEmpty() || unique.all { it.id in observedIds }) return null
        observedIds.addAll(unique.map { it.id })
        return SourceImportPlan(key, nextKey, unique.filterNot { it.id in completedIds })
    }
}

internal enum class SourceImportStatus { QUEUED, RUNNING, PAUSED, FAILED, FINISHED }

internal class SourceImportStore private constructor(val directory: File, var config: SourceImportConfig) {
    val pauseRequested get() = File(directory, "pause").exists()

    // Durable command: a button press may arrive while the worker is downloading
    // or restarting. The token only clears after its available pages are committed.
    fun requestSaveDownloaded() {
        atomicWrite(File(directory, "save-request"), UUID.randomUUID().toString().toByteArray())
    }

    val saveRequestToken: String?
        get() = File(directory, "save-request").takeIf(File::exists)?.readText()?.trim()?.ifEmpty { null }

    fun acknowledgeSaveDownloaded(token: String) {
        val request = File(directory, "save-request")
        if (request.exists() && request.readText().trim() == token) {
            check(request.delete()) { "Could not acknowledge save request" }
        }
    }

    fun pause() {
        FileOutputStream(File(directory, "pause")).use { it.fd.sync() }
    }

    fun resume() {
        check(!File(directory, "pause").exists() || File(directory, "pause").delete())
    }

    fun update(value: SourceImportConfig) {
        check(value.id == config.id && value.sourceId == config.sourceId)
        writeConfig(directory, value)
        config = value
    }

    fun cleanIfReplaced() {
        try {
            cleanReplaced()
        } catch (_: IOException) {
        }
    }

    private fun cleanReplaced() {
        val pointer = File(directory.parentFile, "current")
        if (!directory.exists() || !pointer.exists() || pointer.readText().trim() == config.id) return
        RandomAccessFile(File(directory, "lock"), "rw").use { file ->
            val lock = try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: return
            lock.use {
                listOf("config", "config.tmp", "pause", "journal", "save-request", "save-request.tmp").forEach {
                    File(directory, it).delete()
                }
                File(directory, "page-spool").deleteRecursively()
            }
        }
        File(directory, "lock").delete()
        directory.delete()
    }

    companion object {
        private fun sourceDirectory(root: File, sourceId: Long) = File(root, "source-import/$sourceId")

        fun current(root: File, sourceId: Long): SourceImportStore? {
            val sourceDirectory = sourceDirectory(root, sourceId)
            val pointer = File(sourceDirectory, "current")
            if (!pointer.exists()) return null
            val id = pointer.readText().trim()
            check(UUID.fromString(id).toString() == id) { "Invalid import checkpoint" }
            val directory = File(sourceDirectory, id)
            val properties = Properties().apply { File(directory, "config").inputStream().use(::load) }
            check(properties.getProperty("version") == "1") { "Unsupported import checkpoint" }
            val config = SourceImportConfig(
                id = id,
                sourceId = properties.getProperty("sourceId").toLong(),
                sourceName = properties.getProperty("sourceName"),
                listing = properties.getProperty("listing"),
                query = properties.getProperty("query", ""),
                filters = properties.getProperty("filters", "[]"),
                slowMode = properties.getProperty("slowMode").toBooleanStrict(),
                automatic = properties.getProperty("automatic").toBooleanStrict(),
                preloadPages = properties.getProperty("preloadPages").toInt().coerceIn(1, 64),
                categoryId = properties.getProperty("categoryId")?.toLong(),
                recoveryDelayMillis = properties.getProperty("recoveryDelayMillis", "0").toLong().coerceIn(0, 60_000),
                sequential = properties.getProperty("sequential", "false").toBooleanStrict(),
                importMode = properties.getProperty("importMode", "STREAM"),
                minimumPages = properties.getProperty("minimumPages", "0").toInt().coerceIn(0, 1000000),
            )
            check(config.sourceId == sourceId)
            return SourceImportStore(directory, config)
        }

        fun create(root: File, config: SourceImportConfig): SourceImportStore {
            check(UUID.fromString(config.id).toString() == config.id)
            val sourceDirectory = sourceDirectory(root, config.sourceId)
            val directory = File(sourceDirectory, config.id)
            check(directory.mkdirs()) { "Could not create import checkpoint" }
            writeConfig(directory, config)
            atomicWrite(File(sourceDirectory, "current"), config.id.toByteArray())
            return SourceImportStore(directory, config)
        }

        private fun writeConfig(directory: File, config: SourceImportConfig) {
            val properties = Properties().apply {
                setProperty("version", "1")
                setProperty("sourceId", config.sourceId.toString())
                setProperty("sourceName", config.sourceName)
                setProperty("listing", config.listing)
                setProperty("query", config.query)
                setProperty("filters", config.filters)
                setProperty("slowMode", config.slowMode.toString())
                setProperty("automatic", config.automatic.toString())
                setProperty("preloadPages", config.preloadPages.toString())
                config.categoryId?.let { setProperty("categoryId", it.toString()) }
                setProperty("recoveryDelayMillis", config.recoveryDelayMillis.toString())
                setProperty("sequential", config.sequential.toString())
                setProperty("importMode", config.importMode)
                setProperty("minimumPages", config.minimumPages.toString())
            }
            val bytes = ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
            atomicWrite(File(directory, "config"), bytes)
        }

        private fun atomicWrite(file: File, bytes: ByteArray) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal class SourceImportJournal(private val file: File, repair: Boolean = false) {
    @Volatile
    var pending: SourceImportPlan? = null
        private set
    @Volatile
    var pendingBatch: List<SourceImportPlan>? = null
        private set
    @Volatile
    var nextKey: Int? = 1
        private set
    @Volatile
    var added = 0
        private set
    @Volatile
    var existing = 0
        private set
    @Volatile
    var pages = 0
        private set
    @Volatile
    var status = SourceImportStatus.QUEUED
        private set
    @Volatile
    var error: String? = null
        private set
    val seenIds = mutableSetOf<Long>()

    init {
        if (file.exists()) {
            RandomAccessFile(file, if (repair) "rw" else "r").use { input ->
                var validBytes = 0L
                while (input.filePointer < input.length()) {
                    if (input.length() - input.filePointer < 8) break
                    val length = input.readInt()
                    check(length in 1..1_000_000) { "Invalid import checkpoint record" }
                    val checksum = input.readInt()
                    if (input.length() - input.filePointer < length) break
                    val bytes = ByteArray(length).also(input::readFully)
                    val valid = CRC32().apply { update(bytes) }.value.toInt() == checksum
                    if (!valid && input.filePointer == input.length()) break
                    check(valid) { "Damaged import checkpoint" }
                    apply(bytes)
                    validBytes = input.filePointer
                }
                if (repair && input.length() != validBytes) {
                    input.setLength(validBytes)
                    input.fd.sync()
                }
            }
        }
    }

    fun plan(value: SourceImportPlan) {
        check(pending == null && pendingBatch == null && value.key == nextKey)
        check(value.items.size <= 10_000 && value.items.map { it.id }.distinct().size == value.items.size)
        check(value.items.none { it.id in seenIds })
        append {
            writeByte(1)
            writeInt(value.key)
            writeBoolean(value.nextKey != null)
            value.nextKey?.let(::writeInt)
            writeInt(value.items.size)
            value.items.forEach { writeLong(it.id); writeBoolean(it.added) }
        }
    }

    fun commit() {
        check(pending != null)
        append { writeByte(2); writeInt(pending!!.key) }
    }

    // Record types 4 and 5 are compatible with existing single-page journal
    // records. One durable plan and one durable commit cover multiple pages.
    fun planBatch(plans: List<SourceImportPlan>) {
        check(pending == null && pendingBatch == null && plans.isNotEmpty() && plans.size <= 8)
        check(plans.first().key == nextKey)
        check(plans.zipWithNext().all { (a, b) -> a.nextKey == b.key })
        val ids = plans.flatMap { it.items.map(SourceImportItem::id) }
        check(ids.size <= 10_000 && ids.distinct().size == ids.size && ids.none { it in seenIds })
        append {
            writeByte(4)
            writeInt(plans.size)
            plans.forEach { value ->
                writeInt(value.key)
                writeBoolean(value.nextKey != null)
                value.nextKey?.let(::writeInt)
                writeInt(value.items.size)
                value.items.forEach { writeLong(it.id); writeBoolean(it.added) }
            }
        }
    }

    fun commitBatch() {
        val plans = checkNotNull(pendingBatch)
        append {
            writeByte(5)
            writeInt(plans.first().key)
            writeInt(plans.size)
        }
    }

    fun status(value: SourceImportStatus, message: String? = null) {
        append { writeByte(3); writeUTF(value.name); writeUTF(message.orEmpty().take(1_000)) }
    }

    private fun append(write: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
        val checksum = CRC32().apply { update(bytes) }.value.toInt()
        FileOutputStream(file, true).use { output ->
            val data = DataOutputStream(output)
            data.writeInt(bytes.size)
            data.writeInt(checksum)
            data.write(bytes)
            data.flush()
            output.fd.sync()
        }
        apply(bytes)
    }

    private fun apply(bytes: ByteArray) {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            when (input.readByte().toInt()) {
                1 -> {
                    val key = input.readInt()
                    val next = if (input.readBoolean()) input.readInt() else null
                    val count = input.readInt()
                    check(count in 0..10_000 && pending == null && pendingBatch == null && key == nextKey)
                    val items = List(count) { SourceImportItem(input.readLong(), input.readBoolean()) }
                    check(items.map { it.id }.distinct().size == count && items.none { it.id in seenIds })
                    pending = SourceImportPlan(key, next, items)
                }
                2 -> {
                    val plan = checkNotNull(pending)
                    check(input.readInt() == plan.key)
                    added += plan.items.count { it.added }
                    existing += plan.items.count { !it.added }
                    seenIds.addAll(plan.items.map { it.id })
                    nextKey = plan.nextKey
                    pending = null
                    pages++
                }
                3 -> {
                    status = SourceImportStatus.valueOf(input.readUTF())
                    error = input.readUTF().ifEmpty { null }
                }
                4 -> {
                    val count = input.readInt()
                    check(count in 1..8 && pending == null && pendingBatch == null)
                    val plans = List(count) {
                        val key = input.readInt()
                        val next = if (input.readBoolean()) input.readInt() else null
                        val size = input.readInt()
                        check(size in 0..10_000)
                        val items = List(size) { SourceImportItem(input.readLong(), input.readBoolean()) }
                        SourceImportPlan(key, next, items)
                    }
                    val ids = plans.flatMap { it.items.map(SourceImportItem::id) }
                    check(plans.first().key == nextKey)
                    check(plans.zipWithNext().all { (a, b) -> a.nextKey == b.key })
                    check(ids.size <= 10_000 && ids.distinct().size == ids.size && ids.none { it in seenIds })
                    pendingBatch = plans
                }
                5 -> {
                    val plans = checkNotNull(pendingBatch)
                    check(input.readInt() == plans.first().key && input.readInt() == plans.size)
                    val items = plans.flatMap(SourceImportPlan::items)
                    added += items.count { it.added }
                    existing += items.count { !it.added }
                    seenIds.addAll(items.map(SourceImportItem::id))
                    nextKey = plans.last().nextKey
                    pendingBatch = null
                    pages += plans.size
                }
                else -> error("Unsupported import checkpoint record")
            }
            check(input.available() == 0)
        }
    }
}
