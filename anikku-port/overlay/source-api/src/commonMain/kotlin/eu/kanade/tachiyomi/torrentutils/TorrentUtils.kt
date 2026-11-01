package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import java.net.SocketTimeoutException
import eu.kanade.tachiyomi.alldebrid.AllDebridResolver
import eu.kanade.tachiyomi.torrentServer.TorrentServerPreferences
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object TorrentUtils {
    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        // ANK: Legacy extension API is synchronous; cloud IO remains on its calling IO thread.
        if (Injekt.get<TorrentServerPreferences>().useAllDebrid().get()) {
            if (!url.startsWith("magnet:?", true)) {
                val metadata = runBlocking { Injekt.get<AllDebridResolver>().metadata(url) }
                return TorrentInfo(
                    metadata.name,
                    metadata.files.map { TorrentFile(it.path, it.index, it.size, metadata.hash) },
                    metadata.hash,
                    metadata.files.sumOf { it.size },
                    emptyList(),
                )
            }
            val torrent = runBlocking { Injekt.get<AllDebridResolver>().torrent(url) }
            return TorrentInfo(
                torrent.name,
                torrent.files.mapIndexed { index, file -> TorrentFile(file.path, index + 1, file.size, torrent.hash) },
                torrent.hash,
                torrent.files.sumOf { it.size },
                emptyList(),
            )
        }
        @Suppress("SwallowedException")
        try {
            val torrent = TorrentServerApi.addTorrent(url, title, "", "", false)
            return TorrentInfo(
                torrent.title,
                torrent.file_stats?.map { file ->
                    TorrentFile(file.path, file.id ?: 0, file.length, torrent.hash!!, torrent.trackers ?: emptyList())
                } ?: emptyList(),
                torrent.hash!!,
                torrent.torrent_size!!,
                torrent.trackers ?: emptyList(),
            )
        } catch (e: SocketTimeoutException) {
            throw DeadTorrentException()
        }
    }
}
