# JavaScript plugins

MetroFuse loads universal JavaScript archives (`.mfp`) using QuickJS-KT 1.0.15. The same package is imported on Windows/JVM and Android/ART. JavaScript plugins do not contain JAR, APK or DEX payloads; native extensions use separate `.jar` and `.apk` artifacts.

## Package and build

An archive contains `manifest.json`, its JavaScript entrypoint, optional modules and assets. API version 1.1.0 is experimental and exact-version checked. The importer validates paths, entry counts, sizes, platform compatibility and dependencies before installation. Supplied payload hashes are verified; archives without hashes receive a local hash index at import so later changes are detected. Checksums detect corruption, not publisher identity.

```json
{
  "id": "example.lyrics",
  "name": "Fixture lyrics",
  "version": "1.0.0",
  "author": "MetroFuse contributors",
  "api": "1.1.0",
  "runtime": "javascript",
  "platforms": ["desktop", "android"],
  "script": "index.js",
  "capabilities": ["lyrics"]
}
```

Build any supplied example from the repository directory:

```powershell
.\tooling\pack-mfp.ps1 -Source .\plugins\javascript\lyrics -Output .\build\plugin-examples\lyrics.mfp
```

The packer uses only PowerShell/.NET ZIP support, stamps payload SHA-256 hashes and fixed ZIP entry timestamps. Examples are in `plugins/javascript/minimal`, `metadata`, `lyrics` and `settings`. On Android, open Profile → Integrations → Plugins; Settings → Plugins also opens the manager. Import the resulting file, then enable it. Local import works independently of a marketplace.

## Entrypoint and lifecycle

The entrypoint is an ES module. Relative imports resolve within the installed package. It must assign `globalThis.plugin` with a callable `init(host)`. `dispose()` is optional. Initialization may return a Promise; rejection or invalid exports produce a failed plugin state and remove staged contributions. A context remains active between calls and is disposed on disable/reload.

```javascript
globalThis.plugin = {
  async init(host) {
    this.unsubscribe = host.on('track_changed', track => host.log(track.id));
    host.lyrics('example.fixture.lyrics', async request => ({
      source: 'Local fixture',
      lines: [{ text: request.identity.canonicalId, timeMs: 0 }]
    }));
  },
  dispose() { this.unsubscribe?.(); }
};
```

Registrations are published together after successful initialization. Plugin-owned coroutines, Promise waits, event subscriptions, providers and UI contributions are canceled or removed on disable. Dispose runs with a short deadline after owned operations are canceled; use it for final local cleanup, not new host I/O. Native code and JS bindings run in the host process; this is not a security sandbox.

The runtime applies a 32 MiB context memory limit, 512 KiB stack limit, sampled active evaluation watchdog and 30-second wall deadline. Host-service waits are excluded from the sampled active budget. This is approximate watchdog accounting, not a benchmark or precise CPU measurement. Caller cancellation interrupts pending evaluation; providers also have their pipeline timeouts. Same-context direct recursive JS service calls are rejected; indirect cycles remain bounded by deadlines.

## Host API

All provider and action callbacks must be functions. Cross-runtime payloads use the serializable contracts in `plugin-api/src/commonMain/kotlin/dev/metrofuse/api`.

| API | Contract |
| --- | --- |
| `host.audioSource(id, capabilities, resolve)` | `AudioQuery` → `AudioResult`; use a `StreamDescriptor` or structured `ProviderError`. |
| `host.metadataSource(id, resolve)` | `MetadataRequest` → nullable `MetadataContribution`, with identity, fields and confidence. |
| `host.lyrics(id, resolve)` | `LyricsRequest` → nullable `LyricsResult`, with lines and optional timestamps/word intervals. |
| `host.search(id, search)` | `SearchRequest` → `SearchResult`, with tracks, albums and artists. |
| `host.catalog(id, {album, artist, playlist})` | Optional async catalog lookup callbacks returning `CatalogAlbum`, `CatalogArtist` or `CatalogPlaylist`. At least one callback is required. |
| `host.service(name, callback)` | Register a versioned JSON service, e.g. `example.catalog/v1`. |
| `await host.call(name, request)` | Call an available versioned host/plugin service. |
| `await host.call('catalog.match/v1', {track, candidates, providerOrder, maximumCandidates})` | Match `MatchCandidate` tracks using the adapted MetroFuse title/artist/duration matcher; returns `{candidates}`. Native plugins use `context.matcher` directly. |
| `host.action(id, callback)` | Register a callback for declarative UI actions. |
| `host.on(event, callback)` | Subscribe; returns an unsubscribe function. |
| `await host.emit(event, payload)` | Publish a bounded JSON event. |
| `host.log(message)` | Bounded host logging with URL redaction. Do not log credentials. |
| `host.playerState()` | Snapshot of current track, playing flag, position and queue. |
| `host.playerCapabilities()` | Supported player command names. |
| `await host.player(command, value)` | Supported controls: toggle/play/pause/next/previous/stop/seek/volume, according to capabilities. |
| `await host.playTracks(tracks, startIndex)` | Replace/play the queue where supported. |
| `await host.addToQueue(tracks)` | Append tracks where supported. |
| `await host.track(identity)` | Nullable host catalog track lookup. |

