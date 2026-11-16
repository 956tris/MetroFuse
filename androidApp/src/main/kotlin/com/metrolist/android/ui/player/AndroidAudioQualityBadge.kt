package com.metrolist.android.ui.player

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.metrolist.android.deviceconnect.AndroidDeviceConnect
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.audio_quality_bit_depth
import com.metrolist.shared.generated.resources.audio_quality_bitrate
import com.metrolist.shared.generated.resources.audio_quality_sample_rate
import com.metrolist.shared.generated.resources.audio_quality_source_format
import com.metrolist.shared.generated.resources.close
import org.jetbrains.compose.resources.stringResource
import java.text.NumberFormat
import java.util.Locale

internal fun sourceAudioCodecLabel(format: Format): String? =
    when (val mime = format.sampleMimeType) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "OPUS"
        MimeTypes.AUDIO_VORBIS -> "VORBIS"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> mime?.removePrefix("audio/")?.uppercase(Locale.ROOT)
    }

internal fun sourceAudioBitDepth(format: Format): Int? =
    when (format.pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

@Composable
internal fun AndroidAudioQualityBadge(
    connection: PlayerConnection,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val source by connection.sourceAudioFormat.collectAsStateWithLifecycle()
    val loading by connection.isPlaybackLoading.collectAsStateWithLifecycle()
    val cast by connection.castState.collectAsStateWithLifecycle()
    val remote by AndroidDeviceConnect.manager.remote.state
        .collectAsStateWithLifecycle()
    val format = source.takeIf { !loading && !cast.isConnected && remote.sessionId == null } ?: return
    val codec = sourceAudioCodecLabel(format) ?: return
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 } }
    val details =
        listOfNotNull(
            codec,
            sourceAudioBitDepth(format)?.let { stringResource(Res.string.audio_quality_bit_depth, numbers.format(it)) },
            format.sampleRate.takeIf { it > 0 }?.let {
                stringResource(Res.string.audio_quality_sample_rate, numbers.format(it / 1000.0))
            },
            format.averageBitrate.takeIf { it > 0 }?.let {
                stringResource(Res.string.audio_quality_bitrate, numbers.format(it / 1000.0))
            },
        )
    val title = stringResource(Res.string.audio_quality_source_format)
    val description = title + ": " + details.joinToString(" · ")
    var showDetails by remember(format) { mutableStateOf(false) }
    Surface(
        onClick = { showDetails = true },
        color = contentColor.copy(alpha = 0.08f),
        contentColor = contentColor,
        shape = RoundedCornerShape(7.dp),
        modifier = modifier.padding(top = 4.dp).semantics { contentDescription = description },
    ) {
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(title) },
            text = { Text(details.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text(stringResource(Res.string.close)) }
            },
        )
    }
}
