# Native Kotlin plugins

Native plugins implement `dev.metrofuse.api.NativePlugin` from the in-tree `:plugin-api` module, API **1.1.0**. The host provides that API, Kotlin, coroutines, serialization and Compose libraries. Use `compileOnly(project(":plugin-api"))`; never bundle copies of these host libraries. The importer rejects their class definitions in JAR and DEX payloads. This SDK dependency is currently built from this source checkout; publication to a public Maven repository has not been performed.

## One implementation, two artifacts

`plugins/native-examples` compiles shared JVM-compatible Kotlin once. Windows receives a JAR. The Android task converts the same class files with D8 and packages an APK using AAPT2. Android-specific plugins can instead use an Android application Gradle module with `compileOnly` host dependencies. An APK is a code/resource container: it does not register manifest Activities, Services or other installed application components.

```powershell
.\gradlew.bat :plugin-native-examples:packageNativeJars
.\gradlew.bat :plugin-native-examples:packageNativeApks
```

The examples are `minimal`, `audio` and `settings`. Expected output directory is `plugins/native-examples/build/outputs/plugins/`, containing `native-<example>.jar` and `native-<example>.apk` after the corresponding task succeeds. APK containers are unsigned because they are loaded directly by the host; installing them as separate apps is not their purpose.

The optional JioSaavn provider in `plugins/providers/jiosaavn-native` also builds both native formats from the same implementation:

```powershell
.\gradlew.bat :plugin-jiosaavn-native:packagePluginJar :plugin-jiosaavn-native:packagePluginApk
```

Outputs are `build/plugin-examples/jiosaavn.jar` and `build/plugin-examples/jiosaavn.apk`. Import the APK inside the Android host's plugin manager. All JioSaavn formats share the same plugin ID and persisted settings. `JioSaavnApkTest` verifies native DEX activation, update, reload and restart on Android. Pass `-Pandroid.testInstrumentationRunnerArguments.jiosaavnLive=true` and select that test class to additionally run opt-in Western catalog and Android MediaPlayer decoder checks.

The build requires the repository's Temurin 25 toolchain and Android platform `android-37.0` plus build tools `37.0.0`. Configure `sdk.dir` in untracked `local.properties`, or set `ANDROID_HOME`/`ANDROID_SDK_ROOT`. The Kotlin/Compose ABI is pinned to the versions in `gradle/libs.versions.toml` and the API's `COMPOSE_ABI` constant. Rebuild native plugins when this ABI changes.

## Manifest and installation

JAR: `manifest.json` at archive root. APK: `assets/metrofuse/manifest.json`. The Gradle example tasks generate these files.

```json
{
  "id": "example.native.minimal",
  "name": "Native minimal example",
  "version": "1.0.0",
  "author": "MetroFuse",
  "api": "1.1.0",
  "runtime": "kotlin",
  "platforms": ["desktop", "android"],
  "entryClass": "dev.metrofuse.examples.MinimalPlugin",
  "composeAbi": "kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0",
  "dependencies": {},
  "capabilities": ["logging"],
  "integrity": {}
}
```

Import the native `.jar` on desktop or `.apk` on Android through the plugin manager. No `.mfp` wrapper is required; `.mfp` is reserved for universal JavaScript. Local installation works without a marketplace. Imported code begins disabled until enabled. Native files are copied to host-private storage, validated, and represented internally by a normalized manifest whose SHA-256 index covers the original artifact and extracted UI assets. SHA-256 detects subsequent corruption; an optional marketplace checksum verifies the downloaded artifact. Neither establishes a native-code sandbox.

Dependency values specify required minimum stable versions, for example `"other.provider": ">=1.2.0"`. Missing dependencies, insufficient versions and cycles are rejected. API versions must match exactly. Optional dependencies and version-range solving are not implemented.

## Lifecycle and providers

```kotlin
class MyPlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        context.registerAudioSource(myAudioSource)
        context.registerMetadataSource(myMetadataSource)
        context.registerUi("plugin.my.settings", myNativeScreen)
    }

    override suspend fun stop() {
        // Close plugin-owned files, sockets and other resources.
    }
}
```

Registrations are staged until initialization succeeds. The host cancels the plugin scope, removes providers, components and subscriptions, calls `stop`, then releases the loader. Launch background work in `context.scope`; do not create untracked global jobs. Native provider calls use Kotlin interfaces directly.

