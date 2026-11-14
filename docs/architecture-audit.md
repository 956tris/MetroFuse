# MetroFuse KMP architecture audit

## Baseline and sources

The supplied MetroList snapshot matches upstream commit `e97197786c2a7ad56279cc9bb4ce3233ef6c99ff`. The migration retains GPL licensing, attribution and existing source. Main modules are `androidApp`, `desktopApp`, `shared`, `listenTogetherProto` and `opusDecoder`; `metroproto` and the vendored Opus source are pinned submodules. Existing `iosApp` and shared Apple source remain present and are outside this migration's build targets.

Nearby source discoveries include `Metrofuse KMP` (an earlier modular SDK/data prototype), `metrofuse-plugin-spike` (Android dynamic-loading experiments), and `New folder (4)` (the reusable `dev.metrofuse.api`/QuickJS runtime prototype). Nearby `MetroFuse-nightly (5)` and `(6)` directories contain APKs, not source. No JioSaavn, QQ Music, SoundCloud or Deezer streaming implementation was found in the earlier KMP source inspected. Their existence or migration is not claimed.

## Initialization and platform boundaries

Android initializes in `androidApp/src/main/kotlin/com/metrolist/android/App.kt`. `initializeApp` starts Koin with shared `coreModule`, `sharedModule` and Android `appModule`. Database, settings migrations, networking, notification channels and background maintenance remain upstream-owned. The plugin host is configured after Koin using app-private `filesDir/plugins` and the application coroutine scope. Its playback adapter delegates to `AndroidPlaybackController` and the existing singleton `PlayerConnection`.

Desktop initialization and staged first-frame startup live in `desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/Main.kt`. Koin initializes `coreModule`, `sharedModule` and `composeStateModule`; database/FFmpeg and expensive state are initialized on the existing background startup path. The plugin host uses `XdgUtils.getXdgDataHome()/metrofuse/plugins`, preserving upstream directory conventions. The existing `shutdownApp` closes the plugin runtime before canceling the application scope.

The new `plugin-api` uses common Kotlin contracts with Compose interfaces. `engine` has Android and desktop JVM targets and shared JVM implementation; it has no Apple targets. `shared` exposes an optional common `PluginServicesStore` bridge, implemented by JVM-family `MetroFuseHost`. Apple platforms without that bridge retain upstream behavior without importing the engine.

## Playback, resolution and queues

`shared/src/commonMain/kotlin/com/metrolist/shared/youtube/playback/StreamResolver.kt` is the upstream resolution interface. Its Koin registration in `youtube/di/SharedModule.kt` now wraps `DefaultStreamResolver` with `PluginStreamResolver`. This single registration feeds Android `MusicService`, desktop `PlayerState`, downloads and active-resource refresh. The wrapper checks cached/local audio first for ordinary audio playback, then registered plugin sources, then the original resolver. Remote refresh retains its original hints and excluded profiles when falling back upstream.

`DefaultStreamResolver` retains upstream extraction, account/token handling, client health, resource cache, coalesced requests, cancellation, local cache lookup, SABR and video counterpart logic. Those private authentication details are not exposed through the plugin API. `ProviderPipelines.audio` performs typed Kotlin calls, bounded per-provider timeouts, deterministic ordering, descriptor validation and fallback; no native calls are routed through JavaScript.

The host maps `StreamDescriptor` to existing `PlaybackResource`: URL, headers, MIME, codec, bitrate, sample rate, content length, duration and expiry. Direct, range HTTP and HLS plugin streams are supported at this adapter boundary. Video, local-source hints and explicit upstream client overrides bypass the audio-only plugin pipeline, including refresh and remote resolution. Plugin-defined CUSTOM or SABR payloads have no generic adapter; upstream SABR continues through the original resolver and player.

Android playback remains Media3 in `playback/ExoPlaybackPlayer.kt`. `buildStreamingMediaSource` selects progressive playback or `DefaultMediaSourceFactory` for supported manifests; `buildDirectDataSourceFactory` preserves request headers and range/cache handling. Desktop playback remains `shared/src/desktopMain/.../playback/MusicPlayer.kt` and FFmpeg `AudioPlayer`/`AudioDecoder`, whose URL/path decoding already accepts request headers.

Queues remain `shared/.../playback/MusicQueue.kt` with platform queue coordinators and transitions. The plugin `Player` adapter observes `PlaybackController.state` and forwards controls to that same controller. Native/JS play, pause, seek, next, previous, stop, volume, queue playback and enqueue actions use existing platform methods. There is no second playback engine.

