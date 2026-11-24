package com.metrolist.shared.plugins

import androidx.compose.runtime.snapshotFlow
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.toMlxJson
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistExternalLink
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxMeta
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PluginHomeLayout
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.UpcomingQueueController
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.viewmodels.LibraryViewModel
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
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.LyricsLine
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.LyricsWord
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SongVoteRequest
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.TrackVote
import dev.metrofuse.api.UiLibraryHost
import dev.metrofuse.api.UiLocalSearch
import dev.metrofuse.api.UiPlaybackControls
import dev.metrofuse.api.UpcomingQueuePlayer
import dev.metrofuse.runtime.CredentialStore
import dev.metrofuse.runtime.MetadataMapping
import dev.metrofuse.runtime.PluginFailure
import dev.metrofuse.runtime.Runtime
import dev.metrofuse.runtime.UiFrontendSelectionHost
import dev.metrofuse.runtime.UiLyricsHost
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

/** One engine per process, using the application's existing controller and resolver. */
object MetroFuseHost : PluginServices {
    override suspend fun canvas(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): String? {
        val current = engine ?: return null
        val selected = current.registry.service("artwork.canvas/v1") ?: return null
        if (current.plugins.value.none { it.manifest.id == selected.owner && it.state == "Enabled" }) return null
        val account =
            current.logins.states.value[selected.owner]
                ?.account ?: return null
        if (!account.loggedIn) return null
        val track = Track("", title, artist, album.orEmpty(), durationMs ?: 0)
        return try {
            val response =
                withTimeoutOrNull(15_000) {
                    selected.value.call(buildJsonObject { put("track", Json.encodeToJsonElement(track)) })
                } ?: return null
            if (current.logins.states.value[selected.owner]
                    ?.account != account ||
                current.registry.service("artwork.canvas/v1")?.owner != selected.owner
            ) {
                return null
            }
            (response["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 8192 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            current.registry.reportFailure(selected.owner, "artwork.canvas", error)
            null
        }
    }

    private var engine: Runtime? = null
    private var hostScope: CoroutineScope? = null
    private val ready = CompletableDeferred<Unit>()
    private val playbackAdapter = ControllerPlayer(::songFor)
    private var matchingController: PlaybackController? = null
    private var controllerObserverJob: Job? = null
    override val audioMatchingController: PlaybackController? get() = matchingController
    private val navigation = ApplicationNavigation()
    private val songs = ConcurrentHashMap<String, SongItem>()
    private val songOrder = LinkedHashMap<String, Unit>(512, 0.75f, true)
    private val identities = ConcurrentHashMap<String, TrackIdentity>()

    internal fun savedIdentifiers(id: String): Map<String, String> = identities[id]?.identifiers.orEmpty()
    private val albums = ConcurrentHashMap<String, CatalogAlbum>()
    private val albumOrder = LinkedHashMap<String, Unit>(256, 0.75f, true)
    private val artists = ConcurrentHashMap<String, CatalogArtist>()
    private val artistOrder = LinkedHashMap<String, Unit>(256, 0.75f, true)
    private val playlists = ConcurrentHashMap<String, CatalogPlaylist>()
    private val playlistOrder = LinkedHashMap<String, Unit>(256, 0.75f, true)
    private val supportedAudioTransports =
        setOf(StreamTransport.DIRECT, StreamTransport.RANGE_HTTP, StreamTransport.HLS, StreamTransport.SABR_UMP)
    private var platform = "desktop"
    private var libraryDatabase: MusicDatabase? = null
    private var sharedLibraryViewModel: LibraryViewModel? = null

    fun attachLibrary(database: MusicDatabase) {
        libraryDatabase = database
    }

    @Synchronized
    fun libraryViewModel(
        scope: CoroutineScope,
        database: MusicDatabase,
        downloadedSongIds: Flow<Set<String>>? = null,
    ): LibraryViewModel =
        sharedLibraryViewModel ?: LibraryViewModel(
            hostScope ?: scope,
            database,
            downloadedSongIds,
            useUpstreamLibrary = false,
        ).also { sharedLibraryViewModel = it }

    internal fun library(
        offset: Int,
        limit: Int,
    ): Flow<List<Track>> =
        libraryDatabase?.let { database ->
            val model = libraryViewModel(checkNotNull(hostScope), database)
            model.setActive(true)
            model.refresh(force = false)
            model.uiState
                .map { state ->
                    state.items
                        .filterIsInstance<SongItem>()
                        .drop(offset.coerceAtLeast(0))
                        .take(limit.coerceIn(1, 200))
                        .map { it.also(::remember).toPluginTrack() }
                }.flowOn(Dispatchers.Default)
        } ?: flowOf(emptyList())

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
        onPluginFailure: (PluginFailure) -> Unit = {},
    ) {
        if (engine != null) return
        this.platform = platform
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
        hostScope = scope
        val services =
            object : HostServices, UiLibraryHost, UiLocalSearch, UiFrontendSelectionHost, UiLyricsHost {
                override suspend fun resolveUiLyrics(track: Track): LyricsResult? =
                    withContext(Dispatchers.Default) {
                        val repository =
                            org.koin.core.context.GlobalContext
                                .getOrNull()
                                ?.getOrNull<LyricsRepository>()
                                ?: return@withContext null
                        val providers =
                            LyricsProvider
                                .preferredProvidersForFetch(
                                    LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                                    LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
                                ).sortedBy { it != LyricsProvider.METROFUSE && it != LyricsProvider.BETTERLYRICS }
                        val lyrics =
                            repository.getLyrics(
                                videoId = track.id,
                                title = track.title,
                                artist = track.artist,
                                duration = track.duration.takeIf { it > 0 },
                                album = track.album.takeIf { it.isNotBlank() },
                                preferredProviders = providers,
                                ignoreVideoIdentity = track.id.startsWith("metrofuse:"),
                                preferWordSynced = true,
                                includePluginLyrics = false,
                            ) ?: return@withContext null
                        val entries = LyricsUtils.parseLyrics(lyrics.lyrics)
                        val timed = LyricsUtils.hasPlaybackTiming(entries)
                        val offset =
                            LyricsUtils.combinedLyricsOffsetMs(
                                repository.getLyricsOffset(track.id),
                                Settings.shared.globalLyricsOffsetMs,
                            )
                        val lines =
                            entries.map { entry ->
                                val words =
                                    entry.words.orEmpty().filter { !it.isLineTimed && !it.isBackground && it.endTime > it.startTime }.map {
                                        LyricsWord(
                                            it.text,
                                            ((it.startTime * 1000).toLong() - offset).coerceAtLeast(0),
                                            (
                                                (it.endTime * 1000).toLong() -
                                                    offset
                                            ).coerceAtLeast(0),
                                        )
                                    }
                                LyricsLine(entry.text, (entry.time - offset).coerceAtLeast(0).takeIf { timed }, words)
                            }
                        LyricsResult(lines, lyrics.provider).takeIf { lines.any { it.text.isNotBlank() } }
                    }

                override suspend fun selectUiFrontend(owner: String) =
                    withContext(Dispatchers.Main.immediate) { selectMusicFrontend(owner) }

                override suspend fun searchLibrary(
                    query: String,
                    limit: Int,
                ): List<Track> =
                    libraryDatabase?.dao()?.searchSongs(query, limit)?.first().orEmpty().map { entry ->
                        entry.toSongItem().also(::remember).toPluginTrack()
                    }

                override fun library(
                    offset: Int,
                    limit: Int,
                ) = MetroFuseHost.library(offset, limit)

                override val playback = playbackAdapter
                override val navigation = MetroFuseHost.navigation
                override val catalog =
                    object : TrackCatalog {
                        override val tracks: List<Track> get() = playbackAdapter.state.value.queue

                        override suspend fun identify(identity: TrackIdentity): Track? = matchingTrack(identity)
                    }
                override val settings =
                    object : HostSettings {
                        override fun get(key: String): String? =
                            when (key) {
                                "audio.quality" -> Settings.shared.audioQuality
                                "music.frontend" -> effectiveMusicFrontendOwner()
                                "content.language" -> Settings.shared.contentLanguage
                                "content.country" -> Settings.shared.contentCountry
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
        engine =
            Runtime(
                directory,
                platform,
                scope,
                services,
                hostVersion = BuildConfig.APP_VERSION,
                credentialStore = credentialStore,
                onPluginFailure = onPluginFailure,
            ).also { it.registerHostAdminServices(platform) }
        PluginServicesStore.current = this
        SongLikeState.initialize()
        scope.launch {
            runtime.libraryAccounts.collect { accounts ->
                PluginServicesStore.libraryAccounts.value =
                    accounts.mapValues { (_, state) ->
                        PluginLibraryAccount(state.revision, state.account.loggedIn, state.busy)
                    }
            }
        }
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
                            continuation = page.continuation,
                            loadingMore = page.loadingMore,
                            loadMoreError = page.loadMoreError,
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
        controllerObserverJob?.cancel()
        matchingController = controller
        playbackAdapter.attach(controller, checkNotNull(hostScope), ::remember)
        controllerObserverJob =
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

    override fun loadMoreHome(owner: String) {
        if (owner == effectiveMusicFrontendOwner()) engine?.homes?.loadMore(owner)
    }

    private fun songFor(track: Track): SongItem {
        require(track.id.isNotBlank() && track.title.isNotBlank() && track.extras.size <= 64) { "Invalid plugin track" }
        identities[track.id] = trackIdentity(track.id, track.extras)
        return track.toSongItem(songs[track.id]).also(::remember)
    }

    fun bindNavigator(navigator: Navigator): () -> Unit = navigation.bind(navigator, checkNotNull(hostScope))

    suspend fun close() =
        withContext(NonCancellable) {
            PluginServicesStore.current = null
            controllerObserverJob?.cancel()
            controllerObserverJob = null
            engine?.close()
            engine = null
            hostScope?.cancel()
            hostScope = null
            playbackAdapter.detach()
            matchingController = null
            sharedLibraryViewModel = null
        }

    private fun remember(song: SongItem) {
        retainLru(
            values = songs,
            order = songOrder,
            limit = 512,
            id = song.id,
            value = song,
            protectedId = { matchingController?.state?.value?.currentSong?.id },
            onEvict = identities::remove,
        )
    }

    private fun <T> retainLru(
        values: ConcurrentHashMap<String, T>,
        order: LinkedHashMap<String, Unit>,
        limit: Int,
        id: String,
        value: T,
        protectedId: () -> String? = { null },
        onEvict: (String) -> Unit = {},
    ) {
        synchronized(order) {
            values[id] = value
            order.remove(id)
            order[id] = Unit
            while (order.size > limit) {
                val active = protectedId()
                val candidate = order.keys.firstOrNull { it != id && it != active } ?: break
                order.remove(candidate)
                values.remove(candidate)
                onEvict(candidate)
            }
        }
    }

    private fun matchingTrack(identity: TrackIdentity): Track? {
        val id = identity.identifiers["host"] ?: identity.canonicalId
        val track = songs[id]?.toPluginTrack() ?: return null
        val isrc = identity.isrc ?: identities[id]?.isrc ?: identities[id]?.identifiers?.get("isrc")
        return if (isrc == null) track else track.copy(extras = track.extras + ("isrc" to isrc))
    }

    override val canMatchAudio: Boolean
        get() =
            engine?.let { current ->
                val owners =
                    current.registry
                        .searchProviders()
                        .map { it.owner }
                        .toSet()
                current.registry.audioSources().any { entry ->
                    entry.owner in owners && entry.owner != "builtin" &&
                        entry.provider.capabilities.requirements.none {
                            it.startsWith("platform:") && it != "platform:$platform"
                        }
                }
            } == true

    override fun audioMatch(id: String): AudioMatchCandidate? = AudioMatchOverrides.decode(Settings.shared.audioMatchOverridesJson)[id]

    override suspend fun audioMatches(
        song: SongItem,
        query: String?,
    ): List<AudioMatchCandidate> {
        ready.await()
        if (effectiveMusicFrontendOwner().isEmpty()) return emptyList()
        require(query == null || query.length <= 8192) { "Search query exceeds limits" }
        remember(song)
        val identity = identities[song.id] ?: trackIdentity(song.id)
        val track = matchingTrack(identity) ?: song.toPluginTrack()
        return runtime.pipelines.audioMatches(track, identity, platform, query).mapNotNull { match ->
            val entry = runtime.registry.audioSources().firstOrNull { it.id == match.provider } ?: return@mapNotNull null
            AudioMatchCandidate(
                provider = entry.id,
                owner = entry.owner,
                trackId = match.track.id,
                title = match.track.title,
                artist = match.track.artist,
                providerName =
                    runtime.plugins.value
                        .firstOrNull { it.manifest.id == entry.owner }
                        ?.manifest
                        ?.name ?: entry.id,
                album = match.track.album,
                durationMs = match.track.duration,
                artwork = match.track.artwork,
                isrc = match.track.extras["isrc"],
            )
        }
    }

    override suspend fun setAudioMatch(
        id: String,
        candidate: AudioMatchCandidate?,
    ): Boolean {
        ready.await()
        if (id.isBlank()) return false
        if (candidate != null) {
            if (!AudioMatchOverrides.valid(candidate) || effectiveMusicFrontendOwner().isEmpty()) return false
            val source =
                runtime.registry.audioSources().firstOrNull { it.id == candidate.provider && it.owner == candidate.owner } ?: return false
            val identity = rememberAudioMatch(candidate)
            val result = runtime.pipelines.audioExact(AudioQuery(identity, platform, audioQualityPreference(), source.id))
            val stream = result.stream ?: return false
            if (stream.transport !in supportedAudioTransports) return false
            pluginSabrBootstrap(stream, stream.attributes.getValue("resolvedMediaId"))
        }
        withContext(Dispatchers.Main.immediate) {
            val matches = AudioMatchOverrides.decode(Settings.shared.audioMatchOverridesJson).toMutableMap()
            if (candidate == null) matches.remove(id) else matches[id] = candidate
            Settings.shared.audioMatchOverridesJson = AudioMatchOverrides.encode(matches)
        }
        return true
    }

    private fun rememberAudioMatch(candidate: AudioMatchCandidate): TrackIdentity {
        val rawId = candidate.trackId.removePrefix("metrofuse:${candidate.owner}:")
        val id = "metrofuse:${candidate.owner}:$rawId"
        songFor(
            Track(
                id,
                candidate.title,
                candidate.artist,
                candidate.album,
                candidate.durationMs,
                candidate.artwork,
                extras = candidate.isrc?.let { mapOf("isrc" to it) }.orEmpty(),
            ),
        )
        return checkNotNull(identities[id])
    }

    private fun audioQualityPreference(): QualityPreference =
        QualityPreference(
            preset = runCatching { QualityPreset.valueOf(Settings.shared.audioQuality) }.getOrDefault(QualityPreset.AUTO),
        )

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
            audioMatch(id)?.let { match ->
                if (match.provider in excludedProviders ||
                    runtime.registry.audioSources().none { it.id == match.provider && it.owner == match.owner }
                ) {
                    return null
                }
                runtime.pipelines.audioExact(AudioQuery(rememberAudioMatch(match), platform, preference, match.provider), excludedProviders)
            } ?: runtime.pipelines.audio(
                AudioQuery(identity, platform, preference),
                includeBuiltin = false,
                excludedProviders = excludedProviders,
            )
        val descriptor = result.stream ?: return null
        if (effectiveMusicFrontendOwner().isEmpty()) return null
        val provider = descriptor.attributes["providerId"] ?: error("Audio provider identity missing")
        require(
            descriptor.transport in supportedAudioTransports,
        ) {
            "Unsupported plugin stream transport"
        }
        val sabr =
            try {
                pluginSabrBootstrap(descriptor, descriptor.attributes.getValue("resolvedMediaId"))
            } catch (error: IllegalArgumentException) {
                runtime.log("host", "Rejected audio descriptor from $provider: ${error::class.simpleName}")
                return null
            } catch (error: IllegalStateException) {
                runtime.log("host", "Rejected audio descriptor from $provider: ${error::class.simpleName}")
                return null
            }
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
                when (descriptor.transport) {
                    StreamTransport.HLS -> "application/vnd.apple.mpegurl"
                    else -> sabr?.mimeType ?: descriptor.mimeType
                },
            codecs = descriptor.codec,
            bitrate = descriptor.bitrate,
            sampleRate = descriptor.sampleRate,
            durationSeconds = descriptor.durationSeconds ?: sabr?.durationMs?.div(1000) ?: song?.duration,
            clientName = provider,
            profileId = descriptor.attributes["matchProfile"] ?: provider,
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
        return result.toHostLyrics(title, artist, durationMs)
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

    override suspend fun startRadioQueue(id: String): RadioQueuePage? {
        ready.await()
        val owner = effectiveMusicFrontendOwner().takeIf(String::isNotEmpty) ?: return null
        val result = runtime.pipelines.recommendations(identities[id] ?: trackIdentity(id), setOf(owner)) ?: return null
        if (effectiveMusicFrontendOwner() != owner) return null
        return RadioQueuePage(result.tracks.map(::songFor), emptyList(), result.continuation)
    }

    override suspend fun continueRadioQueue(continuation: String): RadioQueuePage? {
        ready.await()
        val owner = effectiveMusicFrontendOwner().takeIf(String::isNotEmpty) ?: return null
        val result = runtime.pipelines.continueRecommendations(continuation, setOf(owner)) ?: return null
        if (effectiveMusicFrontendOwner() != owner) return null
        return RadioQueuePage(result.tracks.map(::songFor), emptyList(), result.continuation)
    }

    override suspend fun library(continuation: String?): PluginLibraryPage? = loadLibraryPage(continuation, likedSongsOnly = false)

    override suspend fun likedSongs(continuation: String?): PluginLibraryPage? = loadLibraryPage(continuation, likedSongsOnly = true)

    override val canSyncSongVotes: Boolean
        get() {
            val owner = effectiveMusicFrontendOwner()
            val account = PluginServicesStore.libraryAccounts.value[owner]
            return owner.isNotEmpty() && account?.loggedIn == true && !account.busy &&
                engine?.pipelines?.supportsSongVotes(owner) == true
        }

    override suspend fun setSongVote(
        id: String,
        vote: SongVote,
    ): Boolean {
        ready.await()
        val owner = effectiveMusicFrontendOwner()
        if (!canSyncSongVotes) return false
        val prefix = "metrofuse:$owner:"
        if (id.startsWith("metrofuse:") && !id.startsWith(prefix)) return false
        val accountKey = pluginLibraryAccountKey(owner)
        val result = runtime.pipelines.setSongVote(owner, SongVoteRequest(id.removePrefix(prefix), TrackVote.valueOf(vote.name)))
        if (owner != effectiveMusicFrontendOwner() || accountKey != pluginLibraryAccountKey(owner)) {
            throw CancellationException("Song vote account changed")
        }
        return result
    }

    private suspend fun loadLibraryPage(
        continuation: String?,
        likedSongsOnly: Boolean,
    ): PluginLibraryPage? {
        ready.await()
        val owner = effectiveMusicFrontendOwner().takeIf(String::isNotEmpty) ?: return null
        val accountKey = pluginLibraryAccountKey(owner)
        SongLikeState.activateCurrentAccount()
        val voteRevision = SongLikeState.voteObservationRevision()
        val locale = Locale.getDefault()
        val language = Settings.shared.contentLanguage.takeUnless { it == "SYSTEM" } ?: locale.language
        val country = Settings.shared.contentCountry.takeUnless { it == "SYSTEM" } ?: locale.country
        val result = runtime.pipelines.library(owner, LibraryRequest(language, country, continuation, likedSongsOnly)) ?: return null
        if (effectiveMusicFrontendOwner() != owner || pluginLibraryAccountKey(owner) != accountKey) {
            throw CancellationException("Library source changed")
        }
        val items =
            result.items.mapNotNull { item ->
                item.track?.let(::songFor)
                    ?: item.album?.let {
                        retainLru(albums, albumOrder, 256, it.id, it)
                        albumItem(it)
                    }
                    ?: item.artist?.let {
                        retainLru(artists, artistOrder, 256, it.id, it)
                        ArtistItem(it.id, it.name, it.artwork.takeIf(String::isNotBlank))
                    }
                    ?: item.playlist?.let {
                        retainLru(playlists, playlistOrder, 256, it.id, it)
                        PlaylistItem(it.id, it.title, it.artwork.takeIf(String::isNotBlank), author = it.author)
                    }
            }
        val votes =
            result.items
                .mapNotNull { item ->
                    val track = item.track ?: return@mapNotNull null
                    val vote = SongVote.entries.firstOrNull { it.name == track.extras["likeStatus"] } ?: return@mapNotNull null
                    track.id to vote
                }.toMap()
        SongLikeState.reconcilePluginVotes(votes, items.filterIsInstance<SongItem>().associateBy(SongItem::id), voteRevision)
        return PluginLibraryPage(items, result.continuation, result.complete)
    }

    private fun <T> retain(
        values: ConcurrentHashMap<String, T>,
        id: String,
        value: T,
    ) {
        val order =
            when {
                values === albums -> albumOrder
                values === artists -> artistOrder
                values === playlists -> playlistOrder
                else -> null
            }
        if (order != null) {
            retainLru(values, order, 256, id, value)
        } else {
            if (values.size >= 256 && !values.containsKey(id)) values.keys.firstOrNull()?.let(values::remove)
            values[id] = value
        }
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
        val owner = id.substringAfter(':').substringBefore(':')
        val extra = artistMetadata(owner, id.removePrefix("metrofuse:$owner:"))
        val description = (extra["biography"] as? JsonPrimitive)?.contentOrNull?.take(100_000)
        val listeners = (extra["monthlyListeners"] as? JsonPrimitive)?.contentOrNull?.take(256)
        return ArtistPageResult(
            buildMap {
                put(
                    "header",
                    listOf(
                        ArtistItem(
                            id,
                            value.name,
                            value.artwork.takeIf(String::isNotBlank),
                            description = description,
                            monthlyListeners = listeners,
                            externalLinks = extra.artistLinks(),
                        ),
                    ),
                )
                put("songs", value.tracks.map(::songFor))
                put("albums", value.albums.map(::albumItem))
                extra.albumList("featuring").takeIf { it.isNotEmpty() }?.let { albums ->
                    put("spotify_featuring", albums.map { albumItem(it.owned(owner)) })
                }
                extra.artistList("relatedArtists").takeIf { it.isNotEmpty() }?.let { artists ->
                    put(
                        "spotify_related_artists",
                        artists.map { artist ->
                            val ownedId = "metrofuse:$owner:${artist.id}"
                            retain(this@MetroFuseHost.artists, ownedId, artist.copy(id = ownedId))
                            ArtistItem(ownedId, artist.name, artist.artwork.takeIf(String::isNotBlank))
                        },
                    )
                }
                extra.playlistList("playlists").takeIf { it.isNotEmpty() }?.let { playlists ->
                    put(
                        "spotify_artist_playlists",
                        playlists.map { playlist ->
                            val ownedId = "metrofuse:$owner:${playlist.id}"
                            retain(this@MetroFuseHost.playlists, ownedId, playlist.copy(id = ownedId))
                            PlaylistItem(ownedId, playlist.title, playlist.artwork.takeIf(String::isNotBlank), author = playlist.author)
                        },
                    )
                }
            },
        )
    }

    private suspend fun artistMetadata(
        owner: String,
        id: String,
    ): JsonObject {
        val source =
            runtime.registry.serviceCandidates("catalog.artist-metadata/v1").firstOrNull { it.owner == owner }
                ?: return JsonObject(emptyMap())
        val account =
            runtime.logins.states.value[owner]
                ?.account
        return try {
            val result =
                withTimeoutOrNull(15_000) { source.value.call(buildJsonObject { put("id", id) }) }
                    ?: return JsonObject(emptyMap())
            if (runtime.logins.states.value[owner]
                    ?.account != account ||
                !activeCatalogOwner("metrofuse:$owner:$id")
            ) {
                return JsonObject(emptyMap())
            }
            (result["metadata"] as? JsonObject) ?: result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runtime.registry.reportFailure(owner, "catalog.artistMetadata", error)
            JsonObject(emptyMap())
        }
    }

    private fun JsonObject.albumList(key: String): List<CatalogAlbum> =
        (this[key] as? JsonArray)
            .orEmpty()
            .take(
                100,
            ).mapNotNull { runCatching { Json.decodeFromJsonElement<CatalogAlbum>(it) }.getOrNull() }

    private fun JsonObject.artistLinks(): List<ArtistExternalLink> =
        listOf("concerts" to "concert", "merch" to "merch").flatMap { (key, kind) ->
            (this[key] as? JsonArray).orEmpty().take(30).mapNotNull { element ->
                val value = element as? JsonObject ?: return@mapNotNull null

                fun text(name: String) = (value[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                val title = (text("title") ?: text("name"))?.take(512) ?: return@mapNotNull null
                val rawUrl = text("url") ?: text("uri") ?: return@mapNotNull null
                val url =
                    if (rawUrl.startsWith(
                            "spotify:concert:",
                        )
                    ) {
                        "https://open.spotify.com/concert/${rawUrl.substringAfterLast(':')}"
                    } else {
                        rawUrl
                    }
                val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
                if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank() || url.length > 8192) return@mapNotNull null
                val details =
                    listOfNotNull(
                        text("city"),
                        text("venue"),
                        text("startDateIso"),
                        text("price"),
                    ).joinToString(" · ").takeIf(String::isNotBlank)
                ArtistExternalLink(title, url, kind, details)
            }
        }

    private fun JsonObject.artistList(key: String): List<CatalogArtist> =
        (this[key] as? JsonArray)
            .orEmpty()
            .take(
                100,
            ).mapNotNull { runCatching { Json.decodeFromJsonElement<CatalogArtist>(it) }.getOrNull() }

    private fun JsonObject.playlistList(key: String): List<CatalogPlaylist> =
        (this[key] as? JsonArray)
            .orEmpty()
            .take(
                100,
            ).mapNotNull { runCatching { Json.decodeFromJsonElement<CatalogPlaylist>(it) }.getOrNull() }

    private fun CatalogAlbum.owned(owner: String): CatalogAlbum =
        copy(
            id = "metrofuse:$owner:$id",
            tracks =
                tracks.take(1000).map { track ->
                    track.copy(id = "metrofuse:$owner:${track.id}")
                },
        ).also { retain(albums, it.id, it) }

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

internal fun LyricsResult.toHostLyrics(
    title: String,
    artist: String,
    durationMs: Long?,
): LyricsWithProvider {
    val document =
        MlxDocument(
            meta = MlxMeta(lyricSource = source, provider = source),
            song = MlxSong(title = title, artist = artist, durationMs = durationMs, language = language),
            lines =
                lines.map { line ->
                    LyricsEntry(
                        time = line.timeMs ?: 0,
                        text = line.text,
                        words =
                            line.words
                                .map { word ->
                                    WordTimestamp(word.text, word.startMs / 1000.0, word.endMs / 1000.0)
                                }.takeIf { it.isNotEmpty() },
                    )
                },
        )
    return LyricsWithProvider(document.toMlxJson().toString(), source)
}

private fun SongItem.toPluginTrack(): Track {
    val saved = MetroFuseHost.savedIdentifiers(id)
    val mergedExtras = buildMap {
        saved.forEach { (k, v) -> if (k != "host" && v.isNotBlank()) put(k, v) }
        album?.id?.takeIf { it.isNotBlank() }?.let { rawAlbumId ->
            putIfAbsent("albumId", rawAlbumId.substringAfterLast(':', rawAlbumId))
        }
        artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { put("primaryArtist", it) }
    }
    return Track(
        id,
        title,
        artists.joinToString(", ") { it.name },
        album?.name.orEmpty(),
        (duration ?: 0) * 1000,
        thumbnail.orEmpty(),
        extras = mergedExtras,
    )
}

private fun trackIdentity(
    id: String,
    identifiers: Map<String, String> = emptyMap(),
): TrackIdentity {
    val namespace = id.removePrefix("metrofuse:")
    val owner = namespace.substringBefore(':', missingDelimiterValue = "")
    val providerId = namespace.substringAfter(':', missingDelimiterValue = "")
    if (namespace == id || owner.isBlank() || providerId.isBlank()) return TrackIdentity(id, identifiers, identifiers["isrc"])
    return TrackIdentity(
        providerId,
        identifiers +
            mapOf(
                "pluginOwner" to owner,
                "providerTrackId" to providerId,
                "plugin" to owner,
                "host" to id,
            ),
        isrc = identifiers["isrc"],
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
    val mappedVideoType = extras["musicVideoType"]?.takeIf(String::isNotBlank)
    val mappedExplicitness = extras["explicit"]?.toBooleanStrictOrNull()
    return base?.copy(
        title = title,
        artists =
            base.artists.takeIf { current -> current.joinToString(", ") { it.name } == artist }
                ?: mappedArtist.ifEmpty { base.artists },
        album = mappedAlbum ?: base.album,
        thumbnail = mappedArtwork ?: base.thumbnail,
        duration = mappedDuration ?: base.duration,
        musicVideoType = mappedVideoType ?: base.musicVideoType,
        isExplicit = mappedExplicitness ?: base.isExplicit,
        explicitness = mappedExplicitness ?: base.explicitness,
    ) ?: SongItem(
        id,
        title,
        mappedArtwork,
        mappedArtist,
        mappedAlbum,
        mappedDuration,
        musicVideoType = mappedVideoType,
        isExplicit = mappedExplicitness == true,
        explicitness = mappedExplicitness,
    )
}

private class ControllerPlayer(
    private val mapTrack: (Track) -> SongItem,
) : UpcomingQueuePlayer,
    UiPlaybackControls {
    override val state = MutableStateFlow(PlaybackState(Track("", "", "", "")))
    private var controller: PlaybackController? = null
    private var commandScope: CoroutineScope? = null
    private var observer: Job? = null
    override val capabilities: Set<String> get() =
        setOf(
            "toggle",
            "play",
            "pause",
            "next",
            "previous",
            "seek",
            "stop",
            "queue",
            "playTracks",
            "addToQueue",
            "shuffle",
            "repeat",
            "queueIndex",
            "radio",
        ) +
            (if (controller?.supportsVolume == true) setOf("volume") else emptySet()) +
            (if (controller is UpcomingQueueController) setOf("replaceUpcomingQueue") else emptySet())

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
                var lastQueue: List<SongItem>? = null
                var pluginQueue = emptyList<Track>()
                var lastSong: SongItem? = null
                var pluginSong = Track("", "", "", "")
                controller.state.collect { value ->
                    if (lastQueue !== value.queue) {
                        val queue = value.queue
                        if (lastQueue != queue) {
                            queue.forEach(remember)
                            pluginQueue = queue.map(SongItem::toPluginTrack)
                        }
                        lastQueue = queue
                    }
                    if (lastSong != value.currentSong) {
                        lastSong = value.currentSong
                        pluginSong = value.currentSong?.also(remember)?.toPluginTrack() ?: Track("", "", "", "")
                    }
                    val measuredDuration = value.durationMs.takeIf { pluginSong.id.isNotBlank() && it > 0 }
                    val currentTrack =
                        if (measuredDuration != null && measuredDuration != pluginSong.duration) {
                            pluginSong.copy(duration = measuredDuration)
                        } else {
                            pluginSong
                        }
                    state.value =
                        PlaybackState(
                            currentTrack,
                            value.isPlaying,
                            value.positionMs,
                            pluginQueue,
                        ).apply {
                            currentIndex = value.currentQueueIndex.takeIf { it >= 0 }
                            playbackInstanceId = (controller as? UpcomingQueueController)?.playbackInstanceId()
                        }
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

    override fun startTrackRadio() =
        command { controller ->
            controller.state.value.currentSong
                ?.let(controller::startRadio)
        }

    override fun toggleShuffle() = command { it.toggleShuffle() }

    override fun cycleRepeat() = command { it.toggleRepeat() }

    override fun selectQueueIndex(index: Int) =
        command {
            if (index in it.state.value.queue.indices) it.playQueueIndex(index)
        }

    override fun toggle() = command { it.togglePlayPause() }

    override fun replaceUpcomingQueue(tracks: List<Track>) {
        require(tracks.size <= 1000) { "Upcoming queue exceeds the host limit" }
        command { target ->
            val queue = target as? UpcomingQueueController ?: error("Upcoming queue reconciliation is unavailable")
            queue.replaceUpcomingQueue(tracks.map(mapTrack))
        }
    }

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