`FixtureAudioPlugin` demonstrates a deterministic PCM/WAV resolver and search provider. Set its isolated `fixtureUri` string setting to the generated file's URI. Its search returns the file's name and carries that URI in track extras; an empty setting returns no results. The resolver reads `request.identity.identifiers["fixtureUri"]`, accepts a local `file:/` URI, and returns `StreamDescriptor(DIRECT, uri, "audio/wav", codec = "pcm")`. Generate permitted local media with `tooling/native-fixture.ps1`; Android fixtures must reside in storage accessible to the host application. The example does not call any external music service.

`ComposeSettingsPlugin` demonstrates direct `NativeScreen.Content` registration and observable, persisted plugin settings. Its button label comes from its manifest-defined string setting rather than a Kotlin UI literal. Boolean changes use `context.settings.set` from the plugin scope.

Assets are accessible through `context.resources.readAsset("assets/path")`. Android exposes a public `Resources` instance as `context.resources.platformResources`; platform-specific plugins may cast it to `android.content.res.Resources`. Resources are loaded through `PackageManager.getResourcesForApplication` using the plugin APK's `ApplicationInfo`, without hidden `AssetManager` methods.

## Optional YouTube Music provider

The guest provider is built only when explicitly included with `-PbuildYoutubeMusicPlugin=true`; it is not installed into a fresh application. Its native source is in `plugins/providers/youtube-music` and registers audio, metadata, search and catalog implementations through the same public provider APIs as the SDK examples.

```powershell
.\gradlew.bat -PbuildYoutubeMusicPlugin=true :plugin-youtube-music:desktopTest :plugin-youtube-music:packagePluginJar :plugin-youtube-music:packagePluginApk
```

The native artifacts embed InnerTubeX and Kotlin-only QuackJS 0.1.4. Ktor runtime classes and the HTTP engine are supplied by the application. The plugin's isolated `HttpClient` uses host engine discovery, allowing Android's OkHttp and desktop's available engine without referencing CIO on Android. Packaging generates a manifest with the pinned Compose ABI and preserves Android AAR assets and any native libraries. Its guest resolver accepts direct, ranged HTTP and HLS formats; it rejects SABR, lossless requests and unavailable streams. Accounts, video playback and remote catalog continuations are unimplemented. [Native provenance](../plugins/providers/youtube-music/SOURCE.md) records source and dependency licenses.

A separate universal `.mfp` implementation lives in `plugins/providers/youtube-music-js` and contains JavaScript only. It supports public catalog requests and available guest direct streams with limited signature transforms. It reports unsupported throttling transforms and service login restrictions explicitly. Neither implementation provides authenticated playback or SABR. All variants share one provider owner; import one implementation at a time. The deliverable folder is `dist/youtube-music-plugin`; its verification report distinguishes fixture checks from actual service and app playback.

## Platform lifetime limits

Desktop activation uses a fresh private JAR copy. Both classloaders delegate platform, Kotlin, coroutines, serialization, Compose and plugin API classes to the host; plugin-private dependencies load from the plugin first to avoid host version conflicts. Declared dependencies participate in lifecycle ordering and host-service resolution; dependent plugin classpaths are not merged, so use host services for cross-plugin calls. Closing `URLClassLoader` releases owned handles; immediate JVM class unloading is not guaranteed. Replacement of the original installed file can occur without holding its Windows file lock.

Android uses `DexClassLoader` over a read-only private APK copy and supports its multidex entries. Compatible `lib/<ABI>/*.so` files are extracted into the same private activation directory. APKs containing native libraries require a process restart before replacement. ART/JNI class and library unloading are not forced. A desktop plugin can independently load arbitrary JNI code; the host cannot reliably detect or unload all such code, so its author must document restart requirements.

Native plugins execute within the application process as trusted user-installed code. Catching lifecycle exceptions does not contain memory corruption, deliberate process termination or unrestricted native file access.

## Verification

`engine/src/desktopTest/.../NativePluginTest.kt` creates and dynamically loads an actual JAR, checks shared API identity and Windows replacement behavior, exercises repeated activation/deactivation, updates, restart persistence, removal and invalid dependency graphs.

`engine/src/androidDeviceTest/.../NativeApkTest.kt` loads the example APK through the real Android loader and checks its entrypoint and resource handle. Its second test activates the audio APK, searches its configured WAV fixture, resolves it, decodes/plays it through Android `MediaPlayer`, reloads/reinstalls it and checks provider cleanup. This exercises a real Android decoder alongside an isolated test host; the application's upstream ExoPlayer path requires separate validation. The APKs must be supplied as test assets. A connected Android device or emulator is needed to execute the tests. Compilation alone does not establish runtime behavior. Consult `metrofuse-kmp-implementation.md` for actual executed build/test results.
