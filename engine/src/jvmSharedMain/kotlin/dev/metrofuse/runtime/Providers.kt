package dev.metrofuse.runtime

import dev.metrofuse.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object MetadataMapping {
    fun fromTrack(
        track: Track,
        now: Long = System.currentTimeMillis(),
    ): MetadataDocument {
        val values =
            mapOf(
                "title" to JsonPrimitive(track.title),
                "artist" to JsonPrimitive(track.artist),
                "album" to JsonPrimitive(track.album),
                "duration" to JsonPrimitive(track.duration),
                "artwork" to JsonPrimitive(track.artwork),
            ) +
                track.extras.mapValues { JsonPrimitive(it.value) }
        return MetadataDocument(
            TrackIdentity(track.id),
            values.mapValues {
                MetadataField(it.value, FieldProvenance("builtin", .5, now, "Host identity"))
            },
        )
    }

    fun toTrack(
        document: MetadataDocument,
        base: Track,
    ): Track {
        fun field(
            name: String,
            fallback: String,
        ) = (document.fields[name]?.value as? JsonPrimitive)?.contentOrNull ?: fallback
        return base.copy(
            title = field("title", base.title),
            artist = field("artist", base.artist),
            album = field("album", base.album),
            artwork = field("artwork", base.artwork),
            duration =
                field("duration", base.duration.toString()).toLongOrNull()?.takeIf { it > 0 } ?: base.duration,
            extras =
                base.extras +
                    document.fields.filterKeys { it !in setOf("title", "artist", "album", "artwork", "duration") }.mapValues { (_, v) ->
                        (v.value as? JsonPrimitive)?.content
                            ?: v.value.toString()
                    },
        )
    }

    fun match(
        request: TrackIdentity,
        response: TrackIdentity,
    ): String? {
        val requestIsrc = request.isrc
        val responseIsrc = response.isrc
        if (request.identifiers.any { (source, id) -> response.identifiers[source]?.let { it != id } == true }) return null
        if (requestIsrc != null && responseIsrc != null && normalizeIsrc(requestIsrc) != normalizeIsrc(responseIsrc)) return null
        return when {
            request.canonicalId == response.canonicalId -> "Canonical identity"
            requestIsrc != null && responseIsrc != null && normalizeIsrc(requestIsrc) == normalizeIsrc(responseIsrc) -> "ISRC"
            request.identifiers.any { (source, id) -> response.identifiers[source] == id } -> "Explicit provider identifier"
            else -> null
        }
    }

    fun normalizeIsrc(value: String) = value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

    fun meaningful(value: JsonElement): Boolean =
        when (value) {
            JsonNull -> false
            is JsonPrimitive -> !value.isString || value.content.isNotBlank()
            is JsonArray -> value.isNotEmpty()
            is JsonObject -> value.isNotEmpty()
        }
}

private data class Cached<T>(
    val value: T,
    val until: Long,
    val weight: Int,
)

private data class MetadataCacheKey(
    val revision: Long,
    val epoch: Long,
    val entryId: String,
    val request: MetadataRequest,
    val policy: MetadataPolicy,
)

private data class AudioCacheKey(
    val revision: Long,
    val epoch: Long,
    val entryId: String,
    val request: AudioQuery,
    val policy: AudioPolicy,
)

private val EXPECTED_AUDIO_SKIP_CODES =
    setOf(
        "WRONG_PROVIDER",
        "IDENTITY_REQUIRED",
        "AUDIO_DISABLED",
        "TRACK_METADATA_REQUIRED",
        "NO_MATCH",
        "NO_FLAC_MATCH",
        "LOSSLESS_UNAVAILABLE",
        "QUALITY_UNAVAILABLE",
    )

private class BoundedCache<K, T> {
    private val entries = linkedMapOf<K, Cached<T>>()

    @Synchronized fun get(
        key: K,
        now: Long,
    ): T? = entries[key]?.takeIf { it.until > now }?.value

