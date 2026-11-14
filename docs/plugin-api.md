# MetroFuse plugin API 1.1.0

The authoritative shared contracts are in `plugin-api/src/commonMain/kotlin/dev/metrofuse/api`. The module has group `dev.metrofuse` and version `1.1.0`. This is an experimental API; no published Maven SDK or compatibility with older prototype binaries is implied.

Two executable ecosystems register into the same owner-scoped registry: universal JavaScript `.mfp` archives, and native desktop `.jar`/Android `.apk` artifacts. Native plugins implement Kotlin interfaces directly. JavaScript uses the same serializable models at its QuickJS boundary. Native provider calls do not pass through JavaScript or JSON serialization.

## Version and manifest

The current constants are:

```kotlin
const val API_VERSION = "1.1.0"
const val COMPOSE_ABI = "kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0"
```

Manifest fields match the implemented `Manifest` class:

| Field | Meaning |
| --- | --- |
| `id`, `name`, `version`, `author` | Required identity and display metadata. IDs use validated lowercase ASCII identifiers; versions use stable `major.minor.patch`. |
| `api` | Explicit exact API version. The importer requires this field even though the Kotlin model has a default. |
| `runtime` | Exactly `javascript` or `kotlin`. |
| `platforms` | Supported host names: `desktop`, `android`. |
| `script` | JavaScript entrypoint path; required for `.mfp`, absent for native artifacts. |
| `entryClass` | Native class with a no-argument constructor implementing `NativePlugin`; absent for JavaScript. |
| `composeAbi` | Exact current compiler/Compose/API tag for native plugins. |
| `dependencies` | Map from required plugin IDs to minimum stable versions; plain `1.0.0` and `>=1.0.0` are supported. No optional dependency solver is implemented. |
| `capabilities` | Descriptive capability names. They are metadata, not a security permission system. |
| `description`, `icon` | Optional description and packaged icon path. |
| `minimumHostVersion` | Optional minimum stable host version; the engine rejects it if the actual host version is unavailable or incompatible. |
| `settings` | List of validated `SettingDefinition` objects. |
| `integrity` | Payload path → SHA-256 map. Supplied hashes are verified; the importer creates a local index if absent. This is not a publisher signature. |

JavaScript archives carry `manifest.json` at their root. Native JARs carry root `manifest.json`; APKs carry `assets/metrofuse/manifest.json` alongside actual DEX code and Android resources. ZIP entry names must use `/` on every host. The importer stores normalized metadata and the intact native artifact in private managed installation storage.

## Lifecycle and ownership

Native plugins implement:

```kotlin
interface NativePlugin {
    suspend fun start(context: PluginContext)
    suspend fun stop() {}
}
```

JavaScript entry modules assign `globalThis.plugin` with `init(host)` and optional `dispose()`. Initialization may return a Promise. Invalid exports, exceptions and Promise rejections fail activation and remove staged registrations.

The engine exposes `Runtime.install`, `installOrUpdate`, `enable`, `enableOrdered`, `disable`, `hotReplace`, `uninstall` and `close`. Import methods accept the original filename to select `.mfp`, `.jar` or `.apk`. Installing does not automatically enable code. Enabled state and provider preferences persist across clean restarts. Failed initialization removes the plugin from the enabled set and records failure diagnostics.

Actual lifecycle phases are `DISCOVERED`, `INSTALLED`, `LOADING`, `LOADED`, `ENABLED`, `ACTIVE`, `DISABLED`, `FAILED` and `UNLOADED`. `PluginStatus` also contains diagnostic state/error strings and a bounded transition history. Uninstall removes the installed record; there is no retained `UNINSTALLED` tombstone or separately exposed `VALIDATED` phase in this implementation.

Registrations are staged until successful activation. Reload cancels owned operations, subscriptions and scopes, removes contributions, closes QuickJS/classloader resources and creates a fresh activation. Update stages and validates the candidate before deactivation, preserves a backup during replacement and attempts rollback on failure. Rollback status reflects whether the previous implementation actually activated; failed rollback is not reported as active. Filesystem or arbitrary native side effects cannot be rolled back transactionally.

