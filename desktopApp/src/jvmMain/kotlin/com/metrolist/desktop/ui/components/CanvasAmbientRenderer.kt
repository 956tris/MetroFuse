package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import com.metrolist.shared.playback.VideoFrame
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

@Composable
internal fun CanvasAmbientRenderer(
    frameProvider: () -> VideoFrame?,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val painter = rememberVideoFramePainter()
    val buffer = remember(painter) { CanvasAmbientBuffer(painter) }
    LaunchedEffect(playing) { buffer.invalidate() }
    DisposableEffect(buffer) {
        onDispose { buffer.close() }
    }
    Spacer(
        modifier.drawBehind {
            if (size.width <= 0f || size.height <= 0f) return@drawBehind
            val frame = frameProvider()
            drawIntoCanvas { buffer.draw(frame, it.skiaCanvas, size.width, size.height) }
        },
    )
}

private class CanvasAmbientBuffer(
    private val painter: VideoFramePainter,
) : AutoCloseable {
    private val saturation = videoColorFilter(2.5f, 1f)
    private val brightness = videoColorFilter(1f, 0.65f)
    private val blur = ImageFilter.makeBlur(5f, 5f, FilterTileMode.DECAL)
    private val saturatedBlur = ImageFilter.makeColorFilter(saturation, blur, null)
    private val filteredBlur = ImageFilter.makeColorFilter(brightness, saturatedBlur, null)
    private val filterPaint = Paint().apply { imageFilter = filteredBlur }
    private var surface: Surface? = null
    private var image: Image? = null
    private var primed = false
    private var lastTimestamp = Long.MIN_VALUE
    private var lastDrawNanos = 0L

    fun invalidate() {
        primed = false
        lastTimestamp = Long.MIN_VALUE
    }

    fun draw(
        frame: VideoFrame?,
        canvas: Canvas,
        width: Float,
        height: Float,
    ) {
        val bufferWidth = 80
        val bufferHeight = (bufferWidth * height / width).roundToInt().coerceAtLeast(1)
        if (surface?.height != bufferHeight) {
            image?.close()
            image = null
            surface?.close()
            surface = Surface.makeRaster(ImageInfo.makeN32(bufferWidth, bufferHeight, ColorAlphaType.OPAQUE, ColorSpace.sRGB))
            surface?.canvas?.clear(Color.BLACK)
            invalidate()
        }
        val target = surface ?: return
        val now = System.nanoTime()
        if (frame != null && frame.width > 0 && frame.height > 0 && frame.timestampMs != lastTimestamp &&
            (!primed || now - lastDrawNanos >= 1_000_000_000L / 30)
        ) {
            // Indiachrome's opaque 80px canvas retains its pixels between draws:
            // blur(5px) saturate(2.5) brightness(.65), 1.3x crop, then source-over at .3.
            val scale = maxOf(bufferWidth.toFloat() / frame.width, bufferHeight.toFloat() / frame.height) * 1.3f
            val w = frame.width * scale
            val h = frame.height * scale
            val destination = Rect.makeXYWH((bufferWidth - w) / 2f, (bufferHeight - h) / 2f, w, h)
            filterPaint.setAlphaf(if (primed) 0.3f else 1f)
            val saved = target.canvas.saveLayer(destination, filterPaint)
            val rendered =
                try {
                    painter.draw(frame, target.canvas, destination)
                } finally {
                    target.canvas.restoreToCount(saved)
                }
            if (rendered) {
                image?.close()
                image = target.makeImageSnapshot()
                primed = true
                lastTimestamp = frame.timestampMs
                lastDrawNanos = now
            }
        }
        image?.let {
            canvas.drawImageRect(
                it,
                Rect.makeWH(bufferWidth.toFloat(), bufferHeight.toFloat()),
                Rect.makeWH(width, height),
                SamplingMode.LINEAR,
                null,
                true,
            )
        }
    }

    override fun close() {
        image?.close()
        surface?.close()
        filterPaint.close()
        filteredBlur.close()
        saturatedBlur.close()
        blur.close()
        brightness.close()
        saturation.close()
    }
}
