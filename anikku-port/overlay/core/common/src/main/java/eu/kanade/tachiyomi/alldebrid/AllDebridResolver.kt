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
    suspend fun torrent(input: String, headers: Headers? = null): AllDebridTorrent = withContext(Dispatchers.IO) {
        if (keys.read().isBlank()) throw AllDebridException(AllDebridFailure.KEY)
        if (input.startsWith("magnet:?", true)) return@withContext api.torrent(input)
        try {
            val bytes = if (input.startsWith("content://")) {
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
            api.torrent(input, bytes)
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
        return api.stream(torrent(input, headers), index)
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
