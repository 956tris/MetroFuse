package com.metrolist.music.discord

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.discord.socialsdk.AuthenticationClientCallback
import com.discord.socialsdk.NativeCalls
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import timber.log.Timber

data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

object DiscordRpcManager {
    private val APP_ID = BuildConfig.DISCORD_RPC_APPLICATION_ID
    private const val AUTH_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/v10/oauth2/token"
    private const val SCOPES = "openid sdk.social_layer_presence"
    private val REDIRECT_URI = "discord-$APP_ID:///authorize/callback"

    @Volatile private var initialized = false
    @Volatile private var _authorized = false
    @Volatile private var _ready = false
    @Volatile private var accessToken: String? = null
    @Volatile private var pendingActivity: DiscordActivity? = null
    @Volatile private var connectGeneration = 0
    @Volatile private var authorizeGeneration = 0
    @Volatile private var authorizeCallback: ((Boolean) -> Unit)? = null
    @Volatile private var callbackTimer: java.util.Timer? = null

    private val _connectionStatus = MutableStateFlow(Status.Disconnected)
    val connectionStatus: StateFlow<Status> = _connectionStatus
    private val _currentUser = MutableStateFlow<DiscordUser?>(null)
    val currentUser: StateFlow<DiscordUser?> = _currentUser
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Status { Disconnected, Authorizing, Connected }

    fun getAccessToken(): String? = accessToken

    private fun reportError(message: String) {
        Timber.w(message)
        _errors.tryEmit(message)
    }

    private fun reportError(message: String, throwable: Throwable) {
        Timber.e(throwable, message)
        _errors.tryEmit("$message: ${throwable.message ?: throwable.javaClass.simpleName}")
    }

    @JvmStatic
    fun onNativeError(message: String) {
        mainHandler.post {
            reportError("Discord RPC native error: $message")
            if (authorizeCallback != null && !_authorized) {
                _connectionStatus.value = Status.Disconnected
                completeAuthorization(false)
            }
        }
    }

    @JvmStatic
    fun onNativeAuthorized(token: String) {
        mainHandler.post {
            accessToken = token
            _authorized = true
            _ready = false
            _connectionStatus.value = Status.Authorizing
            val generation = ++connectGeneration
            scheduleConnectRetry(token, generation, attempt = 0)
            completeAuthorization(true)
        }
    }

    private external fun nativeInit(appId: Long): Boolean
    private external fun nativeAuthorize()
    private external fun nativeSetTokenAndConnect(token: String)
    private external fun nativeConnect()
    private external fun nativeIsReady(): Boolean
    private external fun nativeIsAuthorized(): Boolean
    private external fun nativeSetListening(
        name: String?, type: String?, state: String?, details: String?,
        startSecs: Long, endSecs: Long,
        largeImage: String?, largeText: String?,
        smallImage: String?, smallText: String?,
        button1Label: String?, button1Url: String?,
        button2Label: String?, button2Url: String?,
    )
    private external fun nativeClear()
    private external fun nativeRunCallbacks()
    private external fun nativeDestroy()
    private external fun nativeDisconnect()
    private external fun nativeCurrentUserJson(): String?

    fun isInitialized(): Boolean = initialized
    fun isAuthorized(): Boolean = _authorized
    fun isReady(): Boolean = _ready

