# Built-in offline player

MetroFuse's Windows and Android hosts use the local library by default. A fresh installation opens Offline Home with zero installed plugins. Local playback does not need an account, a provider or a network connection. Android retains Media3 playback and desktop retains the bundled FFmpeg player.

## Windows

The app scans the operating system's Music folder at startup. Use the Library scan action or Settings' Local music section to scan again, add a music folder, or stop scanning a folder. Selected folders persist across restarts. Scans recurse into subfolders and read supported audio files' tags and duration with the bundled FFmpeg libraries. When tags are missing, the filename supplies the track title.

Removing a scan folder stops future scans of that folder; it does not delete its files or remove saved likes, playlists or history. A file must remain readable at its original location to play. Move a file and scan its new folder to import its new location.

## Android

Use Library's Local music action or Settings → Storage → Local music. Android asks for audio access on the first explicit scan. On Android 13 and later this is READ_MEDIA_AUDIO; older versions use READ_EXTERNAL_STORAGE. A denied permission can be enabled from app settings. Once permission is granted, startup automatically refreshes the local library.

The scanner reads audio indexed as music by Android's MediaStore, including titles, artists, albums, durations, track numbers and album artwork. Files that Android has not indexed cannot appear in this scan. Songs use MediaStore content URIs, which Media3 opens directly. The app checks file access before preparing playback.

## Library and rescans

Scans import songs, albums and artists into the existing local database. Stable file/content URIs identify songs. A rescan refreshes tags while preserving custom titles and artists, likes, listening history, and playlist membership. Rescans do not delete library records when a drive is disconnected or Android temporarily denies access.

Local songs work through Library, Search, album/artist pages and local playlists. Local source resolution runs before provider plugins. No installed plugin is required for local playback. The Home title opens a frontend selector. Offline is the default for Home, Search and Library, including while internet is connected. It permits only local, downloaded or cached audio and stops a network stream when selected. Enabled catalog plugins appear as other frontends. Each selected frontend uses its own catalog search and saved provider content on Home; the current plugin API does not provide a recommendation feed. Selecting a frontend does not change audio provider priorities, which remain in the playback menus.

## Optional YouTube Music packages

See `dist/youtube-music-plugin/README.md` for native JAR/APK and JavaScript MFP installation instructions, package verification and supported playback scope. A native package is imported separately: MFPs contain JavaScript and cannot embed native packages. Online playback requires a usable provider and internet access.

## Verification

`LocalLibraryImporterTest` covers deduplication, rescans preserving user state, database restart persistence, artist/album search and track order, URI mapping, invalid scan rollback, and album identity. `PluginPlaybackIntegrationTest` covers local playback through the real desktop FFmpeg path without a provider. `OfflineHomeTest` checks saved Home content, mode switching and refresh completion. `OfflineLibraryTest` checks library filtering, search pagination and recent-search frontend switching. Platform scanner and build results are recorded with the delivered test artifacts. Android audio permission and device playback require testing on an Android device; building the APK does not verify that device flow.
