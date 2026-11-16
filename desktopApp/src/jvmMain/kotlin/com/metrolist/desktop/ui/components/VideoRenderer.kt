package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.playback.VideoColorRange
import com.metrolist.shared.playback.VideoColorSpace
import com.metrolist.shared.playback.VideoFrame
import com.metrolist.shared.playback.VideoPixelFormat
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorMatrix as SkiaColorMatrix

private const val YUV420P_SKSL = """
uniform shader yTex;
uniform shader uTex;
uniform shader vTex;
uniform int colorSpace;
uniform int colorRange;

half4 main(vec2 coord) {
    float y = max(yTex.eval(coord).r, yTex.eval(coord).a);
    float u = max(uTex.eval(coord * 0.5).r, uTex.eval(coord * 0.5).a);
    float v = max(vTex.eval(coord * 0.5).r, vTex.eval(coord * 0.5).a);

    if (colorRange == 0) {
        y = (y - 0.062745098) * 1.164383562;
        u = (u - 0.501960784) * 1.138392857;
        v = (v - 0.501960784) * 1.138392857;
    } else {
        u = u - 0.5;
        v = v - 0.5;
    }

    vec3 rgb;
    if (colorSpace == 1) {
        rgb.r = y + 1.402 * v;
        rgb.g = y - 0.344136 * u - 0.714136 * v;
        rgb.b = y + 1.772 * u;
    } else if (colorSpace == 2) {
        rgb.r = y + 1.4746 * v;
        rgb.g = y - 0.164553 * u - 0.571353 * v;
        rgb.b = y + 1.8814 * u;
    } else {
        rgb.r = y + 1.5748 * v;
        rgb.g = y - 0.187324 * u - 0.468124 * v;
        rgb.b = y + 1.8556 * u;
    }

    return half4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private const val NV12_SKSL = """
uniform shader yTex;
uniform shader uvTex;
uniform int colorSpace;
uniform int colorRange;

