/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix as AndroidColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.SpotifyCanvasMedia
import com.metrolist.music.utils.spotify.normalizeSpotifyCookieInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val CanvasCrossfadeDurationMillis = 650

private data class SpotifyCanvasVideoKey(
    val url: String,
    val headers: Map<String, String>,
)

@Composable
fun rememberSpotifyCanvasMedia(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean,
    cookie: String,
    shouldLoad: Boolean,
): SpotifyCanvasMedia? {
    val normalizedCookie = normalizeSpotifyCookieInput(cookie)
    val canvasMedia by produceState<SpotifyCanvasMedia?>(
        initialValue = null,
        mediaMetadata,
        enabled,
        normalizedCookie,
        shouldLoad,
    ) {
        if (!enabled || !shouldLoad || mediaMetadata == null || normalizedCookie == null) {
            value = null
            return@produceState
        }

        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    SpotifyCanvasClient.resolveBackground(mediaMetadata, normalizedCookie)
                }.getOrNull()
            }
    }

    return canvasMedia
}

@Composable
fun SpotifyCanvasVideoBackground(
    media: SpotifyCanvasMedia,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.16f,
) {
    val canvasMedia =
        remember(media.url, media.headers) {
            SpotifyCanvasVideoKey(
                url = media.url,
                headers = media.headers,
            )
        }
    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

    var activeMedia by remember { mutableStateOf(canvasMedia) }
    var outgoingMedia by remember { mutableStateOf<SpotifyCanvasVideoKey?>(null) }

    LaunchedEffect(canvasMedia) {
        if (canvasMedia != activeMedia) {
            outgoingMedia = activeMedia
            activeMedia = canvasMedia
        }
    }

    Box(modifier = modifier) {
        outgoingMedia?.let { media ->
            key(media) {
                SpotifyCanvasVideoLayer(
                    media = media,
                    okHttpClient = okHttpClient,
                    shouldPlay = shouldPlay,
                    alpha = 1f,
                    onReady = {},
                )
            }
        }

        key(activeMedia) {
            ActiveSpotifyCanvasVideoLayer(
                media = activeMedia,
                okHttpClient = okHttpClient,
                shouldPlay = shouldPlay,
                fadeIn = outgoingMedia != null,
                onFadeComplete = {
                    if (activeMedia == it) {
                        outgoingMedia = null
                    }
                },
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha.coerceIn(0f, 1f))),
        )
    }
}

