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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Collections
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

    @Volatile
    var activeSessionClientId: String = ""
        private set

    @Volatile
    private var cachedAuthToken: String = ""

    // SoundCloud's "front page" recommendation blocks (mixed-selections, system-playlists,
    // personalized-mixes) are NOT independently re-fetchable resources for a lot of entries -
    // there is no stable "give me this exact recommended mix by id" endpoint. Real unofficial
    // SoundCloud API clients never try to re-resolve these; they consume the big listing
    // response once and keep what they got. We do the same: remember the raw JSON object each
    // PlaylistItem was parsed from, keyed by the id we handed out for it, so tapping into it
    // later can use what we already fetched instead of firing a doomed network request.
    private val collectionCache: MutableMap<String, JSONObject> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, JSONObject>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean =
                    size > 300
            },
        )

    suspend fun fetchRecommendations(authToken: String): List<SongItem> = withContext(Dispatchers.IO) {
        val token = normalizeSoundCloudAuthInput(authToken).orEmpty().ifBlank { cachedAuthToken }
        if (token.isNotBlank()) cachedAuthToken = token
        if (token.isBlank()) return@withContext emptyList()

        runCatching {
            val primary = safeCollectionItems("me/track-recommendations", token, 40).trackItems()
            if (primary.isEmpty()) {
                val suggested = safeCollectionItems("me/suggestions/tracks", token, 40).trackItems()
                if (suggested.isEmpty()) {
                    safeCollectionItems("me/mixed-selections/recommends", token, 40).trackItems()
                } else suggested
            } else primary
        }.getOrDefault(emptyList())
    }

    suspend fun fetchRelatedTracks(trackId: String, authToken: String = ""): List<SongItem> = withContext(Dispatchers.IO) {
        val token = normalizeSoundCloudAuthInput(authToken).orEmpty().ifBlank { cachedAuthToken }
        val numericId = if (trackId.startsWith("http")) {
            runCatching {
                apiObject("resolve", token, mapOf("url" to trackId)).stringOrNull("id")
            }.getOrNull()
        } else {
            trackId.substringAfterLast(":").takeIf { it.all(Char::isDigit) }
        } ?: return@withContext emptyList()

        runCatching {
            safeCollectionItems("tracks/$numericId/related", token, 30).trackItems()
        }.getOrDefault(emptyList())
    }

    private suspend fun effectiveClientId(): String {
        val session = activeSessionClientId
        if (session.isNotBlank()) return session
        return try {
            SoundCloudAudioProvider.clientId()
        } catch (e: Exception) {
            ""
        }
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

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

    suspend fun load(authToken: String = "", sessionClientId: String = ""): Result<HomePage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty().ifBlank { cachedAuthToken }
                if (token.isNotBlank()) cachedAuthToken = token
                activeSessionClientId = sessionClientId
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
        sessionClientId: String = "",
    ): Result<ExternalPlaylistPage> {
        val collectionType = when {
            playlistId.contains(":album:") -> "album"
            playlistId.contains(":mix:") -> "mix"
            playlistId.contains(":playlist:") -> "playlist"
            else -> "playlist"
        }
        return loadCollection(playlistId, collectionType, authToken, sessionClientId)
    }

    suspend fun search(
        query: String,
        authToken: String = "",
    ): Result<SearchSummaryPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty().ifBlank { cachedAuthToken }
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
        sessionClientId: String = "",
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val token = normalizeSoundCloudAuthInput(authToken).orEmpty().ifBlank { cachedAuthToken }
                if (sessionClientId.isNotBlank()) activeSessionClientId = sessionClientId
                val collectionType = type.lowercase().takeIf { it in setOf("playlist", "album", "mix") } ?: "playlist"

                val cleanId = collectionId.removeMetroPrefix()

                // 0. If we already parsed this exact item off a listing (home feed, search,
                // mixed-selections, etc.), use that JSON directly. Many of these "recommended"
                // objects (mixes, made-for-you, personalized system-playlists) have no reliable
                // standalone re-fetch endpoint, so re-resolving over the network is what was
                // failing here - the data we already have is the only copy that's guaranteed to work.
                var collection: JSONObject? = collectionCache[collectionId] ?: collectionCache[cleanId]

                // 1. Strictly handle selections and discover/sets (which 404 on /resolve)
                val selectionUrn = when {
                    cleanId.startsWith("soundcloud:selections:") -> cleanId
                    cleanId.startsWith("selections:") -> "soundcloud:$cleanId"
                    cleanId.startsWith("discover:sets:") -> "soundcloud:selections:${cleanId.removePrefix("discover:sets:")}"
                    cleanId.startsWith("discover/sets/") -> "soundcloud:selections:${cleanId.removePrefix("discover/sets/")}"
                    cleanId.startsWith("http") && cleanId.contains("discover/sets/") -> {
                        val path = cleanId.toHttpUrlOrNull()?.encodedPath.orEmpty()
                        if (path.startsWith("/discover/sets/")) {
                            "soundcloud:selections:${path.removePrefix("/discover/sets/").trimEnd('/')}"
                        } else null
                    }
                    else -> null
                }

                if (collection == null && selectionUrn != null) {
                    val selectionObj = runCatching { apiObject(path = "mixed-selections/$selectionUrn", authToken = token) }.getOrNull()
                    if (selectionObj != null) {
                        // Unwrap system_playlist from inside the selection
                        val itemsCollection = selectionObj.optJSONObject("items")?.optJSONArray("collection")
                        val systemPlaylist = itemsCollection?.optJSONObject(0)?.optJSONObject("system_playlist")
                        if (systemPlaylist != null) {
                            // The system_playlist returned here often omits the tracks array entirely.
                            // We must fetch it by its URN to get the actual tracks.
                            val systemUrn = systemPlaylist.optString("urn")
                            if (systemUrn.isNotBlank()) {
                                collection = runCatching { apiObject(path = "system-playlists/$systemUrn", authToken = token) }.getOrNull()
                            } else {
                                collection = systemPlaylist
                            }
                        } else {
                            // Fallback to the selection itself if no system_playlist found
                            collection = selectionObj
                        }
                    }
                }

                // 2. Handle direct system-playlists
                if (collection == null && (cleanId.startsWith("system-playlists:") || cleanId.startsWith("soundcloud:system-playlists:"))) {
                    val urn = if (cleanId.startsWith("soundcloud:")) cleanId else "soundcloud:$cleanId"
                    collection = runCatching { apiObject(path = "system-playlists/$urn", authToken = token) }.getOrNull()
                }

                // 3. Handle track-stations
                if (collection == null && (cleanId.startsWith("track-stations:") || cleanId.startsWith("soundcloud:track-stations:"))) {
                    val urn = if (cleanId.startsWith("soundcloud:")) cleanId else "soundcloud:$cleanId"
                    collection = runCatching { apiObject(path = "track-stations/$urn", authToken = token) }.getOrNull()
                }

                // 4. Handle plain numeric playlist/album IDs directly via the API.
                // This is the common case: search results and most "recommended" sections
                // (Curated by SoundCloud, Made for you via search, Albums for you, etc.) produce
                // PlaylistItem ids that are just the raw numeric SoundCloud id (e.g. "soundcloud:playlist:58273916"),
                // not a URL and not one of the special urn-based collections handled above.
                if (collection == null && cleanId.isNotBlank() && cleanId.all(Char::isDigit)) {
                    collection = runCatching { apiObject(path = "playlists/$cleanId", authToken = token) }.getOrNull()
                }

                // 5. Fallback to standard /resolve for URLs (permalinks like soundcloud.com/user/sets/name)
                if (collection == null) {
                    val urlToResolve = if (cleanId.startsWith("http")) cleanId else "https://soundcloud.com/$cleanId"
                    collection = resolveCollectionObject(urlToResolve, token)
                }

                collection ?: throw IllegalStateException("Could not resolve SoundCloud collection: $cleanId")

                val songs = collection.loadCollectionSongs(token)
                val playlistItem =
                    collection.toPlaylistItem(preferredType = collectionType)
                        ?: PlaylistItem(
                            id = "soundcloud:$collectionType:$cleanId",
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

    private suspend fun loadPersonalizedHome(authToken: String): HomePage = coroutineScope {
        val sections = mutableListOf<HomePage.Section>()

        val streamJob = async {
            runCatching { safeCollectionItems("me/stream", authToken, 60).trackItems() }.getOrDefault(emptyList())
        }

        val systemMixesJob = async {
            runCatching {
                val obj = apiObject("system-playlists", authToken, mapOf("limit" to "40"))
                val collection = obj.optJSONArray("collection") ?: JSONArray()
                collection.playlistItems()
            }.getOrDefault(emptyList())
        }

        val legacyMixesJob = async {
            runCatching { safeCollectionItems("me/personalized-mixes", authToken, 32).playlistItems() }.getOrDefault(emptyList())
        }

        val mixedJob = async { fetchAllMixedSelections(authToken) }

        val meJob = async {
            runCatching {
                val me = apiObject("me", authToken)
                val userId = me.longOrNull("id")?.toString() ?: return@runCatching null
                val likes = safeCollectionItems("users/$userId/track_likes", authToken, 32).trackItems()
                val history = safeCollectionItems("me/play-history/tracks", authToken, 32).trackItems()
                likes to history
            }.getOrNull()
        }

        val recsJob = async {
            fetchRecommendations(authToken)
        }

        val discoveryJob = async {
            runCatching { apiObject("discovery", authToken) }.getOrNull()
        }

        val madeForYouItems = (systemMixesJob.await() + legacyMixesJob.await())
            .distinctExternalItems()
        if (madeForYouItems.isNotEmpty()) {
            sections.addSection("Made for you", madeForYouItems)
        }

        val recItems = recsJob.await()
        if (recItems.isNotEmpty()) {
            sections.addSection("SoundCloud Recommends", recItems)
        }

        val streamItems = streamJob.await()
        if (streamItems.isNotEmpty()) {
            sections.addSection("Stream", streamItems)
        }

        mixedJob.await().forEach { selection ->
            parseDiscoverySelection(selection)?.let { (title, items) ->
                if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                    sections.addSection(title, items)
                }
            }
        }

        discoveryJob.await()?.let { discovery ->
            discovery.optJSONArray("collection")?.let { collection ->
                for (i in 0 until collection.length()) {
                    val selection = collection.optJSONObject(i) ?: continue
                    parseDiscoverySelection(selection)?.let { (title, items) ->
                        if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                            sections.addSection(title, items)
                        }
                    }
                }
            }
            parseDiscoverySelection(discovery)?.let { (title, items) ->
                if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                    sections.addSection(title, items)
                }
            }
        }

        meJob.await()?.let { (likes, history) ->
            if (likes.isNotEmpty() && sections.none { it.title.contains("like", ignoreCase = true) }) {
                sections.addSection("Your likes", likes)
            }
            if (history.isNotEmpty() && sections.none { it.title.contains("Recent", ignoreCase = true) }) {
                sections.addSection("Recently played", history)
            }
        }

        if (sections.isEmpty()) loadPublicHome()
        else HomePage(chips = null, sections = sections)
    }

    private fun parseDiscoverySelection(selection: JSONObject): Pair<String, List<YTItem>>? {
        val title = selection.stringOrNull("title") ?: selection.stringOrNull("description") ?: return null
        val itemsObj = selection.optJSONObject("items")
        val itemsCollection = if (itemsObj != null) {
            itemsObj.optJSONArray("collection") ?: itemsObj.optJSONArray("items") ?: JSONArray()
        } else {
            selection.optJSONArray("items") ?: JSONArray()
        }
        val items = (itemsCollection.trackItems() + itemsCollection.playlistItems()).distinctExternalItems()
        if (items.isEmpty()) return null
        return title to items
    }

    private suspend fun loadPublicHome(): HomePage = coroutineScope {
        val sections = mutableListOf<HomePage.Section>()

        val mixedJob = async { fetchAllMixedSelections("") }
        val chartsJob = async {
            runCatching {
                safeCollectionItems(
                    path = "charts",
                    authToken = "",
                    limit = 50,
                    extraParams = mapOf(
                        "kind" to "top",
                        "genre" to "soundcloud:genres:all-music",
                        "region" to "soundcloud:regions:all-countries"
                    )
                ).trackItems()
            }.getOrDefault(emptyList())
        }
        val trendingJob = async {
            runCatching {
                safeCollectionItems(
                    path = "charts",
                    authToken = "",
                    limit = 50,
                    extraParams = mapOf(
                        "kind" to "trending",
                        "genre" to "soundcloud:genres:all-music",
                        "region" to "soundcloud:regions:all-countries"
                    )
                ).trackItems()
            }.getOrDefault(emptyList())
        }

        val chartItems = chartsJob.await()
        if (chartItems.isNotEmpty()) {
            sections.addSection("Top 50: All Music", chartItems)
        }

        val trendingItems = trendingJob.await()
        if (trendingItems.isNotEmpty()) {
            sections.addSection("Trending: All Music", trendingItems)
        }

        mixedJob.await().forEach { selection ->
            parseDiscoverySelection(selection)?.let { (title, items) ->
                if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                    sections.addSection(title, items)
                }
            }
        }

        if (sections.size < 5) {
            publicPlaylistQueries.map { (title, query) ->
                async {
                    val items = searchPlaylists(query, "", PUBLIC_SECTION_LIMIT)
                    title to items
                }
            }.forEach { job ->
                val (title, items) = job.await()
                if (items.isNotEmpty() && sections.none { it.title.equals(title, ignoreCase = true) }) {
                    sections.addSection(title, items)
                }
            }
        }

        HomePage(chips = null, sections = sections)
    }

    private suspend fun fetchAllMixedSelections(authToken: String): List<JSONObject> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JSONObject>()
        var nextHref: String? = null

        repeat(3) {
            runCatching {
                if (nextHref == null) {
                    apiObject("mixed-selections", authToken, mapOf("limit" to "40", "linked_partitioning" to "1"))
                } else {
                    val page = apiCollectionPageFromUrl(nextHref!!, authToken)
                    JSONObject().apply {
                        put("collection", page.collection)
                        put("next_href", page.nextHref)
                    }
                }
            }.getOrNull()?.let { response ->
                response.optJSONArray("collection")?.let { collection ->
                    for (i in 0 until collection.length()) {
                        collection.optJSONObject(i)?.let { results.add(it) }
                    }
                }
                nextHref = response.stringOrNull("next_href")
            } ?: return@withContext results

            if (nextHref == null) return@withContext results
        }
        results
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

    private suspend fun searchTracks(
        query: String,
        authToken: String,
        limit: Int,
    ): List<SongItem> {
        if (query.isBlank()) return emptyList()
        val effectiveId = effectiveClientId()
        val metadataItems = try {
            SoundCloudAudioProvider
                .searchMetadata(query, limit = limit, clientId = effectiveId)
                .mapNotNull { it.toSongItem() }
                .distinctBy { it.id }
        } catch (throwable: Throwable) {
            Timber.tag("SoundCloudHome").w(throwable, "SoundCloud metadata search failed; using API search")
            null
        }

        if (!metadataItems.isNullOrEmpty()) return metadataItems

        return safeCollectionItems(
            path = "search/tracks",
            authToken = authToken,
            limit = limit,
            extraParams = mapOf("q" to query),
        ).trackItems()
    }

    private suspend fun searchPlaylists(
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

    private suspend fun safeCollectionItems(
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
            if (throwable !is IllegalStateException || !throwable.message.orEmpty().contains("404")) {
                Timber.tag("SoundCloudHome").w(throwable, "SoundCloud collection failed: $path")
            }
            JSONArray()
        }

    private suspend fun apiCollectionItems(
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

    private suspend fun apiCollectionPage(
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

    private suspend fun apiCollectionPageFromUrl(
        url: String,
        authToken: String,
    ): PagedCollection = withContext(Dispatchers.IO) {
        val clientId = activeSessionClientId.ifBlank { effectiveClientId() }
        val appVersion = SoundCloudAudioProvider.getAppVersion()
        val httpUrl = url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply {
                if (build().queryParameter("client_id").isNullOrBlank()) {
                    addQueryParameter("client_id", clientId)
                }
                if (build().queryParameter("app_locale").isNullOrBlank()) {
                    addQueryParameter("app_locale", APP_LOCALE)
                }
                if (appVersion != null && build().queryParameter("app_version").isNullOrBlank()) {
                    addQueryParameter("app_version", appVersion)
                }
            }
            ?.build()
            ?: return@withContext PagedCollection(JSONArray(), null)

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

        client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("SoundCloud ${response.code}: ${payload.take(180)}")
            }
            JSONObject(payload).toPagedCollection()
        }
    }

    private suspend fun apiObject(
        path: String,
        authToken: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val clientId = activeSessionClientId.ifBlank { effectiveClientId() }
        val appVersion = SoundCloudAudioProvider.getAppVersion()
        val urlBuilder = API_BASE.toHttpUrl()
            .newBuilder()
            .addPathSegments(path.trim('/'))
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("app_locale", APP_LOCALE)
            .apply {
                if (appVersion != null) {
                    addQueryParameter("app_version", appVersion)
                }
            }

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

        client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("SoundCloud ${response.code}: ${payload.take(180)}")
            }
            JSONObject(payload)
        }
    }

    private suspend fun apiTrackArray(
        ids: List<String>,
        authToken: String,
    ): JSONArray = withContext(Dispatchers.IO) {
        val clientId = activeSessionClientId.ifBlank { effectiveClientId() }
        val appVersion = SoundCloudAudioProvider.getAppVersion()
        val urlBuilder = API_BASE.toHttpUrl()
            .newBuilder()
            .addPathSegments("tracks")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("app_locale", APP_LOCALE)
            .apply {
                if (appVersion != null) {
                    addQueryParameter("app_version", appVersion)
                }
            }
            .addQueryParameter("ids", ids.joinToString(","))

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

        client.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("SoundCloud tracks ${response.code}: ${payload.take(180)}")
            }
            JSONArray(payload)
        }
    }

    private suspend fun JSONObject.loadCollectionSongs(authToken: String): List<SongItem> {
        val rawInitialTracks = optJSONArray("tracks")
        val initial =
            (
                    rawInitialTracks.trackItems() +
                            rawInitialTracks.hydratedTrackItems(authToken)
                    ).distinctBy { it.id }
        val trackCount = longOrNull("track_count")?.toInt() ?: initial.size
        if (initial.size >= trackCount || trackCount <= initial.size) return initial

        val playlistId = stringOrNull("id") ?: return initial
        val urn = stringOrNull("urn")
        val isSystem = optString("kind").equals("system-playlist", ignoreCase = true) ||
                urn?.contains("system-playlists") == true
        val isStation = urn?.contains("track-stations") == true
        val isSelection = urn?.contains("selections") == true

        if (isSelection) return initial

        val basePath = when {
            isStation -> "track-stations"
            isSystem -> "system-playlists"
            else -> "playlists"
        }

        val cleanId = if (isSystem || isStation) {
            urn ?: "soundcloud:$basePath:$playlistId"
        } else {
            if (playlistId.startsWith("soundcloud:")) playlistId.substringAfterLast(":") else playlistId
        }

        val songs = initial.toMutableList()
        var nextHref: String? = null
        var offset = rawInitialTracks?.length() ?: initial.size

        while (songs.size < trackCount && songs.size < PLAYLIST_SAFETY_LIMIT) {
            val pageItems =
                try {
                    val page =
                        if (nextHref == null) {
                            apiCollectionPage(
                                path = "$basePath/$cleanId/tracks",
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

    private suspend fun resolveCollectionObject(
        collectionId: String,
        authToken: String,
    ): JSONObject? {
        if (!collectionId.startsWith("http", ignoreCase = true)) return null

        val resolved = runCatching {
            apiObject(
                path = "resolve",
                authToken = authToken,
                params = mapOf("url" to collectionId),
            )
        }.getOrNull() ?: return null

        if (resolved.isPlaylistObject()) return resolved

        if (resolved.optString("kind").equals("selection", ignoreCase = true)) {
            return resolved
        }

        return null
    }

    private fun JSONArray?.trackItems(): List<SongItem> {
        if (this == null) return emptyList()
        val items = mutableListOf<JSONObject>()
        collectTracks(this, items)
        return items.mapNotNull { obj ->
            obj.toSongItem() ?: obj.optJSONObject("track")?.toSongItem()
        }.distinctBy { it.id }
    }

    private suspend fun JSONArray?.hydratedTrackItems(authToken: String): List<SongItem> {
        if (this == null) return emptyList()
        val trackIds = mutableListOf<String>()
        collectTrackIds(this, trackIds)
        if (trackIds.isEmpty()) return emptyList()

        return trackIds
            .distinct()
            .chunked(50)
            .flatMap { ids ->
                runCatching {
                    apiTrackArray(ids, authToken).trackItems()
                }.getOrElse { throwable ->
                    Timber.tag("SoundCloudHome").w(throwable, "SoundCloud track hydration failed")
                    emptyList()
                }
            }.distinctBy { it.id }
    }

    private fun JSONArray?.playlistItems(): List<PlaylistItem> {
        if (this == null) return emptyList()
        val items = mutableListOf<JSONObject>()
        collectPlaylists(this, items)
        return items.mapNotNull { obj ->
            val source = if (obj.isPlaylistObject()) obj else obj.optJSONObject("playlist")
            val item = source?.toPlaylistItem()
            if (item != null && source != null) collectionCache[item.id] = source
            item
        }.distinctBy { it.id }
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

    private fun collectTrackIds(
        value: Any?,
        output: MutableList<String>,
    ) {
        when (value) {
            is JSONObject -> {
                val looksLikeTrack =
                    value.optString("kind").equals("track", ignoreCase = true) ||
                            value.stringOrNull("permalink_url") != null
                if (looksLikeTrack) {
                    value.longOrNull("id")?.toString()?.let(output::add)
                    return
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    collectTrackIds(value.opt(keys.next()), output)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectTrackIds(value.opt(index), output)
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
        val title = stringOrNull("title") ?: return null
        val type = preferredType?.takeIf { it in setOf("playlist", "album", "mix") } ?: soundCloudCollectionType()
        // IMPORTANT: base this only on the real "kind" field from SoundCloud, never on the derived
        // display `type`. Regular curated/editorial playlists are tagged set_type == "mix" (which
        // makes soundCloudCollectionType() return "mix"), but they are still ordinary kind: "playlist"
        // objects fetchable via /playlists/{id}. Treating type == "mix" as isSystem fabricates a
        // non-existent "soundcloud:system-playlists:<numericId>" urn for them, which 404s on resolve.
        val isSystem = optString("kind").equals("system-playlist", ignoreCase = true)

        val urn = stringOrNull("urn")
        val numericId = stringOrNull("id")
        val id = when {
            isSystem && urn != null -> urn.removePrefix("soundcloud:")
            isSystem && numericId != null -> "system-playlists:$numericId"
            urn?.contains("selection") == true -> urn.removePrefix("soundcloud:")
            else -> numericId ?: return null
        }

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
        return when {
            setType == "album" || kind == "album" -> "album"
            kind == "system-playlist" || setType == "mix" -> "mix"
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

    private fun JSONObject.soundCloudArtworkUrl(): String? {
        val direct = (stringOrNull("artwork_url") ?: stringOrNull("calculated_artwork_url"))?.toLargeArtworkUrl()
        if (direct != null) return direct

        val visualsObj = optJSONObject("visuals")
        visualsObj?.stringOrNull("visual_url")?.let { return it.toLargeArtworkUrl() }
        val visualsArray = visualsObj?.optJSONArray("visuals")
        if (visualsArray != null && visualsArray.length() > 0) {
            visualsArray.optJSONObject(0)?.stringOrNull("visual_url")?.let { return it.toLargeArtworkUrl() }
        }

        optJSONObject("playlist")?.soundCloudArtworkUrl()?.let { return it }
        optJSONObject("track")?.soundCloudArtworkUrl()?.let { return it }

        val user = optJSONObject("user")
        val avatar = user?.stringOrNull("avatar_url")
        if (avatar != null && !avatar.contains("default_avatar")) {
            return avatar.toLargeArtworkUrl()
        }

        val userVisuals = user?.optJSONObject("visuals")?.optJSONArray("visuals")
        if (userVisuals != null && userVisuals.length() > 0) {
            userVisuals.optJSONObject(0)?.stringOrNull("visual_url")?.let { return it.toLargeArtworkUrl() }
        }

        optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val trackArt = tracks.optJSONObject(i)?.soundCloudArtworkUrl()
                if (trackArt != null) return trackArt
            }
        }

        optJSONObject("publisher_metadata")?.stringOrNull("artwork_url")?.let { return it.toLargeArtworkUrl() }

        return null
    }

    private fun JSONArray.firstTrackArtworkUrl(): String? {
        for (index in 0 until length()) {
            val track = optJSONObject(index) ?: continue
            track.soundCloudArtworkUrl()?.let { return it }
        }
        return null
    }

    private fun JSONArray.firstPlaylistObject(): JSONObject? {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (item.isPlaylistObject()) return item
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

    private fun String.toLargeArtworkUrl(): String {
        if (isEmpty() || contains("default_avatar")) return this
        if (contains("-t500x500.")) return this

        val urlWithoutQuery = substringBefore('?')
        val query = if (contains('?')) "?" + substringAfter('?') else ""

        val replaced = urlWithoutQuery.replace(Regex("-(large|t120x120|t67x67|t300x300|t240x240|t1024x1024|badge|tiny|small|t200x200|t50x50|t60x60|original|crop)\\."), "-t500x500.")
        if (replaced != urlWithoutQuery) return replaced + query

        if (urlWithoutQuery.contains("sndcdn.com") && !urlWithoutQuery.contains("-t")) {
            val dotIndex = urlWithoutQuery.lastIndexOf('.')
            if (dotIndex > urlWithoutQuery.lastIndexOf('/')) {
                return urlWithoutQuery.substring(0, dotIndex) + "-t500x500" + urlWithoutQuery.substring(dotIndex) + query
            }
        }
        return this
    }

    private fun String.removeMetroPrefix(): String {
        var current = this
        var previous: String
        do {
            previous = current
            current = current.removePrefix("soundcloud:playlist:")
                .removePrefix("soundcloud:album:")
                .removePrefix("soundcloud:mix:")
                .removePrefix("soundcloud:")
        } while (current != previous)
        return current
    }

    private fun String.displayName(): String =
        replaceFirstChar { it.uppercase() }
}