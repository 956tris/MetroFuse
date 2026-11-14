package dev.metrofuse.api

import kotlinx.coroutines.flow.StateFlow

/** Host-owned resources outlive plugin activations and are never constructed by the engine. */
interface HostNavigation {
    val destination: StateFlow<String>
    fun navigate(destination: String)
}
interface TrackCatalog {
    val tracks: List<Track>
    suspend fun identify(identity: TrackIdentity): Track?
}
interface HostSettings {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}
fun interface HostLogger { fun write(owner: String, message: String) }
interface HostServices {
    val playback: Player
    val navigation: HostNavigation
    val catalog: TrackCatalog
    val settings: HostSettings
    val logger: HostLogger
    val audioFallback: AudioSource? get() = null
}

@kotlinx.serialization.Serializable
data class TrackIdentity(val canonicalId: String, val identifiers: Map<String, String> = emptyMap(), val isrc: String? = null)
