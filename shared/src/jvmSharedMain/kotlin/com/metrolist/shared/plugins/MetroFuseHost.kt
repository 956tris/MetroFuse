package com.metrolist.shared.plugins

import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PluginHomeLayout
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.youtube.playback.PlaybackResource
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.runtime.CredentialStore
import dev.metrofuse.runtime.MetadataMapping
import dev.metrofuse.runtime.Runtime
import dev.metrofuse.runtime.UnavailableCredentialStore
import dev.metrofuse.runtime.pluginHomeSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** One engine per process, using the application's existing controller and resolver. */
object MetroFuseHost : PluginServices {
    private var engine: Runtime? = null
    private var hostScope: CoroutineScope? = null
    private val ready = CompletableDeferred<Unit>()
    private val playbackAdapter = ControllerPlayer(::songFor)
    private val navigation = ApplicationNavigation()
    private val songs = ConcurrentHashMap<String, SongItem>()
    private val identities = ConcurrentHashMap<String, TrackIdentity>()
    private val albums = ConcurrentHashMap<String, CatalogAlbum>()
    private val artists = ConcurrentHashMap<String, CatalogArtist>()
    private val playlists = ConcurrentHashMap<String, CatalogPlaylist>()
    private var platform = "desktop"

    val runtime: Runtime get() = checkNotNull(engine) { "Plugin host has not been configured" }
    val isConfigured: Boolean get() = engine != null

    override suspend fun awaitReady() {
        ready.await()
    }

    @Synchronized
    fun configure(
        directory: File,
        platform: String,
        parentScope: CoroutineScope,
        credentialStore: CredentialStore = UnavailableCredentialStore,
    ) {
        if (engine != null) return
        this.platform = platform
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        hostScope = scope
        val services =
            object : HostServices {
                override val playback = playbackAdapter
                override val navigation = MetroFuseHost.navigation
                override val catalog =
                    object : TrackCatalog {
                        override val tracks: List<Track> get() = playbackAdapter.state.value.queue

                        override suspend fun identify(identity: TrackIdentity): Track? =
                            songs[identity.identifiers["host"] ?: identity.canonicalId]?.toPluginTrack()
                    }
                override val settings =
                    object : HostSettings {
                        override fun get(key: String): String? =
                            when (key) {
                                "audio.quality" -> Settings.shared.audioQuality
                                else -> null
                            }

                        override fun set(
                            key: String,
                            value: String?,
                        ) {
                            require(key == "audio.quality" && value != null) { "Unsupported public host setting" }
                            AudioQuality.valueOf(value)
                            Settings.shared.audioQuality = value
                        }
                    }
                override val logger = HostLogger { owner, message -> Logger.i("MetroFuse/$owner", message) }
            }
        engine = Runtime(directory, platform, scope, services, hostVersion = BuildConfig.APP_VERSION, credentialStore = credentialStore)
        PluginServicesStore.current = this
        scope.launch {
            ready.await()
            combine(runtime.plugins, snapshotFlow { Settings.shared.musicFrontendOwner }) { plugins, _ ->
                pluginHomeSources(plugins).map { MusicFrontend(it.owner, it.title, it.icon) }
            }.collect { frontends ->
                PluginServicesStore.frontends.value = frontends
                val selection =
                    Settings.shared.musicFrontendOwner
                        .toHomeSelection()
                        .available(frontends)
                if (Settings.shared.musicFrontendOwner != selection.persisted) Settings.shared.musicFrontendOwner = selection.persisted
            }
        }
        scope.launch {
            runtime.homes.pages.collect { pages ->
                PluginServicesStore.homePages.value =
                    pages.mapValues { (owner, page) ->
                        PluginHomePage(
                            sections =
                                page.shelves.mapIndexed { index, shelf ->
                                    HomeSection(
                                        title = shelf.title,
                                        items =
                                            shelf.items.mapNotNull { item ->
                                                item.track?.let(::songFor)
                                                    ?: item.album?.let {
                                                        retain(albums, it.id, it)
                                                        albumItem(it)
                                                    }
                                                    ?: item.artist?.let {
                                                        retain(artists, it.id, it)
                                                        ArtistItem(it.id, it.name, it.artwork.takeIf(String::isNotBlank))
                                                    }
                                                    ?: item.playlist?.let {
                                                        retain(playlists, it.id, it)
                                                        PlaylistItem(
                                                            it.id,
                                                            it.title,
                                                            it.artwork.takeIf(String::isNotBlank),
                                                            author = it.author,
                                                        )
                                                    }
                                            },
                                        browseId = "metrofuse-home:$owner:$index",
                                        pluginLayout =
                                            if (shelf.layout ==
                                                HomeLayout.ROWS_GRID
                                            ) {
                                                PluginHomeLayout.ROWS_GRID
                                            } else {
                                                PluginHomeLayout.CARDS
                                            },
                                        isResponsiveList = shelf.layout == HomeLayout.ROWS_GRID,
                                        numItemsPerColumn = if (shelf.layout == HomeLayout.ROWS_GRID) 4 else 1,
                                    )
                                },
                            loading = page.loading,
                            error = page.error,
                            loaded = page.loaded,
                        )
                    }
            }
        }
        scope.launch {
            ready.await()
            combine(
                musicFrontendOwnerChanges(),
                snapshotFlow {
                    Settings.shared.contentLanguage to Settings.shared.contentCountry
                },
            ) { owner, _ -> owner }
                .collect { owner -> if (owner.isNotEmpty()) refreshHome(owner) }
        }
        scope.launch {
            try {
                runtime.initialize()
                ready.complete(Unit)
            } catch (error: CancellationException) {
                ready.cancel(error)
                throw error
            } catch (error: Exception) {
                runtime.log("host", "Initialization failed: ${error::class.simpleName}")
                ready.complete(Unit)
            }
        }
        scope.launch {
            runtime.metadata.collect { track ->
                val original = songs[track.id] ?: return@collect
                val resolved = track.toSongItem(original)
                songs[track.id] = resolved
            }
        }
    }

