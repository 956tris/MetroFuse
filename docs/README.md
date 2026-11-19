# MetroFuse documentation

Start with the [user guide](user-guide.md) for offline music, installing and updating plugins, source selection, accounts, custom interfaces, playback quality and troubleshooting.

The public [plugin authoring guide](https://github.com/956tris/MetroFuse-Plugin-Docs) covers API 1.1.0, MFP/JAR/APK development, Home/login, packaging, installation and troubleshooting.

Android and desktop share the SDK's [native host API rules](../plugin-sdk/native-host-api.pro). KMP release jobs check packaged Ktor/IO class, method and field definitions against the resolved host libraries before upload. Native plugins must use matching dependency versions and keep host-provided libraries compile-only.

The application retains its [README](../README.md) and [installation index](install/README.md). Technical references from before documentation cleanup remain available at [the previous source revision](https://github.com/956tris/MetroFuse/blob/8860bae74198ae810ddd8282d602a2698d7f33fe/docs). Local development notes stay outside Git tracking.

Required legal and build inputs remain available as [translation attribution](translation-attribution), [fixture provenance](test-fixtures), [release history](changelog) and [security policy](../SECURITY). Provider packages carry their SOURCE files and license notices.

## Crash reporting configuration

Crash reporting is disabled unless `SENTRY_DSN` is set in the build environment. Android, desktop and iOS embed this DSN from the shared build configuration and still require the user's reporting consent. MetroFuse has no default Sentry project.

For GitHub Actions, configure the repository variables `SENTRY_DSN`, `SENTRY_ORG` and `SENTRY_PROJECT` for MetroFuse's own project, and the `SENTRY_AUTH_TOKEN` secret for symbol uploads. Release uploads and finalization are skipped when those variables are absent. Leave them unset to build without remote crash reporting.

Source bundles include the host runtime and SDK so plugin failure frames can show host source context. Set the optional `SENTRY_REPOSITORY` repository variable after connecting the GitHub repository in Sentry to associate release commits. Release finalization works without that integration.

MetroFuse uses the shared `metrofuse` Sentry project in the `metrofuse` organization for Android, desktop and iOS. The host reports plugin activation, UI, login and provider failures when the user enables crash reporting. Reports identify the plugin, version, runtime and operation. They include exception types and stack frames, but exclude raw plugin exception messages and request or credential payloads. Repeated failures of the same operation and exception type are limited to one report per plugin per minute. Normal coroutine cancellation is not reported.

## Spicy Lyrics

Set `SPICY_LYRICS_CLIENT_KEY` in the build environment, Gradle properties or `local.properties` to the application's publishable key. Enable **Allow requests with no Origin header** for that key in the Spicy Lyrics dashboard for native calls. The KMP beta workflow reads the key from a repository Actions variable of the same name.

Without a configured publishable key, lookup skips Spicy Lyrics and continues to the next provider.

## Rejected features

The following list preserves the upstream issue-triage policy. Do not apply it to bugs in existing functionality. MetroFuse's existing plugin architecture remains part of this fork.

- Liquid Glass UI.
- Other streaming services as music-playback backends.
- Google Play Store publication or distribution support.
- Comment sections.
- YouTube Shorts.
- Music alarms.
- New-music notifications.
- Multiple queues.
- Listen Together profile pictures taken directly from local storage without uploading them.
- Tizen TV support.
- A web/browser client.
- Audio visualizers.
- Custom app icons.

Playlist imports, artwork and scrobbling are separate from streaming backends.

## Writing to maintainers

Search open and closed issues and pull requests before filing a duplicate. Keep reports factual and include the app version, OS, reproduction steps and relevant diagnostics. Remove credentials and private account data. Ask a maintainer before proposing a listed rejected feature; playlist imports, artwork and scrobbling are separate from streaming backends.
