# Existing MetroFuse feature audit and port

## Source provenance

The actual Android source was inspected directly from `C:\Users\Test\Downloads\MetroFuse-main (4).zip`, not inferred from nearby nightly APKs. Archive SHA-256:

```text
76ad4ac3bb5aabf839d72990da28f48efffb75b36bcd2697b48898014df14bfa
```

Its ZIP archive comment records commit `e2cca5bf2278f0345568a48adbfbfbd0046680f6`. Paths below are relative to the archive's `MetroFuse-main/` directory. Source files carry `Metrolist Project (C) 2026`, GPL-3.0, and contributor attribution through Git history. The archive includes the GNU GPL version 3 license. Adapted source retains these notices and records the source commit. The inspection extracted only selected source files; it did not extract user configuration, credential files or authentication stores. No provider service requests were performed during this audit.

## Provider code found

| Existing source | Actual responsibilities | Reuse and limitations |
| --- | --- | --- |
| `app/src/main/kotlin/com/metrolist/music/jiosaavn/JioSaavnAudioProvider.kt` | Namespaced track/album/artist/playlist IDs, `api.php` search/details, candidate scoring, quality selection, bounded stream cache and expiry. | Safe metadata parsing, Unicode/title normalization, duration matching and quality preferences are portable. The archived full-media path decrypts encrypted locators and sends region-impersonation headers; those mechanisms are excluded from this migration. A plugin must use permitted plaintext preview/direct media supplied by an authorized API/backend, respect region restrictions and use host networking asynchronously. |
| `app/src/main/kotlin/com/metrolist/music/soundcloud/SoundCloudAudioProvider.kt` | API-v2 track/search lookup, progressive/HLS transcoding selection, bitrate/codec preferences, track/stream caches, artist/title/version matching and waveform metadata. | Typed direct/HLS descriptors map to the new audio API. The archive also scrapes public web scripts for application identifiers and refreshes them after errors. That discovery mechanism is not ported. A production plugin needs legitimate configured application credentials and, when necessary, user-authorized OAuth. It must not obtain host account cookies/tokens through generic plugin services. |
| `app/src/main/kotlin/com/metrolist/music/deezer/DeezerAudioProvider.kt` | ISRC/search matching, quality fallback, bounded resolver concurrency, proxy modes, authenticated account stream resolution, Android Media3 data-source factories and encrypted block streaming. | Public catalog metadata, artwork and permitted previews can be implemented behind metadata/search/audio contracts. The Android-specific custom data source and encrypted-media processing are not portable generic-player infrastructure and are not ported. A full streaming integration requires a legitimate supported service interface and authorized access; this implementation does not add a decryption adapter. |
| `app/src/main/kotlin/com/metrolist/music/providers/ProviderMatchSearch.kt` | Parallel cross-provider searches with 8-second timeout, 90-second cache, provider order and disabled-provider settings, ISRC enrichment and native provider-specific queries. | Replace enum dispatch and Android DataStore dependencies with the dynamic registry and plugin namespaces. Keep bounded search and cancellation. Existing plugin search registrations already feed the new catalog pipeline; the archived Android coordinator was not copied wholesale. |
| `app/src/main/kotlin/com/metrolist/music/providers/IsrcResolver.kt` | Positive/negative ISRC cache, duration buckets, local tags, harvested identities and metadata lookups, Android preference persistence. | The new `TrackIdentity.isrc` contract preserves supplied identity. Cache and normalization are future portable components. The old Android persistence and service-specific lookup coordinator are not transplanted into core. |

Home-feed modules were also found under `app/src/main/kotlin/com/metrolist/music/providers/`: `JioSaavnHomeFeedProvider.kt`, `SoundCloudHomeFeedProvider.kt` and `DeezerHomeFeedProvider.kt`. They combine provider HTTP models with Android UI/navigation and preferences, so each needs conversion to typed catalog/search results rather than direct reuse in the player. They remain incomplete ports.

## Actual matching port

`app/src/main/kotlin/com/metrolist/music/providers/ProviderFallbackMatcher.kt` was adapted into `engine/src/jvmSharedMain/kotlin/dev/metrofuse/runtime/FallbackMatching.kt`. It uses the new `Track`, `MatchCandidate` and `TrackMatcher` contracts from `plugin-api`, removing dependencies on Android `MediaMetadata` and a hardcoded provider enum.