    @Synchronized fun put(
        key: K,
        value: T,
        until: Long,
        weight: Int = 8192,
    ) {
        if (weight > 1024 * 1024) return
        entries[key] = Cached(value, until, weight)
        while (entries.size > 256 || entries.values.sumOf { it.weight } > 8 * 1024 * 1024) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() = entries.clear()
}

class ProviderPipelines(
    private val registry: Registry,
    private val now: () -> Long = System::currentTimeMillis,
    private val trackLookup: suspend (TrackIdentity) -> Track? = { null },
) {
    val metadataPolicy = MutableStateFlow(MetadataPolicy())
    val audioPolicy = MutableStateFlow(AudioPolicy())
    val diagnostics = MutableStateFlow<List<ProviderDiagnostic>>(emptyList())
    private val metadataCache = BoundedCache<MetadataCacheKey, MetadataContribution>()
    private val audioCache = BoundedCache<AudioCacheKey, AudioResult>()
    private val matchSearch = ProviderMatchSearch(registry, now)
    private val catalogRecommendations = CatalogRecommendations(registry, now)
    private val catalogLibrary = CatalogLibrary(registry, now)
    private val cacheRevision = AtomicLong()

    fun invalidateCaches() {
        cacheRevision.incrementAndGet()
        metadataCache.clear()
        audioCache.clear()
        matchSearch.clear()
        catalogRecommendations.invalidate()
        catalogLibrary.invalidate()
    }

    private fun record(
        point: String,
        id: String,
        outcome: String,
        start: Long,
        attempt: Int = 0,
    ) {
        diagnostics.update {
            (
                it +
                    ProviderDiagnostic(
                        point,
                        id,
                        outcome,
                        (System.nanoTime() - start) / 1_000_000.0,
                        attempt,
                    )
            ).takeLast(128)
        }
    }

    suspend fun metadata(base: MetadataDocument): MetadataDocument =
        withContext(Dispatchers.Default) {
            val policy = metadataPolicy.value
            require(
                policy.mode in setOf(ResolutionMode.MERGE, ResolutionMode.CHAIN, ResolutionMode.FIRST_SUCCESS, ResolutionMode.REPLACE),
            ) {
                "Unsupported metadata mode"
            }
            var document = base
            val sources = registry.metadataSources().let { if (policy.mode == ResolutionMode.REPLACE) it.take(1) else it }
            for (entry in sources) {
                currentCoroutineContext().ensureActive()
                val request =
                    MetadataRequest(
                        document.identity,
                        if (policy.mode ==
                            ResolutionMode.CHAIN
                        ) {
                            document
                        } else {
                            base
                        },
                        policy.configurations[entry.id] ?: JsonObject(emptyMap()),
                    )
                val cacheKey = MetadataCacheKey(cacheRevision.get(), registry.providerEpoch, entry.id, request, policy)
                val start = System.nanoTime()
                val contribution =
                    try {
                        metadataCache.get(cacheKey, now())?.also { record("metadata.resolve", entry.id, "CACHE", start) }
                            ?: withTimeoutOrNull(policy.timeoutMillis.coerceIn(1, 30_000)) { entry.provider.resolve(request) }?.also {
                                if (policy.cacheTtlMillis > 0 &&
                                    it.fields.size <= 256
                                ) {
                                    metadataCache.put(
                                        cacheKey,
                                        it,
                                        now() + policy.cacheTtlMillis.coerceAtMost(86_400_000),
                                        it.fields.values.sumOf { value -> value.toString().length * 2 } + 1024,
                                    )
                                }
                            }
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        registry.reportFailure(entry.owner, "metadata.resolve", e)
                        record("metadata.resolve", entry.id, "ERROR:${e::class.simpleName}", start)
                        null
                    }
                if (contribution == null) {
                    record("metadata.resolve", entry.id, "NO_RESULT_OR_TIMEOUT", start)
                    continue
                }
                if (contribution.fields.values.none(MetadataMapping::meaningful)) {
                    record("metadata.resolve", entry.id, "EMPTY_RESULT", start)
                    continue
                }
                if (contribution.fields.size > 256 || contribution.fields.values.any { it.toString().length > 65536 } ||
                    contribution.identity.identifiers.size > 64
                ) {
                    record("metadata.resolve", entry.id, "RESPONSE_LIMIT", start)
                    continue
                }
                if (registry.metadataSources().none { it.id == entry.id && it.provider === entry.provider }) {
                    record("metadata.resolve", entry.id, "DEACTIVATED", start)
                    continue
                }
                val match = MetadataMapping.match(document.identity, contribution.identity)
                if (match == null) {
                    record("metadata.resolve", entry.id, "IDENTITY_MISMATCH", start)
                    continue
                }
                if (!contribution.confidence.isFinite() ||
                    contribution.confidence !in 0.0..1.0
                ) {
                    record("metadata.resolve", entry.id, "INVALID_CONFIDENCE", start)
                    continue
                }
                val fields = document.fields.toMutableMap()
                for ((field, raw) in contribution.fields) {
                    if (field.length > 120 || !MetadataMapping.meaningful(raw)) continue
                    val value =
                        if (raw is JsonPrimitive &&
                            raw.isString
                        ) {
                            JsonPrimitive(
                                if (field ==
                                    "isrc"
                                ) {
                                    MetadataMapping.normalizeIsrc(raw.content)
                                } else {
                                    raw.content.trim()
                                },
                            )
                        } else {
                            raw
                        }
                    val existing = fields[field]
                    val preferred = policy.fieldProviders[field]
                    val wins =
                        existing == null || existing.provenance.provider == "builtin" || preferred == entry.id ||
                            (
                                preferred != existing.provenance.provider &&
                                    (policy.mode == ResolutionMode.CHAIN || contribution.confidence > existing.provenance.confidence)
                            )
                    if (wins) fields[field] = MetadataField(value, FieldProvenance(entry.id, contribution.confidence, now(), match))
                }
                document =
                    document.copy(
                        identity =
                            document.identity.copy(
                                identifiers =
                                    document.identity.identifiers + contribution.identity.identifiers,
                                isrc =
                                    document.identity.isrc ?: contribution.identity.isrc,
                            ),
                        fields = fields,
                    )
                record("metadata.resolve", entry.id, "SUCCESS", start)
                if (policy.mode in setOf(ResolutionMode.REPLACE, ResolutionMode.FIRST_SUCCESS)) break
            }
            document
        }

    suspend fun audio(
        query: AudioQuery,
        includeBuiltin: Boolean = true,
        excludedProviders: Set<String> = emptySet(),
    ): AudioResult = audioInternal(query, includeBuiltin, excludedProviders, allowFallback = true)

    suspend fun audioExact(
        query: AudioQuery,
        excludedMatches: Set<String> = emptySet(),
    ): AudioResult {
        require(!query.sourceId.isNullOrBlank()) { "Exact audio matching requires a source" }
        return audioInternal(query, includeBuiltin = false, excludedProviders = excludedMatches, allowFallback = false)
    }

    private suspend fun audioInternal(
        query: AudioQuery,
        includeBuiltin: Boolean,
        excludedProviders: Set<String>,
        allowFallback: Boolean = true,
    ): AudioResult =
        withContext(Dispatchers.Default) {
            val policy = audioPolicy.value
            val candidates =
                registry.audioSources().filter { (includeBuiltin || it.owner != "builtin") && it.id !in excludedProviders }.let { entries ->
                    (
                        query.sourceId
                            ?: policy.preferredSource
                    )?.let { source ->
                        val selected = entries.filter { it.id == source }
                        if (policy.manualSourceFallback && allowFallback) selected + entries.filterNot { it.id == source } else selected
                    } ?: entries
                }

            suspend fun resolve(
                entry: ProviderEntry<AudioSource>,
                identity: TrackIdentity,
            ): AudioResult? {
                if (registry.audioSources().none { it.id == entry.id && it.provider === entry.provider }) return null
                if (entry.provider.capabilities.requirements
                        .any { it.startsWith("platform:") && it != "platform:${query.platform}" }
                ) {
                    return null
                }
                val request = query.copy(identity = identity, configuration = policy.configurations[entry.id] ?: query.configuration)
                val requestedMediaId = identity.identifiers["youtube"] ?: identity.canonicalId
                if (audioMatchProfile(entry.id, requestedMediaId) in excludedProviders) return null
                val cacheKey = AudioCacheKey(cacheRevision.get(), registry.providerEpoch, entry.id, request, policy)
                audioCache.get(cacheKey, now())?.let { cached ->
                    if (cached.stream?.attributes?.get("matchProfile") !in excludedProviders) return cached
                    return null
                }
                for (attempt in 0..policy.retries.coerceIn(0, 2)) {
                    currentCoroutineContext().ensureActive()
                    val start = System.nanoTime()
                    var thrownFailure: Throwable? = null
                    val result =
                        try {
                            withTimeoutOrNull(policy.timeoutMillis.coerceIn(1, 30_000)) { entry.provider.resolve(request) }
                                ?: AudioResult(error = ProviderError("TIMEOUT", retryable = true))
                        } catch (
                            e: CancellationException,
                        ) {
                            throw e
                        } catch (e: Exception) {
                            thrownFailure = e
                            AudioResult(error = ProviderError("EXCEPTION", retryable = true))
                        }
                    if (registry.audioSources().none { it.id == entry.id && it.provider === entry.provider }) {
                        record("audio.resolve", entry.id, "DEACTIVATED", start, attempt)
                        break
                    }
                    val stream = result.stream
                    val expires = stream?.expiresAtMillis
                    val bitrate = stream?.bitrate
                    val maximum = request.quality.maxBitrate
                    val valid =
                        stream != null && stream.locator.length in 1..8192 && stream.attributes.size <= 64 && stream.headers.size <= 64 &&
                            stream.headers.all { (name, value) ->
                                name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.length <= 8192 &&
                                    '\r' !in value &&
                                    '\n' !in value
                            } &&
                            (stream.contentLength?.let { it > 0 } ?: true) && (stream.sampleRate?.let { it > 0 } ?: true) &&
                            stream.transport in entry.provider.capabilities.transports &&
                            (expires == null || expires > now() + 1000) && (maximum == null || bitrate == null || bitrate <= maximum)
                    if (valid) {
                        val resolvedMediaId =
                            stream.attributes["resolvedMediaId"]?.takeIf { it.isNotBlank() && it.length <= 2048 }
                                ?: requestedMediaId
                        val profile = audioMatchProfile(entry.id, resolvedMediaId)
                        if (profile in excludedProviders) {
                            record("audio.resolve", entry.id, "EXCLUDED_MATCH", start, attempt)
                            return null
                        }
                        record("audio.resolve", entry.id, "SUCCESS", start, attempt)
                        val ownedResult =
                            result.copy(
                                stream =
                                    stream.copy(
                                        attributes =
                                            stream.attributes +
                                                mapOf(
                                                    "providerId" to entry.id,
                                                    "resolvedMediaId" to resolvedMediaId,
                                                    "matchProfile" to profile,
                                                ),
                                    ),
                            )
                        stream.expiresAtMillis?.let { expiry ->
                            if (policy.cacheTtlMillis >
                                0
                            ) {
                                audioCache.put(
                                    cacheKey,
                                    ownedResult,
                                    minOf(expiry - 1000, now() + policy.cacheTtlMillis.coerceAtMost(86_400_000)),
                                )
                            }
                        }
                        return ownedResult
                    }
                    val error = result.error
                    val outcome = error?.code ?: if (stream == null) "NO_MATCH" else "INVALID_DESCRIPTOR"
                    val diagnosticCode = audioDiagnosticCode(outcome)
                    val shouldReportToRegistry =
                        thrownFailure != null ||
                            (error != null && outcome !in EXPECTED_AUDIO_SKIP_CODES && diagnosticCode !in EXPECTED_AUDIO_SKIP_CODES) ||
                            (error == null && stream != null)
                    if (shouldReportToRegistry) {
                        registry.reportFailure(
                            entry.owner,
                            "audio.resolve",
                            thrownFailure ?: PluginProviderException(
                                outcome,
                                error?.retryable == true,
                                attempt + 1,
                                (System.nanoTime() - start) / 1_000_000,
                            ),
                        )
                    }
                    record(
                        "audio.resolve",
                        entry.id,
                        diagnosticCode,
                        start,
                        attempt,
                    )
                    if (result.error?.retryable != true) break
                    if (attempt < policy.retries) delay(25L * (attempt + 1))
                }
                return null
            }
            for (entry in candidates) {
                resolve(entry, query.identity)?.let { return@withContext it }
            }
            if (!allowFallback) return@withContext AudioResult(error = ProviderError("NO_SOURCE"))
            val sourceTrack =
                try {
                    withTimeoutOrNull(8000) { trackLookup(query.identity) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            if (sourceTrack != null) {
                val matchingSources =
                    candidates.filter { entry ->
                        entry.provider.capabilities.requirements.none {
                            it.startsWith("platform:") && it != "platform:${query.platform}"
                        }
                    }
                val matches = matchSearch.search(sourceTrack, query.identity, matchingSources, policy.timeoutMillis)
                for (match in matches) {
                    val entry = matchingSources.firstOrNull { it.id == match.provider } ?: continue
                    val identity =
                        TrackIdentity(
                            match.track.id,
                            match.track.extras + mapOf("pluginOwner" to entry.owner, "providerTrackId" to match.track.id),
                            match.track.extras["isrc"],
                        )
                    resolve(entry, identity)?.let { return@withContext it }
                }
            }
            AudioResult(error = ProviderError("NO_SOURCE", "No configured audio provider succeeded"))
        }

    suspend fun audioMatches(
        metadata: Track,
        identity: TrackIdentity,
        platform: String,
        query: String? = null,
    ): List<MatchCandidate> {
        val sources =
            registry.audioSources().filter { entry ->
                entry.owner != "builtin" &&
                    entry.provider.capabilities.requirements.none {
                        it.startsWith("platform:") && it != "platform:$platform"
                    }
            }
        return matchSearch.search(metadata, identity, sources, audioPolicy.value.timeoutMillis, query, manual = true)
    }

    suspend fun lyrics(request: LyricsRequest): LyricsResult? =
        withContext(Dispatchers.Default) {
            for (entry in registry.lyricsProviders()) {
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val result =
                    try {
                        withTimeoutOrNull(10_000) { entry.provider.lyrics(request) }
                    } catch (
                        error: CancellationException,
                    ) {
                        throw error
                    } catch (
                        error: Exception,
                    ) {
                        registry.reportFailure(entry.owner, "lyrics.resolve", error)
                        record("lyrics.resolve", entry.id, "ERROR:${error::class.simpleName}", start)
                        null
                    }
                if (registry.lyricsProviders().none { it.id == entry.id && it.provider === entry.provider }) continue
                if (result != null && result.lines.size <= 10_000 && result.lines.any { it.text.isNotBlank() } &&
                    result.lines.all { it.text.length <= 16_384 && it.words.size <= 1024 && (it.timeMs?.let { time -> time >= 0 } ?: true) }
                ) {
                    record("lyrics.resolve", entry.id, "SUCCESS", start)
                    return@withContext result.copy(source = entry.id)
                }
                record("lyrics.resolve", entry.id, "NO_RESULT_OR_TIMEOUT", start)
            }
            null
        }

    suspend fun search(
        request: SearchRequest,
        allowedOwners: Set<String>? = null,
    ): SearchResult =
        withContext(Dispatchers.Default) {
            val limit = request.limit.coerceIn(1, 100)
            val tracks = linkedMapOf<String, Track>()
            val albums = linkedMapOf<String, CatalogAlbum>()
            val artists = linkedMapOf<String, CatalogArtist>()
            val playlists = linkedMapOf<String, CatalogPlaylist>()
            for (entry in registry.searchProviders().filter { allowedOwners == null || it.owner in allowedOwners }) {
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val result =
                    try {
                        withTimeoutOrNull(10_000) { entry.provider.search(request.copy(limit = limit)) }
                    } catch (
                        error: CancellationException,
                    ) {
                        throw error
                    } catch (
                        error: Exception,
                    ) {
                        registry.reportFailure(entry.owner, "search.resolve", error)
                        record("search.resolve", entry.id, "ERROR:${error::class.simpleName}", start)
                        null
                    }
                if (result == null || registry.searchProviders().none { it.id == entry.id && it.provider === entry.provider }) continue
                for (track in result.tracks.take(limit)) {
                    if (track.id.isBlank() || track.id.length > 2048 || track.title.isBlank()) continue
                    val id = "metrofuse:${entry.owner}:${track.id}"
                    tracks.putIfAbsent(
                        id,
                        track.copy(
                            id = id,
                            extras =
                                track.extras + mapOf("providerTrackId" to track.id, "pluginOwner" to entry.owner),
                        ),
                    )
                }
                result.albums.take(limit).forEach { album ->
                    val mapped = ownedAlbum(entry.owner, album)
                    albums.putIfAbsent(mapped.id, mapped)
                }
                result.artists.take(limit).forEach { artist ->
                    val mapped = ownedArtist(entry.owner, artist)
                    artists.putIfAbsent(mapped.id, mapped)
                }
                result.playlists.take(limit).forEach { playlist ->
                    val mapped = ownedPlaylist(entry.owner, playlist)
                    playlists.putIfAbsent(mapped.id, mapped)
                }
                record("search.resolve", entry.id, "SUCCESS", start)
            }
            SearchResult(
                tracks.values.take(limit),
                albums.values.take(limit),
                artists.values.take(limit),
                playlists = playlists.values.take(limit),
            )
        }

    fun home(
        owner: String,
        shelves: List<HomeShelf>,
    ): List<HomeShelf> =
        shelves.map { shelf ->
            shelf.copy(
                items =
                    shelf.items.map { item ->
                        HomeItem(
                            track = item.track?.let { ownedTrack(owner, it) },
                            album = item.album?.let { ownedAlbum(owner, it) },
                            artist = item.artist?.let { ownedArtist(owner, it) },
                            playlist = item.playlist?.let { ownedPlaylist(owner, it) },
                        )
                    },
            )
        }

    private fun ownedTrack(
        owner: String,
        track: Track,
    ): Track =
        track.copy(
            id = "metrofuse:$owner:${track.id}",
            extras = track.extras + mapOf("providerTrackId" to track.id, "pluginOwner" to owner),
        )

    private fun ownedAlbum(
        owner: String,
        value: CatalogAlbum,
    ): CatalogAlbum =
        value.copy(
            id = "metrofuse:$owner:${value.id}",
            tracks =
                value.tracks.take(10_000).map {
                    ownedTrack(owner, it)
                },
        )

    private fun ownedArtist(
        owner: String,
        value: CatalogArtist,
    ): CatalogArtist =
        value.copy(
            id = "metrofuse:$owner:${value.id}",
            tracks =
                value.tracks.take(10_000).map {
                    ownedTrack(owner, it)
                },
            albums = value.albums.take(100).map { ownedAlbum(owner, it) },
        )

    private fun ownedPlaylist(
        owner: String,
        value: CatalogPlaylist,
    ): CatalogPlaylist =
        value.copy(
            id = "metrofuse:$owner:${value.id}",
            tracks =
                value.tracks.take(10_000).map {
                    ownedTrack(owner, it)
                },
        )

    private suspend fun <T> catalog(
        id: String,
        call: suspend (CatalogProvider, String) -> T?,
    ): Pair<String, T>? =
        withContext(Dispatchers.Default) {
            if (!id.startsWith("metrofuse:")) return@withContext null
            val owner = id.substringAfter(':').substringBefore(':')
            val original = id.substringAfter("metrofuse:$owner:")
            for (entry in registry.catalogProviders().filter { it.owner == owner }) {
                currentCoroutineContext().ensureActive()
                val start = System.nanoTime()
                val result =
                    try {
                        withTimeoutOrNull(10_000) { call(entry.provider, original) }
                    } catch (
                        error: CancellationException,
                    ) {
                        throw error
                    } catch (
                        error: Exception,
                    ) {
                        registry.reportFailure(entry.owner, "catalog.resolve", error)
                        record("catalog.resolve", entry.id, "ERROR:${error::class.simpleName}", start)
                        null
                    }
                if (result != null && registry.catalogProviders().any { it.id == entry.id && it.provider === entry.provider }) {
                    record("catalog.resolve", entry.id, "SUCCESS", start)
                    return@withContext owner to result
                }
            }
            null
        }

    suspend fun album(id: String): CatalogAlbum? =
        catalog(id) { provider, original ->
            provider.album(original)?.takeIf {
                it.id == original
            }
        }?.let { (owner, value) -> ownedAlbum(owner, value) }

    suspend fun artist(id: String): CatalogArtist? =
        catalog(id) { provider, original ->
            provider.artist(original)?.takeIf {
                it.id ==
                    original
            }
        }?.let { (owner, value) -> ownedArtist(owner, value) }

    suspend fun playlist(id: String): CatalogPlaylist? =
        catalog(id) { provider, original ->
            provider.playlist(original)?.takeIf {
                it.id ==
                    original
            }
        }?.let { (owner, value) -> ownedPlaylist(owner, value) }

    suspend fun recommendations(
        identity: TrackIdentity,
        allowedOwners: Set<String>,
        limit: Int = 100,
    ): RecommendationResult? =
        catalogRecommendations.start(identity, allowedOwners, limit)?.let { (owner, result) ->
            result.copy(tracks = result.tracks.map { ownedTrack(owner, it) })
        }

    suspend fun continueRecommendations(
        continuation: String,
        allowedOwners: Set<String>,
        limit: Int = 100,
    ): RecommendationResult? =
        catalogRecommendations.continuePage(continuation, allowedOwners, limit)?.let { (owner, result) ->
            result.copy(tracks = result.tracks.map { ownedTrack(owner, it) })
        }

    suspend fun library(
        owner: String,
        request: LibraryRequest,
    ): LibraryPage? =
        catalogLibrary.page(owner, request)?.let { page ->
            page.copy(
                items =
                    page.items.map { item ->
                        item.copy(
                            track = item.track?.let { ownedTrack(owner, it) },
                            album = item.album?.let { ownedAlbum(owner, it) },
                            artist = item.artist?.let { ownedArtist(owner, it) },
                            playlist = item.playlist?.let { ownedPlaylist(owner, it) },
                        )
                    },
            )
        }

    fun supportsSongVotes(owner: String): Boolean = catalogLibrary.supportsSongVotes(owner)

    suspend fun setSongVote(
        owner: String,
        request: SongVoteRequest,
    ): Boolean = catalogLibrary.setSongVote(owner, request)
}
