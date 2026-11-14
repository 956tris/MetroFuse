# MetroFuse KMP handoff

## User intent

Continue the existing workspace in place. Remove the mandatory YouTube Music provider from Windows and Android, preserve the existing Media3 and FFmpeg engines, move YTM to an optional plugin if feasible, and finish the dual runtime and UI described in the [original guide](https://chatgpt.com/library?file_id=libfile_624ab81dfa1481918e900e8f933bf2dc&account_id=personal). Fresh installs start with zero plugins. The user will manually import and enable JioSaavn. Eventual deliverables are a current Android APK and a runnable Windows EXE with its supporting files. The user requested this handoff before full build and runtime verification.

Workspace root:

```text
C:\Users\Test\Downloads\Metrofuse KMP main\Metrolist-main (1)\Metrolist-main
```

There were significant existing staged-in-place source changes and untracked modules before this turn. Use `git status --short` and inspect existing work. Do not reset, discard, or commit any changes. Keep Windows and Android as primary targets.

## JioSaavn location

- Import `build/plugin-examples/jiosaavn.mfp` through **Settings → Plugins → Import or update plugin**, then enable it. The same MFP is for Android and desktop.
- Source folder is `plugins/providers/jiosaavn`.
- Repack from the project root with `& .\tooling\pack-mfp.ps1 -Source plugins/providers/jiosaavn -Output build/plugin-examples/jiosaavn.mfp`.
- This provider searches tracks, albums, artists and playlists and offers public JioTune previews. Full-song playback is currently absent. Search for `Tum Hi Ho` to check against an existing live fixture. Latest provider source still needs real runtime testing on both targets.

## Source changes in this turn

- `shared/.../plugins/PluginServices.kt` now resolves ordinary audio via the local source and external provider registry, without delegating to the original YTM stream resolver. `SharedModule.kt` uses that resolver on Android and Windows. Apple keeps the upstream DI path. A typed unavailable error uses the existing localized message and failure classification. Explicit video/client overrides remain outside the audio plugin contract.
- `shared/.../plugins/MetroFuseHost.kt`, `plugin-api/.../Host.kt` and `engine/.../Runtime.kt`: removed the built-in YTM audio registration from the host. Restored namespaced IDs recover provider owner and raw ID without the in-memory cache. Namespaced album links survive mapping. HLS descriptors without a MIME type are marked as HLS. Registered metadata sources can contribute identity data during resolution.
- Main Search/catalog routing uses providers plus local data on the supported targets. Search suggestions and history use local data. Explicit YouTube playlist import remains an explicit integration. Main Android/desktop startup, Home and Library were made local-first, and automatic YTM reporting and radio calls were restricted on provider hosts. The host still contains upstream YTM types and some explicitly invoked integrations. This is an in-progress migration.
- `LyricsClient.kt` and `SharedModule.kt` make built-in YouTube lyric fetching optional while retaining user lyrics and non-YTM sources.
- Plugin UI now includes owner-scoped destinations, contextual menus for track/album/artist/playlist/player, declarative dialogs, and a player control slot. Settings examples remain available. Further placement and runtime coverage are pending.
- Marketplace work adds search, categories, platform filtering, richer details/source links and improved artifact selection and refresh behavior. Registry configuration is opt-in. Manual sideload remains available. The marketplace agent ran four targeted MarketplaceTests successfully outside Gradle.
- `plugins/providers/youtube-music` contains optional native plugin work in progress. Inspect its build configuration, source sets and artifacts before relying on it. YTM parity, including account features, SABR, and video, is unverified.
- The source includes focused regressions for provider-only resolution, local playback, cancellation, and local-first Home. Verify current test sources and rerun them with the complete tree.

## Build state

- `git -c core.safecrlf=false diff --check` passed during this turn. There was no complete current-source application build or runtime test on either platform.
- Android `:androidApp:assembleDebug` reached native compilation/dex dependency resolution. Offline build failed at `:androidApp:checkDebugDuplicateClasses` because three exact JARs were unavailable locally: `androidx.lifecycle:lifecycle-common-java8:2.12.0-alpha05`, `androidx.lifecycle:lifecycle-common-jvm:2.12.0-alpha05`, and `org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0`. Shell DNS to official repositories and mirrors was unavailable. Log: `build/goal-android-inprocess.log`.
- Windows `:desktopApp:assembleWindowsDebug` stopped at the `:downloadWix` predicate. Investigate using the existing local WiX under `build/wix311` or test `:desktopApp:createDistributable` for a portable image. Log: `build/goal-windows-inprocess.log`. The existing `dist/MetroFuse-Windows-Test.zip` predates the decoupling and contains the upstream YTM path.
- Normal Gradle daemon connections fail in this session. The ignored helper `build/goal-gradle-inprocess.ps1` successfully runs Gradle 9.8.2 in-process against the project-local cache using Temurin 25, explicit matching launcher flags, an isolated Android home and two workers. It defaults to offline mode. Example: `& .\build\goal-gradle-inprocess.ps1 -Tasks @(':engine:desktopTest', ':shared:desktopTest', ':desktopApp:compileKotlinJvm')`. Keep concurrent Gradle invocations off the same cache. For dependencies, use an environment with working Maven access.
- ADB foreground server failed creating its local interrupt socket pair. Emulator startup also hit sandbox restrictions. Retry Android installation and launch on a normal Windows session or connected device.

## Continue from here

1. Compile both primary targets first and repair any errors in this working tree. Run the focused engine/shared tests, including the native and QuickJS FFmpeg fixture and JioSaavn tests. Check the actual task names with Gradle if necessary.
2. Audit remaining YTM-specific host integrations and isolate them where feasible. Finish building the optional native YTM plugin and prove it uses the same public provider registry. Verify legacy IDs, provider-owned IDs, queues, history, downloads, local playback, and failure handling across restarts. Preserve explicit playlist import and existing generic media features.
3. Build and launch the real Windows and Android apps with empty plugin directories, then import JioSaavn manually. Exercise Search, catalog navigation, preview playback, deactivate/reload, persistence, native plugin formats on both platforms, and UI extensions. Record what ran and what remains unverified.
4. Reconcile `docs/architecture-audit.md`, `docs/migration-plan.md` and `docs/plugin-architecture.md` with verified behavior. Some sections still describe the older YTM fallback. Use this handoff and the linked guide for the current direction.
