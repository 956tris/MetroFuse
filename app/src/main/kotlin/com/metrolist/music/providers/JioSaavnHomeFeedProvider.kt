/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.jiosaavn.JioSaavnAudioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

object JioSaavnHomeFeedProvider {
    private const val TAG = "JioSaavnHomeFeedProvider"
    private const val SECTION_LIMIT = 20

    suspend fun load(language: String = "hindi"): Result<HomePage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val root = JioSaavnAudioProvider.launchData(language)
                    ?: error("JioSaavn home is unavailable")
                val sections = mutableListOf<HomePage.Section>()
                coroutineScope {
                    val trending = async { trendingItems(root) }
                    val playlists = async { playlistItems(root, "top_playlists") }
                    val albums = async { albumItems(root, "new_albums") }
                    val charts = async { playlistItems(root, "charts") }
                    trending.await().takeIf { it.isNotEmpty() }?.let {
                        sections.addSection("Trending Now", it)
                    }
                    charts.await().takeIf { it.isNotEmpty() }?.let {
                        sections.addSection("Top Charts", it)
                    }
                    playlists.await().takeIf { it.isNotEmpty() }?.let {
                        sections.addSection("Top Playlists", it)
                    }
                    albums.await().takeIf { it.isNotEmpty() }?.let {
                        sections.addSection("New Albums", it)
                    }
                }
                if (sections.isEmpty()) error("JioSaavn home returned no sections")
                HomePage(chips = null, sections = sections)
            }
        }

    suspend fun search(
        query: String,
        limit: Int = 20,
    ): Result<SearchSummaryPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                if (query.isBlank()) return@withContext SearchSummaryPage(emptyList())
                val summaries = mutableListOf<SearchSummary>()
                coroutineScope {
                    val songs = async {
                        JioSaavnAudioProvider.searchCandidates(query, emptyList(), limit)
                            .mapNotNull { it.toSongItem() }
                    }
                    val playlists = async { searchPlaylists(query, limit) }
                    val albums = async { searchAlbums(query, limit) }
                    val artists = async { searchArtists(query, limit) }
                    songs.await().takeIf { it.isNotEmpty() }?.let {
                        summaries.addSummary("Songs", it)
                    }
                    albums.await().takeIf { it.isNotEmpty() }?.let {
                        summaries.addSummary("Albums", it)
                    }
                    artists.await().takeIf { it.isNotEmpty() }?.let {
                        summaries.addSummary("Artists", it)
                    }
                    playlists.await().takeIf { it.isNotEmpty() }?.let {
                        summaries.addSummary("Playlists", it)
                    }
                }
                SearchSummaryPage(summaries)
            }
        }

    suspend fun loadCollection(
        externalId: String,
        type: String,
    ): Result<ExternalPlaylistPage> =
        runCatching {
            withContext(Dispatchers.IO) {
                val details = when (type.lowercase()) {
                    "playlist" -> JioSaavnAudioProvider.playlistDetails(externalId)
                    "album" -> JioSaavnAudioProvider.albumDetails(externalId)
                    "artist" -> JioSaavnAudioProvider.artistTopSongs(externalId)
                    else -> null
                } ?: error("JioSaavn $type $externalId not found")
                val songs = details.songs.mapNotNull { it.toSongItem() }
                if (songs.isEmpty()) error("JioSaavn $type ${details.title} has no playable songs")
                ExternalPlaylistPage(
                    playlist = PlaylistItem(
                        id = "jiosaavn:$type:$externalId",
                        title = details.title,
                        author = details.subtitle?.takeIf { it.isNotBlank() }?.let {
                            Artist(name = it, id = null)
                        },
                        songCountText = "${songs.size} songs",
                        thumbnail = details.songs.firstOrNull()?.coverUrl,
                        playEndpoint = null,
                        shuffleEndpoint = null,
                        radioEndpoint = null,
                    ),
                    songs = songs,
                    continuation = null,
                )
            }
        }

    private fun trendingItems(root: JSONObject): List<YTItem> {
        val items = mutableListOf<YTItem>()
        root.optJSONArray("new_trending")?.forEachObject { obj ->
            when (obj.optString("type")) {
                "song" -> JioSaavnAudioProvider.parseSongDetail(obj)?.toSongItem()?.let { items += it }
                "album" -> obj.toAlbumItem()?.let { items += it }
                "playlist" -> obj.toPlaylistItem()?.let { items += it }
            }
        }
        return items.take(SECTION_LIMIT)
    }

    private fun playlistItems(
        root: JSONObject,
        key: String,
    ): List<PlaylistItem> =
        root.optJSONArray(key)?.mapObjects { it.toPlaylistItem() }.orEmpty().take(SECTION_LIMIT)

    private fun albumItems(
        root: JSONObject,
        key: String,
    ): List<YTItem> {
        val items = mutableListOf<YTItem>()
        root.optJSONArray(key)?.forEachObject { obj ->
            when (obj.optString("type")) {
                "album" -> obj.toAlbumItem()?.let { items += it }
                "song" -> JioSaavnAudioProvider.parseSongDetail(obj)?.toSongItem()?.let { items += it }
                else -> obj.toPlaylistItem()?.let { items += it }
            }
        }
        return items.take(SECTION_LIMIT)
    }

    private fun searchPlaylists(
        query: String,
        limit: Int,
    ): List<PlaylistItem> =
        runCatching {
            JioSaavnAudioProvider.searchRaw("search.getPlaylistResults", query, limit)
                .optJSONArray("results")?.mapObjects { it.toPlaylistItem() }
                .orEmpty()
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn playlist search failed")
        }.getOrDefault(emptyList())

    private fun searchAlbums(query: String, limit: Int): List<AlbumItem> =
        runCatching {
            JioSaavnAudioProvider.searchRaw("search.getAlbumResults", query, limit)
                .optJSONArray("results")?.mapObjects { it.toAlbumItem() }
                .orEmpty()
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn album search failed")
        }.getOrDefault(emptyList())

    private fun searchArtists(query: String, limit: Int): List<ArtistItem> =
        runCatching {
            JioSaavnAudioProvider.searchRaw("search.getArtistResults", query, limit)
                .optJSONArray("results")?.mapObjects { it.toArtistItem() }
                .orEmpty()
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn artist search failed")
        }.getOrDefault(emptyList())

    private fun JioSaavnAudioProvider.TrackCandidate.toSongItem(): SongItem? {
        if (trackId.isBlank() || title.isBlank()) return null
        val artistNames = artist.split(',').map { it.trim() }.filter { it.isNotBlank() }
        return SongItem(
            id = JioSaavnAudioProvider.TRACK_ID_PREFIX + trackId,
            title = title,
            artists = artistNames.map { Artist(name = it, id = null) },
            album = album?.takeIf { it.isNotBlank() }?.let { Album(name = it, id = "") },
            duration = durationMs?.takeIf { it > 0 }?.div(1000L)?.toInt(),
            thumbnail = coverUrl.orEmpty(),
            explicit = false,
        )
    }

    private fun JioSaavnAudioProvider.SongDetails.toSongItem(): SongItem? {
        if (id.isBlank() || title.isBlank()) return null
        return SongItem(
            id = JioSaavnAudioProvider.TRACK_ID_PREFIX + id,
            title = title,
            artists = artists.map { Artist(name = it, id = null) },
            album = album?.takeIf { it.isNotBlank() }?.let { Album(name = it, id = albumId.orEmpty()) },
            duration = durationMs?.takeIf { it > 0 }?.div(1000L)?.toInt(),
            thumbnail = coverUrl.orEmpty(),
            explicit = explicit,
        )
    }

    private fun JSONObject.toPlaylistItem(): PlaylistItem? {
        val id = optString("id").takeIf { it.isNotBlank() }
            ?: optString("listid").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() }
            ?: optString("listname").takeIf { it.isNotBlank() } ?: return null
        return PlaylistItem(
            id = JioSaavnAudioProvider.PLAYLIST_ID_PREFIX + id,
            title = title,
            author = optString("subtitle").takeIf { it.isNotBlank() }?.let {
                Artist(name = it, id = null)
            },
            songCountText = optString("song_count").takeIf { it.isNotBlank() }
                ?: optString("list_count").takeIf { it.isNotBlank() }?.let { "$it songs" },
            thumbnail = optString("image").takeIf { it.isNotBlank() },
            playEndpoint = null,
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun JSONObject.toAlbumItem(): AlbumItem? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() } ?: return null
        return AlbumItem(
            browseId = JioSaavnAudioProvider.ALBUM_ID_PREFIX + id,
            playlistId = JioSaavnAudioProvider.ALBUM_ID_PREFIX + id,
            title = title,
            artists = null,
            year = optString("year").toIntOrNull(),
            thumbnail = optString("image").takeIf { it.isNotBlank() }.orEmpty(),
            explicit = optString("explicit_content") == "1",
        )
    }

    private fun JSONObject.toArtistItem(): ArtistItem? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = optString("title").takeIf { it.isNotBlank() }
            ?: optString("name").takeIf { it.isNotBlank() } ?: return null
        return ArtistItem(
            id = JioSaavnAudioProvider.ARTIST_ID_PREFIX + id,
            title = title,
            thumbnail = optString("image").takeIf { it.isNotBlank() },
            shuffleEndpoint = null,
            radioEndpoint = null,
        )
    }

    private fun MutableList<HomePage.Section>.addSection(
        title: String,
        items: List<YTItem>,
    ) {
        if (items.isEmpty()) return
        add(
            HomePage.Section(
                title = title,
                label = "JioSaavn",
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

    private fun YTItem.thumbnail(): String? =
        when (this) {
            is SongItem -> thumbnail
            is PlaylistItem -> thumbnail
            is AlbumItem -> thumbnail
            is ArtistItem -> thumbnail
            else -> null
        }

    private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(action)
        }
    }

    private fun <T> JSONArray.mapObjects(action: (JSONObject) -> T?): List<T> {
        val list = mutableListOf<T>()
        for (index in 0 until length()) {
            optJSONObject(index)?.let { action(it)?.let { item -> list += item } }
        }
        return list
    }
}
