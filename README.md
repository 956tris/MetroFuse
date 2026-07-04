<div align="center">

<img src="fastlane/metadata/android/en-US/images/icon.png" alt="MetroFuse app icon" width="180" />

# MetroFuse

### A fused Android music client for streaming, discovery, playlists, lyrics, and provider fallback.

<br/>

[![Latest release](https://img.shields.io/badge/releases-GitHub-181717?style=for-the-badge&logo=github&labelColor=0d1117)](releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge&labelColor=0d1117)](LICENSE)
[![Downloads](https://img.shields.io/github/downloads/956tris/MetroFuse/total?style=for-the-badge&labelColor=0d1117)](releases)
[![Android](https://img.shields.io/badge/platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=0d1117)](#download)
[![Discord](https://img.shields.io/badge/Discord-Join-5865F2?style=for-the-badge&logo=discord&logoColor=white&labelColor=0d1117)](https://discord.gg/K3bJsTU8v) join ts 💔
<br/>

[**Download**](#download) - [**Features**](#features) - [**Screenshots**](#screenshots) - [**Credits**](#credits) - [**Disclaimer**](#disclaimer)

</div>

> [!WARNING]
> MetroFuse connects to third-party services selected by the user. Availability, quality, catalog coverage, login behavior, and regional access can change at any time and may require your own account, VPN, proxy, or provider access.
> MetroFuse also is not Responsible for any actions taken by these third-parties to the users accounts as this fork ovs dont have a ton of users to test how these services handle their policies :D 

Additionally this unoffical fork of Metrolist is completely different backend and frontend wise dont ask the metrolist devs for help with this fork.

---

## What Is MetroFuse?

MetroFuse is a fork of Metrolist focused on combining multiple music frontpages and playback providers in one Android app. It keeps the familiar Material 3 player, library, lyrics, queue, widgets, and playlist tools while adding source switching for different discovery and playback workflows.

The default playback path is Deezer-first, with other enabled providers used only when the primary source cannot produce a playable stream.
Im back :D
---

## Features

| Playback | Discovery |
| --- | --- |
| Qobuz-first playback routing | YouTube Music home feed |
| Provider fallback when a stream misses | Spotify-style personalized frontpage |
| Background playback | TIDAL-style personalized frontpage |
| Downloads and cache for offline use | Search songs, albums, artists, videos, and playlists |
| Skip silence and sleep timer | Open external playlists inside MetroFuse |

| Audio | Library |
| --- | --- |
| Format, bitrate, and sample-rate display when available | Full library management |
| Audio normalization | Local playlists |
| Tempo and pitch control | Import playlists |
| Equalizer | Reorder songs in playlist or queue |
| Spotify Canvas support | Lyrics, translation, and synced lyrics |

---

## Screenshots

<div align="center">

<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_1.png" alt="Home screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_2.png" alt="Artist screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_3.png" alt="Recognize music screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_4.png" alt="Listen together screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_5.png" alt="Player screen" width="30%" />
<img src="fastlane/metadata/android/en-US/images/screenshots/screenshot_6.png" alt="Lyrics screen" width="30%" />

</div>

---

## Download

Grab the latest APK from the [GitHub releases page](releases/latest). Use a release build for normal installs; debug builds are only for local testing.

---

## Build

```bash
./gradlew :app:assembleFossDebug
```

Release builds require the project signing setup used by the maintainer.

---

## Credits

MetroFuse is built on top of Metrolist and stands on a pile of excellent open-source Android music work.

Special thanks to:

- [Metrolist](https://github.com/MetrolistGroup/Metrolist)
- [InnerTune](https://github.com/z-huang/InnerTune)
- [OuterTune](https://github.com/DD3Boh/OuterTune)
- [Kizzy](https://github.com/dead8309/Kizzy)
- [Better Lyrics](https://better-lyrics.boidu.dev)
- [MusicRecognizer](https://github.com/aleksey-saenko/MusicRecognizer)
- [Canvas Api](https://github.com/Paxsenix0/Spotify-Canvas-API)


## License

MetroFuse is licensed under GPL-3.0. See [LICENSE](LICENSE) for details.

---

## Disclaimer

MetroFuse is an independent, unofficial project. It is not affiliated with, funded, authorized, endorsed by, or associated with YouTube, Google, Qobuz, Spotify, TIDAL, Metrolist Group, or any of their affiliates.

All trademarks, service marks, catalogs, artwork, metadata, and content remain the property of their respective owners. Users are responsible for how they access third-party services and for following the rules, rights, and availability requirements of those services in their region.
