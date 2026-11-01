package eu.kanade.tachiyomi.alldebrid

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

class AllDebridClientTest {
    private val secret = "test-credential-never-log"
    private val keys = object : AllDebridKeyStore {
        var value = secret
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
    private val requests = CopyOnWriteArrayList<okhttp3.Request>()
    private val responses = ArrayDeque<Pair<Int, String>>()

    private fun client(timeout: Long = 5_000, pause: Long = 1, onRequest: (okhttp3.Request) -> Unit = {}): AllDebridClient {
        val http = AllDebridClient.privateClient().newBuilder().addInterceptor(Interceptor { chain ->
            requests += chain.request()
            val next = synchronized(responses) { responses.removeFirst() }
            onRequest(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(next.first)
                .message("test").body(next.second.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return AllDebridClient(keys, http, pollDelay = pause, waitTimeout = timeout)
    }

    private fun reply(json: String, code: Int = 200) { responses.add(code to json) }
    private fun success(data: String) = reply("""{"status":"success","data":$data}""")
    private val upload = """{"magnets":[{"id":12}]}"""
    private val ready = """{"magnets":[{"id":12,"filename":"Test","statusCode":4}]}"""
    private val files = """{"magnets":[{"id":12,"files":[{"n":"Folder","e":[{"n":"readme.txt","s":2,"l":"https://alldebrid.com/f/text"},{"n":"Episode 01.mkv","s":10,"l":"https://alldebrid.com/f/video"}]}]}]}"""
    private val magnet = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789&index=2&dn=Test"

    @Test
    fun fullWorkflowUsesCurrentEndpointsAndKeepsCredentialsOutOfUrlsAndBodies() = runBlocking {
        success(upload)
        success("""{"magnets":[{"id":12,"statusCode":1}]}""")
        success(ready)
        success(files)
        success("""{"link":"https://cdn.example/video.mkv"}""")
        val api = client()
        val torrent = api.torrent(AllDebridClient.parseMagnet(magnet).first)
        assertEquals("https://cdn.example/video.mkv", api.stream(torrent, 2))
        assertEquals(listOf("/v4/magnet/upload", "/v4.1/magnet/status", "/v4.1/magnet/status", "/v4/magnet/files", "/v4/link/unlock"), requests.map { it.url.encodedPath })
        assertEquals("Folder/Episode 01.mkv", torrent.files[1].path)
        requests.forEach {
            assertEquals("Bearer $secret", it.header("Authorization"))
            assertFalse(it.url.toString().contains(secret))
            val body = Buffer().also { buffer -> it.body?.writeTo(buffer) }.readUtf8()
            assertFalse(body.contains(secret))
        }
        assertTrue(Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8().startsWith("magnets%5B%5D="))
    }

    @Test
    fun nestedFilesPreserveAllIndicesAndDefaultSelectsVideo() {
        val items = AllDebridClient.flattenFiles(Json.parseToJsonElement("""[{"n":"folder","e":[{"n":"readme.txt","s":2,"l":"https://alldebrid.com/f/a"},{"n":"video.mp4","s":8,"l":"https://alldebrid.com/f/b"}]}]""").jsonArray)
        assertEquals(items[1], AllDebridClient.selectFile(items, 0))
        assertEquals(items[1], AllDebridClient.selectFile(items, 2))
        assertThrows(AllDebridException::class.java) { AllDebridClient.selectFile(items, 1) }
        assertThrows(AllDebridException::class.java) { AllDebridClient.selectFile(items, 9) }
    }

    @Test
    fun parsesExplicitIndexWithoutEatingLaterParameters() {
        val (uri, index) = AllDebridClient.parseMagnet(magnet)
        assertEquals(2, index)
        assertTrue(uri.endsWith("&dn=Test"))
        assertFalse(uri.contains("index="))
        listOf("-1", "abc", "1&index=2").forEach {
            assertThrows(AllDebridException::class.java) { AllDebridClient.parseMagnet("magnet:?xt=urn:btih:abc&index=$it") }
        }
    }

    @Test
    fun handlesTopLevelAndPerItemErrorsWithoutExposingServerMessages() {
        for ((json, expected) in listOf(
            """{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"$secret"}}""" to AllDebridFailure.KEY,
            """{"status":"success","data":{"magnets":[{"error":{"code":"MAGNET_MUST_BE_PREMIUM","message":"$secret"}}]}}""" to AllDebridFailure.ACCOUNT,
        )) {
            reply(json)
            val error = assertThrows(AllDebridException::class.java) { runBlocking { client().torrent(magnet) } }
            assertEquals(expected, error.failure)
            assertFalse(error.toString().contains(secret))
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun rejectsRateLimitMalformedRedirectAndMissingKey() {
        for ((code, body, expected) in listOf(
            Triple(429, secret, AllDebridFailure.RATE_LIMIT),
            Triple(302, secret, AllDebridFailure.NETWORK),
            Triple(200, "not json $secret", AllDebridFailure.NETWORK),
            Triple(200, "{}", AllDebridFailure.RESPONSE),
        )) {
            reply(body, code)
            assertEquals(expected, assertThrows(AllDebridException::class.java) { runBlocking { client().torrent(magnet) } }.failure)
        }
        keys.value = ""
        val count = requests.size
        assertEquals(AllDebridFailure.KEY, assertThrows(AllDebridException::class.java) { runBlocking { client().torrent(magnet) } }.failure)
        assertEquals(count, requests.size)
    }

    @Test
    fun retryReusesSubmittedTransferAndTerminalStatusFails() = runBlocking {
        success(upload)
        success("""{"magnets":[{"id":12,"statusCode":15}]}""")
        val api = client()
        try { api.torrent(magnet) } catch (e: AllDebridException) { assertEquals(AllDebridFailure.TORRENT, e.failure) }
        success(ready)
        success(files)
        api.torrent(magnet)
        assertEquals(1, requests.count { it.url.encodedPath == "/v4/magnet/upload" })
    }

    @Test
    fun multipartTorrentAndDelayedUnlockAreSupported() = runBlocking {
        success("""{"files":[{"id":12}]}""")
        success(ready)
        success(files)
        success("""{"delayed":99}""")
        success("""{"status":1}""")
        success("""{"status":2,"link":"https://cdn.example/video"}""")
        val api = client()
        assertEquals("https://cdn.example/video", api.stream(api.torrent("local.torrent", "d4:test4:datae".toByteArray()), 0))
        assertEquals("/v4/magnet/upload/file", requests.first().url.encodedPath)
        assertTrue(requests.first().body!!.contentType().toString().startsWith("multipart/form-data"))
        assertEquals(2, requests.count { it.url.encodedPath == "/v4/link/delayed" })
    }

    @Test
    fun cancellationPropagatesAndDoesNotResubmitTransfer() = runBlocking {
        success(upload)
        success("""{"magnets":[{"id":12,"statusCode":1}]}""")
        val polling = CompletableDeferred<Unit>()
        val api = client(pause = 5_000, onRequest = {
            if (it.url.encodedPath == "/v4.1/magnet/status") polling.complete(Unit)
        })
        val job = launch { api.torrent(magnet) }
        polling.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        success(ready)
        success(files)
        api.torrent(magnet)
        assertEquals(1, requests.count { it.url.encodedPath == "/v4/magnet/upload" })
        assertTrue(requests.none { it.url.encodedPath.startsWith("/v4/magnet/delete") })
    }

    @Test
    fun validatesPremiumBeforeSaving() = runBlocking {
        success("""{"user":{"isPremium":true}}""")
        client().validateKey(secret)
        success("""{"user":{"isPremium":false}}""")
        assertEquals(AllDebridFailure.ACCOUNT, assertThrows(AllDebridException::class.java) { runBlocking { client().validateKey(secret) } }.failure)
        assertEquals("/v4/user", requests.first().url.encodedPath)
    }

    @Test
    fun concurrentLookupsReuseTheTransferAndChangingAccountsCannotUnlockIt() = runBlocking {
        success(upload)
        success(ready)
        success(files)
        success(ready)
        success(files)
        val api = client()
        val one = async { api.torrent(magnet) }
        val two = async { api.torrent(magnet.substringBefore("&index=") + "&dn=Different+title") }
        val torrent = one.await()
        assertEquals(torrent, two.await())
        assertEquals(1, requests.count { it.url.encodedPath == "/v4/magnet/upload" })
        keys.value = "different-account-key"
        val count = requests.size
        assertEquals(AllDebridFailure.KEY, assertThrows(AllDebridException::class.java) { runBlocking { api.stream(torrent, 0) } }.failure)
        assertEquals(count, requests.size)
    }

    @Test
    fun pendingTransferTimesOutWithoutDeletionOrLocalFallback() = runBlocking {
        success(upload)
        val api = client(timeout = 20)
        assertEquals(AllDebridFailure.PENDING, assertThrows(AllDebridException::class.java) { runBlocking { api.torrent(magnet) } }.failure)
        success(ready)
        success(files)
        // The same client has a short timeout, so cancellation can happen before status is requested.
        assertEquals(1, requests.count { it.url.encodedPath == "/v4/magnet/upload" })
        assertTrue(requests.none { it.url.host != "api.alldebrid.com" })
    }

    @Test
    fun privateHttpClientDoesNotLogCacheSendCookiesOrFollowRedirects() {
        val http = AllDebridClient.privateClient()
        assertTrue(http.interceptors.isEmpty())
        assertTrue(http.networkInterceptors.isEmpty())
        assertEquals(null, http.cache)
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
    }
}
