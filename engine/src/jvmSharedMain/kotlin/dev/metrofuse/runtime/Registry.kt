package dev.metrofuse.runtime

import dev.metrofuse.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

data class Owned<T>(
    val owner: String,
    val value: T,
)

data class ProviderEntry<T>(
    val owner: String,
    val id: String,
    val provider: T,
)

data class RegistryState(
    val ui: List<UiContribution> = emptyList(),
    val priorities: Map<String, Int> = emptyMap(),
    val selections: Map<String, String> = emptyMap(),
    val base: Set<String> = emptySet(),
    val globalOrder: List<String> = emptyList(),
    val pointOrder: Map<String, List<String>> = emptyMap(),
    val pointPriorities: Map<String, Map<String, Int>> = emptyMap(),
    val dependencies: Map<String, Set<String>> = emptyMap(),
    val serviceModes: Map<String, ResolutionMode> = emptyMap(),
    val capabilityRevision: Long = 0,
)

data class ResolutionExplanation(
    val point: String,
    val candidates: List<String>,
    val winner: String?,
    val reason: String,
    val conflict: Boolean,
)

/** All snapshots are immutable. Equal priorities are resolved by plugin ID, never load order. */
class Registry {
    val state = MutableStateFlow(RegistryState())
    val serviceDiagnostics = MutableStateFlow<List<ProviderDiagnostic>>(emptyList())
    private val services = mutableMapOf<String, List<Owned<JsonService>>>()
    private val contracts = mutableMapOf<String, Owned<ServiceContract>>()
    private val hooks = mutableMapOf<Pair<String, HookPhase>, List<Owned<JsonService>>>()

    @Synchronized fun contract(
        owner: String,
        contract: ServiceContract,
    ) {
        require(contract.defaultMode in contract.modes && ResolutionMode.DECORATE !in contract.modes) { "DECORATE is a UI contract" }
        require(contracts[contract.name]?.owner.let { it == null || it == owner }) { "Contract already defined" }
        contracts[contract.name] = Owned(owner, contract)
    }

    @Synchronized fun hook(
        owner: String,
        name: String,
        phase: HookPhase,
        observer: JsonService,
    ) {
        hooks[name to phase] =
            hooks[name to phase].orEmpty() + Owned(owner, observer)
    }

    fun serviceMode(
        name: String,
        mode: ResolutionMode,
    ) {
        require(mode in (contracts[name]?.value?.modes ?: setOf(ResolutionMode.REPLACE))) { "Mode not supported by $name" }
        state.update { it.copy(serviceModes = it.serviceModes + (name to mode)) }
    }

    suspend fun invoke(
        name: String,
        request: JsonObject,
    ): JsonObject {
        val contract = synchronized(this) { contracts[name]?.value ?: ServiceContract(name) }
        val mode = state.value.serviceModes[name] ?: contract.defaultMode
        require(mode in contract.modes) { "Unsupported resolution mode" }
        val candidates = serviceCandidates(name)
        require(candidates.isNotEmpty()) { "Missing service $name" }

        suspend fun observe(
            phase: HookPhase,
            payload: JsonObject,
        ) {
            val handlers = synchronized(this) { ordered(name, hooks[name to phase].orEmpty()) }
            handlers.forEach { withTimeout(1500) { it.value.call(payload) } }
        }
        observe(HookPhase.BEFORE, request)

        suspend fun call(
            p: Owned<JsonService>,
            value: JsonObject,
        ): JsonObject {
            val start = System.nanoTime()
            try {
                val result = withTimeout(30_000) { p.value.call(value) }
                serviceDiagnostics.update {
                    (
                        it +
                            ProviderDiagnostic(name, p.owner, "SUCCESS", (System.nanoTime() - start) / 1_000_000.0)
                    ).takeLast(128)
                }
                return result
            } catch (e: Exception) {
                serviceDiagnostics.update {
                    (
                        it +
                            ProviderDiagnostic(name, p.owner, "ERROR:${e::class.simpleName}", (System.nanoTime() - start) / 1_000_000.0)
                    ).takeLast(128)
                }
                throw e
            }
        }
        val result =
            when (mode) {
                ResolutionMode.REPLACE -> {
                    call(candidates.first(), request)
                }

                ResolutionMode.CHAIN -> {
                    var result = request
                    for (p in candidates) result = call(p, result)
                    result
                }

                ResolutionMode.MERGE -> {
                    val result = linkedMapOf<String, JsonElement>()
                    for (p in candidates) call(p, request).forEach { (key, value) -> result.putIfAbsent(key, value) }
                    JsonObject(result)
                }

                ResolutionMode.OBSERVE -> {
                    candidates.forEach { call(it, request) }
                    request
                }

                ResolutionMode.FIRST_SUCCESS -> {
                    var result: JsonObject? = null
                    for (p in candidates) {
                        try {
                            val value = call(p, request)
                            if (value["error"] == null &&
                                value["success"] != JsonPrimitive(false)
                            ) {
                                result = value
                                break
                            }
                        } catch (
                            e: kotlinx.coroutines.CancellationException,
                        ) {
                            if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                        } catch (
                            e: Exception,
                        ) {
                            // Failure is part of the explicitly selected fallback contract.
                        }
                    }
                    requireNotNull(result) { "All providers failed for $name" }
                }

                ResolutionMode.DECORATE -> {
                    error("DECORATE is UI-only")
                }
            }
        observe(HookPhase.AFTER, result)
        return result
    }

