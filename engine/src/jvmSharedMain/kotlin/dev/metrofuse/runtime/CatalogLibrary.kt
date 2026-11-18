package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.LibraryPage
import dev.metrofuse.api.LibraryProvider
import dev.metrofuse.api.LibraryRequest
import dev.metrofuse.api.SongVoteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class CatalogLibrary(
    private val registry: Registry,
    private val now: () -> Long,
    private val requestTimeoutMillis: Long = 15_000,
) {
    private data class Cursor(
        val owner: String,
        val providerId: String,
        val epoch: Long,
        val revision: Long,
        val request: LibraryRequest,
        val seen: Set<String>,
        val expiresAt: Long,
    )

    private val cursors = linkedMapOf<String, Cursor>()
    private val revision = AtomicLong()

    @Synchronized
    fun invalidate() {
        revision.incrementAndGet()
        cursors.clear()
    }

    @Synchronized
    private fun cursor(token: String): Cursor? =
        cursors[token]?.takeIf {
            it.expiresAt > now() && it.epoch == registry.providerEpoch && it.revision == revision.get()
        }

    @Synchronized
    private fun retain(cursor: Cursor): String {
        val token = "metrofuse-library:${UUID.randomUUID()}"
        cursors[token] = cursor
        while (cursors.size > 256) cursors.remove(cursors.keys.first())
        return token
    }

    @Synchronized
    private fun forget(token: String) {
        cursors.remove(token)
    }

    suspend fun page(
        owner: String,
        request: LibraryRequest,
    ): LibraryPage? =
        withContext(Dispatchers.Default) {
            val continuation = request.continuation
            val cursor = continuation?.let(::cursor)
            if (continuation != null &&
                (cursor == null || cursor.owner != owner || cursor.request.likedSongsOnly != request.likedSongsOnly)
            ) {
                throw CancellationException("Library continuation expired")
            }
            val entry =
                registry.catalogProviders().firstOrNull {
                    it.owner == owner && it.provider is LibraryProvider &&
                        (!request.likedSongsOnly || it.provider.supportsLikedSongs) && (cursor == null || it.id == cursor.providerId)
                }
            if (entry == null) {
                if (cursor != null) throw CancellationException("Library provider changed")
                return@withContext null
            }
            fetch(entry, cursor?.request ?: request, cursor?.seen.orEmpty()).also {
                continuation?.let(::forget)
            }
        }

    fun supportsSongVotes(owner: String): Boolean =
        registry.catalogProviders().any { it.owner == owner && (it.provider as? LibraryProvider)?.supportsSongVotes == true }

    suspend fun setSongVote(
        owner: String,
        request: SongVoteRequest,
    ): Boolean =
        withContext(Dispatchers.Default) {
            require(request.trackId.isNotBlank() && request.trackId.length <= 2048)
            val entry =
                registry.catalogProviders().firstOrNull {
                    it.owner == owner && (it.provider as? LibraryProvider)?.supportsSongVotes == true
                } ?: return@withContext false
            val requestRevision = revision.get()
            val epoch = registry.providerEpoch
            val succeeded =
                registry.callProvider(entry.owner, "catalog.songVote") {
                    withTimeoutOrNull(requestTimeoutMillis) {
                        (entry.provider as LibraryProvider).setSongVote(request)
                    } ?: error("Song vote request timed out")
                }
            if (requestRevision != revision.get() || epoch != registry.providerEpoch ||
                registry.catalogProviders().none { it.id == entry.id && it.owner == owner && it.provider === entry.provider }
            ) {
                throw CancellationException("Song vote account or provider changed")
            }
            succeeded
        }

    private suspend fun fetch(
        entry: ProviderEntry<CatalogProvider>,
        request: LibraryRequest,
        seen: Set<String>,
    ): LibraryPage {
        val requestRevision = revision.get()
        val epoch = registry.providerEpoch
        val result =
            registry.callProvider(entry.owner, "catalog.library") {
                val page =
                    withTimeoutOrNull(requestTimeoutMillis) { (entry.provider as LibraryProvider).library(request) }
                        ?: error("Library request timed out")
                validateHome(listOf(HomeShelf(entry.id, page.items)))
                require((page.continuation?.length ?: 0) <= 16_384)
                page
            }
        if (requestRevision != revision.get() || epoch != registry.providerEpoch ||
            registry.catalogProviders().none { it.id == entry.id && it.owner == entry.owner && it.provider === entry.provider }
        ) {
            throw CancellationException("Library account or provider changed")
        }
        val next = result.continuation?.takeIf(String::isNotBlank)
        val repeated = next != null && (next == request.continuation || next in seen)
        val continuation =
            next?.takeUnless { repeated }?.let {
                retain(
                    Cursor(entry.owner, entry.id, epoch, requestRevision, request.copy(continuation = it), seen + it, now() + 30 * 60_000),
                )
            }
        return result.copy(continuation = continuation, complete = result.complete && !repeated)
    }
}
