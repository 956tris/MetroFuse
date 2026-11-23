# MetroFuse user guide

MetroFuse plays music stored on your device without an account or internet connection. Optional plugins add content sources, lyrics, metadata or a different interface. App downloads contain the plugin host and SDK; provider plugins are separate downloads that you choose to install.

This guide covers Android and desktop. Menu names below use the English interface; your selected app language may translate them.

## Start with your own music

1. Install and open MetroFuse. A new installation starts with **Offline** selected.
2. On Android, open **Library → Add → Scan local music**, or **Settings → Storage → Local music**. Allow audio access when prompted, then scan.
3. On desktop, the app scans your Music folder. Open **Settings → Storage → Local music** to use **Add music folder**, remove a folder or scan again.
4. Open **Library**, choose a song and press Play. Tap or click the mini-player to open the player and queue.

Keep the original files in an accessible location. Moving or deleting them can make their library entries unplayable. If Android denies audio access, use **Open app settings** on the Local music page, grant access and scan again. On desktop, check folder permissions and reconnect removable drives before rescanning.

## Home, Search and Library

The Home title opens the source selector. **Offline** is listed first. Enabled plugins that supply a Home frontend appear below it.

| Selected source | What to expect |
| --- | --- |
| Offline | Device music and downloaded or cached audio; playback is restricted to offline audio. |
| A provider plugin | That source's Home and Search. If the plugin supports an account library, Library shows its songs, albums, artists and playlists as pages load. |

Selecting a provider does not move or delete your device library. Switch back to **Offline** to find local music, local playlists and scanning controls. If the selected plugin is disabled or removed, the app returns to Offline. A provider without account-library support retains the host's saved-content view.

**Source selection and audio priority are separate controls.** The source selector chooses the frontend you browse. Audio providers resolve playback, and their order is managed through playback source controls and **Settings → Plugins → Advanced → Providers**. Hold and drag a handle to reorder providers within a group; earlier providers run first, after their dependencies. Tap or click a handle for move actions, or focus it and use Alt+Up/Down. Installed plugins can be reordered the same way; provider group order overrides that plugin order. Orders are saved across restarts, including preferences for temporarily disabled providers. Selecting Offline still restricts playback to offline audio.

## Choose a plugin package

Provider plugins supply content or playback capabilities. UI plugins change part of the interface or replace the whole application shell. A UI plugin uses MetroFuse's existing player and queue; its appearance alone does not add a music service or sign you into an account. Some plugins contribute several capabilities.

| Download | Import into | Description |
| --- | --- | --- |
| `.mfp` | Android or desktop | Universal JavaScript plugin archive, when its manifest supports your platform. |
| `.jar` | Desktop | Native Kotlin plugin built against the host's API, SDK and Kotlin/Compose versions. |
| `.apk` | Android | Native Kotlin plugin package imported through MetroFuse's Plugins screen. |

The Android **application APK** installs or updates MetroFuse itself. A **plugin APK** is selected inside MetroFuse using the import button. Do not rename one package format into another or extract an MFP before importing it.

Choose the package offered for your platform and host version. Both Plugin API **1.1.0** and **1.2.0** are supported (`1.2.0` adds simplified JS `definePlugin(...)` and Kotlin `MetroFusePlugin` DSL helpers while staying 100% compatible with existing `1.1.0` plugins), and native packages must also match the host ABI. Read the author's compatibility notes. Native plugins execute code inside the app process, so install packages from authors you trust. The [plugin authoring guide](https://github.com/956tris/MetroFuse-Plugin-Docs) explains package compatibility and development.

## Import, enable and update plugins

1. Download the package from its author's release page. Keep it as an `.mfp`, `.jar` or `.apk` file.
2. In MetroFuse, open **Settings → Plugins → Installed**.
3. Under **Add a plugin**, choose **Import or update plugin**, then select the file.
4. Find the plugin in the installed list. Turn on its switch if it is disabled. **Active** means startup completed; **Failed** includes an error to inspect.
5. Open its three-dot menu for **Details**, **Plugin settings** when available, **Reload**, or **Uninstall**. Details show its ID, platforms, API version and declared capabilities or dependencies.

To update, import the new package through the same button. It must have the same plugin ID. The host verifies the candidate before replacing the installed package and restores the previous package if activation of an enabled update fails. If an update says native libraries are still loaded, restart MetroFuse before trying again.