    fun attachController(controller: PlaybackController) {
        playbackAdapter.attach(controller, checkNotNull(hostScope), ::remember)
        checkNotNull(hostScope).launch {
            combine(musicFrontendOwnerChanges(), controller.state) { owner, state -> owner to state }.collect { (owner, state) ->
                if (owner.isEmpty() && state.currentPlaybackResource?.networkUrl != null) {
                    withContext(Dispatchers.Main.immediate) { controller.stop() }
                }
            }
        }
    }

    override fun refreshHome(
        owner: String,
        force: Boolean,
    ) {
        val scope = hostScope ?: return
        scope.launch {
            ready.await()
            val locale = Locale.getDefault()
            val language = Settings.shared.contentLanguage.takeUnless { it == "SYSTEM" } ?: locale.language
            val country = Settings.shared.contentCountry.takeUnless { it == "SYSTEM" } ?: locale.country
            runtime.homes.refresh(owner, HomeRequest(language, country), force)
        }
    }

    fun playTrack(track: Track) {
        playbackAdapter.playTrack(songFor(track))
    }

    private fun songFor(track: Track): SongItem {
        require(track.id.isNotBlank() && track.title.isNotBlank() && track.extras.size <= 64) { "Invalid plugin track" }
        identities[track.id] = trackIdentity(track.id, track.extras)
        return (songs[track.id] ?: track.toSongItem()).also(::remember)
    }

    fun bindNavigator(navigator: Navigator): () -> Unit = navigation.bind(navigator, checkNotNull(hostScope))

    suspend fun close() =
        withContext(NonCancellable) {
            PluginServicesStore.current = null
            engine?.close()
            hostScope?.cancel()
            playbackAdapter.detach()
        }

    private fun remember(song: SongItem) {
        if (songs.size >= 512 && !songs.containsKey(song.id)) {
            songs.keys.firstOrNull()?.let {
                songs.remove(it)
                identities.remove(it)
            }
        }
        songs[song.id] = song
    }

