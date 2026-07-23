/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.MetroMixBarsKey
import com.metrolist.music.constants.MetroMixEffectCurve
import com.metrolist.music.constants.MetroMixEffectCurveKey
import com.metrolist.music.constants.MetroMixEnabledKey
import com.metrolist.music.constants.MetroMixEqCurve
import com.metrolist.music.constants.MetroMixEqCurveKey
import com.metrolist.music.constants.MetroMixPreset
import com.metrolist.music.constants.MetroMixPresetKey
import com.metrolist.music.constants.MetroMixVolumeCurve
import com.metrolist.music.constants.MetroMixVolumeCurveKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.ui.utils.metroMixEffectCurveLabel
import com.metrolist.music.ui.utils.metroMixEqCurveLabel
import com.metrolist.music.ui.utils.metroMixVolumeCurveLabel
import com.metrolist.music.utils.mix.MixMetadata
import com.metrolist.music.utils.mix.MixMetadataResolver
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.metrolist.music.db.entities.SongTransitionEntity
import com.metrolist.music.playback.MusicService
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun MetroMixStudioDialog(
    current: MediaMetadata?,
    next: MediaMetadata?,
    onDismiss: () -> Unit,
) {
    var enabled by rememberPreference(MetroMixEnabledKey, defaultValue = false)
    var preset by rememberEnumPreference(MetroMixPresetKey, defaultValue = MetroMixPreset.AUTO)
    var bars by rememberPreference(MetroMixBarsKey, defaultValue = 8)
    var volumeCurve by rememberEnumPreference(MetroMixVolumeCurveKey, defaultValue = MetroMixVolumeCurve.AUTO)
    var eqCurve by rememberEnumPreference(MetroMixEqCurveKey, defaultValue = MetroMixEqCurve.AUTO)
    var effectCurve by rememberEnumPreference(MetroMixEffectCurveKey, defaultValue = MetroMixEffectCurve.AUTO)
    val spotifyCookie by rememberPreference(SpotifyCookieKey, defaultValue = "")
    val database = LocalDatabase.current

    val fetchedCurrent by produceState<MixMetadata?>(
        initialValue = null,
        key1 = current?.id,
        key2 = current?.bpm,
        key3 = spotifyCookie,
    ) {
        value =
            if (current?.bpm == null) {
                runCatching { current?.let { MixMetadataResolver.resolve(it, spotifyCookie) } }.getOrNull()
            } else {
                null
            }
    }
    val fetchedNext by produceState<MixMetadata?>(
        initialValue = null,
        key1 = next?.id,
        key2 = next?.bpm,
        key3 = spotifyCookie,
    ) {
        value =
            if (next?.bpm == null) {
                runCatching { next?.let { MixMetadataResolver.resolve(it, spotifyCookie) } }.getOrNull()
            } else {
                null
            }
    }

    LaunchedEffect(current?.id, fetchedCurrent) {
        database.persistExternalMixMetadata(current, fetchedCurrent)
    }
    LaunchedEffect(next?.id, fetchedNext) {
        database.persistExternalMixMetadata(next, fetchedNext)
    }

    val outgoing = current.withExternalMix(fetchedCurrent).toMixTrackInfo()
    val incoming = next.withExternalMix(fetchedNext).toMixTrackInfo()

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 760.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                StudioHeader(
                    onCancel = onDismiss,
                    onSave = {
                        if (current != null && next != null) {
                            scope.launch(Dispatchers.IO) {
                                database.query {
                                    upsert(
                                        SongTransitionEntity(
                                            outgoingSongId = current.id,
                                            incomingSongId = next.id,
                                            overlapBars = bars,
                                            mixTransitionStyleOverride = preset.name,
                                            volumeCurve = volumeCurve.name,
                                            eqTemplate = eqCurve.name,
                                            effectType = effectCurve.name,
                                            bpmA = current.bpm ?: fetchedCurrent?.bpm,
                                            bpmB = next.bpm ?: fetchedNext?.bpm,
                                        ),
                                    )
                                }
                            }
                        }
                        enabled = true
                        onDismiss()
                    },
                )

                Spacer(Modifier.height(18.dp))

                TrackHeaderRow(track = outgoing)
                Spacer(Modifier.height(10.dp))
                SpotifyWaveDeck(
                    outgoing = outgoing,
                    incoming = incoming,
                    preset = preset,
                    bars = bars.coerceIn(2, 32),
                    volumeCurve = volumeCurve,
                    eqCurve = eqCurve,
                    effectCurve = effectCurve,
                    onBarsChange = {
                        bars = it
                        enabled = true
                    },
                    hasBeatGrid = outgoing.hasBpm && incoming.hasBpm,
                )
                Spacer(Modifier.height(10.dp))
                BarsEditor(
                    bars = bars.coerceIn(2, 32),
                    onBarsChange = {
                        bars = it
                        enabled = true
                    },
                )
                Spacer(Modifier.height(10.dp))
                TrackHeaderRow(track = incoming)

                Spacer(Modifier.height(16.dp))

                MixControlPanel(
                    volumeCurve = volumeCurve,
                    eqCurve = eqCurve,
                    effectCurve = effectCurve,
                    onVolumeCurveChange = {
                        volumeCurve = it
                        enabled = true
                    },
                    onEqCurveChange = {
                        eqCurve = it
                        enabled = true
                    },
                    onEffectCurveChange = {
                        effectCurve = it
                        enabled = true
                    },
                )

                Spacer(Modifier.height(14.dp))

                PresetStrip(
                    preset = preset,
                    volumeCurve = volumeCurve,
                    eqCurve = eqCurve,
                    effectCurve = effectCurve,
                    onSelect = { option ->
                        preset = option.preset
                        bars = option.bars
                        volumeCurve = option.volumeCurve
                        eqCurve = option.eqCurve
                        effectCurve = option.effectCurve
                        enabled = true
                    },
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.metromix_metadata_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StudioHeader(
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.cancel))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(R.string.metromix_edit_transition),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = stringResource(R.string.metromix_beta),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        TextButton(onClick = onSave) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun TrackHeaderRow(track: MixTrackInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = track.bpmLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (track.hasBpm) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = track.keyLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = track.durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SpotifyWaveDeck(
    outgoing: MixTrackInfo,
    incoming: MixTrackInfo,
    preset: MetroMixPreset,
    bars: Int,
    volumeCurve: MetroMixVolumeCurve,
    eqCurve: MetroMixEqCurve,
    effectCurve: MetroMixEffectCurve,
    onBarsChange: (Int) -> Unit,
    hasBeatGrid: Boolean,
) {
    val surface = Color(0xFF111111)
    val wave = Color(0xFFDCDCDC)
    val grid = Color(0xFF4B4B4B)
    val beat = Color(0xFF1DB954)
    val volume = Color(0xFF29B6F6)
    val eq = Color(0xFFFFC107)
    val effect = Color(0xFFCE93D8)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(292.dp),
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(268.dp)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(14.dp))
                    .background(surface),
        ) {
            val effectivePreset = previewEffectivePreset(preset, volumeCurve, eqCurve, effectCurve)
            val previewWidth = size.width * previewTransitionFraction(bars, effectivePreset)
            val transitionLeft = (size.width - previewWidth) / 2f
            val transitionRight = transitionLeft + previewWidth
            val transitionWidth = transitionRight - transitionLeft
            val playheadX = transitionLeft + transitionWidth * previewPlayheadFraction(effectivePreset)
            val topCenter = size.height * 0.33f
            val bottomCenter = size.height * 0.67f
            val rowHeight = size.height * 0.20f
            val gridTop = topCenter - rowHeight - 12.dp.toPx()
            val gridBottom = bottomCenter + rowHeight + 12.dp.toPx()

            drawRoundRect(
                color = Color.White.copy(alpha = 0.07f),
                topLeft = Offset(transitionLeft, gridTop),
                size = Size(transitionWidth, gridBottom - gridTop),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            )
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(transitionLeft, gridTop),
                size = Size(transitionWidth, gridBottom - gridTop),
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )

            if (hasBeatGrid) {
                repeat(bars + 1) { index ->
                    val x = transitionLeft + transitionWidth * index / bars
                    drawLine(
                        color = grid.copy(alpha = if (index % 4 == 0) 0.82f else 0.48f),
                        start = Offset(x, gridTop),
                        end = Offset(x, gridBottom),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                repeat((bars * 4) + 1) { index ->
                    val x = transitionLeft + transitionWidth * index / (bars * 4)
                    val downbeat = index % 4 == 0
                    drawLine(
                        color = if (downbeat) beat.copy(alpha = 0.5f) else wave.copy(alpha = 0.22f),
                        start = Offset(x, size.height * 0.48f),
                        end = Offset(x, size.height * 0.53f),
                        strokeWidth = if (downbeat) 2.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                drawLine(
                    color = grid.copy(alpha = 0.7f),
                    start = Offset(transitionLeft, gridTop),
                    end = Offset(transitionLeft, gridBottom),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    color = grid.copy(alpha = 0.7f),
                    start = Offset(transitionRight, gridTop),
                    end = Offset(transitionRight, gridBottom),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            drawWaveformRow(
                centerY = topCenter,
                height = rowHeight,
                seed = outgoing.seed,
                color = wave,
                transitionLeft = transitionLeft,
                transitionRight = transitionRight,
                preset = effectivePreset,
                emphasizeEnd = true,
            )
            drawWaveformRow(
                centerY = bottomCenter,
                height = rowHeight,
                seed = incoming.seed + 31,
                color = wave,
                transitionLeft = transitionLeft,
                transitionRight = transitionRight,
                preset = effectivePreset,
                emphasizeEnd = false,
            )

            drawVolumeEnvelope(
                color = volume,
                startX = transitionLeft,
                endX = transitionRight,
                topCenter = topCenter,
                bottomCenter = bottomCenter,
                rowHeight = rowHeight,
                preset = effectivePreset,
            )
            drawEqPreview(
                color = eq,
                startX = transitionLeft,
                endX = transitionRight,
                topCenter = topCenter,
                bottomCenter = bottomCenter,
                rowHeight = rowHeight,
                eqCurve = eqCurve,
                preset = effectivePreset,
            )
            drawEffectPreview(
                color = effect,
                startX = transitionLeft,
                endX = transitionRight,
                centerY = bottomCenter,
                rowHeight = rowHeight,
                effectCurve = effectCurve,
                preset = effectivePreset,
            )

            drawLine(
                color = beat,
                start = Offset(playheadX, gridTop - 10.dp.toPx()),
                end = Offset(playheadX, gridBottom + 10.dp.toPx()),
                strokeWidth = 2.dp.toPx(),
            )

            drawRoundRect(
                color = Color.Black.copy(alpha = 0.92f),
                topLeft = Offset(playheadX - 34.dp.toPx(), size.height / 2f - 18.dp.toPx()),
                size = Size(68.dp.toPx(), 36.dp.toPx()),
                cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(playheadX - 6.dp.toPx(), size.height / 2f - 6.dp.toPx()),
                size = Size(12.dp.toPx(), 12.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }

        BarsSelector(
            bars = bars,
            hasBeatGrid = hasBeatGrid,
            onBarsChange = onBarsChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWaveformRow(
    centerY: Float,
    height: Float,
    seed: Int,
    color: Color,
    transitionLeft: Float,
    transitionRight: Float,
    preset: MetroMixPreset,
    emphasizeEnd: Boolean,
) {
    val count = 108
    val step = size.width / (count - 1)
    repeat(count) { index ->
        val x = index * step
        val normalized = index / (count - 1f)
        val envelope =
            if (emphasizeEnd) {
                (1f - normalized * 0.38f).coerceAtLeast(0.18f)
            } else {
                (0.2f + normalized * 0.55f).coerceAtMost(0.88f)
            }
        val wave =
            abs(sin(index * 0.42f + seed * 0.019f)) * 0.58f +
                abs(sin(index * 0.13f + seed * 0.007f)) * 0.34f
        val inTransition = x in transitionLeft..transitionRight
        val stroke = if (inTransition) previewWaveStroke(preset).dp.toPx() else 1.2.dp.toPx()
        val alpha = if (inTransition) previewWaveAlpha(preset, normalized, emphasizeEnd) else 0.42f
        val barHeight = (height * (0.24f + wave * envelope)).coerceIn(4.dp.toPx(), height)
        drawLine(
            color = color.copy(alpha = alpha),
            start = Offset(x, centerY - barHeight),
            end = Offset(x, centerY + barHeight),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVolumeEnvelope(
    color: Color,
    startX: Float,
    endX: Float,
    topCenter: Float,
    bottomCenter: Float,
    rowHeight: Float,
    preset: MetroMixPreset,
) {
    val steps = 56
    val distance = endX - startX
    var previousIncoming: Offset? = null
    var previousOutgoing: Offset? = null
    repeat(steps) { index ->
        val t = index / (steps - 1f)
        val x = startX + distance * t
        val (fadeIn, fadeOut) = previewVolumePair(t, preset)
        val incoming = Offset(x, bottomCenter + rowHeight * 0.92f - rowHeight * 1.84f * fadeIn)
        val outgoing = Offset(x, topCenter - rowHeight * 0.92f + rowHeight * 1.84f * (1f - fadeOut))

        previousIncoming?.let {
            drawLine(
                color = color,
                start = it,
                end = incoming,
                strokeWidth = 2.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        previousOutgoing?.let {
            drawLine(
                color = color.copy(alpha = 0.72f),
                start = it,
                end = outgoing,
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        previousIncoming = incoming
        previousOutgoing = outgoing
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEqPreview(
    color: Color,
    startX: Float,
    endX: Float,
    topCenter: Float,
    bottomCenter: Float,
    rowHeight: Float,
    eqCurve: MetroMixEqCurve,
    preset: MetroMixPreset,
) {
    val curve =
        when {
            eqCurve != MetroMixEqCurve.AUTO -> eqCurve
            preset == MetroMixPreset.BASS_SWAP || preset == MetroMixPreset.DROP -> MetroMixEqCurve.BASS_SWAP
            preset == MetroMixPreset.VOCAL_BLEND || preset == MetroMixPreset.RISE -> MetroMixEqCurve.VOCAL_SPACE
            preset == MetroMixPreset.CLUB_BLEND || preset == MetroMixPreset.BEAT_BLEND -> MetroMixEqCurve.FULL
            else -> MetroMixEqCurve.CLEAN
        }
    val centerY = (topCenter + bottomCenter) / 2f
    val steps = 48
    val distance = endX - startX
    var previous: Offset? = null
    repeat(steps) { index ->
        val t = index / (steps - 1f)
        val x = startX + distance * t
        val y =
            when (curve) {
                MetroMixEqCurve.CLEAN -> centerY
                MetroMixEqCurve.BASS_SWAP -> centerY + rowHeight * 0.48f * sin(t * PI).toFloat()
                MetroMixEqCurve.VOCAL_SPACE -> centerY - rowHeight * 0.42f * sin(t * PI).toFloat()
                MetroMixEqCurve.FULL -> centerY + rowHeight * 0.34f * sin(t * PI * 3f).toFloat()
                MetroMixEqCurve.AUTO -> centerY
            }
        val next = Offset(x, y)
        previous?.let {
            drawLine(
                color = color.copy(alpha = 0.86f),
                start = it,
                end = next,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        previous = next
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEffectPreview(
    color: Color,
    startX: Float,
    endX: Float,
    centerY: Float,
    rowHeight: Float,
    effectCurve: MetroMixEffectCurve,
    preset: MetroMixPreset,
) {
    val curve =
        when {
            effectCurve != MetroMixEffectCurve.AUTO -> effectCurve
            preset == MetroMixPreset.ECHO_OUT -> MetroMixEffectCurve.ECHO
            preset == MetroMixPreset.RISE || preset == MetroMixPreset.LOOP_OUT -> MetroMixEffectCurve.FILTER
            preset == MetroMixPreset.BEAT_BLEND -> MetroMixEffectCurve.WAVE
            else -> MetroMixEffectCurve.NONE
        }
    if (curve == MetroMixEffectCurve.NONE) return

    val steps = 36
    val distance = endX - startX
    var previous: Offset? = null
    repeat(steps) { index ->
        val t = index / (steps - 1f)
        val x = startX + distance * t
        val y =
            when (curve) {
                MetroMixEffectCurve.FILTER -> centerY + rowHeight * (0.72f - 1.34f * t)
                MetroMixEffectCurve.ECHO -> centerY - rowHeight * 0.52f * (1f - t) * abs(sin(t * PI * 9f)).toFloat()
                MetroMixEffectCurve.WAVE -> centerY + rowHeight * 0.48f * sin(t * PI * 4f).toFloat()
                MetroMixEffectCurve.AUTO,
                MetroMixEffectCurve.NONE -> centerY
            }
        val next = Offset(x, y)
        previous?.let {
            drawLine(
                color = color.copy(alpha = 0.8f),
                start = it,
                end = next,
                strokeWidth = 2.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (curve == MetroMixEffectCurve.ECHO && index % 6 == 0) {
            drawCircle(
                color = color.copy(alpha = 0.72f * (1f - t).coerceAtLeast(0.2f)),
                radius = (2.4f + 4f * (1f - t)).dp.toPx(),
                center = next,
            )
        }
        previous = next
    }
}

private fun previewEffectivePreset(
    preset: MetroMixPreset,
    volumeCurve: MetroMixVolumeCurve,
    eqCurve: MetroMixEqCurve,
    effectCurve: MetroMixEffectCurve,
): MetroMixPreset =
    when {
        effectCurve == MetroMixEffectCurve.ECHO -> MetroMixPreset.ECHO_OUT
        effectCurve == MetroMixEffectCurve.WAVE -> MetroMixPreset.BEAT_BLEND
        eqCurve == MetroMixEqCurve.BASS_SWAP -> MetroMixPreset.BASS_SWAP
        eqCurve == MetroMixEqCurve.VOCAL_SPACE -> MetroMixPreset.VOCAL_BLEND
        volumeCurve == MetroMixVolumeCurve.PUNCHY -> MetroMixPreset.ENERGY_MATCH
        volumeCurve == MetroMixVolumeCurve.MELT -> MetroMixPreset.LOOP_OUT
        volumeCurve == MetroMixVolumeCurve.WAVE -> MetroMixPreset.BEAT_BLEND
        volumeCurve == MetroMixVolumeCurve.BALANCED && preset == MetroMixPreset.AUTO -> MetroMixPreset.SMART_DJ
        preset == MetroMixPreset.AUTO -> MetroMixPreset.SMART_DJ
        else -> preset
    }

private fun previewTransitionFraction(
    bars: Int,
    preset: MetroMixPreset,
): Float {
    val base = (0.28f + bars.coerceIn(2, 32) / 32f * 0.58f).coerceIn(0.32f, 0.88f)
    val presetScale =
        when (preset) {
            MetroMixPreset.QUICK_CUT,
            MetroMixPreset.DROP,
            MetroMixPreset.RADIO_EDIT -> 0.78f
            MetroMixPreset.CLUB_BLEND,
            MetroMixPreset.BEAT_BLEND,
            MetroMixPreset.LONG_BLEND -> 1.08f
            else -> 1f
        }
    return (base * presetScale).coerceIn(0.28f, 0.9f)
}

private fun previewPlayheadFraction(preset: MetroMixPreset): Float =
    when (preset) {
        MetroMixPreset.DROP,
        MetroMixPreset.QUICK_CUT -> 0.42f
        MetroMixPreset.VOCAL_BLEND -> 0.64f
        MetroMixPreset.RISE,
        MetroMixPreset.ECHO_OUT -> 0.58f
        else -> 0.52f
    }

private fun previewWaveStroke(preset: MetroMixPreset): Float =
    when (preset) {
        MetroMixPreset.DROP,
        MetroMixPreset.QUICK_CUT -> 2.2f
        MetroMixPreset.BEAT_BLEND,
        MetroMixPreset.CLUB_BLEND -> 2.0f
        else -> 1.8f
    }

private fun previewWaveAlpha(
    preset: MetroMixPreset,
    normalized: Float,
    outgoing: Boolean,
): Float =
    when (preset) {
        MetroMixPreset.DROP,
        MetroMixPreset.QUICK_CUT -> if (normalized in 0.38f..0.62f) 1f else 0.72f
        MetroMixPreset.VOCAL_BLEND -> if (outgoing) 0.64f else 0.96f
        MetroMixPreset.RISE -> if (outgoing) 0.7f else 1f
        else -> 0.92f
    }

private fun previewVolumePair(
    progress: Float,
    preset: MetroMixPreset,
): Pair<Float, Float> {
    val p = progress.coerceIn(0f, 1f)
    fun smooth(value: Float) = value * value * (3f - 2f * value)
    fun easeOut(value: Float) = 1f - (1f - value) * (1f - value)
    fun easeIn(value: Float) = value * value
    fun range(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)
    fun equalPowerIn(value: Float) = sin(value.coerceIn(0f, 1f) * (PI / 2.0)).toFloat()
    fun equalPowerOut(value: Float) = cos(value.coerceIn(0f, 1f) * (PI / 2.0)).toFloat()
    fun pair(fadeIn: Float, fadeOut: Float): Pair<Float, Float> = fadeIn.coerceIn(0f, 1f) to fadeOut.coerceIn(0f, 1f)
    return when (preset) {
        MetroMixPreset.SMART_DJ,
        MetroMixPreset.SMOOTH,
        MetroMixPreset.AUTO -> pair(equalPowerIn(smooth(p)), equalPowerOut(smooth(p)))
        MetroMixPreset.BEAT_BLEND,
        MetroMixPreset.CLUB_BLEND,
        MetroMixPreset.LONG_BLEND -> pair(
            equalPowerIn(smooth(range(p, 0.05f, 0.95f))),
            equalPowerOut(smooth(range(p, 0.05f, 0.95f))),
        )
        MetroMixPreset.ENERGY_MATCH,
        MetroMixPreset.RISE -> pair(easeOut(range(p, 0.02f, 0.92f)), 1f - easeIn(range(p, 0.08f, 1f)))
        MetroMixPreset.VOCAL_BLEND -> pair(smooth(range(p, 0.34f, 1f)), 1f - smooth(range(p, 0.08f, 0.76f)))
        MetroMixPreset.BASS_SWAP -> pair(easeOut(range(p, 0.18f, 0.74f)), 1f - smooth(range(p, 0.38f, 0.80f)))
        MetroMixPreset.RADIO_EDIT,
        MetroMixPreset.FADE,
        MetroMixPreset.BLEND -> pair(smooth(p), 1f - smooth(p))
        MetroMixPreset.QUICK_CUT,
        MetroMixPreset.DROP -> {
            val delayed = range(p, 0.28f, 0.62f)
            pair(smooth(delayed), if (p < 0.44f) 1f else 1f - smooth(delayed))
        }
        MetroMixPreset.LOOP_OUT -> pair(equalPowerIn(smooth(p)), (1f - smooth(range(p, 0.10f, 1f))) * (1f - 0.15f * p))
        MetroMixPreset.ECHO_OUT -> {
            val remaining = 1f - p
            pair(smooth(p), remaining * remaining * remaining)
        }
    }
}

@Composable
private fun BarsEditor(
    bars: Int,
    onBarsChange: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.metromix_transition_bars),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$bars bars",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = bars.toFloat(),
            onValueChange = { onBarsChange(it.roundToInt().coerceIn(2, 32)) },
            valueRange = 2f..32f,
            steps = 29,
        )
    }
}

@Composable
private fun BarsSelector(
    bars: Int,
    hasBeatGrid: Boolean,
    onBarsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(
            selected = false,
            onClick = { expanded = true },
            label = {
                Text(if (hasBeatGrid) "$bars bars" else stringResource(R.string.metromix_bars_unavailable))
            },
            shape = RoundedCornerShape(8.dp),
            colors =
                FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(2, 4, 8, 16, 32).forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option bars") },
                    onClick = {
                        onBarsChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MixControlPanel(
    volumeCurve: MetroMixVolumeCurve,
    eqCurve: MetroMixEqCurve,
    effectCurve: MetroMixEffectCurve,
    onVolumeCurveChange: (MetroMixVolumeCurve) -> Unit,
    onEqCurveChange: (MetroMixEqCurve) -> Unit,
    onEffectCurveChange: (MetroMixEffectCurve) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f))
                .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MixControlDropdown(
            title = stringResource(R.string.metromix_volume_curve),
            accent = MaterialTheme.colorScheme.primary,
            values = MetroMixVolumeCurve.values().toList(),
            selected = volumeCurve,
            label = { metroMixVolumeCurveLabel(it) },
            onSelected = onVolumeCurveChange,
            modifier = Modifier.weight(1f),
        )
        MixControlDropdown(
            title = stringResource(R.string.metromix_eq_curve),
            accent = Color(0xFFFFC107),
            values = MetroMixEqCurve.values().toList(),
            selected = eqCurve,
            label = { metroMixEqCurveLabel(it) },
            onSelected = onEqCurveChange,
            modifier = Modifier.weight(1f),
        )
        MixControlDropdown(
            title = stringResource(R.string.metromix_effect_curve),
            accent = Color(0xFFCE93D8),
            values = MetroMixEffectCurve.values().toList(),
            selected = effectCurve,
            label = { metroMixEffectCurveLabel(it) },
            onSelected = onEffectCurveChange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun <T> MixControlDropdown(
    title: String,
    accent: Color,
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label(selected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(label(value)) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetStrip(
    preset: MetroMixPreset,
    volumeCurve: MetroMixVolumeCurve,
    eqCurve: MetroMixEqCurve,
    effectCurve: MetroMixEffectCurve,
    onSelect: (MetroMixStudioPreset) -> Unit,
) {
    val options =
        listOf(
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_auto),
                preset = MetroMixPreset.AUTO,
                bars = 8,
                volumeCurve = MetroMixVolumeCurve.AUTO,
                eqCurve = MetroMixEqCurve.AUTO,
                effectCurve = MetroMixEffectCurve.AUTO,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_custom),
                preset = MetroMixPreset.SMART_DJ,
                bars = 8,
                volumeCurve = MetroMixVolumeCurve.BALANCED,
                eqCurve = MetroMixEqCurve.CLEAN,
                effectCurve = MetroMixEffectCurve.NONE,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_fade),
                preset = MetroMixPreset.FADE,
                bars = 4,
                volumeCurve = MetroMixVolumeCurve.BALANCED,
                eqCurve = MetroMixEqCurve.CLEAN,
                effectCurve = MetroMixEffectCurve.NONE,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_rise),
                preset = MetroMixPreset.RISE,
                bars = 8,
                volumeCurve = MetroMixVolumeCurve.PUNCHY,
                eqCurve = MetroMixEqCurve.VOCAL_SPACE,
                effectCurve = MetroMixEffectCurve.FILTER,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_blend),
                preset = MetroMixPreset.BLEND,
                bars = 16,
                volumeCurve = MetroMixVolumeCurve.BALANCED,
                eqCurve = MetroMixEqCurve.FULL,
                effectCurve = MetroMixEffectCurve.NONE,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_curve_wave),
                preset = MetroMixPreset.BEAT_BLEND,
                bars = 16,
                volumeCurve = MetroMixVolumeCurve.WAVE,
                eqCurve = MetroMixEqCurve.BASS_SWAP,
                effectCurve = MetroMixEffectCurve.WAVE,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_curve_melt),
                preset = MetroMixPreset.LOOP_OUT,
                bars = 8,
                volumeCurve = MetroMixVolumeCurve.MELT,
                eqCurve = MetroMixEqCurve.BASS_SWAP,
                effectCurve = MetroMixEffectCurve.FILTER,
            ),
            MetroMixStudioPreset(
                label = stringResource(R.string.metromix_preset_slam),
                preset = MetroMixPreset.DROP,
                bars = 2,
                volumeCurve = MetroMixVolumeCurve.PUNCHY,
                eqCurve = MetroMixEqCurve.BASS_SWAP,
                effectCurve = MetroMixEffectCurve.NONE,
            ),
        )
    val selected =
        options.firstOrNull {
            it.preset == preset &&
                it.volumeCurve == volumeCurve &&
                it.eqCurve == eqCurve &&
                it.effectCurve == effectCurve
        } ?: options[1]

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = option.label,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
        Spacer(Modifier.width(1.dp))
    }
}

@Composable
private fun MediaMetadata?.toMixTrackInfo(): MixTrackInfo {
    if (this == null) {
        return MixTrackInfo(
            title = "-",
            artist = "-",
            thumbnailUrl = null,
            bpmLabel = "-- bpm",
            keyLabel = "--",
            durationLabel = "--:--",
            hasBpm = false,
            seed = 1,
        )
    }
    val metadataBpm = bpm?.takeIf { it in 40f..240f }
    return MixTrackInfo(
        title = title,
        artist = artists.joinToString { it.name }.ifBlank { "-" },
        thumbnailUrl = thumbnailUrl,
        bpmLabel = metadataBpm?.roundToInt()?.let { "$it bpm" } ?: "-- bpm",
        keyLabel = keySignature?.toCamelotKey() ?: "--",
        durationLabel = duration.toDurationLabel(),
        hasBpm = metadataBpm != null,
        seed = title.hashCode(),
    )
}

private fun MediaMetadata?.withExternalMix(metadata: MixMetadata?): MediaMetadata? {
    if (this == null || metadata == null) return this
    val resolvedBpm = bpm ?: metadata.bpm
    val resolvedKey = keySignature ?: metadata.keySignature
    if (resolvedBpm == bpm && resolvedKey == keySignature) return this
    return copy(
        bpm = resolvedBpm,
        keySignature = resolvedKey,
        mixMetadataSource = metadata.source,
    )
}

private fun MusicDatabase.persistExternalMixMetadata(
    mediaMetadata: MediaMetadata?,
    metadata: MixMetadata?,
) {
    if (mediaMetadata == null || metadata == null) return
    if (metadata.bpm == null && metadata.keySignature == null) return
    query {
        val existing = getSongByIdBlocking(mediaMetadata.id)
        if (existing != null) {
            update(
                existing.song.copy(
                    bpm = existing.song.bpm ?: metadata.bpm,
                    keySignature = existing.song.keySignature ?: metadata.keySignature,
                    mixMetadataSource = existing.song.mixMetadataSource ?: metadata.source,
                ),
            )
        } else {
            insert(
                mediaMetadata
                    .withExternalMix(metadata)
                    ?.copy(mixMetadataSource = metadata.source) ?: mediaMetadata,
            )
        }
    }
}

private fun String.toCamelotKey(): String? {
    val normalized =
        trim()
            .replace("minor", "m", ignoreCase = true)
            .replace("major", "", ignoreCase = true)
            .replace("maj", "", ignoreCase = true)
            .replace("min", "m", ignoreCase = true)
            .replace(" ", "")
            .replace('\u266F', '#')
            .replace('\u266D', 'b')
            .uppercase()
    if (Regex("""^(?:[1-9]|1[0-2])[AB]$""").matches(normalized)) return normalized
    return when (normalized) {
        "G#M", "ABM" -> "1A"
        "B" -> "1B"
        "D#M", "EBM" -> "2A"
        "F#" -> "2B"
        "A#M", "BBM" -> "3A"
        "C#" -> "3B"
        "FM" -> "4A"
        "AB" -> "4B"
        "CM" -> "5A"
        "EB" -> "5B"
        "GM" -> "6A"
        "BB" -> "6B"
        "DM" -> "7A"
        "F" -> "7B"
        "AM" -> "8A"
        "C" -> "8B"
        "EM" -> "9A"
        "G" -> "9B"
        "BM" -> "10A"
        "D" -> "10B"
        "F#M", "GBM" -> "11A"
        "A" -> "11B"
        "C#M", "DBM" -> "12A"
        "E" -> "12B"
        else -> takeIf { isNotBlank() }?.take(8)
    }
}

private fun Int.toDurationLabel(): String {
    if (this <= 0) return "--:--"
    val minutes = this / 60
    val seconds = this % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private data class MixTrackInfo(
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val bpmLabel: String,
    val keyLabel: String,
    val durationLabel: String,
    val hasBpm: Boolean,
    val seed: Int,
)

private data class MetroMixStudioPreset(
    val label: String,
    val preset: MetroMixPreset,
    val bars: Int,
    val volumeCurve: MetroMixVolumeCurve,
    val eqCurve: MetroMixEqCurve,
    val effectCurve: MetroMixEffectCurve,
)
