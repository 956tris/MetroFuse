# UI plugins

MetroFuse keeps provider and UI registration in the same activation lifecycle. The engine owns the registry, ordering and QuickJS runtime. Both applications render native Compose contributions and declarative JavaScript trees through the shared UI host. Playback commands still go to the existing host player and its queue.

## Extension points

| Point | Surface |
| --- | --- |
| `ui.app` | Application shell |
| `ui.navigation` | Navigation alongside the active built-in page |
| `ui.home`, `ui.search`, `ui.library`, `ui.settings` | Built-in destinations |
| `ui.player`, `ui.fullscreen`, `ui.controls` | Player surfaces and primary controls |
| `ui.overlay` | Content layered above the shell |
| `screen.<name>` | Plugin page with an owner-qualified route |
| `component.<name>` | Native plugin slot offered through `UiContext.extensions.Render` |

The Plugins screen retains its built-in recovery controls. Choose a full interface there. For slots with more than one replacement, an explicit available selection wins. Otherwise dependency order, point order, global order, priority and plugin ID determine the winner. `BEFORE` and `AFTER` contributions coexist with the replacement or an original surface. The same owner, point and layer identify a single registration. Reloading, disabling and uninstalling withdraw the activation's contributions.

## Native Kotlin

Use the same compile-only Kotlin, Compose and MetroFuse API dependencies as other native plugins. Package a JAR for desktop or APK for Android using the existing native tooling. A partial override can use the same player used by the stock controls:

```kotlin
class ControlsPlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        context.registerUi("ui.controls", object : NativeScreen {
            @Composable
            override fun Content(context: UiContext) {
                val playback by context.player.state.collectAsState()
                Row {
                    Button(onClick = context.player::previous) { Text("Previous") }
                    Button(onClick = context.player::toggle) {
                        Text(if (playback.playing) "Pause" else "Play")
                    }
                    Button(onClick = context.player::next) { Text("Next") }
                }
            }
        }, Layer.REPLACE)
    }
}
```

To replace the shell, register `ui.app` and render a Compose layout occupying the available window. The host leaves system window controls and its original-interface escape control available. Use `UiContext.navigate` for built-in destinations and `plugin:<owner>:screen.<name>` routes. `context.extensions as? UiEnvironment` provides the destination flow, plugin state flow and paged library track flows. The player state includes track, queue, playback position in milliseconds and playing status. Check `player.capabilities` before using optional commands. Reuse `PluginContext.callService` for versioned host and plugin services.

## JavaScript

The [full-shell example](../plugins/javascript/ui-shell) can be packed with the existing `tooling/pack-mfp.ps1` script. Import and enable it manually. Change `ui.app` to `ui.player` to contribute the control surface only.

- `host.ui(point, tree, layer='REPLACE')` registers or replaces a complete tree. `host.patch(id, props)` updates props by stable node ID. IDs must be unique within a tree.
- Trees use `{id, type, props, children}`. Groups are `Box`, `Column`, `Row`, `Section`, `List`, and `LazyColumn`. Leaf controls include `Text`, `Button`, `Switch`, `Slider`, `TextField`, `Spacer` and `Image`. Use `Dialog` for modal content and `AnimatedVisibility` for show/hide transitions.
- Pass string props. Layout props include `fillSize`, `fillWidth`, `width`, `height`, `padding`, `spacing` and `background`. Colors are `#RRGGBB` or `#AARRGGBB`. Text accepts `fontSize` and `color`. Images reference packaged `assets/` files using `asset`.
- `host.action(id, handler)` binds events referenced by a node's `action` prop. The handler receives a string value and an optional target. Slider values range from 0 to 1 and dispatch when the user finishes seeking.
- `host.on(event, callback)` returns an unsubscribe function. UI events include `playback_changed`, `navigation_changed`, `plugins_changed` and `library_changed`. Playback events are sampled at 250 ms. The library event carries the first 100 tracks. Use `host.library(offset, limit)` for pages up to 200 tracks. `host.playerState()`, `host.destination()` and `host.pluginStates()` expose snapshots.
- Use `host.player(action, value)` for existing player commands, `host.playTracks` or `host.addToQueue` to edit the queue, `host.navigate(destination)` for navigation, and `host.call(name, payload)` for documented services.
- Compose renders the validated trees. QuickJS callbacks run on a background dispatcher with the existing runtime deadlines and memory limits. Reusable JavaScript components can be ordinary functions returning nodes or imported modules.

## Recovery

The original interface is part of the application. In a custom shell use **Use original interface** to turn off custom rendering. Open Plugins to review errors, disable an owner or re-enable custom interfaces. On Windows, launch with `--original-ui`. On Android, a launch intent with the boolean extra `metrofuse.original_ui=true` requests the original presentation.

A composition or measurement failure caught while mounting a contribution restores the original UI and records a failure. Two recorded failures quarantine the owner. A mounted UI watchdog records sustained main-thread unresponsiveness for the next launch. Recognized plugin failures that reach the platform crash handler also leave recovery information. Startup recovery uses the original presentation after a recorded UI failure. The ordinary plugin startup marker also handles unclean lifecycle operations.

A native plugin runs inside the application process and can prevent the main loop from returning. A native crash or deadlock may require a process restart. The watchdog cannot identify the precise cause of a global main-thread stall, so it records mounted UI owners. Fatal errors that bypass both the watchdog and platform crash handler can require launching the original interface explicitly.

Examples are source only. A fresh installation has zero installed third-party plugins.
