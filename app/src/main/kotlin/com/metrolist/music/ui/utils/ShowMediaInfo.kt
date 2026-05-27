/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.constants.LoudnessLevelKey
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder
import com.metrolist.music.utils.rememberEnumPreference
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun getLoudnessLevelLabel(loudnessLevel: LoudnessLevel): String {
    return when (loudnessLevel) {
        LoudnessLevel.AGGRESSIVE -> stringResource(R.string.loudness_level_aggressive)
        LoudnessLevel.LOUD -> stringResource(R.string.loudness_level_loud)
        LoudnessLevel.BALANCED -> stringResource(R.string.loudness_level_balanced)
        LoudnessLevel.QUIET -> stringResource(R.string.loudness_level_quiet)
    }
}

private const val APPLE_MUSIC_WRAPPER_ITAG = 100_001
private const val QOBUZ_FALLBACK_ITAG = 100_027
private const val TIDAL_FALLBACK_ITAG = 100_029
private const val DEEZER_FALLBACK_ITAG = 100_033
private const val SOUNDCLOUD_FALLBACK_ITAG = 100_031
private const val INSTAGRAM_FALLBACK_ITAG = 100_041
private const val LOCAL_FILE_ITAG = -2000

private val YouTubeAudioItags = setOf(139, 140, 141, 249, 250, 251, 256, 258, 325, 328, 338, 599, 600, 774)

private fun String.canFetchYouTubeMediaInfo(): Boolean =
    length == 11 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

private fun FormatEntity.audioSourceLabel(): String? =
    when (itag) {
        APPLE_MUSIC_WRAPPER_ITAG -> "Apple Music".takeIf { hasUsefulPlaybackDetails() }
        QOBUZ_FALLBACK_ITAG -> "Qobuz"
        TIDAL_FALLBACK_ITAG -> "TIDAL"
        DEEZER_FALLBACK_ITAG -> "Deezer"
        SOUNDCLOUD_FALLBACK_ITAG -> "SoundCloud"
        INSTAGRAM_FALLBACK_ITAG -> "Instagram"
        LOCAL_FILE_ITAG -> "Local"
        in YouTubeAudioItags -> "YouTube Music"
        else -> playbackUrl?.audioSourceLabelFromUrl()
    }

private fun String.audioSourceLabelFromUrl(): String? {
    val value = lowercase()
    return when {
        value.contains("googlevideo.com") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") -> "YouTube Music"
        value.contains("qobuz.com") ||
            value.contains("jumo-dl") ||
            value.contains("kennyy.com.br") ||
            value.contains("squid.wtf") -> "Qobuz"
        value.contains("tidal.com") ||
            value.contains("zarz.moe") -> "TIDAL"
        value.contains("deezer.com") ||
            value.contains("dzcdn.net") ||
            value.contains("dzmedia") -> "Deezer"
        value.contains("soundcloud.com") ||
            value.contains("sndcdn.com") -> "SoundCloud"
        value.contains("instagram.com") ||
            value.contains("cdninstagram.com") ||
            value.contains("fbcdn.net") -> "Instagram"
        else -> null
    }
}

private fun FormatEntity.hasUsefulPlaybackDetails(): Boolean {
    val hasBitrate = bitrate > 0
    val hasSampleRate = sampleRate?.let { it > 0 } == true
    return hasBitrate || hasSampleRate
}

