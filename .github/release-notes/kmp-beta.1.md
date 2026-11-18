# MetroFuse KMP Beta

The first production-channel KMP beta for Android and Windows x64 includes offline playback, optional provider and UI plugins, custom-interface startup recovery, Android sign-in loading/retry controls, and music source selection from compatible custom interfaces.

## Beta warning

This beta may crash, contain incomplete features, or fail with incompatible plugins. Migration may cause data loss. Export a backup before updating and keep it outside the app folder. Database downgrades are unsupported. Keep your previous installer and backup. Spotify live account verification remains pending; Spotify supplies metadata and does not supply audio streams. Provider authentication and availability can change independently of MetroFuse.

## Installation and migration

Android: install the main app APK, MetroFuse-Android-Release.apk. It uses com.metrofuse.music and the existing MetroFuse signing key. Update a matching installation in place. Do not uninstall to resolve a signature mismatch without backing up first. Debug and legacy packages are separate installations; their data is not automatically shared. Use Backup & import for supported exports and restores.

Windows x64: extract MetroFuse-Desktop-Windows.zip into a new folder and run MetroFuse.exe. Back up the library and close the old app first. Keep the previous program folder until the beta opens your library successfully. The ZIP includes Java. Windows ARM64, macOS and iOS packages are not included in this release.

MetroFuse works offline without plugins. Open Settings > Integrations > Plugins > Import or update plugin to choose a downloaded package. Plugin APKs must be imported inside MetroFuse, NOT installed through Android's package installer. Use native APKs on Android, native JARs on desktop, or supported JavaScript MFP alternatives. Do not enable duplicate versions of a provider. Native packages must match API 1.1.0 and Compose ABI kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0. Enable the provider after import, choose it on Home, and recheck sign-in after migration.

## Updated plugins

- [Roselight Music 1.0.1](https://github.com/956tris/MetroFuse-Roselight/releases/tag/v1.0.1): APK and JAR, mobile/desktop interface with source selection and animations.
- [MetroFlow Native 1.0.2](https://github.com/956tris/MetroFuse-MetroFlow/releases/tag/v1.0.2): APK and JAR, Spotify-style navigation, playback controls and responsive layout.
- [Spotify 1.0.1](https://github.com/956tris/MetroFuse-Spotify/releases/tag/v1.0.1): APK, JAR and MFP, sign-in completion fix.
- [JioSaavn 1.3.0](https://github.com/956tris/MetroFuse-JioSaavn/releases/tag/v1.3.0): APK, JAR and MFP, discovery and search improvements.
- [Deezer 1.0.3](https://github.com/956tris/Metrofuse-Deezer/releases/tag/v1.0.3): APK and JAR, Android source/quality picker fix.

Plugins are separate downloads and are not bundled into the app. Use the subtle restore-original-interface control if a UI plugin misbehaves. Restart after a UI crash to enter recovery. Repeated failures may quarantine a plugin. Recovery cannot prevent every in-process plugin crash or freeze. Live rendering and playback checks for the latest UI plugin packages remain limited.