    private val metadata = mutableListOf<Owned<MetadataProvider>>()
    private val audio = mutableMapOf<String, Owned<AudioProvider>>()
    private val components = mutableMapOf<String, List<Owned<NativeComponent>>>()
    private val metadataSources = mutableMapOf<String, ProviderEntry<MetadataSource>>()
    private val audioSources = mutableMapOf<String, ProviderEntry<AudioSource>>()
    private val lyricsProviders = mutableMapOf<String, ProviderEntry<LyricsProvider>>()
    private val searchProviders = mutableMapOf<String, ProviderEntry<SearchProvider>>()
    private val catalogProviders = mutableMapOf<String, ProviderEntry<CatalogProvider>>()

    /** Activation publishes all staged capability registrations before the single UI snapshot. */
    @Synchronized fun publish(
        staged: Registry,
        owner: String,
    ) {
        require(
            staged.metadataSources.keys.none {
                it in metadataSources
            } && staged.audioSources.keys.none { it in audioSources },
        ) { "Provider ID conflict" }
        require(staged.contracts.keys.none { it in contracts }) { "Contract conflict" }
        require(
            staged.lyricsProviders.keys.none {
                it in lyricsProviders
            } && staged.searchProviders.keys.none { it in searchProviders },
        ) { "Provider ID conflict" }
        require(staged.catalogProviders.keys.none { it in catalogProviders }) { "Catalog provider ID conflict" }
        contracts.putAll(staged.contracts)
        staged.hooks.forEach { (name, values) -> hooks[name] = hooks[name].orEmpty() + values }
        staged.services.forEach { (name, values) -> services[name] = services[name].orEmpty() + values }
        require(staged.audio.keys.none { it in audio }) { "Audio provider conflict" }
        metadata.addAll(staged.metadata)
        audio.putAll(staged.audio)
        staged.components.forEach { (name, values) -> components[name] = components[name].orEmpty() + values }
        metadataSources.putAll(staged.metadataSources)
        audioSources.putAll(staged.audioSources)
        providerEpoch++
        lyricsProviders.putAll(staged.lyricsProviders)
        searchProviders.putAll(staged.searchProviders)
        catalogProviders.putAll(staged.catalogProviders)
        state.update {
            it.copy(
                ui = it.ui.filterNot { u -> u.owner == owner } + staged.state.value.ui,
                capabilityRevision =
                    it.capabilityRevision + 1,
            )
        }
    }

    @Volatile var providerEpoch: Long = 0
        private set

    private fun validateProviderId(id: String) {
        require(id.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,119}"))) { "Invalid provider ID" }
    }

    @Synchronized fun metadataSource(
        owner: String,
        provider: MetadataSource,
    ) {
        validateProviderId(provider.id)
        require(metadataSources.values.count { it.owner == owner } < 64)
        require(!metadataSources.containsKey(provider.id)) { "Duplicate metadata source ${provider.id}" }
        metadataSources[provider.id] =
            ProviderEntry(owner, provider.id, provider)
        providerEpoch++
    }

    @Synchronized fun audioSource(
        owner: String,
        provider: AudioSource,
    ) {
        validateProviderId(provider.id)
        require(audioSources.values.count { it.owner == owner } < 64)
        require(!audioSources.containsKey(provider.id)) { "Duplicate audio source ${provider.id}" }
        audioSources[provider.id] =
            ProviderEntry(owner, provider.id, provider)
        providerEpoch++
    }