Windows uses per-activation JAR copies to avoid replacing open plugin artifacts. Android loads private read-only APK copies through `DexClassLoader`; it does not install APK components as applications. Bundled Android native libraries mark the plugin as requiring a process restart before replacement/reload. Closing a JVM classloader or removing ART references does not guarantee immediate class unloading. Arbitrary JNI loaded independently by a desktop plugin cannot be detected or safely forced out.

## Plugin context

`PluginContext` exposes only supported host contracts:

| Member | Purpose |
| --- | --- |
| `scope` | Plugin-owned coroutine scope, canceled on disable. |
| `player` | Observable player snapshot and supported controls. |
| `metadata`, `metadataDocument` | Current mapped track and metadata fields/provenance as StateFlows. |
| `settings` | Isolated observable persisted manifest-defined settings. |
| `storage` | Isolated asynchronous JSON key/value storage. |
| `network` | Asynchronous isolated HTTP client. |
| `resources` | Packaged asset reading and optional platform resource object. |
| `matcher` | Direct Kotlin access to the adapted MetroFuse title/artist/duration matcher. |
| `registerAudioSource`, `registerMetadataSource` | Typed source registration. |
| `registerLyricsProvider`, `registerSearchProvider`, `registerCatalogProvider` | Typed lyrics/search/album/artist/playlist registration. |
| `registerUi`, `registerComponent` | Native Compose screen/component registration. |
| `registerService`, `callService` | Versioned JSON service registration/invocation. |
| `registerContract`, `registerHook` | Supported service composition and before/after observers. |
| `subscribe`, `publish`, `log` | Bounded events, lifecycle cleanup and logging. |

Legacy `registerMetadata` and `registerAudio` remain from the reusable prototype. New code should prefer the typed source APIs below. Provider IDs must be unique. Disabled plugins cannot create new owned registrations or perform storage/network operations through their retained context.

Use `context.scope` for background work and honor coroutine cancellation. Initialization/stop have cooperative deadlines; blocking arbitrary native code cannot be safely interrupted. Do not put native playback or initialization I/O on the UI thread. Player mutations from JavaScript are dispatched to the platform main thread; native Compose handlers normally run there and must follow the underlying player's thread requirements.

## Track, identity and player state

`Track` contains `id`, `title`, `artist`, `album`, `duration` in milliseconds, `artwork`, and string `extras`. Unknown duration is zero; unknown artwork is empty. `TrackIdentity` contains `canonicalId`, provider `identifiers` and optional `isrc`. Metadata merging checks identity rather than treating similar titles as the same track.

`PlaybackState` contains `track`, `playing`, `position` in milliseconds and `queue`. The current API uses a boolean playing flag, not a complete buffering/error/ended state machine. Provider resolution and playback diagnostics remain separate from that snapshot.

`Player.state` is a StateFlow. `capabilities` advertises supported command names. Baseline names are `toggle`, `play`, `pause`, `next`, `seek`, `stop`; hosts may also advertise `previous`, `volume`, `playTracks` and `addToQueue`. The methods are `toggle()`, `play()`, `pause()`, `next()`, `previous()`, `seek(position)`, `stop()`, `setVolume(volume)`, `playTracks(tracks,startIndex)` and `addToQueue(tracks)`. Optional methods throw when unsupported; callers must inspect capabilities. Default `stop` pauses and seeks to zero.

Host search/display IDs are namespaced by plugin ownership. Original provider IDs and provider metadata are preserved for subsequent audio/catalog lookup. Catalog callbacks receive their original provider IDs; do not send a host display ID directly to a remote provider without the mapping.

## Audio source contract

```kotlin
interface AudioSource {
    val id: String
    val capabilities: AudioCapabilities
    suspend fun resolve(request: AudioQuery): AudioResult
}
```

`AudioCapabilities` declares supported transport names, optional codecs/formats and host requirements. Transport names are `DIRECT`, `RANGE_HTTP`, `HLS`, `SABR_UMP` and `CUSTOM`; declaring a transport does not add a decoder or custom transport implementation to the native player.