@Composable
fun AppleMusicFadedCanvasBackground(
    media: SpotifyCanvasMedia,
    artworkUrl: String?,
    shouldPlay: Boolean,
    surfaceColor: Color,
    fadeColor: Color,
    preservePlayerBackdrop: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val topMotionHeightFraction = if (preservePlayerBackdrop) 0.52f else 0.58f
    val videoAlpha = if (preservePlayerBackdrop) 0.86f else 0.98f
    val motionScrimAlpha = if (preservePlayerBackdrop) 0.035f else 0.05f
    val motionScrim =
        if (fadeColor.red * 0.299f + fadeColor.green * 0.587f + fadeColor.blue * 0.114f > 0.58f) {
            Color.White
        } else {
            Color.Black
        }
    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    val canvasMedia =
        remember(media.url, media.headers) {
            SpotifyCanvasVideoKey(
                url = media.url,
                headers = media.headers,
            )
        }
    var textureView by remember(canvasMedia) { mutableStateOf<TextureView?>(null) }
    var refreshToken by remember(canvasMedia) { mutableIntStateOf(0) }
    val fallbackBitmap by produceState<Bitmap?>(
        initialValue = null,
        artworkUrl,
    ) {
        value = null
        if (artworkUrl.isNullOrBlank()) {
            return@produceState
        }

        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    context
                        .imageLoader
                        .execute(
                            ImageRequest
                                .Builder(context)
                                .data(artworkUrl)
                                .size(192, 320)
                                .allowHardware(false)
                                .build(),
                        )
                        .image
                        ?.toBitmap()
                }.getOrNull()
            }
    }

    LaunchedEffect(textureView, fallbackBitmap, shouldPlay, canvasMedia) {
        if (textureView == null && fallbackBitmap == null) {
            return@LaunchedEffect
        }

        repeat(5) { index ->
            delay(if (index == 0) 120L else 520L)
            refreshToken += 1
        }
    }

    Box(
        modifier =
            modifier
            .fillMaxSize()
            .background(Color(0xFF171717)),
    ) {
        AndroidView(
            factory = { AppleMotionMeltView(it) },
            update = { view ->
                view.configure(
                    textureView = textureView,
                    fallbackBitmap = fallbackBitmap,
                    refreshToken = refreshToken,
                    surfaceColor = surfaceColor.toArgb(),
                    fadeColor = fadeColor.toArgb(),
                    preservePlayerBackdrop = preservePlayerBackdrop,
                    motionBottomFraction = topMotionHeightFraction,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(topMotionHeightFraction)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush =
                                Brush.verticalGradient(
                                    colorStops =
                                        arrayOf(
                                            0.00f to Color.White,
                                            0.70f to Color.White,
                                            1.00f to Color.Transparent,
                                        ),
                                ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
        ) {
            key(canvasMedia) {
                SpotifyCanvasVideoLayer(
                    media = canvasMedia,
                    okHttpClient = okHttpClient,
                    shouldPlay = shouldPlay,
                    alpha = videoAlpha,
                    onReady = {
                        refreshToken += 1
                    },
                    onTextureView = { textureView = it },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.08f
                                scaleY = 1.08f
                            },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops =
                                arrayOf(
                                    0.00f to motionScrim.copy(alpha = motionScrimAlpha),
                                    0.44f to motionScrim.copy(alpha = motionScrimAlpha * 0.22f),
                                    0.62f to Color.Transparent,
                                    1.00f to Color.Transparent,
                                ),
                        ),
                    ),
        )
    }
}

private class AppleMotionMeltView(
    context: Context,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private var textureView: TextureView? = null
    private var fallbackBitmap: Bitmap? = null
    private var refreshToken: Int = Int.MIN_VALUE
    private var surfaceColor: Int = AndroidColor.BLACK
    private var fadeColor: Int = AndroidColor.BLACK
    private var preservePlayerBackdrop: Boolean = false
    private var motionBottomFraction: Float = 0.58f
    private var needsShaderRebuild = true
    private var lastWidth = 0
    private var lastHeight = 0
    private var meltBitmap: Bitmap? = null
    private var meltShader: Shader? = null
    private var washShader: Shader? = null
    private var legibilityShader: Shader? = null

    init {
        setWillNotDraw(false)
    }

    fun configure(
        textureView: TextureView?,
        fallbackBitmap: Bitmap?,
        refreshToken: Int,
        surfaceColor: Int,
        fadeColor: Int,
        preservePlayerBackdrop: Boolean,
        motionBottomFraction: Float,
    ) {
        val sourceChanged =
            textureView !== this.textureView ||
                fallbackBitmap !== this.fallbackBitmap ||
                refreshToken != this.refreshToken ||
                surfaceColor != this.surfaceColor ||
                fadeColor != this.fadeColor ||
                preservePlayerBackdrop != this.preservePlayerBackdrop ||
                motionBottomFraction != this.motionBottomFraction
        this.textureView = textureView
        this.fallbackBitmap = fallbackBitmap
        this.refreshToken = refreshToken
        this.surfaceColor = surfaceColor
        this.fadeColor = fadeColor
        this.preservePlayerBackdrop = preservePlayerBackdrop
        this.motionBottomFraction = motionBottomFraction.coerceIn(0.46f, 0.68f)
        if (sourceChanged) {
            needsShaderRebuild = true
            invalidate()
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        needsShaderRebuild = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }

        if (needsShaderRebuild || width != lastWidth || height != lastHeight) {
            rebuildShaders(width, height)
        }

        canvas.drawColor(AppleLegibilityOpaque)
        fillPaint.color = colorWithAlpha(surfaceColor, if (preservePlayerBackdrop) 3 else 5)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)

        meltShader?.let { shader ->
            paint.shader = shader
            paint.alpha = if (preservePlayerBackdrop) 224 else 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.alpha = 255
        }

        washShader?.let { shader ->
            val washTop = lowerWashStartY(height)
            paint.shader = shader
            canvas.drawRect(
                0f,
                washTop,
                width.toFloat(),
                height.toFloat(),
                paint,
            )
            paint.shader = null
        } ?: run {
            drawFallbackWash(canvas)
        }

        legibilityShader?.let { shader ->
            paint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
    }

    private fun rebuildShaders(
        viewWidth: Int,
        viewHeight: Int,
    ) {
        lastWidth = viewWidth
        lastHeight = viewHeight
        needsShaderRebuild = false
        meltShader = null
        washShader = null
        legibilityShader = buildLegibilityShader(viewHeight)
        meltBitmap?.recycle()
        meltBitmap = null

        val source = buildSourceBitmap(viewWidth, viewHeight) ?: return
        val stripHeight = max(source.height / 8, 2).coerceAtMost(source.height)
        val strip =
            Bitmap.createBitmap(
                source,
                0,
                source.height - stripHeight,
                source.width,
                stripHeight,
            )
        val saturated =
            Bitmap.createBitmap(strip.width, strip.height, Bitmap.Config.ARGB_8888)
        Canvas(saturated).drawBitmap(
            strip,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter =
                    ColorMatrixColorFilter(
                        AndroidColorMatrix().apply {
                            setSaturation(1.4f)
                        },
                    )
            },
        )
        val blurred = boxBlur(saturated, AppleBlurRadius, AppleBlurPasses)
        saturated.recycle()
        if (strip !== source) {
            strip.recycle()
        }
        source.recycle()
        Canvas(blurred).apply {
            drawColor(AppleBlackScrim)
            drawColor(AppleWhiteScrim)
        }

        meltBitmap = blurred
        meltShader = buildRadialMeltShader(blurred, viewWidth, viewHeight)
        washShader = buildColorWashShader(blurred, viewHeight, coverAccentColor())
    }

    private fun buildSourceBitmap(
        viewWidth: Int,
        viewHeight: Int,
    ): Bitmap? {
        val sampleWidth =
            max(
                ceil((viewWidth / 8f) / 10f).toInt() * 10,
                2,
            )
        val sampleHeight = max((viewHeight / 16f).roundToInt(), 2)
        val textureBitmap =
            textureView
                ?.takeIf { it.isAvailable && it.width > 0 && it.height > 0 }
                ?.let { texture ->
                    runCatching {
                        texture.getBitmap(sampleWidth, sampleHeight)
                    }.getOrNull()
                }
                ?.takeIf { it.width > 1 && it.height > 1 }
        val albumBitmap =
            fallbackBitmap?.let { bitmap ->
                scaleCenterCrop(bitmap, sampleWidth, sampleHeight)
            }

        if (textureBitmap != null && !looksBlank(textureBitmap)) {
            if (albumBitmap != null) {
                val textureSaturation = colorSaturation(vibrantAverageColor(textureBitmap))
                val albumInfluence = if (textureSaturation < 0.24f) 0.58f else 0.44f
                return blendBitmaps(textureBitmap, albumBitmap, albumInfluence)
            }

            return textureBitmap
        }

        textureBitmap?.recycle()
        return albumBitmap
    }

    private fun buildRadialMeltShader(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
    ): Shader {
        val motionBottom = (viewHeight * motionBottomFraction).coerceIn(viewHeight * 0.46f, viewHeight * 0.68f)
        val f10 = (0.5f * motionBottom).coerceAtLeast(1f)
        val fPow = (((bitmap.width * bitmap.width).toFloat() / 8f) / f10) + (f10 / 2f)
        val f11 = (motionBottom - f10) + fPow
        val stop = (fPow / f11).coerceIn(0.05f, 0.95f)
        val radialGradient =
            RadialGradient(
                viewWidth / 2f,
                (viewHeight - f10) + fPow,
                f11,
                intArrayOf(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT, AndroidColor.WHITE),
                floatArrayOf(0f, stop, 1f),
                Shader.TileMode.CLAMP,
            )
        val bitmapShader =
            BitmapShader(
                bitmap,
                Shader.TileMode.CLAMP,
                Shader.TileMode.MIRROR,
            )
        matrix.reset()
        matrix.setScale(
            (1.25f * viewWidth) / bitmap.width,
            viewHeight.toFloat() / bitmap.height,
        )
        matrix.postTranslate((-0.25f * viewWidth) / 2f, -motionBottom)
        bitmapShader.setLocalMatrix(matrix)
        return ComposeShader(radialGradient, bitmapShader, PorterDuff.Mode.SRC_IN)
    }

    private fun buildColorWashShader(
        bitmap: Bitmap,
        viewHeight: Int,
        coverAccentColor: Int?,
    ): Shader {
        val color =
            coverAccentColor?.let { accent ->
                val stripColor = vibrantAverageColor(bitmap)
                val stripSaturation = colorSaturation(stripColor)
                val coverInfluence = if (stripSaturation < 0.24f) 0.78f else 0.62f
                blendColors(stripColor, accent, coverInfluence)
            } ?: vibrantAverageColor(bitmap)
        val startY = lowerWashStartY(viewHeight)
        val endY = min(viewHeight.toFloat(), startY + viewHeight * 0.34f)
        return LinearGradient(
            0f,
            startY,
            0f,
            endY,
            intArrayOf(
                AndroidColor.TRANSPARENT,
                colorWithAlpha(color, if (preservePlayerBackdrop) 120 else 150),
                colorWithAlpha(color, if (preservePlayerBackdrop) 156 else 188),
                colorWithAlpha(color, if (preservePlayerBackdrop) 184 else 216),
            ),
            floatArrayOf(0f, 0.66f, 0.88f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun buildLegibilityShader(viewHeight: Int): Shader {
        val alpha = if (preservePlayerBackdrop) 82 else 106
        val base = colorWithAlpha(AppleLegibilityOpaque, alpha)
        val startY = viewHeight * if (preservePlayerBackdrop) 0.70f else 0.68f
        return LinearGradient(
            0f,
            startY,
            0f,
            viewHeight.toFloat(),
            intArrayOf(
                AndroidColor.TRANSPARENT,
                colorWithAlpha(base, (alpha * 0.26f).roundToInt()),
                base,
            ),
            floatArrayOf(0f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun drawFallbackWash(canvas: Canvas) {
        val startY = lowerWashStartY(height)
        val endY = height.toFloat()
        paint.shader =
            LinearGradient(
                0f,
                startY,
                0f,
                endY,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    colorWithAlpha(fadeColor, if (preservePlayerBackdrop) 96 else 128),
                    colorWithAlpha(fadeColor, if (preservePlayerBackdrop) 132 else 168),
                ),
                floatArrayOf(0f, 0.70f, 1f),
                Shader.TileMode.CLAMP,
            )
        canvas.drawRect(0f, startY, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun lowerWashStartY(viewHeight: Int): Float =
        viewHeight *
            (
                motionBottomFraction +
                    if (preservePlayerBackdrop) 0.13f else 0.10f
            ).coerceIn(0.62f, 0.74f)

    private fun coverAccentColor(): Int? =
        fallbackBitmap?.let { bitmap ->
            vibrantAverageColor(bitmap)
        }

    override fun onDetachedFromWindow() {
        meltBitmap?.recycle()
        meltBitmap = null
        super.onDetachedFromWindow()
    }
}

private fun scaleCenterCrop(
    bitmap: Bitmap,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap {
    if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }

    val scale = max(targetWidth / bitmap.width.toFloat(), targetHeight / bitmap.height.toFloat())
    val scaledWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(targetWidth)
    val scaledHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(targetHeight)
    val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    val x = ((scaledWidth - targetWidth) / 2).coerceAtLeast(0)
    val y = ((scaledHeight - targetHeight) / 2).coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(scaled, x, y, targetWidth, targetHeight)
    if (scaled !== bitmap) {
        scaled.recycle()
    }
    return cropped
}

private fun looksBlank(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
        return true
    }

    val points =
        intArrayOf(
            bitmap.getPixel(width / 2, height / 2),
            bitmap.getPixel(width / 4, height / 3),
            bitmap.getPixel((width * 3) / 4, (height * 2) / 3),
        )
    return points.all { color ->
        AndroidColor.alpha(color) == 0 ||
            (AndroidColor.red(color) < 3 && AndroidColor.green(color) < 3 && AndroidColor.blue(color) < 3)
    }
}

private fun blendBitmaps(
    base: Bitmap,
    overlay: Bitmap,
    overlayInfluence: Float,
): Bitmap {
    val result = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    Canvas(result).apply {
        drawBitmap(base, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        drawBitmap(
            overlay,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (overlayInfluence.coerceIn(0f, 1f) * 255).roundToInt()
            },
        )
    }
    base.recycle()
    overlay.recycle()
    return result
}

private fun vibrantAverageColor(bitmap: Bitmap): Int {
    val sample =
        if (bitmap.width > 24 || bitmap.height > 24) {
            Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        } else {
            bitmap
        }
    val hsv = FloatArray(3)
    var alphaTotal = 0.0
    var redTotal = 0.0
    var greenTotal = 0.0
    var blueTotal = 0.0
    var weightTotal = 0.0

    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val color = sample.getPixel(x, y)
            val alpha = AndroidColor.alpha(color) / 255.0
            if (alpha <= 0.01) {
                continue
            }

            AndroidColor.colorToHSV(color, hsv)
            val saturation = hsv[1].toDouble()
            val value = hsv[2].toDouble()
            val colorWeight =
                alpha *
                    (
                        0.04 +
                            saturation * saturation * 2.2 +
                            saturation * value * 0.9
                    ) *
                    (0.35 + value)
            alphaTotal += alpha * colorWeight
            redTotal += AndroidColor.red(color) * colorWeight
            greenTotal += AndroidColor.green(color) * colorWeight
            blueTotal += AndroidColor.blue(color) * colorWeight
            weightTotal += colorWeight
        }
    }

    if (sample !== bitmap) {
        sample.recycle()
    }

    if (weightTotal <= 0.0) {
        return averageColor(bitmap)
    }

    return AndroidColor.argb(
        ((alphaTotal / weightTotal) * 255).roundToInt().coerceIn(0, 255),
        (redTotal / weightTotal).roundToInt().coerceIn(0, 255),
        (greenTotal / weightTotal).roundToInt().coerceIn(0, 255),
        (blueTotal / weightTotal).roundToInt().coerceIn(0, 255),
    )
}

private fun colorSaturation(color: Int): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color, hsv)
    return hsv[1]
}

private fun blendColors(
    base: Int,
    overlay: Int,
    overlayInfluence: Float,
): Int {
    val amount = overlayInfluence.coerceIn(0f, 1f)
    val inverse = 1f - amount
    return AndroidColor.argb(
        (AndroidColor.alpha(base) * inverse + AndroidColor.alpha(overlay) * amount).roundToInt().coerceIn(0, 255),
        (AndroidColor.red(base) * inverse + AndroidColor.red(overlay) * amount).roundToInt().coerceIn(0, 255),
        (AndroidColor.green(base) * inverse + AndroidColor.green(overlay) * amount).roundToInt().coerceIn(0, 255),
        (AndroidColor.blue(base) * inverse + AndroidColor.blue(overlay) * amount).roundToInt().coerceIn(0, 255),
    )
}

private fun averageColor(bitmap: Bitmap): Int {
    val scaled = Bitmap.createScaledBitmap(bitmap, 2, 2, true)
    var alpha = 0
    var red = 0
    var green = 0
    var blue = 0
    for (y in 0 until 2) {
        for (x in 0 until 2) {
            val color = scaled.getPixel(x, y)
            alpha += AndroidColor.alpha(color)
            red += AndroidColor.red(color)
            green += AndroidColor.green(color)
            blue += AndroidColor.blue(color)
        }
    }
    scaled.recycle()
    return AndroidColor.argb(
        alpha / 4,
        red / 4,
        green / 4,
        blue / 4,
    )
}

private fun boxBlur(
    bitmap: Bitmap,
    radius: Int,
    passes: Int,
): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    val scratch = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    repeat(passes) {
        boxBlurHorizontal(pixels, scratch, width, height, radius)
        boxBlurVertical(scratch, pixels, width, height, radius)
    }

    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private fun boxBlurHorizontal(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val offset = y * width
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (x in -radius..radius) {
            val color = input[offset + x.coerceIn(0, width - 1)]
            a += AndroidColor.alpha(color).toLong()
            r += AndroidColor.red(color).toLong()
            g += AndroidColor.green(color).toLong()
            b += AndroidColor.blue(color).toLong()
        }
        for (x in 0 until width) {
            output[offset + x] =
                AndroidColor.argb(
                    (a / window).toInt(),
                    (r / window).toInt(),
                    (g / window).toInt(),
                    (b / window).toInt(),
                )
            val remove = input[offset + (x - radius).coerceIn(0, width - 1)]
            val add = input[offset + (x + radius + 1).coerceIn(0, width - 1)]
            a += AndroidColor.alpha(add).toLong() - AndroidColor.alpha(remove).toLong()
            r += AndroidColor.red(add).toLong() - AndroidColor.red(remove).toLong()
            g += AndroidColor.green(add).toLong() - AndroidColor.green(remove).toLong()
            b += AndroidColor.blue(add).toLong() - AndroidColor.blue(remove).toLong()
        }
    }
}

private fun boxBlurVertical(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var a = 0L
        var r = 0L
        var g = 0L
        var b = 0L
        for (y in -radius..radius) {
            val color = input[y.coerceIn(0, height - 1) * width + x]
            a += AndroidColor.alpha(color).toLong()
            r += AndroidColor.red(color).toLong()
            g += AndroidColor.green(color).toLong()
            b += AndroidColor.blue(color).toLong()
        }
        for (y in 0 until height) {
            output[y * width + x] =
                AndroidColor.argb(
                    (a / window).toInt(),
                    (r / window).toInt(),
                    (g / window).toInt(),
                    (b / window).toInt(),
                )
            val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            a += AndroidColor.alpha(add).toLong() - AndroidColor.alpha(remove).toLong()
            r += AndroidColor.red(add).toLong() - AndroidColor.red(remove).toLong()
            g += AndroidColor.green(add).toLong() - AndroidColor.green(remove).toLong()
            b += AndroidColor.blue(add).toLong() - AndroidColor.blue(remove).toLong()
        }
    }
}