    @Synchronized fun metadataSources() = orderedProviders("metadata.resolve", metadataSources.values.toList())

    @Synchronized fun audioSources() = orderedProviders("audio.resolve", audioSources.values.toList())

    @Synchronized fun lyricsProvider(
        owner: String,
        provider: LyricsProvider,
    ) {
        validateProviderId(provider.id)
        require(lyricsProviders.values.count { it.owner == owner } < 64)
        require(!lyricsProviders.containsKey(provider.id)) { "Duplicate lyrics provider ${provider.id}" }
        lyricsProviders[provider.id] = ProviderEntry(owner, provider.id, provider)
        providerEpoch++
    }

    @Synchronized fun searchProvider(
        owner: String,
        provider: SearchProvider,
    ) {
        validateProviderId(provider.id)
        require(searchProviders.values.count { it.owner == owner } < 64)
        require(!searchProviders.containsKey(provider.id)) { "Duplicate search provider ${provider.id}" }
        searchProviders[provider.id] = ProviderEntry(owner, provider.id, provider)
        providerEpoch++
    }

    @Synchronized fun lyricsProviders() = orderedProviders("lyrics.resolve", lyricsProviders.values.toList())

    @Synchronized fun searchProviders() = orderedProviders("search.resolve", searchProviders.values.toList())

    @Synchronized fun catalogProvider(
        owner: String,
        provider: CatalogProvider,
    ) {
        validateProviderId(provider.id)
        require(catalogProviders.values.count { it.owner == owner } < 64)
        require(!catalogProviders.containsKey(provider.id)) { "Duplicate catalog provider ${provider.id}" }
        catalogProviders[provider.id] = ProviderEntry(owner, provider.id, provider)
        providerEpoch++
    }

    @Synchronized fun catalogProviders() = orderedProviders("catalog.resolve", catalogProviders.values.toList())

    fun <T> orderedProviders(
        point: String,
        entries: List<ProviderEntry<T>>,
    ): List<ProviderEntry<T>> {
        val s = state.value

        fun depends(
            owner: String,
            dependency: String,
            seen: Set<String> = emptySet(),
        ): Boolean =
            owner !in seen &&
                (
                    dependency in s.dependencies[owner].orEmpty() ||
                        s.dependencies[owner].orEmpty().any { depends(it, dependency, seen + owner) }
                )
        val graph = entries.associate { p -> p.id to entries.filter { depends(p.owner, it.owner) }.map { it.id }.toSet() }
        val preferred =
            s.pointOrder[point]
                .orEmpty()
                .flatMap { id ->
                    entries.filter { it.id == id || it.owner == id }.map { it.id }
                }.distinct()
        val global = s.globalOrder.flatMap { id -> entries.filter { it.owner == id }.map { it.id } }
        val priorities = entries.associate { it.id to (s.priorities[it.owner] ?: 0) }
        val local = entries.associate { it.id to (s.pointPriorities[point]?.get(it.id) ?: s.pointPriorities[point]?.get(it.owner) ?: 0) }
        val order = PluginOrdering.resolve(entries.map { it.id }, graph, preferred, global, priorities, local)
        return order.map { id -> entries.first { it.id == id } }
    }

    @Synchronized fun component(
        owner: String,
        name: String,
        value: NativeComponent,
    ) {
        validateProviderId(name)
        components[name] =
            components[name].orEmpty().filterNot { it.owner == owner } + Owned(owner, value)
        state.update {
            it.copy(
                capabilityRevision =
                    it.capabilityRevision + 1,
            )
        }
    }

    @Synchronized fun component(name: String): NativeComponent? =
        ordered(
            "component.$name",
            components[name].orEmpty(),
        ).firstOrNull()?.value

    @Synchronized fun componentNames() = components.keys.toList().sorted()

    @Synchronized fun ui(value: UiContribution) {
        require(value.point.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,119}"))) { "Invalid extension point" }
        require(
            state.value.ui.count { it.owner == value.owner } < 128 ||
                state.value.ui.any { it.owner == value.owner && it.point == value.point && it.layer == value.layer },
        ) { "Plugin contribution limit exceeded" }
        state.update {
            it.copy(
                ui =
                    it.ui.filterNot { old -> old.owner == value.owner && old.point == value.point && old.layer == value.layer } + value,
            )
        }
    }

    @Synchronized fun service(
        owner: String,
        name: String,
        value: JsonService,
    ) {
        require(name.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]+/v[1-9][0-9]*"))) { "Services require a /vN suffix" }
        services[name] = services[name].orEmpty().filterNot { it.owner == owner } + Owned(owner, value)
    }

