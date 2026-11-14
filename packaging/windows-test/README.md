# Windows test build

Android and desktop builds display **Metrofuse Alpha**. Gradle reads the shared `app_name` catalog entry for native desktop packaging; Android launcher aliases use the same resource. Debug, release and benchmark builds share the display name. Existing application IDs and data locations remain unchanged.

## Start the application

1. Extract the **whole** `Metrofuse-Alpha-Windows-Test.zip` into a writable folder. Keep its `app`, runtime, resources and helper files together. Do not run the executable from inside the ZIP.
2. Run `Metrofuse Alpha.exe` from that folder. Java is bundled; a separate Java installation is unnecessary. The supported Windows target is Windows 10 or later, x64. Microsoft Edge WebView2 Runtime is required for login.
3. Complete onboarding if prompted, then open **Plugins** directly below **Listen Together** in the sidebar. **Settings → Plugins** also works. An empty installed-plugin list is valid. Local plugin import does not require a marketplace or sign-in.

On the **Installed** tab, use **Import or update plugin** to select a desktop `.jar` or universal `.mfp`, then turn on its switch. Check that its state becomes **Active**. The card's menu contains settings, details, reload and uninstall. The **Advanced** tab contains provider ordering, provider search and diagnostics; the **Marketplace** tab remains opt-in.

The `examples` folder contains the plugins and a three-second synthetic audio sample:

| File | Purpose |
| --- | --- |
| `native-audio.jar` | Offline local WAV search and playback |
| `native-settings.jar` | Native Compose controls and player action |
| `settings.mfp` | QuickJS settings and declarative controls |
| `jiosaavn.mfp` | Optional JioSaavn catalog and full-song audio |
| `jiosaavn.jar` | Native Kotlin JioSaavn provider for Windows |

## First provider: JioSaavn

Import `examples/jiosaavn.mfp` or `examples/jiosaavn.jar` and enable it. The JAR contains compiled Kotlin provider classes and runs directly through the native loader. Both formats use the same plugin ID; importing the other format replaces the installed implementation and preserves its settings. The `.mfp` can also be manually imported on Android through **Settings → Plugins → Installed**.

Leave **Use JioSaavn's India catalog** enabled for Western-label releases. This restores the original provider's API catalog routing across search, song details, albums, artists and playlists. Turning it off uses the endpoint's network-region catalog, which may return covers instead of originals. Search for `No Idea Don Toliver`, `Blinding Lights The Weeknd` or `goosebumps Travis Scott`, then click **Play** beside the result attributed to the original artist. The main Search page and **Advanced → Search installed providers** both search enabled providers.

This plugin supplies track, album, artist and playlist metadata and resolves full-song audio returned by JioSaavn. High and Auto select up to 320 kbps when the track supports it; Low selects up to 96 kbps. **Allow public previews when full audio is unavailable** controls the optional JioTune preview fallback. Disabling that setting leaves full-song playback and catalog search available. Stream availability depends on the service and your location.

## Play the local WAV through the native plugin

Open `examples/Show-Audio-URI.cmd` and copy the printed file URI for `test-tone.wav`. It begins with `file:/` and encodes any spaces. Keep the extracted folder in place while testing.

1. Import and enable `native-audio.jar`.
2. Open its **Plugin settings**, paste the URI into **Local WAV fixture URI**, and click **Save setting**. A raw Windows path is not a file URI.
3. Scroll to **Search installed providers**, enter `test-tone`, and click **Search**.
4. Click **Play** beside the filename. Check that the existing player shows that track and plays the tone. Repeat Play if it has already ended. A longer WAV is useful for checking pause, seek and progress.
5. Stop playback, then **Disable** the audio plugin. Search again: its result should disappear, and its audio/search providers should disappear from **Providers**. Enable it again and repeat the search to check lifecycle recovery.

## Check native and QuickJS settings

Import and enable `native-settings.jar`, then open **Plugin settings**. Check the animated Compose panel, toggle **Enable example setting**, change **Playback button label**, and click **Save setting**. With a track playing, its playback button should toggle the existing player. Reload the plugin and confirm the saved values persist. Disabling it should remove its panel.

Import and enable `settings.mfp`. In **Plugin settings**, edit **Label** and save it, toggle **Enable fixture**, and exercise the plugin's displayed text field and switch. Reload, then restart the application and check persistence. Disable the plugin and confirm its contributed controls disappear.

For a failure report, include the plugin name/state, the action taken and the manager diagnostics. The default application log is `%USERPROFILE%\.local\state\metrolist\logs\app.log`; custom XDG paths can change this location. After a playback failure, the application also offers playback log export in its diagnostics settings.
