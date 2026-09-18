/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.ui.player.CanvasVideo

@Composable
fun PlaylistBlurredBackdrop(
    backgroundModel: Any?,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 56.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.fillMaxWidth()) {
        if (backgroundModel != null) {
            AsyncImage(
                model = backgroundModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .matchParentSize()
                        .scale(1.3f)
                        .blur(blurRadius),
            )
        }
        Box(
            modifier =
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.42f),
                            Color.Black.copy(alpha = 0.55f),
                            scheme.background.copy(alpha = 0.72f),
                            scheme.background,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun AlbumFullBleedHeader(
    thumbnailModel: Any?,
    canvasUrl: String?,
    modifier: Modifier = Modifier,
    headerHeight: Dp = 440.dp,
    foreground: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(headerHeight),
    ) {
        if (canvasUrl != null) {
            CanvasVideo(
                canvasUrl = canvasUrl,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (thumbnailModel != null) {
            AsyncImage(
                model = thumbnailModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(scheme.surfaceVariant),
            )
        }

        // Top scrim for status/nav readability
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.45f),
                                Color.Transparent,
                            ),
                        ),
                    ),
        )

        // Smooth mix into list background
        Box(
            modifier =
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        0.68f to scheme.background.copy(alpha = 0.55f),
                        0.86f to scheme.background.copy(alpha = 0.92f),
                        1.0f to scheme.background,
                    ),
                ),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
            content = foreground,
        )
    }
}

@Composable
fun MosaicArtwork(
    thumbnails: List<Any?>,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier =
            modifier
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.22f),
                    shape = shape,
                ).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val models = List(4) { thumbnails.getOrNull(it) }
        Box(modifier = Modifier.fillMaxSize()) {
            val alignments =
                listOf(
                    Alignment.TopStart,
                    Alignment.TopEnd,
                    Alignment.BottomStart,
                    Alignment.BottomEnd,
                )
            alignments.forEachIndexed { index, alignment ->
                val model = models[index]
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .align(alignment)
                                .fillMaxSize(0.5f),
                    )
                }
            }
        }
    }
}
