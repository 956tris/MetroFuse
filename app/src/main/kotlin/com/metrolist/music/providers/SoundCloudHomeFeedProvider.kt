/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.utils.soundcloud.normalizeSoundCloudAuthInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import com.metrolist.innertube.models.Artist as TubeArtist

object SoundCloudHomeFeedProvider {
    private const val API_BASE = "https://api-v2.soundcloud.com"
    private const val APP_LOCALE = "en"
    private const val PUBLIC_SECTION_LIMIT = 24
    private const val PERSONAL_SECTION_LIMIT = 60
    private const val SEARCH_SECTION_LIMIT = 50
    private const val PLAYLIST_LIMIT = 100
    private const val PLAYLIST_SAFETY_LIMIT = 10_000

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

    private val feedQueries =
        listOf(
            "Fresh SoundCloud" to "new music",
            "SoundCloud rap" to "soundcloud rap",
            "Trending by genre" to "trending",
            "Electronic" to "electronic",
            "Remixes" to "remix",
        )

    private val publicPlaylistQueries =
        listOf(
            "Curated by SoundCloud" to "SoundCloud",
            "Made for you" to "Daily Drops Weekly Wave",
            "Discover with Stations" to "artist station",
            "Albums for you" to "album",
        )

    private data class PagedCollection(
        val collection: JSONArray,
        val nextHref: String?,
    )

