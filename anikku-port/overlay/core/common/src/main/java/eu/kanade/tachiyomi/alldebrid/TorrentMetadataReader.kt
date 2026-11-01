package eu.kanade.tachiyomi.alldebrid

import java.security.MessageDigest

data class TorrentMetadataFile(val path: String, val index: Int, val size: Long)
data class TorrentMetadata(val name: String, val hash: String, val files: List<TorrentMetadataFile>)

// ANK: Read the file catalogue without submitting a cloud transfer or starting local torrenting.
object TorrentMetadataReader {
    fun read(bytes: ByteArray): TorrentMetadata {
        try {
            require(bytes.isNotEmpty() && bytes.size <= AllDebridClient.MAX_TORRENT_BYTES)
            return Reader(bytes).read()
        } catch (_: Exception) {
            throw AllDebridException(AllDebridFailure.FILE)
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        private var nodes = 0
        private var infoStart = -1
        private var infoEnd = -1

        fun read(): TorrentMetadata {
            val root = value(0) as? Map<*, *> ?: error("dictionary required")
            require(position == bytes.size && infoStart >= 0 && infoEnd > infoStart)
            val info = root["info"] as? Map<*, *> ?: error("info required")
            // BEP 3/hybrid torrents have a v1 pieces field; a v2-only hash is not a btih magnet.
            val pieces = info["pieces"] as? ByteArray ?: error("v1 pieces required")
            require(pieces.size % 20 == 0)
            val name = text(info["name.utf-8"] ?: info["name"])
            require(name.isNotBlank())
            val rawFiles = info["files"] as? List<*>
            val files = if (rawFiles == null) {
                listOf(TorrentMetadataFile(name, 1, length(info["length"])))
            } else {
                rawFiles.mapIndexed { index, item ->
                    val file = item as? Map<*, *> ?: error("file required")
                    val path = file["path.utf-8"] ?: file["path"]
                    val parts = (path as? List<*>)?.map(::text) ?: error("path required")
                    require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it })
                    TorrentMetadataFile((listOf(name) + parts).joinToString("/"), index + 1, length(file["length"]))
                }
            }
            require(files.isNotEmpty())
            files.fold(0L) { total, file -> Math.addExact(total, file.size) }
            // Hash the exact original bencoded info bytes, not a reconstructed dictionary.
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(infoStart, infoEnd))
                .joinToString("") { "%02x".format(it) }
            return TorrentMetadata(name, hash, files)
        }

        private fun value(depth: Int): Any {
            require(depth <= 64 && ++nodes <= 100_000 && position < bytes.size)
            return when (bytes[position].toInt().toChar()) {
                'i' -> {
                    position++
                    val start = position
                    while (position < bytes.size && bytes[position].toInt().toChar() != 'e') position++
                    require(position < bytes.size)
                    val integer = bytes.copyOfRange(start, position++).toString(Charsets.US_ASCII)
                    require(integer.matches(Regex("0|-?[1-9][0-9]*")))
                    integer.toLong()
                }
                'l' -> {
                    position++
                    val list = mutableListOf<Any>()
                    while (!atEnd()) list += value(depth + 1)
                    position++
                    list
                }
                'd' -> {
                    position++
                    val map = linkedMapOf<String, Any>()
                    while (!atEnd()) {
                        val key = text(byteString())
                        require(key !in map)
                        val start = position
                        map[key] = value(depth + 1)
                        if (depth == 0 && key == "info") { infoStart = start; infoEnd = position }
                    }
                    position++
                    map
                }
                in '0'..'9' -> byteString()
                else -> error("invalid bencode")
            }
        }

        private fun atEnd(): Boolean {
            require(position < bytes.size)
            return bytes[position].toInt().toChar() == 'e'
        }

        private fun byteString(): ByteArray {
            val start = position
            while (position < bytes.size && bytes[position].toInt().toChar() in '0'..'9') position++
            require(position > start && position < bytes.size && bytes[position++].toInt().toChar() == ':')
            val size = bytes.copyOfRange(start, position - 1).toString(Charsets.US_ASCII).toInt()
            require(size >= 0 && size <= bytes.size - position)
            return bytes.copyOfRange(position, position + size).also { position += size }
        }

        private fun text(value: Any?) = (value as? ByteArray)?.toString(Charsets.UTF_8) ?: error("string required")
        private fun length(value: Any?) = (value as? Long)?.takeIf { it >= 0 } ?: error("length required")
    }
}