private fun colorWithAlpha(
    color: Int,
    alpha: Int,
): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private const val AppleLegibilityOpaque = 0xFF171717.toInt()
private const val AppleBlackScrim = 0x33000000
private const val AppleWhiteScrim = 0x14FFFFFF
private const val AppleBlurRadius = 25
private const val AppleBlurPasses = 3

@Composable
private fun ActiveSpotifyCanvasVideoLayer(
    media: SpotifyCanvasVideoKey,
    okHttpClient: OkHttpClient,
    shouldPlay: Boolean,
    fadeIn: Boolean,
    onFadeComplete: (SpotifyCanvasVideoKey) -> Unit,
) {
    var isReady by remember(media) { mutableStateOf(!fadeIn) }
    val currentOnFadeComplete by rememberUpdatedState(onFadeComplete)
    val alpha by animateFloatAsState(
        targetValue = if (isReady) 1f else 0f,
        animationSpec = tween(CanvasCrossfadeDurationMillis),
        label = "SpotifyCanvasCrossfade",
        finishedListener = { value ->
            if (value == 1f && isReady) {
                currentOnFadeComplete(media)
            }
        },
    )

    LaunchedEffect(fadeIn) {
        if (!fadeIn) {
            isReady = true
        }
    }

    SpotifyCanvasVideoLayer(
        media = media,
        okHttpClient = okHttpClient,
        shouldPlay = shouldPlay,
        alpha = alpha,
        onReady = { isReady = true },
    )
}

