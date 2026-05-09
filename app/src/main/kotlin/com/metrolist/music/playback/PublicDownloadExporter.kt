/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.metrolist.music.apple.AppleMusicCanvasProvider
import com.metrolist.music.constants.EmbedAnimatedCanvasKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.normalizeSpotifyCookieInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

object PublicDownloadExporter {
    const val DOWNLOAD_PLAYLIST_ID = "LP_METROLIST_DOWNLOADS"

    private const val TAG = "PublicDownloadExporter"
    private const val PUBLIC_FOLDER_NAME = "Metrolist"
    private const val DOWNLOAD_PLAYLIST_NAME = "Metrolist downloads"
    private const val PUBLIC_DOWNLOAD_ITAG = -2001
    private const val OLD_APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac:"
    private const val OLD_APPLE_WRAPPER_CACHE_PREFIX_V2 = "apple-wrapper-alac-v2:"
    private const val APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac-v3:"
    private const val OLD_QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback:"
    private const val QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback-v2:"
    private const val SOUNDCLOUD_FALLBACK_CACHE_PREFIX = "soundcloud-fallback-mp3:"
    private const val INSTAGRAM_FALLBACK_CACHE_PREFIX = "instagram-fallback-audio:"
    private const val YOUTUBE_FALLBACK_CACHE_PREFIX = "youtube-fallback-aac:"

