package com.metrolist.music.discordrpc

import com.metrolist.music.discordrpc.entities.Activity
import com.metrolist.music.discordrpc.entities.Assets
import com.metrolist.music.discordrpc.entities.Button
import com.metrolist.music.discordrpc.entities.Metadata
import com.metrolist.music.discordrpc.entities.Timestamps
import com.metrolist.music.discordrpc.entities.Presence
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject
import timber.log.Timber

enum class ActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    COMPETING(5),
}

class DiscordRpcConnection(
    private val token: String,
    os: String = "Android",
    browser: String = "Discord Android",
    device: String = "Generic Android Device",
    private val userAgent: String = "Discord-Android/314013;RNA",
    private val superPropertiesBase64: String? = null,
) {
    private val tag = "DiscordRpc"
    private val gateway = GatewayWebSocket(token, os, browser, device)
    private val httpClient = HttpClient()

    fun isRunning(): Boolean = gateway.isSessionEstablished()

    fun connect() {
        Timber.tag(tag).i("connect() called")
        gateway.connect()
    }

    suspend fun setActivity(
        name: String?,
        type: ActivityType = ActivityType.LISTENING,
        state: String? = null,
        details: String? = null,
        timestamps: Timestamps? = null,
        largeImage: String? = null,
        largeImageFallback: String? = null,
        largeText: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        buttons: List<Button>? = null,
        status: String = "online",
        since: Long? = null,
        applicationId: String? = null,
    ) {
        Timber.tag(tag).i("setActivity: type=$type state=$state details=$details buttons=${buttons?.size}")

        if (!isRunning()) {
            Timber.tag(tag).d("setActivity: gateway not running, connecting...")
            gateway.connect()
        }

        val resolvedLargeImage = largeImage?.let {
            Timber.tag(tag).v("Resolving large image: $it")
            resolveImage(it, largeImageFallback).also { result ->
                Timber.tag(tag).v("Large image resolved: $result")
            }
        }
        val resolvedSmallImage = smallImage?.let {
            Timber.tag(tag).v("Resolving small image: $it")
            resolveImage(it).also { result ->
                Timber.tag(tag).v("Small image resolved: $result")
            }
        }

        val buttonLabels = buttons?.map { it.label }?.takeIf { it.isNotEmpty() }
        val buttonUrls = buttons?.map { it.url }?.takeIf { it.isNotEmpty() }

        Timber.tag(tag).d("Sending presence update to gateway...")
        gateway.updatePresence(
            Presence(
                activities = listOf(
                    Activity(
                        name = name ?: "Metrolist",
                        type = type.value,
                        applicationId = applicationId,
                        state = state,
                        details = details,
                        timestamps = timestamps,
                        assets = if (resolvedLargeImage != null || resolvedSmallImage != null) {
                            Assets(
                                largeImage = resolvedLargeImage,
                                largeText = largeText,
                                smallImage = resolvedSmallImage,
                                smallText = smallText,
                            )
                        } else null,
                        buttons = buttonLabels,
                        metadata = buttonUrls?.let { Metadata(buttonUrls = it) },
                    ),
                ),
                since = since,
                status = status,
                afk = false,
            ),
        )
        Timber.tag(tag).i("setActivity completed")
    }

    suspend fun clearActivity(status: String = "online") {
        if (isRunning()) {
            Timber.tag(tag).i("Clearing activity")
            gateway.clearPresence()
        }
    }

    suspend fun close() {
        Timber.tag(tag).i("close() called")
        clearActivity()
        gateway.close()
        httpClient.close()
    }

    fun closeDirect() {
        Timber.tag(tag).i("closeDirect() called")
        gateway.close()
        httpClient.close()
    }

    private suspend fun resolveImage(
        image: String,
        fallbackImage: String? = null,
    ): String? {
        return if (image.startsWith("mp:") || image.startsWith("http")) {
            ArtworkCache.getOrFetch(image) {
                if (image.startsWith("mp:")) {
                    Timber.tag(tag).d("Image already mp: — $image")
                    image
                } else {
                    Timber.tag(tag).d("Fetching external asset for: $image")
                    val asset = fetchExternalAsset(
                        client = httpClient,
                        applicationId = APPLICATION_ID,
                        token = token,
                        imageUrl = image,
                        userAgent = userAgent,
                        superPropertiesBase64 = superPropertiesBase64,
                    )
                    if (asset != null) {
                        Timber.tag(tag).i("External asset uploaded: $image -> $asset")
                    } else {
                        Timber.tag(tag).w("External asset upload failed for: $image, using raw URL")
                    }
                    asset ?: fallbackImage
                        ?.takeIf { it.isNotBlank() && it != image }
                        ?.let { fallback ->
                            ArtworkCache.getOrFetch(fallback) { fetchExternalImage(fallback) }
                                ?: fallback.takeIf { it.startsWith("http") }
                        }
                        ?: image
                }
            }
        } else {
            "mp:$image"
        }
    }

    private suspend fun fetchExternalImage(imageUrl: String): String? =
        fetchExternalAsset(
            client = httpClient,
            applicationId = APPLICATION_ID,
            token = token,
            imageUrl = imageUrl,
            userAgent = userAgent,
            superPropertiesBase64 = superPropertiesBase64,
        )

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"
        private const val TAG = "DiscordRpc"

        suspend fun getUserInfo(
            token: String,
            userAgent: String = SuperProperties.userAgent,
            superPropertiesBase64: String? = null,
        ): Result<UserInfo> = runCatching {
            Timber.tag(TAG).i("Fetching user info from Discord API...")
            val client = HttpClient()
            try {
                val response = client.get("https://discord.com/api/v9/users/@me") {
                    header("Authorization", token)
                    header("User-Agent", userAgent)
                    if (superPropertiesBase64 != null) {
                        header("X-Super-Properties", superPropertiesBase64)
                    }
                }
                val text = response.bodyAsText()
                val json = org.json.JSONObject(text)
                val id = json.getString("id")
                val username = json.getString("username")
                val name = json.optString("global_name", username)
                val avatarHash = json.optString("avatar")
                val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                    "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
                } else null
                Timber.tag(TAG).i("User info fetched successfully")
                UserInfo(id, username, name, avatar)
            } finally {
                client.close()
            }
        }
    }
}
