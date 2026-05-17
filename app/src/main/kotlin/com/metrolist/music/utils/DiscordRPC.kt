/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage

class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(
    token = token,
    os = "Android",
    browser = "Discord Android",
    device = android.os.Build.DEVICE,
    userAgent = SuperProperties.userAgent,
    superPropertiesBase64 = SuperProperties.superPropertiesBase64
) {
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
        largeImageUrl: String? = song.song.thumbnailUrl,
        largeImageFallbackUrl: String? = song.song.thumbnailUrl,
        canSend: () -> Boolean = { true },
    ) = runCatching {
        val currentTime = System.currentTimeMillis()
        val safePlaybackSpeed =
            playbackSpeed
                .takeIf { it.isFinite() && it > 0f }
                ?: 1.0f
        val playbackPositionMillis = currentPlaybackTimeMillis.coerceAtLeast(0L)

        val adjustedPlaybackTime = (playbackPositionMillis / safePlaybackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val songTitleWithRate = if (safePlaybackSpeed != 1.0f) {
            "${song.song.title} [${String.format("%.2fx", safePlaybackSpeed)}]"
        } else {
            song.song.title
        }

        val durationMillis =
            song.song.duration
                .takeIf { it > 0 }
                ?.times(1000L)
        val adjustedEndTime =
            durationMillis
                ?.let { it - playbackPositionMillis }
                ?.takeIf { it > 0L }
                ?.let { remainingDuration -> currentTime + (remainingDuration / safePlaybackSpeed).toLong() }

        val buttonsList = mutableListOf<Pair<String, String>>()
        if (button1Visible) {
            val resolvedText = resolveVariables(
                button1Text.ifEmpty { "Listen on YouTube Music" },
                song
            )
            buttonsList.add(resolvedText to "https://music.youtube.com/watch?v=${song.song.id}")
        }
        if (button2Visible) {
            val resolvedText = resolveVariables(
                button2Text.ifEmpty { "Visit Metrolist" },
                song
            )
            buttonsList.add(resolvedText to "https://github.com/MetrolistGroup/Metrolist")
        }

        val type = when (activityType) {
            "playing" -> Type.PLAYING
            "watching" -> Type.WATCHING
            "competing" -> Type.COMPETING
            else -> Type.LISTENING
        }

        val name = activityName.ifEmpty {
            context.getString(R.string.app_name).removeSuffix(" Debug")
        }
        val largeImage =
            largeImageUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { url ->
                    val isAnimated = url.isAnimatedPresenceImageUrl()
                    RpcImage.ExternalImage(
                        image = url,
                        fallbackDiscordAsset = largeImageFallbackUrl.takeUnless { isAnimated },
                        cacheFailures = !isAnimated,
                        resolveAttempts = if (isAnimated) 12 else 1,
                        resolveRetryDelayMs = if (isAnimated) 1_000L else 0L,
                        allowRawUrlFallback = !isAnimated,
                    )
                }
        val requiresResolvedLargeImage = largeImageUrl?.isAnimatedPresenceImageUrl() == true

        setActivity(
            name = name,
            details = songTitleWithRate,
            state = song.artists.joinToString { it.name },
            detailsUrl = "https://music.youtube.com/watch?v=${song.song.id}",
            largeImage = largeImage,
            smallImage = song.artists.firstOrNull()?.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            largeText = song.album?.title,
            smallText = song.artists.firstOrNull()?.name,
            buttons = if (buttonsList.isNotEmpty()) buttonsList else null,
            type = type,
            statusDisplayType = if (useDetails) StatusDisplayType.DETAILS else StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = adjustedEndTime,
            applicationId = APPLICATION_ID,
            status = status,
            requireLargeImage = requiresResolvedLargeImage,
            canSend = canSend,
        )
    }

    override suspend fun close() {
        super.close()
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"

        /**
         * Resolves template variables in text.
         * Supported: {song_name}, {artist_name}, {album_name}
         */
        fun resolveVariables(text: String, song: Song): String {
            return text
                .replace("{song_name}", song.song.title)
                .replace("{artist_name}", song.artists.joinToString { it.name })
                .replace("{album_name}", song.album?.title ?: "")
        }

        private fun String.isAnimatedPresenceImageUrl(): Boolean {
            val path = substringBefore('?').lowercase()
            return path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".avif")
        }
    }
}
