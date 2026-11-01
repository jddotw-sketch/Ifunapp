package eu.kanade.tachiyomi.alldebrid

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NyaaTorrentMetadataTest {
    @Test
    fun openingNyaaTorrentReadsEpisodesWithoutApiKeyCloudTransferOrPolling() = runBlocking {
        val api = mockk<AllDebridClient>()
        val keys = mockk<AllDebridKeyStore>()
        val network = mockk<NetworkHelper>()
        val info = "d6:lengthi12e4:name8:test.mkv12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaae"
        var requests = 0
        every { network.client } returns OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("nyaa.si", chain.request().url.host)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body("d4:info${info}e".toResponseBody("application/x-bittorrent".toMediaType())).build()
        }.build()
        val resolver = AllDebridResolver(mockk<Context>(), api, keys, network)
        val metadata = resolver.metadata("https://nyaa.si/download/123.torrent")
        assertEquals("test.mkv", metadata.name)
        assertEquals(12, metadata.files.single().size)
        assertEquals(1, requests)
        verify(exactly = 0) { keys.read() }
        coVerify(exactly = 0) { api.torrent(any(), any()) }
    }
}