    suspend fun export(
        context: Context,
        database: MusicDatabase,
        downloadCache: SimpleCache,
        lyricsHelper: LyricsHelper,
        downloadId: String,
    ): Boolean {
        val source = database.getSongByIdBlocking(downloadId) ?: return false
        if (source.song.isLocal || source.song.isEpisode) return false

        val format = database.getFormatByIdBlocking(downloadId)
        val cached = cacheKeyCandidates(downloadCache, downloadId)
            .firstNotNullOfOrNull { key ->
                completeCachedSpans(downloadCache, key)?.let { spans -> CachedDownload(key, spans) }
            }

        if (cached == null) {
            Timber.tag(TAG).w("No complete cache spans found for $downloadId")
            return false
        }

        val mimeType = publicMimeType(format)
        val displayName = publicDisplayName(source, format)
        val embeddedMetadata = embeddedMetadata(context, database, lyricsHelper, source)

        return runCatching {
            val stagedFile = createStagedFile(context, displayName, cached.spans)
            try {
                AudioTagWriter.embed(
                    file = stagedFile,
                    extension = publicExtension(format),
                    metadata = embeddedMetadata,
                ).onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to embed metadata in $displayName")
                }

                val publicFile = writePublicFile(context, displayName, mimeType, stagedFile)
                upsertPublicLocalSong(
                    database = database,
                    publicUri = publicFile.uri.toString(),
                    source = source,
                    format = format,
                    mimeType = mimeType,
                    contentLength = publicFile.size,
                    embeddedMetadata = embeddedMetadata,
                )
                deleteAudioCollectionDuplicate(context, database, displayName, publicFile.uri.toString())
            } finally {
                stagedFile.delete()
            }
            Timber.tag(TAG).i(
                "Exported $downloadId to Downloads/$PUBLIC_FOLDER_NAME as $displayName using ${cached.cacheKey}",
            )
            true
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to export $downloadId to public downloads")
        }.getOrDefault(false)
    }

    suspend fun deletePublicCopy(
        context: Context,
        database: MusicDatabase,
        downloadId: String,
    ) {
        val source = database.getSongByIdBlocking(downloadId) ?: return
        val displayName = publicDisplayName(source, database.getFormatByIdBlocking(downloadId))
        val publicUri = findPublicDownloadUri(context, displayName) ?: legacyPublicFile(displayName).toUri()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && publicUri.scheme == "content") {
                context.contentResolver.delete(publicUri, null, null)
            } else {
                publicUri.path?.let(::File)?.delete()
            }

            database.withTransaction {
                getSongByIdBlocking(publicUri.toString())?.song?.let(::delete)
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to delete public copy for $downloadId")
        }
    }

    private fun cacheKeyCandidates(
        downloadCache: SimpleCache,
        downloadId: String,
    ): List<String> =
        (
            listOf(
                downloadId,
                "$APPLE_WRAPPER_CACHE_PREFIX$downloadId",
                "$OLD_APPLE_WRAPPER_CACHE_PREFIX_V2$downloadId",
                "$OLD_APPLE_WRAPPER_CACHE_PREFIX$downloadId",
                "$OLD_QOBUZ_FALLBACK_CACHE_PREFIX$downloadId",
                "$QOBUZ_FALLBACK_CACHE_PREFIX$downloadId",
                "$SOUNDCLOUD_FALLBACK_CACHE_PREFIX$downloadId",
                "$INSTAGRAM_FALLBACK_CACHE_PREFIX$downloadId",
                "$YOUTUBE_FALLBACK_CACHE_PREFIX$downloadId",
            ) +
                downloadCache.keys.filter { key ->
                    key == downloadId ||
                        key.endsWith(":$downloadId") ||
                        key.removeKnownCachePrefix() == downloadId
                }
            )
            .distinct()

    private fun String.removeKnownCachePrefix(): String =
        removePrefix(APPLE_WRAPPER_CACHE_PREFIX)
            .removePrefix(OLD_APPLE_WRAPPER_CACHE_PREFIX_V2)
            .removePrefix(OLD_APPLE_WRAPPER_CACHE_PREFIX)
            .removePrefix(OLD_QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(SOUNDCLOUD_FALLBACK_CACHE_PREFIX)
            .removePrefix(INSTAGRAM_FALLBACK_CACHE_PREFIX)
            .removePrefix(YOUTUBE_FALLBACK_CACHE_PREFIX)

    private fun completeCachedSpans(
        downloadCache: SimpleCache,
        cacheKey: String,
    ): List<CacheSpan>? {
        val spans = downloadCache.getCachedSpans(cacheKey)
            .filter { it.isCached && it.length > 0L && it.file?.exists() == true }
            .sortedBy { it.position }
        if (spans.isEmpty() || spans.first().position != 0L) return null
        if (spans.first().file?.startsWithHlsPlaylist() == true) {
            Timber.tag(TAG).w("Ignoring cached Apple HLS playlist for $cacheKey; download must be refreshed")
            return null
        }

        var nextPosition = 0L
        spans.forEach { span ->
            if (span.position != nextPosition) return null
            nextPosition += span.length
        }
        return spans.takeIf { nextPosition > 0L }
    }

    private fun File.startsWithHlsPlaylist(): Boolean =
        runCatching {
            inputStream().use { input ->
                val marker = "#EXTM3U".toByteArray(StandardCharsets.US_ASCII)
                val header = ByteArray(marker.size)
                input.read(header) == marker.size && header.contentEquals(marker)
            }
        }.getOrDefault(false)

    private fun writePublicFile(
        context: Context,
        displayName: String,
        mimeType: String,
        source: File,
    ): PublicFile {
        val uri =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findPublicDownloadUri(context, displayName)
                    ?: insertPublicDownload(context, displayName, mimeType)
                    ?: error("Could not create MediaStore row for $displayName")
            } else {
                legacyPublicFile(displayName).also { it.parentFile?.mkdirs() }.toUri()
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
            markPending(context, uri, true)
            try {
                context.contentResolver.openOutputStream(uri, "wt").use { output ->
                    writeFile(source, output ?: error("Could not open $uri"))
                }
            } finally {
                markPending(context, uri, false)
            }
        } else {
            File(uri.path ?: error("Missing file path for $uri")).outputStream().use { output ->
                writeFile(source, output)
            }
        }

        return PublicFile(uri = uri, size = source.length())
    }

    private fun createStagedFile(
        context: Context,
        displayName: String,
        spans: List<CacheSpan>,
    ): File {
        val exportDir = context.cacheDir.resolve("public-download-exports").apply { mkdirs() }
        return File.createTempFile("metrolist-export-", ".${displayName.substringAfterLast('.', "tmp")}", exportDir)
            .also { file ->
                file.outputStream().use { output -> writeCachedSpans(spans, output) }
            }
    }

    private fun writeCachedSpans(
        spans: List<CacheSpan>,
        output: OutputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        spans.forEach { span ->
            val file = span.file ?: error("Missing cache file for ${span.key}")
            FileInputStream(file).use { input ->
                var remaining = span.length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) error("Unexpected EOF while reading cache span ${span.key}")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun writeFile(
        source: File,
        output: OutputStream,
    ) {
        source.inputStream().use { input -> input.copyTo(output) }
    }

    private fun insertPublicDownload(
        context: Context,
        displayName: String,
        mimeType: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, publicRelativePath())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        return context.contentResolver.insert(downloadsCollection(), values)
    }

    private fun findPublicDownloadUri(
        context: Context,
        displayName: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = downloadsCollection()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val relativePath = publicRelativePath()
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(displayName, relativePath, "$relativePath/")

        return context.contentResolver
            .query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                    )
                }
            }
    }

    private fun markPending(
        context: Context,
        uri: Uri,
        pending: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        context.contentResolver.update(
            uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            },
            null,
            null,
        )
    }

    private suspend fun upsertPublicLocalSong(
        database: MusicDatabase,
        publicUri: String,
        source: Song,
        format: FormatEntity?,
        mimeType: String,
        contentLength: Long,
        embeddedMetadata: EmbeddedAudioMetadata,
    ) {
        val now = LocalDateTime.now()
        val artistNames = source.orderedArtists
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("Unknown artist") }
        val artists = artistNames.map { name ->
            ArtistEntity(
                id = stableLocalId("artist", name),
                name = name,
                isLocal = true,
            )
        }
        val albumName = source.song.albumName
            ?: source.album?.title
            ?: DOWNLOAD_PLAYLIST_NAME
        val thumbnailUrl = embeddedMetadata.artwork?.localThumbnailUrl
            ?: source.song.thumbnailUrl
            ?: source.album?.thumbnailUrl
        val albumId = stableLocalId("album", "$albumName|${artistNames.joinToString("|")}")
        val album =
            AlbumEntity(
                id = albumId,
                title = albumName,
                year = source.song.year,
                thumbnailUrl = thumbnailUrl,
                songCount = 1,
                duration = source.song.duration.coerceAtLeast(0),
                explicit = source.song.explicit,
                isLocal = true,
            )
        val existingLocal = database.getSongByIdBlocking(publicUri)?.song
        val song =
            SongEntity(
                id = publicUri,
                title = source.song.title,
                duration = source.song.duration,
                thumbnailUrl = thumbnailUrl,
                albumId = albumId,
                albumName = albumName,
                explicit = source.song.explicit,
                year = source.song.year,
                date = source.song.date,
                dateModified = now,
                liked = existingLocal?.liked ?: false,
                likedDate = existingLocal?.likedDate,
                totalPlayTime = existingLocal?.totalPlayTime ?: 0L,
                inLibrary = existingLocal?.inLibrary ?: now,
                dateDownload = now,
                isLocal = true,
                isDownloaded = true,
            )
        val localFormat =
            FormatEntity(
                id = publicUri,
                itag = PUBLIC_DOWNLOAD_ITAG,
                mimeType = mimeType,
                codecs = publicCodecs(format),
                bitrate = exportedBitrate(contentLength, source.song.duration)
                    .takeIf { it > 0 }
                    ?: format?.bitrate?.takeIf { it > 0 }
                    ?: 0,
                sampleRate = format?.sampleRate,
                contentLength = contentLength,
                loudnessDb = format?.loudnessDb,
                perceptualLoudnessDb = format?.perceptualLoudnessDb,
                playbackUrl = null,
            )

        database.withTransaction {
            artists.forEach(::upsert)
            upsert(album)
            upsert(song)
            upsert(localFormat)
            embeddedMetadata.lyrics?.takeIf { it.isEmbeddableLyrics() }?.let { lyrics ->
                upsert(
                    LyricsEntity(
                        id = song.id,
                        lyrics = lyrics,
                        provider = embeddedMetadata.lyricsProvider ?: "Embedded",
                    ),
                )
            }
            artists.forEachIndexed { index, artist ->
                upsert(SongArtistMap(song.id, artist.id, index))
            }
            upsert(SongAlbumMap(song.id, album.id, 0))
            artists.firstOrNull()?.let { artist ->
                upsert(AlbumArtistMap(album.id, artist.id, 0))
            }
            val existingPlaylist = playlistBlocking(DOWNLOAD_PLAYLIST_ID)?.playlist
            if (existingPlaylist == null) {
                insert(
                    PlaylistEntity(
                        id = DOWNLOAD_PLAYLIST_ID,
                        name = DOWNLOAD_PLAYLIST_NAME,
                        createdAt = now,
                        lastUpdateTime = now,
                        bookmarkedAt = null,
                        isLocal = true,
                    ),
                )
            } else {
                update(
                    existingPlaylist.copy(
                        lastUpdateTime = now,
                        bookmarkedAt = null,
                        isLocal = true,
                    ),
                )
            }
            if (playlistSongMaps(song.id).none { it.playlistId == DOWNLOAD_PLAYLIST_ID }) {
                insert(
                    PlaylistSongMap(
                        playlistId = DOWNLOAD_PLAYLIST_ID,
                        songId = song.id,
                        position = playlistSongsBlocking(DOWNLOAD_PLAYLIST_ID).size,
                    ),
                )
            }
        }
    }

    private fun publicDisplayName(
        song: Song,
        format: FormatEntity?,
    ): String {
        val artist = song.orderedArtists
            .joinToString(", ") { it.name }
            .takeIf { it.isNotBlank() }
        val base = listOfNotNull(artist, song.song.title)
            .joinToString(" - ")
            .sanitizeFileName()
            .ifBlank { song.song.id.sanitizeFileName() }
            .take(120)
        return "$base.${publicExtension(format)}"
    }

    private fun publicExtension(format: FormatEntity?): String {
        val mime = format?.mimeType.orEmpty().lowercase(Locale.US)
        val codecs = format?.codecs.orEmpty().lowercase(Locale.US)
        return when {
            "flac" in mime || "flac" in codecs -> "flac"
            "mpeg" in mime || "mp3" in codecs -> "mp3"
            "ogg" in mime -> "ogg"
            "webm" in mime -> "webm"
            "aac" in mime && "mp4" !in mime -> "aac"
            "mp4" in mime || "mp4a" in codecs || "alac" in codecs -> "m4a"
            else -> "m4a"
        }
    }

    private fun publicMimeType(format: FormatEntity?): String =
        when (publicExtension(format)) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "aac" -> "audio/aac"
            else -> "audio/mp4"
        }

    private fun publicCodecs(format: FormatEntity?): String {
        val sourceCodecs = format?.codecs.orEmpty()
        return when (publicExtension(format)) {
            "flac" -> "flac"
            "mp3" -> "mp3"
            "aac" -> "mp4a.40.2"
            "ogg" -> sourceCodecs.takeIf { it.isNotBlank() && !it.contains("alac", ignoreCase = true) }
                ?: "opus"
            "webm" -> sourceCodecs.takeIf { it.isNotBlank() && !it.contains("alac", ignoreCase = true) }
                ?: "opus"
            else -> sourceCodecs.takeIf { it.isNotBlank() } ?: "mp4a.40.2"
        }
    }

    private fun exportedBitrate(
        contentLength: Long,
        durationSeconds: Int,
    ): Int =
        if (contentLength > 0L && durationSeconds > 0) {
            ((contentLength * 8L) / durationSeconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]+"""), "_")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stableLocalId(
        type: String,
        value: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(value.lowercase(Locale.US).trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "local:$type:$digest"
    }

    private fun publicRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_FOLDER_NAME"

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Suppress("DEPRECATION")
    private fun legacyPublicFile(displayName: String): File =
        Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .resolve(PUBLIC_FOLDER_NAME)
            .resolve(displayName)

    private data class CachedDownload(
        val cacheKey: String,
        val spans: List<CacheSpan>,
    )

    private data class PublicFile(
        val uri: Uri,
        val size: Long,
    )

    private suspend fun embeddedMetadata(
        context: Context,
        database: MusicDatabase,
        lyricsHelper: LyricsHelper,
        source: Song,
    ): EmbeddedAudioMetadata {
        val mediaMetadata = source.toMediaMetadata()
        val artistNames = source.orderedArtists
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
        val lyrics = lyricsFor(database, lyricsHelper, source)
        return EmbeddedAudioMetadata(
            title = source.song.title,
            artists = artistNames,
            album = source.song.albumName ?: source.album?.title,
            year = source.song.year,
            lyrics = lyrics?.lyrics,
            lyricsProvider = lyrics?.provider,
            artwork = artworkFor(
                context = context,
                artworkKey = source.song.id,
                thumbnailUrl = source.song.thumbnailUrl ?: source.album?.thumbnailUrl,
            ),
            canvas = canvasFor(
                context = context,
                source = source,
                mediaMetadata = mediaMetadata,
            ),
        )
    }

    private suspend fun lyricsFor(
        database: MusicDatabase,
        lyricsHelper: LyricsHelper,
        source: Song,
    ): LyricsForExport? {
        database.lyrics(source.song.id).first()
            ?.takeIf { it.lyrics.isEmbeddableLyrics() }
            ?.let { return LyricsForExport(it.lyrics, it.provider) }

        val lyricsWithProvider = runCatching {
            lyricsHelper.getLyrics(source.toMediaMetadata())
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to fetch lyrics for ${source.song.id}")
            return null
        }

        val lyrics = lyricsWithProvider.lyrics.takeIf { it.isEmbeddableLyrics() } ?: return null
        database.withTransaction {
            upsert(
                LyricsEntity(
                    id = source.song.id,
                    lyrics = lyrics,
                    provider = lyricsWithProvider.provider,
                ),
            )
        }
        return LyricsForExport(lyrics, lyricsWithProvider.provider)
    }

    private suspend fun deleteAudioCollectionDuplicate(
        context: Context,
        database: MusicDatabase,
        displayName: String,
        publicUri: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val relativePath = publicRelativePath()
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        val selectionArgs = arrayOf(displayName, relativePath, "$relativePath/")
        val duplicateIds = mutableListOf<String>()

        context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)).toString()
                if (uri != publicUri) duplicateIds += uri
            }
        }

        if (duplicateIds.isEmpty()) return
        database.withTransaction {
            duplicateIds.forEach { duplicateId ->
                getSongByIdBlocking(duplicateId)?.song?.takeIf { it.isLocal }?.let(::delete)
            }
        }
    }

    private fun String.isEmbeddableLyrics(): Boolean =
        isNotBlank() &&
            this != LyricsEntity.LYRICS_NOT_FOUND &&
            !equals("Lyrics not found", ignoreCase = true)

    private fun artworkFor(
        context: Context,
        artworkKey: String,
        thumbnailUrl: String?,
    ): EmbeddedArtwork? {
        val url = thumbnailUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        var bestArtwork: NormalizedArtwork? = null

        for (candidateUrl in artworkCandidates(url)) {
            val normalized = runCatching {
                fetchArtwork(candidateUrl)
            }.getOrElse { error ->
                Timber.tag(TAG).d(error, "Failed to fetch artwork candidate for exported download")
                null
            } ?: continue

            val currentBest = bestArtwork
            if (currentBest == null || normalized.pixelCount > currentBest.pixelCount) {
                bestArtwork = normalized
            }
            if (normalized.longestSide >= TARGET_EMBEDDED_ARTWORK_SIDE) {
                break
            }
        }

        val normalized = bestArtwork ?: return null
        val localThumbnailUrl = saveArtwork(context, artworkKey, normalized.bytes)
        return EmbeddedArtwork(
            mimeType = "image/jpeg",
            bytes = normalized.bytes,
            width = normalized.width,
            height = normalized.height,
            colorDepth = 24,
            localThumbnailUrl = localThumbnailUrl,
        )
    }

    private fun fetchArtwork(url: String): NormalizedArtwork? =
        artworkClient.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val contentLength = response.body.contentLength()
            if (contentLength > MAX_SOURCE_ARTWORK_BYTES) return@use null
            val sourceBytes = response.body.bytes().takeIf { it.size <= MAX_SOURCE_ARTWORK_BYTES } ?: return@use null
            val bitmap = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size) ?: return@use null
            normalizeArtwork(bitmap).takeIf { it.bytes.isNotEmpty() }
        }

    private fun artworkCandidates(url: String): List<String> {
        val cleanUrl = url.trim()
        return buildList {
            cleanUrl.youtubeArtworkFile("maxresdefault.jpg")?.let(::add)
            cleanUrl.youtubeArtworkFile("sddefault.jpg")?.let(::add)
            add(cleanUrl.highResolutionArtworkUrl(SOURCE_ARTWORK_SIDE))
            add(cleanUrl.highResolutionArtworkUrl(MAX_EMBEDDED_ARTWORK_SIDE))
            cleanUrl.youtubeArtworkFile("hqdefault.jpg")?.let(::add)
            add(cleanUrl)
        }.distinct()
    }

    private fun String.highResolutionArtworkUrl(size: Int): String =
        replace(Regex("""=w\d+-h\d+[^&?]*"""), "=w$size-h$size-p-l90-rj")
            .replace(Regex("""=s\d+[^&?]*"""), "=s$size")

    private fun String.youtubeArtworkFile(fileName: String): String? {
        val withoutQuery = substringBefore("?")
        if (!withoutQuery.contains("i.ytimg.com/vi/", ignoreCase = true)) return null
        val query = substringAfter("?", missingDelimiterValue = "").takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return withoutQuery.substringBeforeLast('/', missingDelimiterValue = withoutQuery) + "/$fileName$query"
    }

    private val NormalizedArtwork.pixelCount: Long
        get() = width.toLong() * height.toLong()

    private val NormalizedArtwork.longestSide: Int
        get() = max(width, height)

    private suspend fun canvasFor(
        context: Context,
        source: Song,
        mediaMetadata: MediaMetadata,
    ): EmbeddedCanvas? {
        if (!context.dataStore.get(EmbedAnimatedCanvasKey, false)) return null
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null

        val spotifyCookie = normalizeSpotifyCookieInput(context.dataStore.get(SpotifyCookieKey, ""))
        if (spotifyCookie != null) {
            val spotifyCanvas = withTimeoutOrNull(5_000L) {
                runCatching {
                    SpotifyCanvasClient.resolveBackground(mediaMetadata, spotifyCookie)
                }.onFailure { error ->
                    Timber.tag(TAG).d(error, "Failed to resolve Spotify Canvas for ${source.song.id}")
                }.getOrNull()
            }
            spotifyCanvas?.let { canvas ->
                downloadCanvas(
                    url = canvas.url,
                    headers = canvas.headers,
                    provider = "Spotify",
                )?.let { return it }
            }
        }

        val artist = source.orderedArtists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: mediaMetadata.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
            ?: return null
        val album = source.song.albumName ?: source.album?.title ?: mediaMetadata.album?.title
        val appleCanvasUrl =
            AppleMusicCanvasProvider.getCached(
                song = mediaMetadata.title,
                artist = artist,
                album = album,
            )?.animated?.takeIf { it.isNotBlank() }
                ?: withTimeoutOrNull(5_000L) {
                    AppleMusicCanvasProvider.getBySongArtist(
                        song = mediaMetadata.title,
                        artist = artist,
                        album = album,
                    )?.animated?.takeIf { it.isNotBlank() }
                }

        return appleCanvasUrl?.let { url ->
            downloadCanvas(
                url = url,
                headers = emptyMap(),
                provider = "Apple Music",
            )
        }
    }

    private fun downloadCanvas(
        url: String,
        headers: Map<String, String>,
        provider: String,
    ): EmbeddedCanvas? =
        runCatching {
            val request = canvasRequest(url, headers)
            canvasClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                val contentType = response.header("Content-Type").orEmpty().substringBefore(";").trim()
                val isHls = url.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
                    contentType.contains("mpegurl", ignoreCase = true)

                if (isHls) {
                    return@use downloadHlsCanvas(
                        playlistUrl = url,
                        playlist = body.string(),
                        headers = headers,
                        provider = provider,
                    )
                }

                if (body.contentLength() > MAX_EMBEDDED_CANVAS_BYTES) return@use null
                val bytes = body.bytes().takeIf { it.size <= MAX_EMBEDDED_CANVAS_BYTES } ?: return@use null
                val mimeType =
                    contentType
                        .takeIf { it.startsWith("video/", ignoreCase = true) }
                        ?: when {
                            url.substringBefore("?").endsWith(".webm", ignoreCase = true) -> "video/webm"
                            else -> "video/mp4"
                        }
                EmbeddedCanvas(
                    mimeType = mimeType,
                    bytes = bytes,
                    provider = provider,
                )
            }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to download animated Canvas")
            null
        }

    private fun downloadHlsCanvas(
        playlistUrl: String,
        playlist: String,
        headers: Map<String, String>,
        provider: String,
    ): EmbeddedCanvas? {
        val baseUrl = playlistUrl.toHttpUrlOrNull() ?: return null
        val mediaPlaylistUrl = selectHlsVariant(baseUrl, playlist)
        val mediaBaseUrl = mediaPlaylistUrl ?: baseUrl
        val mediaPlaylist =
            if (mediaPlaylistUrl == null) {
                playlist
            } else {
                fetchCanvasText(mediaPlaylistUrl.toString(), headers) ?: return null
            }
        if (!mediaPlaylist.contains("#EXTINF")) return null

        val packageBytes = packageHlsPlaylist(mediaBaseUrl, mediaPlaylist, headers) ?: return null
        return EmbeddedCanvas(
            mimeType = AudioTagWriter.METROFUSE_HLS_CANVAS_MIME,
            bytes = packageBytes,
            provider = provider,
        )
    }

    private fun selectHlsVariant(
        baseUrl: HttpUrl,
        playlist: String,
    ): HttpUrl? {
        val variants = mutableListOf<Pair<Int, HttpUrl>>()
        var pendingBandwidth: Int? = null
        playlist.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = HLS_BANDWIDTH_ATTRIBUTE.find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
                pendingBandwidth != null && line.isNotBlank() && !line.startsWith("#") -> {
                    baseUrl.resolve(line)?.let { variants += (pendingBandwidth ?: Int.MAX_VALUE) to it }
                    pendingBandwidth = null
                }
            }
        }
        return variants.minByOrNull { it.first }?.second
    }

    private fun packageHlsPlaylist(
        baseUrl: HttpUrl,
        playlist: String,
        headers: Map<String, String>,
    ): ByteArray? =
        runCatching {
            val output = java.io.ByteArrayOutputStream()
            var unpackedBytes = 0L
            var segmentIndex = 0
            var initIndex = 0
            var keyIndex = 0
            val rewrittenLines = mutableListOf<String>()

            ZipOutputStream(output).use { zip ->
                fun addEntry(
                    name: String,
                    bytes: ByteArray,
                ) {
                    unpackedBytes += bytes.size
                    if (unpackedBytes > MAX_EMBEDDED_CANVAS_BYTES) error("Canvas HLS package is too large")
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }

                playlist.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                            val uri = HLS_URI_ATTRIBUTE.find(line)?.groupValues?.getOrNull(1)
                            val resolved = uri?.let { baseUrl.resolve(it) }
                            val bytes = resolved?.let { fetchCanvasBytes(it.toString(), headers) }
                            if (resolved != null && bytes != null) {
                                val entryName = "init_${initIndex++}.${hlsExtension(resolved, "mp4")}"
                                addEntry(entryName, bytes)
                                rewrittenLines += HLS_URI_ATTRIBUTE.replace(line, "URI=\"$entryName\"")
                            } else {
                                rewrittenLines += line
                            }
                        }
                        line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                            val uri = HLS_URI_ATTRIBUTE.find(line)?.groupValues?.getOrNull(1)
                            val resolved = uri?.let { baseUrl.resolve(it) }
                            val bytes = resolved?.let { fetchCanvasBytes(it.toString(), headers) }
                            if (resolved != null && bytes != null) {
                                val entryName = "key_${keyIndex++}.key"
                                addEntry(entryName, bytes)
                                rewrittenLines += HLS_URI_ATTRIBUTE.replace(line, "URI=\"$entryName\"")
                            } else {
                                rewrittenLines += line
                            }
                        }
                        line.isBlank() || line.startsWith("#") -> rewrittenLines += line
                        else -> {
                            val resolved = baseUrl.resolve(line)
                            val bytes = resolved?.let { fetchCanvasBytes(it.toString(), headers) }
                            if (resolved != null && bytes != null) {
                                val entryName = "segment_${segmentIndex++}.${hlsExtension(resolved, "m4s")}"
                                addEntry(entryName, bytes)
                                rewrittenLines += entryName
                            }
                        }
                    }
                }

                val manifest = rewrittenLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                addEntry("manifest.m3u8", manifest)
            }

            output.toByteArray().takeIf { it.size <= MAX_EMBEDDED_CANVAS_BYTES }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to package Apple Music Canvas HLS")
            null
        }

    private fun fetchCanvasText(
        url: String,
        headers: Map<String, String>,
    ): String? =
        canvasClient.newCall(canvasRequest(url, headers)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string()
        }

    private fun fetchCanvasBytes(
        url: String,
        headers: Map<String, String>,
    ): ByteArray? =
        canvasClient.newCall(canvasRequest(url, headers)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body
            if (body.contentLength() > MAX_EMBEDDED_CANVAS_BYTES) return@use null
            body.bytes().takeIf { it.size <= MAX_EMBEDDED_CANVAS_BYTES }
        }

    private fun canvasRequest(
        url: String,
        headers: Map<String, String>,
    ): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .apply {
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) {
                        header(name, value)
                    }
                }
            }
            .build()

    private fun hlsExtension(
        url: HttpUrl,
        fallback: String,
    ): String {
        val segment = url.pathSegments.lastOrNull().orEmpty().substringBefore("?")
        val extension = segment.substringAfterLast('.', "")
        return extension
            .takeIf { it.isNotBlank() && it.length <= 5 && it.all { char -> char.isLetterOrDigit() } }
            ?: fallback
    }

    private fun normalizeArtwork(bitmap: Bitmap): NormalizedArtwork {
        val maxSide = max(bitmap.width, bitmap.height)
        val scaled =
            if (maxSide > MAX_EMBEDDED_ARTWORK_SIDE) {
                val scale = MAX_EMBEDDED_ARTWORK_SIDE.toFloat() / maxSide.toFloat()
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }

        val output = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, EMBEDDED_ARTWORK_JPEG_QUALITY, output)
        val width = scaled.width
        val height = scaled.height
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        bitmap.recycle()
        return NormalizedArtwork(
            bytes = output.toByteArray().takeIf { it.size <= MAX_EMBEDDED_ARTWORK_BYTES } ?: ByteArray(0),
            width = width,
            height = height,
        )
    }

    private fun saveArtwork(
        context: Context,
        artworkKey: String,
        bytes: ByteArray,
    ): String? {
        if (bytes.isEmpty()) return null
        val artworkDir = context.filesDir.resolve("download-artwork").apply { mkdirs() }
        val fileName = "${stableLocalId("artwork", artworkKey).replace(':', '_')}.jpg"
        val artworkFile = artworkDir.resolve(fileName)
        artworkFile.outputStream().use { it.write(bytes) }
        return artworkFile.toUri().toString()
    }

    private val artworkClient: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    private val canvasClient: OkHttpClient =
        OkHttpClient.Builder()
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private const val MAX_SOURCE_ARTWORK_BYTES = 5 * 1024 * 1024
    private const val MAX_EMBEDDED_ARTWORK_BYTES = 2 * 1024 * 1024
    private const val SOURCE_ARTWORK_SIDE = 1200
    private const val TARGET_EMBEDDED_ARTWORK_SIDE = 900
    private const val MAX_EMBEDDED_ARTWORK_SIDE = 1024
    private const val EMBEDDED_ARTWORK_JPEG_QUALITY = 92
    private const val MAX_EMBEDDED_CANVAS_BYTES = 8 * 1024 * 1024
    private val HLS_URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    private val HLS_BANDWIDTH_ATTRIBUTE = Regex("""BANDWIDTH=(\d+)""")

    private data class LyricsForExport(
        val lyrics: String,
        val provider: String,
    )

    private data class NormalizedArtwork(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )
}