    @Synchronized fun metadata(
        owner: String,
        provider: MetadataProvider,
    ) {
        metadata += Owned(owner, provider)
    }

    @Synchronized fun audio(
        owner: String,
        id: String,
        provider: AudioProvider,
    ) {
        require(!audio.containsKey(id))
        audio[id] =
            Owned(owner, provider)
    }

    @Synchronized fun audioProviders(): Map<String, Owned<AudioProvider>> = audio.toMap()

    @Synchronized fun metadataProviders(): List<Owned<MetadataProvider>> = ordered("metadata.resolve", metadata)

    @Synchronized fun serviceCandidates(name: String): List<Owned<JsonService>> = ordered(name, services[name].orEmpty())

    @Synchronized fun service(name: String): Owned<JsonService>? = serviceCandidates(name).firstOrNull()

    @Synchronized fun serviceNames() = services.keys.toList().sorted()

    @Synchronized fun serviceContract(name: String) = contracts[name]?.value ?: ServiceContract(name)

    fun order(
        point: String,
        owners: Collection<String>,
    ): List<String> {
        val s = state.value
        return PluginOrdering.resolve(
            owners.distinct(),
            s.dependencies,
            s.pointOrder[point].orEmpty(),
            s.globalOrder,
            s.priorities,
            s.pointPriorities[point].orEmpty(),
        )
    }

    fun <T> ordered(
        point: String,
        values: List<Owned<T>>,
    ): List<Owned<T>> {
        val indices = order(point, values.map { it.owner }).withIndex().associate { it.value to it.index }
        return values.sortedBy { indices[it.owner] }
    }

    fun graph(manifests: List<Manifest>) {
        state.update { it.copy(dependencies = manifests.associate { m -> m.id to m.dependencies.keys }) }
    }

    fun reorder(
        point: String?,
        owners: List<String>,
    ) {
        require(owners.size <= 512 && owners.distinct().size == owners.size)
        state.update { if (point == null) it.copy(globalOrder = owners) else it.copy(pointOrder = it.pointOrder + (point to owners)) }
    }

    /** Reorders a visible subset without discarding preferences for disabled providers. */
    @Synchronized fun reorderVisible(
        point: String?,
        ids: List<String>,
    ) {
        require(ids.size <= 512 && ids.distinct().size == ids.size)
        state.update { snapshot ->
            val previous = if (point == null) snapshot.globalOrder else snapshot.pointOrder[point].orEmpty()
            val providers =
                when (point) {
                    "audio.resolve" -> audioSources.values.map { it.id to it.owner }
                    "metadata.resolve" -> metadataSources.values.map { it.id to it.owner }
                    "lyrics.resolve" -> lyricsProviders.values.map { it.id to it.owner }
                    "search.resolve" -> searchProviders.values.map { it.id to it.owner }
                    else -> emptyList()
                }
            // Older configurations can rank an owner's entire provider group. Explicit IDs must precede those aliases.
            val aliases = providers.filter { it.first in ids && it.second !in ids }.map { it.second }.toSet()
            val remaining = ids.iterator()
            val merged =
                previous.filterNot { it in aliases }.map { if (it in ids) remaining.next() else it } +
                    remaining.asSequence().toList() + previous.filter { it in aliases }
            require(merged.size <= 512)
            if (point == null) {
                snapshot.copy(globalOrder = merged)
            } else {
                snapshot.copy(pointOrder = snapshot.pointOrder + (point to merged))
            }
        }
    }

    fun pointPriority(
        point: String,
        owner: String,
        priority: Int,
    ) {
        state.update {
            it.copy(
                pointPriorities =
                    it.pointPriorities + (point to (it.pointPriorities[point].orEmpty() + (owner to priority.coerceIn(-1000, 1000)))),
            )
        }
    }

    fun select(
        point: String,
        owner: String?,
    ) {
        state.update {
            it.copy(
                selections =
                    if (owner ==
                        null
                    ) {
                        it.selections - point
                    } else {
                        it.selections + (point to owner)
                    },
                base = it.base - point,
            )
        }
    }

    fun restore(point: String) {
        state.update { it.copy(base = it.base + point, selections = it.selections - point) }
    }

    fun restoreAll() {
        state.update { it.copy(base = it.ui.map { u -> u.point }.toSet() + BUILTIN_POINTS, selections = emptyMap()) }
    }

