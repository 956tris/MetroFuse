package com.metrolist.music.discordrpc

import com.metrolist.music.discordrpc.entities.ClientState
import com.metrolist.music.discordrpc.entities.HeartbeatResponse
import com.metrolist.music.discordrpc.entities.Identify
import com.metrolist.music.discordrpc.entities.IdentifyProperties
import com.metrolist.music.discordrpc.entities.OpCode
import com.metrolist.music.discordrpc.entities.Payload
import com.metrolist.music.discordrpc.entities.Presence
import com.metrolist.music.discordrpc.entities.Ready
import com.metrolist.music.discordrpc.entities.Resume
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import timber.log.Timber
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GatewayWebSocket(
    private val token: String,
    private val os: String,
    private val browser: String,
    private val device: String,
) : CoroutineScope {
    private val job = SupervisorJob()
    override val coroutineContext = job + Dispatchers.Default
    private val tag = "DiscordGateway"

    private val client = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var session: DefaultClientWebSocketSession? = null
    private var sessionId: String? = null
    private var sequence = 0
    private var resumeUrl: String? = null
    private var heartbeatInterval = 0L
    private var heartbeatJob: Job? = null
    private var connected = false
    private var sessionEstablished = false
    private var reconnectionJob: Job? = null
    private var currentReconnectDelay = INITIAL_RECONNECT_DELAY
    private var intentionalClose = false
    private var lastHeartbeatAckReceivedAt = 0L
    private var heartbeatWatchdogJob: Job? = null

    fun isSessionEstablished(): Boolean = sessionEstablished

    fun connect() {
        if (connected) {
            Timber.tag(tag).d("connect() called but already connected")
            return
        }
        if (!isActive) {
            Timber.tag(tag).w("connect() called but scope is not active — was close() called?")
            return
        }
        Timber.tag(tag).i("Connecting to Gateway...")
        intentionalClose = false
        reconnectionJob?.cancel()
        reconnectionJob = launch {
            try {
                establishConnection()
            } catch (e: Exception) {
                Timber.tag(tag).e(e, "establishConnection() threw unhandled exception")
                scheduleReconnection()
            }
        }
    }

    private suspend fun establishConnection() {
        val url = resumeUrl ?: GATEWAY_URL
        val systemLocale = Locale.getDefault().toString().replace('_', '-')
        Timber.tag(tag).d("establishConnection: url=$url locale=$systemLocale")

        session = try {
            client.webSocketSession(url) {
                header("User-Agent", USER_AGENT)
                header("Accept-Language", systemLocale)
            }
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "WebSocket connection failed to $url")
            connected = false
            throw e
        }

        connected = true
        currentReconnectDelay = INITIAL_RECONNECT_DELAY
        Timber.tag(tag).i("WebSocket connected to $url")

        try {
            session!!.incoming.receiveAsFlow().collect { frame ->
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        try {
                            val payload = json.decodeFromString<Payload>(text)
                            handlePayload(payload)
                        } catch (e: Exception) {
                            Timber.tag(tag).w(e, "Failed to decode payload (${text.length} bytes)")
                        }
                    }
                    else -> {
                        Timber.tag(tag).v("Ignored frame type: ${frame::class.simpleName}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(tag).e(e, "WebSocket receive flow ended with exception")
        }

        if (!intentionalClose) {
            Timber.tag(tag).d("WebSocket receive flow ended normally, handling disconnect")
            handleDisconnect()
        }
    }

    private suspend fun handlePayload(payload: Payload) {
        payload.s?.let { sequence = it }

        when (payload.op) {
            OpCode.DISPATCH -> {
                Timber.tag(tag).d("DISPATCH | seq=$sequence | event=${payload.t}")
                handleDispatch(payload)
            }
            OpCode.HEARTBEAT -> {
                Timber.tag(tag).d("<- HEARTBEAT (server-requested)")
                sendHeartbeat()
            }
            OpCode.RECONNECT -> {
                Timber.tag(tag).w("<- RECONNECT — server requested reconnect")
                handleReconnect()
            }
            OpCode.INVALID_SESSION -> {
                val canResume = payload.d?.let { json.decodeFromJsonElement<Boolean>(it) } ?: false
                Timber.tag(tag).w("<- INVALID_SESSION | canResume=$canResume")
                handleInvalidSession(payload)
            }
            OpCode.HELLO -> {
                Timber.tag(tag).d("<- HELLO — starting handshake")
                handleHello(payload)
            }
            OpCode.HEARTBEAT_ACK -> {
                lastHeartbeatAckReceivedAt = System.currentTimeMillis()
                Timber.tag(tag).v("<- HEARTBEAT_ACK")
            }
            else -> {
                Timber.tag(tag).d("<- op=${payload.op} (${payload.op?.name})")
            }
        }
    }

    private suspend fun handleHello(payload: Payload) {
        val hello = json.decodeFromJsonElement<HeartbeatResponse>(payload.d!!)
        heartbeatInterval = hello.heartbeatInterval
        Timber.tag(tag).i("HELLO: heartbeat_interval=${heartbeatInterval}ms")

        val jitter = (0..<heartbeatInterval).random()
        Timber.tag(tag).d("First heartbeat with jitter=${jitter}ms")
        delay(jitter)
        sendHeartbeat()
        startHeartbeatLoop()
        startHeartbeatWatchdog()

        if (sessionId != null && sequence > 0) {
            Timber.tag(tag).i("Resuming session: sessionId=$sessionId seq=$sequence")
            sendResume(sessionId!!)
        } else {
            Timber.tag(tag).i("Sending Identify (fresh session)")
            sendIdentify()
        }
    }

    private suspend fun handleDispatch(payload: Payload) {
        when (payload.t) {
            "READY" -> {
                val ready = json.decodeFromJsonElement<Ready>(payload.d!!)
                sessionId = ready.sessionId
                resumeUrl = ready.resumeGatewayUrl?.let { "$it/?v=9&encoding=json" }
                sessionEstablished = true
                Timber.tag(tag).i("READY received")
            }
            "RESUMED" -> {
                sessionEstablished = true
                Timber.tag(tag).i("RESUMED — session re-established")
            }
            else -> {
                Timber.tag(tag).d("Unhandled dispatch: ${payload.t}")
            }
        }
    }

    private suspend fun handleReconnect() {
        session?.close(CloseReason(4000, "Reconnect requested"))
    }

    private suspend fun handleInvalidSession(payload: Payload) {
        val canResume = payload.d?.let { json.decodeFromJsonElement<Boolean>(it) } ?: false
        val sid = sessionId
        delay(1500)
        if (canResume && sid != null) {
            Timber.tag(tag).i("INVALID_SESSION: can resume, sending Resume")
            sendResume(sid)
        } else {
            Timber.tag(tag).i("INVALID_SESSION: cannot resume, sending fresh Identify")
            sessionId = null
            sequence = 0
            resumeUrl = null
            sessionEstablished = false
            sendIdentify()
        }
    }

    private suspend fun handleDisconnect() {
        heartbeatJob?.cancel()
        heartbeatWatchdogJob?.cancel()
        connected = false
        sessionEstablished = false
        val reason = session?.closeReason?.await()
        val code = reason?.code?.toInt() ?: -1
        val message = reason?.message ?: "unknown"
        Timber.tag(tag).w("Disconnected: code=$code reason=$message")

        when {
            code == 4004 -> {
                Timber.tag(tag).e("Token invalid (4004) — will not reconnect")
            }
            code == 4006 || code == 4008 -> {
                Timber.tag(tag).i("Session invalidated ($code), clearing state and reconnecting")
                sessionId = null
                sequence = 0
                resumeUrl = null
                scheduleReconnection()
            }
            code == 4000 -> {
                Timber.tag(tag).d("Close code 4000 — immediate reconnect")
                delay(200.milliseconds)
                connect()
            }
            else -> {
                Timber.tag(tag).d("Close code $code — scheduling reconnection")
                scheduleReconnection()
            }
        }
    }

    private suspend fun sendIdentify() {
        val props = IdentifyProperties(
            os = os,
            browser = browser,
            device = device,
            systemLocale = Locale.getDefault().toString(),
            clientVersion = "314.13 - Stable",
            releaseChannel = "googleRelease",
            osVersion = android.os.Build.VERSION.RELEASE,
            osSdkVersion = android.os.Build.VERSION.SDK_INT.toString(),
            clientBuildNumber = 314013,
        )
        Timber.tag(tag).i("-> IDENTIFY: os=$os browser=$browser device=$device")
        send(
            op = OpCode.IDENTIFY,
            d = Identify(
                token = token,
                properties = props,
                presence = Presence(status = "online", since = null, afk = false),
                clientState = ClientState(),
            ),
        )
    }

    private suspend fun sendResume(sid: String) {
        Timber.tag(tag).i("-> RESUME: sessionId=$sid seq=$sequence")
        send(
            op = OpCode.RESUME,
            d = Resume(token = token, sessionId = sid, seq = sequence),
        )
    }

    private suspend fun sendHeartbeat() {
        val seq = if (sequence == 0) null else sequence
        Timber.tag(tag).v("-> HEARTBEAT seq=$seq")
        send(op = OpCode.HEARTBEAT, d = seq)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            Timber.tag(tag).d("Heartbeat loop started (interval=${heartbeatInterval}ms)")
            lastHeartbeatAckReceivedAt = System.currentTimeMillis()
            while (isActive) {
                delay(heartbeatInterval)
                sendHeartbeat()
            }
            Timber.tag(tag).d("Heartbeat loop ended")
        }
    }

    private fun startHeartbeatWatchdog() {
        heartbeatWatchdogJob?.cancel()
        heartbeatWatchdogJob = launch {
            val threshold = (heartbeatInterval * 2).coerceAtLeast(10_000L)
            while (isActive) {
                delay(threshold)
                val elapsed = System.currentTimeMillis() - lastHeartbeatAckReceivedAt
                if (elapsed >= threshold && connected) {
                    Timber.tag(tag).w("Heartbeat ACK not received for ${elapsed}ms — forcing reconnect")
                    handleReconnect()
                }
            }
        }
    }

    private fun scheduleReconnection() {
        if (intentionalClose) {
            Timber.tag(tag).d("scheduleReconnection: intentionalClose=true, skipping")
            return
        }
        Timber.tag(tag).d("scheduleReconnection: delay=${currentReconnectDelay.inWholeSeconds}s")
        reconnectionJob?.cancel()
        reconnectionJob = launch {
            delay(currentReconnectDelay)
            currentReconnectDelay = (currentReconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
            Timber.tag(tag).d("Reconnection delay elapsed, calling connect()")
            connect()
        }
    }

    suspend fun updatePresence(presence: Presence) {
        Timber.tag(tag).d("updatePresence: waiting for sessionEstablished...")
        var waited = 0L
        while (!sessionEstablished) {
            delay(10.milliseconds)
            waited += 10
            if (waited > 30_000L) {
                Timber.tag(tag).w("updatePresence: timed out waiting for session (30s)")
                return
            }
        }
        Timber.tag(tag).i("-> PRESENCE_UPDATE: activities=${presence.activities.size}")
        send(op = OpCode.PRESENCE_UPDATE, d = presence)
    }

    suspend fun clearPresence() {
        if (sessionEstablished) {
            Timber.tag(tag).i("-> PRESENCE_UPDATE (clearing)")
            send(
                op = OpCode.PRESENCE_UPDATE,
                d = Presence(activities = emptyList(), since = null, status = "online", afk = false),
            )
        }
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (session?.isActive == true) {
            val payload = json.encodeToString(
                Payload(
                    op = op,
                    d = if (d != null) json.encodeToJsonElement(d) else null,
                ),
            )
            session?.send(Frame.Text(payload))
        } else {
            Timber.tag(tag).w("Cannot send ${op.name}: session is not active")
        }
    }

    fun close() {
        Timber.tag(tag).i("close() called — stopping connection")
        intentionalClose = true
        reconnectionJob?.cancel()
        heartbeatJob?.cancel()
        heartbeatWatchdogJob?.cancel()
        kotlinx.coroutines.runBlocking {
            try {
                session?.close()
            } catch (_: Exception) { }
        }
        connected = false
        sessionEstablished = false
    }

    companion object {
        private const val GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"
        private const val USER_AGENT = "Discord-Android/314013;RNA"

        private val INITIAL_RECONNECT_DELAY = 1.seconds
        private val MAX_RECONNECT_DELAY = 60.seconds
    }
}
