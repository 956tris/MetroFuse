package dev.metrofuse.youtube

import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream

internal class YouTubeGuestClient(
    private val http: HttpClient,
) {
    private val mutex = Mutex()
    private var cached: YouTubeClient? = null

    suspend fun get(): YouTubeClient =
        mutex.withLock {
            cached ?: run {
                val response = http.get("https://music.youtube.com/sw.js_data")
                check(response.status.isSuccess()) { "YouTube Music bootstrap HTTP ${response.status.value}" }
                val channel = response.bodyAsChannel()
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                try {
                    while (true) {
                        val count = channel.readAvailable(chunk, 0, minOf(chunk.size, MAX_BOOTSTRAP_BYTES + 1 - bytes.size()))
                        if (count < 0) break
                        bytes.write(chunk, 0, count)
                        check(bytes.size() <= MAX_BOOTSTRAP_BYTES) { "YouTube Music bootstrap exceeded its size limit" }
                    }
                } finally {
                    channel.cancel(null)
                }
                requireNotNull(parseGuestMusicClient(bytes.toByteArray().toString(Charsets.UTF_8))) {
                    "YouTube Music guest client configuration is unavailable"
                }.also { cached = it }
            }
        }
}

internal fun parseGuestMusicClient(source: String): YouTubeClient? {
    val start = source.indexOf('[')
    if (start < 0) return null
    val root =
        try {
            Json.parseToJsonElement(source.substring(start))
        } catch (_: Exception) {
            return null
        }
    val pending = ArrayDeque<JsonElement>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val value = pending.removeLast() as? JsonArray ?: continue
        val profile = (value.getOrNull(15) as? JsonPrimitive)?.intOrNull
        val version = (value.getOrNull(16) as? JsonPrimitive)?.contentOrNull
        if (profile == 67 && version != null && version.matches(Regex("1\\.\\d{8}\\.\\d{2}\\.\\d{2}"))) {
            return YouTubeClient.WEB_REMIX.copy(clientVersion = version)
        }
        pending.addAll(value)
    }
    return null
}

private const val MAX_BOOTSTRAP_BYTES = 2 * 1024 * 1024
