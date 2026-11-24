# MetroFuse 10.0 Beta 11

- Fixed YouTube Music Video Canvas on Android and Windows so it displays in the same immersive, muted, looping player as Spotify Canvas.
- Canvas now uses the YouTube Music plugin's own toggle and works independently of the video button and the plugin supplying the track's audio. No Spotify login is required for YouTube Music Canvas.
- Preserved video stream headers and clip timing, refreshed Canvas when the plugin toggle changes, and kept fallback to Spotify Canvas or animated album artwork when a music video is unavailable.
- Fixed Windows package generation in CI and added regression checks for Canvas playback metadata, source fallback, toggle changes, and artwork handoff.

Install the signed Android APK over your current beta. On Windows, extract the x64 ZIP and run the launcher.
