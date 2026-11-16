@file:OptIn(ExperimentalMaterial3Api::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.audio_quality_average_bitrate
import com.metrolist.shared.generated.resources.audio_quality_bit_depth
import com.metrolist.shared.generated.resources.audio_quality_bitrate
import com.metrolist.shared.generated.resources.audio_quality_bitrate_detail
import com.metrolist.shared.generated.resources.audio_quality_bitrate_exact
import com.metrolist.shared.generated.resources.audio_quality_detail
import com.metrolist.shared.generated.resources.audio_quality_sample_rate
import com.metrolist.shared.generated.resources.audio_quality_source_format
import com.metrolist.shared.generated.resources.bitrate
import com.metrolist.shared.generated.resources.sample_rate
import com.metrolist.shared.generated.resources.sample_rate_value
import org.jetbrains.compose.resources.stringResource
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal fun exactAudioKiloValue(
    value: Long,
    locale: Locale = Locale.getDefault(),
): String = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(BigDecimal.valueOf(value, 3))

internal fun audioCodecLabel(codec: String): String = if (codec.startsWith("pcm_")) "PCM" else codec.uppercase(Locale.ROOT)

@Composable
internal fun PlayerAudioQualityBadge(
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val player = PlayerState.player
    val revision by player.playbackStateChanges.collectAsState()
    val sourceSong by player.currentSong.collectAsState()
    val castState by PlayerState.castManager.state.collectAsState()
    val songId = PlayerState.currentTrack?.id
    val resolving = PlayerState.isPlaybackResolving
    val format =
        remember(revision, sourceSong?.id, songId, castState.isConnected, resolving) {
            player.audioFormat.takeIf { !castState.isConnected && !resolving && songId != null && songId == sourceSong?.id }
        } ?: return
    val numbers = NumberFormat.getIntegerInstance(Locale.getDefault())
    val depth = format.bitDepth?.let { stringResource(Res.string.audio_quality_bit_depth, numbers.format(it)) }
    val sampleRate = format.sampleRateHz?.let { stringResource(Res.string.audio_quality_sample_rate, exactAudioKiloValue(it.toLong())) }
    val bitrate = format.bitrateBps?.let { stringResource(Res.string.audio_quality_bitrate, exactAudioKiloValue(it)) }
    val codec = audioCodecLabel(format.codec)
    val description =
        listOfNotNull(
            stringResource(Res.string.audio_quality_source_format),
            codec,
            depth,
            format.sampleRateHz?.let {
                stringResource(
                    Res.string.audio_quality_detail,
                    stringResource(Res.string.sample_rate),
                    stringResource(Res.string.sample_rate_value, it),
                )
            },
            format.bitrateBps?.let {
                stringResource(
                    Res.string.audio_quality_bitrate_detail,
                    stringResource(if (format.bitrateIsAverage) Res.string.audio_quality_average_bitrate else Res.string.bitrate),
                    bitrate.orEmpty(),
                    stringResource(Res.string.audio_quality_bitrate_exact, numbers.format(it)),
                )
            },
        ).joinToString("\n")
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .semantics { contentDescription = description }
                    .background(contentColor.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
                    .border(1.dp, contentColor.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(12.dp)) {
                for ((index, height) in listOf(0.45f, 0.9f, 0.65f, 0.35f).withIndex()) {
                    drawRoundRect(
                        color = contentColor.copy(alpha = 0.85f),
                        topLeft = Offset(size.width * index / 4f, size.height * (1f - height) / 2f),
                        size = Size(size.width / 8f, size.height * height),
                        cornerRadius = CornerRadius(1.dp.toPx()),
                    )
                }
            }
            Text(codec, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (depth != null || sampleRate != null || bitrate != null) {
                Box(Modifier.width(1.dp).height(11.dp).background(contentColor.copy(alpha = 0.22f)))
            }
            for (detail in listOfNotNull(depth, sampleRate, bitrate)) {
                Text(
                    detail,
                    color = contentColor.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
