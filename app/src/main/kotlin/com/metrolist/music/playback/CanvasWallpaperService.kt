package com.metrolist.music.playback

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.metrolist.music.di.PlayerCache
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class CanvasWallpaperService : WallpaperService() {

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    private val okHttpClient by lazy { OkHttpClient() }

    override fun onCreateEngine(): Engine = CanvasEngine()

    inner class CanvasEngine : Engine() {
        private var player: ExoPlayer? = null
        private var currentUrl: String? = null
        private var currentArtworkUrl: String? = null
        private var artworkBitmap: Bitmap? = null
        private var hasSurface = false

        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private val scrimPaint = Paint().apply {
            color = Color.BLACK
            alpha = 100 
        }
        private val backgroundPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        private var surfaceWidth: Int = 0
        private var surfaceHeight: Int = 0
        private var fadeGradient: LinearGradient? = null
        private var baseColor: Int = Color.BLACK
        private var fadeTargetColor: Int = Color.BLACK

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_UPDATE_WALLPAPER -> {
                        val url = intent.getStringExtra(EXTRA_CANVAS_URL)
                        val artworkUrl = intent.getStringExtra(EXTRA_CANVAS_ARTWORK_URL)
                        updateContent(url, artworkUrl)
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter(ACTION_UPDATE_WALLPAPER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            initializePlayer()
            
            // Request initial update from MusicService
            sendBroadcast(Intent(ACTION_REQUEST_UPDATE))
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(receiver)
            } catch (_: Exception) {}
            releasePlayer()
            coroutineScope.cancel()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            createFadeGradient()
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                player?.play()
                drawFrame()
            } else {
                player?.pause()
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            hasSurface = true
            player?.setVideoSurfaceHolder(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            hasSurface = false
            player?.setVideoSurfaceHolder(null)
            super.onSurfaceDestroyed(holder)
        }

        private fun initializePlayer() {
            val dataSourceFactory = CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(okHttpClient)
                        .setDefaultRequestProperties(
                            mapOf(
                                "Origin" to "https://music.apple.com",
                                "Referer" to "https://music.apple.com/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            )
                        )
                )
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaSourceFactory = DefaultMediaSourceFactory(this@CanvasWallpaperService)
                .setDataSourceFactory(dataSourceFactory)

            player = ExoPlayer.Builder(this@CanvasWallpaperService)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = isVisible
                }
        }

        private fun updateContent(url: String?, artworkUrl: String?) {
            if (artworkUrl != currentArtworkUrl) {
                currentArtworkUrl = artworkUrl
                coroutineScope.launch {
                    val bitmap = loadArtwork(artworkUrl)
                    artworkBitmap = bitmap
                    generatePaletteColors(bitmap)
                    if (currentUrl == null) {
                        drawFrame()
                    }
                }
            }

            if (url == currentUrl) return
            currentUrl = url

            player?.let { p ->
                p.stop()
                p.clearMediaItems()
                if (!url.isNullOrBlank()) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(url)
                        .setMimeType(if (url.contains(".m3u8")) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
                        .build()
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    if (isVisible) p.play()
                } else {
                    drawFrame()
                }
            }
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }

        private suspend fun loadArtwork(url: String?): Bitmap? = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext null
            try {
                val request = ImageRequest.Builder(this@CanvasWallpaperService)
                    .data(url)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    result.image.toBitmap()
                } else {
                    null
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load wallpaper artwork")
                null
            }
        }

        private fun generatePaletteColors(bitmap: Bitmap?) {
            if (bitmap == null) {
                baseColor = Color.BLACK
                fadeTargetColor = Color.BLACK
                createFadeGradient()
                return
            }

            Palette.from(bitmap).generate { palette ->
                if (palette == null) return@generate
                
                val selectedSwatch = palette.darkVibrantSwatch 
                    ?: palette.darkMutedSwatch 
                    ?: palette.vibrantSwatch 
                    ?: palette.mutedSwatch 
                    ?: palette.dominantSwatch
                
                baseColor = selectedSwatch?.rgb ?: Color.BLACK
                fadeTargetColor = if (isDark(baseColor)) Color.BLACK else 0xFF121212.toInt()
                
                createFadeGradient()
                if (currentUrl == null) {
                    drawFrame()
                }
            }
        }

        private fun isDark(color: Int): Boolean {
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0
            return luminance < 0.5
        }

        private fun createFadeGradient() {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            
            val startY = surfaceHeight * 0.6f
            
            fadeGradient = LinearGradient(
                0f, startY, 0f, surfaceHeight.toFloat(),
                intArrayOf(Color.TRANSPARENT, baseColor, fadeTargetColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        private fun drawFrame() {
            if (!hasSurface || !isVisible || !currentUrl.isNullOrBlank()) return
            
            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            } ?: return

            try {
                artworkBitmap?.let { bitmap ->
                    val bitmapWidth = bitmap.width
                    val bitmapHeight = bitmap.height
                    
                    val scale: Float
                    var dx = 0f
                    var dy = 0f

                    if (bitmapWidth * surfaceHeight > surfaceWidth * bitmapHeight) {
                        scale = surfaceHeight.toFloat() / bitmapHeight.toFloat()
                        dx = (surfaceWidth - bitmapWidth * scale) * 0.5f
                    } else {
                        scale = surfaceWidth.toFloat() / bitmapWidth.toFloat()
                        dy = (surfaceHeight - bitmapHeight * scale) * 0.5f
                    }

                    canvas.withTranslation(dx, dy) {
                        withScale(scale, scale) {
                            drawBitmap(bitmap, 0f, 0f, backgroundPaint)
                        }
                    }
                } ?: run {
                    canvas.drawColor(Color.BLACK)
                }

                fadeGradient?.let { gradient ->
                    backgroundPaint.shader = gradient
                    canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), backgroundPaint)
                    backgroundPaint.shader = null
                }

                canvas.drawRect(0f, 0f, surfaceWidth.toFloat(), surfaceHeight.toFloat(), scrimPaint)
            } catch (e: Exception) {
                Timber.e(e, "Failed to draw wallpaper frame")
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val ACTION_UPDATE_WALLPAPER = "com.metrolist.music.UPDATE_WALLPAPER"
        const val ACTION_REQUEST_UPDATE = "com.metrolist.music.REQUEST_WALLPAPER_UPDATE"
        const val EXTRA_CANVAS_URL = "canvas_url"
        const val EXTRA_CANVAS_ARTWORK_URL = "canvas_artwork_url"

        fun setLiveWallpaper(context: Context) {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(context, CanvasWallpaperService::class.java))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
