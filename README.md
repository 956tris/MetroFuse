# MetroFuse

<img src="desktopApp/src/desktopMain/resources/logo.svg" alt="MetroFuse logo" width="96" />

MetroFuse is an offline-first music player for Android and desktop. Play your local library without an account, and add optional plugins for music services, lyrics, or custom interfaces.

## Get MetroFuse

Download the signed Android APK (`MetroFuse-Android-Release.apk`) or the Windows x64 portable ZIP (`MetroFuse-Desktop-Windows.zip`) from [MetroFuse Releases](https://github.com/956tris/MetroFuse/releases), and verify packages against `SHA256SUMS.txt`.

## Plugins (API 1.1.0 & 1.2.0)

Import plugins through **Settings → Plugins → Installed → Import or update plugin**. The [user guide](docs/user-guide.md) covers setup, sign-in, and audio source order. MetroFuse `10.0.0` supports **Plugin API 1.2.0** alongside full backward compatibility with **Plugin API 1.1.0** (`.mfp` JavaScript and native `.apk`/`.jar` plugins):

- **JavaScript `.mfp`**: Author concise plugins with `definePlugin({ search: { ... }, lyrics: { ... }, audioSources: { ... } })`, `host.json(url)`, and `host.text(url)`.
- **Native Kotlin `.apk`/`.jar`**: Extend `MetroFusePlugin` from `plugin-sdk` using the `PluginBuilder` DSL and package quickly with `tooling/metrofuse-plugin.py`.
- See the [plugin authoring guide](https://github.com/956tris/MetroFuse-Plugin-Docs) and local [plugin examples](plugins/native-examples) for full SDK details.

Based on [Metrolist KMP](https://github.com/MetrolistGroup/Metrolist). Licensed under the [GNU GPL v3](LICENSE).
