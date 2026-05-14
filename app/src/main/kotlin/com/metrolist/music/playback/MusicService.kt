/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("DEPRECATION")

package com.metrolist.music.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.MoreExecutors
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.lastfm.LastFM
import com.metrolist.music.MainActivity
import com.metrolist.music.R
import com.metrolist.music.apple.AppleMusicAwareDataSourceFactory
import com.metrolist.music.apple.AppleMusicCanvasProvider
import com.metrolist.music.apple.AppleMusicDecryptPipeline
import com.metrolist.music.apple.AppleMusicSongResolver
import com.metrolist.music.apple.AppleMusicWrapperDataSource
import com.metrolist.music.constants.AndroidAutoTargetPlaylistKey
import com.metrolist.music.constants.AppleMusicFallbackEnabledKey
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioOffload
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderMatchOverridesKey
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoSkipNextOnErrorKey
import com.metrolist.music.constants.AutoplayKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerCookieKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.DisableLoadMoreWhenRepeatAllKey
import com.metrolist.music.constants.DiscordActivityNameKey
import com.metrolist.music.constants.DiscordActivityTypeKey
import com.metrolist.music.constants.DiscordAdvancedModeKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.DiscordButton1TextKey
import com.metrolist.music.constants.DiscordButton1VisibleKey
import com.metrolist.music.constants.DiscordButton2TextKey
import com.metrolist.music.constants.DiscordButton2VisibleKey
import com.metrolist.music.constants.DiscordStatusKey
import com.metrolist.music.constants.DiscordTokenKey
import com.metrolist.music.constants.DiscordUseDetailsKey
import com.metrolist.music.constants.EnableDiscordRPCKey
import com.metrolist.music.constants.EnableLastFMScrobblingKey
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.LastFMUseNowPlaying
import com.metrolist.music.constants.MetroMixEnabledKey
import com.metrolist.music.constants.MetroMixBarsKey
import com.metrolist.music.constants.MetroMixEffectCurve
import com.metrolist.music.constants.MetroMixEffectCurveKey
import com.metrolist.music.constants.MetroMixEqCurve
import com.metrolist.music.constants.MetroMixEqCurveKey
import com.metrolist.music.constants.MetroMixPreset
import com.metrolist.music.constants.MetroMixPresetKey
import com.metrolist.music.constants.MetroMixVolumeCurve
import com.metrolist.music.constants.MetroMixVolumeCurveKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.MediaSessionConstants.CommandAddToTargetPlaylist
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleLike
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleShuffle
import com.metrolist.music.constants.MediaSessionConstants.CommandToggleStartRadio
import com.metrolist.music.constants.PauseListenHistoryKey
import com.metrolist.music.constants.PauseOnMute
import com.metrolist.music.constants.PersistentQueueKey
import com.metrolist.music.constants.PersistentShuffleAcrossQueuesKey
import com.metrolist.music.constants.PreferAppleMusicKey
import com.metrolist.music.constants.PreferDeezerAudioKey
import com.metrolist.music.constants.PreferTidalAudioKey
import com.metrolist.music.constants.PreferSoundCloudAudioKey
import com.metrolist.music.constants.PreferInstagramAudioKey
import com.metrolist.music.constants.PreferYouTubeMusicAudioKey
import com.metrolist.music.constants.InstagramCookieKey
import com.metrolist.music.constants.InstagramAppIdKey
import com.metrolist.music.constants.InstagramUserAgentKey
import com.metrolist.music.constants.InstagramUuidKey
import com.metrolist.music.constants.TidalAudioQuality
import com.metrolist.music.constants.TidalAudioQualityKey
import com.metrolist.music.constants.TidalCookieKey
import com.metrolist.music.constants.PlayerVolumeKey
import com.metrolist.music.constants.PreventDuplicateTracksInQueueKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.constants.RememberShuffleAndRepeatKey
import com.metrolist.music.constants.RepeatModeKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.constants.ScrobbleDelayPercentKey
import com.metrolist.music.constants.ScrobbleDelaySecondsKey
import com.metrolist.music.constants.ScrobbleMinSongDurationKey
import com.metrolist.music.constants.ShowLyricsKey
import com.metrolist.music.constants.ShuffleModeKey
import com.metrolist.music.constants.ShufflePlaylistFirstKey
import com.metrolist.music.constants.SimilarContent
import com.metrolist.music.constants.SkipSilenceInstantKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.constants.StopOnProviderErrorKey
import com.metrolist.music.deezer.DeezerAudioAwareDataSourceFactory
import com.metrolist.music.deezer.DeezerAudioDataSource
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.RelatedSongMap
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.eq.EqualizerService
import com.metrolist.music.eq.audio.CustomEqualizerAudioProcessor
import com.metrolist.music.eq.data.EQProfileRepository
import com.metrolist.music.extensions.SilentHandler
import com.metrolist.music.extensions.collect
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.currentMetadata
import com.metrolist.music.extensions.findNextMediaItemById
import com.metrolist.music.extensions.mediaItems
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.setOffloadEnabled
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.extensions.toPersistQueue
import com.metrolist.music.extensions.toQueue
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.models.PersistPlayerState
import com.metrolist.music.models.PersistQueue
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.alarm.MusicAlarmScheduler
import com.metrolist.music.playback.alarm.MusicAlarmStore
import com.metrolist.music.playback.audio.SilenceDetectorAudioProcessor
import com.metrolist.music.playback.queues.EmptyQueue
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.playback.queues.Queue
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.playback.queues.filterExplicit
import com.metrolist.music.playback.queues.filterVideoSongs
import com.metrolist.music.providers.DeezerHomeFeedProvider
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.providers.ProviderMatchOverride
import com.metrolist.music.providers.ProviderMatchOverrides
import com.metrolist.music.providers.TidalHomeFeedProvider
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.instagram.InstagramAudioProvider
import com.metrolist.music.tidal.TidalAudioProvider
import com.metrolist.music.constants.LoudnessLevel
import com.metrolist.music.constants.LoudnessLevelKey
import com.metrolist.music.utils.CoilBitmapLoader
import com.metrolist.music.utils.DiscordRPC
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.ScrobbleManager
import com.metrolist.music.utils.SyncUtils
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.widget.MetrolistWidgetManager
import com.metrolist.music.widget.MusicWidgetReceiver
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.youtube.YouTubeAudioProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import java.util.Collections

private const val INSTANT_SILENCE_SKIP_STEP_MS = 15_000L
private const val INSTANT_SILENCE_SKIP_SETTLE_MS = 350L
private const val APPLE_CANVAS_PREFETCH_WINDOW = 1
private const val APPLE_CANVAS_PREFETCH_CACHE_LIMIT = 128

private data class CrossfadePreferenceState(
    val crossfadeEnabled: Boolean,
    val crossfadeDuration: Float,
    val crossfadeGapless: Boolean,
    val metroMixEnabled: Boolean,
    val metroMixPreset: MetroMixPreset,
    val metroMixBars: Int,
    val metroMixVolumeCurve: MetroMixVolumeCurve,
    val metroMixEqCurve: MetroMixEqCurve,
    val metroMixEffectCurve: MetroMixEffectCurve,
)

