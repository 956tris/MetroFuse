/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.discordrpc.ActivityType
import com.metrolist.music.discordrpc.DiscordRpcConnection
import com.metrolist.music.discordrpc.SuperProperties
import com.metrolist.music.discordrpc.entities.Button
import com.metrolist.music.discordrpc.entities.Timestamps

class DiscordRPC(
    val context: Context,
    token: String,
) {
    private val connection = DiscordRpcConnection(
        token = token,
        os = "Android",
        browser = "Discord Android",
        device = android.os.Build.DEVICE,
        userAgent = SuperProperties.userAgent,
        superPropertiesBase64 = SuperProperties.superPropertiesBase64,
    )

    fun start() {
        connection.connect()
    }

    fun closeRPC() {
        connection.closeDirect()
    }

    fun isRpcRunning(): Boolean = connection.isRunning()

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
        artworkUrl: String? = null,
    ) = runCatching {
        val currentTime = System.currentTimeMillis()

        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val songTitleWithRate = if (playbackSpeed != 1.0f) {
            "${song.song.title} [${String.format("%.2fx", playbackSpeed)}]"
        } else {
            song.song.title
        }

        val remainingDuration = song.song.duration * 1000L - currentPlaybackTimeMillis
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

        val buttonsList = mutableListOf<Button>()
        if (button1Visible) {
            val resolvedText = resolveVariables(
                button1Text.ifEmpty { context.getString(R.string.discord_default_button_1) },
                song,
            )
            buttonsList.add(Button(resolvedText, "https://music.youtube.com/watch?v=${song.song.id}"))
        }
        if (button2Visible) {
            val resolvedText = resolveVariables(
                button2Text.ifEmpty { context.getString(R.string.discord_default_button_2) },
                song,
            )
            buttonsList.add(Button(resolvedText, context.getString(R.string.discord_default_button_2_url)))
        }

        val type = when (activityType) {
            "playing" -> ActivityType.PLAYING
            "watching" -> ActivityType.WATCHING
            "competing" -> ActivityType.COMPETING
            else -> ActivityType.LISTENING
        }

        val name = activityName.ifEmpty {
            context.getString(R.string.app_name).removeSuffix(" Debug")
        }
        val largeImageUrl =
            listOf(
                artworkUrl,
                song.album?.thumbnailUrl,
                song.song.thumbnailUrl,
            ).firstNotNullOfOrNull { it.normalizedRpcArtworkUrl() }
        connection.setActivity(
            name = name,
            type = type,
            details = if (!useDetails) songTitleWithRate else song.artists.joinToString { it.name },
            state = if (!useDetails) song.artists.joinToString { it.name } else songTitleWithRate,
            timestamps = Timestamps(
                start = calculatedStartTime,
                end = currentTime + adjustedRemainingDuration,
            ),
            largeImage = largeImageUrl,
            smallImage = song.artists.firstOrNull()?.thumbnailUrl.normalizedRpcArtworkUrl(),
            largeText = song.album?.title,
            smallText = song.artists.firstOrNull()?.name,
            buttons = buttonsList.ifEmpty { null },
            status = status,
            since = currentTime,
            applicationId = APPLICATION_ID,
        )
    }

    suspend fun close() {
        connection.close()
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"

        private fun String?.normalizedRpcArtworkUrl(): String? {
            val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
            if (!value.startsWith("http", ignoreCase = true) && !value.startsWith("mp:", ignoreCase = true)) {
                return null
            }
            if (!value.contains("googleusercontent.com", ignoreCase = true) &&
                !value.contains("ggpht.com", ignoreCase = true)
            ) {
                return value
            }
            val base =
                value
                    .substringBefore("=w")
                    .substringBefore("=s")
            return when {
                base.contains("googleusercontent.com", ignoreCase = true) -> "$base=w640-h640-p-l90-rj"
                base.contains("ggpht.com", ignoreCase = true) -> "$base=s640"
                else -> value
            }
        }

        fun resolveVariables(text: String, song: Song): String {
            return text
                .replace("{song_name}", song.song.title)
                .replace("{artist_name}", song.artists.joinToString { it.name })
                .replace("{album_name}", song.album?.title ?: "")
        }
    }
}
