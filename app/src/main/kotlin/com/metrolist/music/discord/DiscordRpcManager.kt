package com.metrolist.music.discord

import android.os.Handler
import android.os.Looper
import com.metrolist.music.BuildConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber

data class DiscordUser(
    val id: String,
    val username: String,
    val name: String,
    val avatar: String?,
)

object DiscordRpcManager {
    private val APP_ID = BuildConfig.DISCORD_RPC_APPLICATION_ID

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
                                        _ready = true
                                        _connectionStatus.value = Status.Connected
                                        pendingActivity?.let(::setActivity)
                                    }
                                    if (_ready && !nativeReady) {
                                        _ready = false
                                        _connectionStatus.value = Status.Authorizing
                                    }
                                    if (!_authorized && nativeAuth) {
                                        _authorized = true
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
            nativeAuthorize()
            scheduleAuthorizationTimeout(generation)
        } catch (e: Exception) {
            reportError("Discord RPC authorization screen failed to open", e)
            _connectionStatus.value = Status.Disconnected
            completeAuthorization(false)
        }
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

            DiscordUser(id, username, name, avatar)
        } catch (e: Exception) {
            Timber.e(e, "fetchCurrentUser: exception")
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
        connectGeneration++
        callbackTimer?.cancel()
        callbackTimer = null
        nativeDestroy()
    }
}