    override suspend fun resolve(
        id: String,
        song: SongItem?,
        quality: AudioQuality,
        refresh: Boolean,
        excludedProviders: Set<String>,
    ): PlaybackResource? {
        ready.await()
        if (effectiveMusicFrontendOwner().isEmpty()) return null
        song?.let(::remember)
        if (refresh) runtime.pipelines.invalidateCaches()
        val identity = identities[id] ?: trackIdentity(id)
        val preference =
            QualityPreference(
                preset =
                    when (quality) {
                        AudioQuality.AUTO -> QualityPreset.AUTO
                        AudioQuality.LOW -> QualityPreset.LOW
                        AudioQuality.HIGH -> QualityPreset.HIGH
                        AudioQuality.MP4 -> QualityPreset.MP4
                    },
            )
        val result =
            runtime.pipelines.audio(
                AudioQuery(identity, platform, preference),
                includeBuiltin = false,
                excludedProviders = excludedProviders,
            )
        val descriptor = result.stream ?: return null
        if (effectiveMusicFrontendOwner().isEmpty()) return null
        val provider = descriptor.attributes["providerId"] ?: error("Audio provider identity missing")
        require(
            descriptor.transport in
                setOf(
                    StreamTransport.DIRECT,
                    StreamTransport.RANGE_HTTP,
                    StreamTransport.HLS,
                    StreamTransport.SABR_UMP,
                ),
        ) {
            "Unsupported plugin stream transport"
        }
        val sabr = pluginSabrBootstrap(descriptor, identity.identifiers["youtube"] ?: identity.canonicalId)
        // FFmpeg's Windows file protocol does not accept Java's file:/C:/ URI form.
        val playbackLocator =
            if (platform == "desktop" && descriptor.locator.startsWith("file:", ignoreCase = true)) {
                File(URI(descriptor.locator)).absolutePath
            } else {
                descriptor.locator
            }
        return PlaybackResource(
            videoId = id,
            url = playbackLocator,
            headers = descriptor.headers,
            loudnessDb = null,
            expiresAt = descriptor.expiresAtMillis?.let(Instant::fromEpochMilliseconds),
            contentLengthBytes = descriptor.contentLength ?: sabr?.contentLengthBytes,
            mimeType =
                sabr?.mimeType ?: descriptor.mimeType
                    ?: if (descriptor.transport == StreamTransport.HLS) "application/vnd.apple.mpegurl" else null,
            codecs = descriptor.codec,
            bitrate = descriptor.bitrate,
            sampleRate = descriptor.sampleRate,
            durationSeconds = descriptor.durationSeconds ?: sabr?.durationMs?.div(1000) ?: song?.duration,
            clientName = provider,
            profileId = provider,
            requireBoundedRange = descriptor.transport == StreamTransport.RANGE_HTTP,
            useRangeChunks = descriptor.transport == StreamTransport.RANGE_HTTP,
            sabrBootstrap = sabr,
        )
    }

    override suspend fun lyrics(
        id: String,
        title: String,
        artist: String,
        durationMs: Long?,
    ): LyricsWithProvider? {
        ready.await()
        if (effectiveMusicFrontendOwner().isEmpty()) return null
        val result =
            runtime.pipelines.lyrics(LyricsRequest(identities[id] ?: trackIdentity(id), title, artist, durationMs ?: 0)) ?: return null
        val document =
            MlxDocument(
                meta = MlxMeta(lyricSource = result.source, provider = result.source),
                song = MlxSong(title = title, artist = artist, durationMs = durationMs, language = result.language),
                lines =
                    result.lines.map { line ->
                        LyricsEntry(
                            time = line.timeMs ?: 0,
                            text = line.text,
                            words =
                                line.words.takeIf { it.isNotEmpty() }?.map { word ->
                                    WordTimestamp(
                                        word.text,
                                        word.startMs / 1000.0,
                                        word.endMs / 1000.0,
                                    )
                                },
                        )
                    },
            )
        return LyricsWithProvider(Json.encodeToString(document), result.source)
    }

    override suspend fun search(query: String): List<YTItem> {
        ready.await()
        val owner = effectiveMusicFrontendOwner().takeIf { it.isNotEmpty() } ?: return emptyList()
        val result = runtime.pipelines.search(SearchRequest(query), allowedOwners = setOf(owner))
        if (effectiveMusicFrontendOwner() != owner) return emptyList()
        result.albums.forEach { retain(albums, it.id, it) }
        result.artists.forEach { retain(artists, it.id, it) }
        result.playlists.forEach { retain(playlists, it.id, it) }
        return result.tracks.map(::songFor) + result.albums.map(::albumItem) +
            result.artists.map { ArtistItem(it.id, it.name, it.artwork.takeIf(String::isNotBlank)) } +
            result.playlists.map { PlaylistItem(it.id, it.title, it.artwork.takeIf(String::isNotBlank), author = it.author) }
    }

    private fun <T> retain(
        values: ConcurrentHashMap<String, T>,
        id: String,
        value: T,
    ) {
        if (values.size >= 256 && !values.containsKey(id)) values.keys.firstOrNull()?.let(values::remove)
        values[id] = value
    }

    private fun albumItem(value: CatalogAlbum) =
        AlbumItem(
            value.id,
            value.title,
            value.artwork.takeIf(String::isNotBlank),
            listOf(ArtistTiny(null, value.artist)),
            playlistId = value.id,
            songCount = value.tracks.size,
        )

