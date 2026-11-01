package eu.kanade.tachiyomi.alldebrid

import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// ANK: Credentials never enter URLs, the shared logging client, or player headers.
enum class AllDebridFailure { KEY, ACCOUNT, BLOCKED, RATE_LIMIT, NETWORK, RESPONSE, PENDING, TORRENT, FILE, STORAGE }

class AllDebridException(val failure: AllDebridFailure) : IOException("AllDebrid: ${failure.name}")

interface AllDebridKeyStore {
    fun read(): String
    fun write(value: String)
}

data class AllDebridFile(val path: String, val size: Long, val link: String)
data class AllDebridTorrent(val id: Long, val hash: String, val name: String, val files: List<AllDebridFile>)

class AllDebridClient(
    private val keys: AllDebridKeyStore,
    private val client: OkHttpClient = privateClient(),
    private val pollDelay: Long = 5_000,
    private val waitTimeout: Long = 120_000,
) {
    private val transfers = linkedMapOf<String, Long>()
    private val transferLock = Mutex()
    private val requestLock = Mutex()
    private var nextRequestAt = 0L

    suspend fun validateKey(key: String) {
        val user = request("v4/user", key = key)["user"] as? JsonObject ?: fail(AllDebridFailure.RESPONSE)
        if (user["isPremium"]?.jsonPrimitive?.content != "true") fail(AllDebridFailure.ACCOUNT)
    }

    suspend fun clearSession() = transferLock.withLock { transfers.clear() }

    /** One transfer per input and account in this session; retries never silently use local P2P. */
    suspend fun torrent(input: String, file: ByteArray? = null): AllDebridTorrent = transferLock.withLock {
        val key = keys.read().takeIf { it.isNotBlank() } ?: fail(AllDebridFailure.KEY)
        val magnet = parseMagnet(input).first
        val identity = if (file == null) magnet else java.security.MessageDigest.getInstance("SHA-256")
            .digest(file).joinToString("") { "%02x".format(it) }
        // The account key is part of the cache key but never logged or persisted.
        val cacheKey = "$key:$identity"
        val id = transfers[cacheKey] ?: run {
            val body = if (file == null) {
                if (!magnet.startsWith("magnet:?", true)) fail(AllDebridFailure.TORRENT)
                FormBody.Builder().add("magnets[]", magnet).build()
            } else {
                if (file.isEmpty() || file.size > MAX_TORRENT_BYTES) fail(AllDebridFailure.FILE)
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("files[]", "upload.torrent", file.toRequestBody("application/x-bittorrent".toMediaType()))
                    .build()
            }
            val data = request(if (file == null) "v4/magnet/upload" else "v4/magnet/upload/file", body, key)
            val item = (data[if (file == null) "magnets" else "files"] as? JsonArray)
                ?.singleOrNull() as? JsonObject ?: fail(AllDebridFailure.RESPONSE)
            checkError(item)
            val newId = item["id"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: fail(AllDebridFailure.RESPONSE)
            if (transfers.size >= 100) transfers.remove(transfers.keys.first())
            transfers[cacheKey] = newId
            newId
        }
        withTimeoutOrNull(waitTimeout) {
            while (true) {
                val data = request("v4.1/magnet/status", form("id", id.toString()), key)
                // v4.1 returns an array, including for a single requested ID.
                val item = (data["magnets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.singleOrNull { it["id"]?.jsonPrimitive?.longOrNull == id } ?: fail(AllDebridFailure.RESPONSE)
                checkError(item)
                when (item["statusCode"]?.jsonPrimitive?.intOrNull) {
                    4 -> {
                        val fileData = request("v4/magnet/files", form("id[]", id.toString()), key)
                        val entry = (fileData["magnets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                            ?.singleOrNull { it["id"]?.jsonPrimitive?.longOrNull == id } ?: fail(AllDebridFailure.RESPONSE)
                        checkError(entry)
                        val files = flattenFiles(entry["files"] as? JsonArray ?: fail(AllDebridFailure.RESPONSE))
                        if (files.isEmpty()) fail(AllDebridFailure.FILE)
                        return@withTimeoutOrNull AllDebridTorrent(
                            id,
                            magnet.substringAfter("urn:btih:", "").substringBefore('&'),
                            item["filename"]?.jsonPrimitive?.content.orEmpty(),
                            files,
                        )
                    }
                    in 0..3 -> delay(pollDelay)
                    null -> fail(AllDebridFailure.RESPONSE)
                    else -> fail(AllDebridFailure.TORRENT)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } ?: fail(AllDebridFailure.PENDING)
    }

    suspend fun stream(torrent: AllDebridTorrent, index: Int): String {
        val file = selectFile(torrent.files, index)
        var data = request("v4/link/unlock", form("link", file.link))
        val delayed = data["delayed"]?.jsonPrimitive?.longOrNull
        if (delayed != null) {
            data = withTimeoutOrNull(waitTimeout) {
                var result: JsonObject
                do {
                    delay(pollDelay)
                    result = request("v4/link/delayed", form("id", delayed.toString()))
                    when (result["status"]?.jsonPrimitive?.intOrNull) {
                        1 -> Unit
                        2 -> return@withTimeoutOrNull result
                        else -> fail(AllDebridFailure.FILE)
                    }
                } while (true)
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            } ?: fail(AllDebridFailure.PENDING)
        }
        return safeLink(data["link"]?.jsonPrimitive?.content ?: fail(AllDebridFailure.RESPONSE))
    }

    private suspend fun request(
        endpoint: String,
        body: RequestBody? = null,
        key: String = keys.read(),
    ): JsonObject = requestLock.withLock {
        if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) fail(AllDebridFailure.KEY)
        val now = System.nanoTime() / 1_000_000
        if (nextRequestAt > now) delay(nextRequestAt - now)
        nextRequestAt = System.nanoTime() / 1_000_000 + 150
        try {
            val builder = Request.Builder().url("https://api.alldebrid.com/$endpoint")
                .header("Authorization", "Bearer $key")
            if (body != null) builder.post(body)
            client.newCall(builder.build()).await().use { response ->
                when {
                    response.code == 429 -> fail(AllDebridFailure.RATE_LIMIT)
                    response.code == 401 || response.code == 403 -> fail(AllDebridFailure.KEY)
                    !response.isSuccessful -> fail(AllDebridFailure.NETWORK)
                }
                val source = response.body.source()
                if (source.request(MAX_RESPONSE_BYTES + 1L)) fail(AllDebridFailure.RESPONSE)
                val root = Json.parseToJsonElement(source.readUtf8()) as? JsonObject ?: fail(AllDebridFailure.RESPONSE)
                checkError(root)
                if (root["status"]?.jsonPrimitive?.content != "success") fail(AllDebridFailure.RESPONSE)
                root["data"] as? JsonObject ?: fail(AllDebridFailure.RESPONSE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AllDebridException) {
            throw e
        } catch (_: Exception) {
            // Do not retain raw transport errors, JSON bodies, account details or signed URLs.
            fail(AllDebridFailure.NETWORK)
        }
    }

    companion object {
        const val MAX_TORRENT_BYTES = 4 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        fun privateClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).build()

        fun parseMagnet(input: String): Pair<String, Int> {
            if (!input.startsWith("magnet:?", true)) return input to 0
            val parts = input.substringAfter('?').split('&')
            val indices = parts.filter { it.substringBefore('=') == "index" }
            if (indices.size > 1) fail(AllDebridFailure.FILE)
            val index = indices.singleOrNull()?.substringAfter('=')?.toIntOrNull()
            if (indices.isNotEmpty() && (index == null || index < 0)) fail(AllDebridFailure.FILE)
            return "magnet:?${parts.filterNot { it.substringBefore('=') == "index" }.joinToString("&")}" to (index ?: 0)
        }

        fun selectFile(files: List<AllDebridFile>, index: Int): AllDebridFile {
            // Match TorrServer's 1-based explicit file IDs; zero selects the first video.
            val selected = if (index == 0) files.firstOrNull { isVideo(it.path) } else files.getOrNull(index - 1)
            return selected?.takeIf { isVideo(it.path) } ?: fail(AllDebridFailure.FILE)
        }

        fun flattenFiles(nodes: JsonArray, prefix: String = "", depth: Int = 0): List<AllDebridFile> {
            if (depth > 32) fail(AllDebridFailure.RESPONSE)
            return nodes.flatMap { node ->
                val item = node as? JsonObject ?: fail(AllDebridFailure.RESPONSE)
                val name = item["n"]?.jsonPrimitive?.content ?: fail(AllDebridFailure.RESPONSE)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                val children = item["e"] as? JsonArray
                if (children != null) flattenFiles(children, path, depth + 1) else {
                    listOf(AllDebridFile(path, item["s"]?.jsonPrimitive?.longOrNull ?: 0, safeLink(item["l"]?.jsonPrimitive?.content.orEmpty())))
                }
            }
        }

        private fun isVideo(path: String) = path.substringAfterLast('.').lowercase() in
            setOf("mkv", "mp4", "avi", "webm", "mov", "m4v", "ts", "m2ts", "wmv", "flv")

        private fun safeLink(value: String): String {
            val url = value.toHttpUrlOrNull() ?: fail(AllDebridFailure.RESPONSE)
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) fail(AllDebridFailure.RESPONSE)
            return value
        }

        private fun checkError(item: JsonObject) {
            val error = item["error"] as? JsonObject ?: return
            val failure = when (error["code"]?.jsonPrimitive?.content) {
                "AUTH_BAD_APIKEY", "AUTH_MISSING_APIKEY" -> AllDebridFailure.KEY
                "AUTH_BLOCKED", "NO_SERVER", "MAGNET_NO_SERVER" -> AllDebridFailure.BLOCKED
                "AUTH_USER_BANNED", "MAGNET_MUST_BE_PREMIUM", "MUST_BE_PREMIUM", "FREE_TRIAL_LIMIT_REACHED" -> AllDebridFailure.ACCOUNT
                "MAGNET_TOO_MANY_ACTIVE" -> AllDebridFailure.RATE_LIMIT
                else -> AllDebridFailure.TORRENT
            }
            fail(failure)
        }

        private fun form(name: String, value: String) = FormBody.Builder().add(name, value).build()
        private fun fail(failure: AllDebridFailure): Nothing = throw AllDebridException(failure)
    }
}