The implementation retains Unicode normalization, title token coverage, comparable-artist rejection, duration checks, confidence before provider preference, duplicate filtering and one candidate per provider. Track durations in the new API are milliseconds. For a versioned upload explicitly titled `Artist - Song (Sped Up)`, its title's artist credit can be used when metadata identifies the uploader. This corrects an inconsistency in the archived source: its canonical-version regression expected acceptance despite a different uploader artist, while its implementation rejected any comparable artist mismatch. Ordinary wrong-artist tracks remain rejected.

Native plugins use `context.matcher.selectSafeCandidates(...)` directly. JavaScript plugins call the versioned `catalog.match/v1` service with `track`, `candidates`, `providerOrder` and optional `maximumCandidates`. Matching does not resolve or decrypt streams; it selects candidate identities to pass to an authorized audio provider.

`FallbackMatchingTest` covers a versioned upload with explicit artist credit, wrong artists/unrelated titles, duration rejection, accent normalization, confidence ordering and one result per provider. Consult the implementation report for executed test results; this audit does not claim the archived test suite was run.

## Lyrics

`app/src/main/kotlin/com/metrolist/music/lyrics/LyricsProviderRegistry.kt` explicitly registers BetterLyrics, BiniLyrics, PaxsenixAppleMusic, Musixmatch, PaxsenixQQMusic, LrcLib, KuGou, LyricsPlus, Spotify, Deezer, YouTubeSubtitle and YouTube. Its saved order preserves known providers and appends newly added providers. `LyricsHelper.kt` performs sequential first-success fallback with a 25-second overall budget, 8-second provider budget, cancellation propagation and a small cache. Its alternative all-provider path creates an Android-specific independent coroutine scope; the new runtime's plugin-owned scope should be used instead.

`LyricsPlusProvider.kt` contains typed line/word/syllable timing and translations, server fallback, TTML conversion and synchronized LRC output. `betterlyrics/src/main/kotlin/com/metrolist/music/betterlyrics/TTMLParser.kt` uses JVM XML DOM APIs and preserves word/background-vocal timing. These parsers could live in JVM-shared code, but the current KMP source already has `shared/.../api/lyrics/XmlParser.kt`, `MlxParser.kt`, `MlxJsonParser.kt`, `LyricsClient.kt`, `LyricsUtils.kt` and `shared/.../repository/LyricsRepository.kt`. Those working components remain in place. Plugin `LyricsResult` supports line timing and word timing; provider-specific parsers should adapt to that contract rather than add another global lyrics manager.

Full archived Spotify/Musixmatch/Deezer authenticated lyrics integrations are not claimed migrated. Any future account-dependent plugin must obtain authorized credentials in its own namespace and respect the service's access requirements.

## Visuals and other existing functionality

The archive's `app/src/main/kotlin/com/metrolist/music/ui/player/GalaxyStarOverlay.kt` uses pure Compose Canvas, deterministic pooled stars, a 33-ms animation interval and composition-owned frame updates. It was safely adapted, with attribution, into the native SDK settings example to demonstrate compatible Compose rendering. This is a reachable plugin-screen demo; the main player has not been restyled with Galaxy backgrounds.

`MovingBlurBackground.kt` uses animated artwork transforms around a heavily blurred child layer, suppresses drift when hidden, and references Android Coil context and API-31 blur availability. A complete Galaxy-blur player port would reuse KMP `shared/.../ui/utils/BlurCompat.kt` and `ArtworkBlur.kt`, replace the Android image context with the platform-supported Coil context, and validate Windows/Android rendering and hidden-screen animation cleanup. That player integration remains unfinished.

Existing KMP Canvas/artwork infrastructure under `shared/.../api/artwork/` and `shared/.../viewmodels/CanvasFetch.kt`/`CanvasPreloader.kt`, lyrics infrastructure, and `shared/.../discord/DiscordRpcManager.kt` and its collaborators remain upstream implementations. Downloads, database, queue state, caching, navigation and themes also remain the upstream core. Retention of their source is not a claim that every remote integration or visual variant has been runtime-tested during migration.

## Remaining work

Complete authorized production provider plugins using the inspected parsing/matching behavior, test real service availability separately from deterministic SDK tests, add catalog/home-feed screens only after audio/metadata acceptance, and validate optional Galaxy player styling on both primary platforms. No Apple application or Apple platform loader is introduced by this feature audit.
