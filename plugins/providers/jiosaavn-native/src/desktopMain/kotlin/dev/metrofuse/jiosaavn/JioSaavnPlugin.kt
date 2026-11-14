// Metrolist Project (C) 2026, GPL-3.0. See SOURCE.
package dev.metrofuse.jiosaavn

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HomePlugin
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginNetwork
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URLEncoder
import kotlin.random.Random

class JioSaavnPlugin :
    NativePlugin,
    HomePlugin {
    private var api: JioApi? = null

    override suspend fun getHome(request: HomeRequest) =
        JioHome.shelves(checkNotNull(api).call("webapi.getLaunchData", mapOf("hl" to request.language, "gl" to request.country)))

    override suspend fun start(context: PluginContext) {
        val api = JioApi(context.network, context.settings)
        this.api = api
        context.registerSearchProvider(JioSearch(api))
        context.registerCatalogProvider(JioBrowse(api))
        context.registerMetadataSource(JioMetadata(api))
        context.registerAudioSource(JioAudio(api))
    }

    override suspend fun stop() {
        api?.clear()
        api = null
    }
}

internal class JioApi(
    private val network: PluginNetwork,
    private val settings: PluginSettings,
) {
    private val routingIp = "103.57.226.${Random.nextInt(1, 255)}"
    private val cache = linkedMapOf<String, Pair<Long, JsonObject>>()

    fun enabled(key: String): Boolean = (settings.values.value[key] as? JsonPrimitive)?.booleanOrNull == true

    suspend fun call(
        call: String,
        parameters: Map<String, String>,
        regionalCatalog: Boolean = enabled("regionalCatalog"),
    ): JsonObject {
        val query = mapOf("__call" to call, "_format" to "json", "_marker" to "0", "api_version" to "4", "ctx" to "web6dot0") + parameters
        val url =
            "https://www.jiosaavn.com/api.php?" + query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val headers =
            mapOf("Accept" to "application/json", "Referer" to "https://www.jiosaavn.com/") +
                if (regionalCatalog) mapOf("X-Forwarded-For" to routingIp, "X-Real-IP" to routingIp) else emptyMap()
        val response = network.request(HttpRequest(url, headers = headers, maxBytes = 1048576, timeoutMs = 10000))
        check(response.status == 200) { "JioSaavn HTTP ${response.status}" }
        return Json.parseToJsonElement(response.body) as? JsonObject ?: error("JioSaavn response must be an object")
    }

    suspend fun song(identity: TrackIdentity): JsonObject? {
        val id = JioCatalog.id(identity) ?: return null
        val regionalCatalog = enabled("regionalCatalog")
        val key = "$regionalCatalog:$id"
        synchronized(cache) { cache[key]?.takeIf { it.first > System.currentTimeMillis() }?.let { return it.second } }
        val song =
            call("song.getDetails", mapOf("pids" to id), regionalCatalog)["songs"]
                .objects()
                .firstOrNull { it.text("id") == id && JioCatalog.song(it) != null } ?: return null
        synchronized(cache) {
            cache[key] = (System.currentTimeMillis() + 60000) to song
            while (cache.size > 80) cache.remove(cache.keys.first())
        }
        return song
    }

    fun clear() = synchronized(cache) { cache.clear() }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

internal class JioSearch(
    private val api: JioApi,
) : SearchProvider {
    override val id = "jiosaavn.search"

    override suspend fun search(request: SearchRequest): SearchResult =
        coroutineScope {
            if (request.query.isBlank()) return@coroutineScope SearchResult(emptyList())
            val parameters = mapOf("q" to request.query, "n" to request.limit.coerceIn(1, 30).toString(), "p" to "1")
            val responses =
                listOf("search.getResults", "search.getAlbumResults", "search.getArtistResults", "search.getPlaylistResults")
                    .map { call ->
                        async {
                            try {
                                Result.success(api.call(call, parameters))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Result.failure(e)
                            }
                        }
                    }.map { it.await() }
            if (responses.all { it.isFailure }) throw requireNotNull(responses.first().exceptionOrNull())
            val values = responses.map { it.getOrNull()?.get("results").objects() }
            SearchResult(
                tracks = values[0].mapNotNull(JioCatalog::song),
                albums = values[1].mapNotNull { JioCatalog.album(it, "") }.filter { it.id.isNotEmpty() },
                artists = values[2].mapNotNull { JioCatalog.artist(it, "") }.filter { it.id.isNotEmpty() },
                playlists = values[3].mapNotNull { JioCatalog.playlist(it, "") }.filter { it.id.isNotEmpty() },
            )
        }
}

internal class JioBrowse(
    private val api: JioApi,
) : CatalogProvider {
    override val id = "jiosaavn.catalog"

    override suspend fun album(id: String) = JioCatalog.album(api.call("content.getAlbumDetails", mapOf("albumid" to id)), id)

    override suspend fun artist(id: String) =
        JioCatalog.artist(
            api.call(
                "artist.getArtistPageDetails",
                mapOf(
                    "artistId" to id,
                    "n_song" to "25",
                    "n_album" to "0",
                ),
            ),
            id,
        )

    override suspend fun playlist(id: String) = JioCatalog.playlist(api.call("playlist.getDetails", mapOf("listid" to id)), id)
}

internal class JioMetadata(
    private val api: JioApi,
) : MetadataSource {
    override val id = "jiosaavn.metadata"

    override suspend fun resolve(request: MetadataRequest): MetadataContribution? {
        val track = JioCatalog.song(api.song(request.identity)) ?: return null
        val identity = request.identity.copy(identifiers = request.identity.identifiers + ("jiosaavn" to track.extras.getValue("jiosaavn")))
        val fields =
            mapOf(
                "title" to JsonPrimitive(track.title),
                "artist" to JsonPrimitive(track.artist),
                "album" to JsonPrimitive(track.album),
                "duration" to JsonPrimitive(track.duration),
                "artwork" to JsonPrimitive(track.artwork),
            )
        return MetadataContribution(identity, fields, confidence = 1.0)
    }
}

internal class JioAudio(
    private val api: JioApi,
) : AudioSource {
    override val id = "jiosaavn.audio"
    override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))

    override suspend fun resolve(request: AudioQuery): AudioResult {
        if (JioCatalog.id(request.identity) == null) return AudioResult(error = ProviderError("IDENTITY_REQUIRED"))
        return JioMedia.resolve(api.song(request.identity), request.quality, api.enabled("previewEnabled"))
    }
}
