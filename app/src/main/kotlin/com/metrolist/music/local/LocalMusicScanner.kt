/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.local

import android.content.ContentUris
import android.content.Context
import android.os.Build
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
        val tracks = readTracks(context, database)
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

    private fun readTracks(
        context: Context,
        database: MusicDatabase,
    ): List<LocalTrack> {
        val projection =
            buildList {
                add(MediaStore.Audio.Media._ID)
                add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.ALBUM_ID)
                add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.DATE_ADDED)
                add(MediaStore.Audio.Media.DATE_MODIFIED)
                add(MediaStore.Audio.Media.YEAR)
                add(MediaStore.Audio.Media.MIME_TYPE)
                add(MediaStore.Audio.Media.SIZE)
                add(MediaStore.Audio.Media.TRACK)
                add(MediaStore.MediaColumns.DISPLAY_NAME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(MediaStore.MediaColumns.RELATIVE_PATH)
                }
            }.toTypedArray()

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
            val displayNameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val relativePathColumn =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                val displayName = cursor.getStringOrNull(displayNameColumn)
                val relativePath = cursor.getStringOrNull(relativePathColumn)
                val isMetrolistDownload = relativePath.isMetrolistDownloadPath()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMetrolistDownload) {
                    continue
                }
                val existingSong = if (isMetrolistDownload) database.getSongByIdBlocking(uri) else null
                val displayMetadata = displayName.parseMetrolistDisplayName()
                val title =
                    if (isMetrolistDownload) {
                        existingSong?.song?.title
                            ?: cursor.getStringOrNull(titleColumn)?.cleanUnknown()
                            ?: displayMetadata?.title
                            ?: "Unknown title"
                    } else {
                        cursor.getStringOrNull(titleColumn)?.takeIf { it.isNotBlank() } ?: "Unknown title"
                    }
                val artistNames =
                    if (isMetrolistDownload) {
                        existingSong?.orderedArtists?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                            ?: cursor.getStringOrNull(artistColumn)?.cleanUnknown()?.let(::listOf)
                            ?: displayMetadata?.artist?.let(::listOf)
                            ?: listOf("Unknown artist")
                    } else {
                        listOf(cursor.getStringOrNull(artistColumn)?.cleanUnknown() ?: "Unknown artist")
                    }
                val albumName =
                    if (isMetrolistDownload) {
                        existingSong?.song?.albumName
                            ?: existingSong?.album?.title
                            ?: cursor.getStringOrNull(albumColumn)?.cleanUnknown()
                            ?: "Metrolist downloads"
                    } else {
                        cursor.getStringOrNull(albumColumn)?.cleanUnknown() ?: "Unknown album"
                }
                val mediaStoreAlbumId = cursor.getLongOrNull(albumIdColumn)?.takeIf { it > 0L }
                val durationMs = cursor.getLongOrNull(durationColumn)?.coerceAtLeast(0L) ?: 0L
                val scannedDurationSeconds = if (durationMs > 0L) (durationMs / 1000L).toInt() else -1
                val durationSeconds =
                    if (isMetrolistDownload) {
                        existingSong?.song?.duration?.takeIf { it > 0 } ?: scannedDurationSeconds
                    } else {
                        scannedDurationSeconds
                    }
                val dateAdded = cursor.getLongOrNull(dateAddedColumn)?.toLocalDateTime()
                val dateModified = cursor.getLongOrNull(dateModifiedColumn)?.toLocalDateTime()
                val year =
                    if (isMetrolistDownload) {
                        existingSong?.song?.year ?: cursor.getIntOrNull(yearColumn)?.takeIf { it > 0 }
                    } else {
                        cursor.getIntOrNull(yearColumn)?.takeIf { it > 0 }
                    }
                val mimeType = cursor.getStringOrNull(mimeColumn)?.takeIf { it.isNotBlank() } ?: "audio/*"
                val size = cursor.getLongOrNull(sizeColumn)?.coerceAtLeast(0L) ?: 0L
                val trackNumber = cursor.getIntOrNull(trackColumn)?.takeIf { it > 0 }?.rem(1000) ?: tracks.size
                val scannedThumbnailUrl = mediaStoreAlbumId?.let { albumArtUri(it) }
                val thumbnailUrl =
                    if (isMetrolistDownload) {
                        existingSong?.song?.thumbnailUrl ?: scannedThumbnailUrl
                    } else {
                        scannedThumbnailUrl
                    }
                val artists =
                    artistNames.map { artistName ->
                        ArtistEntity(
                            id = stableLocalId("artist", artistName),
                            name = artistName,
                            isLocal = true,
                        )
                    }
                val albumId =
                    if (isMetrolistDownload) {
                        existingSong?.song?.albumId ?: stableLocalId("album", "$albumName|${artistNames.joinToString("|")}")
                    } else {
                        mediaStoreAlbumId?.let { "local:album:$it" }
                            ?: stableLocalId("album", "$albumName|${artistNames.joinToString("|")}")
                    }
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
                        liked = existingSong?.song?.liked ?: false,
                        likedDate = existingSong?.song?.likedDate,
                        totalPlayTime = existingSong?.song?.totalPlayTime ?: 0L,
                        inLibrary = existingSong?.song?.inLibrary ?: dateAdded,
                        dateDownload = existingSong?.song?.dateDownload ?: dateAdded,
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
                tracks += LocalTrack(song, artists, album, format, trackNumber)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tracks += readMetrolistDownloadTracks(
                context = context,
                database = database,
                existingTrackIds = tracks.mapTo(mutableSetOf()) { it.song.id },
            )
        }

        return tracks.distinctBy { it.song.id }
    }

    private fun readMetrolistDownloadTracks(
        context: Context,
        database: MusicDatabase,
        existingTrackIds: Set<String>,
    ): List<LocalTrack> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val selection =
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND " +
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf(
            "Download/Metrolist",
            "Download/Metrolist/",
            "Downloads/Metrolist",
            "Downloads/Metrolist/",
            "audio/%",
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val tracks = mutableListOf<LocalTrack>()

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val displayNameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateAddedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                if (uri in existingTrackIds) continue

                val existingSong = database.getSongByIdBlocking(uri)
                val displayName = cursor.getStringOrNull(displayNameColumn)
                val displayMetadata = displayName.parseMetrolistDisplayName()
                val artistNames =
                    existingSong?.orderedArtists?.map { it.name }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                        ?: displayMetadata?.artist?.let(::listOf)
                        ?: listOf("Unknown artist")
                val title =
                    existingSong?.song?.title
                        ?: displayMetadata?.title
                        ?: displayName?.substringBeforeLast('.', missingDelimiterValue = displayName)
                        ?: "Unknown title"
                val albumName =
                    existingSong?.song?.albumName
                        ?: existingSong?.album?.title
                        ?: "Metrolist downloads"
                val dateAdded = cursor.getLongOrNull(dateAddedColumn)?.toLocalDateTime()
                val dateModified = cursor.getLongOrNull(dateModifiedColumn)?.toLocalDateTime()
                val durationSeconds = existingSong?.song?.duration?.takeIf { it > 0 } ?: -1
                val year = existingSong?.song?.year
                val mimeType = cursor.getStringOrNull(mimeColumn)?.takeIf { it.isNotBlank() } ?: "audio/*"
                val size = cursor.getLongOrNull(sizeColumn)?.coerceAtLeast(0L) ?: 0L
                val thumbnailUrl = existingSong?.song?.thumbnailUrl
                val artists =
                    artistNames.map { artistName ->
                        ArtistEntity(
                            id = stableLocalId("artist", artistName),
                            name = artistName,
                            isLocal = true,
                        )
                    }
                val albumId =
                    existingSong?.song?.albumId
                        ?: stableLocalId("album", "$albumName|${artistNames.joinToString("|")}")
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
                        liked = existingSong?.song?.liked ?: false,
                        likedDate = existingSong?.song?.likedDate,
                        totalPlayTime = existingSong?.song?.totalPlayTime ?: 0L,
                        inLibrary = existingSong?.song?.inLibrary ?: dateAdded,
                        dateDownload = existingSong?.song?.dateDownload ?: dateAdded,
                        isLocal = true,
                        isDownloaded = true,
                    )
                val format =
                    FormatEntity(
                        id = uri,
                        itag = LOCAL_FILE_ITAG,
                        mimeType = mimeType,
                        codecs = "",
                        bitrate = bitrate(size, durationSeconds.takeIf { it > 0 }?.toLong()?.times(1000L) ?: 0L),
                        sampleRate = null,
                        contentLength = size,
                        loudnessDb = null,
                        playbackUrl = null,
                    )
                tracks += LocalTrack(song, artists, album, format, tracks.size)
            }
        }

        return tracks
    }

    private fun android.database.Cursor.getStringOrNull(column: Int): String? =
        if (column < 0 || isNull(column)) null else getString(column)

    private fun android.database.Cursor.getLongOrNull(column: Int): Long? =
        if (column < 0 || isNull(column)) null else getLong(column)

    private fun android.database.Cursor.getIntOrNull(column: Int): Int? =
        if (column < 0 || isNull(column)) null else getInt(column)

    private fun String.cleanUnknown(): String? =
        trim()
            .takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) }

    private fun String?.isMetrolistDownloadPath(): Boolean =
        this
            ?.replace('\\', '/')
            ?.trim('/')
            ?.equals("Download/Metrolist", ignoreCase = true) == true ||
            this
                ?.replace('\\', '/')
                ?.trim('/')
                ?.equals("Downloads/Metrolist", ignoreCase = true) == true

    private fun String?.parseMetrolistDisplayName(): MetrolistDisplayMetadata? {
        val name = this
            ?.substringBeforeLast('.', missingDelimiterValue = this)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val parts = name.split(" - ", limit = 2)
        return if (parts.size == 2) {
            MetrolistDisplayMetadata(artist = parts[0].trim(), title = parts[1].trim())
        } else {
            MetrolistDisplayMetadata(artist = null, title = name)
        }
    }

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

    private data class MetrolistDisplayMetadata(
        val artist: String?,
        val title: String,
    )

    private const val LOCAL_FILE_ITAG = -2000
    private val ALBUM_ART_URI = android.net.Uri.parse("content://media/external/audio/albumart")
}
