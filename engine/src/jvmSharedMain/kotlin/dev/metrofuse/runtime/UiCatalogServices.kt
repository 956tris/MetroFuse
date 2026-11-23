package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.UiLocalSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/** Optional app-owned selection bridge for shells. */
interface UiFrontendSelectionHost {
    suspend fun selectUiFrontend(owner: String)
}

interface UiLyricsHost {
    suspend fun resolveUiLyrics(track: Track): LyricsResult?
}

internal const val UI_FRONTENDS_SERVICE = "ui.frontends/v1"
internal const val UI_LIBRARY_SERVICE = "ui.library/v1"
internal const val UI_SETTINGS_SERVICE = "ui.settings/v1"
internal const val UI_PLUGINS_SERVICE = "ui.plugins/v1"

internal fun Runtime.registerUiCatalogServices() {
    fun register(
        name: String,
        handle: suspend (JsonObject) -> JsonObject,
    ) {
        registry.service(
            "builtin",
            name,
            object : JsonService {
                override suspend fun call(request: JsonObject) = handle(request)
            },
        )
    }

    register("ui.source/v1") {
        uiSourceMetadata(host.settings.get("music.frontend").orEmpty(), plugins.value, logins.states.value)
    }
    register(UI_LIBRARY_SERVICE) { request ->
        val owner = host.settings.get("music.frontend").orEmpty()
        if (owner.isEmpty()) {
            buildJsonObject { put("owner", owner) }
        } else {
            require((request["owner"] as? JsonPrimitive)?.contentOrNull == owner) { "Library source changed" }
            val continuation = (request["continuation"] as? JsonPrimitive)?.contentOrNull
            require((continuation?.length ?: 0) <= 2048)
            val locale = Locale.getDefault()
            val language = host.settings.get("content.language")?.takeUnless { it.isBlank() || it == "SYSTEM" } ?: locale.language
            val country = host.settings.get("content.country")?.takeUnless { it.isBlank() || it == "SYSTEM" } ?: locale.country
            val likedSongsOnly = (request["likedSongsOnly"] as? JsonPrimitive)?.contentOrNull == "true"
            val page = pipelines.library(owner, LibraryRequest(language, country, continuation, likedSongsOnly))
            if (host.settings.get("music.frontend").orEmpty() != owner) throw CancellationException("Library source changed")
            buildJsonObject {
                put("owner", owner)
                if (page != null) put("page", codec.encodeToJsonElement(LibraryPage.serializer(), page))
            }
        }
    }
    if (host is UiFrontendSelectionHost) {
        register(UI_FRONTENDS_SERVICE) { request ->
            val selection = request["select"]
            if (selection != null) {
                require(selection is JsonPrimitive && selection.isString) { "Expected a frontend ID" }
                val owner = selection.content
                require(owner.isEmpty() || pluginHomeSources(plugins.value).any { it.owner == owner }) { "Unavailable frontend" }
                host.selectUiFrontend(owner)
            }
            uiFrontendsSnapshot(host.settings.get("music.frontend").orEmpty(), pluginHomeSources(plugins.value))
        }
    }
    register("ui.search/v1") { request ->
        val query =
            request["query"]
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
                .trim()
                .take(250)
        val limit = (request["limit"]?.jsonPrimitive?.intOrNull ?: 40).coerceIn(1, 100)
        val owner = host.settings.get("music.frontend").orEmpty()
        val libraryOnly = (request["scope"] as? JsonPrimitive)?.contentOrNull == "library"
        val result =
            if (query.isBlank() || owner.isEmpty() || libraryOnly) {
                SearchResult(emptyList())
            } else {
                pipelines.search(SearchRequest(query, limit), allowedOwners = setOf(owner))
            }
        val local =
            if (query.isBlank()) {
                emptyList()
            } else {
                (host as? UiLocalSearch)?.searchLibrary(query, limit).orEmpty()
            }
        buildJsonObject {
            put(
                "results",
                codec.encodeToJsonElement(
                    SearchResult.serializer(),
                    result.copy(tracks = (local + result.tracks).distinctBy { it.id }.take(limit)),
                ),
            )
        }
    }
    register("ui.home/v1") {
        val owner = host.settings.get("music.frontend").orEmpty()
        val shelves =
            homes.pages.value[owner]
                ?.shelves
                .orEmpty()
                .take(24)
        buildJsonObject {
            put(
                "shelves",
                codec.encodeToJsonElement(
                    ListSerializer(HomeShelf.serializer()),
                    shelves.map { it.copy(items = it.items.take(20)) },
                ),
            )
        }
    }
    register("ui.collection/v1") { request ->
        val id = request.getValue("id").jsonPrimitive.content
        require(id.length in 1..2048)
        val item =
            when (request.getValue("kind").jsonPrimitive.content) {
                "album" -> {
                    pipelines
                        .album(
                            id,
                        )?.let { codec.encodeToJsonElement(CatalogAlbum.serializer(), it.copy(tracks = it.tracks.take(100))) }
                }

                "artist" -> {
                    pipelines.artist(id)?.let {
                        codec.encodeToJsonElement(
                            CatalogArtist.serializer(),
                            it.copy(tracks = it.tracks.take(100), albums = it.albums.take(32)),
                        )
                    }
                }

                "playlist" -> {
                    pipelines
                        .playlist(
                            id,
                        )?.let { codec.encodeToJsonElement(CatalogPlaylist.serializer(), it.copy(tracks = it.tracks.take(100))) }
                }

                else -> {
                    null
                }
            }
        buildJsonObject { if (item != null) put("item", item) }
    }
    register("ui.lyrics/v1") {
        val track = player.state.value.track
        val owner = track.id.split(':', limit = 3).takeIf { it.size == 3 && it[0] == "metrofuse" }
        val identity =
            if (owner == null) {
                TrackIdentity(track.id, track.extras)
            } else {
                TrackIdentity(owner[2], track.extras + ("pluginOwner" to owner[1]))
            }
        val result =
            if (track.id.isBlank()) {
                null
            } else {
                val hostLyrics = try {
                    withTimeoutOrNull(18_000) { (host as? UiLyricsHost)?.resolveUiLyrics(track) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (hostLyrics.hasWordTimings()) {
                    hostLyrics
                } else {
                    val pluginLyrics = withTimeoutOrNull(10_000) {
                        pipelines.lyrics(LyricsRequest(identity, track.title, track.artist, track.duration))
                    }
                    pluginLyrics.takeIf { hostLyrics == null || it.hasWordTimings() } ?: hostLyrics
                }
            }
        buildJsonObject {
            put("trackId", track.id)
            if (result != null) put("lyrics", codec.encodeToJsonElement(LyricsResult.serializer(), result))
        }
    }
}

private fun LyricsResult?.hasWordTimings(): Boolean =
    this?.lines?.any { line -> line.words.any { it.text.isNotBlank() && it.endMs > it.startMs } } == true

internal fun uiFrontendsSnapshot(
    selected: String,
    sources: List<PluginHomeSource>,
): JsonObject =
    buildJsonObject {
        put("selected", selected.takeIf { id -> sources.any { it.owner == id } }.orEmpty())
        put(
            "sources",
            buildJsonArray {
                sources.forEach { source ->
                    add(
                        buildJsonObject {
                            put("id", source.owner)
                            put("title", source.title)
                        },
                    )
                }
            },
        )
    }

internal fun uiSourceMetadata(
    selected: String,
    plugins: List<PluginStatus>,
    accounts: Map<String, PluginAccountState>,
): JsonObject {
    val plugin = plugins.firstOrNull { it.manifest.id == selected && it.phase == LifecyclePhase.ACTIVE }
    val account = plugin?.let { accounts[it.manifest.id]?.account }?.takeIf { it.loggedIn }
    return buildJsonObject {
        put("id", plugin?.manifest?.id.orEmpty())
        put("offline", plugin == null)
        put("loggedIn", account != null)
        plugin?.manifest?.let { manifest ->
            put("title", manifest.home?.title?.takeIf { it.isNotBlank() } ?: manifest.name)
        }
        account?.accountName?.takeIf { it.isNotBlank() }?.let { put("accountName", it) }
    }
}