private fun FormatEntity.audioCodecDetailsLabel(): String? =
    when {
        codecs.contains("ec-3", ignoreCase = true) ||
            codecs.contains("eac3", ignoreCase = true) ||
            codecs.contains("atmos", ignoreCase = true) -> "Dolby Atmos (${codecs})"
        codecs.contains("mp4a", ignoreCase = true) -> "AAC (${codecs})"
        codecs.isNotBlank() -> codecs
        else -> null
    }

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank() || videoId.isEmpty()) return

    val windowInsets = WindowInsets.systemBars

    var info by remember {
        mutableStateOf<MediaInfo?>(null)
    }

    val database = LocalDatabase.current
    var song by remember { mutableStateOf<Song?>(null) }

    var currentFormat by remember { mutableStateOf<FormatEntity?>(null) }

    val playerConnection = LocalPlayerConnection.current
    val playbackMetadataState = playerConnection?.mediaMetadata?.collectAsStateWithLifecycle(initialValue = null)
    val playbackMetadata = playbackMetadataState?.value?.takeIf { it.id == videoId }
    val liveFormatState = playerConnection?.currentFormat?.collectAsStateWithLifecycle(initialValue = null)
    val liveFormat = liveFormatState?.value?.takeIf { playbackMetadata?.id == videoId }
    val context = LocalContext.current

    val loudnessLevel by rememberEnumPreference(
        LoudnessLevelKey,
        defaultValue = LoudnessLevel.BALANCED
    )

    val targetLufs: Float = loudnessLevel.targetLufs

    LaunchedEffect(Unit, videoId) {
        info =
            if (videoId.canFetchYouTubeMediaInfo()) {
                withTimeoutOrNull(5_000L) {
                    YouTube.getMediaInfo(videoId).getOrNull()
                }
            } else {
                null
            }
    }

    LaunchedEffect(Unit, videoId) {
        database.song(videoId).collect {
            song = it
        }
    }

    LaunchedEffect(Unit, videoId) {
        database.format(videoId).collect {
            currentFormat = it
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .padding(
                windowInsets
                    .asPaddingValues()
            )
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val title = song?.song?.title ?: playbackMetadata?.title
        val artists =
            song?.orderedArtists?.joinToString { it.name }
                ?: playbackMetadata?.artists?.joinToString { it.name }
        val displayMediaId = song?.id ?: playbackMetadata?.id ?: videoId
        val displayFormatEntity = currentFormat ?: liveFormat

        item(contentType = "MediaDetails") {
            Column {
                    val baseList = listOf(
                        stringResource(R.string.song_title) to title,
                        stringResource(R.string.song_artists) to artists,
                        stringResource(R.string.media_id) to displayMediaId
                    )

                    val baseIconsList = listOf(
                        R.drawable.music_note,
                        R.drawable.person,
                        R.drawable.media3_icon_bookmark_filled,
                    )

                    val iconsList = listOf(
                        R.drawable.cloud,
                        R.drawable.media3_icon_feed,
                        R.drawable.media3_icon_thumb_up_unfilled,
                        R.drawable.media3_icon_thumb_down_unfilled,
                        R.drawable.key,
                        R.drawable.info,
                        R.drawable.radio,
                        R.drawable.gradient,
                        R.drawable.contrast,
                        R.drawable.volume_up,
                        R.drawable.volume_up,
                        R.drawable.volume_mute,
                        R.drawable.content_copy
                    )

                    val measuredLufs: Double? =
                        displayFormatEntity?.perceptualLoudnessDb
                            ?: displayFormatEntity?.loudnessDb?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }
                    val displayFormat = displayFormatEntity?.takeIf { it.hasUsefulPlaybackDetails() }

                    val extendedList = if (displayFormatEntity != null || info != null || playerConnection != null) {
                        listOf(
                            stringResource(R.string.ai_provider) to displayFormat?.audioSourceLabel(),
                            stringResource(R.string.views) to info?.viewCount?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.likes) to info?.like?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.dislikes) to info?.dislike?.let(::numberFormatter).orEmpty(),
                            "Itag" to displayFormatEntity?.itag?.toString(),
                            stringResource(R.string.mime_type) to displayFormatEntity?.mimeType,
                            stringResource(R.string.codecs) to displayFormatEntity?.audioCodecDetailsLabel(),
                            stringResource(R.string.bitrate) to displayFormat?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} Kbps" },
                            stringResource(R.string.sample_rate) to displayFormat?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" },
                            stringResource(R.string.loudness) to measuredLufs?.let {
                                String.format(LocalLocale.current.platformLocale, "%.2f dB", it - targetLufs)
                            },
                            stringResource(R.string.loudness_level) to getLoudnessLevelLabel(loudnessLevel),
                            stringResource(R.string.volume) to if (playerConnection != null) "${(playerConnection.player.volume * 100).toInt()}%" else null,
                            stringResource(R.string.file_size) to
                                displayFormatEntity?.contentLength?.let {
                                    Formatter.formatShortFileSize(
                                        context,
                                        it,
                                    )
                                },
                        )
                    } else {
                        emptyList()
                    }

                    val cardsBaseList = mutableListOf<Material3SettingsItem>()
                    val cardsExtendedList = mutableListOf<Material3SettingsItem>()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    baseList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsBaseList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(baseIconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    extendedList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsExtendedList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(iconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    Material3SettingsGroup(
                        title = stringResource(R.string.general),
                        items = cardsBaseList
                    )

                    Spacer(Modifier.height(8.dp))

                    Material3SettingsGroup(
                        title = stringResource(R.string.information),
                        items = cardsExtendedList
                    )

                    Spacer(Modifier.height(8.dp))

                    val descriptionText = info?.description ?: stringResource(R.string.unknown)

                    Material3SettingsGroup(
                        title = stringResource(R.string.description),
                        items = listOf(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.description)) },
                                description = { Text(descriptionText) },
                                onClick = {
                                    cm.setPrimaryClip(ClipData.newPlainText("text", descriptionText))
                                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                        )
                    )
            }
        }
    }
}