    private fun activeCatalogOwner(id: String): Boolean {
        val owner = id.substringAfter(':').substringBefore(':')
        if (owner != effectiveMusicFrontendOwner()) return false
        return runtime.plugins.value.any { it.manifest.id == owner && it.state == "Enabled" }
    }

    override suspend fun album(id: String): AlbumPage? {
        ready.await()
        if (!activeCatalogOwner(id)) return null
        val value = runtime.pipelines.album(id) ?: albums[id] ?: return null
        return AlbumPage(albumItem(value), value.tracks.map(::songFor))
    }

    override suspend fun artist(id: String): ArtistPageResult? {
        ready.await()
        if (!activeCatalogOwner(id)) return null
        val value = runtime.pipelines.artist(id) ?: artists[id] ?: return null
        return ArtistPageResult(
            mapOf(
                "header" to listOf(ArtistItem(id, value.name, value.artwork.takeIf(String::isNotBlank))),
                "songs" to value.tracks.map(::songFor),
                "albums" to value.albums.map(::albumItem),
            ),
        )
    }

    override suspend fun playlist(id: String): Map<String, List<YTItem>>? {
        ready.await()
        if (!activeCatalogOwner(id)) return null
        val value = runtime.pipelines.playlist(id) ?: playlists[id] ?: return null
        return mapOf(
            "header" to
                listOf(
                    PlaylistItem(
                        id,
                        value.title,
                        value.artwork.takeIf(String::isNotBlank),
                        author = value.author,
                        songCount = value.tracks.size.toString(),
                    ),
                ),
            "songs" to value.tracks.map(::songFor),
        )
    }

    override fun isLyricsProviderEnabled(id: String): Boolean = engine?.registry?.lyricsProviders()?.any { it.id == id } == true

    override val hasLyricsProviders: Boolean get() = engine?.registry?.lyricsProviders()?.isNotEmpty() == true

    override suspend fun metadata(id: String): SongItem? {
        ready.await()
        if (effectiveMusicFrontendOwner().isEmpty()) return null
        val song = songs[id] ?: return null
        val track = song.toPluginTrack()
        val base = MetadataMapping.fromTrack(track).copy(identity = identities[id] ?: trackIdentity(id))
        val resolved = runtime.pipelines.metadata(base)
        identities[id] = resolved.identity
        return MetadataMapping.toTrack(resolved, track).toSongItem(song).also(::remember)
    }
}

private fun SongItem.toPluginTrack() =
    Track(
        id,
        title,
        artists.joinToString(", ") { it.name },
        album?.name.orEmpty(),
        (duration ?: 0) * 1000,
        thumbnail.orEmpty(),
    )

private fun trackIdentity(
    id: String,
    identifiers: Map<String, String> = emptyMap(),
): TrackIdentity {
    val namespace = id.removePrefix("metrofuse:")
    val owner = namespace.substringBefore(':', missingDelimiterValue = "")
    val providerId = namespace.substringAfter(':', missingDelimiterValue = "")
    if (namespace == id || owner.isBlank() || providerId.isBlank()) return TrackIdentity(id, identifiers)
    return TrackIdentity(
        providerId,
        identifiers +
            mapOf(
                "pluginOwner" to owner,
                "providerTrackId" to providerId,
                "plugin" to owner,
                "host" to id,
            ),
    )
}

private fun Track.toSongItem(base: SongItem? = null): SongItem {
    val owner = trackIdentity(id).identifiers["pluginOwner"]
    val albumId =
        extras["albumId"]?.takeIf(String::isNotBlank)?.let { sourceAlbumId ->
            owner?.let { "metrofuse:$it:$sourceAlbumId" }
        }
    val mappedAlbum = album.takeIf(String::isNotBlank)?.let { AlbumTiny(albumId ?: base?.album?.id, it) }
    val mappedArtist = artist.takeIf(String::isNotBlank)?.let { listOf(ArtistTiny(null, it)) }.orEmpty()
    val mappedDuration = duration.takeIf { it > 0 }?.div(1000)
    val mappedArtwork = artwork.takeIf(String::isNotBlank)
    return base?.copy(
        title = title,
        artists =
            base.artists.takeIf { current -> current.joinToString(", ") { it.name } == artist }
                ?: mappedArtist.ifEmpty { base.artists },
        album = mappedAlbum ?: base.album,
        thumbnail = mappedArtwork ?: base.thumbnail,
        duration = mappedDuration ?: base.duration,
    ) ?: SongItem(id, title, mappedArtwork, mappedArtist, mappedAlbum, mappedDuration)
}