half4 main(vec2 coord) {
    float y = yTex.eval(coord).r;
    vec2 uv = uvTex.eval(coord * 0.5).rg;
    float u = uv.r;
    float v = uv.g;

    if (colorRange == 0) {
        y = (y - 0.062745098) * 1.164383562;
        u = (u - 0.501960784) * 1.138392857;
        v = (v - 0.501960784) * 1.138392857;
    } else {
        u = u - 0.5;
        v = v - 0.5;
    }

    vec3 rgb;
    if (colorSpace == 1) {
        rgb.r = y + 1.402 * v;
        rgb.g = y - 0.344136 * u - 0.714136 * v;
        rgb.b = y + 1.772 * u;
    } else if (colorSpace == 2) {
        rgb.r = y + 1.4746 * v;
        rgb.g = y - 0.164553 * u - 0.571353 * v;
        rgb.b = y + 1.8814 * u;
    } else {
        rgb.r = y + 1.5748 * v;
        rgb.g = y - 0.187324 * u - 0.468124 * v;
        rgb.b = y + 1.8556 * u;
    }

    return half4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private val yuv420pEffect by lazy {
    RuntimeEffect.makeForShader(YUV420P_SKSL)
}

private val nv12Effect by lazy {
    RuntimeEffect.makeForShader(NV12_SKSL)
}

private val filterClamp = FilterTileMode.CLAMP
private val linearSampling = SamplingMode.LINEAR

internal fun videoColorFilter(
    saturation: Float,
    brightness: Float,
): ColorFilter {
    val matrix = ColorMatrix().apply { setToSaturation(saturation) }
    for (row in 0..2) {
        for (column in 0..4) matrix[row, column] *= brightness
    }
    return ColorFilter.makeMatrix(SkiaColorMatrix(matrix.values))
}

@Composable
internal fun rememberVideoFramePainter(): VideoFramePainter {
    val painter = remember { VideoFramePainter() }
    DisposableEffect(painter) {
        onDispose { painter.close() }
    }
    return painter
}

internal class VideoFramePainter : AutoCloseable {
    private val paint = Paint()
    private val yuvBuilder = RuntimeShaderBuilder(yuv420pEffect)
    private val nv12Builder = RuntimeShaderBuilder(nv12Effect)

    fun draw(
        frame: VideoFrame,
        canvas: Canvas,
        destination: Rect,
        renderPaint: Paint = paint,
    ): Boolean {
        var rendered = false
        val usable =
            frame.drawSafely {
                val srcW = width.toFloat()
                val srcH = height.toFloat()
                if (srcW <= 0f || srcH <= 0f) return@drawSafely
                val csUniform =
                    when (colorSpace) {
                        VideoColorSpace.BT601 -> 1
                        VideoColorSpace.BT2020 -> 2
                        VideoColorSpace.BT709 -> 0
                    }
                val crUniform = if (colorRange == VideoColorRange.FULL) 1 else 0
                try {
                    when (format) {
                        VideoPixelFormat.NV12 -> {
                            val yImage = planeY ?: return@drawSafely
                            val uvImage = planeUV ?: return@drawSafely
                            yImage.makeShader(filterClamp, filterClamp, linearSampling).use { yShader ->
                                uvImage.makeShader(filterClamp, filterClamp, linearSampling).use { uvShader ->
                                    nv12Builder.child("yTex", yShader)
                                    nv12Builder.child("uvTex", uvShader)
                                    drawShader(canvas, destination, srcW, srcH, nv12Builder, csUniform, crUniform, renderPaint)
                                }
                            }
                        }

                        VideoPixelFormat.YUV420P -> {
                            val yImage = planeY ?: return@drawSafely
                            val uImage = planeU ?: return@drawSafely
                            val vImage = planeV ?: return@drawSafely
                            yImage.makeShader(filterClamp, filterClamp, linearSampling).use { yShader ->
                                uImage.makeShader(filterClamp, filterClamp, linearSampling).use { uShader ->
                                    vImage.makeShader(filterClamp, filterClamp, linearSampling).use { vShader ->
                                        yuvBuilder.child("yTex", yShader)
                                        yuvBuilder.child("uTex", uShader)
                                        yuvBuilder.child("vTex", vShader)
                                        drawShader(canvas, destination, srcW, srcH, yuvBuilder, csUniform, crUniform, renderPaint)
                                    }
                                }
                            }
                        }

                        VideoPixelFormat.BGRA -> {
                            val bitmap = bgraBitmap ?: return@drawSafely
                            Image.makeFromBitmap(bitmap).use { image ->
                                canvas.drawImageRect(image, Rect.makeWH(srcW, srcH), destination, linearSampling, renderPaint, true)
                            }
                        }
                    }
                    rendered = true
                } catch (_: Throwable) {
                }
            }
        return usable && rendered
    }

    private fun drawShader(
        canvas: Canvas,
        destination: Rect,
        sourceWidth: Float,
        sourceHeight: Float,
        builder: RuntimeShaderBuilder,
        colorSpace: Int,
        colorRange: Int,
        renderPaint: Paint,
    ) {
        builder.uniform("colorSpace", colorSpace)
        builder.uniform("colorRange", colorRange)
        builder.makeShader().use { shader ->
            renderPaint.shader = shader
            val count = canvas.save()
            try {
                canvas.translate(destination.left, destination.top)
                canvas.scale(destination.width / sourceWidth, destination.height / sourceHeight)
                canvas.drawRect(Rect.makeWH(sourceWidth, sourceHeight), renderPaint)
            } finally {
                canvas.restoreToCount(count)
                renderPaint.shader = null
            }
        }
    }

    override fun close() {
        paint.close()
        yuvBuilder.close()
        nv12Builder.close()
    }
}

@Composable
fun VideoRenderer(
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    frameProvider: () -> VideoFrame? = { PlayerState.videoFrame },
    onViewportSizeChanged: (width: Int, height: Int) -> Unit = { _, _ -> },
    alignment: Alignment = Alignment.Center,
    saturation: Float = 1f,
    brightness: Float = 1f,
) {
    val colorFilter = remember(saturation, brightness) { videoColorFilter(saturation, brightness) }
    val renderPaint = remember(colorFilter) { Paint().apply { this.colorFilter = colorFilter } }
    val painter = rememberVideoFramePainter()
    DisposableEffect(renderPaint, colorFilter) {
        onDispose {
            renderPaint.close()
            colorFilter.close()
        }
    }
    Spacer(
        modifier =
            modifier
                .onSizeChanged { onViewportSizeChanged(it.width, it.height) }
                .drawBehind {
                    val frame = frameProvider() ?: return@drawBehind
                    val srcW = frame.width.toFloat()
                    val srcH = frame.height.toFloat()
                    if (srcW <= 0f || srcH <= 0f) return@drawBehind
                    val scale =
                        if (contentScale == ContentScale.Crop) {
                            maxOf(size.width / srcW, size.height / srcH)
                        } else {
                            minOf(size.width / srcW, size.height / srcH)
                        }
                    val dstW = (srcW * scale).roundToInt()
                    val dstH = (srcH * scale).roundToInt()
                    val offset =
                        alignment.align(
                            IntSize(dstW, dstH),
                            IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            layoutDirection,
                        )
                    drawIntoCanvas { canvas ->
                        painter.draw(
                            frame,
                            canvas.skiaCanvas,
                            Rect.makeXYWH(offset.x.toFloat(), offset.y.toFloat(), dstW.toFloat(), dstH.toFloat()),
                            renderPaint,
                        )
                    }
                },
    )
}
