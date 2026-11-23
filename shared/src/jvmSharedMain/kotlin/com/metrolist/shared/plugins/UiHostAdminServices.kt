package com.metrolist.shared.plugins

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.ThemeMode
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.Layer
import dev.metrofuse.api.isSupportedApiVersion
import dev.metrofuse.runtime.MarketplaceEntry
import dev.metrofuse.runtime.Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun Runtime.registerHostAdminServices(platformName: String) {
    fun register(
        name: String,
        handle: suspend (JsonObject) -> JsonObject,
    ) {
        registry.service(
            "builtin",
            name,
            object : JsonService {
                override suspend fun call(request: JsonObject) = handle(request)
            },
        )
    }

    register("ui.settings/v1") { request ->
        val action = (request["action"] as? JsonPrimitive)?.contentOrNull ?: "snapshot"
        when (action) {
            "set" -> {
                val key = (request["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val raw = request["value"]
                withContext(Dispatchers.Main.immediate) {
                    applyHostSetting(key, raw)
                }
            }

            "openOriginal", "useOriginalUi" -> {
                setUiSafeMode(true)
            }
        }
        hostSettingsSnapshot(platformName, this)
    }

    register("ui.plugins/v1") { request ->
        val action = (request["action"] as? JsonPrimitive)?.contentOrNull ?: "snapshot"
        when (action) {
            "enable" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isNotBlank()) enable(id)
            }

            "disable" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isNotBlank()) disable(id)
            }

            "reload" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isNotBlank()) hotReplace(id)
            }

            "uninstall" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isNotBlank()) uninstall(id)
            }

            "setUiApp" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isBlank()) {
                    setUiSafeMode(true)
                } else {
                    registry.select("ui.app", id)
                    setUiSafeMode(false)
                }
            }

            "setSafeMode" -> {
                val enabled = (request["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                setUiSafeMode(enabled)
            }

            "setPluginSetting" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val key = (request["key"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val value = request["value"] ?: JsonNull
                if (id.isNotBlank() && key.isNotBlank()) {
                    settingsFor(id).set(key, value)
                }
            }

            "setMarketplaceUrl" -> {
                val url = (request["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                withContext(Dispatchers.Main.immediate) {
                    Settings.shared.pluginMarketplaceUrl = url
                }
            }

            "fetchMarketplace" -> {
                val url =
                    ((request["url"] as? JsonPrimitive)?.contentOrNull ?: Settings.shared.pluginMarketplaceUrl)
                        .trim()
                if (url.isNotBlank()) {
                    sharedMarketplaceClient.fetch(url)
                    withContext(Dispatchers.Main.immediate) {
                        Settings.shared.pluginMarketplaceUrl = url
                    }
                }
            }

            "installMarketplace" -> {
                val id = (request["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val url =
                    ((request["url"] as? JsonPrimitive)?.contentOrNull ?: Settings.shared.pluginMarketplaceUrl)
                        .trim()
                val entries =
                    sharedMarketplaceClient.cached(url)
                        ?: if (url.isNotBlank()) sharedMarketplaceClient.fetch(url) else emptyList()
                val entry = entries.firstOrNull { it.id == id }
                requireNotNull(entry) { "Marketplace plugin not found: $id" }
                sharedMarketplaceClient.install(this, entry)
            }
        }
        hostPluginsSnapshot(platformName, this)
    }
}

private fun applyHostSetting(
    key: String,
    raw: kotlinx.serialization.json.JsonElement?,
) {
    val primitive = raw as? JsonPrimitive ?: return
    val text = primitive.contentOrNull
    val bool = primitive.booleanOrNull
    val num = primitive.doubleOrNull
    when (key) {
        "audio.quality" -> {
            if (text != null) {
                AudioQuality.valueOf(text)
                Settings.shared.audioQuality = text
            }
        }

        "audio.normalization" -> if (bool != null) Settings.shared.audioNormalization = bool
        "audio.skipSilence" -> if (bool != null) Settings.shared.skipSilence = bool
        "audio.crossfade" -> if (bool != null) Settings.shared.crossfadeEnabled = bool
        "playback.persistentQueue" -> if (bool != null) Settings.shared.persistentQueue = bool
        "playback.keepPausedOnSongChange" -> if (bool != null) Settings.shared.keepPausedOnSongChange = bool
        "appearance.themeMode" -> {
            if (text != null) Settings.shared.themeMode = ThemeMode.valueOf(text)
        }

        "appearance.dynamicTheme" -> if (bool != null) Settings.shared.dynamicTheme = bool
        "appearance.pureBlack" -> if (bool != null) Settings.shared.autoPureBlack = bool
        "lyrics.autoScroll" -> if (bool != null) Settings.shared.lyricsAutoScroll = bool
        "lyrics.clickToSeek" -> if (bool != null) Settings.shared.lyricsClickToSeek = bool
        "lyrics.preferWordSynced" -> if (bool != null) Settings.shared.preferWordSyncedLyrics = bool
        "lyrics.showTranslations" -> if (bool != null) Settings.shared.lyricsShowTranslations = bool
        "lyrics.textSize" -> if (num != null) Settings.shared.lyricsTextSize = num.toFloat().coerceIn(18f, 56f)
        "content.language" -> if (text != null) Settings.shared.contentLanguage = text.ifBlank { "SYSTEM" }
        "content.country" -> if (text != null) Settings.shared.contentCountry = text.ifBlank { "SYSTEM" }
        "content.hideExplicit" -> if (bool != null) Settings.shared.hideExplicit = bool
        "plugins.marketplaceUrl" -> if (text != null) Settings.shared.pluginMarketplaceUrl = text.trim()
    }
}

private fun hostSettingsSnapshot(
    platformName: String,
    runtime: Runtime,
): JsonObject {
    fun selectItem(
        key: String,
        title: String,
        category: String,
        value: String,
        options: List<String>,
    ) = buildJsonObject {
        put("key", key)
        put("title", title)
        put("category", category)
        put("type", "select")
        put("value", value)
        put("options", buildJsonArray { options.forEach { add(JsonPrimitive(it)) } })
    }

    fun boolItem(
        key: String,
        title: String,
        category: String,
        value: Boolean,
    ) = buildJsonObject {
        put("key", key)
        put("title", title)
        put("category", category)
        put("type", "boolean")
        put("value", value)
    }

    fun stringItem(
        key: String,
        title: String,
        category: String,
        value: String,
    ) = buildJsonObject {
        put("key", key)
        put("title", title)
        put("category", category)
        put("type", "string")
        put("value", value)
    }

    val s = Settings.shared
    return buildJsonObject {
        put("platform", platformName)
        put("uiSafeMode", runtime.uiSafeMode.value)
        put("recovery", runtime.recovery.value)
        put(
            "settings",
            buildJsonArray {
                add(selectItem("audio.quality", "Audio quality", "Audio & Playback", s.audioQuality, listOf("AUTO", "HIGH", "LOW")))
                add(boolItem("audio.normalization", "Audio normalization", "Audio & Playback", s.audioNormalization))
                add(boolItem("audio.skipSilence", "Skip silence", "Audio & Playback", s.skipSilence))
                add(boolItem("audio.crossfade", "Crossfade", "Audio & Playback", s.crossfadeEnabled))
                add(boolItem("playback.persistentQueue", "Persistent queue", "Audio & Playback", s.persistentQueue))
                add(boolItem("playback.keepPausedOnSongChange", "Keep paused on track change", "Audio & Playback", s.keepPausedOnSongChange))
                add(selectItem("appearance.themeMode", "Theme mode", "Appearance", s.themeMode.name, ThemeMode.entries.map { it.name }))
                add(boolItem("appearance.dynamicTheme", "Dynamic colors from artwork", "Appearance", s.dynamicTheme))
                add(boolItem("appearance.pureBlack", "Pure black dark mode", "Appearance", s.autoPureBlack))
                add(boolItem("lyrics.autoScroll", "Auto-scroll lyrics", "Lyrics", s.lyricsAutoScroll))
                add(boolItem("lyrics.clickToSeek", "Tap lyrics to seek", "Lyrics", s.lyricsClickToSeek))
                add(boolItem("lyrics.preferWordSynced", "Prefer word-synced lyrics", "Lyrics", s.preferWordSyncedLyrics))
                add(boolItem("lyrics.showTranslations", "Show lyric translations", "Lyrics", s.lyricsShowTranslations))
                add(stringItem("content.language", "Content language", "Content & Region", s.contentLanguage))
                add(stringItem("content.country", "Content country", "Content & Region", s.contentCountry))
                add(boolItem("content.hideExplicit", "Hide explicit tracks", "Content & Region", s.hideExplicit))
                add(stringItem("plugins.marketplaceUrl", "Marketplace registry URL", "Plugins", s.pluginMarketplaceUrl))
            },
        )
    }
}

private suspend fun hostPluginsSnapshot(
    platformName: String,
    runtime: Runtime,
): JsonObject {
    val plugins = runtime.plugins.value
    val registryState = runtime.registry.state.value
    val logins = runtime.logins.states.value
    val activeUiApp = registryState.selections["ui.app"] ?: registryState.ui.firstOrNull { it.point == "ui.app" && it.layer == Layer.REPLACE }?.owner.orEmpty()
    val uiApps = registryState.ui.filter { it.point == "ui.app" && it.layer == Layer.REPLACE }
    val marketplaceUrl = Settings.shared.pluginMarketplaceUrl.trim()
    val cachedMarketplace = marketplaceUrl.takeIf { it.isNotBlank() }?.let { sharedMarketplaceClient.cached(it) }.orEmpty()
    val installedVersions = plugins.associate { it.manifest.id to it.manifest.version }

    val pluginArray = buildJsonArray {
        plugins.forEach { status ->
            val manifest = status.manifest
            val settingsStore = runCatching { runtime.settingsFor(manifest.id).values.value }.getOrDefault(emptyMap())
            val loginState = logins[manifest.id]
            add(
                buildJsonObject {
                    put("id", manifest.id)
                    put("name", manifest.name)
                    put("version", manifest.version)
                    put("author", manifest.author)
                    put("description", manifest.description)
                    put("runtime", manifest.runtime)
                    put("state", status.state)
                    put("phase", status.phase.name)
                    put("enabled", status.state == "Enabled")
                    status.error?.let { put("error", it) }
                    put("capabilities", buildJsonArray { manifest.capabilities.forEach { add(JsonPrimitive(it)) } })
                    put("hasLogin", "login" in manifest.capabilities || loginState != null)
                    put("loggedIn", loginState?.account?.loggedIn == true)
                    loginState?.account?.accountName?.takeIf { it.isNotBlank() }?.let { put("accountName", it) }
                    put(
                        "settings",
                        buildJsonArray {
                            manifest.settings.forEach { def ->
                                val currentValue = settingsStore[def.key] ?: def.default
                                add(
                                    buildJsonObject {
                                        put("key", def.key)
                                        put("title", def.title)
                                        put("type", def.type.name)
                                        put("value", currentValue)
                                        put("options", buildJsonArray { def.options.forEach { add(JsonPrimitive(it)) } })
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    val uiAppsArray = buildJsonArray {
        uiApps.forEach { entry ->
            val name = plugins.firstOrNull { it.manifest.id == entry.owner }?.manifest?.name ?: entry.owner
            add(
                buildJsonObject {
                    put("id", entry.owner)
                    put("name", name)
                    put("active", !runtime.uiSafeMode.value && entry.owner == activeUiApp)
                },
            )
        }
    }

    val marketplaceArray = buildJsonArray {
        cachedMarketplace.forEach { entry ->
            val installedVer = installedVersions[entry.id]
            add(entry.toJson(platformName, installedVer))
        }
    }

    return buildJsonObject {
        put("platform", platformName)
        put("uiSafeMode", runtime.uiSafeMode.value)
        put("recovery", runtime.recovery.value)
        put("marketplaceUrl", marketplaceUrl)
        put("plugins", pluginArray)
        put("uiApps", uiAppsArray)
        put("marketplace", marketplaceArray)
    }
}

private fun MarketplaceEntry.toJson(
    platformName: String,
    installedVersion: String?,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("name", name)
        put("author", author)
        put("description", description)
        put("version", version)
        put("pluginApiVersion", pluginApiVersion)
        put("releaseChannel", releaseChannel)
        put("compatible", isSupportedApiVersion(pluginApiVersion) && supports(platformName))
        installedVersion?.let { put("installedVersion", it) }
        sourceUrl?.let { put("sourceUrl", it) }
        put("categories", buildJsonArray { displayCategories.forEach { add(JsonPrimitive(it)) } })
    }
