/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.WordTimestamp
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.ui.screens.settings.LyricsPosition
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

private data class HyphenGroupWord(
    val pos: Int,
    val size: Int,
    val isLast: Boolean,
    val groupStartMs: Long,
    val groupEndMs: Long
)

private data class SustainedWordMotion(
    val scaleX: Float,
    val scaleY: Float,
    val risePx: Float,
    val glow: Float,
    val offsetX: Float,
)

private val NoSustainedWordMotion = SustainedWordMotion(0f, 0f, 0f, 0f, 0f)

private fun String.containsRtl(): Boolean {
    for (c in this) {
        val directionality = Character.getDirectionality(c).toInt()
        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT.toInt() ||
            directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC.toInt()
        ) {
            return true
        }
    }
    return false
}

/**
 * Splits a string into Unicode grapheme clusters using BreakIterator.
 * This correctly handles Devanagari, Bengali, Arabic, Hangul, emoji, etc.
 * where a single visible glyph is composed of multiple code points (e.g. base
 * consonant + matra + anusvara = one cluster, not three separate chars).
 */
private fun String.toGraphemeClusters(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(this)
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        result.add(substring(start, end))
        start = end
        end = it.next()
    }
    return result
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LyricsLine(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricsTextSize: Float,
    lyricsLineSpacing: Float,
    expressiveAccent: Color,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    appleMusicStyle: Boolean = false,
    lineEndTime: Long = Long.MAX_VALUE,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    if (appleMusicStyle) {
        SpicyLyricsLine(
            index = index,
            item = item,
            isSynced = isSynced,
            isActiveLine = isActiveLine,
            bgVisible = bgVisible,
            isSelected = isSelected,
            isSelectionModeActive = isSelectionModeActive,
            currentPositionState = currentPositionState,
            lyricsOffset = lyricsOffset,
            playerConnection = playerConnection,
            lyricsTextSize = lyricsTextSize,
            expressiveAccent = expressiveAccent,
            lyricsTextPosition = lyricsTextPosition,
            respectAgentPositioning = respectAgentPositioning,
            isAutoScrollEnabled = isAutoScrollEnabled,
            displayedCurrentLineIndex = displayedCurrentLineIndex,
            romanizeAsMain = romanizeAsMain,
            enabledLanguages = enabledLanguages,
            romanizeLyrics = romanizeLyrics,
            lineEndTime = lineEndTime,
            onSizeChanged = onSizeChanged,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
        )
        return
    }
    
    val itemModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
        .padding(
            start = when {
                appleMusicStyle -> 22.dp
                lyricsTextPosition == LyricsPosition.CENTER -> 24.dp
                else -> 11.dp
            },
            end = when {
                appleMusicStyle -> 22.dp
                lyricsTextPosition == LyricsPosition.CENTER -> 24.dp
                else -> 11.dp
            },
            top = when {
                item.isBackground -> 0.dp
                appleMusicStyle -> 10.dp
                else -> 12.dp
            },
            bottom = when {
                item.isBackground -> 2.dp
                appleMusicStyle -> 10.dp
                else -> 12.dp
            }
        )

    val agentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.Start
        respectAgentPositioning && item.agent == "v2" -> Alignment.End
        respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
        item.isBackground -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }
    
    val agentTextAlign = when {
        respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
        respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
        respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
        item.isBackground -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    Box(modifier = itemModifier, contentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
        respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
        item.isBackground -> Alignment.Center
        respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.CenterStart
            LyricsPosition.RIGHT -> Alignment.CenterEnd
            LyricsPosition.CENTER -> Alignment.Center
        }
    }) {
        @Composable
        fun LyricContent() {
            val distanceFromCurrent = if (displayedCurrentLineIndex >= 0) abs(index - displayedCurrentLineIndex) else Int.MAX_VALUE
            val activeScale by animateFloatAsState(
                targetValue =
                    when {
                        !appleMusicStyle || item.isBackground -> 1f
                        isActiveLine -> 1.075f
                        distanceFromCurrent <= 1 -> 0.965f
                        else -> 0.925f
                    },
                tween(420),
                label = "appleLyricsScale",
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (appleMusicStyle && !item.isBackground) {
                                scaleX = activeScale
                                scaleY = activeScale
                                transformOrigin =
                                    when (agentTextAlign) {
                                        TextAlign.Left -> TransformOrigin(0f, 0.5f)
                                        TextAlign.Right -> TransformOrigin(1f, 0.5f)
                                        else -> TransformOrigin(0.5f, 0.5f)
                                    }
                            }
                        },
                horizontalAlignment = agentAlignment,
            ) {
                val inactiveAlpha =
                    when {
                        item.isBackground -> if (appleMusicStyle) 0.14f else 0.08f
                        appleMusicStyle -> 0.12f
                        else -> 0.2f
                    }
                val activeAlpha = 1f
                val focusedAlpha =
                    when {
                        item.isBackground -> if (appleMusicStyle) 0.58f else 0.5f
                        appleMusicStyle -> 0.52f
                        else -> 0.3f
                    }
                val targetAlpha = if (item.isBackground || isActiveLine) {
                    activeAlpha
                } else if (isAutoScrollEnabled && displayedCurrentLineIndex >= 0) {
                    when (distanceFromCurrent) {
                        0 -> focusedAlpha
                        1 -> if (appleMusicStyle) 0.46f else 0.2f
                        2 -> if (appleMusicStyle) 0.34f else 0.2f
                        3 -> if (appleMusicStyle) 0.24f else 0.15f
                        4 -> if (appleMusicStyle) 0.16f else 0.1f
                        else -> if (appleMusicStyle) 0.1f else 0.08f
                    }
                } else inactiveAlpha
                
                val animatedAlpha by animateFloatAsState(targetAlpha, tween(if (appleMusicStyle) 700 else 250), label = "lyricsLineAlpha")
                val lineColor = expressiveAccent.copy(alpha = if (item.isBackground) focusedAlpha else animatedAlpha)
                
                val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
                val isRomanizedAvailable = romanizedTextState != null
                val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
                val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
                val mainText = if (item.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
                val subText = if (item.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

                val lyricStyle = TextStyle(
                    fontSize = if (item.isBackground) (lyricsTextSize * (if (appleMusicStyle) 0.56f else 0.7f)).sp else lyricsTextSize.sp,
                    fontWeight =
                        when {
                            item.isBackground -> FontWeight.SemiBold
                            appleMusicStyle -> FontWeight.Black
                            else -> FontWeight.Bold
                        },
                    fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
                    lineHeight = if (item.isBackground) (lyricsTextSize * (if (appleMusicStyle) 0.56f else 0.7f) * lyricsLineSpacing).sp else (lyricsTextSize * lyricsLineSpacing).sp,
                    letterSpacing = if (appleMusicStyle) 0.sp else (-0.5).sp,
                    textAlign = agentTextAlign,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )

                val effectiveWords = if (item.words?.isNotEmpty() == true) {
                    item.words
                } else if (mainText != null) {
                    remember(mainText, item.time) {
                        val words = mainText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val wordDurationSec = 0.18
                        val wordStaggerSec = 0.03
                        val startTimeSec = item.time / 1000.0
                        words.mapIndexed { idx, wordText ->
                            WordTimestamp(
                                text = wordText,
                                startTime = startTimeSec + (idx * wordStaggerSec),
                                endTime = startTimeSec + (idx * wordStaggerSec) + wordDurationSec,
                                hasTrailingSpace = idx < words.size - 1
                            )
                        }
                    }
                } else null

                if (isSynced && effectiveWords != null && (isActiveLine || abs(index - displayedCurrentLineIndex) <= 3) && mainText != null) {
                    WordLevelLyrics(
                        mainText = mainText,
                        words = effectiveWords,
                        isActiveLine = isActiveLine,
                        currentPositionState = currentPositionState,
                        lyricsOffset = lyricsOffset,
                        playerConnection = playerConnection,
                        lyricStyle = lyricStyle,
                        lineColor = lineColor,
                        expressiveAccent = expressiveAccent,
                        isBackground = item.isBackground,
                        focusedAlpha = focusedAlpha,
                        alignment = agentTextAlign,
                        appleMusicStyle = appleMusicStyle,
                    )
                } else {
                    Text(
                        text = mainText ?: "",
                        style = lyricStyle.copy(color = if (isActiveLine) expressiveAccent else lineColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                    subText?.let { 
                        Text(
                            text = it,
                            fontSize = if (appleMusicStyle) (lyricsTextSize * 0.42f).sp else 18.sp,
                            color = expressiveAccent.copy(alpha = 0.6f),
                            textAlign = agentTextAlign,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                
                val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
                transText?.let { 
                    Text(
                        text = it,
                        fontSize = if (appleMusicStyle) (lyricsTextSize * 0.38f).sp else 16.sp,
                        color = expressiveAccent.copy(alpha = 0.5f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (item.isBackground) {
            AnimatedVisibility(
                visible = bgVisible,
                enter = fadeIn(tween(durationMillis = 250, delayMillis = 100)),
                exit = fadeOut(tween(250))
            ) {
                LyricContent()
            }
        } else {
            LyricContent()
        }
    }
}

@Composable
private fun WordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricStyle: TextStyle,
    lineColor: Color,
    expressiveAccent: Color,
    isBackground: Boolean,
    focusedAlpha: Float,
    alignment: TextAlign,
    appleMusicStyle: Boolean,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
        }
    }
    
    var smoothPosition by remember { mutableLongStateOf(currentPositionState + lyricsOffset) }
    
    LaunchedEffect(isActiveLine) {
        if (isActiveLine) {
            var lastPlayerPos = playerConnection.player.currentPosition
            var lastUpdateTime = System.currentTimeMillis()
            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    val playerPos = playerConnection.player.currentPosition
                    if (playerPos != lastPlayerPos) {
                        lastPlayerPos = playerPos
                        lastUpdateTime = now
                    }
                    val elapsed = now - lastUpdateTime
                    smoothPosition = lastPlayerPos + lyricsOffset + (if (playerConnection.player.isPlaying) elapsed else 0)
                }
            }
        }
    }
    
    LaunchedEffect(isActiveLine, currentPositionState) {
        if (!isActiveLine) {
            smoothPosition = currentPositionState + lyricsOffset
        }
    }

    val (effectiveWords, effectiveToOriginalIdx) = remember(words, isBackground) {
        words.flatMapIndexed { originalIdx, word ->
            val shouldSplit = word.text.contains('-') && word.text.length > 1 &&
                (!word.hasTrailingSpace || words.size == 1)
            if (shouldSplit) {
                val segments = mutableListOf<String>()
                var start = 0
                for (i in 0 until word.text.length) {
                    if (word.text[i] == '-') {
                        segments.add(word.text.substring(start, i + 1))
                        start = i + 1
                    }
                }
                if (start < word.text.length) {
                    segments.add(word.text.substring(start))
                }

                if (segments.size > 1) {
                    val totalDuration = word.endTime - word.startTime
                    val segmentDuration = totalDuration / segments.size
                    segments.mapIndexed { index, segmentText ->
                        WordTimestamp(
                            text = segmentText,
                            startTime = word.startTime + index * segmentDuration,
                            endTime = word.startTime + (index + 1) * segmentDuration,
                            hasTrailingSpace = if (index == segments.size - 1) word.hasTrailingSpace else false
                        ) to originalIdx
                    }
                } else listOf(word to originalIdx)
            } else listOf(word to originalIdx)
        }.let { data -> data.map { it.first } to data.map { it.second } }
    }

    // Break mainText into grapheme clusters so that multi-codepoint glyphs
    // (Devanagari/Bengali matras, Arabic ligatures, emoji, etc.) are treated
    // as single units throughout the animation pipeline.
    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size
    // For each cluster index, the String offset (Char index) of its first character in mainText.
    // Required because TextLayoutResult.getBoundingBox/getLineForOffset take
    // String offsets (UTF-16/Char indices), not cluster indices.
    val clusterCharOffsets = remember(mainText) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    // wordIdxMap / charInWordMap / wordLenMap are now sized and indexed by
    // CLUSTER INDEX (not codepoint index) so that each visual glyph unit is
    // mapped to exactly one word slot.
    val charToWordData = remember(mainText, effectiveWords, isBackground, graphemeClusters, clusterCharOffsets) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount) { 0 }
        val wordLenMap = IntArray(clusterCount) { 1 }
        var currentPos = 0
        var clCursor = 0
        effectiveWords.forEachIndexed { wordIdx, word ->
            val rawWordText = word.text.let {
                if (isBackground) {
                    var t = it
                    if (wordIdx == 0) t = t.removePrefix("(")
                    if (wordIdx == effectiveWords.size - 1) t = t.removeSuffix(")")
                    t
                } else it
            }
            val indexInMain = mainText.indexOf(rawWordText, currentPos)
            if (indexInMain != -1) {
                val wordEndInMain = indexInMain + rawWordText.length
                // Advance clCursor to the first cluster at or after indexInMain
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < indexInMain) {
                    clCursor++
                }
                val firstClIdx = clCursor
                // Collect all clusters in the word range [indexInMain, wordEndInMain)
                val wordClusterIndices = mutableListOf<Int>()
                while (clCursor < clusterCount && clusterCharOffsets[clCursor] < wordEndInMain) {
                    wordClusterIndices.add(clCursor)
                    clCursor++
                }
                val wordClusterLen = wordClusterIndices.size
                wordClusterIndices.forEachIndexed { posInWord, clIdx ->
                    wordIdxMap[clIdx] = wordIdx
                    charInWordMap[clIdx] = posInWord
                    wordLenMap[clIdx] = wordClusterLen
                }
                // Check the cluster at clCursor for a trailing space
                if (clCursor < clusterCount && clusterCharOffsets[clCursor] == wordEndInMain && 
                    wordEndInMain < mainText.length && mainText[wordEndInMain] == ' ') {
                    val spaceClIdx = clCursor
                    wordIdxMap[spaceClIdx] = wordIdx
                    charInWordMap[spaceClIdx] = wordClusterLen
                    wordLenMap[spaceClIdx] = wordClusterLen + 1
                    clCursor++
                }
                currentPos = wordEndInMain
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    val hyphenGroupData = remember(effectiveWords) {
        val map = mutableMapOf<Int, HyphenGroupWord>()
        var currentGroup = mutableListOf<Int>()
        effectiveWords.forEachIndexed { wordIdx, word ->
            currentGroup.add(wordIdx)
            if (!word.text.endsWith("-")) {
                if (currentGroup.size > 1) {
                    val groupSize = currentGroup.size
                    val groupStartMs = (effectiveWords[currentGroup.first()].startTime * 1000).toLong()
                    val groupEndMs = (word.endTime * 1000).toLong()
                    currentGroup.forEachIndexed { pos, idx ->
                        map[idx] = HyphenGroupWord(pos, groupSize, pos == groupSize - 1, groupStartMs, groupEndMs)
                    }
                }
                currentGroup = mutableListOf()
            }
        }
        map
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true
            )
        }
        
        // Each layout corresponds to one grapheme cluster (the visual unit),
        // not one codepoint. Fixes Devanagari/Bengali matra fragmentation.
        val letterLayouts = remember(mainText, lyricStyle) {
            graphemeClusters.map { cluster -> textMeasurer.measure(cluster, lyricStyle) }
        }
        
        val isRtlText = remember(mainText) { mainText.containsRtl() }
        
        Canvas(modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { layoutResult.size.height.toDp() })
            .graphicsLayer(
                clip = false,
                compositingStrategy = CompositingStrategy.Offscreen,
            )
        ) {
            if (mainText.isEmpty()) return@Canvas
            if (!isActiveLine) {
                drawText(layoutResult, color = lineColor)
            } else {
                if (isRtlText) {
                    val (wordIdxMap, _, _) = charToWordData
                    val wordFactors = effectiveWords.map { word ->
                        val wStartMs = (word.startTime * 1000).toLong()
                        val wEndMs = (word.endTime * 1000).toLong()
                        val isWordSung = smoothPosition > wEndMs
                        val isWordActive = smoothPosition in wStartMs..wEndMs
                        val sungFactor = if (isWordSung) 1f 
                                        else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                        else 0f
                        Triple(sungFactor, isWordSung, isWordActive)
                    }

                    drawText(layoutResult, color = lineColor.copy(alpha = focusedAlpha))

                    effectiveWords.indices.forEach { wIdx ->
                        val (sungFactor, isWordSung, isWordActive) = wordFactors[wIdx]
                        
                        var left = Float.MAX_VALUE
                        var right = Float.MIN_VALUE
                        var top = Float.MAX_VALUE
                        var bottom = Float.MIN_VALUE
                        var found = false

                        for (i in 0 until clusterCount) {
                            if (wordIdxMap[i] == wIdx) {
                                val charOffset = clusterCharOffsets[i]
                                val bounds = layoutResult.getBoundingBox(charOffset)
                                left = minOf(left, bounds.left)
                                right = maxOf(right, bounds.right)
                                top = minOf(top, bounds.top)
                                bottom = maxOf(bottom, bounds.bottom)
                                found = true
                            }
                        }

                        if (found) {
                            if (isWordSung) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent)
                                }
                            } else if (isWordActive && sungFactor > 0f) {
                                clipRect(left = left, top = top, right = right, bottom = bottom) {
                                    drawText(layoutResult, color = expressiveAccent.copy(alpha = focusedAlpha + (1f - focusedAlpha) * sungFactor))
                                }
                            }
                        }
                    }
                    return@Canvas
                }

                val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
                val wordFactors = effectiveWords.map { word ->
                    val wStartMs = (word.startTime * 1000).toLong()
                    val wEndMs = (word.endTime * 1000).toLong()
                    val isWordSung = smoothPosition > wEndMs
                    val isWordActive = smoothPosition in wStartMs..wEndMs
                    val sungFactor = if (isWordSung) 1f 
                                    else if (isWordActive) ((smoothPosition - wStartMs).toFloat() / (wEndMs - wStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                                    else 0f
                    Triple(sungFactor, word, isWordSung)
                }

                val wordWobbles = FloatArray(words.size)
                words.forEachIndexed { wordIdx, word ->
                    val startMs = (word.startTime * 1000).toLong()
                    val timeSinceStart = (smoothPosition - startMs).toFloat()
                    val wobble = if (timeSinceStart in 0f..750f) {
                        if (timeSinceStart < 125f) timeSinceStart / 125f
                        else (1f - (timeSinceStart - 125f) / 625f).coerceAtLeast(0f)
                    } else 0f
                    wordWobbles[wordIdx] = wobble
                }

                fun sustainedWordMotion(
                    wordItem: WordTimestamp?,
                    isWordSung: Boolean,
                    charIndexInWord: Int,
                    wordClusterLength: Int,
                ): SustainedWordMotion {
                    if (!appleMusicStyle || isBackground || wordItem == null || wordItem.text.contains('-')) {
                        return NoSustainedWordMotion
                    }

                    val wordLength = wordItem.text.count { !it.isWhitespace() }.coerceAtLeast(1)
                    val wordDurationMs = ((wordItem.endTime - wordItem.startTime) * 1000.0).toFloat().coerceAtLeast(1f)
                    val isGrowable =
                        wordLength <= 7 &&
                            wordDurationMs >=
                            if (wordLength < 3) {
                                maxOf(1050f, wordLength * 525f)
                            } else {
                                maxOf(850f, wordLength * 190f)
                            }
                    val isLongRise =
                        !isGrowable &&
                            (
                                (wordLength >= 8 && wordDurationMs >= maxOf(700f, wordLength * 85f)) ||
                                    (wordLength < 8 && wordDurationMs >= maxOf(1300f, wordLength * 260f))
                            )

                    if (!isGrowable && !isLongRise) {
                        return NoSustainedWordMotion
                    }

                    val wordStartMs = (wordItem.startTime * 1000.0).toLong()
                    val progress =
                        if (isWordSung) {
                            1f
                        } else {
                            ((smoothPosition - wordStartMs).toFloat() / wordDurationMs).coerceIn(0f, 1f)
                        }
                    val charDelay = (charIndexInWord.toFloat() / wordClusterLength.coerceAtLeast(1)) * 0.22f
                    val delayedProgress = ((progress - charDelay) / (1f - charDelay).coerceAtLeast(0.35f)).coerceIn(0f, 1f)
                    val attack = (delayedProgress / 0.28f).coerceIn(0f, 1f)
                    val release = ((1f - delayedProgress) / 0.35f).coerceIn(0f, 1f)
                    val envelope = minOf(attack, release)
                    val easedEnvelope = sin(envelope * PI.toFloat() * 0.5f)
                    val riseBasePx = lyricStyle.fontSize.toPx() * 0.035f
                    val settledRise = if (isWordSung) riseBasePx else 0f

                    return if (isGrowable) {
                        val normalizedIndex =
                            if (wordClusterLength > 1) {
                                charIndexInWord.toFloat() / (wordClusterLength - 1)
                            } else {
                                0.5f
                            }
                        val charDecay = 1f - normalizedIndex * 0.35f
                        val scale = (0.045f + easedEnvelope * 0.085f) * charDecay
                        SustainedWordMotion(
                            scaleX = if (isWordSung) 0f else scale * 0.98f,
                            scaleY = if (isWordSung) 0f else scale,
                            risePx = maxOf(settledRise, riseBasePx * (0.45f + easedEnvelope * 1.55f)),
                            glow = if (isWordSung) 0f else (0.2f + easedEnvelope * 0.42f).coerceAtMost(0.58f),
                            offsetX = (normalizedIndex - 0.5f) * scale * 28f,
                        )
                    } else {
                        SustainedWordMotion(
                            scaleX = 0f,
                            scaleY = if (isWordSung) 0f else easedEnvelope * 0.018f,
                            risePx = maxOf(settledRise, riseBasePx * (0.25f + easedEnvelope)),
                            glow = 0f,
                            offsetX = 0f,
                        )
                    }
                }

                val lineCurrentPushes = FloatArray(layoutResult.lineCount)
                val lineTotalPushes = FloatArray(layoutResult.lineCount)
                
                // Pre-calculate total pushes per line to handle alignment correctly.
                // Iterate over cluster indices so each visual glyph unit is one slot.
                for (i in 0 until clusterCount) {
                    val charOffset = clusterCharOffsets[i]
                    val lineIdx = layoutResult.getLineForOffset(charOffset)
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1
                    
                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else Triple(0f, null, false)
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    
                    var crescendoDeltaX = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 2.5f
                        val freq = 10.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            crescendoDeltaX = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            crescendoDeltaX = (groupWord.pos * baseScalePerSegment) + boost
                        }
                    }

                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        0.038f * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f

                    val sustainMotion = sustainedWordMotion(wordItem, isWordSung, charInWordMap[i], wordLenMap[i])
                    val charScaleX = 1f + (wobble * 0.025f) + crescendoDeltaX + (nudgeScale * 0.3f) + sustainMotion.scaleX
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    lineTotalPushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }

                // Main drawing loop: iterate over cluster indices so each visual
                // glyph (including multi-codepoint Devanagari clusters) is one unit.
                for (i in 0 until clusterCount) {
                    val charOffset = clusterCharOffsets[i]
                    val lineIdx = layoutResult.getLineForOffset(charOffset)
                    val charBounds = layoutResult.getBoundingBox(charOffset)
                    val wordIdx = wordIdxMap[i]
                    val originalWordIdx = if (wordIdx != -1) effectiveToOriginalIdx[wordIdx] else -1
                    
                    val alignShift = when(alignment) {
                        TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                        TextAlign.Right -> -lineTotalPushes[lineIdx]
                        else -> 0f
                    }
                    
                    val (sungFactor, wordItem, isWordSung) = if (wordIdx != -1) wordFactors[wordIdx] else Triple(0f, null, false)
                    val wobble = if (originalWordIdx != -1) wordWobbles[originalWordIdx] else 0f
                    val wobbleX = wobble * 0.025f
                    val wobbleY = wobble * 0.015f
                    
                    val charLp = if (wordItem != null) {
                        val sMs = wordItem.startTime * 1000
                        val dur = (wordItem.endTime * 1000 - wordItem.startTime * 1000).coerceAtLeast(100.0)
                        val wProg = (smoothPosition.toDouble() - sMs) / dur
                        val cInW = charInWordMap[i].toDouble()
                        val wLen = wordLenMap[i].toDouble()
                        ((wProg - cInW / wLen) * wLen).coerceIn(0.0, 1.0).toFloat()
                    } else 0f

                    val shouldGlow = wordItem != null && !isWordSung && sungFactor > 0.001f

                    var crescendoDeltaX = 0f
                    var crescendoDeltaY = 0f
                    val groupWord = if (wordIdx != -1) hyphenGroupData[wordIdx] else null
                    if (groupWord != null) {
                        val p = sungFactor
                        val timeSinceEnd = (smoothPosition - groupWord.groupEndMs).toFloat()
                        val exitDuration = 600f
                        val pOut = (timeSinceEnd / exitDuration).coerceIn(0f, 1f)
                        val peakScale = 0.06f
                        val decay = 3.5f
                        val freq = 5.0f
                        val baseScalePerSegment = 0.012f
                        if (pOut > 0f) {
                            val baseAtEnd = groupWord.pos * baseScalePerSegment
                            val totalAtEnd = baseAtEnd + peakScale
                            val springOut = totalAtEnd * exp(-decay * pOut) * cos(freq * pOut * PI.toFloat()) * (1f - pOut)
                            crescendoDeltaX = springOut
                            crescendoDeltaY = springOut
                        } else if (groupWord.isLast) {
                            val base = groupWord.pos * baseScalePerSegment
                            val springPart = peakScale * (1f - exp(-decay * p) * cos(freq * p * PI.toFloat()) * (1f - p))
                            crescendoDeltaX = base + springPart
                            crescendoDeltaY = base + springPart
                        } else {
                            val boost = if (p > 0f) 0.02f * (1f - p) else 0f
                            val base = (groupWord.pos * baseScalePerSegment) + boost
                            crescendoDeltaX = base
                            crescendoDeltaY = base
                        }
                    }

                    val nudgeStrength = 0.038f
                    val nudgeScale = if (wordItem != null && !isWordSung && sungFactor > 0f) {
                        nudgeStrength * sin(charLp * PI.toFloat()) * exp(-3f * charLp)
                    } else 0f
                    
                    val sustainMotion = sustainedWordMotion(wordItem, isWordSung, charInWordMap[i], wordLenMap[i])
                    val charScaleX = 1f + wobbleX + crescendoDeltaX + nudgeScale * 0.3f + sustainMotion.scaleX
                    val charScaleY = 1f + wobbleY + crescendoDeltaY + nudgeScale + sustainMotion.scaleY

                    withTransform({
                        var waveOffset = 0f
                        if (groupWord != null) {
                            val wallTime = System.currentTimeMillis()
                            val adjSmoothPos = smoothPosition
                            val timeInGroup = (adjSmoothPos - groupWord.groupStartMs).toFloat()
                            val timeToGroupEnd = (groupWord.groupEndMs - adjSmoothPos).toFloat()
                            val waveFade = (timeInGroup / 200f).coerceIn(0f, 1f) * (timeToGroupEnd / 200f).coerceIn(0f, 1f)
                            if (waveFade > 0.01f) {
                                val waveSpeed = 0.006f
                                val waveHeight = 3.24f
                                val phaseOffset = i * 0.4f
                                waveOffset = sin(wallTime * waveSpeed + phaseOffset) * waveHeight * waveFade
                            }
                        }

                        translate(
                            left = alignShift + lineCurrentPushes[lineIdx] + charBounds.left + sustainMotion.offsetX,
                            top = charBounds.top + waveOffset - sustainMotion.risePx,
                        )
                        if (wordIdx != -1) {
                            scale(
                                charScaleX,
                                charScaleY,
                                pivot = Offset(charBounds.width / 2f, charBounds.height)
                            )
                        }
                    }) {
                        if ((shouldGlow || sustainMotion.glow > 0f) && wordItem != null) {
                            val sMs = wordItem.startTime * 1000
                            val eMs = wordItem.endTime * 1000
                            val dur = eMs - sMs
                            val wordLenText = wordItem.text.length.coerceAtLeast(1)
                            val impactRatio = dur.toFloat() / wordLenText
                            val fadeFactor = (sungFactor * 5f).coerceIn(0f, 1f) * ((1f - sungFactor) * 8f).coerceIn(0f, 1f)
                            val impactFactor = (((impactRatio - 100f) / 250f).coerceIn(0f, 1f) * 0.6f + ((dur.toFloat() - 300f) / 1500f).coerceIn(0f, 1f) * 0.4f).coerceIn(0f, 1f) * fadeFactor
                            val glowFactor = maxOf(impactFactor, sustainMotion.glow)
                            if (glowFactor > 0.01f) {
                                val glowAlpha = (0.38f * glowFactor).coerceIn(0f, 0.44f)
                                val baseGlowRadius = 14.dp.toPx() * glowFactor
                                drawIntoCanvas { canvas ->
                                    glowPaint.maskFilter = BlurMaskFilter(baseGlowRadius, BlurMaskFilter.Blur.NORMAL)
                                    glowPaint.color = expressiveAccent.copy(alpha = glowAlpha).toArgb()
                                    glowPaint.textSize = lyricStyle.fontSize.toPx()
                                    glowPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                    canvas.nativeCanvas.drawText(letterLayouts[i].layoutInput.text.text, 0f, letterLayouts[i].firstBaseline, glowPaint)
                                }
                            }
                        }
                        val baseAlpha = if (isWordSung || charLp > 0.99f) 1f else (focusedAlpha + (1f - focusedAlpha) * sungFactor)
                        drawText(letterLayouts[i], color = expressiveAccent.copy(alpha = if (wordIdx == -1) focusedAlpha else baseAlpha))
                        if (!isWordSung && charLp > 0f && charLp < 1f) {
                            val fXL = charBounds.width * charLp
                            val eW = (charBounds.width * 0.45f).coerceAtLeast(1f)
                            val sWL = (fXL - eW).coerceAtLeast(0f)
                            if (sWL > 0f) {
                                clipRect(left = 0f, top = 0f, right = sWL, bottom = charBounds.height) { drawText(letterLayouts[i], color = expressiveAccent) }
                            }
                            for (j in 0 until 12) {
                                val start = sWL + (j * eW / 12f)
                                val end = (sWL + ((j + 1) * eW / 12f) + 0.5f).coerceAtMost(fXL)
                                if (end > start) {
                                    clipRect(left = start, top = 0f, right = end, bottom = charBounds.height) { drawText(letterLayouts[i], color = expressiveAccent.copy(alpha = 1f - (j + 0.5f) / 12f)) }
                                }
                            }
                        }
                    }
                    lineCurrentPushes[lineIdx] += charBounds.width * (charScaleX - 1f)
                }
            }
        }
    }
}