@Composable
private fun SpotifyCanvasVideoLayer(
    media: SpotifyCanvasVideoKey,
    okHttpClient: OkHttpClient,
    shouldPlay: Boolean,
    alpha: Float,
    onReady: () -> Unit,
    onTextureView: (TextureView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentShouldPlay by rememberUpdatedState(shouldPlay)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnTextureView by rememberUpdatedState(onTextureView)
    val textureView =
        remember(media) {
            TextureView(context).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
            }
        }
    val player =
        remember(media) {
            val isRemoteCanvas =
                media.url.startsWith("http://", ignoreCase = true) ||
                    media.url.startsWith("https://", ignoreCase = true)
            val mediaSourceFactory =
                if (isRemoteCanvas) {
                    val dataSourceFactory =
                        OkHttpDataSource
                            .Factory(okHttpClient)
                            .setDefaultRequestProperties(media.headers)
                    DefaultMediaSourceFactory(dataSourceFactory)
                } else {
                    DefaultMediaSourceFactory(context)
                }

            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(createCanvasTrackSelector(context))
                .build()
                .apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = shouldPlay
                    setVideoTextureView(textureView)
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                }
        }

    LaunchedEffect(textureView) {
        currentOnTextureView(textureView)
    }

    DisposableEffect(player, lifecycleOwner, textureView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> if (currentShouldPlay) player.play()
                    Lifecycle.Event.ON_PAUSE -> player.pause()
                    else -> Unit
                }
            }
        val playerListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        currentOnReady()
                    }
                }
            }
        player.addListener(playerListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        if (player.playbackState == Player.STATE_READY) {
            currentOnReady()
        }
        onDispose {
            player.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.clearVideoTextureView(textureView)
            player.release()
        }
    }

    LaunchedEffect(player, shouldPlay) {
        player.playWhenReady = shouldPlay
        if (shouldPlay) {
            player.play()
        } else {
            player.pause()
        }
    }

    AndroidView(
        factory = { textureView },
        modifier =
            modifier
                .fillMaxSize()
                .alpha(alpha),
    )
}

private fun createCanvasTrackSelector(context: Context): DefaultTrackSelector =
    DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build(),
        )
    }
