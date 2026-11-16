package dev.metrofuse.runtime

import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomePage
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
    val continuation: String? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: Boolean = false,
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
        val getPage: suspend (HomeRequest, String?) -> HomePage,
    ) {
        val revision = AtomicLong()
        val seenContinuations = ConcurrentHashMap.newKeySet<String>()
        var request: HomeRequest? = null
        var job: Job? = null
    }

    fun bind(
        owner: String,
        getHome: suspend (HomeRequest) -> List<HomeShelf>,
    ) = bindPaged(owner) { request, _ -> HomePage(getHome(request)) }

    fun bindPaged(
        owner: String,
        getPage: suspend (HomeRequest, String?) -> HomePage,
    ) {
        unbind(owner)
        bindings[owner] = Binding(CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])), getPage)
        pages.update { it + (owner to PluginHomeState()) }
    }

    fun unbind(owner: String) {
        bindings.remove(owner)?.scope?.cancel()
        pages.update { it - owner }
    }

    @Synchronized
    fun invalidate(owner: String) {
        val binding = bindings[owner] ?: return
        val request = binding.request
        binding.job?.cancel()
        val revision = binding.revision.incrementAndGet()
        binding.request = null
        update(owner, binding, revision) { PluginHomeState() }
        if (request != null) refresh(owner, request, force = true)
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
        binding.seenContinuations.clear()
        update(owner, binding, revision) { it.copy(loading = true, error = false, loadingMore = false, loadMoreError = false) }
        binding.job =
            binding.scope.launch {
                try {
                    val page = withTimeout(timeoutMs) { binding.getPage(request, null) }
                    validateHome(page.shelves)
                    val continuation = nextContinuation(binding, page.continuation, page.shelves)
                    update(owner, binding, revision) { PluginHomeState(page.shelves, loaded = true, continuation = continuation) }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    update(owner, binding, revision) { it.copy(loading = false, error = true) }
                } catch (_: Throwable) {
                    update(owner, binding, revision) { it.copy(loading = false, error = true) }
                }
            }
    }

    @Synchronized
    fun loadMore(owner: String) {
        val binding = bindings[owner] ?: return
        val previous = pages.value[owner] ?: return
        val request = binding.request ?: return
        val continuation = previous.continuation ?: return
        if (!previous.loaded || previous.loading || previous.loadingMore) return
        val revision = binding.revision.get()
        update(owner, binding, revision) { it.copy(loadingMore = true, loadMoreError = false) }
        binding.job =
            binding.scope.launch {
                try {
                    val page = withTimeout(timeoutMs) { binding.getPage(request, continuation) }
                    validateHome(page.shelves)
                    val shelves = mergeHomeShelves(previous.shelves, page.shelves)
                    val next = nextContinuation(binding, page.continuation, shelves)
                    update(owner, binding, revision) {
                        it.copy(shelves = shelves, continuation = next, loadingMore = false, loadMoreError = false)
                    }
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                    update(owner, binding, revision) { it.copy(loadingMore = false, loadMoreError = true) }
                } catch (_: Throwable) {
                    update(owner, binding, revision) { it.copy(loadingMore = false, loadMoreError = true) }
                }
            }
    }

    private fun nextContinuation(
        binding: Binding,
        continuation: String?,
        shelves: List<HomeShelf>,
    ): String? {
        require((continuation?.length ?: 0) <= 16_384)
        if (shelves.size >= 60 || shelves.sumOf { it.items.size } >= 1000) return null
        return continuation?.takeIf { it.isNotBlank() && binding.seenContinuations.add(it) }
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

internal fun mergeHomeShelves(
    existing: List<HomeShelf>,
    additional: List<HomeShelf>,
): List<HomeShelf> {
    fun HomeItem.identity(): String =
        track?.let { "track:${it.id}" } ?: album?.let { "album:${it.id}" }
            ?: artist?.let { "artist:${it.id}" } ?: "playlist:${playlist?.id}"

    val shelves = linkedMapOf<Pair<String, HomeLayout>, HomeShelf>()
    for (shelf in existing + additional) {
        val key = shelf.title to shelf.layout
        val previous = shelves[key]
        shelves[key] = shelf.copy(items = (previous?.items.orEmpty() + shelf.items).distinctBy { it.identity() })
    }
    var remaining = 1000
    return shelves.values.take(60).mapNotNull { shelf ->
        val items = shelf.items.take(minOf(100, remaining))
        remaining -= items.size
        items.takeIf { it.isNotEmpty() }?.let { shelf.copy(items = it) }
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