private data class SpicyPoint(val time: Float, val value: Float)

private class SpicyNaturalSpline(
    private val points: List<SpicyPoint>,
) {
    private val secondDerivatives = FloatArray(points.size)

    init {
        if (points.size > 2) {
            val u = FloatArray(points.size - 1)
            secondDerivatives[0] = 0f
            u[0] = 0f

            for (i in 1 until points.lastIndex) {
                val prev = points[i - 1]
                val point = points[i]
                val next = points[i + 1]
                val span = (next.time - prev.time).coerceAtLeast(0.0001f)
                val sig = ((point.time - prev.time) / span).coerceIn(0f, 1f)
                val p = sig * secondDerivatives[i - 1] + 2f
                secondDerivatives[i] = (sig - 1f) / p
                val slopeNext = (next.value - point.value) / (next.time - point.time).coerceAtLeast(0.0001f)
                val slopePrev = (point.value - prev.value) / (point.time - prev.time).coerceAtLeast(0.0001f)
                u[i] = (6f * (slopeNext - slopePrev) / span - sig * u[i - 1]) / p
            }

            secondDerivatives[points.lastIndex] = 0f
            for (k in points.lastIndex - 1 downTo 0) {
                secondDerivatives[k] = secondDerivatives[k] * secondDerivatives[k + 1] + u[k]
            }
        }
    }

    fun at(progress: Float): Float {
        val x = progress.coerceIn(points.first().time, points.last().time)
        var low = 0
        var high = points.lastIndex
        while (high - low > 1) {
            val mid = (high + low) / 2
            if (points[mid].time > x) {
                high = mid
            } else {
                low = mid
            }
        }

        val h = (points[high].time - points[low].time).coerceAtLeast(0.0001f)
        val a = (points[high].time - x) / h
        val b = (x - points[low].time) / h
        return (
            a * points[low].value +
                b * points[high].value +
                ((a * a * a - a) * secondDerivatives[low] + (b * b * b - b) * secondDerivatives[high]) * h * h / 6f
            )
    }
}

