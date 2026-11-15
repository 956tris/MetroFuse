# MetroFuse plugin SDK

Reusable plugin UI lives in this module. Plugins own their account screens, provider URLs, navigation rules, labels and session verification. The SDK supplies `WebViewLoginScreen`, Android WebView, desktop WebView and the generic Windows WebView2 helper. The application hosts `NativeLoginUi` contributions through `LoginUiContext` and handles owner-scoped credential persistence.

Desktop login dialogs retain the host's Material theme and surface colors. On Windows, the SDK writes the browser request to a temporary UTF-8 JSON file and passes its path to the helper; JSON is never placed in Windows command-line arguments. Invalid requests return a failure status to the retry UI. `:plugin-sdk:desktopTest` checks this transport against the published helper without opening a browser or signing in.

Native plugins compile against both `plugin-api` and `plugin-sdk` JARs with the host's Kotlin and Compose versions. Keep both dependencies compile-only in production: their classes are supplied by the host and must never be bundled into a plugin JAR or APK. JavaScript plugins return `{request, labels}` from `host.login`'s `ui` callback to contribute the SDK's browser screen.

Export the JARs with `:plugin-api:desktopJar :plugin-sdk:desktopJar`. Windows packaging needs .NET SDK 10 and restores Microsoft.Web.WebView2 1.0.4314.35. Use `-PdotnetExecutable=<path>` when .NET is outside PATH. The SDK JAR embeds the helper as a resource, so installed native plugins use the same SDK browser as MFP plugins.

The Windows browser uses a private profile beneath its SDK-owned temporary directory, cleaned after exit or cancellation. Android uses the process WebView cookie store and blocks screenshots. Linux checks its WebKitGTK libraries before composing the browser. Session candidates are delivered through the plugin's activation-scoped callback; plugins must verify their service account before accepting them.

See the public [browser sign-in guide](https://github.com/956tris/MetroFuse-Plugin-Docs/blob/main/docs/browser-login.md) and separate [YouTube Music reference plugin](https://github.com/956tris/MetroFuse-YouTube-Music).

See [UI plugins](ui-plugins.md) for the application shell, player UI, native and JavaScript APIs, and recovery.
