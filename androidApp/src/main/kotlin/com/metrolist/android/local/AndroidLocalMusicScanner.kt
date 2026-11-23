/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.metrolist.shared.local.LocalLibraryImporter
import com.metrolist.shared.local.LocalTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class LocalMusicScanStatus { IDLE, SCANNING, COMPLETE, PERMISSION_REQUIRED, FAILED }

data class LocalMusicScanState(
    val status: LocalMusicScanStatus = LocalMusicScanStatus.IDLE,
    val found: Int = 0,
    val imported: Int = 0,
)

class AndroidLocalMusicScanner(
    private val context: Context,
    private val importer: LocalLibraryImporter,
) {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(LocalMusicScanState())
    val state = mutableState.asStateFlow()

    val permission: String
        get() =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun scan(): LocalMusicScanState =
        gate.withLock {
            if (!hasPermission()) {
                return@withLock LocalMusicScanState(LocalMusicScanStatus.PERMISSION_REQUIRED).also { mutableState.value = it }
            }
            mutableState.value = LocalMusicScanState(LocalMusicScanStatus.SCANNING)
            try {
                val tracks = readTracks()
                val imported = importer.importTracks(tracks)
                LocalMusicScanState(LocalMusicScanStatus.COMPLETE, tracks.size, imported).also { mutableState.value = it }
            } catch (cancelled: CancellationException) {
                mutableState.value = LocalMusicScanState()
                throw cancelled
            } catch (denied: SecurityException) {
                LocalMusicScanState(LocalMusicScanStatus.PERMISSION_REQUIRED).also { mutableState.value = it }
            } catch (failure: Exception) {
                Timber.w(failure, "Local music scan failed")
                LocalMusicScanState(LocalMusicScanStatus.FAILED).also { mutableState.value = it }
            }
        }

    private suspend fun readTracks(): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection =
                buildList {
                    add(MediaStore.Audio.Media._ID)
                    add(MediaStore.Audio.Media.TITLE)
                    add(MediaStore.Audio.Media.DISPLAY_NAME)
                    add(MediaStore.Audio.Media.DURATION)
                    add(MediaStore.Audio.Media.ARTIST)
                    add(MediaStore.Audio.Media.ALBUM)
                    add(MediaStore.Audio.Media.YEAR)
                    add(MediaStore.Audio.Media.TRACK)
                    add(MediaStore.Audio.Media.DATE_MODIFIED)
                    add(MediaStore.Audio.Media.SIZE)
                    add(MediaStore.Audio.Media.MIME_TYPE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.Audio.Media.IS_PENDING)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        add(MediaStore.Audio.Media.IS_TRASHED)
                    }
                }.toTypedArray()
            val tracks = mutableListOf<LocalTrack>()
            val cursor =
                requireNotNull(
                    context.contentResolver.query(
                        collection,
                        projection,
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                        null,
                        "${MediaStore.Audio.Media.TITLE} ASC",
                    ),
                )
            cursor.use {
                val indices = projection.associateWith(cursor::getColumnIndex)
                val sizeIndex = indices[MediaStore.Audio.Media.SIZE] ?: -1
                val mimeIndex = indices[MediaStore.Audio.Media.MIME_TYPE] ?: -1
                val pendingIndex =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        indices[MediaStore.Audio.Media.IS_PENDING] ?: -1
                    } else {
                        -1
                    }
                val trashedIndex =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        indices[MediaStore.Audio.Media.IS_TRASHED] ?: -1
                    } else {
                        -1
                    }

                fun text(column: String): String? {
                    val index = indices[column] ?: -1
                    if (index < 0 || cursor.isNull(index)) return null
                    return cursor
                        .getString(index)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING }
                }
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    if (pendingIndex >= 0 && !cursor.isNull(pendingIndex) && cursor.getInt(pendingIndex) != 0) continue
                    if (trashedIndex >= 0 && !cursor.isNull(trashedIndex) && cursor.getInt(trashedIndex) != 0) continue
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) <= 0L) continue
                    val durationMillis = cursor.getLong(indices.getValue(MediaStore.Audio.Media.DURATION))
                    if (durationMillis <= 0L) continue
                    val mimeType =
                        if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                            cursor.getString(mimeIndex)?.trim()?.takeIf { it.isNotBlank() }
                        } else {
                            null
                        }
                    if (mimeType != null && !mimeType.startsWith("audio/", ignoreCase = true) && mimeType !in EXTRA_SUPPORTED_AUDIO_MIMES) {
                        continue
                    }
                    val id = cursor.getLong(indices.getValue(MediaStore.Audio.Media._ID))
                    val audioUri = ContentUris.withAppendedId(collection, id)
                    val title =
                        text(MediaStore.Audio.Media.TITLE)
                            ?: text(MediaStore.Audio.Media.DISPLAY_NAME)?.substringBeforeLast('.')
                            ?: continue
                    val encodedTrack = cursor.getInt(indices.getValue(MediaStore.Audio.Media.TRACK))
                    val year = cursor.getInt(indices.getValue(MediaStore.Audio.Media.YEAR)).takeIf { it > 0 }
                    val modified = cursor.getLong(indices.getValue(MediaStore.Audio.Media.DATE_MODIFIED))
                    tracks +=
                        LocalTrack(
                            uri = audioUri.toString(),
                            title = title,
                            durationSeconds = (durationMillis / 1000L).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                            artist = text(MediaStore.Audio.Media.ARTIST),
                            album = text(MediaStore.Audio.Media.ALBUM),
                            year = year,
                            trackNumber = (encodedTrack % 1000).takeIf { it > 0 },
                            artworkUri = localAudioArtworkUri(audioUri, modified.takeIf { it > 0 }),
                            dateModifiedMillis = modified.takeIf { it > 0 }?.times(1000L),
                        )
                }
            }
            tracks
        }

    private companion object {
        val EXTRA_SUPPORTED_AUDIO_MIMES = setOf("application/ogg", "application/x-flac")
    }
}