`AudioQuery` carries `identity`, `platform`, `quality`, optional requested `sourceId`, and provider `configuration`. `QualityPreference` carries optional `maxBitrate`, `lossless`, `preferredCodecs` and a selection `preset` (`AUTO`, `LOW`, `HIGH`, `MP4`). Presets express the actual upstream quality setting; providers choose available formats rather than treating presets as invented bitrate caps.

Return `AudioResult(stream=...)` or `AudioResult(error=ProviderError(...))`. A `StreamDescriptor` carries:

| Field | Meaning |
| --- | --- |
| `transport`, `locator` | Required transport and URI/private locator. |
| `mimeType`, `codec`, `bitrate` | Optional verified media information; bitrate is bits per second. |
| `expiresAtMillis` | Optional absolute expiry time. Expired sources cannot be reused. |
| `headers` | Explicit request headers for the stream. |
| `sampleRate`, `contentLength`, `durationSeconds` | Optional underlying player fields. |
| `attributes` | Bounded string metadata for supported transport integration. |

`ProviderError` has `code`, `message` and `retryable`. Do not return both a usable stream and an error. Do not log stream locators, authentication headers or signed URLs; the descriptor's normal string representation redacts its locator.

`AudioPolicy` controls bounded timeout, limited retries, preferred source, manual-source fallback, cache TTL and per-provider configuration. Registry priority/order determines candidates. Failure/timeout advances to the next compatible source; caller cancellation propagates. The host owns active playback and URL refresh/re-resolution. There is no new playback engine or guaranteed seamless cross-provider switching.

Native source example using an explicit local fixture:

```kotlin
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LocalFixturePlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        context.registerAudioSource(object : AudioSource {
            override val id = "example.local.audio"
            override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))
            override suspend fun resolve(request: AudioQuery): AudioResult {
                val uri = context.settings.values.value["fixtureUri"]?.jsonPrimitive?.contentOrNull
                if (uri.isNullOrBlank()) return AudioResult(error = ProviderError("NO_FIXTURE"))
                return AudioResult(stream = StreamDescriptor(StreamTransport.DIRECT, uri))
            }
        })
    }
}
```

Declare `fixtureUri` as a STRING setting in this example's manifest and supply a real permitted fixture. The buildable examples are in `plugins/native-examples`; use their packaging tasks instead of copying host libraries into plugin artifacts.

## Metadata, lyrics and catalog

`MetadataSource.resolve(MetadataRequest)` returns nullable `MetadataContribution`. Requests include stable identity, base `MetadataDocument` and provider configuration. Contributions include identity, JSON `fields` and confidence in `[0,1]`. `MetadataField` attaches `FieldProvenance(provider,confidence,observedAtMillis,match)`. Missing or empty contributions do not erase valid metadata. `MetadataPolicy` supports supported merge/chain/first-success/replace modes, per-field preferences, timeouts and bounded cache configuration.

`LyricsProvider.lyrics(LyricsRequest)` returns nullable `LyricsResult`. A request includes identity/title/artist/duration. Results contain `lines`, optional source and language. `LyricsLine` has text, optional `timeMs` and optional `LyricsWord` entries with text/start/end millisecond intervals. Plain lines, synchronized lines and word timing share this contract. Language is supplied by the provider; no hardcoded language fallback is introduced.

`SearchProvider.search(SearchRequest)` returns `SearchResult`. Requests contain query and limit. Results contain tracks and optional albums/artists/playlists plus `nextPage`. The current interface does not carry a page-token input, so arbitrary incremental provider paging is not promised. Result limits and owner namespacing are enforced by the host pipeline.

`CatalogProvider` supplies nullable `album(id)`, `artist(id)` and `playlist(id)` callbacks. It may implement any subset. `CatalogAlbum` has id/title/artist/artwork/tracks; `CatalogArtist` has id/name/artwork/tracks/albums; `CatalogPlaylist` has id/title/author/artwork/tracks. These are read/navigation contracts. Remote playlist mutation, listening-history export and arbitrary provider authentication flows are not built into this API; plugins may expose explicitly versioned services for additional cooperative functionality.

`TrackMatcher.selectSafeCandidates` adapts inspected MetroFuse matching behavior. Inputs are host metadata, `MatchCandidate(provider,track)` entries, provider preference order and maximum count. It considers normalized title/artist/version descriptors and duration compatibility. This helper selects candidate matches; it does not authorize playback, decrypt media or automatically search every provider.

