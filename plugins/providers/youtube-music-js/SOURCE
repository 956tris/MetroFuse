# JavaScript YouTube Music provider

This optional JavaScript implementation is GPL-3.0. Catalog renderer parsing is adapted from MetroList's `YouTubeItemParser` and `YouTubeSearchOperations` at commit `e97197786c2a7ad56279cc9bb4ce3233ef6c99ff`. The bootstrap, HTTP bridge, direct stream selection and limited signature interpreter were written for MetroFuse's public API 1.1.0. No remote player code is evaluated, and the archive contains no native executable payload.

The plugin reads public YouTube Music `sw.js_data` bootstrap configuration at activation's first service request. It validates the returned WEB_REMIX client tuple rather than inventing a client version or changing cookie consent. It uses a separate unauthenticated session and respects explicit plugin content-language/country settings. With empty settings it retains the locale supplied by the public service response. It does not use the host's account, cookie or built-in YouTube resolver. Its alternate unauthenticated ANDROID_VR player profile is taken from [InnerTubeX v0.11.7](https://github.com/MetrolistGroup/innertubex/blob/v0.11.7/src/commonMain/kotlin/com/metrolist/innertubex/models/YouTubeClient.kt), also GPL-3.0.

The universal `.mfp` and the native `.jar`/`.apk` implementations use the same `metrofuse.youtube-music` owner so provider track IDs stay stable. Import one implementation for your platform. The universal package can search and browse the public catalog and resolve audio formats with direct CDN URLs. For signature-cipher formats it recognizes only a single unambiguous sequence of reverse, splice and swap operations; unsupported shapes return `SIGNATURE_TRANSFORM_UNAVAILABLE`. Streams requiring a throttling `n` transform return `N_TRANSFORM_UNAVAILABLE`. Native InnerTubeX/QuackJS provides broader extraction. The JavaScript package does not provide SABR, accounts or music video playback.

Both implementations currently expose the first search/browse response. Remote continuations and account parity are not implemented. Large responses exceeding the host's 2 MiB HTTP limit fail explicitly. Service availability and successful CDN playback need separate live verification.

Build from the repository root:

```powershell
& .\tooling\pack-mfp.ps1 -Source plugins/providers/youtube-music-js -Output dist/youtube-music-plugin/MetroFuse-YouTube-Music.mfp
node plugins/providers/youtube-music/tests/javascript.mjs
```

The synthetic fixture in the native provider's desktop test resources is shared by native and JavaScript parser checks. It contains no captured account response. Node fixture checks establish parsing, ID ownership, quality selection and supported/unsupported signature behavior; they do not establish live service or Android playback.