`SongItem` is defined in `shared/.../model/Models.kt`; its duration is seconds. Plugin `Track` and lyrics request durations are milliseconds. Search results use `metrofuse:<plugin-owner>:<original-id>` host IDs to avoid catalog/cache collisions. `Track.extras`, including explicit fixture/provider identifiers, are kept in a bounded host identity map for subsequent audio queries.

## Catalog, metadata and lyrics

Shared search goes through `viewmodels/SearchViewModel.kt` and `api/YouTubeRepository.kt`. Plugin tracks, albums, artists and playlists are merged into summary and first-page filtered search, including when upstream/network access is unavailable. Plugin continuation pages are not yet supported; upstream pagination retains its original behavior. `CatalogProvider` supplies typed album/artist/playlist lookups. Namespaced catalog IDs route through the host into existing album, artist and playlist page models instead of being sent to YouTube. Nested track identities remain namespaced and preserve explicit provider/fixture identifiers.

`YouTubeRepository.getSongsMetadata` separates namespaced plugin IDs from upstream IDs. Plugin IDs use the host identity map and metadata pipeline rather than being submitted to YouTube. The host also exposes resolved metadata to plugin UI through `Runtime.metadata` and `metadataDocument`. Broad rewriting of upstream library metadata/database ownership is avoided.

`repository/LyricsRepository.kt` retains user edits, explicit cached selections, local lyrics, built-in source order, timing preference, translation, romanization and cache policy. It requests registered plugin lyrics after the existing user/local/explicit cache checks and before automatic built-in fetches. Typed line/word timestamps become the existing MLX document format. Automatic plugin results use the existing conditional cache write to preserve concurrent manual selections and offsets. Cached automatic plugin lyrics are eligible only while their provider remains registered; explicit user selections remain preserved. Enabling plugin lyrics bypasses previous negative built-in cache entries.

## Existing MetroFuse-style features retained

The actual upstream source already includes Spotify Canvas/animated artwork in `PlayerViewModel`, word-by-word lyrics/editor/retiming, multiple lyrics sources, playlist imports, listening history, settings/themes, caching, downloads and Discord integration. These components stay intact. Provider abstraction does not imply that any absent external streaming provider has been ported. The earlier KMP `core/data/resolver` supplied useful provider-order/cache/reference ideas, but its broad `runCatching` swallowed cancellation; it was not copied into the playback hot path.

## Settings, networking, UI and events

Existing application settings remain `Settings.shared` with platform-backed persistence and shared preference keys. Plugin settings/storage are isolated in the engine. Public host settings expose only `audio.quality`; internal account cookies/session stores are excluded. Host logging uses the existing shared `Logger`, while plugin diagnostics remain bounded engine flows.

Compose navigation uses shared `Navigator`/`Screen`; Android `ui/MetrolistApp.kt` and desktop `Main.kt` bind the actual navigator. The public host adapter supports home, search, library and settings routes. Plugin UI is registered by owner and removed during deactivation. The manager renders native Compose/declarative contributions without replacing the application renderer.

Playback state comes from existing controller flows. Engine lifecycle/event subscriptions are scoped to plugin sessions; provider snapshots are rechecked after suspended calls to reject late results from disabled plugins. Upstream `PlaybackEventLog` remains the player diagnostics mechanism. `YouTubePlaybackReporter.kt` explicitly ignores plugin resources without tracking metadata/fallback, preventing provider playback from generating invented YouTube reports.

## Builds and validation boundaries

The project uses its existing Gradle wrapper, version catalog, Android APK generation and desktop distribution infrastructure. Root build settings control exclusion of Apple targets during Windows/Android checkpoints. No toolchain success is implied by this audit.

`ProviderPipelinesTest` adds deterministic checks for source ordering/failure fallback, headers, cancellation, removal during resolution, typed lyrics/search/catalog registration and cleanup. `PluginStreamResolverTest` checks resolver delegation/cache precedence, audio-only boundaries, cancellation, offline filtered search and plugin catalog page routing through `YouTubeRepository` with HTTP access forbidden. Existing upstream fixtures under `shared/src/desktopTest/resources/ffmpeg` include AAC, Opus and HLS media, documented in `docs/test-fixtures.md`. New native fixture tooling generates synthetic WAV audio. Actual executed test/build results and remaining platform limitations belong in `metrofuse-kmp-implementation.md`.