Disabling withdraws the plugin's active providers and UI contributions while leaving the package installed. Reload restarts an active plugin. Uninstall removes it; return to Offline or another source if needed. Keep account credentials and irreplaceable data backed up separately.

### Use a marketplace registry

**Settings → Plugins → Marketplace** accepts a registry URL you choose. Enter the author's registry URL, choose **Fetch**, then browse or filter the catalog. Inspect the source repository, supported platform and API version before choosing **Install** or **Update**. Use **Refresh** to fetch changes. An empty marketplace does not mean local imports are unavailable; you can always use the Installed tab.

## Accounts and provider content

After enabling a provider with Home support, select it from the Home title. The profile menu exposes account and login actions when that plugin supports sign-in. Follow the plugin's account screen; some use a browser and others use a form. Only plugins declaring login support offer these actions.

The plugin checks its account before accepting the session. Accepted credentials are stored under that plugin's ID using Android Keystore encryption or user-bound Windows DPAPI. Persistent plugin credentials are currently unavailable on Linux and macOS, so signing in again can be necessary. Never include passwords, cookies or tokens in a bug report.

Account changes refresh that source's cached content. A provider's account library, likes and voting depend on the capabilities it implements. If Home is empty or sign-in fails, check connectivity and the plugin's settings and diagnostics, then try Reload. Local playback remains available through Offline.

## Change or restore the interface

Import and enable a UI plugin using the same steps as a provider. When a full-shell replacement is available, **Installed → App interface** lets you select it by name. If several replacements are active, choose the one you want. Use one package variant of the same UI plugin for your platform.

The host keeps a small **Use original interface** restore icon in the top-right corner of a custom shell. Choose it to return to the built-in interface, then open Plugins to inspect errors or disable the UI plugin. **Enable custom interfaces** turns custom rendering back on after you have addressed the problem. Switching interfaces continues to use the host player and queue.

If recovery paused plugin activation during startup, review or disable the failing plugin first, then choose **Enable custom interfaces** and restart to resume your saved activation choices.

If the custom interface prevents normal startup:

- On the Android or desktop crash screen, choose **Use original MetroFuse UI**. This saves recovery before restarting, restores the built-in interface and pauses automatic plugin activation across later restarts. Your plugin activation choices are preserved; review the failing plugin before resuming activation.
- On Windows, launch MetroFuse with the `--original-ui` argument.
- On Android, an advanced launch can set the boolean intent extra `metrofuse.original_ui=true`; this is an intent extra, not an app setting or a website address.
- If a plugin's first render is interrupted or the app detects a UI failure, the next launch restores the original presentation and pauses automatic plugin activation. Review the recovery notice and **Advanced → Diagnostics** before re-enabling custom interfaces. Repeated failures can quarantine a UI plugin; a native crash or deadlock may require restarting the app.

Plugin authors can use the [SDK UI guide](../plugin-sdk/ui-plugins.md) for supported surfaces, player access and recovery behavior.

### Try MetroFlow

MetroFlow is an optional dark interface with green accents, a desktop sidebar, compact navigation, Home shelves, Search, Library, Queue and a persistent player. It displays your selected MetroFuse source and uses the existing playback engine. Choose a package variant for your platform:

| Variant | Package | Where to use it |
| --- | --- | --- |
| MetroFlow · JavaScript | `.mfp` | Android or desktop, using the host's declarative UI renderer. |
| MetroFlow Native | `.jar` | Desktop, using native Compose UI and the UI SDK. |
| MetroFlow Native | `.apk` | Android, using native Compose UI and the UI SDK. |