    suspend fun load(authToken: String = ""): Result<HomePage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty()
                if (token.isNotBlank()) {
                    runCatching { loadPersonalizedHome(token) }
                        .getOrElse { throwable ->
                            Timber.tag("SoundCloudHome").w(throwable, "SoundCloud personalized home failed; using public feed")
                            loadPublicHome()
                        }
                } else {
                    loadPublicHome()
                }
            }
        }

    suspend fun loadPlaylist(
        playlistId: String,
        authToken: String = "",
    ): Result<ExternalPlaylistPage> =
        loadCollection(playlistId, "playlist", authToken)

    suspend fun search(
        query: String,
        authToken: String = "",
    ): Result<SearchSummaryPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty()
                val tracks = searchTracks(query, token, SEARCH_SECTION_LIMIT)
                val playlists = searchPlaylists(query, token, SEARCH_SECTION_LIMIT)
                val albums = playlists.filter { it.id.contains(":album:") }
                val mixes = playlists.filter { it.id.contains(":mix:") }
                val plainPlaylists =
                    playlists.filterNot {
                        it.id.contains(":album:") || it.id.contains(":mix:")
                    }

                SearchSummaryPage(
                    summaries =
                        buildList {
                            addSummary("Songs", tracks)
                            addSummary("Playlists", plainPlaylists)
                            addSummary("Albums", albums)
                            addSummary("Mixes", mixes)
                        },
                )
            }
        }

    suspend fun loadCollection(
        collectionId: String,
        type: String,
        authToken: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty()
                val collectionType = type.lowercase().takeIf { it in setOf("playlist", "album", "mix") } ?: "playlist"
                val collection =
                    apiObject(
                        path = "playlists/$collectionId",
                        authToken = token,
                    )
                val songs = collection.loadCollectionSongs(token)
                val playlistItem =
                    collection.toPlaylistItem(preferredType = collectionType)
                        ?: PlaylistItem(
                            id = "soundcloud:$collectionType:$collectionId",
                            title = collection.stringOrNull("title") ?: "SoundCloud ${collectionType.displayName()}",
                            author = collection.optJSONObject("user")?.stringOrNull("username")?.let { TubeArtist(name = it, id = null) },
                            songCountText = songs.size.takeIf { it > 0 }?.let { "$it tracks" },
                            thumbnail = collection.soundCloudArtworkUrl() ?: songs.firstOrNull()?.thumbnail,
                            playEndpoint = null,
                            shuffleEndpoint = null,
                            radioEndpoint = null,
                        )

                ExternalPlaylistPage(
                    playlist = playlistItem,
                    songs = songs,
                )
            }
        }

    private fun loadPersonalizedHome(authToken: String): HomePage {
        val me = apiObject("me", authToken)
        val userId = me.longOrNull("id")?.toString()
            ?: throw IllegalStateException("SoundCloud account id missing")
        val username = me.stringOrNull("username") ?: "you"

        val likes = safeCollectionItems("users/$userId/track_likes", authToken, PERSONAL_SECTION_LIMIT)
            .trackItems()
        val library = safeCollectionItems("me/library/all", authToken, PERSONAL_SECTION_LIMIT)
        val libraryTracks = library.trackItems()
        val recentlyPlayed = safeCollectionItems("me/play-history/tracks", authToken, PERSONAL_SECTION_LIMIT)
            .trackItems()
        val history = safeCollectionItems("me/play-history/tracks", authToken, PERSONAL_SECTION_LIMIT)
            .trackItems()
        val playlists = safeCollectionItems("users/$userId/playlists/liked_and_owned", authToken, PERSONAL_SECTION_LIMIT)
            .playlistItems()
        val libraryCollections = library.playlistItems()
        val mixes =
            (playlists + libraryCollections + searchPlaylists("Daily Drops Weekly Wave mix", authToken, PERSONAL_SECTION_LIMIT))
                .filter { it.id.contains(":mix:") || it.title.contains("mix", ignoreCase = true) }
                .distinctBy { it.id }
        val albums =
            (safeCollectionItems("users/$userId/albums", authToken, PERSONAL_SECTION_LIMIT).playlistItems() +
                libraryCollections.filter { it.id.contains(":album:") } +
                searchPlaylists("album", authToken, PUBLIC_SECTION_LIMIT).filter { it.id.contains(":album:") })
                .distinctBy { it.id }
        val curated = searchPlaylists("SoundCloud", authToken, PUBLIC_SECTION_LIMIT)
        val stations = searchPlaylists("artist station", authToken, PUBLIC_SECTION_LIMIT)

        val shortcutItems =
            buildList<YTItem> {
                addAll(mixes.take(2))
                addAll(playlists.take(2))
                addAll(likes.take(4))
                addAll(libraryTracks.take(4))
            }.distinctExternalItems().take(8)

        val sections =
            buildList {
                addSection(
                    title = "Your likes",
                    items = shortcutItems.ifEmpty { likes.take(8) },
                )
                addSection(
                    title = "More of what you like",
                    items = (libraryTracks + likes).distinctExternalItems().take(PERSONAL_SECTION_LIMIT),
                )
                addSection(
                    title = "Recently played",
                    items = recentlyPlayed.distinctExternalItems().take(PERSONAL_SECTION_LIMIT),
                )
                addSection(
                    title = "Mixed for $username",
                    items = mixes.distinctExternalItems().take(PERSONAL_SECTION_LIMIT),
                )
                addSection(
                    title = "Listening history",
                    items = history.distinctExternalItems().take(PERSONAL_SECTION_LIMIT),
                )
                addSection(
                    title = "Curated by SoundCloud",
                    items = curated.distinctExternalItems().take(PUBLIC_SECTION_LIMIT),
                )
                addSection(
                    title = "Discover with Stations",
                    items = stations.distinctExternalItems().take(PUBLIC_SECTION_LIMIT),
                )
                addSection(
                    title = "Albums for you",
                    items = albums.distinctExternalItems().take(PERSONAL_SECTION_LIMIT),
                )
            }

        return if (sections.isEmpty()) {
            loadPublicHome()
        } else {
            HomePage(chips = null, sections = sections)
        }
    }

    private fun loadPublicHome(): HomePage {
        val sections =
            buildList {
                publicPlaylistQueries.forEach { (title, query) ->
                    addSection(title, searchPlaylists(query, "", PUBLIC_SECTION_LIMIT))
                }
                feedQueries.forEach { (title, query) ->
                    addSection(title, searchTracks(query, "", PUBLIC_SECTION_LIMIT))
                }
            }

        return HomePage(chips = null, sections = sections)
    }

    private fun MutableList<HomePage.Section>.addSection(
        title: String,
        items: List<YTItem>,
    ) {
        if (items.isEmpty()) return
        add(
            HomePage.Section(
                title = title,
                label = "SoundCloud",
                thumbnail = items.firstOrNull()?.thumbnail(),
                endpoint = null,
                items = items,
            ),
        )
    }

    private fun MutableList<SearchSummary>.addSummary(
        title: String,
        items: List<YTItem>,
    ) {
        if (items.isEmpty()) return
        add(SearchSummary(title = title, items = items))
    }

    private fun searchTracks(
        query: String,
        authToken: String,
        limit: Int,
    ): List<SongItem> {
        if (query.isBlank()) return emptyList()
        SoundCloudAudioProvider
            .searchMetadata(query, limit = limit)
            .mapNotNull { it.toSongItem() }
            .distinctBy { it.id }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        return safeCollectionItems(
            path = "search/tracks",
            authToken = authToken,
            limit = limit,
            extraParams = mapOf("q" to query),
        ).trackItems()
    }

    private fun searchPlaylists(
        query: String,
        authToken: String,
        limit: Int,
    ): List<PlaylistItem> {
        if (query.isBlank()) return emptyList()
        return safeCollectionItems(
            path = "search/playlists",
            authToken = authToken,
            limit = limit,
            extraParams = mapOf("q" to query),
        ).playlistItems()
    }

    private fun safeCollectionItems(
        path: String,
        authToken: String,
        limit: Int,
        extraParams: Map<String, String> = emptyMap(),
        maxItems: Int = limit,
    ): JSONArray =
        runCatching {
            apiCollectionItems(
                path = path,
                authToken = authToken,
                limit = limit,
                extraParams = extraParams,
                maxItems = maxItems,
            )
        }.getOrElse { throwable ->
            Timber.tag("SoundCloudHome").w(throwable, "SoundCloud collection failed: $path")
            JSONArray()
        }

    private fun apiCollectionItems(
        path: String,
        authToken: String,
        limit: Int,
        extraParams: Map<String, String> = emptyMap(),
        maxItems: Int = limit,
    ): JSONArray {
        val merged = JSONArray()
        var nextHref: String? = null
        var seen = 0

        do {
            val page =
                if (nextHref == null) {
                    apiCollectionPage(
                        path = path,
                        authToken = authToken,
                        limit = limit,
                        extraParams = extraParams,
                    )
                } else {
                    apiCollectionPageFromUrl(nextHref, authToken)
                }

            val collection = page.collection
            if (collection.length() == 0) break

            for (index in 0 until collection.length()) {
                if (seen >= maxItems.coerceAtMost(PLAYLIST_SAFETY_LIMIT)) break
                merged.put(collection.opt(index))
                seen++
            }

            nextHref = page.nextHref
        } while (!nextHref.isNullOrBlank() && seen < maxItems.coerceAtMost(PLAYLIST_SAFETY_LIMIT))

        return merged
    }

    private fun apiCollectionPage(
        path: String,
        authToken: String,
        limit: Int,
        extraParams: Map<String, String> = emptyMap(),
    ): PagedCollection =
        apiObject(
            path = path,
            authToken = authToken,
            params =
                mapOf(
                    "limit" to limit.toString(),
                    "linked_partitioning" to "1",
                ) + extraParams,
        ).toPagedCollection()

    private fun apiCollectionPageFromUrl(
        url: String,
        authToken: String,
    ): PagedCollection {
        val httpUrl = url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply {
                if (build().queryParameter("client_id").isNullOrBlank()) {
                    addQueryParameter("client_id", SoundCloudAudioProvider.clientId())
                }
                if (build().queryParameter("app_locale").isNullOrBlank()) {
                    addQueryParameter("app_locale", APP_LOCALE)
                }
            }
            ?.build()
            ?: return PagedCollection(JSONArray(), null)

        val requestBuilder =
            Request
                .Builder()
                .url(httpUrl)
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", SoundCloudAudioProvider.BROWSER_USER_AGENT)
                .header("Referer", "https://soundcloud.com/")

        if (authToken.isNotBlank()) {
            requestBuilder.header("Authorization", "OAuth $authToken")
        }

        return client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("SoundCloud ${response.code}: ${payload.take(180)}")
            }
            JSONObject(payload).toPagedCollection()
        }
    }

    private fun apiObject(
        path: String,
        authToken: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        val urlBuilder = API_BASE.toHttpUrl()
            .newBuilder()
            .addPathSegments(path.trim('/'))
            .addQueryParameter("client_id", SoundCloudAudioProvider.clientId())
            .addQueryParameter("app_locale", APP_LOCALE)

        params.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }

        val requestBuilder =
            Request
                .Builder()
                .url(urlBuilder.build())
                .get()
                .header("Accept", "application/json")
                .header("User-Agent", SoundCloudAudioProvider.BROWSER_USER_AGENT)
                .header("Referer", "https://soundcloud.com/")

        if (authToken.isNotBlank()) {
            requestBuilder.header("Authorization", "OAuth $authToken")
        }

        return client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("SoundCloud ${response.code}: ${payload.take(180)}")
            }
            JSONObject(payload)
        }
    }

    private fun JSONObject.loadCollectionSongs(authToken: String): List<SongItem> {
        val rawInitialTracks = optJSONArray("tracks")
        val initial = rawInitialTracks.trackItems()
        val trackCount = longOrNull("track_count")?.toInt() ?: initial.size
        if (initial.size >= trackCount || trackCount <= initial.size) return initial

        val playlistId = stringOrNull("id") ?: return initial
        val songs = initial.toMutableList()
        var nextHref: String? = null
        var offset = rawInitialTracks?.length() ?: initial.size

        while (songs.size < trackCount && songs.size < PLAYLIST_SAFETY_LIMIT) {
            val pageItems =
                try {
                    val page =
                        if (nextHref == null) {
                            apiCollectionPage(
                                path = "playlists/$playlistId/tracks",
                                authToken = authToken,
                                limit = PLAYLIST_LIMIT,
                                extraParams = mapOf("offset" to offset.toString()),
                            )
                        } else {
                            apiCollectionPageFromUrl(nextHref, authToken)
                        }
                    nextHref = page.nextHref
                    page.collection.trackItems()
                } catch (throwable: Throwable) {
                    Timber.tag("SoundCloudHome").w(throwable, "SoundCloud collection pagination failed")
                    break
                }

            if (pageItems.isEmpty()) break
            songs += pageItems
            offset += pageItems.size
            if (nextHref.isNullOrBlank()) break
        }

        return songs.distinctBy { it.id }
    }

    private fun JSONArray?.trackItems(): List<SongItem> {
        if (this == null) return emptyList()
        val tracks = mutableListOf<JSONObject>()
        collectTracks(this, tracks)
        return tracks
            .mapNotNull { it.toSongItem() }
            .distinctBy { it.id }
    }

    private fun JSONArray?.playlistItems(): List<PlaylistItem> {
        if (this == null) return emptyList()
        val playlists = mutableListOf<JSONObject>()
        collectPlaylists(this, playlists)
        return playlists
            .mapNotNull { it.toPlaylistItem() }
            .distinctBy { it.id }
    }

    private fun collectTracks(
        value: Any?,
        output: MutableList<JSONObject>,
    ) {
        when (value) {
            is JSONObject -> {
                if (value.isPlayableTrack()) {
                    output += value
                    return
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    collectTracks(value.opt(keys.next()), output)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectTracks(value.opt(index), output)
                }
            }
        }
    }

    private fun collectPlaylists(
        value: Any?,
        output: MutableList<JSONObject>,
    ) {
        when (value) {
            is JSONObject -> {
                if (value.isPlaylistObject()) {
                    output += value
                    return
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    collectPlaylists(value.opt(keys.next()), output)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectPlaylists(value.opt(index), output)
                }
            }
        }
    }

    private fun JSONObject.isPlayableTrack(): Boolean {
        if (!optString("kind").equals("track", ignoreCase = true)) return false
        if (!optBoolean("streamable", true)) return false
        if (stringOrNull("permalink_url").isNullOrBlank()) return false
        val policy = stringOrNull("policy").orEmpty()
        return !policy.equals("BLOCK", ignoreCase = true) && !policy.equals("SNIP", ignoreCase = true)
    }

    private fun JSONObject.isPlaylistObject(): Boolean {
        val kind = stringOrNull("kind").orEmpty()
        return kind.equals("playlist", ignoreCase = true) ||
            kind.equals("system-playlist", ignoreCase = true) ||
            (optJSONArray("tracks") != null && stringOrNull("title") != null && stringOrNull("id") != null)
    }

    private fun JSONObject.toSongItem(): SongItem? {
        if (!isPlayableTrack()) return null
        val permalinkUrl = stringOrNull("permalink_url") ?: return null
        val title = stringOrNull("title") ?: return null
        val artist =
            optJSONObject("publisher_metadata")?.stringOrNull("artist")
                ?: optJSONObject("user")?.stringOrNull("username")
                ?: "SoundCloud"
        return SongItem(
            id = permalinkUrl,
            title = title,
            artists = listOf(Artist(name = artist, id = null)),
            duration = longOrNull("full_duration")?.takeIf { it > 0L }?.div(1000L)?.toInt()
                ?: longOrNull("duration")?.takeIf { it > 0L }?.div(1000L)?.toInt(),
            thumbnail = soundCloudArtworkUrl().orEmpty(),
            explicit = false,
        )
    }

    private fun JSONObject.toPlaylistItem(preferredType: String? = null): PlaylistItem? {
        if (!isPlaylistObject()) return null
        val id = stringOrNull("id") ?: return null
        val title = stringOrNull("title") ?: return null
        val type = preferredType?.takeIf { it in setOf("playlist", "album", "mix") } ?: soundCloudCollectionType()
        val userName = optJSONObject("user")?.stringOrNull("username")
        val trackCount = longOrNull("track_count")
            ?: optJSONArray("tracks")?.length()?.toLong()
        return PlaylistItem(
            id = "soundcloud:$type:$id",
            title = title,
            author = userName?.let { TubeArtist(name = it, id = null) },
            songCountText = trackCount?.takeIf { it > 0L }?.let { "$it tracks" },
            thumbnail = soundCloudArtworkUrl()
                ?: optJSONArray("tracks")?.firstTrackArtworkUrl(),
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.soundCloudCollectionType(): String {
        val setType = stringOrNull("set_type")?.lowercase()
        val kind = stringOrNull("kind")?.lowercase()
        val title = stringOrNull("title").orEmpty()
        return when {
            setType == "album" || kind == "album" -> "album"
            setType == "mix" || kind == "system-playlist" || title.contains("mix", ignoreCase = true) -> "mix"
            else -> "playlist"
        }
    }

    private fun SoundCloudAudioProvider.TrackMetadata.toSongItem(): SongItem? {
        val thumbnail = artworkUrl?.toLargeArtworkUrl() ?: return null
        return SongItem(
            id = permalinkUrl,
            title = title,
            artists = listOf(Artist(name = artist, id = null)),
            duration = durationMs?.takeIf { it > 0L }?.div(1000L)?.toInt(),
            thumbnail = thumbnail,
            explicit = false,
        )
    }

    private fun YTItem.thumbnail(): String? =
        when (this) {
            is SongItem -> thumbnail
            is PlaylistItem -> thumbnail
            else -> null
        }

    private fun List<YTItem>.distinctExternalItems(): List<YTItem> =
        distinctBy {
            when (it) {
                is SongItem -> it.id
                is PlaylistItem -> it.id
                else -> it.hashCode().toString()
            }
        }

    private fun JSONObject.soundCloudArtworkUrl(): String? =
        stringOrNull("artwork_url")?.toLargeArtworkUrl()
            ?: optJSONObject("user")?.stringOrNull("avatar_url")?.toLargeArtworkUrl()

    private fun JSONArray.firstTrackArtworkUrl(): String? {
        for (index in 0 until length()) {
            val track = optJSONObject(index) ?: continue
            track.soundCloudArtworkUrl()?.let { return it }
        }
        return null
    }

    private fun JSONObject.toPagedCollection(): PagedCollection =
        PagedCollection(
            collection = optJSONArray("collection") ?: JSONArray(),
            nextHref = stringOrNull("next_href"),
        )

    private fun JSONObject.stringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.longOrNull(name: String): Long? =
        when (val value = opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    private fun String.toLargeArtworkUrl(): String =
        replace("-large.", "-t500x500.")
            .replace("-t120x120.", "-t500x500.")

    private fun String.displayName(): String =
        replaceFirstChar { it.uppercase() }
}
