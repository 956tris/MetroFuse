# MetroFuse 10.0 Beta 9

- Added Plugin SDK and host support for hooking the player's video button (`playback.video/v1`) with up to 4K (`2160p`) direct and SABR video streams (`PluginSabrTransport` video bootstrap and adaptive height variants).
- Updated Android video mode handling so active music plugins can serve video directly from the player's video button even when no built-in music video counterpart exists.
- Fixed HLS playback and seek timing in the host and added support for dotted service cookie names in plugin sign-in.

Install the signed Android APK over your current beta. On Windows, extract the x64 ZIP and run the launcher.
