package com.metrolist.music.playback

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
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
import com.metrolist.music.di.PlayerCache
import dagger.hilt.android.AndroidEntryPoint
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class CanvasWallpaperService : WallpaperService() {

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    override fun onCreateEngine(): Engine = CanvasEngine()

    inner class CanvasEngine : Engine() {
        private var player: ExoPlayer? = null
        private var currentUrl: String? = null
        private val scrimPaint = Paint().apply {
            color = Color.BLACK
            alpha = 80 // Subtle dark overlay
        }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_UPDATE_WALLPAPER) {
                    val url = intent.getStringExtra(EXTRA_CANVAS_URL)
                    updatePlayer(url)
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            val filter = IntentFilter(ACTION_UPDATE_WALLPAPER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            initializePlayer()
        }

        override fun onDestroy() {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered or not registered
            }
            releasePlayer()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                player?.play()
            } else {
                player?.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            player?.setVideoSurfaceHolder(holder)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            player?.setVideoSurfaceHolder(null)
            super.onSurfaceDestroyed(holder)
        }

        private fun initializePlayer() {
            val dataSourceFactory = CacheDataSource.Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(OkHttpClient())
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

        private fun updatePlayer(url: String?) {
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
                }
            }
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }
    }

    companion object {
        const val ACTION_UPDATE_WALLPAPER = "com.metrolist.music.UPDATE_WALLPAPER"
        const val EXTRA_CANVAS_URL = "canvas_url"

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
