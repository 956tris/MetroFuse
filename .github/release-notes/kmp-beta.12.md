# MetroFuse 10.0 Beta 12

- Fixed YouTube Music Video Canvas (`artwork.video-canvas/v1`) on Android and Windows to support SABR-only video streams alongside direct URLs, preserve stream headers and clip timing, and resolve plugin track identities reliably across queue/database items.
- Kept Apple Music as the only hardcoded built-in animated artwork source in MetroFuse core (with third-party plugin Canvas support preserved via `artwork.canvas/v1`).
- Enabled preferring high-resolution Apple Music static album artwork globally by default across the player, album pages, queue, search, and home cards (with a toggle in Artwork settings).

Install the signed Android APK over your current beta. On Windows, extract the x64 ZIP and run the launcher.
