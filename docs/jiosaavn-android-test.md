# JioSaavn native Android test

Copy `jiosaavn.apk` to your Android device. Open MetroFuse → Settings → Plugins → Installed → Import or update plugin, select the file, then enable JioSaavn. The APK loads inside MetroFuse and shares its player.

Keep **Use JioSaavn's India catalog** enabled. Turn off **Allow public previews when full audio is unavailable** while testing full-song playback.

Search these originals in the main Search page or Advanced → Search installed providers:

| Artist | Songs |
| --- | --- |
| Don Toliver | No Idea; After Party |
| The Weeknd | Blinding Lights; Starboy |
| Travis Scott | goosebumps; SICKO MODE |

Check that results name the original artist, playback advances, and you can pause and seek. Reload the plugin and restart MetroFuse to check persistence. You can import `jiosaavn.mfp` afterward to compare formats; it replaces the same provider and preserves settings.

The native plugin requires Android 7.0 or newer and a compatible MetroFuse host with native plugin support.

If an older MetroFuse build reports `Initialize the native Android platform before loading plugins`, install the updated MetroFuse application APK, restart the app, and enable JioSaavn again. The updated application's startup initializes the native loader before the plugin runtime restores or activates APK plugins.
