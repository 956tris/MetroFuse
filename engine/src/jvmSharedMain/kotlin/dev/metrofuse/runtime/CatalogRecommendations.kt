package dev.metrofuse.runtime

import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.RecommendationProvider
import dev.metrofuse.api.RecommendationRequest
import dev.metrofuse.api.RecommendationResult
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class CatalogRecommendations(
    private val registry: Registry,
    private val now: () -> Long,
) {
    private data class Cursor(
        val owner: String,
        val providerId: String,
        val epoch: Long,
        val identity: TrackIdentity,
        val token: String,
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
    private fun cursor(token: String): Cursor? = cursors[token]?.takeIf { it.expiresAt > now() && it.epoch == registry.providerEpoch }

    @Synchronized
    private fun retain(cursor: Cursor): String {
        val token = "metrofuse-radio:${UUID.randomUUID()}"
        cursors[token] = cursor
        while (cursors.size > 256) cursors.remove(cursors.keys.first())
        return token
    }

    suspend fun start(
        identity: TrackIdentity,
        allowedOwners: Set<String>,
        limit: Int,
    ): Pair<String, RecommendationResult>? =
        withContext(Dispatchers.Default) {
            if (identity.canonicalId.isBlank() || identity.canonicalId.length > 2048) return@withContext null
            for (entry in registry.catalogProviders().filter { it.owner in allowedOwners }) {
                val original = identity.copy(canonicalId = identity.canonicalId.removePrefix("metrofuse:${entry.owner}:"))
                fetch(entry, RecommendationRequest(original, limit.coerceIn(1, 100)))?.let { return@withContext entry.owner to it }
            }
            null
        }

    suspend fun continuePage(
        token: String,
        allowedOwners: Set<String>,
        limit: Int,
    ): Pair<String, RecommendationResult>? =
        withContext(Dispatchers.Default) {
            val cursor = cursor(token)?.takeIf { it.owner in allowedOwners } ?: return@withContext null
            val entry =
                registry.catalogProviders().firstOrNull { it.owner == cursor.owner && it.id == cursor.providerId }
                    ?: return@withContext null
            fetch(entry, RecommendationRequest(cursor.identity, limit.coerceIn(1, 100), cursor.token))?.let { cursor.owner to it }
        }

    private suspend fun fetch(
        entry: ProviderEntry<CatalogProvider>,
        request: RecommendationRequest,
    ): RecommendationResult? {
        val provider = entry.provider as? RecommendationProvider ?: return null
        val requestRevision = revision.get()
        val epoch = registry.providerEpoch
        currentCoroutineContext().ensureActive()
        val result =
            try {
                withTimeoutOrNull(10_000) { provider.recommendations(request) }?.also(::validateRecommendations)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        if (requestRevision != revision.get() || epoch != registry.providerEpoch ||
            registry.catalogProviders().none { it.id == entry.id && it.owner == entry.owner && it.provider === entry.provider }
        ) {
            return null
        }
        val continuation =
            result.continuation?.takeIf(String::isNotBlank)?.let {
                retain(Cursor(entry.owner, entry.id, epoch, request.identity, it, now() + 30 * 60_000))
            }
        return result.copy(tracks = result.tracks.distinctBy { it.id }, continuation = continuation)
    }
}

private fun validateRecommendations(result: RecommendationResult) {
    require(result.tracks.size <= 200 && (result.continuation?.length ?: 0) <= 16_384)
    var characters = 0L
    result.tracks.forEach { track ->
        require(track.id.isNotBlank() && track.id.length <= 256 && track.title.isNotBlank() && track.title.length <= 512)
        require(track.artist.length <= 512 && track.album.length <= 512 && track.artwork.length <= 4096)
        require(track.extras.size <= 62 && track.extras.all { (key, value) -> key.length <= 128 && value.length <= 4096 })
        characters += track.id.length + track.title.length + track.artist.length + track.album.length + track.artwork.length
        characters += track.extras.entries.sumOf { it.key.length + it.value.length }
    }
    require(characters <= 2 * 1024 * 1024)
}
