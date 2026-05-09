/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

object LocalMusicScanner {
    suspend fun scan(
        context: Context,
        database: MusicDatabase,
    ): Int {
        val tracks = readTracks(context)
        database.withTransaction {
            if (tracks.isEmpty()) {
                deleteAllLocalSongs()
            } else {
                deleteMissingLocalSongs(tracks.map { it.song.id })
            }

            val albums = tracks.groupBy { it.album.id }
            tracks.forEach { track ->
                track.artists.forEach(::upsert)
                albums[track.album.id]?.let { albumTracks ->
                    upsert(
                        track.album.copy(
                            songCount = albumTracks.size,
                            duration = albumTracks.sumOf { it.song.duration.coerceAtLeast(0) },
                        ),
                    )
                }
                upsert(track.song)
                track.format?.let(::upsert)
                track.artists.forEachIndexed { index, artist ->
                    upsert(SongArtistMap(track.song.id, artist.id, index))
                }
                track.song.albumId?.let { albumId ->
                    upsert(SongAlbumMap(track.song.id, albumId, track.trackNumber))
                    track.artists.firstOrNull()?.let { artist ->
                        upsert(AlbumArtistMap(albumId, artist.id, 0))
                    }
                }
            }
        }
        return tracks.size
    }

    private fun readTracks(context: Context): List<LocalTrack> {
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.TRACK,
            )

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val tracks = mutableListOf<LocalTrack>()

        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                val title = cursor.getStringOrNull(titleColumn)?.takeIf { it.isNotBlank() } ?: "Unknown title"
                val artistName = cursor.getStringOrNull(artistColumn)?.cleanUnknown() ?: "Unknown artist"
                val albumName = cursor.getStringOrNull(albumColumn)?.cleanUnknown() ?: "Unknown album"
                val mediaStoreAlbumId = cursor.getLongOrNull(albumIdColumn)?.takeIf { it > 0L }
                val durationMs = cursor.getLongOrNull(durationColumn)?.coerceAtLeast(0L) ?: 0L
                val durationSeconds = if (durationMs > 0L) (durationMs / 1000L).toInt() else -1
                val dateAdded = cursor.getLongOrNull(dateAddedColumn)?.toLocalDateTime()
                val dateModified = cursor.getLongOrNull(dateModifiedColumn)?.toLocalDateTime()
                val year = cursor.getIntOrNull(yearColumn)?.takeIf { it > 0 }
                val mimeType = cursor.getStringOrNull(mimeColumn)?.takeIf { it.isNotBlank() } ?: "audio/*"
                val size = cursor.getLongOrNull(sizeColumn)?.coerceAtLeast(0L) ?: 0L
                val trackNumber = cursor.getIntOrNull(trackColumn)?.takeIf { it > 0 }?.rem(1000) ?: tracks.size
                val thumbnailUrl = mediaStoreAlbumId?.let { albumArtUri(it) }
                val artist = ArtistEntity(
                    id = stableLocalId("artist", artistName),
                    name = artistName,
                    isLocal = true,
                )
                val albumId = mediaStoreAlbumId?.let { "local:album:$it" }
                    ?: stableLocalId("album", "$albumName|$artistName")
                val album =
                    AlbumEntity(
                        id = albumId,
                        title = albumName,
                        year = year,
                        thumbnailUrl = thumbnailUrl,
                        songCount = 0,
                        duration = 0,
                        isLocal = true,
                    )
                val song =
                    SongEntity(
                        id = uri,
                        title = title,
                        duration = durationSeconds,
                        thumbnailUrl = thumbnailUrl,
                        albumId = albumId,
                        albumName = albumName,
                        year = year,
                        date = year?.let { LocalDateTime.of(it, 1, 1, 0, 0) },
                        dateModified = dateModified,
                        inLibrary = dateAdded,
                        dateDownload = dateAdded,
                        isLocal = true,
                        isDownloaded = true,
                    )
                val format =
                    FormatEntity(
                        id = uri,
                        itag = LOCAL_FILE_ITAG,
                        mimeType = mimeType,
                        codecs = "",
                        bitrate = bitrate(size, durationMs),
                        sampleRate = null,
                        contentLength = size,
                        loudnessDb = null,
                        playbackUrl = null,
                    )
                tracks += LocalTrack(song, listOf(artist), album, format, trackNumber)
            }
        }

        return tracks
    }

    private fun android.database.Cursor.getStringOrNull(column: Int): String? =
        if (isNull(column)) null else getString(column)

    private fun android.database.Cursor.getLongOrNull(column: Int): Long? =
        if (isNull(column)) null else getLong(column)

    private fun android.database.Cursor.getIntOrNull(column: Int): Int? =
        if (isNull(column)) null else getInt(column)

    private fun String.cleanUnknown(): String? =
        trim()
            .takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) }

    private fun Long.toLocalDateTime(): LocalDateTime =
        Instant.ofEpochSecond(this)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

    private fun albumArtUri(albumId: Long): String =
        ContentUris.withAppendedId(ALBUM_ART_URI, albumId).toString()

    private fun stableLocalId(
        type: String,
        value: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(value.lowercase(Locale.US).trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "local:$type:$digest"
    }

    private fun bitrate(
        sizeBytes: Long,
        durationMs: Long,
    ): Int =
        if (sizeBytes > 0L && durationMs > 0L) {
            ((sizeBytes * 8_000L) / durationMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }

    private data class LocalTrack(
        val song: SongEntity,
        val artists: List<ArtistEntity>,
        val album: AlbumEntity,
        val format: FormatEntity?,
        val trackNumber: Int,
    )

    private const val LOCAL_FILE_ITAG = -2000
    private val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")
}
