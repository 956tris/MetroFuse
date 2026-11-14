package dev.metrofuse.runtime

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class PluginHomeState(
    val shelves: List<HomeShelf> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val loaded: Boolean = false,
)

/** One activation-owned request and cache per source. Offline never enters this registry. */
class PluginHomes(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 15_000,
) {
    val pages = MutableStateFlow<Map<String, PluginHomeState>>(emptyMap())
    private val bindings = ConcurrentHashMap<String, Binding>()

    private class Binding(
        val scope: CoroutineScope,
        val getHome: suspend (HomeRequest) -> List<HomeShelf>,
    ) {
        val revision = AtomicLong()
        var request: HomeRequest? = null
        var job: Job? = null
    }

    fun bind(
        owner: String,
        getHome: suspend (HomeRequest) -> List<HomeShelf>,
    ) {
        unbind(owner)
        bindings[owner] = Binding(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])), getHome)
        pages.update { it + (owner to PluginHomeState()) }
    }

    fun unbind(owner: String) {
        bindings.remove(owner)?.scope?.cancel()
        pages.update { it - owner }
    }

    @Synchronized
    fun refresh(
        owner: String,
        request: HomeRequest,
        force: Boolean = false,
    ) {
        val binding = bindings[owner] ?: return
        val previous = pages.value[owner] ?: PluginHomeState()
        if (!force && binding.request == request && (previous.loading || previous.loaded)) return
        binding.job?.cancel()
        val revision = binding.revision.incrementAndGet()
        binding.request = request
        update(owner, binding, revision) { it.copy(loading = true, error = false) }
        binding.job =
            binding.scope.launch {
                try {
                    val shelves = withTimeout(timeoutMs) { binding.getHome(request) }
                    validateHome(shelves)
                    update(owner, binding, revision) { PluginHomeState(shelves, loaded = true) }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    update(owner, binding, revision) { it.copy(loading = false, error = true) }
                } catch (_: Throwable) {
                    update(owner, binding, revision) { it.copy(loading = false, error = true) }
                }
            }
    }

    private fun update(
        owner: String,
        binding: Binding,
        revision: Long,
        transform: (PluginHomeState) -> PluginHomeState,
    ) {
        pages.update { current ->
            if (bindings[owner] !== binding || binding.revision.get() != revision) {
                current
            } else {
                current + (owner to transform(current[owner] ?: PluginHomeState()))
            }
        }
    }
}

internal fun validateHome(shelves: List<HomeShelf>) {
    require(shelves.size <= 60 && shelves.sumOf { it.items.size } <= 1000)
    var characters = 0L

    fun text(
        value: String,
        maximum: Int = 4096,
    ) {
        require(value.length <= maximum)
        characters += value.length
        require(characters <= 2 * 1024 * 1024)
    }

    fun track(value: Track) {
        require(value.id.isNotBlank() && value.title.isNotBlank())
        text(value.id, 256)
        text(value.title, 512)
        text(value.artist, 512)
        text(value.album, 512)
        text(value.artwork)
        require(value.extras.size <= 64 && value.extras.keys.count { it != "pluginOwner" && it != "providerTrackId" } <= 62)
        value.extras.forEach { (key, entry) ->
            text(key, 128)
            text(entry)
        }
    }

    fun item(value: HomeItem) {
        require(listOf(value.track, value.album, value.artist, value.playlist).count { it != null } == 1)
        value.track?.let(::track)
        value.album?.let { album ->
            require(album.id.isNotBlank() && album.title.isNotBlank() && album.tracks.size <= 1000)
            text(album.id, 256)
            text(album.title, 512)
            text(album.artist, 512)
            text(album.artwork)
            album.tracks.forEach(::track)
        }
        value.artist?.let { artist ->
            require(artist.id.isNotBlank() && artist.name.isNotBlank() && artist.tracks.size <= 1000 && artist.albums.size <= 100)
            text(artist.id, 256)
            text(artist.name, 512)
            text(artist.artwork)
            artist.tracks.forEach(::track)
            artist.albums.forEach { item(HomeItem(album = it)) }
        }
        value.playlist?.let { playlist ->
            require(playlist.id.isNotBlank() && playlist.title.isNotBlank() && playlist.tracks.size <= 1000)
            text(playlist.id, 256)
            text(playlist.title, 512)
            text(playlist.author, 512)
            text(playlist.artwork)
            playlist.tracks.forEach(::track)
        }
    }
    shelves.forEach { shelf ->
        require(shelf.title.isNotBlank())
        text(shelf.title, 256)
        shelf.items.forEach(::item)
    }
}