private val SpicyWordScaleSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0.95f),
        SpicyPoint(0.7f, 1.075f),
        SpicyPoint(1f, 1f),
    ),
)

private val SpicyLetterScaleSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0.95f),
        SpicyPoint(0.7f, 1.18f),
        SpicyPoint(1f, 1f),
    ),
)

private val SpicyWordYOffsetSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0.01f),
        SpicyPoint(0.9f, -1f / 52.5f),
        SpicyPoint(1f, 0f),
    ),
)

private val SpicyLetterYOffsetSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0.01f),
        SpicyPoint(0.9f, -1f / 50f),
        SpicyPoint(1f, 0f),
    ),
)

private val SpicyGlowSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0f),
        SpicyPoint(0.15f, 1f),
        SpicyPoint(0.6f, 1f),
        SpicyPoint(1f, 0f),
    ),
)

private val SpicyLineGlowSpline = SpicyNaturalSpline(
    listOf(
        SpicyPoint(0f, 0f),
        SpicyPoint(0.5f, 1f),
        SpicyPoint(1f, 0f),
    ),
)

private val SpicyLineEasing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
private val SpicyOpacityEasing = CubicBezierEasing(0.61f, 1f, 0.88f, 1f)

private fun spicyEaseSinOut(progress: Float): Float =
    ((1f - cos(PI.toFloat() * progress.coerceIn(0f, 1f))) / 2f).coerceIn(0f, 1f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpicyLyricsLine(
    index: Int,
    item: LyricsEntry,
    isSynced: Boolean,
    isActiveLine: Boolean,
    bgVisible: Boolean,
    isSelected: Boolean,
    isSelectionModeActive: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricsTextSize: Float,
    expressiveAccent: Color,
    lyricsTextPosition: LyricsPosition,
    respectAgentPositioning: Boolean,
    isAutoScrollEnabled: Boolean,
    displayedCurrentLineIndex: Int,
    romanizeAsMain: Boolean,
    enabledLanguages: List<String>,
    romanizeLyrics: Boolean,
    lineEndTime: Long,
    onSizeChanged: (Int) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distanceFromCurrent = if (displayedCurrentLineIndex >= 0) abs(index - displayedCurrentLineIndex) else Int.MAX_VALUE
    val isPastLine = displayedCurrentLineIndex >= 0 && index < displayedCurrentLineIndex

    val agentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.Start
        respectAgentPositioning && item.agent == "v2" -> Alignment.End
        respectAgentPositioning && item.agent == "v1000" -> Alignment.CenterHorizontally
        item.isBackground -> Alignment.CenterHorizontally
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.Start
            LyricsPosition.CENTER -> Alignment.CenterHorizontally
            LyricsPosition.RIGHT -> Alignment.End
        }
    }

    val agentTextAlign = when {
        respectAgentPositioning && item.agent == "v1" -> TextAlign.Left
        respectAgentPositioning && item.agent == "v2" -> TextAlign.Right
        respectAgentPositioning && item.agent == "v1000" -> TextAlign.Center
        item.isBackground -> TextAlign.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> TextAlign.Left
            LyricsPosition.CENTER -> TextAlign.Center
            LyricsPosition.RIGHT -> TextAlign.Right
        }
    }

    val targetAlpha = when {
        item.isBackground && !bgVisible -> 0f
        item.isBackground -> 0.6f
        isActiveLine -> 1f
        !isAutoScrollEnabled || displayedCurrentLineIndex < 0 -> 0.51f
        isPastLine -> 0.497f
        else -> 0.51f
    } * when {
        item.isBackground || isActiveLine -> 1f
        distanceFromCurrent <= 4 -> 1f
        distanceFromCurrent <= 6 -> 0.72f
        else -> 0.52f
    }

    val lineAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200, easing = SpicyOpacityEasing),
        label = "spicyLyricsAlpha",
    )

    val blurAmount by animateFloatAsState(
        targetValue = if (isActiveLine || item.isBackground || distanceFromCurrent == Int.MAX_VALUE) {
            0f
        } else {
            (1.25f * distanceFromCurrent).coerceAtMost(6.83125f)
        },
        animationSpec = tween(durationMillis = 250),
        label = "spicyLyricsBlur",
    )

    val romanizedTextState by item.romanizedTextFlow.collectAsStateWithLifecycle()
    val isRomanizedAvailable = romanizedTextState != null
    val mainTextRaw = if (romanizeAsMain && isRomanizedAvailable) romanizedTextState else item.text
    val subTextRaw = if (romanizeAsMain && isRomanizedAvailable) item.text else romanizedTextState
    val mainText = if (item.isBackground) mainTextRaw?.removePrefix("(")?.removeSuffix(")") else mainTextRaw
    val subText = if (item.isBackground) subTextRaw?.removePrefix("(")?.removeSuffix(")") else subTextRaw

    val lyricFontSize = if (item.isBackground) lyricsTextSize * 0.56f else lyricsTextSize
    val lyricStyle = TextStyle(
        fontSize = lyricFontSize.sp,
        fontFamily = FontFamily.SansSerif,
        fontWeight = if (item.isBackground) FontWeight.SemiBold else FontWeight.Bold,
        fontStyle = if (item.isBackground) FontStyle.Italic else FontStyle.Normal,
        lineHeight = (lyricFontSize * 1.1818182f).sp,
        letterSpacing = 0.sp,
        textAlign = agentTextAlign,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )

    val effectiveWords = item.words?.takeIf { it.isNotEmpty() }
    val isWordSyncedLine = isSynced && effectiveWords != null
    val isLineSyncedLine = isSynced && effectiveWords == null && !mainText.isNullOrBlank()
    val lineScale by animateFloatAsState(
        targetValue =
            if (isLineSyncedLine && isActiveLine && !item.isBackground) {
                1.05f
            } else {
                1f
            },
        animationSpec = tween(durationMillis = 200, easing = SpicyLineEasing),
        label = "spicyLyricsScale",
    )

    val itemModifier = modifier
        .fillMaxWidth()
        .onSizeChanged { onSizeChanged(it.height) }
        .clip(RoundedCornerShape(8.dp))
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        )
        .background(if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
        .padding(
            horizontal = 24.dp,
            vertical = if (item.isBackground) 0.dp else 4.dp,
        )

    val contentAlignment = when {
        respectAgentPositioning && item.agent == "v1" -> Alignment.CenterStart
        respectAgentPositioning && item.agent == "v2" -> Alignment.CenterEnd
        item.isBackground -> Alignment.Center
        respectAgentPositioning && item.agent == "v1000" -> Alignment.Center
        else -> when (lyricsTextPosition) {
            LyricsPosition.LEFT -> Alignment.CenterStart
            LyricsPosition.RIGHT -> Alignment.CenterEnd
            LyricsPosition.CENTER -> Alignment.Center
        }
    }

    Box(modifier = itemModifier, contentAlignment = contentAlignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = lineAlpha
                    scaleX = lineScale
                    scaleY = lineScale
                    transformOrigin = when (agentTextAlign) {
                        TextAlign.Left -> TransformOrigin(0f, 0.5f)
                        TextAlign.Right -> TransformOrigin(1f, 0.5f)
                        else -> TransformOrigin(0.5f, 0.5f)
                    }
                },
            horizontalAlignment = agentAlignment,
        ) {
            if (isWordSyncedLine && effectiveWords != null && mainText != null) {
                SpicyWordLevelLyrics(
                    mainText = mainText,
                    words = effectiveWords,
                    isActiveLine = isActiveLine,
                    currentPositionState = currentPositionState,
                    lyricsOffset = lyricsOffset,
                    playerConnection = playerConnection,
                    lyricStyle = lyricStyle,
                    inactiveColor = Color.White.copy(alpha = if (isPastLine) 0.497f else 0.51f),
                    activeColor = Color.White,
                    isBackground = item.isBackground,
                    alignment = agentTextAlign,
                    lineBlurRadiusPx = with(LocalDensity.current) { blurAmount.dp.toPx() },
                )
            } else if (isLineSyncedLine && mainText != null) {
                SpicyLineLevelLyrics(
                    mainText = mainText,
                    isActiveLine = isActiveLine,
                    currentPositionState = currentPositionState,
                    lyricsOffset = lyricsOffset,
                    lineStartTime = item.time,
                    lineEndTime = lineEndTime,
                    lyricStyle = lyricStyle,
                    inactiveColor = Color.White.copy(alpha = if (isPastLine) 0.497f else 0.51f),
                    activeColor = Color.White,
                    lineBlurRadiusPx = with(LocalDensity.current) { blurAmount.dp.toPx() },
                )
            } else {
                Text(
                    text = mainText ?: "",
                    style = lyricStyle.copy(
                        color = Color.White.copy(alpha = if (isActiveLine) 1f else if (isPastLine) 0.497f else 0.51f),
                        shadow =
                            if (!isActiveLine && blurAmount > 0f) {
                                Shadow(
                                    color = Color.White.copy(alpha = if (isPastLine) 0.85f else 0.35f),
                                    offset = Offset.Zero,
                                    blurRadius = with(LocalDensity.current) { blurAmount.dp.toPx() },
                                )
                            } else {
                                null
                            },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (romanizeLyrics && enabledLanguages.isNotEmpty()) {
                subText?.let {
                    Text(
                        text = it,
                        fontSize = (lyricsTextSize * 0.42f).sp,
                        color = expressiveAccent.copy(alpha = 0.58f),
                        textAlign = agentTextAlign,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            val transText by item.translatedTextFlow.collectAsStateWithLifecycle()
            transText?.let {
                Text(
                    text = it,
                    fontSize = (lyricsTextSize * 0.38f).sp,
                    color = expressiveAccent.copy(alpha = 0.5f),
                    textAlign = agentTextAlign,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SpicyLineLevelLyrics(
    mainText: String,
    isActiveLine: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    lineStartTime: Long,
    lineEndTime: Long,
    lyricStyle: TextStyle,
    inactiveColor: Color,
    activeColor: Color,
    lineBlurRadiusPx: Float,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }
    val spicyClock = remember(mainText) { SpicyAnimationClock() }
    val lineSprings = remember(mainText) { SpicyLineSprings() }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true,
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { layoutResult.size.height.toDp() })
                .graphicsLayer(
                    clip = false,
                    compositingStrategy = CompositingStrategy.Offscreen,
                ),
        ) {
            if (mainText.isEmpty()) return@Canvas

            if (!isActiveLine) {
                if (lineBlurRadiusPx > 0f) {
                    drawIntoCanvas { canvas ->
                        glowPaint.maskFilter = BlurMaskFilter(lineBlurRadiusPx, BlurMaskFilter.Blur.NORMAL)
                        glowPaint.color = Color.White.copy(alpha = 0.35f).toArgb()
                        glowPaint.textSize = lyricStyle.fontSize.toPx()
                        canvas.nativeCanvas.drawText(layoutResult.layoutInput.text.text, 0f, layoutResult.firstBaseline, glowPaint)
                    }
                }
                drawText(layoutResult, color = inactiveColor)
                return@Canvas
            }

            val position = currentPositionState + lyricsOffset
            val duration = (lineEndTime - lineStartTime).coerceAtLeast(1L)
            val progress = ((position - lineStartTime).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val glow = lineSprings.step(
                targetGlow = SpicyLineGlowSpline.at(progress),
                deltaSeconds = spicyClock.step(),
            )

            if (glow > 0.01f) {
                drawIntoCanvas { canvas ->
                    glowPaint.maskFilter = BlurMaskFilter(4.dp.toPx() + 13.dp.toPx() * glow, BlurMaskFilter.Blur.NORMAL)
                    glowPaint.color = activeColor.copy(alpha = (glow * 0.68f).coerceIn(0f, 0.68f)).toArgb()
                    glowPaint.textSize = lyricStyle.fontSize.toPx()
                    canvas.nativeCanvas.drawText(layoutResult.layoutInput.text.text, 0f, layoutResult.firstBaseline, glowPaint)
                }
            }

            drawText(layoutResult, color = activeColor.copy(alpha = 0.35f))

            val filledWidth = size.width * progress
            val edgeWidth = (size.width * 0.2f).coerceAtLeast(1f)
            val solidWidth = (filledWidth - edgeWidth).coerceAtLeast(0f)
            if (solidWidth > 0f) {
                clipRect(left = 0f, top = 0f, right = solidWidth, bottom = size.height) {
                    drawText(layoutResult, color = activeColor)
                }
            }
            for (step in 0 until 12) {
                val start = solidWidth + (step * edgeWidth / 12f)
                val end = (solidWidth + ((step + 1) * edgeWidth / 12f) + 0.5f).coerceAtMost(filledWidth)
                if (end > start) {
                    clipRect(left = start, top = 0f, right = end, bottom = size.height) {
                        drawText(layoutResult, color = activeColor.copy(alpha = 1f - (step + 0.5f) / 12f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpicyWordLevelLyrics(
    mainText: String,
    words: List<WordTimestamp>,
    isActiveLine: Boolean,
    currentPositionState: Long,
    lyricsOffset: Long,
    playerConnection: PlayerConnection,
    lyricStyle: TextStyle,
    inactiveColor: Color,
    activeColor: Color,
    isBackground: Boolean,
    alignment: TextAlign,
    lineBlurRadiusPx: Float,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val glowPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }

    var smoothPosition by remember { mutableLongStateOf(currentPositionState + lyricsOffset) }

    LaunchedEffect(isActiveLine) {
        if (isActiveLine) {
            var lastPlayerPos = playerConnection.player.currentPosition
            var lastUpdateTime = System.currentTimeMillis()
            while (isActive) {
                withFrameMillis {
                    val now = System.currentTimeMillis()
                    val playerPos = playerConnection.player.currentPosition
                    if (playerPos != lastPlayerPos) {
                        lastPlayerPos = playerPos
                        lastUpdateTime = now
                    }
                    val elapsed = now - lastUpdateTime
                    smoothPosition = lastPlayerPos + lyricsOffset + (if (playerConnection.player.isPlaying) elapsed else 0)
                }
            }
        }
    }

    LaunchedEffect(isActiveLine, currentPositionState) {
        if (!isActiveLine) {
            smoothPosition = currentPositionState + lyricsOffset
        }
    }

    val graphemeClusters = remember(mainText) { mainText.toGraphemeClusters() }
    val clusterCount = graphemeClusters.size
    val clusterCharOffsets = remember(mainText, graphemeClusters) {
        IntArray(clusterCount).also { offsets ->
            var charOffset = 0
            graphemeClusters.forEachIndexed { i, cluster ->
                offsets[i] = charOffset
                charOffset += cluster.length
            }
        }
    }

    val charToWordData = remember(mainText, words, graphemeClusters, clusterCharOffsets, isBackground) {
        val wordIdxMap = IntArray(clusterCount) { -1 }
        val charInWordMap = IntArray(clusterCount) { 0 }
        val wordLenMap = IntArray(clusterCount) { 1 }
        var currentPos = 0
        var clusterCursor = 0
        words.forEachIndexed { wordIdx, word ->
            val wordText = word.text.let {
                if (isBackground) {
                    var t = it
                    if (wordIdx == 0) t = t.removePrefix("(")
                    if (wordIdx == words.lastIndex) t = t.removeSuffix(")")
                    t
                } else {
                    it
                }
            }
            val indexInMain = mainText.indexOf(wordText, currentPos)
            if (indexInMain >= 0) {
                val wordEnd = indexInMain + wordText.length
                while (clusterCursor < clusterCount && clusterCharOffsets[clusterCursor] < indexInMain) {
                    clusterCursor++
                }
                val wordClusters = mutableListOf<Int>()
                while (clusterCursor < clusterCount && clusterCharOffsets[clusterCursor] < wordEnd) {
                    wordClusters.add(clusterCursor)
                    clusterCursor++
                }
                val wordClusterLen = wordClusters.size.coerceAtLeast(1)
                wordClusters.forEachIndexed { position, clusterIndex ->
                    wordIdxMap[clusterIndex] = wordIdx
                    charInWordMap[clusterIndex] = position
                    wordLenMap[clusterIndex] = wordClusterLen
                }
                if (
                    clusterCursor < clusterCount &&
                    clusterCharOffsets[clusterCursor] == wordEnd &&
                    wordEnd < mainText.length &&
                    mainText[wordEnd].isWhitespace()
                ) {
                    wordIdxMap[clusterCursor] = wordIdx
                    charInWordMap[clusterCursor] = wordClusterLen
                    wordLenMap[clusterCursor] = wordClusterLen + 1
                    clusterCursor++
                }
                currentPos = wordEnd
            }
        }
        Triple(wordIdxMap, charInWordMap, wordLenMap)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val layoutResult = remember(mainText, maxWidthPx, lyricStyle) {
            textMeasurer.measure(
                text = mainText,
                style = lyricStyle,
                constraints = Constraints(minWidth = maxWidthPx, maxWidth = maxWidthPx),
                softWrap = true,
            )
        }

        val letterLayouts = remember(mainText, lyricStyle) {
            graphemeClusters.map { cluster -> textMeasurer.measure(cluster, lyricStyle) }
        }

        val isRtlText = remember(mainText) { mainText.containsRtl() }
        val spicyClock = remember(mainText, words) { SpicyAnimationClock() }
        val spicySprings = remember(mainText, words, clusterCount) {
            List(clusterCount) { SpicyClusterSprings() }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { layoutResult.size.height.toDp() })
                .graphicsLayer(
                    clip = false,
                    compositingStrategy = CompositingStrategy.Offscreen,
                ),
        ) {
            if (mainText.isEmpty()) return@Canvas

            if (!isActiveLine || isRtlText) {
                if (lineBlurRadiusPx > 0f) {
                    drawIntoCanvas { canvas ->
                        glowPaint.maskFilter = BlurMaskFilter(lineBlurRadiusPx, BlurMaskFilter.Blur.NORMAL)
                        glowPaint.color = Color.White.copy(alpha = 0.35f).toArgb()
                        glowPaint.textSize = lyricStyle.fontSize.toPx()
                        canvas.nativeCanvas.drawText(layoutResult.layoutInput.text.text, 0f, layoutResult.firstBaseline, glowPaint)
                    }
                }
                drawText(layoutResult, color = if (isActiveLine) activeColor else inactiveColor)
                return@Canvas
            }

            val (wordIdxMap, charInWordMap, wordLenMap) = charToWordData
            val deltaSeconds = spicyClock.step()
            val lineTotalPushes = FloatArray(layoutResult.lineCount)
            val clusterVisuals = Array(clusterCount) { index ->
                val wordIdx = wordIdxMap[index]
                if (wordIdx < 0) {
                    SpicyClusterVisual()
                } else {
                    val word = words[wordIdx]
                    val startMs = (word.startTime * 1000.0).toLong()
                    val endMs = (word.endTime * 1000.0).toLong()
                    val durationMs = (endMs - startMs).coerceAtLeast(1L)
                    val wordProgress = ((smoothPosition - startMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    val isSung = smoothPosition >= endMs
                    val isActiveWord = smoothPosition in startMs..endMs
                    val useLetterRange =
                        durationMs >= 1000L &&
                            !word.text.contains('-')
                    val wordClusterCount = wordLenMap[index].coerceAtLeast(1)
                    val charIndexInWord = charInWordMap[index].coerceIn(0, wordClusterCount - 1)

                    if (useLetterRange) {
                        val activeLetterIndex = (wordProgress * wordClusterCount).toInt().coerceIn(0, wordClusterCount - 1)
                        val activeLetterProgress = (wordProgress * wordClusterCount - activeLetterIndex).coerceIn(0f, 1f)
                        val letterStateProgress = when {
                            isSung || charIndexInWord < activeLetterIndex -> 1f
                            isActiveWord && charIndexInWord == activeLetterIndex -> spicyEaseSinOut(activeLetterProgress)
                            else -> 0f
                        }
                        val baseScale = SpicyLetterScaleSpline.at(activeLetterProgress)
                        val baseYOffset = SpicyLetterYOffsetSpline.at(activeLetterProgress)
                        val baseGlow = SpicyGlowSpline.at(activeLetterProgress)
                        val restingScale = SpicyLetterScaleSpline.at(0f)
                        val restingYOffset = SpicyLetterYOffsetSpline.at(0f)
                        val restingGlow = SpicyGlowSpline.at(0f)
                        val distance = abs(charIndexInWord - activeLetterIndex).toFloat()
                        val falloff = (1f / (1f + distance.pow(2.8f))).coerceIn(0f, 1f)
                        val glowFalloff = (1f / (1f + distance * 0.9f)).coerceIn(0f, 1f)
                        val activeScale = restingScale + (baseScale - restingScale) * falloff
                        val activeYOffset = restingYOffset + (baseYOffset - restingYOffset) * falloff
                        val activeGlow = restingGlow + (baseGlow - restingGlow) * glowFalloff
                        val springValues = spicySprings[index].step(
                            targetScale = if (isSung) 1f else activeScale,
                            targetYOffset = if (isSung) 0f else activeYOffset,
                            targetGlow = if (isActiveWord) activeGlow else 0f,
                            deltaSeconds = deltaSeconds,
                        )

                        SpicyClusterVisual(
                            charProgress = letterStateProgress,
                            isSung = isSung || charIndexInWord < activeLetterIndex,
                            isActiveWord = isActiveWord,
                            letterEffect = true,
                            scale = springValues.scale,
                            yOffsetEm = springValues.yOffset * 2f,
                            glow = springValues.glow,
                        )
                    } else {
                        val animationProgress = when {
                            isSung -> 1f
                            isActiveWord -> wordProgress
                            else -> 0f
                        }
                        val springValues = spicySprings[index].step(
                            targetScale = if (isSung) 1f else SpicyWordScaleSpline.at(animationProgress),
                            targetYOffset = if (isSung) 0f else SpicyWordYOffsetSpline.at(animationProgress),
                            targetGlow = if (isActiveWord) SpicyGlowSpline.at(animationProgress) else 0f,
                            deltaSeconds = deltaSeconds,
                        )
                        SpicyClusterVisual(
                            charProgress = if (isActiveWord) {
                                val characterStart = charIndexInWord.toFloat() / wordClusterCount.toFloat()
                                ((wordProgress - characterStart) * wordClusterCount).coerceIn(0f, 1f)
                            } else if (isSung) {
                                1f
                            } else {
                                0f
                            },
                            isSung = isSung,
                            isActiveWord = isActiveWord,
                            letterEffect = false,
                            scale = springValues.scale,
                            yOffsetEm = springValues.yOffset,
                            glow = springValues.glow,
                        )
                    }
                }
            }

            for (i in 0 until clusterCount) {
                val charOffset = clusterCharOffsets[i]
                val lineIdx = layoutResult.getLineForOffset(charOffset)
                val bounds = layoutResult.getBoundingBox(charOffset)
                lineTotalPushes[lineIdx] += bounds.width * (clusterVisuals[i].scale - 1f)
            }

            val lineCurrentPushes = FloatArray(layoutResult.lineCount)
            for (i in 0 until clusterCount) {
                val charOffset = clusterCharOffsets[i]
                val lineIdx = layoutResult.getLineForOffset(charOffset)
                val bounds = layoutResult.getBoundingBox(charOffset)
                val visual = clusterVisuals[i]
                val alignShift = when (alignment) {
                    TextAlign.Center -> -lineTotalPushes[lineIdx] / 2f
                    TextAlign.Right -> -lineTotalPushes[lineIdx]
                    else -> 0f
                }
                val yOffsetPx = lyricStyle.fontSize.toPx() * visual.yOffsetEm

                withTransform({
                    translate(
                        left = alignShift + lineCurrentPushes[lineIdx] + bounds.left,
                        top = bounds.top + yOffsetPx,
                    )
                    scale(
                        visual.scale,
                        visual.scale,
                        pivot = Offset(bounds.width / 2f, bounds.height * 0.82f),
                    )
                }) {
                    val textGlow = visual.glow
                    if (textGlow > 0.01f) {
                        val glowAlpha = if (visual.letterEffect) {
                            (textGlow * 1.85f).coerceIn(0f, 1f)
                        } else {
                            (textGlow * 0.35f).coerceIn(0f, 0.35f)
                        }
                        val glowRadius = if (visual.letterEffect) {
                            4.dp.toPx() + 12.dp.toPx() * textGlow
                        } else {
                            4.dp.toPx() + 2.dp.toPx() * textGlow
                        }
                        drawIntoCanvas { canvas ->
                            glowPaint.maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
                            glowPaint.color = Color.White.copy(alpha = glowAlpha).toArgb()
                            glowPaint.textSize = lyricStyle.fontSize.toPx()
                            canvas.nativeCanvas.drawText(letterLayouts[i].layoutInput.text.text, 0f, letterLayouts[i].firstBaseline, glowPaint)
                        }
                    }

                    val baseColor = if (visual.isSung) activeColor else inactiveColor
                    drawText(letterLayouts[i], color = baseColor)

                    if (visual.isActiveWord && visual.charProgress > 0f) {
                        val filledWidth = bounds.width * visual.charProgress
                        val edgeWidth = (bounds.width * 0.45f).coerceAtLeast(1f)
                        val solidWidth = (filledWidth - edgeWidth).coerceAtLeast(0f)
                        if (solidWidth > 0f) {
                            clipRect(left = 0f, top = 0f, right = solidWidth, bottom = bounds.height) {
                                drawText(letterLayouts[i], color = activeColor)
                            }
                        }
                        for (step in 0 until 12) {
                            val start = solidWidth + (step * edgeWidth / 12f)
                            val end = (solidWidth + ((step + 1) * edgeWidth / 12f) + 0.5f).coerceAtMost(filledWidth)
                            if (end > start) {
                                clipRect(left = start, top = 0f, right = end, bottom = bounds.height) {
                                    drawText(letterLayouts[i], color = activeColor.copy(alpha = 1f - (step + 0.5f) / 12f))
                                }
                            }
                        }
                    }
                }
                lineCurrentPushes[lineIdx] += bounds.width * (visual.scale - 1f)
            }
        }
    }
}

private data class SpicyClusterVisual(
    val charProgress: Float = 0f,
    val isSung: Boolean = false,
    val isActiveWord: Boolean = false,
    val letterEffect: Boolean = false,
    val scale: Float = 1f,
    val yOffsetEm: Float = 0f,
    val glow: Float = 0f,
)

private data class SpicySpringValues(
    val scale: Float,
    val yOffset: Float,
    val glow: Float,
)

private class SpicyAnimationClock {
    private var lastNanos = System.nanoTime()

    fun step(): Float {
        val now = System.nanoTime()
        val deltaSeconds = ((now - lastNanos).toDouble() / 1_000_000_000.0).coerceIn(0.0, 1.0 / 20.0)
        lastNanos = now
        return deltaSeconds.toFloat()
    }
}

private class SpicyLineSprings {
    private val glow = SpicySpring(0f, 1f, 0.5f)
    private var initialized = false

    fun step(
        targetGlow: Float,
        deltaSeconds: Float,
    ): Float {
        if (!initialized) {
            glow.setGoal(targetGlow, replacePosition = true)
            initialized = true
        } else {
            glow.setGoal(targetGlow)
        }
        return glow.step(deltaSeconds)
    }
}

private class SpicyClusterSprings {
    private val scale = SpicySpring(0.95f, 0.7f, 0.6f)
    private val yOffset = SpicySpring(0.01f, 1.25f, 0.4f)
    private val glow = SpicySpring(0f, 1f, 0.5f)
    private var initialized = false

    fun step(
        targetScale: Float,
        targetYOffset: Float,
        targetGlow: Float,
        deltaSeconds: Float,
    ): SpicySpringValues {
        if (!initialized) {
            scale.setGoal(targetScale, replacePosition = true)
            yOffset.setGoal(targetYOffset, replacePosition = true)
            glow.setGoal(targetGlow, replacePosition = true)
            initialized = true
        } else {
            scale.setGoal(targetScale)
            yOffset.setGoal(targetYOffset)
            glow.setGoal(targetGlow)
        }

        return SpicySpringValues(
            scale = scale.step(deltaSeconds),
            yOffset = yOffset.step(deltaSeconds),
            glow = glow.step(deltaSeconds),
        )
    }
}

private class SpicySpring(
    startPosition: Float,
    private val frequency: Float,
    private val dampingRatio: Float,
    goal: Float = startPosition,
) {
    private var springGoal = goal.toDouble()
    private var position = startPosition.toDouble()
    private var velocity = 0.0

    fun step(deltaSeconds: Float): Float {
        val d = dampingRatio.toDouble()
        val f = frequency.toDouble() * (2.0 * Math.PI)
        val g = springGoal
        var p = position
        var v = velocity
        val dt = deltaSeconds.toDouble()

        if (d == 1.0) {
            val q = exp(-f * dt)
            val w = dt * q
            val c0 = q + w * f
            val c2 = q - w * f
            val c3 = w * f * f
            val o = p - g
            p = o * c0 + v * w + g
            v = v * c2 - o * c3
        } else if (d < 1.0) {
            val q = exp(-d * f * dt)
            val c = sqrt(1.0 - d * d)
            val i = cos(dt * f * c)
            val j = sin(dt * f * c)
            val z =
                if (c > 1e-5) {
                    j / c
                } else {
                    val a = dt * f
                    a + ((a * a) * (c * c) * (c * c) / 20.0 - c * c) * (a * a * a) / 6.0
                }
            val y =
                if (f * c > 1e-5) {
                    j / (f * c)
                } else {
                    val b = f * c
                    dt + ((dt * dt) * (b * b) * (b * b) / 20.0 - b * b) * (dt * dt * dt) / 6.0
                }
            val o = p - g
            p = (o * (i + z * d) + v * y) * q + g
            v = (v * (i - z * d) - o * (z * f)) * q
        } else {
            val c = sqrt(d * d - 1.0)
            val r1 = -f * (d + c)
            val r2 = -f * (d - c)
            val ec1 = exp(r1 * dt)
            val ec2 = exp(r2 * dt)
            val o = p - g
            val co2 = (v - o * r1) / (2.0 * f * c)
            val co1 = ec1 * (o - co2)
            p = co1 + co2 * ec2 + g
            v = co1 * r1 + co2 * ec2 * r2
        }

        position = p
        velocity = v
        return p.toFloat()
    }

    fun setGoal(goal: Float, replacePosition: Boolean = false) {
        springGoal = goal.toDouble()
        if (replacePosition) {
            position = springGoal
            velocity = 0.0
        }
    }
}