private class ControllerPlayer(
    private val mapTrack: (Track) -> SongItem,
) : Player {
    override val state = MutableStateFlow(PlaybackState(Track("", "", "", "")))
    private var controller: PlaybackController? = null
    private var commandScope: CoroutineScope? = null
    private var observer: Job? = null
    override val capabilities: Set<String> get() =
        setOf("toggle", "play", "pause", "next", "previous", "seek", "stop", "queue", "playTracks", "addToQueue") +
            if (controller?.supportsVolume == true) setOf("volume") else emptySet()

    fun attach(
        controller: PlaybackController,
        scope: CoroutineScope,
        remember: (SongItem) -> Unit,
    ) {
        if (this.controller === controller) return
        observer?.cancel()
        this.controller = controller
        commandScope = scope
        observer =
            scope.launch {
                controller.state.collect { value ->
                    value.queue.forEach(remember)
                    value.currentSong?.let(remember)
                    state.value =
                        PlaybackState(
                            value.currentSong?.toPluginTrack() ?: Track(
                                "",
                                "",
                                "",
                                "",
                            ),
                            value.isPlaying,
                            value.positionMs,
                            value.queue.map {
                                it.toPluginTrack()
                            },
                        )
                }
            }
    }

    fun detach() {
        observer?.cancel()
        controller = null
        commandScope = null
    }

    private fun command(action: (PlaybackController) -> Unit) {
        val target = checkNotNull(controller) { "Playback controller is not connected" }
        checkNotNull(commandScope).launch(Dispatchers.Main.immediate) { action(target) }
    }

    override fun toggle() = command { it.togglePlayPause() }

    override fun seek(position: Long) = command { it.seekTo(position.coerceAtLeast(0)) }

    override fun next() = command { it.skipToNext() }

    override fun play() = command { if (!it.state.value.isPlaying) it.togglePlayPause() }

    override fun pause() = command { if (it.state.value.isPlaying) it.togglePlayPause() }

    override fun previous() = command { it.skipToPrevious() }

    override fun stop() = command { it.stop() }

    override fun setVolume(volume: Float) {
        require(volume.isFinite()) { "Invalid volume" }
        command { it.setVolume(volume.coerceIn(0f, 1f)) }
    }

    override fun playTracks(
        tracks: List<Track>,
        startIndex: Int,
    ) {
        require(tracks.isNotEmpty() && startIndex in tracks.indices && tracks.size <= 10_000) { "Invalid playback queue" }
        val songs = tracks.map(mapTrack)
        command { it.playQueue(songs, startIndex) }
    }

    override fun addToQueue(tracks: List<Track>) {
        require(tracks.size <= 10_000) { "Playback queue limit exceeded" }
        command { it.addToQueue(tracks.map(mapTrack)) }
    }

    fun playTrack(song: SongItem) = command { it.play(song, listOf(song)) }
}

private class ApplicationNavigation : HostNavigation {
    override val destination = MutableStateFlow("home")
    private var navigator: Navigator? = null
    private var scope: CoroutineScope? = null

    fun bind(
        value: Navigator,
        scope: CoroutineScope,
    ): () -> Unit {
        navigator = value
        this.scope = scope
        return { if (navigator === value) navigator = null }
    }

    override fun navigate(destination: String) {
        val screen =
            when (destination) {
                "home" -> {
                    Screen.Home
                }

                "search" -> {
                    Screen.Search
                }

                "library" -> {
                    Screen.Library
                }

                "settings" -> {
                    Screen.Settings
                }

                "plugins" -> {
                    Screen.Plugins
                }

                else -> {
                    val point = destination.substringAfter(PLUGIN_SCREEN_PREFIX, "")
                    val owner = point.substringBefore(':')
                    val name = point.substringAfter(':', "")
                    val route = PluginScreenRoute(owner, name)
                    require(route.hasValidNames) { "Unsupported host destination" }
                    Screen.PluginDestination(route.owner, route.point)
                }
            }
        val target = checkNotNull(navigator) { "Navigation is not connected" }
        checkNotNull(scope).launch(Dispatchers.Main.immediate) {
            target.navigate(screen)
            this@ApplicationNavigation.destination.value =
                destination
        }
    }
}
