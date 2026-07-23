/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.Album as TubeAlbum
import com.metrolist.innertube.models.Artist as TubeArtist
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.qobuz.QobuzAudioProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object QobuzHomeFeedProvider {
    private val client = OkHttpClient()

    suspend fun load(customInstances: String? = null): Result<HomePage> = runCatching {
        withContext(Dispatchers.IO) {
            val baseUrl = QobuzAudioProvider.resolverInstanceBases(customInstances).first()
            val url = "$baseUrl/api/get-featured"
            
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", QobuzAudioProvider.BROWSER_USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Qobuz home failed: ${response.code}")
                val body = response.body.string()
                parse(JSONObject(body))
            }
        }
    }

    private fun parse(root: JSONObject): HomePage {
        val data = root.optJSONObject("data") ?: return HomePage(chips = null, sections = emptyList())
        val sections = mutableListOf<HomePage.Section>()

        // Featured Albums
        data.optJSONArray("featured_albums")?.let { albums ->
            val items = parseAlbums(albums)
            if (items.isNotEmpty()) {
                sections.add(HomePage.Section(
                    title = "Featured Albums",
                    label = null,
                    thumbnail = null,
                    endpoint = null,
                    items = items
                ))
            }
        }

        // New Releases
        data.optJSONArray("new_releases")?.let { albums ->
            val items = parseAlbums(albums)
            if (items.isNotEmpty()) {
                sections.add(HomePage.Section(
                    title = "New Releases",
                    label = null,
                    thumbnail = null,
                    endpoint = null,
                    items = items
                ))
            }
        }

        // Top Tracks
        data.optJSONArray("top_tracks")?.let { tracks ->
            val items = parseTracks(tracks)
            if (items.isNotEmpty()) {
                sections.add(HomePage.Section(
                    title = "Top Tracks",
                    label = null,
                    thumbnail = null,
                    endpoint = null,
                    items = items
                ))
            }
        }

        return HomePage(chips = null, sections = sections)
    }

    private fun parseAlbums(array: JSONArray): List<YTItem> {
        val items = mutableListOf<YTItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id") ?: continue
            val title = obj.optString("title") ?: continue
            val artistName = obj.optJSONObject("artist")?.optString("name")
            val thumbnail = obj.optJSONObject("image")?.optString("large")
                ?: obj.optJSONObject("image")?.optString("small")

            items.add(AlbumItem(
                browseId = "qobuz:album:$id",
                playlistId = "qobuz:album:$id",
                title = title,
                artists = artistName?.let { listOf(TubeArtist(name = it, id = null)) },
                year = null,
                thumbnail = thumbnail ?: "",
                explicit = false
            ))
        }
        return items
    }

    private fun parseTracks(array: JSONArray): List<YTItem> {
        val items = mutableListOf<YTItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id") ?: continue
            val title = obj.optString("title") ?: continue
            val artistName = obj.optJSONObject("performer")?.optString("name")
            val album = obj.optJSONObject("album")
            val thumbnail = album?.optJSONObject("image")?.optString("large")
                ?: album?.optJSONObject("image")?.optString("small")

            items.add(SongItem(
                id = "qobuz:track:$id",
                title = title,
                artists = artistName?.let { listOf(TubeArtist(name = it, id = null)) }.orEmpty(),
                album = album?.let { TubeAlbum(name = it.optString("title"), id = "qobuz:album:${it.optString("id")}") },
                duration = obj.optInt("duration"),
                thumbnail = thumbnail ?: "",
                explicit = false
            ))
        }
        return items
    }
}