Legacy `host.metadata(fn)` and `host.audio(id, fn)` are retained from the reusable prototype. New integrations should use typed source APIs. Player mutations are dispatched to the platform main thread; networking and storage never perform blocking I/O there. Hosts advertise unsupported controls rather than silently succeeding.

Provider fields include stream transport, locator, MIME/codec, bitrate, expiration, request headers and the optional fields required by the underlying native player. Format support depends on that player; advertising a format does not add a decoder. Returning a short-lived source should include `expiresAtMillis`; policy prevents reuse after expiry. No DRM bypass or ambient host credentials are supplied.

Provider callbacks use the provider's original identifiers. The host namespaces search/display identifiers by owning plugin and preserves original identifiers in the track metadata used for audio and catalog lookup. Do not treat a host display identifier as a provider-specific identifier without that mapping.

## Networking and isolated data

```javascript
const response = await host.http({
  url: 'https://your-provider.example/catalog',
  method: 'GET',
  maxBytes: 65536,
  timeoutMs: 10000
});
if (response.status !== 200) throw Error('Provider HTTP failure');
const result = JSON.parse(response.body);
await host.storage.set('lastResult', result);
```

`host.http` returns `{status, headers, body}`; body is UTF-8 text. GET, HEAD, POST, PUT, PATCH and DELETE are supported. `body` supplies an optional JSON text request body, and `headers` supplies explicit plugin-owned headers. Response/request body limits are 2 MiB; request deadlines are at most 30 seconds. Redirects are returned to the caller. The dedicated client has no host cookie jar, session store or authentication interceptors. Cancellation cancels the HTTP call. Binary HTTP responses are not part of this API version.

`host.storage.get(key)`, `set(key, value)` and `remove(key)` are asynchronous JSON storage operations. Data is isolated by plugin ID and persisted in the runtime's `data/<id>/storage.json`. There are at most 1,024 keys and 1 MiB of JSON per plugin. A plugin cannot use this API to select another namespace.

`host.settings.all()`, `get(key)` and `set(key, value)` access settings declared in the manifest. Definitions support BOOLEAN, STRING, NUMBER, ENUM and OBJECT, defaults, numeric bounds and enum choices. Unknown keys and invalid types are rejected. Changes update an observable Kotlin StateFlow and are persisted atomically where the filesystem supports it. Settings survive disable and application restart; uninstall retains the plugin's data namespace.

## UI and events

`host.ui(point, tree, layer)` registers host-rendered Compose UI, with `layer` REPLACE, BEFORE or AFTER. Stable node IDs and bounded depth/size are validated. The application renderer supports Column, Row, List, Text, Button, Switch and TextField; additional cooperative slots are host-defined. `host.patch(id, props)` changes this plugin's nodes. Native Compose code cannot execute inside JavaScript.

```javascript
host.action('label', async value => await host.settings.set('label', value));
host.ui('settings.example', {
  id: 'page', type: 'Column', children: [
    { id: 'label', type: 'TextField', props: { text: 'Local fixture', action: 'label' } }
  ]
});
```

Built-in event names include `playback`, `track_changed`, `queue_changed`, `playback_started`, `playback_paused`, `navigation_changed`, `source_resolved`, `provider_failed`, `plugin_enabled` and `plugin_disabled`. `playback_stopped` is emitted for plugin-issued stop commands; the existing boolean playback snapshot does not distinguish every application-side stop from pause. Provider events are derived from the bounded diagnostic flow and may conflate rapid changes. Payloads exclude track extras, stream locators and headers. UI callbacks use activation tokens so stale controls cannot invoke a reloaded implementation. Disabling the owning plugin removes its pages and actions.

## Validation

`JavaScriptRuntimeTest` exercises real QuickJS modules/Promises, errors, infinite-loop interruption, caller cancellation, disable cleanup, settings persistence, typed providers, local HTTP bridge limits/cancellation and the Android risk-marker policy on JVM. `JavaScriptAndroidTest` exercises universal archive import, real QuickJS execution on ART, asynchronous host calls, metadata/lyrics/UI registration, persistence and repeated reload. Compilation and runtime results are recorded separately in the implementation report; the existence of these tests does not imply they have passed on every platform.

An additional optional provider lives in `plugins/providers/jiosaavn`. Package it with the same tool to `build/plugin-examples/jiosaavn.mfp`. It ports inspected MetroFuse catalog parsing, DES locator decoding, full-song quality selection and optional public previews, with source attribution in its `SOURCE.md`. The persisted `regionalCatalog` setting restores the upstream provider's catalog-region headers on every API request; headers are never attached to CDN streams. `JioSaavnPluginTest` and `JioSaavnRoutingTest` validate production QuickJS parsing, cryptography and routing without service requests. `JioSaavnWesternCatalogTest` additionally validates the packaged MFP and native JAR against Western originals when `METROFUSE_JIOSAAVN_LIVE=1`. The native implementation lives in `plugins/providers/jiosaavn-native`; build it with `:plugin-jiosaavn-native:packagePluginJar`. Network providers may require increasing the host metadata/audio policy timeout from its short default; live catalog and playback availability are separate from parser and packaging validation.