    fun init() {
        synchronized(this) {
            if (initialized) return
            try {
                System.loadLibrary("metrofuse_discord")
            } catch (e: UnsatisfiedLinkError) {
                reportError("Discord RPC native library failed to load", e)
                return
            }
            initialized = nativeInit(APP_ID)
            if (!initialized) {
                reportError("Discord RPC native initialization failed")
                return
            }
            _connectionStatus.value = Status.Disconnected
            callbackTimer?.cancel()
            callbackTimer =
                java.util.Timer("DiscordRPC", true).apply {
                    schedule(
                        object : java.util.TimerTask() {
                            override fun run() {
                                try {
                                    nativeRunCallbacks()
                                    val nativeReady = nativeIsReady()
                                    val nativeAuth = nativeIsAuthorized()
                                    if (!_ready && _authorized && nativeReady) {
                                        mainHandler.post {
                                            if (!_ready && _authorized) {
                                                _ready = true
                                                _connectionStatus.value = Status.Connected
                                                currentUserFromSdk()
                                                pendingActivity?.let(::setActivity)
                                            }
                                        }
                                    }
                                    if (_ready && !nativeReady) {
                                        mainHandler.post {
                                            if (_ready) {
                                                _ready = false
                                                _connectionStatus.value = Status.Authorizing
                                            }
                                        }
                                    }
                                    if (!_authorized && nativeAuth) {
                                        mainHandler.post {
                                            _authorized = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "TIMER: error")
                                }
                            }
                        },
                        1000, 1000,
                    )
                }
        }
    }

    fun authorize(onComplete: (Boolean) -> Unit) {
        if (!initialized) {
            reportError("Discord RPC authorization failed because the SDK is not initialized")
            onComplete(false)
            return
        }

        synchronized(this) {
            if (authorizeCallback != null) {
                reportError("Discord RPC authorization is already in progress")
                onComplete(false)
                return
            }
            authorizeCallback = onComplete
        }

        _connectionStatus.value = Status.Authorizing
        val generation = ++authorizeGeneration
        try {
            val verifier = generateCodeVerifier()
            val challenge = generateCodeChallenge(verifier)
            val oauthUrl =
                "$AUTH_URL" +
                    "?client_id=$APP_ID" +
                    "&response_type=code" +
                    "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
                    "&scope=${URLEncoder.encode(SCOPES, "UTF-8")}" +
                    "&code_challenge_method=S256" +
                    "&code_challenge=$challenge"
            val callback =
                object : AuthenticationClientCallback(0) {
                    private var callbackFired = false

                    override fun onAuthorizationComplete(
                        error: String?,
                        authCode: String?,
                        state: String?,
                    ) {
                        if (callbackFired) return
                        callbackFired = true
                        mainHandler.post {
                            if (generation != authorizeGeneration || authorizeCallback == null) {
                                return@post
                            }
                            when {
                                !error.isNullOrBlank() -> {
                                    reportError("Discord RPC authorization failed: $error")
                                    _connectionStatus.value = Status.Disconnected
                                    completeAuthorization(false)
                                }
                                authCode.isNullOrBlank() -> {
                                    reportError("Discord RPC authorization returned no code")
                                    _connectionStatus.value = Status.Disconnected
                                    completeAuthorization(false)
                                }
                                else -> exchangeCodeForToken(authCode, verifier, generation)
                            }
                        }
                    }
                }
            NativeCalls.authorize(oauthUrl, callback)
            scheduleAuthorizationTimeout(generation)
        } catch (e: Exception) {
            reportError("Discord RPC authorization screen failed to open", e)
            _connectionStatus.value = Status.Disconnected
            completeAuthorization(false)
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun exchangeCodeForToken(
        authCode: String,
        codeVerifier: String,
        generation: Int,
    ) {
        Thread {
            try {
                val body =
                    "client_id=$APP_ID" +
                        "&grant_type=authorization_code" +
                        "&code=${URLEncoder.encode(authCode, "UTF-8")}" +
                        "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}" +
                        "&code_verifier=$codeVerifier"
                val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val responseCode = conn.responseCode
                val responseBody =
                    if (responseCode in 200..299) {
                        conn.inputStream.bufferedReader().readText()
                    } else {
                        conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    }
                conn.disconnect()

                if (responseCode !in 200..299) {
                    mainHandler.post {
                        if (generation != authorizeGeneration || authorizeCallback == null) return@post
                        reportError("Discord RPC token exchange failed: HTTP $responseCode")
                        _connectionStatus.value = Status.Disconnected
                        completeAuthorization(false)
                    }
                    return@Thread
                }

                val token = JSONObject(responseBody).optString("access_token").takeIf { it.isNotBlank() }
                if (token == null) {
                    mainHandler.post {
                        if (generation != authorizeGeneration || authorizeCallback == null) return@post
                        reportError("Discord RPC token exchange returned no access token")
                        _connectionStatus.value = Status.Disconnected
                        completeAuthorization(false)
                    }
                    return@Thread
                }

                mainHandler.post {
                    if (generation != authorizeGeneration || authorizeCallback == null) return@post
                    accessToken = token
                    _authorized = true
                    _ready = false
                    _connectionStatus.value = Status.Authorizing
                    val connectAttemptGeneration = ++connectGeneration
                    nativeSetTokenAndConnect(token)
                    nativeConnect()
                    scheduleConnectRetry(token, connectAttemptGeneration, attempt = 0)
                    completeAuthorization(true)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (generation != authorizeGeneration || authorizeCallback == null) return@post
                    reportError("Discord RPC token exchange failed", e)
                    _connectionStatus.value = Status.Disconnected
                    completeAuthorization(false)
                }
            }
        }.apply { name = "DiscordTokenExchange" }.start()
    }

    private fun scheduleAuthorizationTimeout(generation: Int) {
        mainHandler.postDelayed(
            {
                if (generation != authorizeGeneration || authorizeCallback == null) {
                    return@postDelayed
                }
                reportError("Discord RPC authorization timed out")
                _connectionStatus.value = Status.Disconnected
                completeAuthorization(false)
            },
            60_000L,
        )
    }

    private fun completeAuthorization(success: Boolean) {
        val callback = synchronized(this) {
            val callback = authorizeCallback
            authorizeCallback = null
            callback
        }
        callback?.invoke(success)
    }

    fun fetchCurrentUser(token: String): DiscordUser? {
        return try {
            val url = URL("https://discord.com/api/v10/users/@me")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode !in 200..299) {
                Timber.w("fetchCurrentUser: HTTP $responseCode body=$responseBody")
                return null
            }

            val json = JSONObject(responseBody)
            val id = json.getString("id")
            val username = json.getString("username")
            val name = json.optString("global_name", username)
            val avatarHash = json.optString("avatar")
            val avatar = if (avatarHash.isNotEmpty() && avatarHash != "null") {
                "https://cdn.discordapp.com/avatars/$id/$avatarHash.png"
            } else null

            DiscordUser(id, username, name, avatar).also {
                _currentUser.value = it
            }
        } catch (e: Exception) {
            Timber.e(e, "fetchCurrentUser: exception")
            null
        }
    }

    fun currentUserFromSdk(): DiscordUser? {
        if (!initialized) return null
        return try {
            parseDiscordUser(nativeCurrentUserJson()).also { user ->
                if (user != null) {
                    _currentUser.value = user
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "currentUserFromSdk: exception")
            null
        }
    }

    fun disconnect() {
        if (!initialized) return
        _connectionStatus.value = Status.Disconnected
        _ready = false
        _authorized = false
        accessToken = null
        pendingActivity = null
        _currentUser.value = null
        connectGeneration++
        nativeDisconnect()
    }

    fun setActivity(activity: DiscordActivity) {
        pendingActivity = activity
        if (!_ready) return
        nativeSetListening(
            activity.name, activity.type, activity.state, activity.details,
            activity.startTimestamp, activity.endTimestamp ?: 0L,
            activity.largeImage, activity.largeText,
            activity.smallImage, activity.smallText,
            activity.button1Label, activity.button1Url,
            activity.button2Label, activity.button2Url,
        )
    }

    fun clear() {
        pendingActivity = null
        if (!_ready) return
        nativeClear()
    }

    fun reconnectWithToken(token: String) {
        if (!initialized) {
            reportError("Discord RPC reconnect failed because the SDK is not initialized")
            return
        }
        _authorized = true
        _connectionStatus.value = Status.Authorizing
        connectWithToken(token)
    }

    private fun connectWithToken(token: String) {
        accessToken = token
        _ready = false
        nativeSetTokenAndConnect(token)
        val generation = ++connectGeneration
        scheduleConnectRetry(token, generation, attempt = 0)
    }

    private fun scheduleConnectRetry(token: String, generation: Int, attempt: Int) {
        mainHandler.postDelayed(
            {
                if (!initialized || _ready || accessToken != token || generation != connectGeneration) {
                    return@postDelayed
                }
                nativeConnect()
                if (attempt < 4) {
                    scheduleConnectRetry(token, generation, attempt + 1)
                } else if (!_ready) {
                    _authorized = false
                    reportError("Discord RPC connection timed out after token exchange")
                    _connectionStatus.value = Status.Disconnected
                }
            },
            if (attempt == 0) 500L else 2_000L,
        )
    }

    fun destroy() = synchronized(this) {
        if (!initialized) return@synchronized
        _ready = false
        _authorized = false
        initialized = false
        pendingActivity = null
        _currentUser.value = null
        connectGeneration++
        callbackTimer?.cancel()
        callbackTimer = null
        nativeDestroy()
    }

    private fun parseDiscordUser(rawJson: String?): DiscordUser? {
        if (rawJson.isNullOrBlank()) return null
        val json = JSONObject(rawJson)
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val username = json.optString("username").takeIf { it.isNotBlank() } ?: "Discord"
        val name =
            json.optString("name")
                .takeIf { it.isNotBlank() }
                ?: json.optString("globalName")
                    .takeIf { it.isNotBlank() }
                ?: username
        val avatar = json.optString("avatar").takeIf { it.isNotBlank() }
        return DiscordUser(id, username, name, avatar)
    }
}