    fun priority(
        owner: String,
        priority: Int,
    ) {
        state.update {
            it.copy(
                priorities =
                    it.priorities + (owner to priority.coerceIn(-1000, 1000)),
            )
        }
    }

    fun layers(point: String): List<UiContribution> {
        val s = state.value
        if (point in s.base) return emptyList()
        val ranks = order(point, s.ui.filter { it.point == point }.map { it.owner }).withIndex().associate { it.value to it.index }
        val all = s.ui.filter { it.point == point }.sortedBy { ranks[it.owner] }
        val replace = all.filter { it.layer == Layer.REPLACE }
        val winner = replace.firstOrNull { it.owner == s.selections[point] } ?: replace.firstOrNull()
        return all.filter { it.layer == Layer.BEFORE } + listOfNotNull(winner) + all.filter { it.layer == Layer.AFTER }
    }

    fun explain(point: String): ResolutionExplanation {
        val s = state.value
        val candidates = order(point, s.ui.filter { it.point == point && it.layer == Layer.REPLACE }.map { it.owner })
        val winner = layers(point).firstOrNull { it.layer == Layer.REPLACE }?.owner
        val reason =
            when {
                point in s.base -> "Explicit built-in fallback"

                s.selections[point] == winner && winner != null -> "User-selected exclusive implementation"

                winner !=
                    null -> "Dependency-aware order, point preferences, global preferences, numeric priority, then plugin ID"

                else -> "No enabled replacement; built-in fallback"
            }
        return ResolutionExplanation(point, candidates, winner, reason, candidates.size > 1)
    }

    @Synchronized fun remove(owner: String) {
        services.replaceAll { _, values -> values.filterNot { it.owner == owner } }
        services.entries.removeAll { it.value.isEmpty() }
        contracts.entries.removeAll { it.value.owner == owner }
        hooks.replaceAll { _, values -> values.filterNot { it.owner == owner } }
        hooks.entries.removeAll { it.value.isEmpty() }
        metadata.removeAll { it.owner == owner }
        audio.entries.removeAll { it.value.owner == owner }
        components.replaceAll { _, values -> values.filterNot { it.owner == owner } }
        components.entries.removeAll { it.value.isEmpty() }
        metadataSources.entries.removeAll { it.value.owner == owner }
        audioSources.entries.removeAll { it.value.owner == owner }
        providerEpoch++
        lyricsProviders.entries.removeAll { it.value.owner == owner }
        searchProviders.entries.removeAll { it.value.owner == owner }
        catalogProviders.entries.removeAll { it.value.owner == owner }
        state.update { it.copy(ui = it.ui.filterNot { u -> u.owner == owner }, capabilityRevision = it.capabilityRevision + 1) }
    }

    fun patch(
        owner: String,
        id: String,
        props: Map<String, String>,
    ) = patch(owner, mapOf(id to props))

    fun patch(
        owner: String,
        changes: Map<String, Map<String, String>>,
    ) {
        require(changes.size <= 64)
        for ((id, props) in changes) {
            require(
                id.length in 1..256 && props.size <= 64 &&
                    props.keys.all { it.length <= 80 } && props.values.all { it.length <= 16384 },
            ) { "Property patch limit" }
        }

        fun change(node: UiNode): UiNode {
            val children = node.children.map(::change)
            val props = changes[node.id]?.let { node.props + it } ?: node.props
            return if (props == node.props && children.indices.all { children[it] === node.children[it] }) {
                node
            } else {
                node.copy(props = props, children = children)
            }
        }
        state.update { snapshot ->
            snapshot.copy(
                ui =
                    snapshot.ui.map { contribution ->
                        val previous = contribution.tree
                        if (contribution.owner != owner || previous == null) {
                            contribution
                        } else {
                            val updated = change(previous)
                            if (updated === previous) {
                                contribution
                            } else {
                                contribution.copy(tree = updated.also(Runtime::validateTree))
                            }
                        }
                    },
            )
        }
    }

    companion object {
        val BUILTIN_POINTS =
            setOf(
                "ui.app",
                "ui.navigation",
                "ui.home",
                "ui.search",
                "ui.library",
                "ui.nowPlaying",
                "ui.miniPlayer",
                "ui.settings",
                "ui.trackRow",
                "ui.metadata.title",
                "ui.metadata.subtitle",
                "ui.background",
                "ui.lyrics",
                "ui.controls",
                "ui.player",
                "ui.fullscreen",
                "ui.settings",
                "ui.overlay",
            )
    }
}