## Settings, storage, HTTP and resources

`SettingDefinition` contains key/title/type/default/options/minimum/maximum. Types are BOOLEAN, STRING, NUMBER, ENUM and OBJECT. `PluginSettings.values` is observable; `set(key,value)` validates declared keys and values before persisting. Settings are isolated by plugin ID and survive disable/restart.

`PluginStorage.get/set` asynchronously persist JSON values in the plugin's namespace. Null passed to `set` removes a key. Limits are 1,024 keys and 1 MiB of JSON per plugin. Settings have a separate 128 KiB persistence limit. Uninstall currently retains the data namespace so reinstall can recover configuration.

`PluginNetwork.request(HttpRequest)` is asynchronous and cancellation-aware. Request fields are URL, HTTP method, explicit headers, optional text body, maximum response size and deadline. `HttpResponse` returns status/headers/UTF-8 body. Supported methods are GET, HEAD, POST, PUT, PATCH and DELETE. Bodies are at most 2 MiB and deadlines at most 30 seconds. Redirects are returned, not followed. The dedicated client has no host cookie jar, session interceptor or automatic host authentication. Binary HTTP bodies are not in this version.

Native `resources.readAsset("assets/example.txt")` reads a validated packaged asset. Android supplies its public `Resources` object through `platformResources`; desktop has no Android resource-table object. Plugin APK Activities/Services/Receivers/Providers are not installed host components.

## Compose UI, services and events

Native plugins register `NativeScreen.Content(UiContext)` or `NativeComponent.Content(UiNode,UiContext)`. Their code runs as actual Compose. `UiContext` contains the player, observable metadata, host navigation callback and optional cooperative nested `UiExtensions`. Layers are REPLACE, BEFORE and AFTER. Native plugins must use matching Kotlin/Compose versions and `compileOnly` host APIs/dependencies. Release shrinker rules preserve that external ABI; release-package validation is a separate requirement.

JavaScript registers bounded `UiNode` trees through `host.ui`, patches owned nodes through `host.patch` and attaches registered action callbacks. The application renderer supports the documented host controls; arbitrary Kotlin Compose code, DOM and WebViews are not JavaScript plugin execution models. See [JavaScript development](javascript-plugin-development.md) for exact bridge methods and examples.

`JsonService.call(JsonObject)` is the cross-language service boundary. Names require a major suffix such as `example.catalog/v1`. `ServiceContract` declares supported `ResolutionMode` values; `HookPhase` observers run BEFORE/AFTER. Implemented service composition supports replace/chain/merge/observe/first-success, while DECORATE belongs to UI layers. Native ordinary providers remain typed; services serialize only when their explicit JSON contract requires it.

The built-in `catalog.match/v1` accepts `{track,candidates,providerOrder,maximumCandidates}` and returns matched candidates. `navigation.route/v1` performs supported host navigation. Plugin settings do not expose the host's unrelated settings/session store.

Events reuse bounded flows. Built-in names include `playback`, `track_changed`, `queue_changed`, `playback_started`, `playback_paused`, `navigation_changed`, `source_resolved`, `provider_failed`, `plugin_enabled` and `plugin_disabled`. Plugin-issued stop commands emit `playback_stopped`; the boolean player snapshot does not distinguish every application stop from pause. Provider diagnostic-derived events may conflate rapid changes. Event payloads omit track extras, stream locators and headers. `Subscription.cancel` and plugin disable remove listeners. UI activation tokens reject callbacks from stale screens after replacement.

## Trust and current limits

Both native code and QuickJS bindings run in-process. Native plugins are trusted executable code and can cause process failure or retain references outside supported lifecycle contracts. This API does not claim a native sandbox, guaranteed class unloading, forced termination of blocking code, automatic dependency downloads or isolation from all arbitrary plugin side effects.

The host still retains upstream provider-specific catalog/playback behavior behind compatibility adapters. Plugin capability registration does not imply every legacy MetroFuse feature or provider has been ported. Available build, playback, instrumented-device and packaged-app results are recorded in [the implementation report](metrofuse-kmp-implementation.md). Compilation alone is not runtime validation.
