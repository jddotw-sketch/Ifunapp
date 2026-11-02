package eu.kanade.tachiyomi.alldebrid

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.InputStream
import java.io.ByteArrayOutputStream

// ANK: Used by playback, downloads, casting and the extension TorrentUtils API.
class AllDebridResolver(
    private val context: Context,
    private val api: AllDebridClient,
    private val keys: AllDebridKeyStore,
    private val network: NetworkHelper,
) {
    private val metadata = java.util.Collections.synchronizedMap(linkedMapOf<String, TorrentMetadata>())

    suspend fun metadata(input: String, headers: Headers? = null): TorrentMetadata = withContext(Dispatchers.IO) {
        val result = TorrentMetadataReader.read(torrentBytes(input, headers))
        synchronized(metadata) {
            if (metadata.size >= 64) metadata.remove(metadata.keys.first())
            metadata[result.hash] = result
        }
        result
    }

    suspend fun torrent(input: String, headers: Headers? = null): AllDebridTorrent = withContext(Dispatchers.IO) {
        if (keys.read().isBlank()) throw AllDebridException(AllDebridFailure.KEY)
        if (input.startsWith("magnet:?", true)) return@withContext api.torrent(input)
        try {
            api.torrent(input, torrentBytes(input, headers))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AllDebridException) {
            throw e
        } catch (_: Exception) {
            throw AllDebridException(AllDebridFailure.NETWORK)
        }
    }

    private suspend fun torrentBytes(input: String, headers: Headers?): ByteArray {
        try {
            return if (input.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(input))?.use(::readTorrent)
                    ?: throw AllDebridException(AllDebridFailure.FILE)
            } else {
                val url = input.toHttpUrlOrNull() ?: throw AllDebridException(AllDebridFailure.FILE)
                if (!url.encodedPath.endsWith(".torrent", true)) throw AllDebridException(AllDebridFailure.FILE)
                // This request uses source headers only; the AllDebrid credential stays on api.alldebrid.com.
                val request = Request.Builder().url(url).apply { headers?.let { this.headers(it) } }.build()
                network.client.newCall(request).await().use {
                    if (!it.isSuccessful) throw AllDebridException(AllDebridFailure.NETWORK)
                    readTorrent(it.body.byteStream())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AllDebridException) {
            throw e
        } catch (_: Exception) {
            throw AllDebridException(AllDebridFailure.NETWORK)
        }
    }

    suspend fun resolve(input: String, headers: Headers? = null): String {
        val index = AllDebridClient.parseMagnet(input).second
        val torrent = torrent(input, headers)
        val original = metadata[torrent.hash.lowercase()]
        val mapped = if (index > 0 && original != null) {
            val path = original.files.singleOrNull { it.index == index }?.path
                ?: throw AllDebridException(AllDebridFailure.FILE)
            val relative = path.removePrefix("${original.name}/")
            val matches = torrent.files.withIndex().filter { it.value.path == path || it.value.path == relative }
            if (matches.size != 1) throw AllDebridException(AllDebridFailure.FILE)
            matches.single().index + 1
        } else index
        return api.stream(torrent, mapped)
    }

    private fun readTorrent(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > AllDebridClient.MAX_TORRENT_BYTES) throw AllDebridException(AllDebridFailure.FILE)
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        if (bytes.isEmpty() || bytes.size > AllDebridClient.MAX_TORRENT_BYTES) throw AllDebridException(AllDebridFailure.FILE)
        return bytes
    }

    companion object {
        fun isTorrent(input: String): Boolean = input.startsWith("magnet:?", true) ||
            input.toHttpUrlOrNull()?.encodedPath?.endsWith(".torrent", true) == true
    }
}