Open **Settings → Plugins → Installed → Import or update plugin** and select the MetroFlow file. Import the native APK inside MetroFuse; it is a plugin package, not the MetroFuse application installer. Enable the variant you chose, then select its name under **App interface** if several full interfaces are available. Keep just one MetroFlow variant enabled to avoid competing shells. Source code is available for the [JavaScript variant](https://github.com/956tris/MetroFuse-MetroFlow/tree/main/javascript) and [native variant](https://github.com/956tris/MetroFuse-MetroFlow/tree/main/native).

Use **Settings & plugins** in the JavaScript interface, or **Settings** and **Plugins** in the native interface, to open the built-in screens. Settings, Plugins and detail destinations keep MetroFuse's original interface so account configuration, scanning and recovery remain accessible. Use the host's **Back** control to return from those screens to MetroFlow. To change the content source, choose **Use original interface**, select the source through the original Home title, then re-enable the custom interface in Plugins. A MetroFlow package supplies the interface; any provider package and account are configured separately.

## Playback, quality and lyrics

If automatic matching plays the wrong recording, choose **Match track** beside the player's queue and lyrics controls. Search by title or artist, compare the provider, album and duration, then select the recording you want. MetroFuse checks that the provider can resolve it before saving the choice. The queue, playback position and paused state are preserved. Choose **Automatic matching** to clear that song's saved choice.

Automatic fallback uses recording codes when available, then compares title, artist, version and duration. It can try another eligible recording from the same provider after a matched stream fails. Manual choices are saved per song and used again on later plays.

### Spotify metadata and account

The separate [Spotify plugin](https://github.com/956tris/MetroFuse-Spotify/releases) supplies Home, search, collections, library, likes, artist details, lyrics and available Canvas metadata. Import one `.mfp`, desktop `.jar` or Android plugin `.apk` variant and sign in through its account screen. It requires the patched host release; choose an audio provider separately for playback.

The native Spotify plugin waits for the browser to return to the Web Player before capturing its session. Cold native sign-in and saved-session verification have up to 90 seconds for token and account checks.

Its **Report listening history to Spotify** and **Use Spotify Web Player upcoming queue** settings are independent and off by default. Queue sync uses Spotify's actual Web Player queue and preserves the current host track. Reporting sends listening activity back to Spotify. Live account behavior remains unverified in this prerelease and depends on Spotify's private endpoints and account permissions.

Use the player for previous, play/pause, next, seeking and the current queue. Artwork, playback controls and supported lyrics remain host features even when a plugin supplies content. Lyrics availability depends on your enabled sources and the current song.

**Settings → Player and audio → Audio quality** offers **Low**, **Auto** and **High**. This preference asks the playback source for an available quality; the source and original recording determine the actual format. Higher quality can use more data and storage. Local files retain their source format.

The audio-format label reports known metadata for the playing source: codec, original bit depth, sample rate and bitrate. Missing fields are omitted. A lossy source's decoded PCM depth does not establish its original bit depth. The label describes source audio, so it does not guarantee the quality of a Bluetooth connection, cast receiver or physical output device. On Android, the label appears below the artist in the built-in player; tap it for details. It stays hidden while loading or playing through Cast or a remote Device Connect session. Desktop tooltips show exact sample-rate and bitrate values where available.

Android uses playback source metadata when the decoder omits the sample rate or bitrate. For audio-only local or cached files, Android can estimate average bitrate from file size and playback duration.

For offline listening, use local files or audio already downloaded or cached by the app. A song visible in a provider catalog is not necessarily saved for offline playback. Test with **Offline** selected before travelling without a connection.

## Fix common problems

| Problem | What to check |
| --- | --- |
| Plugin file is rejected | Use MFP for a supported platform, JAR for desktop or plugin APK for Android. Check exact API compatibility, native ABI requirements and declared dependencies in the author's release notes. Download the original file again if it is incomplete. |
| Plugin is installed but absent from the Home selector | Enable it and check for Active status. Only plugins supplying a Home frontend appear in that selector; a lyrics or UI plugin may have no Home entry. |
| Provider Library shows no device files | Select Offline. Provider account libraries intentionally show that source's content. |
| Playback fails for a provider song | Check connectivity, account status and provider configuration. Review audio source priority and exclusions. Try a local file in Offline to distinguish a source problem from a player problem. |
| Imported UI does not appear | Enable the plugin, check for errors, then choose its name under App interface and enable custom interfaces if recovery disabled them. |
| Native plugin update requires restart | Close and reopen the app before importing again; native libraries can remain loaded after a plugin is disabled. |
| Local file is missing or will not play | Check audio/folder access and that the original file or drive is still present, then scan again. |
| Custom interface hangs or crashes | Restore the original interface using the recovery steps above, inspect Diagnostics and disable the failing plugin. |

**Settings → Plugins → Advanced** contains provider priority, a search test and recent diagnostics. When reporting a problem, include the MetroFuse version, OS, plugin name/version, package format, exact error and steps to reproduce. Remove credentials and private account data from logs.

Drag a provider's handle to change its priority. Keyboard and accessibility users can use the move actions. Ordering is saved per provider category, including preferences for disabled or temporarily unavailable providers.

## Back up before major changes

Export a backup from Settings before replacing installations or making major library changes. Save it outside the app's private storage. Keep original music and downloaded audio separately; do not assume the database backup includes every audio file. Uninstalling the Android app deletes its private data. See the [installation guide](install/README.md) for package verification and update precautions.