private data class MetroMixRuntimeProfile(
    val preset: MetroMixPreset?,
    val durationMs: Long,
    val volumeCurve: MetroMixVolumeCurve = MetroMixVolumeCurve.AUTO,
    val eqCurve: MetroMixEqCurve = MetroMixEqCurve.AUTO,
    val effectCurve: MetroMixEffectCurve = MetroMixEffectCurve.AUTO,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    @Inject
    lateinit var equalizerService: EqualizerService

    @Inject
    lateinit var eqProfileRepository: EQProfileRepository

    @Inject
    lateinit var widgetManager: MetrolistWidgetManager

    @Inject
    lateinit var listenTogetherManager: com.metrolist.music.listentogether.ListenTogetherManager

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var hasAudioFocus = false
    private var reentrantFocusGain = false
    private var wasPlayingBeforeVolumeMute = false
    private var isPausedByVolumeMute = false

    private var crossfadeEnabled = false
    private var crossfadeDuration = 5000f
    private var crossfadeGapless = true
    private var activeMetroMixPreset: MetroMixPreset? = null
    private var activeMetroMixBars = 8
    private var activeMetroMixVolumeCurve = MetroMixVolumeCurve.AUTO
    private var activeMetroMixEqCurve = MetroMixEqCurve.AUTO
    private var activeMetroMixEffectCurve = MetroMixEffectCurve.AUTO
    private var pendingMetroMixProfile: MetroMixRuntimeProfile? = null
    private var activeCrossfadeDurationMs = 5000L
    private var activeMetroMixRuntimePreset: MetroMixPreset? = null
    private var activeMetroMixRuntimeProfile: MetroMixRuntimeProfile? = null
    private var crossfadeTriggerJob: Job? = null
    private var crossfadePrepareJob: Job? = null

    private val secondaryPlayerListener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Timber.tag(TAG).e(error, "Secondary player error")
                cleanupSecondaryCrossfadePlayer()
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = MusicBinder()

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private lateinit var audioQuality: com.metrolist.music.constants.AudioQuality

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private var queueSaveJob: Job? = null

    val currentMediaMetadata = MutableStateFlow<com.metrolist.music.models.MediaMetadata?>(null)
    val currentAppleCanvasUrl = MutableStateFlow<String?>(null)
    val currentAppleTallCanvasUrl = MutableStateFlow<String?>(null)
    val currentEmbeddedCanvasUrl = MutableStateFlow<String?>(null)
    val currentPreferredArtworkUrl = MutableStateFlow<String?>(null)
    val currentTidalArtworkUrl = currentPreferredArtworkUrl
    private val preferredArtworkCache =
        object : LinkedHashMap<String, String?>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean = size > 256
        }
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.stateIn(scope, SharingStarted.Lazily, null)
    val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }

    lateinit var playerVolume: MutableStateFlow<Float>
    val isMuted = MutableStateFlow(false)
    private val sleepTimerVolumeMultiplier = MutableStateFlow(1f)
    private val audioFocusVolumeMultiplier = MutableStateFlow(1f)

    fun toggleMute() {
        val newMutedState = !isMuted.value
        isMuted.value = newMutedState
        applyEffectiveVolume()
    }

    fun setMuted(muted: Boolean) {
        isMuted.value = muted
        applyEffectiveVolume()
    }

    private fun calculateEffectiveVolume(
        volume: Float = playerVolume.value,
        muted: Boolean = isMuted.value,
        sleepTimerMultiplier: Float = sleepTimerVolumeMultiplier.value,
        focusMultiplier: Float = audioFocusVolumeMultiplier.value,
    ): Float {
        if (muted) return 0f
        return (volume * sleepTimerMultiplier * focusMultiplier).coerceIn(0f, 1f)
    }

    private fun applyEffectiveVolume() {
        if (!::player.isInitialized || isCrossfading) return
        player.volume = calculateEffectiveVolume()
    }

    fun prepareManualPlaybackTransition() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        crossfadePrepareJob?.cancel()
        crossfadePrepareJob = null
        crossfadeJob?.cancel()
        crossfadeJob = null
        pendingMetroMixProfile = null
        activeMetroMixRuntimePreset = null
        activeMetroMixRuntimeProfile = null

        if (secondaryPlayer != null) {
            cleanupSecondaryCrossfadePlayer(scheduleNext = false)
        }

        if (fadingPlayer != null || isCrossfading) {
            cleanupCrossfade(
                fadingPlayerSessionId = fadingPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET,
                scheduleNext = false,
            )
        }

        isCrossfading = false
        applyEffectiveVolume()
    }

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject
    @DownloadCache
    lateinit var downloadCache: SimpleCache

    lateinit var player: ExoPlayer
        private set
    private var secondaryPlayer: ExoPlayer? = null
    private var fadingPlayer: ExoPlayer? = null
    private var isCrossfading = false
    private var crossfadeJob: Job? = null

    private lateinit var mediaSession: MediaLibrarySession

    // Tracks if player has been properly initilized
    private val playerInitialized = MutableStateFlow(false)
    val isPlayerReady: kotlinx.coroutines.flow.StateFlow<Boolean> = playerInitialized.asStateFlow()

    // Expose active player flow for UI/Connection updates
    private val _playerFlow = MutableStateFlow<ExoPlayer?>(null)
    val playerFlow = _playerFlow.asStateFlow()

    private val playerSilenceProcessors = HashMap<Player, SilenceDetectorAudioProcessor>()

    private val instantSilenceSkipEnabled = MutableStateFlow(false)

    private data class QueueSaveSnapshot(
        val queue: PersistQueue,
        val automix: PersistQueue,
        val playerState: PersistPlayerState,
    )

    private var isAudioEffectSessionOpened = false
    private var openedAudioEffectSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var loudnessSetupJob: Job? = null
    private var loudnessSetupGeneration: Long = 0L

    @Volatile
    private var normalizationEnabledCached: Boolean = false

    @Volatile
    private var loudnessLevelCached: LoudnessLevel = LoudnessLevel.BALANCED

    private var cachedNormalizationGainMb: Int? = null
    private var cachedNormalizationEnabled: Boolean = false
    @Volatile
    private var cachedInstagramCookie: String = ""
    @Volatile
    private var cachedSpotifyCookie: String = ""
    @Volatile
    private var cachedInstagramUserAgent: String = InstagramAudioProvider.DEFAULT_USER_AGENT

    private var discordRpc: DiscordRPC? = null
    private var lastPlaybackSpeed = 1.0f
    private var discordUpdateJob: kotlinx.coroutines.Job? = null
    private val discordUpdateGeneration = AtomicLong(0L)

    @Volatile
    private var latestMediaNotification: Notification? = null

    private var scrobbleManager: ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())

    // Tracks the original queue size to distinguish original items from auto-added ones
    private var originalQueueSize: Int = 0

    private var consecutivePlaybackErr = 0
    private var retryJob: Job? = null
    private var retryCount = 0
    private var silenceSkipJob: Job? = null

    private data class CachedSongStream(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val selectionKey: String,
        val format: FormatEntity,
        val mimeType: String? = null,
    )

    private data class PlaybackStreamResolution(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val format: FormatEntity,
        val mimeType: String? = null,
        val tempFilePath: String? = null,
    )

    // Cached preferences to avoid runBlocking DataStore reads in hot paths
    @Volatile
    private var cachedPersistentQueue = true
    @Volatile
    private var cachedAutoplay = true
    @Volatile
    private var cachedDisableLoadMoreWhenRepeatAll = false
    @Volatile
    private var cachedHideExplicit = false
    @Volatile
    private var cachedHideVideoSongs = false
    @Volatile
    private var cachedShufflePlaylistFirst = false
    @Volatile
    private var cachedAutoLoadMore = true

    // URL cache for stream URLs - class-level so it can be invalidated on errors
    private val songUrlCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedSongStream>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSongStream>): Boolean {
                return size > 500
            }
        }
    )
    private val audioFormatRetryJobs = ConcurrentHashMap<String, Job>()
    private val appleCanvasPrefetchMediaIds = ConcurrentHashMap.newKeySet<String>()

    // Flag to bypass cache when quality changes - forces fresh stream fetch
    private val bypassCacheForQualityChange = mutableSetOf<String>()
    private val skipAppleOnceMediaIds = ConcurrentHashMap.newKeySet<String>()
    private val skipTidalLiveManifestOnceMediaIds = ConcurrentHashMap.newKeySet<String>()
    private val tidalProgressivePreferredMediaIds = ConcurrentHashMap.newKeySet<String>()

    // Enhanced error tracking for strict retry management
    private var currentMediaIdRetryCount = mutableMapOf<String, Int>()
    private val MAX_RETRY_PER_SONG = 3
    private val RETRY_DELAY_MS = 1000L

    // Track failed songs to prevent infinite retry loops
    private val recentlyFailedSongs = mutableSetOf<String>()
    private var failedSongsClearJob: Job? = null

    // Google Cast support
    var castConnectionHandler: CastConnectionHandler? = null
        private set

    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (!player.isPlaying) {
                            scope.launch(Dispatchers.IO) {
                                discordRpc?.closeRPC()
                            }
                        }
                    }

                    Intent.ACTION_SCREEN_ON -> {
                        if (player.isPlaying) {
                            scope.launch {
                                currentSong.value?.let { song ->
                                    updateDiscordRPC(song)
                                }
                            }
                        }
                    }
                }
            }
        }

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                super.onAudioDevicesAdded(addedDevices)
                val hasBluetooth =
                    addedDevices?.any {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    } == true

                if (hasBluetooth) {
                    if (dataStore.get(ResumeOnBluetoothConnectKey, false)) {
                        if (player.playbackState == Player.STATE_READY && !player.isPlaying) {
                            player.play()
                        }
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        setListener(
            object : MediaSessionService.Listener {
                override fun onForegroundServiceStartNotAllowedException() {
                    handleForegroundServiceStartNotAllowed(null)
                }
            },
        )

        // Player rediness reset to false
        playerInitialized.value = false

        // 3. Connect the processor to the service
        // handled in createExoPlayer

        seedLoudnessCacheFromPrefs()
        cachedInstagramCookie = dataStore.get(InstagramCookieKey, "")
        cachedSpotifyCookie = dataStore.get(SpotifyCookieKey, "")
        cachedInstagramUserAgent =
            dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
                .takeIf { it.isNotBlank() }
                ?: InstagramAudioProvider.DEFAULT_USER_AGENT

        if (!ensureStartedAsForegroundOrStop()) {
            return
        }

        val defaultMediaNotificationProvider =
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player,
            ).apply {
                setSmallIcon(R.drawable.small_icon)
            }

        setMediaNotificationProvider(
            object : MediaNotification.Provider {
                override fun createNotification(
                    mediaSession: MediaSession,
                    mediaButtonPreferences: ImmutableList<CommandButton>,
                    actionFactory: MediaNotification.ActionFactory,
                    onNotificationChangedCallback: MediaNotification.Provider.Callback,
                ): MediaNotification {
                    val trackingCallback =
                        MediaNotification.Provider.Callback { notification ->
                            latestMediaNotification = notification.notification
                            Handler(Looper.getMainLooper()).post {
                                runCatching {
                                    NotificationManagerCompat
                                        .from(this@MusicService)
                                        .notify(notification.notificationId, notification.notification)
                                }.onFailure { error ->
                                    Timber.tag(TAG).w(error, "Failed to post async media notification update")
                                }
                            }
                        }

                    return defaultMediaNotificationProvider
                        .createNotification(
                            mediaSession,
                            mediaButtonPreferences,
                            actionFactory,
                            trackingCallback,
                        ).also { mediaNotification ->
                            latestMediaNotification = mediaNotification.notification
                        }
                }

                override fun handleCustomCommand(
                    session: MediaSession,
                    action: String,
                    extras: Bundle,
                ): Boolean = defaultMediaNotificationProvider.handleCustomCommand(session, action, extras)

                override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
                    defaultMediaNotificationProvider.notificationChannelInfo
            },
        )
        player = createExoPlayer()
        player.addListener(this@MusicService)
        sleepTimer =
            SleepTimer(scope, player) { multiplier ->
                sleepTimerVolumeMultiplier.value = multiplier
            }
        player.addListener(sleepTimer)

        // Mark player as initialized after successful creation
        playerInitialized.value = true
        Timber.tag(TAG).d("Player successfully initialized")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            service = this@MusicService
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
            addToTargetPlaylist = ::addToTargetPlaylist
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        player.repeatMode = dataStore.get(RepeatModeKey, REPEAT_MODE_OFF)

        // Restore shuffle mode if remember option is enabled
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            player.shuffleModeEnabled = dataStore.get(ShuffleModeKey, false)
        }

        // Keep a connected controller so that notification works
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        val screenStateFilter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        registerReceiver(screenStateReceiver, screenStateFilter)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)

        audioQuality = dataStore.get(AudioQualityKey).toEnum(com.metrolist.music.constants.AudioQuality.AUTO)
        playerVolume = MutableStateFlow(dataStore.get(PlayerVolumeKey, 1f).coerceIn(0f, 1f))
        dataStore.data
            .map { it[InstagramCookieKey] ?: "" }
            .distinctUntilChanged()
            .collectLatest(scope) { cachedInstagramCookie = it }
        dataStore.data
            .map { it[SpotifyCookieKey] ?: "" }
            .distinctUntilChanged()
            .collectLatest(scope) { cachedSpotifyCookie = it }
        dataStore.data
            .map { prefs ->
                prefs[InstagramUserAgentKey]
                    ?.takeIf { it.isNotBlank() }
                    ?: InstagramAudioProvider.DEFAULT_USER_AGENT
            }
            .distinctUntilChanged()
            .collectLatest(scope) { cachedInstagramUserAgent = it }

        // Initialize Google Cast
        initializeCast()

        // Update lyrics provider order preference
        // Collecting this flow activates the internal map that updates lyricsProviders in LyricsHelper
        lyricsHelper.preferred.collectLatest(scope) {}

        currentMediaMetadata
            .distinctUntilChangedBy { it?.id }
            .collectLatest(scope) { metadata ->
                preloadUpcomingAppleCanvases()
                markAppleWrapperFormat(metadata)
                coroutineScope {
                    launch { updateAppleCanvas(metadata) }
                    launch { updatePreferredArtwork(metadata) }
                }
            }

        // 4. Watch for EQ profile changes
        scope.launch {
            eqProfileRepository.activeProfile.collect { profile ->
                if (profile != null) {
                    val result = equalizerService.applyProfile(profile)
                    if (result.isSuccess && player.playbackState == Player.STATE_READY && player.isPlaying) {
                        // Instant update: flush buffers and seek slightly to re-process audio
                        // Small seek to force re-buffer through the new EQ settings
                        // Seek to current position effectively resets the pipeline
                        player.seekTo(player.currentPosition)
                    }
                } else {
                    equalizerService.disable()
                    if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                        player.seekTo(player.currentPosition)
                    }
                }
            }
        }

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    triggerRetry()
                }
                // Update Discord RPC when network becomes available
                if (isConnected && discordRpc != null && player.isPlaying) {
                    val mediaId = player.currentMetadata?.id
                    if (mediaId != null) {
                        database.song(mediaId).first()?.let { song ->
                            updateDiscordRPC(song)
                        }
                    }
                }
            }
        }

        // Watch for audio quality setting changes
        var isFirstQualityEmit = true
        scope.launch {
            dataStore.data
                .map {
                    it[AudioQualityKey]?.let { value ->
                        com.metrolist.music.constants.AudioQuality.entries
                            .find { it.name == value }
                    } ?: com.metrolist.music.constants.AudioQuality.AUTO
                }.distinctUntilChanged()
                .collect { newQuality ->
                    val oldQuality = audioQuality
                    audioQuality = newQuality

                    // Skip reload on first emit (app startup)
                    if (isFirstQualityEmit) {
                        isFirstQualityEmit = false
                        Timber.tag("MusicService").i("QUALITY INIT: $newQuality")
                        return@collect
                    }

                    Timber.tag("MusicService").i("QUALITY CHANGED: $oldQuality -> $newQuality")

                    // Reload current song with new quality
                    val mediaId = player.currentMediaItem?.mediaId ?: return@collect
                    val currentPosition = player.currentPosition
                    val wasPlaying = player.isPlaying
                    val currentIndex = player.currentMediaItemIndex

                    Timber.tag("MusicService").i("RELOADING STREAM: $mediaId at position ${currentPosition}ms")

                    // Clear cached URL to force fresh fetch
                    songUrlCache.remove(mediaId)
                    AppleMusicSongResolver.invalidate(mediaId)
                    QobuzAudioProvider.invalidate(mediaId)
                    TidalAudioProvider.invalidate(mediaId)
                    DeezerAudioProvider.invalidate(mediaId)
                    SoundCloudAudioProvider.invalidate(mediaId)
                    InstagramAudioProvider.invalidate(mediaId)
                    YouTubeAudioProvider.invalidate(mediaId)
                    AppleMusicDecryptPipeline.clearMemoryCaches()

                    // CRITICAL: Clear caches synchronously to prevent format parsing errors
                    runBlocking(Dispatchers.IO) {
                        try {
                            playerCache.removeResource(mediaId)
                            playerCache.removeResource(appleWrapperCacheKey(mediaId))
                            playerCache.removeResource(qobuzFallbackCacheKey(mediaId))
                            playerCache.removeResource(tidalFallbackCacheKey(mediaId))
                            playerCache.removeResource(deezerFallbackCacheKey(mediaId))
                            playerCache.removeResource(soundCloudFallbackCacheKey(mediaId))
                            playerCache.removeResource(instagramFallbackCacheKey(mediaId))
                            playerCache.removeResource(youtubeFallbackCacheKey(mediaId))
                            downloadCache.removeResource(mediaId)
                            downloadCache.removeResource(appleWrapperCacheKey(mediaId))
                            downloadCache.removeResource(qobuzFallbackCacheKey(mediaId))
                            downloadCache.removeResource(tidalFallbackCacheKey(mediaId))
                            downloadCache.removeResource(deezerFallbackCacheKey(mediaId))
                            downloadCache.removeResource(soundCloudFallbackCacheKey(mediaId))
                            downloadCache.removeResource(instagramFallbackCacheKey(mediaId))
                            downloadCache.removeResource(youtubeFallbackCacheKey(mediaId))
                            Timber.tag("MusicService").d("Cleared player and download cache for $mediaId")
                        } catch (e: Exception) {
                            Timber.tag("MusicService").e(e, "Failed to clear cache for $mediaId")
                        }
                    }

                    // Set bypass flag so resolver skips cache checks
                    bypassCacheForQualityChange.add(mediaId)
                    Timber.tag("MusicService").d("Set bypass cache flag for $mediaId")

                    // Reload player at same position
                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
        }

        combine(
            playerVolume,
            isMuted,
            sleepTimerVolumeMultiplier,
            audioFocusVolumeMultiplier,
        ) { volume, muted, timerMultiplier, focusMultiplier ->
            calculateEffectiveVolume(
                volume = volume,
                muted = muted,
                sleepTimerMultiplier = timerMultiplier,
                focusMultiplier = focusMultiplier,
            )
        }.collectLatest(scope) {
            if (!isCrossfading) {
                player.volume = it
            }
        }

        playerVolume.debounce(1000).collect(scope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(1000).collect(scope) { song ->
            updateNotification()
            updateWidgetUI(player.isPlaying)
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(scope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database
                    .lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyricsWithProvider = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyricsWithProvider.lyrics,
                            provider = lyricsWithProvider.provider,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { (it[SkipSilenceKey] ?: false) to (it[SkipSilenceInstantKey] ?: false) }
            .distinctUntilChanged()
            .collectLatest(scope) { (skipSilence, instantSkip) ->
                player.skipSilenceEnabled = skipSilence
                secondaryPlayer?.skipSilenceEnabled = skipSilence

                val enableInstant = skipSilence && instantSkip
                instantSilenceSkipEnabled.value = enableInstant

                playerSilenceProcessors.values.forEach { processor ->
                    processor.instantModeEnabled = enableInstant
                    if (!enableInstant) {
                        processor.resetTracking()
                    }
                }

                if (!enableInstant) {
                    silenceSkipJob?.cancel()
                }
            }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
            dataStore.data
                .map { prefs -> prefs[LoudnessLevelKey].toEnum(LoudnessLevel.BALANCED) }
                .distinctUntilChanged(),
        ) { format, normalizeAudio, loudnessLevel ->
            Triple(format, normalizeAudio, loudnessLevel)
        }.collectLatest(scope) { (format, normalizeAudio, loudnessLevel) ->
            normalizationEnabledCached = normalizeAudio
            loudnessLevelCached = loudnessLevel
            setupLoudnessEnhancer()
        }

        combine(
            dataStore.data.map { it[AudioOffload] ?: false },
            dataStore.data.map { it[CrossfadeEnabledKey] ?: false },
            dataStore.data.map { it[MetroMixEnabledKey] ?: false },
        ) { offloadPref, crossfadeEnabled, metroMixEnabled ->
            shouldEnableAudioOffload(offloadPref, crossfadeEnabled || metroMixEnabled)
        }.distinctUntilChanged()
            .collectLatest(scope) { useOffload ->
                player.setOffloadEnabled(useOffload)
                secondaryPlayer?.setOffloadEnabled(useOffload)
            }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { (key, enabled) ->
                if (discordRpc?.isRpcRunning() == true) {
                    discordRpc?.closeRPC()
                }
                discordRpc = null
                if (key != null && enabled) {
                    discordRpc = DiscordRPC(this, key)
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            updateDiscordRPC(it, true)
                        }
                    }
                }
            }

        // Watch all Discord customization preferences
        dataStore.data
            .map {
                listOf(
                    it[DiscordUseDetailsKey],
                    it[DiscordAdvancedModeKey],
                    it[DiscordStatusKey],
                    it[DiscordButton1TextKey],
                    it[DiscordButton1VisibleKey],
                    it[DiscordButton2TextKey],
                    it[DiscordButton2VisibleKey],
                    it[DiscordActivityTypeKey],
                    it[DiscordActivityNameKey],
                )
            }.debounce(300)
            .distinctUntilChanged()
            .collect(scope) {
                if (player.playbackState == Player.STATE_READY) {
                    currentSong.value?.let { song ->
                        updateDiscordRPC(song, true)
                    }
                }
            }

        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration =
                        dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    scrobbleManager =
                        ScrobbleManager(
                            scope,
                            minSongDuration = minSongDuration,
                            scrobbleDelayPercent = delayPercent,
                            scrobbleDelaySeconds = delaySeconds,
                        )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
                )
            }.distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        combine(
            dataStore.data.map { prefs ->
                CrossfadePreferenceState(
                    crossfadeEnabled = prefs[CrossfadeEnabledKey] ?: false,
                    crossfadeDuration = prefs[CrossfadeDurationKey] ?: 5f,
                    crossfadeGapless = prefs[CrossfadeGaplessKey] ?: true,
                    metroMixEnabled = prefs[MetroMixEnabledKey] ?: false,
                    metroMixPreset = prefs[MetroMixPresetKey].toEnum(defaultValue = MetroMixPreset.AUTO),
                    metroMixBars = prefs[MetroMixBarsKey] ?: 8,
                    metroMixVolumeCurve = prefs[MetroMixVolumeCurveKey].toEnum(defaultValue = MetroMixVolumeCurve.AUTO),
                    metroMixEqCurve = prefs[MetroMixEqCurveKey].toEnum(defaultValue = MetroMixEqCurve.AUTO),
                    metroMixEffectCurve = prefs[MetroMixEffectCurveKey].toEnum(defaultValue = MetroMixEffectCurve.AUTO),
                )
            },
            listenTogetherManager.roomState,
        ) { prefs, roomState ->
            // Disable crossfade if user is in a listen together room
            CrossfadePreferenceState(
                crossfadeEnabled = (prefs.crossfadeEnabled || prefs.metroMixEnabled) && roomState == null,
                crossfadeDuration = if (prefs.metroMixEnabled) prefs.metroMixPreset.durationSeconds else prefs.crossfadeDuration,
                crossfadeGapless = if (prefs.metroMixEnabled) false else prefs.crossfadeGapless,
                metroMixEnabled = prefs.metroMixEnabled && roomState == null,
                metroMixPreset = prefs.metroMixPreset,
                metroMixBars = prefs.metroMixBars,
                metroMixVolumeCurve = prefs.metroMixVolumeCurve,
                metroMixEqCurve = prefs.metroMixEqCurve,
                metroMixEffectCurve = prefs.metroMixEffectCurve,
            )
        }.distinctUntilChanged()
            .collect(scope) { prefs ->
                crossfadeEnabled = prefs.crossfadeEnabled
                crossfadeDuration = prefs.crossfadeDuration * 1000f // Convert to ms
                crossfadeGapless = prefs.crossfadeGapless
                activeMetroMixPreset = prefs.metroMixPreset.takeIf { prefs.metroMixEnabled }
                activeMetroMixBars = prefs.metroMixBars.coerceIn(2, 32)
                activeMetroMixVolumeCurve = prefs.metroMixVolumeCurve
                activeMetroMixEqCurve = prefs.metroMixEqCurve
                activeMetroMixEffectCurve = prefs.metroMixEffectCurve
                scheduleCrossfade()
            }

        // Observe and cache common preferences to avoid runBlocking reads in playback callbacks
        scope.launch {
            dataStore.data.map { it[PersistentQueueKey] ?: true }.distinctUntilChanged().collect { cachedPersistentQueue = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoplayKey] ?: true }.distinctUntilChanged().collect { cachedAutoplay = it }
        }
        scope.launch {
            dataStore.data.map { it[DisableLoadMoreWhenRepeatAllKey] ?: false }.distinctUntilChanged().collect { cachedDisableLoadMoreWhenRepeatAll = it }
        }
        scope.launch {
            dataStore.data.map { it[HideExplicitKey] ?: false }.distinctUntilChanged().collect { cachedHideExplicit = it }
        }
        scope.launch {
            dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged().collect { cachedHideVideoSongs = it }
        }
        scope.launch {
            dataStore.data.map { it[ShufflePlaylistFirstKey] ?: false }.distinctUntilChanged().collect { cachedShufflePlaylistFirst = it }
        }
        scope.launch {
            dataStore.data.map { it[AutoLoadMoreKey] ?: true }.distinctUntilChanged().collect { cachedAutoLoadMore = it }
        }

        if (dataStore.get(PersistentQueueKey, true)) {
            val queueFile = filesDir.resolve(PERSISTENT_QUEUE_FILE)
            if (queueFile.exists()) {
                runCatching {
                    queueFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        // Convert back to proper queue type
                        val restoredQueue = queue.toQueue()
                        // Wait for player initialization before playing
                        scope.launch {
                            playerInitialized.first { it }
                            if (isActive) {
                                playQueue(
                                    queue = restoredQueue,
                                    playWhenReady = false,
                                )
                            }
                        }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore persisted queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read persisted queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            val automixFile = filesDir.resolve(PERSISTENT_AUTOMIX_FILE)
            if (automixFile.exists()) {
                runCatching {
                    automixFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistQueue
                        }
                    }
                }.onSuccess { queue ->
                    runCatching {
                        automixItems.value = queue.items.map { it.toMediaItem() }
                    }.onFailure { error ->
                        Timber.tag(TAG).w(error, "Failed to restore automix queue, clearing data")
                        clearPersistedQueueFiles()
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read automix queue, clearing data")
                    clearPersistedQueueFiles()
                }
            }

            // Restore player state
            val playerStateFile = filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE)
            if (playerStateFile.exists()) {
                runCatching {
                    playerStateFile.inputStream().use { fis ->
                        ObjectInputStream(fis).use { oos ->
                            oos.readObject() as PersistPlayerState
                        }
                    }
                }.onSuccess { playerState ->
                    // Restore player settings after queue is loaded
                    scope.launch {
                        delay(1000) // Wait for queue to be loaded
                        // Don't restore repeat/shuffle from playerState as they are already set from DataStore (source of truth)
                        // player.repeatMode = playerState.repeatMode
                        // player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume

                        // Restore position if it's still valid
                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }
                    }
                }.onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to read player state, clearing data")
                    clearPersistedQueueFiles()
                }
            }
        }

        // Save queue periodically to prevent queue loss from crash or force kill
        scope.launch {
            while (isActive) {
                delay(15.seconds)
                if (cachedPersistentQueue) {
                    saveQueueToDisk()
                }
                // Also save episode position periodically
                val currentMetadata = player.currentMediaItem?.metadata
                if (currentMetadata?.isEpisode == true && player.isPlaying && player.currentPosition > 0) {
                    previousEpisodePosition = player.currentPosition
                    saveEpisodePosition(currentMetadata.id, player.currentPosition)
                }
            }
        }

        // Save queue more frequently when playing to ensure state is preserved
        scope.launch {
            while (isActive) {
                delay(10.seconds)
                if (cachedPersistentQueue && player.isPlaying) {
                    saveQueueToDisk()
                }
            }
        }
    }

    private fun createExoPlayer(publishToUi: Boolean = true): ExoPlayer {
        val eqProcessor = CustomEqualizerAudioProcessor()
        equalizerService.addAudioProcessor(eqProcessor)

        val silenceProcessor = SilenceDetectorAudioProcessor { handleLongSilenceDetected() }

        // Set initial state
        runBlocking {
            val skipSilence = dataStore.get(SkipSilenceKey, false)
            val instantSkip = dataStore.get(SkipSilenceInstantKey, false)
            silenceProcessor.instantModeEnabled = skipSilence && instantSkip
        }

        val player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(eqProcessor, silenceProcessor))
                .setLoadControl(createLoadControl())
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()

        playerSilenceProcessors[player] = silenceProcessor

        player.apply {
            runBlocking {
                val offload = dataStore.get(AudioOffload, false)
                val crossfade = dataStore.get(CrossfadeEnabledKey, false)
                val metroMix = dataStore.get(MetroMixEnabledKey, false)
                val useOffload = shouldEnableAudioOffload(offload, crossfade || metroMix)
                setOffloadEnabled(useOffload)
                skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
            }
            addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))

            // Cleanup handled manually in onDestroy/release
        }
        if (publishToUi) {
            _playerFlow.value = player
        }
        return player
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes
                        .Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                ).setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }.setAcceptsDelayedFocusGain(true)
                .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f

                if (wasPlayingBeforeAudioFocusLoss && !player.isPlaying && !reentrantFocusGain) {
                    reentrantFocusGain = true
                    scope.launch {
                        delay(300)
                        if (hasAudioFocus && wasPlayingBeforeAudioFocusLoss && !player.isPlaying) {
                            // Don't start local playback if casting
                            if (castConnectionHandler?.isCasting?.value != true) {
                                player.play()
                            }
                            wasPlayingBeforeAudioFocusLoss = false
                        }
                        reentrantFocusGain = false
                    }
                }

                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                abandonAudioFocus()
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 1f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasAudioFocus = false
                audioFocusVolumeMultiplier.value = 0.2f
                wasPlayingBeforeAudioFocusLoss = player.isPlaying
                if (player.isPlaying) {
                    applyEffectiveVolume()
                }
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                audioFocusVolumeMultiplier.value = 1f
                applyEffectiveVolume()
                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    private fun clearPersistedQueueFiles() {
        runCatching { filesDir.resolve(PERSISTENT_QUEUE_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_AUTOMIX_FILE).delete() }
        runCatching { filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE).delete() }
    }

    fun hasAudioFocusForPlayback(): Boolean = hasAudioFocus

    private fun waitOnNetworkError() {
        if (waitingForNetworkConnection.value) return

        // Check if we've exceeded max retry attempts
        if (retryCount >= MAX_RETRY_COUNT) {
            Timber.tag(TAG).w("Max retry count ($MAX_RETRY_COUNT) reached, stopping playback")
            stopOnError()
            retryCount = 0
            return
        }

        waitingForNetworkConnection.value = true

        // Start a retry timer with exponential backoff
        retryJob?.cancel()
        retryJob =
            scope.launch {
                // Exponential backoff: 3s, 6s, 12s, 24s... max 30s
                val delayMs = minOf(3000L * (1 shl retryCount), 30000L)
                Timber.tag(TAG).d("Waiting ${delayMs}ms before retry attempt ${retryCount + 1}/$MAX_RETRY_COUNT")
                delay(delayMs)

                if (isNetworkConnected.value && waitingForNetworkConnection.value) {
                    retryCount++
                    triggerRetry()
                }
            }
    }

    private fun triggerRetry() {
        waitingForNetworkConnection.value = false
        retryJob?.cancel()

        if (player.currentMediaItem != null) {
            // After 3+ failed retries, try to refresh the stream URL by seeking to current position
            // This forces ExoPlayer to re-resolve the data source and get a fresh URL
            if (retryCount > 3) {
                Timber.tag(TAG).d("Retry count > 3, attempting to refresh stream URL")
                val currentPosition = player.currentPosition
                player.seekTo(player.currentMediaItemIndex, currentPosition)
            }
            player.prepare()
            // Don't call play() here - let the player auto-resume via playWhenReady
            // This avoids stealing audio focus during retry attempts
        }
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        val notificationMetadata = currentMediaMetadata.value ?: player.currentMetadata
        val notificationSong = currentSong.value?.song
        val notificationLiked = if (notificationSong?.isEpisode == true) {
            notificationSong.inLibrary != null
        } else {
            notificationSong?.liked ?: notificationMetadata?.liked == true
        }
        mediaSession.setCustomLayout(
            listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (notificationLiked) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    ).setIconResId(if (notificationLiked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(notificationMetadata != null || notificationSong != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> throw IllegalStateException()
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> throw IllegalStateException()
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(R.string.android_auto_target_playlist))
                    .setIconResId(R.drawable.playlist_add)
                    .setSessionCommand(CommandAddToTargetPlaylist)
                    .setEnabled(currentSong.value != null)
                    .build(),
            ),
        )
    }

    private suspend fun recoverSong(
        mediaId: String,
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata =
            withContext(Dispatchers.Main) {
                player.findNextMediaItemById(mediaId)?.metadata
            } ?: return
        val duration =
            song?.song?.duration?.takeIf { it != -1 }
                ?: mediaMetadata.duration.takeIf { it != -1 }
                ?: -1
        database.query {
            if (song == null) {
                insert(mediaMetadata.copy(duration = duration))
            } else {
                var updatedSong = song.song
                if (song.song.duration == -1) {
                    updatedSong = updatedSong.copy(duration = duration)
                }
                // Update isVideo flag if it's different from the current value
                if (song.song.isVideo != mediaMetadata.isVideoSong) {
                    updatedSong = updatedSong.copy(isVideo = mediaMetadata.isVideoSong)
                }
                if (updatedSong != song.song) {
                    update(updatedSong)
                }
            }
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id,
                        )
                    }.forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        // Safety Check : Ensuring player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("playQueue called before player initialization, queuing request")
            scope.launch {
                playerInitialized.first { it }
                playQueue(queue, playWhenReady)
            }
            return
        }

        currentQueue = queue
        queueTitle = null
        val persistShuffleAcrossQueues = dataStore.get(PersistentShuffleAcrossQueuesKey, false)
        val previousShuffleEnabled = player.shuffleModeEnabled
        if (!persistShuffleAcrossQueues) {
            player.shuffleModeEnabled = false
        }
        // Reset original queue size when starting a new queue
        originalQueueSize = 0
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            val initialStatus =
                withContext(Dispatchers.IO) {
                    queue
                        .getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                }
            if (queue.preloadItem != null && player.playbackState == STATE_IDLE) return@launch
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            // Track original queue size for shuffle playlist first feature
            originalQueueSize = initialStatus.items.size
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex),
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size,
                    ),
                )
            } else {
                player.setMediaItems(
                    initialStatus.items,
                    if (initialStatus.mediaItemIndex >
                        0
                    ) {
                        initialStatus.mediaItemIndex
                    } else {
                        0
                    },
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
            }

            // Rebuild shuffle order if shuffle is enabled
            if (player.shuffleModeEnabled) {
                val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
            }
        }
    }

    fun startRadioSeamlessly() {
        // Safety Check: Ensure Player is initilized
        if (!playerInitialized.value) {
            Timber.tag(TAG).w("startRadioSeamlessly called before player initialization")
            return
        }

        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            // Use simple videoId to let YouTube personalize recommendations
            val radioQueue =
                YouTubeQueue(
                    endpoint =
                        WatchEndpoint(
                            videoId = currentMediaId,
                        ),
                )

            try {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        radioQueue
                            .getInitialStatus()
                            .filterExplicit(dataStore.get(HideExplicitKey, false))
                            .filterVideoSongs(dataStore.get(HideVideoSongsKey, false))
                    }

                if (initialStatus.title != null) {
                    queueTitle = initialStatus.title
                }

                // Filter radio items to exclude current media item
                val radioItems =
                    initialStatus.items.filter { item ->
                        item.mediaId != currentMediaId
                    }

                if (radioItems.isNotEmpty()) {
                    val itemCount = player.mediaItemCount

                    if (itemCount > currentIndex + 1) {
                        player.removeMediaItems(currentIndex + 1, itemCount)
                    }

                    player.addMediaItems(currentIndex + 1, radioItems)
                    if (player.shuffleModeEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                currentQueue = radioQueue
            } catch (e: Exception) {
                // Fallback: try with related endpoint
                try {
                    val nextResult =
                        withContext(Dispatchers.IO) {
                            YouTube.next(WatchEndpoint(videoId = currentMediaId)).getOrNull()
                        }
                    nextResult?.relatedEndpoint?.let { relatedEndpoint ->
                        val relatedPage =
                            withContext(Dispatchers.IO) {
                                YouTube.related(relatedEndpoint).getOrNull()
                            }
                        relatedPage?.songs?.let { songs ->
                            val radioItems =
                                songs
                                    .filter { it.id != currentMediaId }
                                    .map { it.toMediaItem() }
                                    .filterExplicit(cachedHideExplicit)
                                    .filterVideoSongs(cachedHideVideoSongs)

                            if (radioItems.isNotEmpty()) {
                                val itemCount = player.mediaItemCount
                                if (itemCount > currentIndex + 1) {
                                    player.removeMediaItems(currentIndex + 1, itemCount)
                                }
                                player.addMediaItems(currentIndex + 1, radioItems)
                                if (player.shuffleModeEnabled) {
                                    applyShuffleOrder(
                                        player.currentMediaItemIndex,
                                        player.mediaItemCount,
                                        cachedShufflePlaylistFirst,
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(SimilarContent, true) &&
            !(dataStore.get(DisableLoadMoreWhenRepeatAllKey, false) && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                try {
                    // Try primary method
                    YouTube
                        .next(WatchEndpoint(playlistId = playlistId))
                        .onSuccess { firstResult ->
                            YouTube
                                .next(WatchEndpoint(playlistId = firstResult.endpoint.playlistId))
                                .onSuccess { secondResult ->
                                    automixItems.value =
                                        secondResult.items.map { song ->
                                            song.toMediaItem()
                                        }
                                }.onFailure {
                                    // Fallback: use first result items
                                    if (firstResult.items.isNotEmpty()) {
                                        automixItems.value =
                                            firstResult.items.map { song ->
                                                song.toMediaItem()
                                            }
                                    }
                                }
                        }.onFailure {
                            // Fallback: try with radio format
                            val currentSong = player.currentMetadata
                            if (currentSong != null) {
                                // Use simple videoId for better personalized recommendations
                                YouTube
                                    .next(
                                        WatchEndpoint(
                                            videoId = currentSong.id,
                                        ),
                                    ).onSuccess { radioResult ->
                                        val filteredItems =
                                            radioResult.items
                                                .filter { it.id != currentSong.id }
                                                .map { it.toMediaItem() }
                                        if (filteredItems.isNotEmpty()) {
                                            automixItems.value = filteredItems
                                        }
                                    }.onFailure {
                                        // Final fallback: try related endpoint
                                        YouTube
                                            .next(WatchEndpoint(videoId = currentSong.id))
                                            .getOrNull()
                                            ?.relatedEndpoint
                                            ?.let { relatedEndpoint ->
                                                YouTube.related(relatedEndpoint).onSuccess { relatedPage ->
                                                    val relatedItems =
                                                        relatedPage.songs
                                                            .filter { it.id != currentSong.id }
                                                            .map { it.toMediaItem() }
                                                    if (relatedItems.isNotEmpty()) {
                                                        automixItems.value = relatedItems
                                                    }
                                                }
                                            }
                                    }
                            }
                        }
                } catch (_: Exception) {
                    // Silent fail
                }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixItems.value = emptyList()
    }

    fun playNext(items: List<MediaItem>) {
        // If queue is empty or player is idle, play immediately instead
        if (player.mediaItemCount == 0 || player.playbackState == STATE_IDLE) {
            player.setMediaItems(items)
            player.prepare()
            // Don't start local playback if casting
            if (castConnectionHandler?.isCasting?.value != true) {
                player.play()
            }
            return
        }

        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        val insertIndex = player.currentMediaItemIndex + 1
        val shuffleEnabled = player.shuffleModeEnabled

        // Insert items immediately after the current item in the window/index space
        player.addMediaItems(insertIndex, items)
        player.prepare()

        if (shuffleEnabled) {
            // Rebuild shuffle order so that newly inserted items are played next
            val timeline = player.currentTimeline
            if (!timeline.isEmpty) {
                val size = timeline.windowCount
                val currentIndex = player.currentMediaItemIndex

                // Newly inserted indices are a contiguous range [insertIndex, insertIndex + items.size)
                val newIndices = (insertIndex until (insertIndex + items.size)).toSet()

                // Collect existing shuffle traversal order excluding current index
                val orderAfter = mutableListOf<Int>()
                var idx = currentIndex
                while (true) {
                    idx = timeline.getNextWindowIndex(idx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (idx == C.INDEX_UNSET) break
                    if (idx != currentIndex) orderAfter.add(idx)
                }

                val prevList = mutableListOf<Int>()
                var pIdx = currentIndex
                while (true) {
                    pIdx = timeline.getPreviousWindowIndex(pIdx, Player.REPEAT_MODE_OFF, /*shuffleModeEnabled=*/true)
                    if (pIdx == C.INDEX_UNSET) break
                    if (pIdx != currentIndex) prevList.add(pIdx)
                }
                prevList.reverse() // preserve original forward order

                val existingOrder = (prevList + orderAfter).filter { it != currentIndex && it !in newIndices }

                // Build new shuffle order: current -> newly inserted (in insertion order) -> rest
                val nextBlock = (insertIndex until (insertIndex + items.size)).toList()
                val finalOrder = IntArray(size)
                var pos = 0
                prevList
                    .filter { it !in newIndices }
                    .forEach { if (it in 0 until size) finalOrder[pos++] = it }
                finalOrder[pos++] = currentIndex
                nextBlock.forEach { if (it in 0 until size) finalOrder[pos++] = it }
                orderAfter
                    .filter { it !in newIndices }
                    .forEach { if (pos < size) finalOrder[pos++] = it }

                // Fill any missing indices (safety) to ensure a full permutation
                if (pos < size) {
                    for (i in 0 until size) {
                        if (!finalOrder.contains(i)) {
                            finalOrder[pos++] = i
                            if (pos == size) break
                        }
                    }
                }

                player.setShuffleOrder(DefaultShuffleOrder(finalOrder, System.currentTimeMillis()))
            }
        }
    }

    fun addToQueue(items: List<MediaItem>) {
        // Remove duplicates if enabled
        if (dataStore.get(PreventDuplicateTracksInQueueKey, false)) {
            val itemIds = items.map { it.mediaId }.toSet()
            val indicesToRemove = mutableListOf<Int>()
            val currentIndex = player.currentMediaItemIndex

            for (i in 0 until player.mediaItemCount) {
                if (i != currentIndex && player.getMediaItemAt(i).mediaId in itemIds) {
                    indicesToRemove.add(i)
                }
            }

            // Remove from highest index to lowest to maintain index stability
            indicesToRemove.sortedDescending().forEach { index ->
                player.removeMediaItem(index)
            }
        }

        player.addMediaItems(items)
        if (player.shuffleModeEnabled) {
            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
        }
        player.prepare()
    }

    fun toggleLibrary() {
        scope.launch {
            val songToToggle = currentSong.first()
            songToToggle?.let {
                val isInLibrary = it.song.inLibrary != null
                val token = if (isInLibrary) it.song.libraryRemoveToken else it.song.libraryAddToken

                // Call YouTube API with feedback token if available
                token?.let { feedbackToken ->
                    YouTube.feedback(listOf(feedbackToken))
                }

                // Update local database
                database.query {
                    update(it.song.toggleLibrary())
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun toggleLike() {
        scope.launch {
            val songToToggle = currentSong.first()
            val metadata = player.currentMetadata ?: currentMediaMetadata.value
            val songEntity = songToToggle?.song ?: metadata?.toSongEntity()
            songEntity?.let { baseSong ->

                // For podcast episodes, toggle save for later instead of like
                if (baseSong.isEpisode) {
                    toggleEpisodeSaveForLater(baseSong)
                    return@let
                }

                val song = baseSong.toggleLike()
                database.query {
                    if (songToToggle == null && metadata != null) {
                        insert(metadata) { song }
                    } else {
                        update(song)
                    }
                    syncUtils.likeSong(song)

                    // Check if auto-download on like is enabled and the song is now liked
                    if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                        // Trigger download for the liked song
                        val downloadRequest =
                            androidx.media3.exoplayer.offline.DownloadRequest
                                .Builder(song.id, song.id.toUri())
                                .setCustomCacheKey(song.id)
                                .setData(song.title.toByteArray())
                                .build()
                        androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                            this@MusicService,
                            ExoDownloadService::class.java,
                            downloadRequest,
                            false,
                        )
                    }
                }
                currentMediaMetadata.value = player.currentMetadata
            }
        }
    }

    fun addToTargetPlaylist() {
        scope.launch {
            val currentSong = currentSong.first() ?: return@launch
            val targetPlaylistId = dataStore.get(AndroidAutoTargetPlaylistKey, MediaSessionConstants.TARGET_PLAYLIST_AUTO)

            if (targetPlaylistId == MediaSessionConstants.TARGET_PLAYLIST_AUTO) {
                Handler(Looper.getMainLooper()).post {
                    Toast
                        .makeText(
                            this@MusicService,
                            getString(R.string.android_auto_target_playlist_not_set),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return@launch
            }

            val targetPlaylist = database.playlist(targetPlaylistId).first()
            if (targetPlaylist != null) {
                database.addSongsToPlaylist(targetPlaylist, listOf(currentSong.id to null), prepend = true)
            }
        }
    }

    private suspend fun toggleEpisodeSaveForLater(songEntity: com.metrolist.music.db.entities.SongEntity) {
        val isCurrentlySaved = songEntity.inLibrary != null
        val shouldBeSaved = !isCurrentlySaved

        // Update database first (optimistic update)
        // Also ensure isEpisode = true so it appears in saved episodes list
        database.query {
            update(
                songEntity.copy(
                    inLibrary = if (isCurrentlySaved) null else java.time.LocalDateTime.now(),
                    isEpisode = true,
                ),
            )
        }
        currentMediaMetadata.value = player.currentMetadata

        // Sync with YouTube (handles login check internally)
        val setVideoId = if (isCurrentlySaved) database.getSetVideoId(songEntity.id)?.setVideoId else null
        syncUtils.saveEpisode(songEntity.id, shouldBeSaved, setVideoId)
    }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    private fun seedLoudnessCacheFromPrefs() {
        normalizationEnabledCached = dataStore.get(AudioNormalizationKey, true)
        loudnessLevelCached = dataStore[LoudnessLevelKey].toEnum(LoudnessLevel.BALANCED)

        Timber.tag(TAG).d(
            "Seeded loudness cache: normalization=$normalizationEnabledCached, level=$loudnessLevelCached"
        )
    }

    private fun applyCachedLoudnessEnhancerNow() {
        val enhancer = loudnessEnhancer ?: return

        try {
            val gain = cachedNormalizationGainMb

            if (cachedNormalizationEnabled && gain != null) {
                enhancer.setTargetGain(gain)
                enhancer.enabled = true
            } else {
                enhancer.enabled = false
            }
        } catch (e: Exception) {
            reportException(e)
            releaseLoudnessEnhancer()
        }
    }

    private fun createLoudnessEnhancerForSessionId(audioSessionId: Int): Boolean {
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId)
            Timber.tag(TAG).d("LoudnessEnhancer created for sessionId=$audioSessionId")

            return true
        } catch (e: Exception) {
            reportException(e)
            loudnessEnhancer = null

            return false
        }
    }

    private fun setupLoudnessEnhancer() {
        val audioSessionId = player.audioSessionId

        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            Timber
                .tag(TAG)
                .w("setupLoudnessEnhancer: invalid audioSessionId ($audioSessionId), cannot create effect yet")
            return
        }

        // Create or recreate enhancer if needed
        if (loudnessEnhancer == null && !createLoudnessEnhancerForSessionId(audioSessionId)) {
            return
        }

        val requestGeneration = ++loudnessSetupGeneration
        loudnessSetupJob?.cancel()

        loudnessSetupJob = scope.launch {
            try {
                val currentMediaId =
                    withContext(Dispatchers.Main) {
                        player.currentMediaItem?.mediaId
                    }

                val normalizeAudio = normalizationEnabledCached

                if (normalizeAudio && currentMediaId != null) {
                    val format =
                        withContext(Dispatchers.IO) {
                            database.format(currentMediaId).first()
                        }

                    val targetLufs = loudnessLevelCached.targetLufs

                    Timber.tag(TAG).d("Audio normalization enabled: $normalizeAudio")
                    Timber
                        .tag(TAG)
                        .d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")

                    // Use perceptualLoudnessDb if available, otherwise fall back to loudnessDb + offset
                    val measuredLufs: Double? = format?.perceptualLoudnessDb
                        ?: format?.loudnessDb?.let { it + LoudnessLevel.AGGRESSIVE.targetLufs }

                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        if (player.audioSessionId != audioSessionId || player.currentMediaItem?.mediaId != currentMediaId) return@withContext

                        when {
                            measuredLufs != null -> {
                                val loudnessDb = measuredLufs - targetLufs
                                val targetGain = (-loudnessDb * 100.0).toInt()
                                val clampedGain = targetGain.coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)

                                Timber.tag(TAG)
                                    .d("Normalization Target LUFS: $targetLufs, Measured LUFS: $measuredLufs, Calculated gain: $targetGain mB, Clamped gain: $clampedGain mB")

                                Timber.tag(TAG)
                                    .d("Calculated raw normalization gain: $targetGain mB (from loudness: $loudnessDb)")

                                cachedNormalizationGainMb = clampedGain
                                cachedNormalizationEnabled = true
                                loudnessEnhancer?.setTargetGain(clampedGain)
                                loudnessEnhancer?.enabled = true
                            }

                            format == null -> {
                                // Row not available yet for new track: keep carry-over gain to avoid a jump.
                                Timber.tag(TAG).d("Loudness row not ready yet; keeping cached normalization state")
                            }

                            else -> {
                                cachedNormalizationGainMb = null
                                cachedNormalizationEnabled = false
                                loudnessEnhancer?.enabled = false
                                Timber.tag(TAG).w("No loudness data available for track - normalization disabled")
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (!isActive || requestGeneration != loudnessSetupGeneration) return@withContext
                        cachedNormalizationGainMb = null
                        cachedNormalizationEnabled = false
                        loudnessEnhancer?.enabled = false
                        Timber.tag(TAG).d("setupLoudnessEnhancer: normalization disabled or mediaId unavailable")
                    }
                }
            } catch (e: CancellationException) {
                Timber.tag(TAG).d("setupLoudnessEnhancer: job cancelled, likely due to new setup request or session change")
                throw e
            } catch (e: Exception) {
                reportException(e)
                releaseLoudnessEnhancer()
            }
        }
    }

    private fun releaseLoudnessEnhancer(clearNormalizationCache: Boolean = true) {
        try {
            loudnessEnhancer?.release()
            Timber.tag(TAG).d("LoudnessEnhancer released")
        } catch (e: Exception) {
            reportException(e)
            Timber.tag(TAG).e(e, "Error releasing LoudnessEnhancer: ${e.message}")
        } finally {
            if (clearNormalizationCache) {
                cachedNormalizationGainMb = null
                cachedNormalizationEnabled = false
            }
            loudnessEnhancer = null
        }
    }

    private fun openAudioEffectSession() {
        val audioSessionId = player.audioSessionId
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId <= 0) {
            Timber.tag(TAG).w("openAudioEffectSession: invalid audioSessionId=$audioSessionId")
            return
        }

        if (isAudioEffectSessionOpened &&
            openedAudioEffectSessionId == audioSessionId &&
            loudnessEnhancer != null
        ) {
            applyCachedLoudnessEnhancerNow()

            if (!cachedNormalizationEnabled || cachedNormalizationGainMb == null) {
                setupLoudnessEnhancer()
            }

            return
        }

        if (isAudioEffectSessionOpened && openedAudioEffectSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = openedAudioEffectSessionId, clearNormalizationCache = false)
        } else {
            releaseLoudnessEnhancer(clearNormalizationCache = false)
        }

        val enhancerReady = loudnessEnhancer != null || createLoudnessEnhancerForSessionId(audioSessionId)

        if (!enhancerReady) {
            isAudioEffectSessionOpened = false
            openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
            Timber.tag(TAG).w("openAudioEffectSession: failed to create LoudnessEnhancer for sessionId=$audioSessionId, audio effects will be unavailable")
            return
        }

        isAudioEffectSessionOpened = true
        openedAudioEffectSessionId = audioSessionId

        applyCachedLoudnessEnhancerNow()

        if (!cachedNormalizationEnabled || cachedNormalizationGainMb == null) {
            setupLoudnessEnhancer()
        }

        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            },
        )
    }

    private fun closeAudioEffectSession(sessionIdOverride: Int? = null, clearNormalizationCache: Boolean = true) {
        val sessionIdToClose = sessionIdOverride ?: openedAudioEffectSessionId

        loudnessSetupGeneration++
        loudnessSetupJob?.cancel()
        loudnessSetupJob = null

        // Guard: only release/reset state if closing the currently active session
        val isClosingCurrentSession =
            isAudioEffectSessionOpened &&
                    openedAudioEffectSessionId != C.AUDIO_SESSION_ID_UNSET &&
                    sessionIdToClose == openedAudioEffectSessionId

        if (isClosingCurrentSession) {
            if (loudnessEnhancer != null) {
                releaseLoudnessEnhancer(clearNormalizationCache = clearNormalizationCache)
            }

            isAudioEffectSessionOpened = false
            openedAudioEffectSessionId = C.AUDIO_SESSION_ID_UNSET
        }

        // Broadcast close for the requested session (even if stale)
        if (sessionIdToClose != C.AUDIO_SESSION_ID_UNSET && sessionIdToClose > 0) {
            sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionIdToClose)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                },
            )
        }
    }

    private var previousMediaItemIndex = C.INDEX_UNSET
    private var previousEpisodeId: String? = null
    private var previousEpisodePosition: Long = 0L

    /**
     * Save podcast episode playback position to database.
     * Only saves if the item is an episode and position is meaningful (> 3 seconds).
     */
    private fun saveEpisodePosition(
        episodeId: String,
        positionMs: Long,
    ) {
        if (positionMs < 3000) return // Don't save if less than 3 seconds played
        scope.launch(Dispatchers.IO + SilentHandler) {
            database.updatePlaybackPosition(episodeId, positionMs)
            Timber.tag(TAG).d("Saved episode position: $episodeId at ${positionMs}ms")
        }
    }

    /**
     * Restore podcast episode playback position from database.
     * Seeks to saved position if available.
     */
    private fun restoreEpisodePosition(episodeId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            val savedPosition = database.getPlaybackPosition(episodeId)
            if (savedPosition != null && savedPosition > 0) {
                withContext(Dispatchers.Main) {
                    // Only seek if we're still on the same episode
                    if (player.currentMediaItem?.mediaId == episodeId) {
                        player.seekTo(savedPosition)
                        Timber.tag(TAG).d("Restored episode position: $episodeId to ${savedPosition}ms")
                    }
                }
            }
        }
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
            skipAppleOnceMediaIds.clear()
            skipTidalLiveManifestOnceMediaIds.clear()
            AppleMusicDecryptPipeline.clearMemoryCaches()
        }

        // Save previous episode position if it was an episode
        previousEpisodeId?.let { episodeId ->
            if (previousEpisodePosition > 0) {
                saveEpisodePosition(episodeId, previousEpisodePosition)
            }
        }
        previousEpisodeId = null
        previousEpisodePosition = 0L

        // Check if new item is an episode and restore its position
        val newMetadata = mediaItem?.metadata
        currentMediaMetadata.value = newMetadata ?: player.currentMetadata
        updateCurrentAudioFormatFromTracks(player.currentTracks)
        (newMetadata?.id ?: mediaItem?.mediaId)
            ?.takeIf { it.isNotBlank() }
            ?.let(::scheduleAudioFormatRetry)
        if (newMetadata?.isEpisode == true) {
            previousEpisodeId = newMetadata.id
            // Delay restoration to let playback start
            scope.launch {
                delay(100)
                restoreEpisodePosition(newMetadata.id)
            }
        }

        // Force Repeat One if the player ignored it and auto-advanced
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (player.repeatMode == REPEAT_MODE_ONE &&
                previousMediaItemIndex != C.INDEX_UNSET &&
                previousMediaItemIndex != player.currentMediaItemIndex
            ) {
                player.seekTo(previousMediaItemIndex, 0)
            }
        }
        previousMediaItemIndex = player.currentMediaItemIndex

        lastPlaybackSpeed = -1.0f // force update song

        setupLoudnessEnhancer()

        discordUpdateJob?.cancel()

        scrobbleManager?.onSongStop()
        if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
            scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
        }

        // Sync Cast when media changes and Cast is connected
        // Skip if this change was triggered by Cast sync (to prevent loops)
        if (castConnectionHandler?.isCasting?.value == true &&
            castConnectionHandler?.isSyncingFromCast != true &&
            mediaItem != null
        ) {
            val metadata = mediaItem.metadata
            if (metadata != null) {
                // Try to navigate to the item if it's already in Cast queue
                // This avoids a full reload which causes the widget to refresh
                val navigated = castConnectionHandler?.navigateToMediaIfInQueue(metadata.id) ?: false
                if (!navigated) {
                    // Item not in Cast queue, need to reload
                    castConnectionHandler?.loadMedia(metadata)
                }
            }
        }

        // Auto load more songs from queue
        if (cachedAutoLoadMore &&
            reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
            player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
            currentQueue.hasNextPage() &&
            !(cachedDisableLoadMoreWhenRepeatAll && player.repeatMode == REPEAT_MODE_ALL)
        ) {
            scope.launch(SilentHandler) {
                val mediaItems =
                    withContext(Dispatchers.IO) {
                        currentQueue
                            .nextPage()
                            .filterExplicit(cachedHideExplicit)
                            .filterVideoSongs(cachedHideVideoSongs)
                    }
                if (player.playbackState != STATE_IDLE && mediaItems.isNotEmpty()) {
                    player.addMediaItems(mediaItems)
                    if (player.shuffleModeEnabled) {
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, cachedShufflePlaylistFirst)
                    }
                }
            }
        }

        // Save state when media item changes
        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    override fun onPlaybackStateChanged(
        @Player.State playbackState: Int,
    ) {
        // Handle autoplay - skip to next song when playback ends
        if (playbackState == Player.STATE_ENDED) {
            // Check sleep timer guard - don't autoplay/repeat if sleep timer will pause
            if (sleepTimer.isActive && sleepTimer.pauseWhenSongEnd) {
                return
            }

            val repeatMode = player.repeatMode

            // Handle Repeat All mode
            if (repeatMode == REPEAT_MODE_ALL && player.mediaItemCount > 0) {
                player.seekTo(0, 0)
                player.prepare()
                player.play()
                return
            }

            // Handle Repeat One mode - restart current song
            if (repeatMode == REPEAT_MODE_ONE) {
                player.seekTo(player.currentMediaItemIndex, 0)
                player.prepare()
                player.play()
                return
            }

            // Handle autoplay - check if there's a next item to play
            if (cachedAutoplay && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                if (castConnectionHandler?.isCasting?.value != true) {
                    player.play()
                }
            }
        }

        // Save state when playback state changes (but not during silence skipping)
        if (cachedPersistentQueue && !isSilenceSkipping) {
            saveQueueToDisk()
        }

        if (playbackState == Player.STATE_READY) {
            consecutivePlaybackErr = 0
            retryCount = 0
            waitingForNetworkConnection.value = false
            retryJob?.cancel()
            currentMediaMetadata.value = player.currentMetadata
            updateCurrentAudioFormatFromTracks(player.currentTracks)
            player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let(::scheduleAudioFormatRetry)
            player.currentMediaItem?.localConfiguration?.uri
                ?.takeIf { AppleMusicWrapperDataSource.isAppleUri(it) }
                ?.let {
                    Timber.tag("AppleALAC").d(
                        "playback_ready mediaId=${player.currentMediaItem?.mediaId} duration=${player.duration}",
                    )
                }

            // Reset retry count for current song on successful playback
            player.currentMediaItem?.mediaId?.let { mediaId ->
                resetRetryCount(mediaId)
                Timber.tag(TAG).d("Playback successful for $mediaId, reset retry count")
            }
            scheduleCrossfade()
        }

        if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            scrobbleManager?.onSongStop()
        }
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        // Safety net: if local player tries to start while casting, immediately pause it
        if (playWhenReady && castConnectionHandler?.isCasting?.value == true) {
            player.pause()
            return
        }

        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) {
            if (playWhenReady) {
                isPausedByVolumeMute = false
            }

            if (!playWhenReady && !isPausedByVolumeMute) {
                wasPlayingBeforeVolumeMute = false
            }
        }

        // Save episode position when pausing
        if (!playWhenReady) {
            val currentMetadata = player.currentMediaItem?.metadata
            if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
                saveEpisodePosition(currentMetadata.id, player.currentPosition)
                previousEpisodePosition = player.currentPosition
            }
        }

        if (playWhenReady) {
            applyCachedLoudnessEnhancerNow()
        }
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
            )
        ) {
            scheduleCrossfade()
            val isBufferingOrReady =
                player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
            if (isBufferingOrReady && player.playWhenReady) {
                val focusGranted = requestAudioFocus()
                if (focusGranted) {
                    openAudioEffectSession()
                }
            } else if (player.playbackState == Player.STATE_IDLE) {
                closeAudioEffectSession()
            }
        }
        if (events.containsAny(
                EVENT_TIMELINE_CHANGED,
                EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
            )
        ) {
            currentMediaMetadata.value = player.currentMetadata
            preloadUpcomingAppleCanvases()
        }
        if (events.containsAny(
                Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            updateCurrentAudioFormatFromTracks(player.currentTracks)
        }

        // Widget and Discord RPC updates
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            updateWidgetUI(player.isPlaying)
            if (player.isPlaying) {
                startWidgetUpdates()
            } else {
                stopWidgetUpdates()
            }
            if (!player.isPlaying &&
                !events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                )
            ) {
                scope.launch {
                    discordRpc?.close()
                }
            }
        }

        // Update Discord RPC when media item changes or playback starts
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_IS_PLAYING_CHANGED,
            ) && player.isPlaying
        ) {
            val mediaId = player.currentMetadata?.id
            if (mediaId != null) {
                scope.launch {
                    // Fetch song from database to get full info
                    database.song(mediaId).first()?.let { song ->
                        updateDiscordRPC(song)
                    }
                }
            }
        }

        // Scrobbling
        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
            scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (shuffleModeEnabled) {
            // If queue is empty, don't shuffle
            if (player.mediaItemCount == 0) return

            val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
            val currentIndex = player.currentMediaItemIndex
            val totalCount = player.mediaItemCount

            applyShuffleOrder(currentIndex, totalCount, shufflePlaylistFirst)
        }

        // Save shuffle mode to preferences
        if (dataStore.get(RememberShuffleAndRepeatKey, true)) {
            scope.launch {
                dataStore.edit { settings ->
                    settings[ShuffleModeKey] = shuffleModeEnabled
                }
            }
        }

        // Save state when shuffle mode changes
        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateNotification()
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        // Save state when repeat mode changes
        if (cachedPersistentQueue) {
            saveQueueToDisk()
        }
    }

    /**
     * Applies a new shuffle order to the player, maintaining the current item's position.
     * If `shufflePlaylistFirst` is true, it attempts to shuffle original items separately from added items.
     */
    private fun applyShuffleOrder(
        currentIndex: Int,
        totalCount: Int,
        shufflePlaylistFirst: Boolean,
    ) {
        if (totalCount == 0) return

        if (shufflePlaylistFirst && originalQueueSize > 0 && originalQueueSize < totalCount) {
            // Shuffle original items and added items separately
            val originalIndices = (0 until originalQueueSize).filter { it != currentIndex }.toMutableList()
            val addedIndices = (originalQueueSize until totalCount).filter { it != currentIndex }.toMutableList()

            originalIndices.shuffle()
            addedIndices.shuffle()

            val shuffledIndices = IntArray(totalCount)
            var pos = 0
            shuffledIndices[pos++] = currentIndex

            if (currentIndex < originalQueueSize) {
                originalIndices.forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            } else {
                (0 until originalQueueSize).shuffled().forEach { shuffledIndices[pos++] = it }
                addedIndices.forEach { shuffledIndices[pos++] = it }
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        } else {
            val shuffledIndices = IntArray(totalCount) { it }
            shuffledIndices.shuffle()
            // Ensure current item is first in the shuffle order
            val currentItemIndexInShuffled = shuffledIndices.indexOf(currentIndex)
            if (currentItemIndexInShuffled != -1) { // Should always be true if totalCount > 0
                val temp = shuffledIndices[0]
                shuffledIndices[0] = shuffledIndices[currentItemIndexInShuffled]
                shuffledIndices[currentItemIndexInShuffled] = temp
            }
            player.setShuffleOrder(DefaultShuffleOrder(shuffledIndices, System.currentTimeMillis()))
        }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        super.onPlaybackParametersChanged(playbackParameters)
        if (playbackParameters.speed != lastPlaybackSpeed) {
            lastPlaybackSpeed = playbackParameters.speed
            discordUpdateJob?.cancel()

            // update scheduling thingy
            discordUpdateJob =
                scope.launch {
                    delay(1000)
                    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        currentSong.value?.let { song ->
                            updateDiscordRPC(song)
                        }
                    }
                }
        }
    }

    /**
     * Extracts the HTTP response code from an error's cause chain.
     * Returns null if no HTTP response code is found.
     */
    private fun getHttpResponseCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    /**
     * Checks if the error is caused by an expired/forbidden URL (HTTP 403).
     * This typically happens when a YouTube stream URL expires.
     */
    private fun isExpiredUrlError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 403
    }

    /**
     * Checks if the error is a Range Not Satisfiable error (HTTP 416).
     * This happens when cached data doesn't match the actual stream size.
     */
    private fun isRangeNotSatisfiableError(error: PlaybackException): Boolean {
        val responseCode = getHttpResponseCode(error)
        return responseCode == 416
    }

    /**
     * Checks if the error is a "page needs to be reloaded" error.
     * This is a YouTube-specific error that requires refreshing the stream.
     */
    private fun isPageReloadError(error: PlaybackException): Boolean {
        val errorMessage = error.message?.lowercase() ?: ""
        val causeMessage = error.cause?.message?.lowercase() ?: ""
        val innerCauseMessage =
            error.cause
                ?.cause
                ?.message
                ?.lowercase() ?: ""

        val reloadKeywords =
            listOf(
                "page needs to be reloaded",
                "pagina deve essere ricaricata",
                "la pagina deve essere ricaricata",
                "page must be reloaded",
                "reload",
                "ricaricata",
            )

        return reloadKeywords.any { keyword ->
            errorMessage.contains(keyword) ||
                causeMessage.contains(keyword) ||
                innerCauseMessage.contains(keyword)
        }
    }

    private fun isNetworkRelatedError(error: PlaybackException): Boolean {
        // Don't treat specific errors as network errors - they need special handling
        if (isExpiredUrlError(error) || isRangeNotSatisfiableError(error) || isPageReloadError(error)) {
            return false
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.cause is java.net.ConnectException ||
            error.cause is java.net.UnknownHostException ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
    }

    /**
     * Checks if the error is caused by AudioTrack write or initialization failures.
     * These errors indicate the audio renderer is in a corrupted/invalid state.
     */
    private fun isAudioRendererError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    private fun isAppleAlacDecodingError(error: PlaybackException): Boolean {
        return error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED &&
            (error.containsInCauseChain("audio/alac") || error.containsInCauseChain("alac"))
    }

    private fun isAppleAlacContainerParsingError(mediaId: String?, error: PlaybackException): Boolean {
        if (mediaId == null) return false
        if (error.errorCode != PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) return false
        if (
            !error.containsInCauseChain("Skipping atom with length") &&
            !error.containsInCauseChain("unsupported atom") &&
            !error.containsInCauseChain("invalid atom") &&
            !error.containsInCauseChain("NoDeclaredBrand")
        ) {
            return false
        }
        return isCurrentAppleWrapperPlayback(mediaId)
    }

    private fun isAppleSourceRoutingError(error: PlaybackException): Boolean =
        error.containsInCauseChain("Apple Music ALAC was resolved after Media3 selected a progressive source") ||
            error.containsInCauseChain("Apple wrapper URI reached DataSource before HLS source selection")

    private fun isTidalSourceRoutingError(error: PlaybackException): Boolean =
        error.containsInCauseChain("TIDAL stream was resolved after Media3 selected a DASH source") ||
            (
                isCurrentTidalLiveManifestPlayback() &&
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED &&
                    (
                        error.containsInCauseChain("ftypisom") ||
                            error.containsInCauseChain("Unexpected token")
                    )
            )

    private fun isCurrentAppleWrapperPlayback(mediaId: String?): Boolean {
        val currentUri = player.currentMediaItem?.localConfiguration?.uri
        if (currentUri != null && AppleMusicWrapperDataSource.isAppleUri(currentUri)) return true
        if (mediaId == null) return false
        return songUrlCache[mediaId]
            ?.uri
            ?.toUri()
            ?.let(AppleMusicWrapperDataSource::isAppleUri)
            ?: false
    }

    private fun CachedSongStream.isFallbackStream(mediaId: String): Boolean =
        cacheKey == qobuzFallbackCacheKey(mediaId) ||
            isTidalFallbackCacheKey(cacheKey) ||
            cacheKey == deezerFallbackCacheKey(mediaId) ||
            cacheKey == soundCloudFallbackCacheKey(mediaId) ||
            cacheKey == instagramFallbackCacheKey(mediaId) ||
            cacheKey == youtubeFallbackCacheKey(mediaId)

    private fun Throwable.containsInCauseChain(fragment: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current.message?.contains(fragment, ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun Throwable.firstCauseMessageContaining(vararg fragments: String): String? {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message
            if (message != null && fragments.any { message.contains(it, ignoreCase = true) }) {
                return message.take(220)
            }
            current = current.cause
        }
        return null
    }

    private fun alacFailureDetail(error: PlaybackException): String {
        return error.firstCauseMessageContaining(
            "ALAC",
            "wrapper-manager",
            "audio/alac",
            "Skipping atom",
            "unsupported atom",
            "invalid atom",
            "Ffmpeg",
            "progressive source",
            "HLS source selection",
        ) ?: "errorCode=${error.errorCode}"
    }

    private fun handleAppleAlacFailure(
        mediaId: String?,
        reason: String,
    ) {
        if (DEBUG_DISABLE_APPLE_ALAC_PROVIDER_FALLBACK) {
            Timber.tag(TAG).w(
                "Apple ALAC fallback disabled for debugging; surfacing failure for $mediaId: $reason",
            )
            stopOnError()
            return
        }
        handleAppleAlacProviderFallback(mediaId, reason)
    }

    /**
     * Checks if the error is an IO_FILE_NOT_FOUND (ENOENT).
     *
     * In practice this surfaces when the player cache reports a chunk as cached
     * but the backing file has been evicted/removed (e.g. LRU eviction racing
     * with a buffer read, an external cache wipe, or partial corruption).
     * CacheDataSource then falls back to the upstream DefaultDataSource with a
     * URI that is just the bare mediaId (no scheme), which is interpreted as a
     * local file path and fails to open.
     */
    private fun isFileNotFoundError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)

        // Safety check : ensuring player is still initialized
        if (!playerInitialized.value) {
            Timber.tag(TAG).e(error, "Player error occurred but player not initialized")
            return
        }

        val mediaId = player.currentMediaItem?.mediaId
        Timber
            .tag(TAG)
            .w(error, "Player error occurred for $mediaId: errorCode=${error.errorCode}, message=${error.message}")

        if (isTidalSourceRoutingError(error)) {
            Timber.tag(TAG).d("TIDAL source routing retry needed for $mediaId")
            handleTidalSourceRoutingRetry(mediaId)
            return
        }

        if (dataStore.get(StopOnProviderErrorKey, false)) {
            Timber.tag(TAG).w("Stop-on-provider-error is enabled; surfacing playback error without retry/fallback")
            reportException(error)
            stopOnError()
            return
        }

        // Check if this song has failed too many times
        if (mediaId != null && hasExceededRetryLimit(mediaId)) {
            Timber.tag(TAG).w("Song $mediaId has exceeded retry limit, skipping")
            markSongAsFailed(mediaId)
            handleFinalFailure()
            return
        }

        reportException(error)

        // Aggressive cache clearing for all playback errors
        if (mediaId != null) {
            performAggressiveCacheClear(mediaId)
        }

        if (AppleMusicDecryptPipeline.isAlacTimeout(error)) {
            val detail = alacFailureDetail(error)
            Timber.tag(TAG).d("ALAC decrypt timeout detected ($detail)")
            handleAppleAlacFailure(mediaId, "ALAC decrypt timeout: $detail")
            return
        }

        if (AppleMusicDecryptPipeline.isAlacIntegrityError(error) || isAppleAlacContainerParsingError(mediaId, error)) {
            val detail = alacFailureDetail(error)
            Timber.tag(TAG).d("ALAC container failure detected ($detail)")
            handleAppleAlacFailure(mediaId, "ALAC container failure: $detail")
            return
        }

        if (isAppleAlacDecodingError(error)) {
            val detail = alacFailureDetail(error)
            Timber.tag(TAG).d("ALAC decoding failure detected ($detail)")
            handleAppleAlacFailure(mediaId, "ALAC decoding failure: $detail")
            return
        }

        if (isAppleSourceRoutingError(error)) {
            val detail = alacFailureDetail(error)
            Timber.tag(TAG).d("ALAC source routing failure detected ($detail)")
            handleAppleAlacFailure(mediaId, "ALAC source routing failure: $detail")
            return
        }

        if (isCurrentAppleWrapperPlayback(mediaId)) {
            val detail = alacFailureDetail(error)
            Timber.tag(TAG).d("Apple wrapper playback failure detected ($detail)")
            handleAppleAlacFailure(mediaId, "Apple wrapper playback failure: $detail")
            return
        }

        if (isCurrentTidalLiveManifestPlayback()) {
            Timber.tag(TAG).d("TIDAL live manifest playback failed; retrying as progressive stream")
            handleTidalLiveManifestFailure(mediaId)
            return
        }

        // Handle specific error types with strict strategies
        when {
            isAudioRendererError(error) -> {
                Timber.tag(TAG).d("AudioTrack error detected (${error.errorCode}), performing safe recovery")
                handleAudioRendererError(mediaId)
                return
            }

            isRangeNotSatisfiableError(error) -> {
                Timber.tag(TAG).d("Range Not Satisfiable (416) detected, performing strict recovery")
                handleRangeNotSatisfiableError(mediaId)
                return
            }

            isPageReloadError(error) -> {
                Timber.tag(TAG).d("Page reload error detected, performing strict recovery")
                handlePageReloadError(mediaId)
                return
            }

            isExpiredUrlError(error) -> {
                Timber.tag(TAG).d("Expired URL (403) detected, refreshing stream URL")
                handleExpiredUrlError(mediaId)
                return
            }

            isFileNotFoundError(error) -> {
                Timber.tag(TAG).d("Cache file missing (ENOENT) detected, refreshing stream")
                handleFileNotFoundError(mediaId)
                return
            }

            !isNetworkConnected.value || isNetworkRelatedError(error) -> {
                Timber.tag(TAG).d("Network-related error detected, waiting for connection")
                waitOnNetworkError()
                return
            }
        }

        // For IO_UNSPECIFIED and IO_BAD_HTTP_STATUS, try recovery first
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        ) {
            Timber.tag(TAG).d("IO error detected (${error.errorCode}), attempting recovery")
            handleGenericIOError(mediaId)
            return
        }

        // Final fallback
        if (dataStore.get(AutoSkipNextOnErrorKey, false)) {
            Timber.tag(TAG).d("Auto-skipping to next track due to unrecoverable error")
            skipOnError()
        } else {
            Timber.tag(TAG).d("Stopping playback due to unrecoverable error")
            stopOnError()
        }
    }

    /**
     * Performs aggressive cache clearing for a media item.
     * Clears both player cache and download cache, plus URL cache.
     */
    private fun performAggressiveCacheClear(mediaId: String) {
        Timber.tag(TAG).d("Performing aggressive cache clear for $mediaId")

        // Clear URL cache
        songUrlCache.remove(mediaId)
        AppleMusicSongResolver.invalidate(mediaId)
        QobuzAudioProvider.invalidate(mediaId)
        TidalAudioProvider.invalidate(mediaId)
        DeezerAudioProvider.invalidate(mediaId)
        SoundCloudAudioProvider.invalidate(mediaId)
        InstagramAudioProvider.invalidate(mediaId)
        YouTubeAudioProvider.invalidate(mediaId)
        AppleMusicDecryptPipeline.clearMemoryCaches()

        // Clear player cache
        try {
            playerCache.removeResource(mediaId)
            playerCache.removeResource(appleWrapperCacheKey(mediaId))
            playerCache.removeResource(qobuzFallbackCacheKey(mediaId))
            playerCache.removeResource(tidalFallbackCacheKey(mediaId))
            playerCache.removeResource(deezerFallbackCacheKey(mediaId))
            playerCache.removeResource(soundCloudFallbackCacheKey(mediaId))
            playerCache.removeResource(instagramFallbackCacheKey(mediaId))
            playerCache.removeResource(youtubeFallbackCacheKey(mediaId))
            downloadCache.removeResource(mediaId)
            downloadCache.removeResource(appleWrapperCacheKey(mediaId))
            downloadCache.removeResource(qobuzFallbackCacheKey(mediaId))
            downloadCache.removeResource(tidalFallbackCacheKey(mediaId))
            downloadCache.removeResource(deezerFallbackCacheKey(mediaId))
            downloadCache.removeResource(soundCloudFallbackCacheKey(mediaId))
            downloadCache.removeResource(instagramFallbackCacheKey(mediaId))
            downloadCache.removeResource(youtubeFallbackCacheKey(mediaId))
            Timber.tag(TAG).d("Cleared player cache for $mediaId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear player cache for $mediaId")
        }

        Timber.tag(TAG).d("Cleared Apple wrapper resolver and decrypt caches for $mediaId")
    }

    private fun handleAppleAlacProviderFallback(
        mediaId: String?,
        reason: String,
    ) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)
        skipAppleOnceMediaIds.add(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex == C.INDEX_UNSET) {
                    handleFinalFailure()
                    return@launch
                }
                delay(150L)
                player.seekTo(currentIndex, player.currentPosition.coerceAtLeast(0L))
                player.prepare()
                Timber.tag(TAG).d("Retrying $mediaId after $reason using fallback providers")
            }
    }

    private fun showPlaybackToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast
                .makeText(this@MusicService, message, Toast.LENGTH_LONG)
                .show()
        }
    }

    /**
     * Checks if a song has exceeded the retry limit.
     */
    private fun hasExceededRetryLimit(mediaId: String): Boolean {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        return currentRetries >= MAX_RETRY_PER_SONG
    }

    /**
     * Increments the retry count for a song.
     */
    private fun incrementRetryCount(mediaId: String) {
        val currentRetries = currentMediaIdRetryCount[mediaId] ?: 0
        currentMediaIdRetryCount[mediaId] = currentRetries + 1
        Timber.tag(TAG).d("Retry count for $mediaId: ${currentRetries + 1}/$MAX_RETRY_PER_SONG")
    }

    /**
     * Resets the retry count for a song (called on successful playback).
     */
    private fun resetRetryCount(mediaId: String) {
        currentMediaIdRetryCount.remove(mediaId)
        recentlyFailedSongs.remove(mediaId)
        skipAppleOnceMediaIds.remove(mediaId)
        skipTidalLiveManifestOnceMediaIds.remove(mediaId)
    }

    /**
     * Marks a song as failed to prevent further retry attempts.
     */
    private fun markSongAsFailed(mediaId: String) {
        recentlyFailedSongs.add(mediaId)
        currentMediaIdRetryCount.remove(mediaId)

        // Schedule cleanup of failed songs list after 5 minutes
        failedSongsClearJob?.cancel()
        failedSongsClearJob =
            scope.launch {
                delay(5 * 60 * 1000L) // 5 minutes
                recentlyFailedSongs.clear()
                Timber.tag(TAG).d("Cleared recently failed songs list")
            }
    }

    /**
     * Handles AudioTrack errors (write failed, init failed) with safe recovery.
     * These errors indicate the audio renderer is corrupted and needs careful reset.
     */
    private fun handleAudioRendererError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                try {
                    // Pause playback immediately to stop the renderer
                    player.pause()
                    Timber.tag(TAG).d("Paused playback due to AudioTrack error")

                    // Wait longer for audio renderer to settle before retry
                    // This prevents the renderer from continuing to fail in a loop
                    delay(RETRY_DELAY_MS * 3) // 3 seconds instead of 1 second

                    // Check if player is still initialized before attempting recovery
                    if (!playerInitialized.value) {
                        Timber.tag(TAG).w("Player no longer initialized, aborting AudioTrack recovery")
                        return@launch
                    }

                    val currentIndex = player.currentMediaItemIndex
                    if (currentIndex != C.INDEX_UNSET) {
                        // Seek to current position to force a clean audio renderer reinit
                        val currentPosition = player.currentPosition
                        player.seekTo(currentIndex, currentPosition)
                        player.prepare()

                        Timber.tag(TAG).d("Retrying playback for $mediaId after AudioTrack error")

                        // Resume playback if it wasn't paused by user
                        if (wasPlayingBeforeAudioFocusLoss) {
                            delay(500) // Brief delay to allow renderer to be ready
                            if (hasAudioFocus && playerInitialized.value) {
                                if (castConnectionHandler?.isCasting?.value != true) {
                                    player.play()
                                }
                            }
                        }
                    } else {
                        Timber.tag(TAG).w("Invalid media item index during AudioTrack recovery")
                        handleFinalFailure()
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error during AudioTrack error recovery")
                    handleFinalFailure()
                }
            }
    }

    /**
     * Handles Range Not Satisfiable (416) errors with strict recovery.
     * This error occurs when cached data doesn't match the actual stream size.
     */
    private fun handleRangeNotSatisfiableError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                // Clear all caches aggressively
                performAggressiveCacheClear(mediaId)

                // Wait before retry
                delay(RETRY_DELAY_MS)

                // Force re-prepare from position 0 to avoid range issues
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, 0)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 416 error (from position 0)")
            }
    }

    /**
     * Handles "page needs to be reloaded" errors with strict recovery.
     * This requires clearing decryption caches and getting fresh stream URLs.
     */
    private fun handlePageReloadError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                Timber.tag(TAG).d("Handling page reload error for $mediaId")

                // Clear all caches including decryption caches
                performAggressiveCacheClear(mediaId)

                // Additional delay for page reload errors as they may be rate-limited
                delay(RETRY_DELAY_MS * 2)

                // Re-prepare the player
                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after page reload error")
            }
    }

    /**
     * Handles expired URL (403) errors by clearing caches and retrying.
     */
    private fun handleExpiredUrlError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        // Clear the cached URL
        songUrlCache.remove(mediaId)
        AppleMusicSongResolver.invalidate(mediaId)
        QobuzAudioProvider.invalidate(mediaId)
        TidalAudioProvider.invalidate(mediaId)
        DeezerAudioProvider.invalidate(mediaId)
        SoundCloudAudioProvider.invalidate(mediaId)
        InstagramAudioProvider.invalidate(mediaId)
        YouTubeAudioProvider.invalidate(mediaId)
        AppleMusicDecryptPipeline.clearMemoryCaches()
        Timber.tag(TAG).d("Cleared cached URL for $mediaId")

        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_DELAY_MS)

                // Seek to current position to force URL re-resolution
                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after 403 error")
            }
    }

    /**
     * Handles IO_FILE_NOT_FOUND (ENOENT) by purging any cached state for the
     * media item and forcing the resolver to fetch a fresh stream URL.
     *
     * The aggressive cache clear at the top of [onPlayerError] already drops
     * the player cache entry and the cached stream URL, so re-preparing the
     * player here causes the resolver to take the "fetch fresh stream" path
     * instead of attempting another cache read for a file that no longer
     * exists on disk.
     */
    private fun handleFileNotFoundError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                delay(RETRY_DELAY_MS)

                val currentPosition = player.currentPosition
                val currentIndex = player.currentMediaItemIndex
                if (currentIndex == C.INDEX_UNSET) {
                    Timber.tag(TAG).w("Invalid media item index during file-not-found recovery")
                    handleFinalFailure()
                    return@launch
                }
                player.seekTo(currentIndex, currentPosition)
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after IO_FILE_NOT_FOUND")
            }
    }

    /**
     * Handles generic IO errors with recovery attempt.
     */
    private fun handleGenericIOError(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)

        retryJob?.cancel()
        retryJob =
            scope.launch {
                performAggressiveCacheClear(mediaId)
                delay(RETRY_DELAY_MS)

                val currentIndex = player.currentMediaItemIndex
                player.stop()
                player.prepare()

                Timber.tag(TAG).d("Retrying playback for $mediaId after generic IO error")
            }
    }

    private fun isCurrentTidalLiveManifestPlayback(): Boolean {
        val localConfiguration = player.currentMediaItem?.localConfiguration ?: return false
        return localConfiguration.mimeType == MimeTypes.APPLICATION_MPD &&
            (
                TidalAudioProvider.isLiveManifestUri(localConfiguration.uri.toString()) ||
                    localConfiguration.uri.isPendingTidalManifestUri()
            )
    }

    private fun handleTidalLiveManifestFailure(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)
        retryJob?.cancel()
        retryJob =
            scope.launch {
                val currentIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition.coerceAtLeast(0L)
                if (currentIndex == C.INDEX_UNSET) {
                    handleFinalFailure()
                    return@launch
                }
                skipTidalLiveManifestOnceMediaIds.add(mediaId)
                tidalProgressivePreferredMediaIds.add(mediaId)
                performAggressiveCacheClear(mediaId)
                delay(300L)
                player.seekTo(currentIndex, currentPosition)
                player.prepare()
                Timber.tag(TAG).d("Retrying $mediaId as progressive TIDAL stream after live manifest failure")
            }
    }

    private fun handleTidalSourceRoutingRetry(mediaId: String?) {
        if (mediaId == null) {
            handleFinalFailure()
            return
        }

        incrementRetryCount(mediaId)
        skipTidalLiveManifestOnceMediaIds.add(mediaId)
        tidalProgressivePreferredMediaIds.add(mediaId)
        clearResolvedStreamCache(mediaId)
        retryJob?.cancel()
        retryJob =
            scope.launch {
                val currentIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition.coerceAtLeast(0L)
                if (currentIndex == C.INDEX_UNSET) {
                    handleFinalFailure()
                    return@launch
                }
                delay(120L)
                player.seekTo(currentIndex, currentPosition)
                player.prepare()
                Timber.tag(TAG).d("Retrying $mediaId as progressive TIDAL stream")
            }
    }

    /**
     * Handles final failure when all recovery attempts have been exhausted.
     */
    private fun handleFinalFailure() {
        val autoSkipOnError = dataStore.get(AutoSkipNextOnErrorKey, false)
        val autoplay = dataStore.get(AutoplayKey, true)
        val canAdvance = player.hasNextMediaItem()

        if (autoSkipOnError || (autoplay && canAdvance)) {
            Timber.tag(TAG).d("All recovery attempts exhausted, auto-skipping to next track")
            skipOnError()
        } else {
            Timber.tag(TAG).d("All recovery attempts exhausted, stopping playback")
            stopOnError()
        }
    }

    override fun onDeviceVolumeChanged(
        volume: Int,
        muted: Boolean,
    ) {
        super.onDeviceVolumeChanged(volume, muted)
        val pauseOnMute = dataStore.get(PauseOnMute, false)

        if ((volume == 0 || muted) && pauseOnMute) {
            if (player.isPlaying) {
                wasPlayingBeforeVolumeMute = true
                isPausedByVolumeMute = true
                player.pause()
            }
        } else if (volume > 0 && !muted && pauseOnMute) {
            if (wasPlayingBeforeVolumeMute && !player.isPlaying && castConnectionHandler?.isCasting?.value != true) {
                wasPlayingBeforeVolumeMute = false
                isPausedByVolumeMute = false
                player.play()
            }
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .proxy(YouTube.proxy)
                                    .proxyAuthenticator { _, response ->
                                        YouTube.proxyAuth?.let { auth ->
                                            response.request
                                                .newBuilder()
                                                .header("Proxy-Authorization", auth)
                                                .build()
                                        } ?: response.request
                                    }.addInterceptor { chain ->
                                        var request = chain.request()
                                        if (request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY) != null) {
                                            val clientName = request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY)
                                            val cleanUrl =
                                                request.url
                                                    .newBuilder()
                                                    .removeAllQueryParameters(YouTubeAudioProvider.STREAM_MARKER_QUERY)
                                                    .build()
                                            request =
                                                YouTubeAudioProvider.addYouTubePlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    clientName,
                                                    request.header("Range") != null,
                                                ).build()
                                        }
                                        if (request.url.queryParameter(SoundCloudAudioProvider.STREAM_MARKER_QUERY) != null) {
                                            val isApiStream =
                                                request.url.queryParameter(SoundCloudAudioProvider.STREAM_SOURCE_QUERY) ==
                                                    SoundCloudAudioProvider.STREAM_SOURCE_API
                                            val isHlsStream =
                                                request.url.queryParameter(SoundCloudAudioProvider.STREAM_HLS_MARKER_QUERY) == "1"
                                            val cleanUrl =
                                                request.url
                                                    .newBuilder()
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_MARKER_QUERY)
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_HLS_MARKER_QUERY)
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_SOURCE_QUERY)
                                                    .build()
                                            request =
                                                SoundCloudAudioProvider.addPlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    request.header("Range") != null,
                                                    isApiStream,
                                                    isHlsStream,
                                            ).build()
                                        }
                                        if (InstagramAudioProvider.isInstagramPlaybackUrl(request.url)) {
                                            val instagramClient =
                                                InstagramAudioProvider.playbackClientProfile(request.url)
                                            val instagramUserAgent =
                                                InstagramAudioProvider.playbackUserAgent(request.url)
                                                    ?: cachedInstagramUserAgent
                                            val cleanUrl = InstagramAudioProvider.cleanPlaybackUrl(request.url)
                                            request =
                                                InstagramAudioProvider.addPlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    cachedInstagramCookie,
                                                    request.header("Range") != null,
                                                    instagramClient,
                                                    instagramUserAgent,
                                                ).build()
                                        }
                                        if (request.url.queryParameter(PRIVATE_STREAM_MARKER) != null) {
                                            val cleanUrl =
                                                request.url
                                                    .newBuilder()
                                                    .removeAllQueryParameters(PRIVATE_STREAM_MARKER)
                                                    .build()
                                            val builder = request.newBuilder().url(cleanUrl)
                                            val host = cleanUrl.host
                                            if (host == "youtube.com" || host.endsWith(".youtube.com") ||
                                                host.endsWith(".googlevideo.com")
                                            ) {
                                                YouTube.cookie?.let { builder.header("Cookie", it) }
                                            }
                                            request = builder.build()
                                        }
                                        chain.proceed(request)
                                    }.build(),
                            ),
                        ),
                    ),
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    // Flag to prevent queue saving during silence skip operations
    private var isSilenceSkipping = false

    private fun handleLongSilenceDetected() {
        if (!instantSilenceSkipEnabled.value) return
        if (silenceSkipJob?.isActive == true) return

        silenceSkipJob =
            scope.launch {
                // Debounce so short fades or transitions do not trigger a jump.
                delay(200)
                performInstantSilenceSkip()
            }
    }

    private suspend fun performInstantSilenceSkip() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= INSTANT_SILENCE_SKIP_STEP_MS) return

        isSilenceSkipping = true
        try {
            var hops = 0
            val silenceProcessor = playerSilenceProcessors[player] ?: return
            while (coroutineContext.isActive && instantSilenceSkipEnabled.value && silenceProcessor.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + INSTANT_SILENCE_SKIP_STEP_MS).coerceAtMost(duration - 500)

                if (target <= current) break

                // Reset silence tracking before seeking to prevent immediate re-trigger
                silenceProcessor.resetTracking()
                player.seekTo(target)
                hops++

                if (hops >= 80 || target >= duration - 500) break

                delay(INSTANT_SILENCE_SKIP_SETTLE_MS)
            }
            if (hops > 0) {
                Timber.tag(TAG).d("Silence skip: jumped $hops times")
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    private fun updateDiscordRPC(
        song: Song,
        showFeedback: Boolean = false,
    ) {
        val useDetails = dataStore.get(DiscordUseDetailsKey, false)
        val advancedMode = dataStore.get(DiscordAdvancedModeKey, false)

        val status = if (advancedMode) dataStore.get(DiscordStatusKey, "online") else "online"
        val b1Text = if (advancedMode) dataStore.get(DiscordButton1TextKey, "") else ""
        val b1Visible = if (advancedMode) dataStore.get(DiscordButton1VisibleKey, true) else true
        val b2Text = if (advancedMode) dataStore.get(DiscordButton2TextKey, "") else ""
        val b2Visible = if (advancedMode) dataStore.get(DiscordButton2VisibleKey, true) else true
        val activityType = if (advancedMode) dataStore.get(DiscordActivityTypeKey, "listening") else "listening"
        val activityName = if (advancedMode) dataStore.get(DiscordActivityNameKey, "") else ""
        val mediaId = song.song.id
        val updateGeneration = discordUpdateGeneration.incrementAndGet()

        discordUpdateJob?.cancel()
        discordUpdateJob =
            scope.launch {
                if (discordUpdateGeneration.get() != updateGeneration || currentSong.value?.song?.id != mediaId) {
                    return@launch
                }
                discordRpc
                    ?.updateSong(
                        song,
                        player.currentPosition,
                        player.playbackParameters.speed,
                        useDetails,
                        status,
                        b1Text,
                        b1Visible,
                        b2Text,
                        b2Visible,
                        activityType,
                        activityName,
                    )?.onFailure {
                        if (showFeedback) {
                            Handler(Looper.getMainLooper()).post {
                                Toast
                                    .makeText(
                                        this@MusicService,
                                        "Discord RPC update failed: ${it.message}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                            }
                        }
                    }
            }
    }

    private fun upsertAppleWrapperFormat(mediaId: String) {
        database.query {
            val existing = getFormatByIdBlocking(mediaId)
            if (existing != null && existing.codecs.equals("alac", ignoreCase = true)) {
                if (existing.bitrate == 0 && existing.sampleRate != null) return@query
                upsert(appleWrapperFormat(mediaId, sampleRate = existing.sampleRate))
            } else {
                upsert(appleWrapperFormat(mediaId))
            }
        }
    }

    private fun markAppleWrapperFormat(metadata: com.metrolist.music.models.MediaMetadata?) {
        if (metadata == null || metadata.isEpisode || metadata.isVideoSong) return
        scope.launch(Dispatchers.IO) {
            val song = database.getSongByIdBlocking(metadata.id)
            if (song?.song?.isLocal == true || song?.song?.isEpisode == true) return@launch
            upsertAppleWrapperFormat(metadata.id)
        }
    }

    private fun currentStreamSelectionKey(): String {
        val appleMusicFallbackEnabled = dataStore.get(AppleMusicFallbackEnabledKey, true)
        val preferAppleMusic = dataStore.get(PreferAppleMusicKey, false)
        val preferTidalAudio = dataStore.get(PreferTidalAudioKey, false)
        val tidalQuality = dataStore.get(TidalAudioQualityKey).toEnum(TidalAudioQuality.AAC_320)
        val preferDeezerAudio = dataStore.get(PreferDeezerAudioKey, false)
        val deezerResolverUrl = dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
        val preferSoundCloudAudio = dataStore.get(PreferSoundCloudAudioKey, false)
        val preferInstagramAudio = dataStore.get(PreferInstagramAudioKey, false)
        val preferYouTubeMusicAudio = dataStore.get(PreferYouTubeMusicAudioKey, false)
        val stopOnProviderError = dataStore.get(StopOnProviderErrorKey, false)
        val audioProviderOrder = AudioProviderOrder.deserialize(dataStore.get(AudioProviderOrderKey, ""))
        val providerMatchOverrides = dataStore.get(AudioProviderMatchOverridesKey, "")
        val instagramCookie = dataStore.get(InstagramCookieKey, "")
        val instagramUserAgent = dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_USER_AGENT
        val instagramAppId = dataStore.get(InstagramAppIdKey, InstagramAudioProvider.DEFAULT_APP_ID)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_APP_ID
        val instagramUuid = dataStore.get(InstagramUuidKey, "")
        val instagramCookieConfigured = instagramCookie.isNotBlank()
        val soundCloudAuthConfigured = dataStore.get(SoundCloudAuthTokenKey, "").isNotBlank()
        val qobuzBackend = dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
        val qobuzCountry = dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        return listOf(
            "appleFallback=$appleMusicFallbackEnabled",
            "preferApple=$preferAppleMusic",
            "preferTidal=$preferTidalAudio",
            "tidalQuality=${tidalQuality.name}",
            "preferDeezer=$preferDeezerAudio",
            "deezerResolver=${deezerResolverUrl.hashCode()}",
            "deezerQuality=${deezerQuality.name}",
            "preferSoundCloud=$preferSoundCloudAudio",
            "preferInstagram=$preferInstagramAudio",
            "preferYouTube=$preferYouTubeMusicAudio",
            "stopOnProviderError=$stopOnProviderError",
            "providerOrder=${audioProviderOrder.joinToString(",") { it.name }}",
            "providerOverrides=${providerMatchOverrides.hashCode()}",
            "instagramAuth=$instagramCookieConfigured",
            "instagramCookie=${instagramCookie.hashCode()}",
            "instagramUserAgent=${instagramUserAgent.hashCode()}",
            "instagramAppId=${instagramAppId.hashCode()}",
            "instagramUuid=${instagramUuid.hashCode()}",
            "soundCloudAuth=$soundCloudAuthConfigured",
            "backend=${qobuzBackend.name}",
            "country=$qobuzCountry",
        ).joinToString(";")
    }

    private fun isProviderFirstInPlaybackOrder(
        mediaId: String,
        provider: AudioProviderOrderItem,
        skipAppleForThisAttempt: Boolean = false,
    ): Boolean {
        val providerOverride = ProviderMatchOverrides.decode(dataStore.get(AudioProviderMatchOverridesKey, ""))[mediaId]
        if (providerOverride != null) {
            return providerOverride.provider == provider
        }

        val orderedProviders =
            buildList {
                when {
                    SoundCloudAudioProvider.isSoundCloudUrl(mediaId) -> add(AudioProviderOrderItem.SOUNDCLOUD)
                    TidalAudioProvider.isTidalTrackId(mediaId) -> add(AudioProviderOrderItem.TIDAL)
                    DeezerAudioProvider.isDeezerTrackId(mediaId) -> add(AudioProviderOrderItem.DEEZER)
                }
                addAll(AudioProviderOrder.deserialize(dataStore.get(AudioProviderOrderKey, "")))
            }.distinct()

        return orderedProviders.firstOrNull { canAttemptProviderFromOrder(it, skipAppleForThisAttempt) } == provider
    }

    private fun canAttemptProviderFromOrder(
        provider: AudioProviderOrderItem,
        skipAppleForThisAttempt: Boolean = false,
    ): Boolean =
        when (provider) {
            AudioProviderOrderItem.APPLE_MUSIC ->
                dataStore.get(AppleMusicFallbackEnabledKey, true) && !skipAppleForThisAttempt
            AudioProviderOrderItem.INSTAGRAM ->
                dataStore.get(InstagramCookieKey, "").isNotBlank()
            else -> true
        }

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(
            DeezerAudioAwareDataSourceFactory(
                AppleMusicAwareDataSourceFactory(createCacheDataSource()),
            ),
        ) { dataSpec ->
            val explicitProviderMediaId =
                AppleMusicWrapperDataSource.mediaIdFromUri(dataSpec.uri)
                    ?: DeezerAudioDataSource.mediaIdFromUri(dataSpec.uri)
            if (
                explicitProviderMediaId == null &&
                dataSpec.uri.scheme.equals("file", ignoreCase = true)
            ) {
                return@Factory dataSpec
            }
            if (
                explicitProviderMediaId == null &&
                dataSpec.key?.let(::isProviderFallbackCacheKey) == true &&
                dataSpec.uri.isResolvedProviderPlaybackUri()
            ) {
                return@Factory dataSpec
            }
            if (
                explicitProviderMediaId == null &&
                dataSpec.uri.isTidalPlaybackCdnUri()
            ) {
                return@Factory dataSpec
            }

            val mediaId = explicitProviderMediaId
                ?: dataSpec.uri.mediaIdFromPendingTidalManifestUri()
                ?: dataSpec.key?.let(::mediaIdFromDataSpecKey)
                ?: return@Factory dataSpec
            val isPendingTidalDashRequest = dataSpec.uri.isPendingTidalDashRequest()

            if (AppleMusicWrapperDataSource.isAppleUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(appleWrapperCacheKey(mediaId))
                    .build()
            }
            if (DeezerAudioDataSource.isDeezerUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(deezerFallbackCacheKey(mediaId))
                    .build()
            }

            val song = database.getSongByIdBlocking(mediaId)
            val streamSelectionKey = currentStreamSelectionKey()

            if (song?.song?.isLocal == true || song?.song?.isEpisode == true) {
                return@Factory dataSpec
            }

            val applePrimary = isProviderFirstInPlaybackOrder(
                mediaId = mediaId,
                provider = AudioProviderOrderItem.APPLE_MUSIC,
                skipAppleForThisAttempt = skipAppleOnceMediaIds.contains(mediaId),
            )
            val shouldBypassUrlCache =
                    bypassCacheForQualityChange.contains(mediaId) ||
                    skipAppleOnceMediaIds.contains(mediaId)
            val requestedFallbackKey = dataSpec.key?.takeIf(::isProviderFallbackCacheKey)
            songUrlCache[mediaId]?.takeIf {
                !shouldBypassUrlCache &&
                    it.expiresAtMs > System.currentTimeMillis() &&
                    it.selectionKey == streamSelectionKey &&
                    (requestedFallbackKey == null || it.cacheKey == requestedFallbackKey)
            }?.let { cached ->
                val appleSkippedForCachedAttempt = skipAppleOnceMediaIds.contains(mediaId)
                val tidalProgressiveRetry =
                    skipTidalLiveManifestOnceMediaIds.contains(mediaId) ||
                        tidalProgressivePreferredMediaIds.contains(mediaId)
                val cachedUri = cached.uri.toUri()
                val cachedIsTidalNonDashForPendingRoute =
                    isPendingTidalDashRequest &&
                        isTidalFallbackCacheKey(cached.cacheKey) &&
                        cached.mimeType != MimeTypes.APPLICATION_MPD
                if (cachedIsTidalNonDashForPendingRoute && !tidalProgressiveRetry) {
                    skipTidalLiveManifestOnceMediaIds.add(mediaId)
                    tidalProgressivePreferredMediaIds.add(mediaId)
                    throw PlaybackException(
                        "TIDAL stream was resolved after Media3 selected a DASH source",
                        IllegalStateException("Cached TIDAL stream is ${cached.mimeType ?: "not DASH"}"),
                        PlaybackException.ERROR_CODE_REMOTE_ERROR,
                    )
                }
                val currentDataSpecIsFallback = dataSpec.key?.let { key ->
                        key.startsWith(QOBUZ_FALLBACK_CACHE_PREFIX) ||
                        isTidalFallbackCacheKey(key) ||
                        key.startsWith(DEEZER_FALLBACK_CACHE_PREFIX) ||
                        key.startsWith(SOUNDCLOUD_FALLBACK_CACHE_PREFIX) ||
                        key.startsWith(INSTAGRAM_FALLBACK_CACHE_PREFIX) ||
                        key.startsWith(YOUTUBE_FALLBACK_CACHE_PREFIX)
                } == true
                if (
                    cached.isFallbackStream(mediaId) &&
                    !appleSkippedForCachedAttempt &&
                    applePrimary &&
                    !currentDataSpecIsFallback
                ) {
                    Timber.tag("AppleALAC").d("Dropping one-shot fallback cache before fresh Apple attempt for $mediaId")
                    clearResolvedStreamCache(mediaId)
                } else {
                    val resolvedUri = if (AppleMusicWrapperDataSource.isAppleUri(cachedUri)) {
                        Timber.tag("AppleALAC").w(
                            "Progressive source reached cached Apple ALAC for $mediaId; using direct decrypted stream URI",
                        )
                        AppleMusicWrapperDataSource.toProgressiveStreamUri(cachedUri)
                    } else {
                        cachedUri
                    }
                    upsertCachedStreamFormat(mediaId, cached.format)
                    return@Factory dataSpec
                        .buildUpon()
                        .setUri(resolvedUri)
                        .setKey(cached.cacheKey)
                        .build()
                }
            } ?: run {
                clearResolvedStreamCache(mediaId)
            }

            if (applePrimary) {
                upsertAppleWrapperFormat(mediaId)
            }
            val resolved = resolvePlaybackStreamBlocking(mediaId, song)

            database.query {
                upsert(resolved.format)
            }

            if (bypassCacheForQualityChange.remove(mediaId)) {
                Timber.tag("MusicService").d("Cleared bypass cache flag for $mediaId after stream fetch")
            }

            songUrlCache[mediaId] = CachedSongStream(
                uri = resolved.uri,
                expiresAtMs = resolved.expiresAtMs,
                cacheKey = resolved.cacheKey,
                selectionKey = streamSelectionKey,
                format = resolved.format,
                mimeType = resolved.mimeType,
            )
            if (isPendingTidalDashRequest && resolved.mimeType != MimeTypes.APPLICATION_MPD) {
                skipTidalLiveManifestOnceMediaIds.add(mediaId)
                tidalProgressivePreferredMediaIds.add(mediaId)
                throw PlaybackException(
                    "TIDAL stream was resolved after Media3 selected a DASH source",
                    IllegalStateException("Resolved TIDAL stream is ${resolved.mimeType ?: "not DASH"}"),
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                )
            }
            val resolvedUri = resolved.uri.toUri()
            val playbackUri = if (AppleMusicWrapperDataSource.isAppleUri(resolvedUri)) {
                Timber.tag("AppleALAC").w(
                    "Progressive source resolved Apple ALAC for $mediaId; using direct decrypted stream URI",
                )
                AppleMusicWrapperDataSource.toProgressiveStreamUri(resolvedUri)
            } else {
                resolvedUri
            }
            return@Factory dataSpec
                .buildUpon()
                .setUri(playbackUri)
                .setKey(resolved.cacheKey)
                .build()
        }
    }

    private fun clearResolvedStreamCache(mediaId: String) {
        songUrlCache.remove(mediaId)
        playerCache.removeResource(mediaId)
        playerCache.removeResource(appleWrapperCacheKey(mediaId))
        playerCache.removeResource(qobuzFallbackCacheKey(mediaId))
        playerCache.removeResource(tidalFallbackCacheKey(mediaId))
        playerCache.removeResource(deezerFallbackCacheKey(mediaId))
        playerCache.removeResource(soundCloudFallbackCacheKey(mediaId))
        playerCache.removeResource(instagramFallbackCacheKey(mediaId))
        playerCache.removeResource(youtubeFallbackCacheKey(mediaId))
        downloadCache.removeResource(mediaId)
        downloadCache.removeResource(appleWrapperCacheKey(mediaId))
        downloadCache.removeResource(qobuzFallbackCacheKey(mediaId))
        downloadCache.removeResource(tidalFallbackCacheKey(mediaId))
        downloadCache.removeResource(deezerFallbackCacheKey(mediaId))
        downloadCache.removeResource(soundCloudFallbackCacheKey(mediaId))
        downloadCache.removeResource(instagramFallbackCacheKey(mediaId))
        downloadCache.removeResource(youtubeFallbackCacheKey(mediaId))
    }

    fun setProviderMatchOverride(
        mediaId: String,
        provider: AudioProviderOrderItem?,
        providerTrackId: String?,
        label: String?,
    ) {
        if (mediaId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            dataStore.edit { preferences ->
                val overrides = ProviderMatchOverrides.decode(preferences[AudioProviderMatchOverridesKey])
                if (provider == null || providerTrackId.isNullOrBlank()) {
                    overrides.remove(mediaId)
                } else {
                    overrides[mediaId] = ProviderMatchOverride(
                        provider = provider,
                        providerTrackId = providerTrackId,
                        label = label?.takeIf { it.isNotBlank() } ?: providerTrackId,
                    )
                }
                preferences[AudioProviderMatchOverridesKey] = ProviderMatchOverrides.encode(overrides)
            }
            withContext(Dispatchers.Main) {
                clearResolvedStreamCache(mediaId)
                AppleMusicSongResolver.invalidate(mediaId)
                QobuzAudioProvider.invalidate(mediaId)
                TidalAudioProvider.invalidate(mediaId)
                DeezerAudioProvider.invalidate(mediaId)
                SoundCloudAudioProvider.invalidate(mediaId)
                InstagramAudioProvider.invalidate(mediaId)
                YouTubeAudioProvider.invalidate(mediaId)
                if (player.currentMediaItem?.mediaId == mediaId) {
                    val currentPosition = player.currentPosition.coerceAtLeast(0L)
                    val wasPlaying = player.playWhenReady
                    val currentIndex = player.currentMediaItemIndex
                    player.stop()
                    player.seekTo(currentIndex, currentPosition)
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                    }
                }
            }
        }
    }

    private fun upsertCachedStreamFormat(
        mediaId: String,
        format: FormatEntity,
    ) {
        runCatching {
            database.query {
                val existing = getFormatByIdBlocking(mediaId)
                if (
                    existing == null ||
                    existing.itag != format.itag ||
                    (format.codecs.equals("alac", ignoreCase = true) && existing.bitrate != 0) ||
                    (existing.bitrate <= 0 && !format.codecs.equals("alac", ignoreCase = true)) ||
                    existing.sampleRate == null ||
                    existing.sampleRate <= 0
                ) {
                    upsert(format)
                }
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Failed to restore cached stream format for $mediaId")
        }
    }

    private fun resolvePlaybackStreamBlocking(
        mediaId: String,
        song: Song?,
        queuedMetadata: com.metrolist.music.models.MediaMetadata? = null,
    ): PlaybackStreamResolution {
        Timber.tag("MusicService").i("FETCHING PLAYBACK STREAM: $mediaId")
        return runCatching {
            runBlocking(Dispatchers.IO) {
                resolveOnlineStream(mediaId, song, queuedMetadata)
            }
        }.getOrElse { throwable ->
            when (throwable) {
                is PlaybackException -> throw throwable
                is java.net.ConnectException, is java.net.UnknownHostException -> {
                    throw PlaybackException(
                        getString(R.string.error_no_internet),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    )
                }
                is java.net.SocketTimeoutException -> {
                    throw PlaybackException(
                        getString(R.string.error_timeout),
                        throwable,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    )
                }
                else -> {
                    throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR,
                    )
                }
            }
        }
    }

    private suspend fun resolveOnlineStream(
        mediaId: String,
        song: Song?,
        queuedMetadata: com.metrolist.music.models.MediaMetadata? = null,
    ): PlaybackStreamResolution {
        if (mediaId.toUri().isTidalPlaybackCdnUri()) {
            return PlaybackStreamResolution(
                uri = mediaId,
                expiresAtMs = System.currentTimeMillis() + 45 * 60 * 1000L,
                cacheKey = mediaId,
                format = tidalDirectPlaybackFormat(mediaId),
                mimeType = MimeTypes.AUDIO_MP4,
            )
        }

        val appleMusicFallbackEnabled = dataStore.get(AppleMusicFallbackEnabledKey, true)
        val tidalQuality = dataStore.get(TidalAudioQualityKey).toEnum(TidalAudioQuality.AAC_320)
        val deezerResolverUrl = dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
        val stopOnProviderError = dataStore.get(StopOnProviderErrorKey, false)
        val audioProviderOrder = AudioProviderOrder.deserialize(dataStore.get(AudioProviderOrderKey, ""))
        val providerOverride = ProviderMatchOverrides.decode(dataStore.get(AudioProviderMatchOverridesKey, ""))[mediaId]
        val instagramCookie = dataStore.get(InstagramCookieKey, "")
        val instagramUserAgent = dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_USER_AGENT
        val instagramAppId = dataStore.get(InstagramAppIdKey, InstagramAudioProvider.DEFAULT_APP_ID)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_APP_ID
        val instagramUuid = dataStore.get(InstagramUuidKey, "")
        val soundCloudAuthToken = dataStore.get(SoundCloudAuthTokenKey, "")
        val directTidalMediaId = TidalAudioProvider.isTidalTrackId(mediaId)
        val directDeezerMediaId = DeezerAudioProvider.isDeezerTrackId(mediaId)
        val directSoundCloudMediaId = SoundCloudAudioProvider.isSoundCloudUrl(mediaId)
        val skipAppleForThisAttempt = skipAppleOnceMediaIds.remove(mediaId)
        val skipTidalLiveManifestForThisAttempt =
            skipTidalLiveManifestOnceMediaIds.remove(mediaId) ||
                tidalProgressivePreferredMediaIds.contains(mediaId)

        fun AppleMusicSongResolver.Resolved.toPlaybackResolution(): PlaybackStreamResolution =
            PlaybackStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = appleWrapperCacheKey(mediaId),
                format = appleWrapperFormat(mediaId, sampleRate = sampleRate),
                mimeType = MimeTypes.APPLICATION_M3U8,
            )

        fun QobuzAudioProvider.Resolved.toPlaybackResolution(): PlaybackStreamResolution =
            PlaybackStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = qobuzFallbackCacheKey(mediaId),
                format = qobuzFallbackFormat(mediaId, this),
                mimeType = mimeType,
            )

        fun TidalAudioProvider.Resolved.toPlaybackResolution(): PlaybackStreamResolution =
            PlaybackStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = tidalFallbackCacheKey(mediaId),
                format = tidalFallbackFormat(mediaId, this),
                mimeType = mimeType,
            )

        fun DeezerAudioProvider.Resolved.toPlaybackResolution(): PlaybackStreamResolution =
            PlaybackStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = deezerFallbackCacheKey(mediaId),
                format = deezerFallbackFormat(mediaId, this),
                mimeType = mimeType,
            )

        fun InstagramAudioProvider.Resolved.toPlaybackResolution(): PlaybackStreamResolution =
            PlaybackStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = instagramFallbackCacheKey(mediaId),
                format = instagramFallbackFormat(mediaId, this),
                mimeType = mimeType,
            )

        fun throwProviderFailure(
            provider: String,
            error: Throwable?,
        ): Nothing {
            val cause = error ?: IllegalStateException("$provider failed")
            throw PlaybackException(
                "$provider failed: ${cause.readableMessage()}",
                cause,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        fun showProviderWarning(message: String) {
            showPlaybackToast(message)
        }

        var appleAttempt: Result<AppleMusicSongResolver.Resolved> =
            if (skipAppleForThisAttempt) {
                Result.failure(IllegalStateException("Apple Music skipped after slow ALAC startup"))
            } else if (!appleMusicFallbackEnabled) {
                Result.failure(IllegalStateException("Apple Music fallback disabled"))
            } else {
                Result.failure(IllegalStateException("Apple Music not attempted yet"))
            }
        var soundCloudAttempt: Result<PlaybackStreamResolution> =
            Result.failure(IllegalStateException("SoundCloud not attempted yet"))
        var tidalAttempt: Result<TidalAudioProvider.Resolved> =
            Result.failure(IllegalStateException("TIDAL audio not enabled"))
        var deezerAttempt: Result<DeezerAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Deezer audio not enabled"))
        var instagramAttempt: Result<InstagramAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Instagram audio not enabled"))
        var youtubeAttempt: Result<PlaybackStreamResolution> =
            Result.failure(IllegalStateException("YouTube Music not attempted yet"))
        var qobuzAttempt: Result<QobuzAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Qobuz not attempted yet"))
        val attemptedProviders = mutableSetOf<AudioProviderOrderItem>()
        val orderedProviders = buildList {
            providerOverride?.provider?.let(::add)
            if (directSoundCloudMediaId) add(AudioProviderOrderItem.SOUNDCLOUD)
            if (directTidalMediaId) add(AudioProviderOrderItem.TIDAL)
            if (directDeezerMediaId) add(AudioProviderOrderItem.DEEZER)
            addAll(audioProviderOrder)
        }.distinct()

        fun isForcedProvider(provider: AudioProviderOrderItem): Boolean =
            providerOverride?.provider == provider

        fun providerMediaId(provider: AudioProviderOrderItem): String =
            if (isForcedProvider(provider)) providerOverride?.providerMediaId().orEmpty().ifBlank { mediaId } else mediaId

        fun canAttemptOrderedProvider(provider: AudioProviderOrderItem): Boolean =
            when (provider) {
                AudioProviderOrderItem.APPLE_MUSIC -> appleMusicFallbackEnabled && !skipAppleForThisAttempt
                AudioProviderOrderItem.INSTAGRAM -> instagramCookie.isNotBlank()
                else -> true
            }

        suspend fun attemptProvider(provider: AudioProviderOrderItem): PlaybackStreamResolution? {
            if (provider in attemptedProviders) return null
            if (provider == AudioProviderOrderItem.APPLE_MUSIC && skipAppleForThisAttempt) return null
            if (!canAttemptOrderedProvider(provider) && !isForcedProvider(provider)) return null
            when (provider) {
                AudioProviderOrderItem.SOUNDCLOUD -> {
                    attemptedProviders += provider
                    soundCloudAttempt = runCatching {
                        resolveSoundCloudFallback(
                            mediaId = mediaId,
                            song = song,
                            queuedMetadata = queuedMetadata,
                            authToken = soundCloudAuthToken,
                            queryMediaId = providerMediaId(provider),
                        )
                    }
                    soundCloudAttempt.getOrNull()?.let { return it }
                    if (stopOnProviderError) {
                        throwProviderFailure("SoundCloud", soundCloudAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.TIDAL -> {
                    attemptedProviders += provider
                    tidalAttempt = runCatching {
                        TidalAudioProvider.resolve(
                            query = buildTidalQuery(providerMediaId(provider), song, queuedMetadata),
                            cacheDir = cacheDir,
                            preferAtmos = false,
                            preferLiveDash = false,
                            audioQuality = tidalQuality,
                        )
                    }
                    tidalAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using TIDAL stream for $mediaId: ${resolved.label}")
                        resolved.losslessDowngradedBitrateKbps?.let { bitrateKbps ->
                            showProviderWarning(getString(R.string.tidal_lossless_downgraded_to_aac, bitrateKbps))
                        }
                        return resolved.toPlaybackResolution()
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("TIDAL", tidalAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.DEEZER -> {
                    attemptedProviders += provider
                    deezerAttempt = runCatching {
                        DeezerAudioProvider.resolve(
                            buildDeezerQuery(
                                mediaId = providerMediaId(provider),
                                song = song,
                                metadataOverride = queuedMetadata,
                                resolverUrl = deezerResolverUrl,
                                quality = deezerQuality,
                            ),
                        )
                    }
                    deezerAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using Deezer stream for $mediaId: ${resolved.label}")
                        return resolved.toPlaybackResolution()
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("Deezer", deezerAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.INSTAGRAM -> {
                    attemptedProviders += provider
                    instagramAttempt = runCatching {
                        InstagramAudioProvider.resolve(
                            buildInstagramQuery(mediaId, song, queuedMetadata),
                            instagramCookie,
                            instagramUuid,
                            instagramUserAgent,
                            instagramAppId,
                        )
                    }
                    instagramAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using Instagram audio stream for $mediaId: ${resolved.title}")
                        return resolved.toPlaybackResolution()
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("Instagram", instagramAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.YOUTUBE_MUSIC -> {
                    attemptedProviders += provider
                    youtubeAttempt = runCatching {
                        resolveYouTubeFallback(
                            mediaId = providerMediaId(provider),
                            cacheMediaId = mediaId,
                        )
                    }
                    youtubeAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using YouTube Music stream for $mediaId")
                        return resolved
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("YouTube Music", youtubeAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.QOBUZ -> {
                    attemptedProviders += provider
                    qobuzAttempt = runCatching {
                        QobuzAudioProvider.resolve(buildQobuzQuery(providerMediaId(provider), song, queuedMetadata))
                    }
                    qobuzAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using Qobuz stream for $mediaId: ${resolved.label}")
                        return resolved.toPlaybackResolution()
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("Qobuz", qobuzAttempt.exceptionOrNull())
                    }
                }
                AudioProviderOrderItem.APPLE_MUSIC -> {
                    attemptedProviders += provider
                    appleAttempt = runCatching {
                        AppleMusicSongResolver.resolve(buildAppleMusicQuery(providerMediaId(provider), song, queuedMetadata))
                    }
                    appleAttempt.getOrNull()?.let { resolved ->
                        Timber.tag("MusicService").i("Using Apple Music stream for $mediaId")
                        return resolved.toPlaybackResolution()
                    }
                    if (stopOnProviderError) {
                        throwProviderFailure("Apple Music", appleAttempt.exceptionOrNull())
                    }
                }
            }
            return null
        }

        for (provider in orderedProviders) {
            attemptProvider(provider)?.let { return it }
        }

        if (!attemptedProviders.contains(AudioProviderOrderItem.SOUNDCLOUD) && !directSoundCloudMediaId) {
            soundCloudAttempt = runCatching {
                resolveSoundCloudFallback(mediaId, song, queuedMetadata, soundCloudAuthToken)
            }
            soundCloudAttempt.getOrNull()?.let { return it }
        }

        if (!attemptedProviders.contains(AudioProviderOrderItem.YOUTUBE_MUSIC)) {
            youtubeAttempt = runCatching {
                resolveYouTubeFallback(mediaId)
            }
        }
        youtubeAttempt.getOrNull()?.let { return it }

        val appleError = appleAttempt.exceptionOrNull()
            ?: IllegalStateException("Apple Music failed")
        if (DEBUG_DISABLE_APPLE_ALAC_PROVIDER_FALLBACK && !skipAppleForThisAttempt) {
            throw PlaybackException(
                "Apple Music failed with fallback disabled: ${appleError.readableMessage()}",
                appleError,
                PlaybackException.ERROR_CODE_REMOTE_ERROR,
            )
        }

        val youtubeError = youtubeAttempt.exceptionOrNull()
            ?: IllegalStateException("YouTube fallback failed")
        val soundCloudError = soundCloudAttempt.exceptionOrNull()
            ?: IllegalStateException("SoundCloud fallback failed")
        val tidalDetail = if (attemptedProviders.contains(AudioProviderOrderItem.TIDAL) || directTidalMediaId) {
            tidalAttempt.exceptionOrNull()?.readableMessage()
                ?.let { "TIDAL failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val deezerDetail = if (attemptedProviders.contains(AudioProviderOrderItem.DEEZER) || directDeezerMediaId) {
            deezerAttempt.exceptionOrNull()?.readableMessage()
                ?.let { "Deezer failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val instagramDetail = if (attemptedProviders.contains(AudioProviderOrderItem.INSTAGRAM)) {
            instagramAttempt.exceptionOrNull()?.readableMessage()
                ?.let { "Instagram failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val qobuzDetail = qobuzAttempt.exceptionOrNull()?.readableMessage()
            ?.let { "Qobuz failed: $it; " }
            .orEmpty()
        throw PlaybackException(
            "${qobuzDetail}${tidalDetail}${deezerDetail}${instagramDetail}Apple Music failed: ${appleError.readableMessage()}; SoundCloud failed: ${soundCloudError.readableMessage()}; YouTube failed: ${youtubeError.readableMessage()}",
            youtubeError,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }

    private fun resolveSoundCloudFallback(
        mediaId: String,
        song: Song?,
        queuedMetadata: com.metrolist.music.models.MediaMetadata? = null,
        authToken: String = "",
        queryMediaId: String = mediaId,
    ): PlaybackStreamResolution {
        val resolved = SoundCloudAudioProvider.resolve(buildSoundCloudQuery(queryMediaId, song, queuedMetadata), authToken)
        Timber.tag("MusicService").i(
            "Using SoundCloud fallback for $mediaId: ${resolved.title} by ${resolved.artist}, bitrate=${resolved.bitrate}",
        )
        return PlaybackStreamResolution(
            uri = resolved.mediaUri,
            expiresAtMs = resolved.expiresAtMs,
            cacheKey = soundCloudFallbackCacheKey(mediaId),
            format = soundCloudFallbackFormat(mediaId, resolved),
            mimeType = resolved.mimeType,
        )
    }

    private suspend fun resolveYouTubeFallback(
        mediaId: String,
        cacheMediaId: String = mediaId,
    ): PlaybackStreamResolution {
        val resolved = YouTubeAudioProvider.resolve(mediaId)
        Timber.tag("MusicService").i(
            "Using YouTube AAC fallback for $mediaId: itag=${resolved.itag}, bitrate=${resolved.bitrate}",
        )
        return PlaybackStreamResolution(
            uri = resolved.mediaUri,
            expiresAtMs = resolved.expiresAtMs,
            cacheKey = youtubeFallbackCacheKey(cacheMediaId),
            format = youtubeFallbackFormat(cacheMediaId, resolved),
            mimeType = resolved.mimeType,
        )
    }

    private fun buildTidalQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
    ): TidalAudioProvider.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)

        return TidalAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id, queuedMetadata?.id),
            durationMs = durationMs,
        )
    }

    private fun buildQobuzQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
    ): QobuzAudioProvider.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)
        val backend = dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
        val country = dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"

        return QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id, queuedMetadata?.id),
            durationMs = durationMs,
            countryCode = country,
            backend = backend.toQobuzProviderBackend(),
        )
    }

    private fun buildDeezerQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
        resolverUrl: String,
        quality: DeezerAudioQuality,
    ): DeezerAudioProvider.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)

        return DeezerAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id, queuedMetadata?.id),
            durationMs = durationMs,
            resolverUrl = resolverUrl,
            quality = quality,
        )
    }

    private fun buildSoundCloudQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
    ): SoundCloudAudioProvider.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)

        return SoundCloudAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
        )
    }

    private fun buildInstagramQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
    ): InstagramAudioProvider.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)

        return InstagramAudioProvider.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id, queuedMetadata?.id),
        )
    }

    private fun QobuzBackend.toQobuzProviderBackend(): QobuzAudioProvider.ResolverBackend {
        return when (this) {
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
        }
    }

    private fun Throwable.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    private suspend fun updateAppleCanvas(metadata: com.metrolist.music.models.MediaMetadata?) {
        currentEmbeddedCanvasUrl.value = null
        if (metadata == null || metadata.isEpisode || metadata.isVideoSong) {
            currentAppleCanvasUrl.value = null
            currentAppleTallCanvasUrl.value = null
            return
        }

        if (isLocalMedia(metadata)) {
            currentAppleCanvasUrl.value = null
            currentAppleTallCanvasUrl.value = null
            loadEmbeddedCanvasInBackground(metadata.id)
            return
        }

        val artist =
            metadata.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
                ?: run {
                    currentAppleCanvasUrl.value = null
                    currentAppleTallCanvasUrl.value = null
                    return
                }
        val album = metadata.album?.title
        val cached = AppleMusicCanvasProvider.getCached(
            song = metadata.title,
            artist = artist,
            album = album,
            explicit = metadata.explicit.takeIf { it },
        )?.animated?.takeIf { it.isNotBlank() }
        val cachedTall = AppleMusicCanvasProvider.getCached(
            song = metadata.title,
            artist = artist,
            album = album,
            explicit = metadata.explicit.takeIf { it },
            preferredAspect = AppleMusicCanvasProvider.CanvasAspectPreference.TALL,
        )?.animated?.takeIf { it.isNotBlank() }
        if (cached != null) {
            currentAppleCanvasUrl.value = cached
        } else {
            currentAppleCanvasUrl.value = null
        }
        if (cachedTall != null) {
            currentAppleTallCanvasUrl.value = cachedTall
        } else {
            currentAppleTallCanvasUrl.value = null
        }
        if (cached != null && cachedTall != null) {
            return
        }

        val (resolved, resolvedTall) =
            coroutineScope {
                val squareDeferred =
                    if (cached == null) {
                        async {
                            withTimeoutOrNull(6_500L) {
                                AppleMusicCanvasProvider.getBySongArtist(
                                    song = metadata.title,
                                    artist = artist,
                                    album = album,
                                    explicit = metadata.explicit.takeIf { it },
                                )?.animated?.takeIf { it.isNotBlank() }
                            }
                        }
                    } else {
                        null
                    }
                val tallDeferred =
                    if (cachedTall == null) {
                        async {
                            withTimeoutOrNull(6_500L) {
                                AppleMusicCanvasProvider.getBySongArtist(
                                    song = metadata.title,
                                    artist = artist,
                                    album = album,
                                    explicit = metadata.explicit.takeIf { it },
                                    preferredAspect = AppleMusicCanvasProvider.CanvasAspectPreference.TALL,
                                )?.animated?.takeIf { it.isNotBlank() }
                            }
                        }
                    } else {
                        null
                    }
                (cached ?: squareDeferred?.await()) to (cachedTall ?: tallDeferred?.await())
            }

        if (currentMediaMetadata.value?.id == metadata.id) {
            currentAppleCanvasUrl.value = resolved
            currentAppleTallCanvasUrl.value = resolvedTall ?: resolved
        }
    }

    private fun preloadUpcomingAppleCanvases() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || player.mediaItemCount <= 1) return

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return

        val metadataToPrefetch = mutableListOf<com.metrolist.music.models.MediaMetadata>()
        var nextIndex =
            timeline.getNextWindowIndex(
                currentIndex,
                REPEAT_MODE_OFF,
                player.shuffleModeEnabled,
            )
        while (nextIndex != C.INDEX_UNSET && metadataToPrefetch.size < APPLE_CANVAS_PREFETCH_WINDOW) {
            player
                .getMediaItemAt(nextIndex)
                .metadata
                ?.let(metadataToPrefetch::add)
            nextIndex =
                timeline.getNextWindowIndex(
                    nextIndex,
                    REPEAT_MODE_OFF,
                    player.shuffleModeEnabled,
                )
        }

        if (metadataToPrefetch.isEmpty()) return
        if (appleCanvasPrefetchMediaIds.size > APPLE_CANVAS_PREFETCH_CACHE_LIMIT) {
            appleCanvasPrefetchMediaIds.clear()
        }

        metadataToPrefetch.forEach { metadata ->
            if (metadata.isEpisode || metadata.isVideoSong || metadata.id.isLocalMediaId()) return@forEach
            val artist = metadata.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return@forEach
            if (!appleCanvasPrefetchMediaIds.add(metadata.id)) return@forEach
            scope.launch(Dispatchers.IO + SilentHandler) {
                AppleMusicCanvasProvider.prefetchBySongArtist(
                    song = metadata.title,
                    artist = artist,
                    album = metadata.album?.title,
                    explicit = metadata.explicit.takeIf { it },
                    preferredAspect = AppleMusicCanvasProvider.CanvasAspectPreference.TALL,
                )
            }
        }
    }

    private suspend fun updatePreferredArtwork(
        metadata: com.metrolist.music.models.MediaMetadata?,
    ) {
        currentPreferredArtworkUrl.value = null
        if (metadata == null || metadata.isEpisode || metadata.isVideoSong) return
        if (isLocalMedia(metadata)) return

        val artist = metadata.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }
        val cacheKey = preferredArtworkCacheKey(metadata, artist)
        val cached =
            synchronized(preferredArtworkCache) {
                if (preferredArtworkCache.containsKey(cacheKey)) {
                    true to preferredArtworkCache[cacheKey]
                } else {
                    false to null
                }
            }
        if (cached.first) {
            if (currentMediaMetadata.value?.id == metadata.id) {
                currentPreferredArtworkUrl.value = cached.second
            }
            return
        }

        val tidalArtwork =
            withTimeoutOrNull(2_750L) {
                TidalHomeFeedProvider.resolveAlbumArtwork(
                    title = metadata.title,
                    artist = artist,
                    album = metadata.album?.title,
                    cookie = dataStore.get(TidalCookieKey, ""),
                )
            }?.takeIf { it.isNotBlank() }
        val resolved =
            tidalArtwork
                ?: withTimeoutOrNull(2_750L) {
                    DeezerHomeFeedProvider.resolveAlbumArtwork(
                        title = metadata.title,
                        artist = artist,
                        album = metadata.album?.title,
                        cookie = dataStore.get(DeezerCookieKey, ""),
                    )
                }?.takeIf { it.isNotBlank() }

        synchronized(preferredArtworkCache) {
            preferredArtworkCache[cacheKey] = resolved
        }
        if (currentMediaMetadata.value?.id == metadata.id) {
            currentPreferredArtworkUrl.value = resolved
        }
    }

    private fun preferredArtworkCacheKey(
        metadata: com.metrolist.music.models.MediaMetadata,
        artist: String?,
    ): String =
        listOf(
            metadata.title,
            artist.orEmpty(),
            metadata.album?.title.orEmpty(),
        ).joinToString("|") { it.lowercase().trim() }

    private suspend fun isLocalMedia(metadata: com.metrolist.music.models.MediaMetadata): Boolean =
        metadata.id.isLocalMediaId() ||
            withContext(Dispatchers.IO) {
            database.getSongByIdBlocking(metadata.id)?.song?.isLocal == true
            }

    private fun String.isLocalMediaId(): Boolean =
        startsWith("content://", ignoreCase = true) ||
            startsWith("file://", ignoreCase = true)

    private fun loadEmbeddedCanvasInBackground(mediaId: String) {
        scope.launch(Dispatchers.IO) {
            val embeddedCanvas =
                AudioTagWriter.extractEmbeddedCanvasToCache(applicationContext, mediaId)
            withContext(Dispatchers.Main) {
                if (currentMediaMetadata.value?.id == mediaId) {
                    if (embeddedCanvas?.provider?.contains("apple", ignoreCase = true) == true) {
                        currentAppleCanvasUrl.value = embeddedCanvas.uri
                        currentAppleTallCanvasUrl.value = embeddedCanvas.uri
                        currentEmbeddedCanvasUrl.value = null
                    } else {
                        currentAppleCanvasUrl.value = null
                        currentAppleTallCanvasUrl.value = null
                        currentEmbeddedCanvasUrl.value = embeddedCanvas?.uri
                    }
                }
            }
        }
    }

    private fun buildAppleMusicQuery(
        mediaId: String,
        song: Song?,
        metadataOverride: com.metrolist.music.models.MediaMetadata? = null,
    ): AppleMusicSongResolver.Query {
        val queuedMetadata = metadataOverride ?: if (song == null) currentQueueMetadata(mediaId) else null
        val title = song?.song?.title ?: queuedMetadata?.title ?: mediaId
        val artists = song?.orderedArtists?.map { it.name }
            ?.takeIf { it.isNotEmpty() }
            ?: queuedMetadata?.artists?.map { it.name }.orEmpty()
        val album = song?.song?.albumName
            ?: song?.album?.title
            ?: queuedMetadata?.album?.title
        val durationMs = song?.song?.duration
            ?.takeIf { it > 0 }
            ?.toLong()
            ?.times(1000L)
            ?: queuedMetadata?.duration?.takeIf { it > 0 }?.toLong()?.times(1000L)
        val explicit = song?.song?.explicit ?: queuedMetadata?.explicit

        return AppleMusicSongResolver.Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id, queuedMetadata?.id),
            durationMs = durationMs,
            explicit = explicit,
        )
    }

    private fun currentQueueMetadata(mediaId: String): com.metrolist.music.models.MediaMetadata? {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            player.findNextMediaItemById(mediaId)?.metadata
        } else {
            runCatching {
                runBlocking(Dispatchers.Main) {
                    player.findNextMediaItemById(mediaId)?.metadata
                }
            }.getOrNull()
        }
    }

    private data class PendingAppleMetadata(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val explicit: Boolean?,
    )

    private fun MediaItem.pendingAppleMetadata(mediaId: String): PendingAppleMetadata? {
        metadata?.let { custom ->
            if (custom.isEpisode) return null
            return PendingAppleMetadata(
                title = custom.title.cleanMetadataText() ?: mediaId,
                artists = custom.artists.mapNotNull { it.name.cleanMetadataText() },
                album = custom.album?.title.cleanMetadataText(),
                durationMs = custom.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                explicit = custom.explicit,
            )
        }

        val platform = mediaMetadata
        return PendingAppleMetadata(
            title = platform.title.cleanMetadataText()
                ?: platform.displayTitle.cleanMetadataText()
                ?: mediaId,
            artists = splitDisplayArtists(
                platform.artist.cleanMetadataText()
                    ?: platform.albumArtist.cleanMetadataText()
                    ?: platform.subtitle.cleanMetadataText(),
            ),
            album = platform.albumTitle.cleanMetadataText(),
            durationMs = null,
            explicit = null,
        )
    }

    private fun CharSequence?.cleanMetadataText(): String? =
        this?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun splitDisplayArtists(raw: String?): List<String> =
        raw
            ?.split(",", "\u2022")
            ?.mapNotNull { it.cleanMetadataText() }
            .orEmpty()

    private fun Uri.isUnresolvedPlaybackUri(mediaId: String): Boolean {
        val raw = toString()
        if (raw == mediaId) return true
        if (!scheme.isNullOrBlank()) return false
        return raw.isNotBlank() &&
            !raw.contains('/') &&
            !raw.contains('\\') &&
            !raw.contains('.') &&
            raw.length <= 64
    }

    private fun MediaItem.mediaIdForPlaybackSource(): String? {
        val localConfiguration = localConfiguration ?: return mediaId.takeIf { it.isNotBlank() }
        return mediaId.takeIf { it.isNotBlank() }
            ?: localConfiguration.customCacheKey?.let(::mediaIdFromDataSpecKey)
            ?: AppleMusicWrapperDataSource.mediaIdFromUri(localConfiguration.uri)
            ?: DeezerAudioDataSource.mediaIdFromUri(localConfiguration.uri)
    }

    private fun MediaItem.canUsePendingAppleRoute(mediaId: String): Boolean {
        val localConfiguration = localConfiguration ?: return false
        if (AppleMusicWrapperDataSource.isAppleUri(localConfiguration.uri)) return false
        metadata?.let { custom ->
            if (custom.isEpisode) return false
        }

        val scheme = localConfiguration.uri.scheme?.lowercase()
        return when (scheme) {
            null, "" -> localConfiguration.uri.isUnresolvedPlaybackUri(mediaId)
            "http", "https" -> true
            else -> false
        }
    }

    private fun MediaItem.buildPendingAppleRoute(mediaId: String): MediaItem? {
        if (!canUsePendingAppleRoute(mediaId)) return null
        val pendingMetadata = pendingAppleMetadata(mediaId) ?: return null
        val pendingAppleUri = AppleMusicWrapperDataSource.buildPendingUri(
            mediaId = mediaId,
            title = pendingMetadata.title,
            artists = pendingMetadata.artists,
            album = pendingMetadata.album,
            durationMs = pendingMetadata.durationMs,
            explicit = pendingMetadata.explicit,
        )
        return withResolvedPlaybackStream(pendingAppleUri, appleWrapperCacheKey(mediaId))
    }

    private fun MediaItem.buildPendingTidalRoute(
        mediaId: String,
        expectDash: Boolean,
    ): MediaItem? {
        val localConfiguration = localConfiguration ?: return null
        val scheme = localConfiguration.uri.scheme?.lowercase(Locale.US)
        val canRoute =
            when (scheme) {
                null, "" -> localConfiguration.uri.isUnresolvedPlaybackUri(mediaId)
                "http", "https" -> true
                else -> false
            }
        if (!canRoute) return null
        return withResolvedPlaybackStream(
            uri = pendingTidalManifestUri(mediaId, expectDash),
            cacheKey = tidalFallbackCacheKey(mediaId),
            mimeType = if (expectDash) MimeTypes.APPLICATION_MPD else null,
        )
    }

    private fun resolveMediaItemForSource(mediaItem: MediaItem): MediaItem {
        val localConfiguration = mediaItem.localConfiguration ?: return mediaItem
        val mediaId = mediaItem.mediaIdForPlaybackSource()
            ?: return mediaItem
        val appleSkippedForAttempt = skipAppleOnceMediaIds.contains(mediaId)
        val applePrimary =
            isProviderFirstInPlaybackOrder(
                mediaId = mediaId,
                provider = AudioProviderOrderItem.APPLE_MUSIC,
                skipAppleForThisAttempt = appleSkippedForAttempt,
            )
        val tidalPrimary =
            !applePrimary &&
                isProviderFirstInPlaybackOrder(
                    mediaId = mediaId,
                    provider = AudioProviderOrderItem.TIDAL,
                    skipAppleForThisAttempt = appleSkippedForAttempt,
                )
        val tidalQuality = dataStore.get(TidalAudioQualityKey).toEnum(TidalAudioQuality.AAC_320)
        val skipTidalLiveManifestForAttempt =
            skipTidalLiveManifestOnceMediaIds.contains(mediaId) ||
                tidalProgressivePreferredMediaIds.contains(mediaId)

        if (AppleMusicWrapperDataSource.isAppleUri(localConfiguration.uri)) {
            if (!applePrimary) {
                return mediaItem.withResolvedPlaybackStream(mediaId, mediaId)
            }
            return mediaItem.withResolvedPlaybackStream(
                uri = localConfiguration.uri.toString(),
                cacheKey = appleWrapperCacheKey(mediaId),
            )
        }

        val streamSelectionKey = currentStreamSelectionKey()
        songUrlCache[mediaId]?.takeIf {
            it.expiresAtMs > System.currentTimeMillis() &&
                it.selectionKey == streamSelectionKey
        }?.let { cached ->
            if (skipTidalLiveManifestForAttempt && isTidalFallbackCacheKey(cached.cacheKey)) {
                Timber.tag("MusicService").d("Ignoring cached TIDAL stream during progressive retry for $mediaId")
                clearResolvedStreamCache(mediaId)
                return@let
            }
            val cachedUri = cached.uri.toUri()
            val cachedIsBrokenTidalTemp =
                tidalPrimary &&
                    !skipTidalLiveManifestForAttempt &&
                    isTidalFallbackCacheKey(cached.cacheKey) &&
                    cachedUri.scheme.equals("file", ignoreCase = true) &&
                    cached.mimeType != MimeTypes.APPLICATION_MPD
            if (cachedIsBrokenTidalTemp) {
                Timber.tag("MusicService").d("Ignoring non-DASH TIDAL temp cache before source selection for $mediaId")
                clearResolvedStreamCache(mediaId)
                return@let
            }
            if (
                skipTidalLiveManifestForAttempt &&
                isTidalFallbackCacheKey(cached.cacheKey) &&
                cached.mimeType != MimeTypes.APPLICATION_MPD
            ) {
                skipTidalLiveManifestOnceMediaIds.remove(mediaId)
            }
            if (cached.isFallbackStream(mediaId) && applePrimary) {
                Timber.tag("AppleALAC").d("Ignoring cached fallback stream before fresh Apple source selection for $mediaId")
                clearResolvedStreamCache(mediaId)
                return@let
            }
            return if (AppleMusicWrapperDataSource.isAppleUri(cachedUri)) {
                if (!applePrimary) {
                    mediaItem.withResolvedPlaybackStream(mediaId, mediaId)
                } else {
                    mediaItem.withResolvedPlaybackStream(cached.uri, cached.cacheKey, cached.mimeType)
                }
            } else {
                mediaItem.withResolvedPlaybackStream(cached.uri, cached.cacheKey, cached.mimeType)
            }
        } ?: clearResolvedStreamCache(mediaId)

        if (applePrimary) {
            mediaItem.buildPendingAppleRoute(mediaId)?.let { pendingAppleItem ->
                return pendingAppleItem
            }
        }

        if (tidalPrimary) {
            mediaItem.buildPendingTidalRoute(
                mediaId = mediaId,
                expectDash = false,
            )?.let { pendingTidalItem ->
                return pendingTidalItem
            }
        }

        return mediaItem
    }

    private fun MediaItem.withResolvedPlaybackStream(
        uri: String,
        cacheKey: String,
        mimeType: String? = null,
    ): MediaItem {
        val resolvedUri = uri.toUri()
        val playbackCacheKey = if (resolvedUri.scheme.equals("file", ignoreCase = true)) {
            resolvedUri.toString()
        } else {
            cacheKey
        }
        val isSoundCloudHls =
            resolvedUri.isHierarchical &&
                resolvedUri.getQueryParameter(SoundCloudAudioProvider.STREAM_HLS_MARKER_QUERY) == "1"
        return buildUpon()
            .setUri(resolvedUri)
            .setCustomCacheKey(playbackCacheKey)
            .setMimeType(
                when {
                    AppleMusicWrapperDataSource.isAppleUri(resolvedUri) || isSoundCloudHls -> MimeTypes.APPLICATION_M3U8
                    else -> mimeType ?: localConfiguration?.mimeType
                },
            )
            .build()
    }

    private fun MediaItem.isResolvedTidalPlaybackStream(): Boolean {
        val localConfiguration = localConfiguration ?: return false
        val uri = localConfiguration.uri
        return localConfiguration.customCacheKey?.let(::isTidalFallbackCacheKey) == true ||
            TidalAudioProvider.isLiveManifestUri(uri.toString()) ||
            uri.isTidalPlaybackCdnUri()
    }

    private inner class PlaybackMediaSourceFactory(
        dataSourceFactory: DataSource.Factory,
    ) : MediaSource.Factory {
        private val extractorsFactory =
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
        private val defaultFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            extractorsFactory,
        )
        private val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
        private val dashFactory = DashMediaSource.Factory(dataSourceFactory)
        private val supportedTypes = (
            defaultFactory.supportedTypes.toSet() +
                C.CONTENT_TYPE_HLS +
                C.CONTENT_TYPE_DASH
        ).toIntArray()

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val resolvedItem = runCatching {
                resolveMediaItemForSource(mediaItem)
            }.onFailure { error ->
                Timber.tag("AppleALAC").w(error, "Failed to resolve stream before media source creation")
            }.getOrDefault(mediaItem)
            val routedItem = if (resolvedItem.localConfiguration?.uri?.let(AppleMusicWrapperDataSource::isAppleUri) == true) {
                resolvedItem
            } else {
                val mediaId = mediaItem.mediaIdForPlaybackSource()
                val appleSkippedForAttempt = mediaId?.let(skipAppleOnceMediaIds::contains) == true
                val applePrimary =
                    mediaId != null &&
                        isProviderFirstInPlaybackOrder(
                            mediaId = mediaId,
                            provider = AudioProviderOrderItem.APPLE_MUSIC,
                            skipAppleForThisAttempt = appleSkippedForAttempt,
                        )
                if (mediaId != null && applePrimary) {
                    mediaItem.buildPendingAppleRoute(mediaId)?.also {
                        Timber.tag("AppleALAC").d(
                            "Forced pending HLS source before progressive selection for $mediaId",
                        )
                    } ?: resolvedItem
                } else {
                    resolvedItem
                }
            }
            val uri = routedItem.localConfiguration?.uri
            val isHlsSource =
                uri != null &&
                    (
                        AppleMusicWrapperDataSource.isAppleUri(uri) ||
                            routedItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8
                    )
            val isDashSource =
                uri != null &&
                    routedItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_MPD

            return when {
                isHlsSource -> {
                    Timber.tag("AppleALAC").d("Using HLS media source for ${routedItem.mediaId}")
                    hlsFactory.createMediaSource(
                        routedItem.buildUpon()
                            .setMimeType(MimeTypes.APPLICATION_M3U8)
                            .build(),
                    )
                }
                isDashSource -> {
                    Timber.tag("MusicService").d("Using DASH media source for ${routedItem.mediaId}")
                    dashFactory.createMediaSource(
                        routedItem.buildUpon()
                            .setMimeType(MimeTypes.APPLICATION_MPD)
                            .build(),
                    )
                }
                else -> defaultFactory.createMediaSource(routedItem)
            }
        }

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            dashFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            dashFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = supportedTypes
    }

    private fun createMediaSourceFactory() = PlaybackMediaSourceFactory(createDataSourceFactory())

    private fun createLoadControl() =
        DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(
                ALAC_MIN_BUFFER_MS,
                ALAC_MAX_BUFFER_MS,
                ALAC_BUFFER_FOR_PLAYBACK_MS,
                ALAC_BUFFER_FOR_REBUFFER_MS,
            ).build()

    private fun shouldEnableAudioOffload(
        offloadPref: Boolean,
        crossfadeEnabled: Boolean,
    ): Boolean {
        if (!offloadPref) return false
        if (crossfadeEnabled) {
            Timber.tag(TAG).d("Audio offload disabled because crossfade is enabled")
            return false
        }
        Timber.tag(TAG).d(
            "Audio offload requested, but custom EQ/silence/speed audio processors still block reliable offload"
        )
        return false
    }

    private fun createRenderersFactory(
        eqProcessor: CustomEqualizerAudioProcessor,
        silenceProcessor: SilenceDetectorAudioProcessor,
    ) = object : DefaultRenderersFactory(this) {
        init {
            setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ) = DefaultAudioSink
            .Builder(this@MusicService)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    // 2. Inject processor into audio pipeline
                    arrayOf(
                        eqProcessor,
                        silenceProcessor,
                    ),
                    SilenceSkippingAudioProcessor(2_000_000, 20_000, 256),
                    SonicAudioProcessor(),
                ),
            ).build()
    }

    override fun onTracksChanged(tracks: Tracks) {
        super.onTracksChanged(tracks)
        updateCurrentAudioFormatFromTracks(tracks)
    }

    private fun updateCurrentAudioFormatFromTracks(
        tracks: Tracks,
        retryIfUnknown: Boolean = true,
    ) {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        val audioFormat = tracks.selectedAudioFormat()
        if (audioFormat == null) {
            if (retryIfUnknown) scheduleAudioFormatRetry(mediaId)
            return
        }
        val rendererBitrate = audioFormat.averageBitrate
            .takeIf { it > 0 }
            ?: audioFormat.peakBitrate.takeIf { it > 0 }
        val rendererSampleRate = audioFormat.sampleRate.takeIf { it > 0 }
        if (rendererBitrate == null && rendererSampleRate == null) {
            if (retryIfUnknown) scheduleAudioFormatRetry(mediaId)
            return
        }

        scope.launch(Dispatchers.IO) {
            var shouldRetry = false
            database.query {
                val existing = getFormatByIdBlocking(mediaId)
                val cached = songUrlCache[mediaId]?.format
                val baseFormat =
                    when {
                        cached != null && (existing == null || existing.itag != cached.itag || !existing.hasUsefulAudioMetadata()) -> cached
                        existing != null -> existing
                        else -> rendererFallbackFormat(mediaId, audioFormat, rendererBitrate, rendererSampleRate)
                    }
                if (existing == null) {
                    val inserted = baseFormat.withRendererAudioMetadata(audioFormat, rendererBitrate, rendererSampleRate)
                    upsert(inserted)
                    shouldRetry = !inserted.hasUsefulAudioMetadata()
                    return@query
                }
                val isAlac = baseFormat.codecs.contains("alac", ignoreCase = true)
                val shouldUpdateBitrate =
                    !isAlac &&
                    rendererBitrate != null &&
                    (
                        baseFormat.bitrate <= 0 ||
                            baseFormat.itag in setOf(TIDAL_FALLBACK_ITAG, SOUNDCLOUD_FALLBACK_ITAG, INSTAGRAM_FALLBACK_ITAG)
                    )
                val shouldUpdateSampleRate =
                    rendererSampleRate != null &&
                    (baseFormat.sampleRate == null || baseFormat.sampleRate <= 0 || baseFormat.itag in setOf(TIDAL_FALLBACK_ITAG, DEEZER_FALLBACK_ITAG, SOUNDCLOUD_FALLBACK_ITAG, INSTAGRAM_FALLBACK_ITAG))
                val shouldClearAlacBitrate = isAlac && baseFormat.bitrate != 0
                if (
                    baseFormat == existing &&
                    !shouldUpdateBitrate &&
                    !shouldUpdateSampleRate &&
                    !shouldClearAlacBitrate
                ) {
                    shouldRetry = !baseFormat.hasUsefulAudioMetadata()
                    return@query
                }

                val updated = baseFormat.copy(
                    bitrate = if (shouldClearAlacBitrate) 0 else if (shouldUpdateBitrate) rendererBitrate else baseFormat.bitrate,
                    sampleRate = if (shouldUpdateSampleRate) rendererSampleRate else baseFormat.sampleRate,
                )
                upsert(updated)
                shouldRetry = !updated.hasUsefulAudioMetadata()
            }
            if (retryIfUnknown && shouldRetry) {
                scheduleAudioFormatRetry(mediaId)
            } else if (!shouldRetry) {
                audioFormatRetryJobs.remove(mediaId)?.cancel()
            }
        }
    }

    private fun FormatEntity.hasUsefulAudioMetadata(): Boolean {
        val hasSampleRate = sampleRate?.let { it > 0 } == true
        val hasBitrate = bitrate > 0
        return when {
            itag == TIDAL_FALLBACK_ITAG -> hasBitrate || hasSampleRate
            itag == DEEZER_FALLBACK_ITAG -> hasBitrate || hasSampleRate
            codecs.contains("alac", ignoreCase = true) -> hasSampleRate
            else -> hasBitrate || hasSampleRate
        }
    }

    private fun rendererFallbackFormat(
        mediaId: String,
        audioFormat: Format,
        rendererBitrate: Int?,
        rendererSampleRate: Int?,
    ): FormatEntity {
        val codecs = audioFormat.codecs?.takeIf { it.isNotBlank() }.orEmpty()
        val isAlac =
            codecs.contains("alac", ignoreCase = true) ||
                audioFormat.sampleMimeType?.contains("alac", ignoreCase = true) == true
        return FormatEntity(
            id = mediaId,
            itag = 0,
            mimeType = audioFormat.sampleMimeType?.takeIf { it.isNotBlank() } ?: "audio/unknown",
            codecs = codecs,
            bitrate = if (isAlac) 0 else rendererBitrate ?: 0,
            sampleRate = rendererSampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = player.currentMediaItem?.localConfiguration?.uri?.toString(),
        )
    }

    private fun FormatEntity.withRendererAudioMetadata(
        audioFormat: Format,
        rendererBitrate: Int?,
        rendererSampleRate: Int?,
    ): FormatEntity {
        val resolvedMimeType = mimeType
            .takeIf { it.isNotBlank() && !it.equals("audio/unknown", ignoreCase = true) }
            ?: audioFormat.sampleMimeType?.takeIf { it.isNotBlank() }
            ?: "audio/unknown"
        val resolvedCodecs = codecs
            .takeIf { it.isNotBlank() }
            ?: audioFormat.codecs?.takeIf { it.isNotBlank() }
            ?: ""
        val isAlac =
            resolvedCodecs.contains("alac", ignoreCase = true) ||
                resolvedMimeType.contains("alac", ignoreCase = true)
        val providerItag =
            itag in setOf(TIDAL_FALLBACK_ITAG, SOUNDCLOUD_FALLBACK_ITAG, INSTAGRAM_FALLBACK_ITAG)
        return copy(
            mimeType = resolvedMimeType,
            codecs = resolvedCodecs,
            bitrate = when {
                isAlac -> 0
                rendererBitrate != null && (bitrate <= 0 || providerItag) -> rendererBitrate
                else -> bitrate
            },
            sampleRate = when {
                rendererSampleRate != null && (sampleRate == null || sampleRate <= 0 || providerItag) -> rendererSampleRate
                else -> sampleRate
            },
        )
    }

    private fun scheduleAudioFormatRetry(mediaId: String) {
        if (audioFormatRetryJobs[mediaId]?.isActive == true) return

        val retryJob =
            scope.launch {
                repeat(AUDIO_FORMAT_RETRY_ATTEMPTS) { attempt ->
                    delay(AUDIO_FORMAT_RETRY_DELAY_MS)
                    if (player.currentMediaItem?.mediaId != mediaId) return@launch

                    val hasResolvedAudioQuality =
                        withContext(Dispatchers.IO) {
                            database.format(mediaId).first()?.hasUsefulAudioMetadata() == true
                        }
                    if (hasResolvedAudioQuality) return@launch

                    Timber.tag(TAG).d(
                        "Retrying audio format metadata for $mediaId (${attempt + 1}/$AUDIO_FORMAT_RETRY_ATTEMPTS)",
                    )
                    updateCurrentAudioFormatFromTracks(player.currentTracks, retryIfUnknown = false)
                }
            }

        audioFormatRetryJobs[mediaId] = retryJob
        retryJob.invokeOnCompletion {
            audioFormatRetryJobs.remove(mediaId, retryJob)
        }
    }

    private fun Tracks.selectedAudioFormat(): Format? {
        groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) return@forEach
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) return group.getTrackFormat(index)
            }
        }
        return null
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
        val historyDurationMs = dataStore[HistoryDuration]?.times(1000f) ?: 30000f

        if (playbackStats.totalPlayTimeMs >= historyDurationMs &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }
        }

        // Apple wrapper playback does not use YouTube/Opus stream tracking.
    }

    private fun saveQueueToDisk() {
        if (player.mediaItemCount == 0) {
            Timber.tag(TAG).d("Skipping queue save - no media items")
            return
        }

        val snapshot =
            try {
                QueueSaveSnapshot(
                    queue =
                        currentQueue.toPersistQueue(
                            title = queueTitle,
                            items = player.mediaItems.mapNotNull { it.metadata },
                            mediaItemIndex = player.currentMediaItemIndex,
                            position = player.currentPosition,
                        ),
                    automix =
                        PersistQueue(
                            title = "automix",
                            items = automixItems.value.mapNotNull { it.metadata },
                            mediaItemIndex = 0,
                            position = 0,
                        ),
                    playerState =
                        PersistPlayerState(
                            playWhenReady = player.playWhenReady,
                            repeatMode = player.repeatMode,
                            shuffleModeEnabled = player.shuffleModeEnabled,
                            volume = playerVolume.value,
                            currentPosition = player.currentPosition,
                            currentMediaItemIndex = player.currentMediaItemIndex,
                            playbackState = player.playbackState,
                        ),
                )
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error while snapshotting queue state")
                reportException(e)
                return
            }

        queueSaveJob?.cancel()
        queueSaveJob =
            scope.launch(Dispatchers.IO) {
                delay(250)
                writeQueueSnapshotToDisk(snapshot)
            }
    }

    private fun writeQueueSnapshotToDisk(snapshot: QueueSaveSnapshot) {
        writeObjectAtomically(
            file = filesDir.resolve(PERSISTENT_QUEUE_FILE),
            value = snapshot.queue,
            successMessage = "Queue saved successfully",
            failureMessage = "Failed to save queue",
        )
        writeObjectAtomically(
            file = filesDir.resolve(PERSISTENT_AUTOMIX_FILE),
            value = snapshot.automix,
            successMessage = "Automix saved successfully",
            failureMessage = "Failed to save automix",
        )
        writeObjectAtomically(
            file = filesDir.resolve(PERSISTENT_PLAYER_STATE_FILE),
            value = snapshot.playerState,
            successMessage = "Player state saved successfully",
            failureMessage = "Failed to save player state",
        )
    }

    private fun writeObjectAtomically(
        file: File,
        value: Any,
        successMessage: String,
        failureMessage: String,
    ) {
        runCatching {
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.outputStream().use { fos ->
                ObjectOutputStream(fos).use { oos ->
                    oos.writeObject(value)
                }
            }
            if (file.exists() && !file.delete()) {
                Timber.tag(TAG).w("Could not delete old ${file.name} before queue state replace")
            }
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            Timber.tag(TAG).d(successMessage)
        }.onFailure {
            Timber.tag(TAG).e(it, failureMessage)
            reportException(it)
        }
    }

    /**
     * [Context.startForegroundService] requires [startForeground] to succeed quickly. If we cannot
     * enter the foreground state, stop immediately so the system does not ANR the app process.
     */
    private fun ensureStartedAsForegroundOrStop(): Boolean =
        startForegroundSafely(
            notification = createFallbackForegroundNotification(),
            deniedMessage = "Foreground service start not allowed; stopping service to avoid ANR",
            failureMessage = "Failed to enter foreground; stopping service to avoid ANR",
        )

    private fun ensureForegroundWithLatestNotificationOrStop(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update; stopping service",
            failureMessage = "Failed to promote service during notification update; stopping service",
            stopOnFailure = true,
        )

    private fun tryEnsureForegroundWithLatestNotification(): Boolean =
        startForegroundSafely(
            notification = latestMediaNotification ?: createFallbackForegroundNotification(),
            deniedMessage = "Foreground promotion denied during notification update",
            failureMessage = "Failed to promote service during notification update",
            stopOnFailure = false,
        )

    private fun ensureForegroundChannelExists() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_player),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun createFallbackForegroundNotification(): Notification {
        ensureForegroundChannelExists()
        val pending =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.music_player))
            .setContentText("")
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundSafely(
        notification: Notification,
        deniedMessage: String,
        failureMessage: String,
        stopOnFailure: Boolean = true,
    ): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Timber.tag(TAG).w(e, deniedMessage)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, failureMessage)
            reportException(e)
            if (stopOnFailure) {
                stopSelf()
            }
            false
        }

    override fun onDestroy() {
        isRunning = false

        if (!::player.isInitialized) {
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
            super.onDestroy()
            return
        }

        // Save episode position before destroying
        val currentMetadata = player.currentMediaItem?.metadata
        if (currentMetadata?.isEpisode == true && player.currentPosition > 0) {
            runBlocking(Dispatchers.IO) {
                database.updatePlaybackPosition(currentMetadata.id, player.currentPosition)
            }
        }

        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        castConnectionHandler?.release()
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }
        if (discordRpc?.isRpcRunning() == true) {
            discordRpc?.closeRPC()
        }
        discordRpc = null
        connectivityObserver.unregister()
        abandonAudioFocus()
        closeAudioEffectSession()
        mediaLibrarySessionCallback.release()
        mediaSession.release()
        player.removeListener(this)
        player.removeListener(sleepTimer)
        playerSilenceProcessors.remove(player)
        // Note: equalizerService audio processors are cleared in equalizerService.release() if needed,
        // or we can't easily reference the specific processor created in createExoPlayer here without storing it.
        // But since we are destroying the service, it's fine.
        player.release()
        discordUpdateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = super.onBind(intent) ?: binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (dataStore.get(StopMusicOnTaskClearKey, false)) {
            if (!::player.isInitialized) {
                stopSelf()
                return
            }
            // Remote playback (Cast) is independent of the local ExoPlayer; ending the session
            // is required or audio keeps playing on the Cast device.
            runCatching {
                if (castConnectionHandler?.isCasting?.value == true) {
                    castConnectionHandler?.disconnect()
                }
                player.stop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Media3: coordinates notification/foreground teardown and stopSelf; required when
                // playback was ongoing (default super.onTaskRemoved keeps the service alive).
                pauseAllPlayersAndStopSelf()
            }.onFailure { e ->
                Timber.tag(TAG).e(e, "Failed to stop playback on task clear")
                runCatching { pauseAllPlayersAndStopSelf() }.onFailure { stopSelf() }
            }
            return
        }
        super.onTaskRemoved(rootIntent)
        // User removed the task while paused: drop foreground promotion so the process can idle.
        // Queue/state remain persisted; opening the app restores playback as usual.
        if (::player.isInitialized && !player.isPlaying) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        try {
            // Pass startInForegroundRequired through unchanged so Media3 manages the
            // foreground service lifecycle itself. Overriding it to `false` and promoting
            // manually causes Media3 to demote the service on every notification update
            // (track change, metadata refresh, etc.); the subsequent manual re-promotion is
            // denied while backgrounded on Android 12+ (mAllowStartForeground=false), which
            // ends background playback mid-song after autoplay transitions.
            // Media3 1.10.0 catches ForegroundServiceStartNotAllowedException internally in
            // onUpdateNotificationInternal, so the 1.7.x crash-workaround is no longer
            // needed; the try/catch below is kept as belt-and-suspenders defense.
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: ForegroundServiceStartNotAllowedException) {
            handleForegroundServiceStartNotAllowed(e)
        } catch (e: IllegalStateException) {
            if (isForegroundServiceStartNotAllowedException(e)) {
                handleForegroundServiceStartNotAllowed(e)
            } else {
                throw e
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val requiresForegroundPromotion =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (intent?.action == null || intent.action == ACTION_ALARM_TRIGGER)
        if (requiresForegroundPromotion && !ensureForegroundWithLatestNotificationOrStop()) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_ALARM_TRIGGER -> {
                handleAlarmTrigger(intent)
            }

            MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_LIKE -> {
                toggleLike()
            }

            MusicWidgetReceiver.ACTION_NEXT -> {
                prepareManualPlaybackTransition()
                player.seekToNext()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_PREVIOUS -> {
                prepareManualPlaybackTransition()
                player.seekToPrevious()
                updateWidgetUI(player.isPlaying)
            }

            MusicWidgetReceiver.ACTION_UPDATE_WIDGET -> {
                updateWidgetUI(player.isPlaying)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun handleAlarmTrigger(intent: Intent) {
        scope.launch(Dispatchers.IO) {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to reschedule alarms after trigger")
            }
        }
        val playlistId = intent.getStringExtra(EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val alarmId = intent.getStringExtra(EXTRA_ALARM_ID).orEmpty()
        if (playlistId.isBlank()) {
            if (alarmId.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val alarms = MusicAlarmStore.load(this@MusicService)
                        val updated =
                            alarms.map { alarm ->
                                if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                            }
                        MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                    } catch (t: Throwable) {
                        Timber.tag(TAG).e(t, "Failed to disable alarm with invalid playlist")
                    }
                }
            }
            return
        }
        val randomSong = intent.getBooleanExtra(EXTRA_ALARM_RANDOM_SONG, false)
        scope.launch {
            try {
                val playlistSongs =
                    withContext(Dispatchers.IO) {
                        database.playlistSongs(playlistId).first()
                    }
                if (playlistSongs.isEmpty()) {
                    if (alarmId.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            val alarms = MusicAlarmStore.load(this@MusicService)
                            val updated =
                                alarms.map { alarm ->
                                    if (alarm.id == alarmId) alarm.copy(enabled = false, nextTriggerAt = -1L) else alarm
                                }
                            MusicAlarmScheduler.scheduleAll(this@MusicService, updated)
                        }
                    }
                    return@launch
                }
                val items = playlistSongs.map { it.song.toMediaItem() }
                val playlistName =
                    withContext(Dispatchers.IO) {
                        database
                            .playlist(playlistId)
                            .first()
                            ?.playlist
                            ?.name
                    }
                withContext(Dispatchers.IO) {
                    MusicAlarmScheduler.scheduleFromPreferences(this@MusicService)
                }

                val alarmItems =
                    if (randomSong) {
                        val firstIndex = Random.nextInt(items.size)
                        buildList(items.size) {
                            add(items[firstIndex])
                            items.forEachIndexed { index, item ->
                                if (index != firstIndex) add(item)
                            }
                        }
                    } else {
                        items
                    }

                player.stop()
                player.clearMediaItems()
                playQueue(
                    ListQueue(
                        title = playlistName,
                        items = alarmItems,
                        startIndex = 0,
                        position = 0L,
                    ),
                    playWhenReady = true,
                )
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to start alarm playback")
            }
        }
    }

    private fun handleForegroundServiceStartNotAllowed(error: Throwable?) {
        if (error != null) {
            Timber.tag(TAG).w(error, "Foreground service start denied during notification update")
        } else {
            Timber.tag(TAG).w("Foreground service start denied by MediaSessionService listener")
        }

        if (tryEnsureForegroundWithLatestNotification()) {
            return
        }

        if (!::player.isInitialized) {
            stopSelf()
            return
        }

        if (player.isPlaying) {
            Timber.tag(TAG).w("Keeping playback alive after denied foreground restart request")
            return
        }

        runCatching {
            pauseAllPlayersAndStopSelf()
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to stop service after foreground start denial")
            stopSelf()
        }
    }

    private fun isForegroundServiceStartNotAllowedException(error: IllegalStateException): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error.javaClass.name == ForegroundServiceStartNotAllowedException::class.java.name

    /**
     * Updates all app widgets with current playback state
     */
    private fun updateWidgetUI(isPlaying: Boolean) {
        scope.launch {
            try {
                val songData = currentSong.value
                val song = songData?.song
                val songTitle = song?.title ?: getString(R.string.no_song_playing)
                val artistName = songData?.artists?.joinToString(", ") { it.name } ?: getString(R.string.tap_to_open)
                val isLiked = songData?.song?.liked == true

                widgetManager.updateWidgets(
                    title = songTitle,
                    artist = artistName,
                    artworkUri = song?.thumbnailUrl,
                    isPlaying = isPlaying,
                    isLiked = isLiked,
                    duration = if (player.duration != C.TIME_UNSET) player.duration else 0,
                    currentPosition = player.currentPosition,
                )
            } catch (e: Exception) {
                // Widget not added to home screen or other error
            }
        }
    }

    private var widgetUpdateJob: Job? = null

    private fun startWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob =
            scope.launch {
                while (isActive) {
                    if (player.isPlaying) {
                        updateWidgetUI(true)
                    }
                    delay(200)
                }
            }
    }

    private fun stopWidgetUpdates() {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = null
    }

    private fun shareSong() {
        val songData = currentSong.value
        val songId = songData?.song?.id ?: return

        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/watch?v=$songId")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        startActivity(
            Intent.createChooser(shareIntent, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * Get the stream URL for a given media ID.
     * This is used for Google Cast to send the audio URL to Chromecast.
     */
    suspend fun getStreamUrl(mediaId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val song = database.getSongByIdBlocking(mediaId)
                if (song?.song?.isLocal == true || song?.song?.isEpisode == true) {
                    return@withContext null
                }
                resolveOnlineStream(mediaId, song).uri
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to get stream URL for Cast")
                null
            }
        }

    /**
     * Initialize Google Cast support
     */
    private fun initializeCast() {
        if (dataStore.get(com.metrolist.music.constants.EnableGoogleCastKey, true)) {
            try {
                castConnectionHandler = CastConnectionHandler(this, scope, this)
                castConnectionHandler?.initialize()
                timber.log.Timber.d("Google Cast initialized")
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Failed to initialize Google Cast")
            }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            scheduleCrossfade()
        }
    }

    private fun scheduleCrossfade() {
        crossfadeTriggerJob?.cancel()
        crossfadeTriggerJob = null
        if (isCrossfading || secondaryPlayer != null) return
        val mixProfile = currentMetroMixProfile()
        if (!crossfadeEnabled || player.duration == C.TIME_UNSET || player.duration <= mixProfile.durationMs) return
        if (crossfadeGapless && isNextItemGapless()) return
        if (!player.hasNextMediaItem() && player.repeatMode != REPEAT_MODE_ONE) return

        val triggerTime = player.duration - mixProfile.durationMs
        val delayMs = triggerTime - player.currentPosition
        pendingMetroMixProfile = mixProfile
        if (delayMs <= 250L) {
            startCrossfade()
            return
        }

        val targetMediaId = player.currentMediaItem?.mediaId

        crossfadeTriggerJob =
            scope.launch {
                delay(delayMs)
                if (isActive && player.isPlaying && player.currentMediaItem?.mediaId == targetMediaId && !sleepTimer.pauseWhenSongEnd) {
                    startCrossfade()
                }
            }
    }

    private fun currentMetroMixProfile(): MetroMixRuntimeProfile {
        val selectedPreset = activeMetroMixPreset
        val runtimePreset =
            when (selectedPreset) {
                null -> null
                MetroMixPreset.AUTO -> inferAutoMetroMixPreset()
                else -> selectedPreset
            }
        val bpm = player.currentMediaItem?.metadata?.bpm
        val bars = activeMetroMixBars.coerceIn(2, 32)
        val barDurationMs = bpm?.takeIf { it in 40f..240f }?.let { (60_000f / it * 4f * bars).toLong() }
        val presetDurationMs = ((runtimePreset?.durationSeconds ?: (crossfadeDuration / 1000f)) * 1000f).toLong()
        val durationMs =
            if (selectedPreset != null) {
                barDurationMs ?: (presetDurationMs * (bars / 8f)).toLong()
            } else {
                presetDurationMs
            }.coerceIn(750L, 32_000L)
        return MetroMixRuntimeProfile(
            preset = runtimePreset,
            durationMs = durationMs,
            volumeCurve = activeMetroMixVolumeCurve,
            eqCurve = activeMetroMixEqCurve,
            effectCurve = activeMetroMixEffectCurve,
        )
    }

    private fun inferAutoMetroMixPreset(): MetroMixPreset {
        val current = player.currentMediaItem?.mediaMetadata
        val nextIndex = player.nextMediaItemIndex
        val next =
            if (nextIndex != C.INDEX_UNSET) {
                player.getMediaItemAt(nextIndex).mediaMetadata
            } else {
                null
            }
        val currentTitle = current?.title?.toString().orEmpty()
        val nextTitle = next?.title?.toString().orEmpty()
        val titles = "$currentTitle $nextTitle".lowercase(Locale.US)
        val currentAlbum = current?.albumTitle?.toString().orEmpty()
        val nextAlbum = next?.albumTitle?.toString().orEmpty()
        val sameAlbum = currentAlbum.isNotBlank() && currentAlbum == nextAlbum
        val currentDuration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

        return when {
            sameAlbum -> MetroMixPreset.SMOOTH
            titles.containsAny("skit", "interlude", "intro") || currentDuration in 1L..95_000L -> MetroMixPreset.QUICK_CUT
            titles.containsAny("outro", "acoustic", "live", "remaster") -> MetroMixPreset.VOCAL_BLEND
            titles.containsAny("club", "extended", "remix", "mix", "edit") -> MetroMixPreset.CLUB_BLEND
            currentDuration >= 300_000L -> MetroMixPreset.BEAT_BLEND
            currentDuration in 1L..150_000L -> MetroMixPreset.RADIO_EDIT
            else -> MetroMixPreset.SMART_DJ
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun isNextItemGapless(): Boolean {
        val current = player.currentMediaItem?.mediaMetadata ?: return false
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val next = player.getMediaItemAt(nextIndex).mediaMetadata
        return current.albumTitle != null && current.albumTitle == next.albumTitle
    }

    private fun startCrossfade() {
        if (isCrossfading) return
        if (secondaryPlayer != null) return
        if (castConnectionHandler?.isCasting?.value == true) return
        val mixProfile = pendingMetroMixProfile ?: currentMetroMixProfile()
        pendingMetroMixProfile = null
        activeCrossfadeDurationMs = mixProfile.durationMs
        activeMetroMixRuntimePreset = mixProfile.preset
        activeMetroMixRuntimeProfile = mixProfile

        // Preserve player state before creating the secondary player
        // Use runBlocking to ensure we get the correct state from DataStore
        val savedRepeatMode = runBlocking { dataStore.get(RepeatModeKey, REPEAT_MODE_OFF) }
        val savedShuffleEnabled = runBlocking { dataStore.get(ShuffleModeKey, false) }

        // For repeat-one, crossfade back into the same track
        val targetIndex =
            if (savedRepeatMode == REPEAT_MODE_ONE) {
                player.currentMediaItemIndex
            } else {
                player.nextMediaItemIndex
            }
        if (targetIndex == C.INDEX_UNSET) return
        val targetMediaItem = runCatching { player.getMediaItemAt(targetIndex) }.getOrNull()

        secondaryPlayer = createExoPlayer(publishToUi = false)
        val secPlayer = secondaryPlayer!!
        secPlayer.addListener(secondaryPlayerListener)
        var swapped = false
        val readinessListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_READY || swapped) return
                    swapped = true
                    secPlayer.removeListener(this)
                    crossfadePrepareJob?.cancel()
                    crossfadePrepareJob = null
                    performCrossfadeSwap(targetIndex, targetMediaItem)
                    if (savedShuffleEnabled) {
                        val shufflePlaylistFirst = dataStore.get(ShufflePlaylistFirstKey, false)
                        applyShuffleOrder(player.currentMediaItemIndex, player.mediaItemCount, shufflePlaylistFirst)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    swapped = true
                    secPlayer.removeListener(this)
                    cleanupSecondaryCrossfadePlayer()
                }
            }
        secPlayer.addListener(readinessListener)

        val itemCount = player.mediaItemCount
        val items = mutableListOf<MediaItem>()
        // Copy entire queue history + future
        for (i in 0 until itemCount) {
            items.add(player.getMediaItemAt(i))
        }

        secPlayer.setMediaItems(items)
        // Seek to target track (next track, or current track for repeat-one)
        secPlayer.seekTo(targetIndex, 0)
        secPlayer.volume = 0f

        // Copy repeat and shuffle state to the new player
        secPlayer.repeatMode = savedRepeatMode
        secPlayer.shuffleModeEnabled = savedShuffleEnabled

        secPlayer.prepare()
        secPlayer.playWhenReady = true

        crossfadePrepareJob =
            scope.launch {
                delay((activeCrossfadeDurationMs + 5_000L).coerceAtLeast(8_000L))
                if (!swapped && secondaryPlayer === secPlayer) {
                    Timber.tag(TAG).w("Crossfade secondary player did not become ready in time")
                    secPlayer.removeListener(readinessListener)
                    cleanupSecondaryCrossfadePlayer()
                }
            }
    }

    private fun crossfadeVolumePair(
        progress: Float,
        preset: MetroMixPreset?,
        profile: MetroMixRuntimeProfile? = null,
    ): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        fun smooth(value: Float) = value * value * (3f - 2f * value)
        fun easeOut(value: Float) = 1f - (1f - value) * (1f - value)
        fun easeIn(value: Float) = value * value
        fun range(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)
        fun equalPowerIn(value: Float) = sin(value.coerceIn(0f, 1f) * (PI / 2.0)).toFloat()
        fun equalPowerOut(value: Float) = cos(value.coerceIn(0f, 1f) * (PI / 2.0)).toFloat()
        fun centerDuck(amount: Float): Float {
            val distanceFromCenter = (2f * p - 1f).coerceIn(-1f, 1f)
            return 1f - amount * (1f - distanceFromCenter * distanceFromCenter)
        }
        fun pair(fadeIn: Float, fadeOut: Float, headroom: Float = 1f): Pair<Float, Float> =
            (fadeIn.coerceIn(0f, 1f) * headroom) to (fadeOut.coerceIn(0f, 1f) * headroom)
        val effectivePreset =
            when {
                profile?.effectCurve == MetroMixEffectCurve.ECHO -> MetroMixPreset.ECHO_OUT
                profile?.effectCurve == MetroMixEffectCurve.WAVE -> MetroMixPreset.BEAT_BLEND
                profile?.eqCurve == MetroMixEqCurve.BASS_SWAP -> MetroMixPreset.BASS_SWAP
                profile?.eqCurve == MetroMixEqCurve.VOCAL_SPACE -> MetroMixPreset.VOCAL_BLEND
                profile?.volumeCurve == MetroMixVolumeCurve.PUNCHY -> MetroMixPreset.ENERGY_MATCH
                profile?.volumeCurve == MetroMixVolumeCurve.MELT -> MetroMixPreset.LOOP_OUT
                profile?.volumeCurve == MetroMixVolumeCurve.WAVE -> MetroMixPreset.BEAT_BLEND
                profile?.volumeCurve == MetroMixVolumeCurve.BALANCED -> MetroMixPreset.SMART_DJ
                else -> preset
            }

        return when (effectivePreset) {
            null -> {
                val fadeIn = easeOut(p)
                val fadeOut = (1f - p) * (1f - p)
                pair(fadeIn, fadeOut)
            }

            MetroMixPreset.AUTO,
            MetroMixPreset.SMART_DJ,
            MetroMixPreset.SMOOTH -> {
                val duck = centerDuck(0.10f)
                pair(equalPowerIn(smooth(p)), equalPowerOut(smooth(p)), duck)
            }

            MetroMixPreset.BEAT_BLEND -> {
                val fadeIn = equalPowerIn(smooth(range(p, 0.05f, 0.95f)))
                val fadeOut = equalPowerOut(smooth(range(p, 0.05f, 0.95f)))
                pair(fadeIn, fadeOut, centerDuck(0.08f))
            }

            MetroMixPreset.ENERGY_MATCH -> {
                val fadeIn = easeOut(range(p, 0.02f, 0.92f))
                val fadeOut = 1f - easeIn(range(p, 0.08f, 1f))
                pair(fadeIn, fadeOut, centerDuck(0.14f))
            }

            MetroMixPreset.CLUB_BLEND -> {
                val fadeIn = smooth(range(p, 0.00f, 0.86f))
                val fadeOut = 1f - smooth(range(p, 0.18f, 1f))
                pair(fadeIn, fadeOut, centerDuck(0.16f))
            }

            MetroMixPreset.VOCAL_BLEND -> {
                val fadeIn = smooth(range(p, 0.34f, 1f))
                val fadeOut = 1f - smooth(range(p, 0.08f, 0.76f))
                pair(fadeIn, fadeOut, centerDuck(0.06f))
            }

            MetroMixPreset.BASS_SWAP -> {
                val fadeIn = easeOut(range(p, 0.18f, 0.74f))
                val fadeOut = 1f - smooth(range(p, 0.38f, 0.80f))
                pair(fadeIn, fadeOut, centerDuck(0.18f))
            }

            MetroMixPreset.RADIO_EDIT -> {
                val curve = smooth(p)
                pair(equalPowerIn(curve), equalPowerOut(curve), centerDuck(0.12f))
            }

            MetroMixPreset.QUICK_CUT -> {
                val fadeIn = smooth(range(p, 0.04f, 0.52f))
                val fadeOut = 1f - smooth(range(p, 0.20f, 0.58f))
                pair(fadeIn, fadeOut, centerDuck(0.05f))
            }

            MetroMixPreset.LOOP_OUT -> {
                val fadeIn = equalPowerIn(smooth(p))
                val fadeOut = (1f - smooth(range(p, 0.10f, 1f))) * (1f - 0.15f * p)
                pair(fadeIn, fadeOut, centerDuck(0.18f))
            }

            MetroMixPreset.FADE -> pair(p, 1f - p)

            MetroMixPreset.RISE -> {
                val fadeIn = easeOut(p)
                val fadeOut = 1f - smooth(p)
                pair(fadeIn, fadeOut)
            }

            MetroMixPreset.BLEND -> {
                val fadeIn = smooth(p)
                val fadeOut = (1f - easeIn(p)).coerceIn(0f, 1f)
                pair(fadeIn, fadeOut, centerDuck(0.10f))
            }

            MetroMixPreset.DROP -> {
                val delayed = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                pair(smooth(delayed), if (p < 0.42f) 1f else (1f - smooth(delayed)))
            }

            MetroMixPreset.ECHO_OUT -> {
                val fadeIn = smooth(p)
                val remaining = 1f - p
                pair(fadeIn, remaining * remaining * remaining)
            }

            MetroMixPreset.LONG_BLEND -> {
                val fadeIn = easeOut(p)
                val fadeOut = 1f - easeIn(p)
                pair(fadeIn, fadeOut, centerDuck(0.12f))
            }
        }
    }

    private fun performCrossfadeSwap(
        targetIndex: Int,
        targetMediaItem: MediaItem?,
    ) {
        val nextPlayer = secondaryPlayer ?: return
        isCrossfading = true
        val currentPlayer = player
        val metroMixPreset = activeMetroMixRuntimePreset ?: activeMetroMixPreset
        val metroMixProfile = activeMetroMixRuntimeProfile

        fadingPlayer = currentPlayer
        player = nextPlayer
        secondaryPlayer = null

        fadingPlayer?.removeListener(this)
        fadingPlayer?.removeListener(sleepTimer)

        // Add listener to sync play/pause state
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isCrossfading && fadingPlayer != null) {
                        if (isPlaying) {
                            fadingPlayer?.play()
                        } else {
                            fadingPlayer?.pause()
                        }
                    } else {
                        player.removeListener(this)
                    }
                }
            },
        )

        nextPlayer.removeListener(secondaryPlayerListener)
        nextPlayer.addListener(this)
        nextPlayer.addListener(sleepTimer)

        sleepTimer.player = player

        try {
            (mediaSession as MediaSession).player = player
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to swap player in MediaSession")
        }

        val previousAudioSessionId = fadingPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
        previousMediaItemIndex = targetIndex
        val transitionedMetadata = targetMediaItem?.metadata ?: player.currentMetadata
        currentMediaMetadata.value = transitionedMetadata
        updateCurrentAudioFormatFromTracks(player.currentTracks)
        _playerFlow.value = player
        updateNotification()
        updateWidgetUI(player.isPlaying)
        lastPlaybackSpeed = -1.0f
        discordUpdateJob?.cancel()
        if (player.isPlaying && transitionedMetadata != null) {
            scrobbleManager?.onSongStop()
            scrobbleManager?.onSongStart(transitionedMetadata, duration = player.duration)
            scope.launch {
                database.song(transitionedMetadata.id).first()?.let { song ->
                    updateDiscordRPC(song)
                }
            }
        }
        if (dataStore.get(PersistentQueueKey, true)) {
            saveQueueToDisk()
        }

        openAudioEffectSession()

        crossfadeJob =
            scope.launch {
                val duration = activeCrossfadeDurationMs.coerceAtLeast(750L)
                val steps = (duration / 40L).toInt().coerceIn(24, 120)
                val stepTime = (duration / steps).coerceAtLeast(16L)
                val startVolume =
                    try {
                        fadingPlayer?.volume ?: 1f
                    } catch (e: Exception) {
                        1f
                    }

                for (i in 0..steps) {
                    if (!isActive) break
                    // Pause volume ramp if player is paused
                    while (!player.isPlaying && isActive) {
                        delay(100)
                    }

                    val progress = i / steps.toFloat()
                    val (fadeIn, fadeOut) = crossfadeVolumePair(progress, metroMixPreset, metroMixProfile)

                    try {
                        player.volume = startVolume * fadeIn
                        fadingPlayer?.volume = startVolume * fadeOut
                    } catch (e: Exception) {
                        break
                    }

                    delay(stepTime)
                }

                try {
                    fadingPlayer?.volume = 0f
                    player.volume = startVolume
                } catch (e: Exception) {
                }

                cleanupCrossfade(fadingPlayerSessionId = previousAudioSessionId)
            }
    }

    private fun cleanupSecondaryCrossfadePlayer(scheduleNext: Boolean = true) {
        crossfadePrepareJob?.cancel()
        crossfadePrepareJob = null
        secondaryPlayer?.let { player ->
            runCatching {
                player.removeListener(secondaryPlayerListener)
                player.stop()
                player.clearMediaItems()
                player.release()
            }
        }
        secondaryPlayer = null
        isCrossfading = false
        activeMetroMixRuntimePreset = null
        activeMetroMixRuntimeProfile = null
        pendingMetroMixProfile = null
        if (scheduleNext) {
            scheduleCrossfade()
        }
    }

    private fun cleanupCrossfade(
        fadingPlayerSessionId: Int = C.AUDIO_SESSION_ID_UNSET,
        scheduleNext: Boolean = true,
    ) {
        crossfadePrepareJob?.cancel()
        crossfadePrepareJob = null
        fadingPlayer?.stop()
        fadingPlayer?.clearMediaItems()
        fadingPlayer?.release()
        fadingPlayer = null
        isCrossfading = false
        activeMetroMixRuntimePreset = null
        activeMetroMixRuntimeProfile = null
        pendingMetroMixProfile = null
        applyEffectiveVolume()
        sleepTimer.notifySongTransition()

        if (fadingPlayerSessionId != C.AUDIO_SESSION_ID_UNSET && fadingPlayerSessionId > 0) {
            closeAudioEffectSession(sessionIdOverride = fadingPlayerSessionId, clearNormalizationCache = true)
        }
        if (scheduleNext) {
            scheduleCrossfade()
        }
    }

    companion object {
        const val ACTION_ALARM_TRIGGER = "com.metrofuse.music.action.ALARM_TRIGGER"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_PLAYLIST_ID = "extra_alarm_playlist_id"
        const val EXTRA_ALARM_RANDOM_SONG = "extra_alarm_random_song"

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"
        const val YOUTUBE_PLAYLIST = "youtube_playlist"
        const val SEARCH = "search"
        const val SHUFFLE_ACTION = "__shuffle__"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MAX_RETRY_COUNT = 10

        // Constants for audio normalization
        private const val MAX_GAIN_MB = 300 // Maximum gain in millibels (3 dB)
        private const val MIN_GAIN_MB = -1500 // Minimum gain in millibels (-15 dB)

        private const val TAG = "MusicService"
        private const val AUDIO_FORMAT_RETRY_ATTEMPTS = 8
        private const val AUDIO_FORMAT_RETRY_DELAY_MS = 1_000L
        private const val PRIVATE_STREAM_MARKER = "_metrolist_private"
        private const val TIDAL_PENDING_MANIFEST_SCHEME = "metrofuse-tidal"
        private const val TIDAL_PENDING_MANIFEST_HOST = "manifest"
        private const val TIDAL_PENDING_MEDIA_ID = "media_id"
        private const val TIDAL_PENDING_EXPECT_DASH = "expect_dash"
        private const val APPLE_MUSIC_WRAPPER_ITAG = 100_001
        private const val QOBUZ_FALLBACK_ITAG = 100_027
        private const val TIDAL_FALLBACK_ITAG = 100_029
        private const val DEEZER_FALLBACK_ITAG = 100_033
        private const val SOUNDCLOUD_FALLBACK_ITAG = 100_031
        private const val INSTAGRAM_FALLBACK_ITAG = 100_041
        private const val APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac-v2:"
        private const val OLD_QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback:"
        private const val QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback-v2:"
        private const val OLD_TIDAL_FALLBACK_CACHE_PREFIX = "tidal-flac-fallback:"
        private const val TIDAL_FALLBACK_CACHE_PREFIX = "tidal-flac-fallback-temp-v1:"
        private const val DEEZER_FALLBACK_CACHE_PREFIX = "deezer-fallback-audio:"
        private const val SOUNDCLOUD_FALLBACK_CACHE_PREFIX = "soundcloud-fallback-mp3:"
        private const val INSTAGRAM_FALLBACK_CACHE_PREFIX = "instagram-fallback-audio:"
        private const val YOUTUBE_FALLBACK_CACHE_PREFIX = "youtube-fallback-aac:"
        private const val ALAC_MIN_BUFFER_MS = 18_000
        private const val ALAC_MAX_BUFFER_MS = 60_000
        private const val ALAC_BUFFER_FOR_PLAYBACK_MS = 1_200
        private const val ALAC_BUFFER_FOR_REBUFFER_MS = 6_000
        private const val DEBUG_DISABLE_APPLE_ALAC_PROVIDER_FALLBACK = false

        private fun appleWrapperCacheKey(mediaId: String) = "$APPLE_WRAPPER_CACHE_PREFIX$mediaId"

        private fun qobuzFallbackCacheKey(mediaId: String) = "$QOBUZ_FALLBACK_CACHE_PREFIX$mediaId"

        private fun tidalFallbackCacheKey(mediaId: String) = "$TIDAL_FALLBACK_CACHE_PREFIX$mediaId"

        private fun deezerFallbackCacheKey(mediaId: String) = "$DEEZER_FALLBACK_CACHE_PREFIX$mediaId"

        private fun pendingTidalManifestUri(
            mediaId: String,
            expectDash: Boolean,
        ): String =
            Uri.Builder()
                .scheme(TIDAL_PENDING_MANIFEST_SCHEME)
                .authority(TIDAL_PENDING_MANIFEST_HOST)
                .appendQueryParameter(TIDAL_PENDING_MEDIA_ID, mediaId)
                .appendQueryParameter(TIDAL_PENDING_EXPECT_DASH, if (expectDash) "1" else "0")
                .build()
                .toString()

        private fun soundCloudFallbackCacheKey(mediaId: String) = "$SOUNDCLOUD_FALLBACK_CACHE_PREFIX$mediaId"

        private fun instagramFallbackCacheKey(mediaId: String) = "$INSTAGRAM_FALLBACK_CACHE_PREFIX$mediaId"

        private fun youtubeFallbackCacheKey(mediaId: String) = "$YOUTUBE_FALLBACK_CACHE_PREFIX$mediaId"

        private fun isTidalFallbackCacheKey(key: String): Boolean =
            key.startsWith(TIDAL_FALLBACK_CACHE_PREFIX) ||
                key.startsWith(OLD_TIDAL_FALLBACK_CACHE_PREFIX)

        private fun isProviderFallbackCacheKey(key: String): Boolean =
            key.startsWith(QOBUZ_FALLBACK_CACHE_PREFIX) ||
                isTidalFallbackCacheKey(key) ||
                key.startsWith(DEEZER_FALLBACK_CACHE_PREFIX) ||
                key.startsWith(SOUNDCLOUD_FALLBACK_CACHE_PREFIX) ||
                key.startsWith(INSTAGRAM_FALLBACK_CACHE_PREFIX) ||
                key.startsWith(YOUTUBE_FALLBACK_CACHE_PREFIX)

        private fun Uri.isResolvedProviderPlaybackUri(): Boolean =
            when (scheme?.lowercase(Locale.US)) {
                "http", "https", "data", "file" -> true
                else -> false
            }

        private fun Uri.isTidalPlaybackCdnUri(): Boolean {
            val host = host?.lowercase(Locale.US) ?: return false
            return path?.contains("/mediatracks/", ignoreCase = true) == true &&
                (
                    host == "audio.tidal.com" ||
                        host.endsWith(".audio.tidal.com") ||
                        host.endsWith(".tidal.com")
                )
        }

        private fun Uri.isPendingTidalManifestUri(): Boolean =
            scheme?.equals(TIDAL_PENDING_MANIFEST_SCHEME, ignoreCase = true) == true &&
                host?.equals(TIDAL_PENDING_MANIFEST_HOST, ignoreCase = true) == true

        private fun Uri.isPendingTidalDashRequest(): Boolean =
            isPendingTidalManifestUri() &&
                getQueryParameter(TIDAL_PENDING_EXPECT_DASH) != "0"

        private fun Uri.mediaIdFromPendingTidalManifestUri(): String? =
            if (isPendingTidalManifestUri()) {
                getQueryParameter(TIDAL_PENDING_MEDIA_ID)?.takeIf { it.isNotBlank() }
            } else {
                null
            }

        private fun mediaIdFromDataSpecKey(key: String): String? =
            key
                .removePrefix(APPLE_WRAPPER_CACHE_PREFIX)
                .removePrefix(OLD_QOBUZ_FALLBACK_CACHE_PREFIX)
                .removePrefix(QOBUZ_FALLBACK_CACHE_PREFIX)
                .removePrefix(TIDAL_FALLBACK_CACHE_PREFIX)
                .removePrefix(OLD_TIDAL_FALLBACK_CACHE_PREFIX)
                .removePrefix(DEEZER_FALLBACK_CACHE_PREFIX)
                .removePrefix(SOUNDCLOUD_FALLBACK_CACHE_PREFIX)
                .removePrefix(INSTAGRAM_FALLBACK_CACHE_PREFIX)
                .removePrefix(YOUTUBE_FALLBACK_CACHE_PREFIX)
                .takeUnless { Uri.parse(it).isTidalPlaybackCdnUri() }

        private fun appleWrapperFormat(
            mediaId: String,
            bitrate: Int = 0,
            sampleRate: Int? = null,
        ) = FormatEntity(
            id = mediaId,
            itag = APPLE_MUSIC_WRAPPER_ITAG,
            mimeType = "audio/mp4",
            codecs = "alac",
            bitrate = bitrate,
            sampleRate = sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun qobuzFallbackFormat(
            mediaId: String,
            resolved: QobuzAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = QOBUZ_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun tidalFallbackFormat(
            mediaId: String,
            resolved: TidalAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = TIDAL_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun tidalDirectPlaybackFormat(mediaId: String) = FormatEntity(
            id = mediaId,
            itag = TIDAL_FALLBACK_ITAG,
            mimeType = MimeTypes.AUDIO_MP4,
            codecs = "mp4a.40.2",
            bitrate = 320_000,
            sampleRate = null,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun deezerFallbackFormat(
            mediaId: String,
            resolved: DeezerAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = DEEZER_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun soundCloudFallbackFormat(
            mediaId: String,
            resolved: SoundCloudAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = SOUNDCLOUD_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun instagramFallbackFormat(
            mediaId: String,
            resolved: InstagramAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = INSTAGRAM_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun youtubeFallbackFormat(
            mediaId: String,
            resolved: YouTubeAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = resolved.itag,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = resolved.loudnessDb,
            perceptualLoudnessDb = resolved.perceptualLoudnessDb,
            playbackUrl = null,
        )

        @Volatile
        var isRunning = false
            private set
    }
}
