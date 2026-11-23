package com.metrolist.shared.plugins

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.NoPlayableSourceException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow

/** Optional platform bridge. Platforms without a plugin runtime retain upstream behavior. */
interface PluginServices {
    suspend fun awaitReady() {}

    val canMatchAudio: Boolean get() = false
    val audioMatchingController: PlaybackController? get() = null

    fun audioMatch(id: String): AudioMatchCandidate? = null

    suspend fun audioMatches(
        song: SongItem,
        query: String? = null,
    ): List<AudioMatchCandidate> = emptyList()

    suspend fun setAudioMatch(
        id: String,
        candidate: AudioMatchCandidate?,
    ): Boolean = false

    suspend fun canvas(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): String? = null

    fun refreshHome(
        owner: String,
        force: Boolean = false,
    ) {}

    fun loadMoreHome(owner: String) {}

    suspend fun resolve(
        id: String,
        song: SongItem?,
        quality: AudioQuality,
        refresh: Boolean,
        excludedProviders: Set<String>,
    ): PlaybackResource?

    suspend fun lyrics(
        id: String,
        title: String,
        artist: String,
        durationMs: Long?,
    ): LyricsWithProvider?

    suspend fun search(query: String): List<YTItem>

    suspend fun metadata(id: String): SongItem?

    fun isLyricsProviderEnabled(id: String): Boolean = false

    val hasLyricsProviders: Boolean get() = false

    suspend fun album(id: String): AlbumPage? = null

    suspend fun artist(id: String): ArtistPageResult? = null

    suspend fun playlist(id: String): Map<String, List<YTItem>>? = null

    suspend fun startRadioQueue(id: String): RadioQueuePage? = null

    suspend fun continueRadioQueue(continuation: String): RadioQueuePage? = null

    suspend fun library(continuation: String? = null): PluginLibraryPage? = null

    suspend fun likedSongs(continuation: String? = null): PluginLibraryPage? = null

    val canSyncSongVotes: Boolean get() = false

    suspend fun setSongVote(
        id: String,
        vote: SongVote,
    ): Boolean = false
}

object PluginServicesStore {
    private val services = MutableStateFlow<PluginServices?>(null)
    val frontends = MutableStateFlow<List<MusicFrontend>>(emptyList())
    val homePages = MutableStateFlow<Map<String, PluginHomePage>>(emptyMap())
    val libraryAccounts = MutableStateFlow<Map<String, PluginLibraryAccount>>(emptyMap())
    var current: PluginServices?
        get() = services.value
        set(value) {
            services.value = value
            if (value == null) {
                frontends.value = emptyList()
                homePages.value = emptyMap()
                libraryAccounts.value = emptyMap()
            }
        }
}

/** Resolves local audio directly and delegates remote audio to registered providers. */
class PluginStreamResolver(
    private val localAudioSource: LocalAudioSource? = null,
    private val offlineOnly: () -> Boolean = { effectiveMusicFrontendOwner().isEmpty() },
) : StreamResolver {
    override suspend fun resolve(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource = resolveAudio(videoId, hints, audioQuality, sourceSong)

    override suspend fun refresh(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
    ): PlaybackResource = resolveAudio(videoId, hints, audioQuality, sourceSong, refresh = true)

    override suspend fun resolveRemote(
        videoId: String,
        hints: ContentHints,
        audioQuality: AudioQuality,
        sourceSong: SongItem?,
        refresh: Boolean,
        excludedProfiles: Set<String>,
    ): PlaybackResource =
        resolveAudio(
            videoId,
            hints,
            audioQuality,
            sourceSong,
            refresh,
            excludedProfiles,
            allowLocal = false,
        )

    private suspend fun resolveAudio(
        id: String,
        hints: ContentHints,
        quality: AudioQuality,
        song: SongItem?,
        refresh: Boolean = false,
        excludedProviders: Set<String> = emptySet(),
        allowLocal: Boolean = true,
    ): PlaybackResource {
        if (hints.wantVideo || hints.playbackClientOverrideId != null) {
            throw NoPlayableSourceException(NoPlayableSourceException.Reason.VIDEO_UNSUPPORTED)
        }
        if (allowLocal && (offlineOnly() || PluginServicesStore.current?.audioMatch(id) == null)) {
            localAudioSource?.resolve(id, song)?.let {
                if (offlineOnly() && it.networkUrl != null) {
                    throw NoPlayableSourceException(NoPlayableSourceException.Reason.OFFLINE_MODE)
                }
                return it
            }
        }
        if (Settings.shared.musicFrontendOwner.toHomeSelection() is HomeSelection.Plugin) PluginServicesStore.current?.awaitReady()
        if (offlineOnly()) throw NoPlayableSourceException(NoPlayableSourceException.Reason.OFFLINE_MODE)
        if (hints.isLocal == true || song?.isLocal == true) {
            throw NoPlayableSourceException(NoPlayableSourceException.Reason.LOCAL_FILE_MISSING)
        }

        val currentServices =
            PluginServicesStore.current
                ?: throw NoPlayableSourceException(NoPlayableSourceException.Reason.NO_AUDIO_PROVIDER)
        val resolved =
            currentServices.resolve(id, song, quality, refresh, excludedProviders)
                ?: throw NoPlayableSourceException(NoPlayableSourceException.Reason.NO_PROVIDER_SUCCEEDED)
        if (offlineOnly()) throw NoPlayableSourceException(NoPlayableSourceException.Reason.OFFLINE_MODE)
        return resolved
    }
}

internal fun pluginSearchMatches(
    item: YTItem,
    filter: SearchFilter,
): Boolean =
    when (filter) {
        SearchFilter.SONG -> item is SongItem && !item.isPodcast && !item.isVideoSong
        SearchFilter.VIDEO -> item is SongItem && item.isVideoSong
        SearchFilter.EPISODE -> item is SongItem && item.isPodcast
        SearchFilter.ALBUM -> item is AlbumItem
        SearchFilter.ARTIST -> item is ArtistItem
        SearchFilter.FEATURED_PLAYLIST, SearchFilter.COMMUNITY_PLAYLIST -> item is PlaylistItem
        SearchFilter.PODCAST, SearchFilter.PROFILE -> false
    }
