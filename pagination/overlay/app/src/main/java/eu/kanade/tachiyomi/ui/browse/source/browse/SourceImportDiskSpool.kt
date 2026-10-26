package eu.kanade.tachiyomi.ui.browse.source.browse

import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Ordered, crash-recoverable disk queue. The network worker writes a complete
 * page before it becomes visible. A page is only deleted after journal commit.
 */
internal class SourceImportDiskSpool(private val root: File) {
    init { check(root.isDirectory || root.mkdirs()) { "Cannot create import disk queue" } }

    data class Page(val key: Int, val next: Int?, val mangas: List<SManga>)

    private fun file(key: Int) = File(root, "page-$key.bin")

    fun contains(key: Int) = file(key).isFile

    fun read(key: Int): Page? {
        val file = file(key).takeIf { it.isFile } ?: return null
        ObjectInputStream(FileInputStream(file).buffered()).use { input ->
            check(input.readInt() == 0x53495031) { "Invalid cached catalog page" }
            val actualKey = input.readInt()
            check(actualKey == key)
            val next = input.readInt().takeUnless { it == Int.MIN_VALUE }
            val count = input.readInt()
            check(count in 0..1000) { "Invalid cached manga count" }
            val mangas = List(count) {
                SManga.create().apply {
                    url = input.readObject() as String
                    title = input.readObject() as String
                    thumbnail_url = input.readObject() as String?
                    artist = input.readObject() as String?
                    author = input.readObject() as String?
                    description = input.readObject() as String?
                    genre = input.readObject() as String?
                    status = input.readInt()
                    initialized = input.readBoolean()
                    update_strategy = UpdateStrategy.valueOf(input.readObject() as String)
                    memo = Json.parseToJsonElement(input.readObject() as String).jsonObject
                }
            }
            return Page(key, next, mangas)
        }
    }

    fun write(page: Page) {
        if (contains(page.key)) return
        check(root.usableSpace >= 128L * 1024 * 1024) {
            "Insufficient free space for import disk queue (128 MiB reserve)"
        }
        val tmp = File(root, "page-${page.key}.tmp")
        try {
            FileOutputStream(tmp).use { stream ->
                ObjectOutputStream(stream).use { out ->
                    out.writeInt(0x53495031)
                    out.writeInt(page.key)
                    out.writeInt(page.next ?: Int.MIN_VALUE)
                    out.writeInt(page.mangas.size)
                    page.mangas.forEach { manga ->
                        out.writeObject(manga.url)
                        out.writeObject(manga.originalTitle)
                        out.writeObject(manga.originalThumbnailUrl)
                        out.writeObject(manga.originalArtist)
                        out.writeObject(manga.originalAuthor)
                        out.writeObject(manga.originalDescription)
                        out.writeObject(manga.originalGenre)
                        out.writeInt(manga.originalStatus)
                        out.writeBoolean(manga.initialized)
                        out.writeObject(manga.update_strategy.name)
                        out.writeObject(manga.memo.toString())
                    }
                    out.flush()
                    stream.fd.sync()
                }
            }
            java.nio.file.Files.move(
                tmp.toPath(),
                file(page.key).toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally { tmp.delete() }
    }

    fun remove(keys: List<Int>) { keys.forEach { file(it).delete() } }

    fun cachedPageCount() = root.listFiles()?.count { it.name.startsWith("page-") && it.name.endsWith(".bin") } ?: 0

    fun firstUncachedKey(start: Int): Int? {
        var key: Int? = start
        val visited = mutableSetOf<Int>()
        while (key != null && visited.add(key)) {
            val page = read(key) ?: return key
            key = page.next
        }
        return key
    }
}
