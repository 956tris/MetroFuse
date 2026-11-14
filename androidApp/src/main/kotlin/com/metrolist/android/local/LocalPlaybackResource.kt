/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

suspend fun localPlaybackResource(
    context: Context,
    id: String,
    durationSeconds: Long?,
): PlaybackResource? =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(id)
        if (uri.scheme != "content" && uri.scheme != "file") return@withContext null
        try {
            val length =
                if (uri.scheme == "file") {
                    val file = uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() } ?: return@withContext null
                    file.length()
                } else {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return@withContext null
                }
            if (length == 0L) return@withContext null
            val mimeType =
                if (uri.scheme == "content") {
                    context.contentResolver.getType(uri)
                } else {
                    uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.let {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
                    }
                }
            PlaybackResource(
                videoId = id,
                url = id,
                headers = emptyMap(),
                loudnessDb = null,
                expiresAt = null,
                mimeType = mimeType,
                durationSeconds = durationSeconds?.takeIf { it > 0 },
                contentLengthBytes = length.takeIf { it > 0 },
                clientName = "local",
                profileId = "device-audio",
                requireBoundedRange = false,
                allowPlaybackTrackingFallback = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
