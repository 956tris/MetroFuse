package dev.metrofuse.runtime

import androidx.compose.ui.graphics.ImageBitmap
import com.dokar.quickjs.*
import com.dokar.quickjs.binding.*
import dev.metrofuse.api.*
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

val codec =
    Json {
        ignoreUnknownKeys = false
        prettyPrint = true
        encodeDefaults = true
    }

data class PluginStatus(
    val manifest: Manifest,
    val directory: File,
    val state: String = "Installed",
    val error: String? = null,
    val phase: LifecyclePhase = LifecyclePhase.INSTALLED,
    val history: List<LifecycleTransition> =
        listOf(LifecycleTransition(LifecyclePhase.DISCOVERED), LifecycleTransition(LifecyclePhase.INSTALLED)),
)

private interface JavaScriptBoundService : JsonService

class Runtime(
    val storage: File,
    val platform: String,
    parentScope: CoroutineScope,
    val host: HostServices,
    private val hostVersion: String? = null,
    private val credentialStore: CredentialStore = UnavailableCredentialStore,
    private val onPluginFailure: (PluginFailure) -> Unit = {},
) {
    private val runtimeJob = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + runtimeJob)
    val registry = Registry(::reportPluginFailure)
    private val failureReports = PluginFailureReports()

    internal fun reportPluginFailure(
        owner: String,
        operation: String,
        error: Throwable,
    ) {
        val manifest = plugins.value.firstOrNull { it.manifest.id == owner }?.manifest ?: return
        failureReports.report(PluginFailure(owner, manifest.version, manifest.runtime, operation, error)) { failure ->
            failure.audioDiagnostic?.let { detail ->
                log(
                    owner,
                    "${failure.operation} ${detail.code}, attempt ${detail.attempt}, ${detail.durationMillis} ms, retryable=${detail.retryable}",
                )
            }
            onPluginFailure(failure)
        }
    }

    val pipelines = ProviderPipelines(registry, trackLookup = host.catalog::identify)
    val libraryAccounts = MutableStateFlow<Map<String, PluginAccountState>>(emptyMap())
    val events = EngineEvents()
    val player: Player = host.playback
    val metadata = MutableStateFlow(player.state.value.track)
    val metadataDocument = MutableStateFlow(MetadataMapping.fromTrack(player.state.value.track))
    val plugins = MutableStateFlow<List<PluginStatus>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val recovery = MutableStateFlow(false)
    private val uiRecovery = PluginUiRecovery(storage)
    val uiSafeMode = MutableStateFlow(uiRecovery.isOriginalUiForced)
    private val uiFailureMarker = File(storage, "ui-failure.json")

    fun forceOriginalUi() {
        uiSafeMode.value = true
    }

    fun beginUiRender(): () -> Unit = uiRecovery.beginRender()

    suspend fun recordUiFailure(owners: Set<String>) =
        withContext(Dispatchers.IO) {
            if (owners.isEmpty()) return@withContext
            uiRecovery.recoverOriginalUi()
            writePluginJson(
                uiFailureMarker,
                buildJsonObject {
                    put("owners", JsonArray(owners.sorted().map(::JsonPrimitive)))
                },
                128 * 1024,
            )
        }

    private val visibleUi = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun watchUi(owner: String): () -> Unit {
        visibleUi.update { it + (owner to (it.getOrDefault(owner, 0) + 1)) }
        return {
            visibleUi.update {
                val remaining = it.getOrDefault(owner, 1) - 1
                if (remaining == 0) it - owner else it + (owner to remaining)
            }
        }
    }

    private fun monitorUi() {
        scope.launch(Dispatchers.Default) {
            visibleUi.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                supervisorScope {
                    while (isActive && !uiSafeMode.value) {
                        delay(2000)
                        val pulse = launch(Dispatchers.Main) {}
                        if (withTimeoutOrNull(12_000) {
                                pulse.join()
                                true
                            } != true
                        ) {
                            val owners = visibleUi.value.keys
                            if (owners.isNotEmpty()) {
                                recordUiFailure(owners)
                                forceOriginalUi()
                            }
                            pulse.cancel()
                            break
                        }
                    }
                }
            }
        }
    }

    suspend fun failUi(
        owner: String,
        error: Throwable,
    ) {
        reportPluginFailure(owner, "ui.render", error)
        forceOriginalUi()
        withContext(Dispatchers.IO) {
            uiRecovery.recoverOriginalUi()
            recordFailure(owner)
            if (failureCounts.getOrDefault(owner, 0) >= 2) quarantine(owner, IllegalStateException("UI rendering failed"))
        }
        log(owner, "UI rendering failed: ${error::class.simpleName}")
    }

    suspend fun reportUiCrash(error: Throwable) {
        val activeOwners = visibleUi.value.keys
        val frames = generateSequence(error) { it.cause }.take(8).flatMap { it.stackTrace.asSequence() }.toList()
        val owners =
            plugins.value
                .filter { plugin ->
                    plugin.manifest.id in activeOwners && plugin.manifest.entryClass
                        ?.substringBeforeLast('.', "")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { prefix -> frames.any { it.className.startsWith("$prefix.") } } == true
                }.mapTo(mutableSetOf()) { it.manifest.id }
        // Compose can throw from host layout/drawing frames after plugin code has returned.
        recordUiFailure(owners.ifEmpty { activeOwners })
    }

    suspend fun setUiSafeMode(enabled: Boolean) =
        withContext(Dispatchers.IO) {
            uiRecovery.setOriginalUi(enabled)
            uiSafeMode.value = enabled
        }

    val logins = PluginLogins(scope, credentialStore, onFailure = ::reportPluginFailure)
    val homes = PluginHomes(scope, onFailure = ::reportPluginFailure)
    val homeSources = plugins.map(::pluginHomeSources).stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val gate = Mutex()
    private val initialization = Mutex()
    private val packageOperations = Mutex()
    private val metadataResolution = Mutex()
    private var initialized = false
    private val sessions = ConcurrentHashMap<String, Session>()
    private val nativeRestartRequiredIds = ConcurrentHashMap.newKeySet<String>()
    private val settingsStores = ConcurrentHashMap<String, PersistentPluginSettings>()
    private val storageStores = ConcurrentHashMap<String, PersistentPluginStorage>()
    private val networking = HostPluginNetwork()
    private val marker = File(storage, "running.marker")
    private val configFile = File(storage, "configuration.json")
    private val failuresFile = File(storage, "failures.json")
    private var failureCounts = emptyMap<String, Int>()
    private var riskDepth = 0
    private val store = PackageStore(File(storage, "installed"), platform)
    private var restoring = false
    private var desiredEnabled = emptySet<String>()
    private val closing = AtomicBoolean(false)
    private val logQueue = Channel<String>(128)
    private val storageReady = CompletableDeferred<Unit>()
    private val logWriter =
        scope.launch(Dispatchers.IO) {
            storageReady.await()
            val file = File(storage, "runtime.log")
            for (line in logQueue) {
                try {
                    if (file.length() > 512 * 1024) {
                        val old = File(storage, "runtime.previous.log")
                        old.delete()
                        file.renameTo(old)
                    }
                    file.appendText(line + "\n")
                } catch (e: Exception) {
                    logs.update { (it + "[host] Log persistence failed: ${e::class.simpleName}").takeLast(200) }
                }
            }
        }

    init {
        monitorUi()
        scope.launch {
            val completedAccounts = mutableMapOf<String, Pair<PluginLoginState, Long>>()
            logins.states.collect { states ->
                completedAccounts.keys.retainAll(states.keys)
                // Refresh after the callback finishes so logout cannot reuse the previous session.
                states.filterValues { !it.busy }.forEach { (owner, state) ->
                    val current = state.account to state.revision
                    val previous = completedAccounts.put(owner, current)
                    if (previous != current && (previous != null || state.account.loggedIn)) {
                        pipelines.invalidateCaches()
                        homes.invalidate(owner)
                    }
                }
                libraryAccounts.value = states
            }
        }
        registerUiCatalogServices()
        host.audioFallback?.let { registry.audioSource("builtin", it) }
        registry.service(
            "builtin",
            "catalog.match/v1",
            object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject {
                    val track = codec.decodeFromJsonElement<Track>(request.getValue("track"))
                    val candidates = codec.decodeFromJsonElement<List<MatchCandidate>>(request.getValue("candidates"))
                    val order = request["providerOrder"]?.let { codec.decodeFromJsonElement<List<String>>(it) }.orEmpty()
                    val maximum = request["maximumCandidates"]?.jsonPrimitive?.intOrNull ?: 8
                    return buildJsonObject {
                        put(
                            "candidates",
                            codec.encodeToJsonElement(ProviderFallbackMatcher.selectSafeCandidates(track, candidates, order, maximum)),
                        )
                    }
                }
            },
        )
        registry.service(
            "builtin",
            "navigation.route/v1",
            object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject {
                    withContext(
                        Dispatchers.Main.immediate,
                    ) { host.navigation.navigate(request.getValue("destination").jsonPrimitive.content) }
                    return buildJsonObject { put("success", true) }
                }
            },
        )
        scope.launch {
            player.state.map { it.track.id to it.playing }.distinctUntilChanged().collect { (id, playing) ->
                events.publishHost(
                    "playback",
                    buildJsonObject {
                        put("track", buildJsonObject { put("id", id) })
                        put("playing", playing)
                    },
                )
            }
        }
        scope.launch {
            player.state
                .map {
                    it.track
                }.distinctUntilChanged()
                .collect {
                    events.publishHost(
                        "track_changed",
                        codec.encodeToJsonElement(it.copy(extras = emptyMap())).jsonObject,
                    )
                }
        }
        scope.launch {
            player.state.map { it.queue }.distinctUntilChanged().collect {
                events.publishHost(
                    "queue_changed",
                    buildJsonObject {
                        put(
                            "queue",
                            codec.encodeToJsonElement(
                                it.map { track ->
                                    track.copy(extras = emptyMap())
                                },
                            ),
                        )
                    },
                )
            }
        }
        scope.launch {
            player.state
                .map {
                    it.playing
                }.distinctUntilChanged()
                .drop(1)
                .collect {
                    events.publishHost(
                        if (it) "playback_started" else "playback_paused",
                        buildJsonObject { put("trackId", player.state.value.track.id) },
                    )
                }
        }
        scope.launch {
            host.navigation.destination.drop(1).distinctUntilChanged().collect {
                events.publishHost("navigation_changed", buildJsonObject { put("destination", it) })
            }
        }
        scope.launch {
            pipelines.diagnostics.map { it.lastOrNull() }.filterNotNull().distinctUntilChanged().collect { diagnostic ->
                val success = diagnostic.outcome == "SUCCESS" || diagnostic.outcome == "CACHE"
                val event =
                    if (success &&
                        diagnostic.point == "audio.resolve"
                    ) {
                        "source_resolved"
                    } else if (!success) {
                        "provider_failed"
                    } else {
                        null
                    }
                if (event !=
                    null
                ) {
                    events.publishHost(
                        event,
                        buildJsonObject {
                            put("provider", diagnostic.provider)
                            put("capability", diagnostic.point)
                            put("outcome", diagnostic.outcome)
                        },
                    )
                }
            }
        }
        scope.launch {
            player.state
                .map { it.track }
                .distinctUntilChanged()
                .collect { refreshMetadata() }
        }
        scope.launch { registry.state.drop(1).collect { if (!restoring) saveConfiguration() } }
        scope.launch {
            combine(pipelines.metadataPolicy, pipelines.audioPolicy) { _, _ -> Unit }.drop(1).collect {
                if (!restoring) saveConfiguration()
                pipelines.invalidateCaches()
            }
        }
        scope.launch {
            registry.state
                .map {
                    listOf(it.priorities, it.globalOrder, it.pointOrder["metadata.resolve"], it.pointPriorities["metadata.resolve"])
                }.distinctUntilChanged()
                .drop(1)
                .collect { if (initialized) refreshMetadata() }
        }
        scope.launch { pipelines.metadataPolicy.drop(1).collect { if (initialized) refreshMetadata() } }
    }

    /** Called once at startup, before accepting manager actions. Recovery never auto-enables code. */
    suspend fun initialize() =
        withContext(Dispatchers.IO) {
            initialization.withLock {
                if (initialized) return@withLock
                require(storage.mkdirs() || storage.isDirectory)
                recovery.value = marker.exists()
                if (recovery.value) uiRecovery.recoverOriginalUi()
                if (uiRecovery.isOriginalUiForced) forceOriginalUi()
                marker.writeText("Unclean exit: start without plugins")
                storageReady.complete(Unit)
                store.recoverInterruptedInstallations()
                if (recovery.value) recoverInterruptedUpdates()
                restoring = true
                try {
                    val config = if (configFile.exists()) codec.decodeFromString<Configuration>(configFile.readText()) else Configuration()
                    failureCounts =
                        if (failuresFile.exists()) codec.decodeFromString<Map<String, Int>>(failuresFile.readText()) else emptyMap()
                    desiredEnabled = config.enabled
                    if (uiFailureMarker.isFile) {
                        val failedOwners =
                            codec
                                .parseToJsonElement(uiFailureMarker.readText())
                                .jsonObject
                                .getValue("owners")
                                .jsonArray
                                .map { it.jsonPrimitive.content }
                        failedOwners.forEach(::recordFailure)
                        val quarantined = failedOwners.filterTo(mutableSetOf()) { failureCounts.getOrDefault(it, 0) >= 2 }
                        uiRecovery.setOriginalUi(true)
                        Files.deleteIfExists(uiFailureMarker.toPath())
                        if (quarantined.isNotEmpty()) {
                            desiredEnabled = desiredEnabled - quarantined
                            log("host", "UI plugins disabled after repeated unresponsiveness: ${quarantined.sorted().joinToString()}")
                        }
                    }
                    pipelines.metadataPolicy.value = config.metadataPolicy
                    pipelines.audioPolicy.value = migrateAudioPolicy(config.audioPolicy)
                    registry.state.value =
                        RegistryState(
                            priorities = config.priorities,
                            selections = config.selections,
                            base = config.base,
                            globalOrder = config.globalOrder,
                            pointOrder = config.pointOrder,
                            pointPriorities = config.pointPriorities,
                            serviceModes = config.serviceModes,
                        )
                    File(storage, "installed").listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.forEach { dir ->
                        try {
                            store.verify(dir)
                            addDirectory(dir)
                        } catch (e: Exception) {
                            // Keep broken installations manageable; recovery must not require deleting app data.
                            val manifest =
                                runCatching {
                                    codec.decodeFromString<Manifest>(
                                        File(dir, "manifest.json").readText(),
                                    )
                                }.getOrNull()
                            val safe =
                                manifest?.takeIf { it.id == dir.name }
                                    ?: Manifest(dir.name, "Invalid package: ${dir.name}", "0.0.0", "Unknown", runtime = "invalid")
                            plugins.update {
                                it.filterNot { p -> p.manifest.id == safe.id } +
                                    PluginStatus(safe, dir, "Failed", e.toString(), LifecyclePhase.FAILED)
                            }
                            log("host", "Installed package ${dir.name} rejected: $e")
                        }
                    }
                    if (!recovery.value && !uiRecovery.isPluginStartupBlocked) {
                        val pending = desiredEnabled.toMutableSet()
                        while (pending.isNotEmpty()) {
                            val ready =
                                pending.filter { id ->
                                    plugins.value.firstOrNull { it.manifest.id == id }?.manifest?.dependencies?.keys?.none {
                                        it in
                                            pending
                                    }
                                        ?: true
                                }
                            if (ready.isEmpty()) {
                                pending.forEach { status(it, "Failed", "Dependency cycle: $pending") }
                                log("host", "Dependency cycle: $pending")
                                break
                            }
                            registry.order("lifecycle.activate", ready).forEach { id ->
                                if (plugins.value.any { it.manifest.id == id }) enable(id)
                                pending.remove(id)
                            }
                        }
                    }
                } catch (e: Exception) {
                    recovery.value = true
                    log("host", "Configuration recovery: $e")
                } finally {
                    restoring = false
                    initialized = true
                    saveConfiguration()
                    if (platform == "android") marker.delete()
                }
            }
        }

    @Synchronized private fun saveConfiguration() {
        if (restoring) return
        try {
            val s = registry.state.value
            val temp = File(storage, "configuration.tmp")
            temp.outputStream().use {
                it.write(
                    codec
                        .encodeToString(
                            Configuration(
                                desiredEnabled,
                                s.priorities,
                                s.selections,
                                s.base,
                                s.globalOrder,
                                s.pointOrder,
                                s.pointPriorities,
                                pipelines.metadataPolicy.value,
                                pipelines.audioPolicy.value,
                                s.serviceModes,
                            ),
                        ).toByteArray(),
                )
                it.fd.sync()
            }
            try {
                Files.move(temp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (
                e: AtomicMoveNotSupportedException,
            ) {
                Files.move(temp.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                log("host", "Filesystem lacks atomic rename")
            }
        } catch (e: Exception) {
            log("host", "Cannot persist settings: $e")
        }
    }

    @Synchronized private fun beginRisk() {
        if (platform == "android") {
            riskDepth++
            marker.writeText("Interrupted plugin lifecycle operation")
        }
    }

    @Synchronized private fun endRisk() {
        if (platform == "android") {
            riskDepth--
            if (riskDepth == 0) marker.delete()
        }
    }

    @Synchronized private fun recordFailure(id: String) {
        failureCounts = failureCounts + (id to (failureCounts.getOrDefault(id, 0) + 1))
        writePluginJson(failuresFile, codec.encodeToJsonElement(failureCounts).jsonObject, 128 * 1024)
    }

    private fun recoverInterruptedUpdates() {
        val installed = File(storage, "installed")
        installed.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".backup-") }.forEach { backup ->
            try {
                val old = store.verify(backup)
                val destination = File(installed, old.id)
                val abandoned = File(storage, "updates/abandoned-${UUID.randomUUID()}")
                abandoned.parentFile.mkdirs()
                if (destination.exists()) require(destination.renameTo(abandoned))
                require(backup.renameTo(destination)) { "Cannot restore pending backup" }
                if (abandoned.exists() && !abandoned.deleteRecursively()) log("host", "Abandoned package requires cleanup")
                log(old.id, "Recovered interrupted package update; plugin remains disabled")
            } catch (e: Exception) {
                log("host", "Pending update recovery failed: ${e::class.simpleName}")
            }
        }
    }

    suspend fun install(
        input: InputStream,
        fileName: String = "plugin.mfp",
    ): Manifest =
        withContext(Dispatchers.IO) {
            packageOperations.withLock {
                val dir = gate.withLock { store.install(input, fileName) }
                try {
                    addDirectory(dir)
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    throw e
                }
            }
        }

    /** Prepare and verify before deactivation. Failed updates restore the previous package and activation. */
    suspend fun installOrUpdate(
        input: InputStream,
        fileName: String = "plugin.mfp",
    ): Manifest =
        withContext(Dispatchers.IO) {
            packageOperations.withLock {
                val scratch = File(storage, "updates/${UUID.randomUUID()}")
                val candidate = PackageStore(scratch, platform).install(input, fileName)
                val manifest = store.verify(candidate)
                val previous = plugins.value.firstOrNull { it.manifest.id == manifest.id }
                require(
                    manifest.id !in nativeRestartRequiredIds,
                ) { "Native libraries are still loaded; restart before replacing this plugin" }
                val destination = File(storage, "installed/${manifest.id}")
                require(destination.parentFile.mkdirs() || destination.parentFile.isDirectory)
                if (previous == null) {
                    try {
                        require(candidate.renameTo(destination))
                        return@withContext addDirectory(destination)
                    } finally {
                        scratch.deleteRecursively()
                    }
                }
                require(previous.directory.canonicalFile == destination.canonicalFile) { "Only managed packages can be updated" }
                require(
                    plugins.value.none { it.state == "Enabled" && manifest.id in it.manifest.dependencies },
                ) { "Deactivate dependents before updating" }
                val wasEnabled = previous.state == "Enabled"
                val backup = File(destination.parentFile, ".backup-${manifest.id}-${UUID.randomUUID()}")
                var movedOld = false
                beginRisk()
                try {
                    disable(manifest.id)
                    require(destination.renameTo(backup)) { "Native files are busy; enter recovery and restart before updating" }
                    movedOld = true
                    require(candidate.renameTo(destination)) { "Cannot commit update" }
                    addDirectory(destination)
                    if (wasEnabled) {
                        enable(manifest.id)
                        require(
                            plugins.value.first { it.manifest.id == manifest.id }.state == "Enabled",
                        ) { "Updated plugin failed activation" }
                    }
                    if (!backup.deleteRecursively()) log(manifest.id, "Old package retained for cleanup: ${backup.name}")
                    manifest
                } catch (e: Exception) {
                    withContext(NonCancellable) {
                        try {
                            if (movedOld) {
                                gate.withLock { stop(manifest.id) }
                                require(
                                    destination.deleteRecursively() && backup.renameTo(destination),
                                ) { "Rollback storage failure; use recovery manager" }
                                addDirectory(destination)
                            }
                            if (wasEnabled) enable(previous.manifest.id)
                            val restored = sessions.containsKey(previous.manifest.id)
                            val restoredState =
                                if (restored) {
                                    "Enabled"
                                } else if (wasEnabled) {
                                    "Failed"
                                } else {
                                    "Installed"
                                }
                            status(
                                previous.manifest.id,
                                restoredState,
                                "Update rolled back${if (wasEnabled && !restored) "; previous activation failed" else ""}: $e",
                            )
                            log(
                                manifest.id,
                                "Update rolled back; previous activation ${if (restored) "active" else "inactive"}: ${e::class.simpleName}",
                            )
                        } catch (rollback: Exception) {
                            status(previous.manifest.id, "Failed", "Update rollback failed: $rollback")
                            log(manifest.id, "Rollback failed: ${rollback::class.simpleName}")
                            e.addSuppressed(rollback)
                        }
                    }
                    throw e
                } finally {
                    endRisk()
                    scratch.deleteRecursively()
                }
            }
        }

    fun exportConfiguration(): String {
        val s = registry.state.value
        return codec.encodeToString(
            Configuration(
                desiredEnabled,
                s.priorities,
                s.selections,
                s.base,
                s.globalOrder,
                s.pointOrder,
                s.pointPriorities,
                pipelines.metadataPolicy.value,
                pipelines.audioPolicy.value,
                s.serviceModes,
            ),
        )
    }

    suspend fun importConfiguration(text: String) {
        require(text.length <= 128 * 1024)
        val config = codec.decodeFromString<Configuration>(text)
        require(config.globalOrder.distinct().size == config.globalOrder.size && config.pointOrder.size <= 512)
        disableAll()
        registry.state.update {
            it.copy(
                priorities = config.priorities,
                selections = config.selections,
                base = config.base,
                globalOrder = config.globalOrder,
                pointOrder = config.pointOrder,
                pointPriorities = config.pointPriorities,
                serviceModes = config.serviceModes,
            )
        }
        pipelines.metadataPolicy.value = config.metadataPolicy
        pipelines.audioPolicy.value = config.audioPolicy
        enableOrdered(config.enabled)
        withContext(Dispatchers.IO) { saveConfiguration() }
    }

    suspend fun uninstall(id: String) =
        withContext(Dispatchers.IO) {
            packageOperations.withLock {
                disable(id)
                gate.withLock {
                    val p = plugins.value.first { it.manifest.id == id }
                    require(
                        p.directory.canonicalFile.parentFile == File(storage, "installed").canonicalFile,
                    ) { "Only managed packages can be uninstalled" }
                    require(
                        p.directory.deleteRecursively(),
                    ) { "Cannot delete package; a native loader may retain files. Restart in recovery mode." }
                    plugins.update { it.filterNot { it.manifest.id == id } }
                }
            }
        }

    fun log(
        owner: String,
        text: String,
    ) {
        val safe = text.take(4000).replace(Regex("https?://[^\\s)]+"), "<url>")
        val line = "${System.currentTimeMillis()} [$owner] $safe"
        logs.update { (it + line).takeLast(200) }
        if (logQueue.trySend(line).isFailure) logs.update { (it + "[host] Log persistence queue full or closed").takeLast(200) }
        try {
            host.logger.write(owner, safe)
        } catch (
            e: Exception,
        ) {
            logs.update { (it + "[host] Host logger failed: ${e::class.simpleName}").takeLast(200) }
        }
    }

    suspend fun refreshMetadata() =
        withContext(Dispatchers.Default) {
            metadataResolution.withLock {
                val requestedTrack = player.state.value.track
                val epoch = registry.providerEpoch
                val policy = pipelines.metadataPolicy.value
                var value = requestedTrack
                for (p in registry.metadataProviders()) {
                    try {
                        value = p.value.resolve(value)
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        log(p.owner, "Metadata failed: $e")
                        scope.launch { quarantine(p.owner, e) }
                    }
                }
                val document = pipelines.metadata(MetadataMapping.fromTrack(value))
                if (player.state.value.track == requestedTrack && registry.providerEpoch == epoch &&
                    pipelines.metadataPolicy.value == policy
                ) {
                    metadataDocument.value = document
                    metadata.value = MetadataMapping.toTrack(document, value)
                }
            }
        }

    private fun phase(
        id: String,
        phase: LifecyclePhase,
    ) {
        plugins.update {
            it.map { p ->
                if (p.manifest.id ==
                    id
                ) {
                    p.copy(phase = phase, history = (p.history + LifecycleTransition(phase)).takeLast(40))
                } else {
                    p
                }
            }
        }
    }

    private fun status(
        id: String,
        state: String,
        error: String? = null,
    ) {
        plugins.update { it.map { p -> if (p.manifest.id == id) p.copy(state = state, error = error) else p } }
        when (state) {
            "Loading" -> phase(id, LifecyclePhase.LOADING)
            "Enabled" -> phase(id, LifecyclePhase.ACTIVE)
            "Failed" -> phase(id, LifecyclePhase.FAILED)
        }
    }

    fun resources() =
        ResourceSnapshot(
            sessions.size,
            events.subscriberCount.get(),
            registry.state.value.ui.size,
            registry.metadataSources().count {
                it.owner !=
                    "builtin"
            },
            registry.audioSources().count { it.owner != "builtin" },
        )

    /** Early feasibility entry point; the package installer calls this after validation. */
    suspend fun addDirectory(directory: File): Manifest =
        withContext(Dispatchers.IO) {
            val m = codec.decodeFromString<Manifest>(File(directory, "manifest.json").readText())
            PackageStore.validateManifest(m, platform)
            m.minimumHostVersion?.let { minimum ->
                val current = requireNotNull(hostVersion) { "Host version is unavailable; cannot validate minimum host version" }
                require(
                    SemVer.parse(current.substringBefore('-')) >= SemVer.parse(minimum),
                ) { "Plugin requires host >= $minimum; installed $current" }
            }
            gate.withLock {
                require(sessions[m.id] == null) { "Disable before replacing this plugin" }
                settingsStores.remove(m.id)
                plugins.update { it.filterNot { p -> p.manifest.id == m.id } + PluginStatus(m, directory) }
                registry.graph(plugins.value.map { it.manifest })
            }
            m
        }

    suspend fun enableOrdered(ids: Set<String>) {
        try {
            val all = plugins.value.associateBy { it.manifest.id }
            PluginOrdering.validateDependencies(all.mapValues { it.value.manifest }, ids)
            val closure = ids.toMutableSet()

            fun visit(id: String) {
                val p = requireNotNull(all[id]) { "Missing dependency: $id" }
                p.manifest.dependencies.keys
                    .forEach { dep -> if (closure.add(dep)) visit(dep) }
            }
            ids.forEach(::visit)
            registry.order("lifecycle.activate", closure).forEach { enable(it) }
        } catch (e: Exception) {
            ids.forEach { reportPluginFailure(it, "lifecycle.dependencies", e) }
            ids.forEach { status(it, "Failed", e.toString()) }
            log("host", "Activation graph rejected: $e")
        }
    }

    suspend fun enable(id: String) =
        withContext(Dispatchers.Default) {
            gate.withLock {
                check(!closing.get()) { "Runtime is closed" }
                if (sessions.containsKey(id)) return@withLock
                val p = plugins.value.first { it.manifest.id == id }
                status(id, "Loading")
                var session: Session? = null
                beginRisk()
                try {
                    val settings = pluginSettings(id)
                    session = Session(p, settings)
                    sessions[id] = session
                    if (p.directory.parentFile.canonicalFile ==
                        File(storage, "installed").canonicalFile
                    ) {
                        withContext(Dispatchers.IO) { store.verify(p.directory) }
                    }
                    PluginOrdering.validateDependencies(plugins.value.associate { it.manifest.id to it.manifest }, listOf(id))
                    require(
                        p.manifest.dependencies.keys.all { dep ->
                            plugins.value.any { it.manifest.id == dep && it.state == "Enabled" }
                        },
                    ) { "Dependencies must be enabled" }
                    if (p.manifest.runtime == "javascript") session.startJs()
                    if (p.manifest.runtime == "kotlin") {
                        require(p.manifest.composeAbi == COMPOSE_ABI) { "Native Compose ABI mismatch: rebuild plugin with $COMPOSE_ABI" }
                        val file = File(p.directory, if (platform == "desktop") "native/plugin.jar" else "native/plugin.apk")
                        session.native = loadNative(file.absolutePath, requireNotNull(p.manifest.entryClass))
                        if (session.native!!.requiresRestartForReplacement) nativeRestartRequiredIds.add(id)
                        withTimeout(30000) { session.native!!.plugin.start(session.context) }
                    }
                    require(p.manifest.runtime in listOf("javascript", "kotlin"))
                    phase(id, LifecyclePhase.LOADED)
                    phase(id, LifecyclePhase.ENABLED)
                    session.publish()
                    if ("home" in p.manifest.capabilities) session.bindHome()
                    if ("login" in p.manifest.capabilities) session.bindLogin()
                    status(id, "Enabled")
                    desiredEnabled = desiredEnabled + id
                    saveConfiguration()
                    log(id, "Enabled external ${p.manifest.runtime} plugin")
                    scope.launch { events.publishHost("plugin_enabled", buildJsonObject { put("id", id) }) }
                } catch (e: Throwable) {
                    homes.unbind(id)
                    logins.unbind(id)
                    withContext(NonCancellable) {
                        try {
                            session?.close()
                        } catch (cleanup: Throwable) {
                            log(id, "Cleanup failed: $cleanup")
                        }
                    }
                    sessions.remove(id)
                    registry.remove(id)
                    status(id, "Failed", e.toString())
                    reportPluginFailure(id, "lifecycle.activate", e)
                    log(id, e.stackTraceToString())
                    desiredEnabled = desiredEnabled - id
                    withContext(NonCancellable + Dispatchers.IO) {
                        recordFailure(id)
                        saveConfiguration()
                    }
                    if (e is CancellationException) throw e
                } finally {
                    endRisk()
                }
                refreshMetadata()
            }
        }

    suspend fun disable(id: String) =
        withContext(Dispatchers.Default) {
            gate.withLock {
                val dependents = plugins.value.filter { it.state == "Enabled" && id in it.manifest.dependencies }
                require(dependents.isEmpty()) { "Disable dependents first: ${dependents.map { it.manifest.id }}" }
                stop(id)
                refreshMetadata()
                desiredEnabled = desiredEnabled - id
                saveConfiguration()
            }
        }

    private suspend fun stop(id: String) {
        homes.unbind(id)
        logins.unbind(id)
        try {
            sessions.remove(id)?.close()
            status(id, "Installed")
            phase(id, LifecyclePhase.DISABLED)
            if (plugins.value
                    .firstOrNull {
                        it.manifest.id ==
                            id
                    }?.manifest
                    ?.runtime ==
                "javascript"
            ) {
                phase(id, LifecyclePhase.UNLOADED)
            }
        } catch (
            e: Throwable,
        ) {
            status(id, "Failed", "Cleanup: $e")
            reportPluginFailure(id, "lifecycle.dispose", e)
            log(id, "Cleanup: $e")
        } finally {
            registry.remove(id)
            scope.launch { events.publishHost("plugin_disabled", buildJsonObject { put("id", id) }) }
        }
    }

    private fun shutdownOrder(): List<String> =
        try {
            registry.order("lifecycle.activate", sessions.keys.toList()).asReversed()
        } catch (e: IllegalArgumentException) {
            log("host", "Shutdown graph invalid: $e")
            sessions.keys.sortedDescending()
        }

    suspend fun disableAll() =
        withContext(Dispatchers.Default) {
            gate.withLock {
                shutdownOrder().forEach { stop(it) }
                desiredEnabled =
                    emptySet()
                saveConfiguration()
                refreshMetadata()
            }
        }

    suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        // A clean process shutdown must retain the user's enabled set.
        gate.withLock { shutdownOrder().forEach { stop(it) } }
        withContext(Dispatchers.IO) {
            saveConfiguration()
            marker.delete()
        }
        if (!storageReady.isCompleted) storageReady.complete(Unit)
        logQueue.close()
        logWriter.join()
        runtimeJob.cancelAndJoin()
        networking.close()
    }

    suspend fun hotReplace(id: String) {
        require(id !in nativeRestartRequiredIds) { "Native libraries are still loaded; restart before reloading this plugin" }
        disable(id)
        enable(id)
    }

    suspend fun pluginSettings(id: String): PersistentPluginSettings {
        val manifest = plugins.value.first { it.manifest.id == id }.manifest
        val settings = settingsStores.getOrPut(id) { PersistentPluginSettings(File(storage, "data/$id/settings.json"), manifest.settings) }
        settings.load()
        return settings
    }

    suspend fun settingsFor(id: String): PluginSettings = pluginSettings(id)

    private suspend fun quarantine(
        id: String,
        error: Throwable,
    ) = gate.withLock {
        reportPluginFailure(id, "lifecycle.quarantine", error)
        // Dependents must lose their contributions too if a provider disappears.
        val affected = mutableSetOf(id)
        do {
            val before = affected.size
            plugins.value
                .filter { p ->
                    p.manifest.dependencies.keys
                        .any { it in affected }
                }.forEach {
                    affected +=
                        it.manifest.id
                }
        } while (before != affected.size)
        affected.forEach {
            stop(it)
            status(it, "Failed", if (it == id) error.toString() else "Dependency failed: $id")
        }
        desiredEnabled = desiredEnabled - affected
        saveConfiguration()
        log(id, "Quarantined: $error")
    }

    fun sessionToken(owner: String): String? = sessions[owner]?.token

    suspend fun artwork(
        owner: String,
        path: String,
    ): ImageBitmap =
        withContext(Dispatchers.IO) {
            PackageStore.validatePath(path)
            require(path.startsWith("assets/")) { "Images must be packaged assets" }
            val directory = plugins.value.first { it.manifest.id == owner }.directory
            val file = File(directory, path)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator) && file.length() in 1..4 * 1024 * 1024)
            decodeArtwork(file.readBytes())
        }

    suspend fun dispatchAction(
        owner: String,
        action: String,
        value: String = "",
    ) {
        sessions[owner]?.action(action, value)
    }

    fun action(
        owner: String,
        action: String,
        value: String = "",
        token: String? = sessionToken(owner),
        target: UiTarget? = null,
    ) {
        scope.launch {
            try {
                val session = sessions[owner]?.takeIf { it.token == token } ?: return@launch
                session.action(action, value, target)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                withContext(Dispatchers.IO) { recordFailure(owner) }
                log(owner, "Callback failed: $e")
                quarantine(owner, e)
            }
        }
    }

    private inner class Session(
        val p: PluginStatus,
        val pluginSettings: PersistentPluginSettings,
    ) {
        val id = p.manifest.id
        val token = UUID.randomUUID().toString()
        val job = SupervisorJob(scope.coroutineContext[Job])
        val observers = CoroutineScope(job + Dispatchers.Default)
        val jsGate = Mutex()
        private val bridgeWaiters = AtomicInteger()
        private val uiSubscriptions = MutableStateFlow(emptySet<String>())
        private val sockets =
            PluginSockets(observers) { name, payload ->
                try {
                    evaluate("JSON.stringify(await __emit(${quote(name)},$payload))")
                } catch (e: CancellationException) {
                    if (e !is TimeoutCancellationException) throw e
                    scope.launch { quarantine(id, IllegalStateException("Socket callback failed")) }
                } catch (_: Exception) {
                    scope.launch { quarantine(id, IllegalStateException("Socket callback failed")) }
                }
            }

        /** The dependency's native timer includes pending async time on the tested builds.
         * Bound active evaluation separately, retaining a wall deadline and native cancellation. */
        private suspend fun <T> jsBudget(block: suspend () -> T): T =
            coroutineScope {
                val monitor =
                    launch(Dispatchers.Default) {
                        var activeNanos = 0L
                        var previous = System.nanoTime()
                        while (isActive) {
                            delay(5)
                            val now = System.nanoTime()
                            if (bridgeWaiters.get() == 0) activeNanos += now - previous
                            previous = now
                            if (activeNanos > 500_000_000L) {
                                js?.interruptEvaluation()
                                break
                            }
                        }
                    }
                try {
                    withTimeout(30_000) { block() }
                } finally {
                    monitor.cancelAndJoin()
                }
            }

        private val staged = Registry()
        private var committed = false
        private val registrations: Registry get() = if (committed) registry else staged

        fun publish() {
            registry.publish(staged, id)
            committed = true
        }

        var js: QuickJs? = null
        var native: NativeHandle? = null
        private val jsLoginState = MutableStateFlow(PluginLoginState())

        fun bindHome() {
            val provider = native?.plugin as? HomePlugin
            homes.bindPaged(id) { request, continuation ->
                owned {
                    val page =
                        if (js !=
                            null
                        ) {
                            val arguments =
                                buildJsonObject {
                                    put("language", request.language)
                                    put("country", request.country)
                                    continuation?.let { put("continuation", it) }
                                }
                            when (
                                val response =
                                    codec.parseToJsonElement(
                                        evaluate("JSON.stringify(await globalThis.__home?.($arguments) ?? [])"),
                                    )
                            ) {
                                is JsonArray -> HomePage(codec.decodeFromJsonElement<List<HomeShelf>>(response))
                                else -> codec.decodeFromJsonElement<HomePage>(response)
                            }
                        } else if (provider is PagedHomePlugin) {
                            provider.getHomePage(request, continuation)
                        } else {
                            HomePage(provider?.getHome(request).orEmpty())
                        }
                    validateHome(page.shelves)
                    page.copy(shelves = pipelines.home(id, page.shelves))
                }
            }
        }

        fun bindLogin() {
            val provider = native?.plugin as? LoginPlugin
            logins.bind(
                id,
                token,
                provider?.loginState ?: jsLoginState,
                request = {
                    owned {
                        if (js !=
                            null
                        ) {
                            codec.decodeFromString<LoginRequest?>(evaluate("JSON.stringify(await globalThis.__login?.request?.() ?? null)"))
                        } else {
                            provider?.loginRequest()
                        }
                    }
                },
                login = { credentials ->
                    owned {
                        if (js !=
                            null
                        ) {
                            val result =
                                codec.decodeFromString<PluginLoginState>(
                                    evaluate(
                                        "JSON.stringify(await globalThis.__login?.login?.(${codec.encodeToString(
                                            credentials,
                                        )}) ?? {loggedIn:false})",
                                    ),
                                )
                            jsLoginState.value = result
                            result
                        } else {
                            provider?.login(credentials) ?: PluginLoginState()
                        }
                    }
                },
                logout = {
                    owned {
                        if (js !=
                            null
                        ) {
                            evaluate("JSON.stringify(await globalThis.__login?.logout?.() ?? null)")
                            jsLoginState.value =
                                PluginLoginState()
                        } else {
                            provider?.logout()
                        }
                        Unit
                    }
                },
                webRequest = {
                    owned {
                        if (js != null) {
                            codec.decodeFromString<WebLoginRequest?>(evaluate("JSON.stringify(await globalThis.__login?.web?.() ?? null)"))
                        } else {
                            (provider as? WebLoginPlugin)?.webLoginRequest()
                        }
                    }
                },
                accountUi = {
                    owned {
                        if (js != null) {
                            codec
                                .decodeFromString<WebViewLoginUi?>(
                                    evaluate("JSON.stringify(await globalThis.__login?.ui?.() ?? null)"),
                                )?.let { ui ->
                                    val form =
                                        codec.decodeFromString<LoginRequest?>(
                                            evaluate("JSON.stringify(await globalThis.__login?.request?.() ?? null)"),
                                        )
                                    ui.request.validate(requireNotNull(form))
                                    WebViewLoginScreen(ui)
                                }
                        } else {
                            provider as? NativeLoginUi
                        }
                    }
                },
            )
        }

        @Volatile var alive = true

        private fun requireActive() {
            check(alive && job.isActive) { "Plugin is no longer active" }
        }

        private suspend fun <T> owned(block: suspend () -> T): T {
            requireActive()
            val task = observers.async { block() }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }

        val context =
            object : PluginContext {
                override val scope: CoroutineScope get() = observers
                override val matcher: TrackMatcher get() = ProviderFallbackMatcher
                override val settings =
                    object : PluginSettings {
                        override val values get() = pluginSettings.values

                        override suspend fun set(
                            key: String,
                            value: JsonElement,
                        ) = owned { pluginSettings.set(key, value) }
                    }
                override val storage =
                    object : PluginStorage {
                        private val store get() =
                            storageStores.computeIfAbsent(
                                id,
                            ) { PersistentPluginStorage(File(this@Runtime.storage, "data/$id/storage.json")) }

                        override suspend fun get(key: String): JsonElement? = owned { store.get(key) }

                        override suspend fun set(
                            key: String,
                            value: JsonElement?,
                        ) = owned { store.set(key, value) }
                    }
                override val network =
                    object : PluginNetwork {
                        override suspend fun request(request: HttpRequest): HttpResponse = owned { networking.request(request) }
                    }
                override val resources =
                    object : PluginResources {
                        override val platformResources: Any? get() = native?.platformResources

                        override suspend fun readAsset(path: String): ByteArray =
                            withContext(Dispatchers.IO) {
                                requireActive()
                                PackageStore.validatePath(path)
                                val handle = native
                                if (handle != null) {
                                    handle.readAsset(path)
                                } else {
                                    val file = File(p.directory, "assets/$path")
                                    require(file.canonicalPath.startsWith(File(p.directory, "assets").canonicalPath + File.separator))
                                    require(file.isFile && file.length() <= 4 * 1024 * 1024) { "Missing or oversized plugin asset" }
                                    file.readBytes()
                                }
                            }
                    }
                override val player: Player =
                    object : Player by this@Runtime.player {
                        override fun stop() {
                            requireActive()
                            this@Runtime.player.stop()
                            scope.launch {
                                events.publishHost(
                                    "playback_stopped",
                                    buildJsonObject {
                                        put(
                                            "trackId",
                                            this@Runtime
                                                .player.state.value.track.id,
                                        )
                                    },
                                )
                            }
                        }
                    }.let { guarded ->
                        val upcoming = this@Runtime.player as? UpcomingQueuePlayer
                        if (upcoming == null) {
                            guarded
                        } else {
                            object : UpcomingQueuePlayer, Player by guarded {
                                override fun replaceUpcomingQueue(tracks: List<Track>) {
                                    requireActive()
                                    require("replaceUpcomingQueue" in upcoming.capabilities) { "Upcoming queue replacement is unavailable" }
                                    upcoming.replaceUpcomingQueue(tracks)
                                }
                            }
                        }
                    }
                override val metadata: StateFlow<Track> get() = this@Runtime.metadata
                override val metadataDocument: StateFlow<MetadataDocument> get() = this@Runtime.metadataDocument

                override fun registerUi(
                    point: String,
                    screen: NativeScreen,
                    layer: Layer,
                ) {
                    requireActive()
                    registrations.ui(UiContribution(id, point, layer, native = screen))
                }

                override fun registerComponent(
                    type: String,
                    component: NativeComponent,
                ) {
                    requireActive()
                    registrations.component(id, type, component)
                }

                override fun registerService(
                    name: String,
                    service: JsonService,
                ) {
                    requireActive()
                    registrations.service(id, name, service)
                }

                override fun registerContract(contract: ServiceContract) {
                    requireActive()
                    registrations.contract(id, contract)
                }

                override fun registerHook(
                    name: String,
                    phase: HookPhase,
                    observer: JsonService,
                ) {
                    requireActive()
                    registrations.hook(id, name, phase, observer)
                }

                override fun subscribe(
                    event: String,
                    handler: suspend (JsonObject) -> Unit,
                ): Subscription {
                    requireActive()
                    return events.subscribe(observers, event) {
                        try {
                            handler(it.payload)
                        } catch (
                            e: CancellationException,
                        ) {
                            throw e
                        } catch (e: Exception) {
                            scope.launch { quarantine(id, e) }
                        }
                    }
                }

                override suspend fun publish(
                    event: String,
                    payload: JsonObject,
                ) = owned { events.publish(event, payload) }

                override suspend fun callService(
                    name: String,
                    request: JsonObject,
                ): JsonObject =
                    owned {
                        if (name == UI_FRONTENDS_SERVICE && "select" in request) {
                            require("ui" in p.manifest.capabilities) { "UI capability required to select a frontend" }
                        }
                        if (name == UI_LIBRARY_SERVICE) {
                            require("ui" in p.manifest.capabilities) { "UI capability required to read frontend library" }
                        }
                        withTimeout(30_000) {
                            val service = requireNotNull(staged.service(name) ?: registry.service(name)) { "Missing service $name" }
                            if (service.owner == id) service.value.call(request) else registry.invoke(name, request)
                        }
                    }

                override fun registerMetadata(provider: MetadataProvider) {
                    requireActive()
                    registrations.metadata(id, provider)
                }

                override fun registerMetadataSource(provider: MetadataSource) {
                    requireActive()
                    registrations.metadataSource(
                        id,
                        object : MetadataSource {
                            override val id = provider.id

                            override suspend fun resolve(request: MetadataRequest): MetadataContribution? {
                                val task = observers.async { provider.resolve(request) }
                                return try {
                                    task.await()
                                } finally {
                                    task.cancel()
                                }
                            }
                        },
                    )
                }

                override fun registerAudio(
                    id: String,
                    provider: AudioProvider,
                ) {
                    requireActive()
                    registrations.audio(this@Session.id, id, provider)
                }

                override fun registerAudioSource(provider: AudioSource) {
                    requireActive()
                    registrations.audioSource(
                        id,
                        object : AudioSource {
                            override val id = provider.id
                            override val capabilities = provider.capabilities

                            override suspend fun resolve(request: AudioQuery): AudioResult {
                                val task = observers.async { provider.resolve(request) }
                                return try {
                                    task.await()
                                } finally {
                                    task.cancel()
                                }
                            }
                        },
                    )
                }

                override fun registerLyricsProvider(provider: LyricsProvider) {
                    requireActive()
                    registrations.lyricsProvider(
                        id,
                        object : LyricsProvider {
                            override val id = provider.id

                            override suspend fun lyrics(request: LyricsRequest): LyricsResult? {
                                val task = observers.async { provider.lyrics(request) }
                                return try {
                                    task.await()
                                } finally {
                                    task.cancel()
                                }
                            }
                        },
                    )
                }

                override fun registerSearchProvider(provider: SearchProvider) {
                    requireActive()
                    registrations.searchProvider(
                        id,
                        object : SearchProvider {
                            override val id = provider.id

                            override suspend fun search(request: SearchRequest): SearchResult {
                                val task = observers.async { provider.search(request) }
                                return try {
                                    task.await()
                                } finally {
                                    task.cancel()
                                }
                            }
                        },
                    )
                }

                override fun registerCatalogProvider(provider: CatalogProvider) {
                    requireActive()
                    val catalog =
                        object : CatalogProvider {
                            override val id = provider.id

                            override suspend fun album(id: String): CatalogAlbum? = owned { provider.album(id) }

                            override suspend fun artist(id: String): CatalogArtist? = owned { provider.artist(id) }

                            override suspend fun playlist(id: String): CatalogPlaylist? = owned { provider.playlist(id) }
                        }
                    val recommendations =
                        (provider as? RecommendationProvider)?.let {
                            object : RecommendationProvider {
                                override suspend fun recommendations(request: RecommendationRequest): RecommendationResult =
                                    owned { it.recommendations(request) }
                            }
                        }
                    val library =
                        (provider as? LibraryProvider)?.let {
                            object : LibraryProvider {
                                override suspend fun library(request: LibraryRequest): LibraryPage = owned { it.library(request) }

                                override val supportsSongVotes: Boolean get() = it.supportsSongVotes

                                override val supportsLikedSongs: Boolean get() = it.supportsLikedSongs

                                override suspend fun setSongVote(request: SongVoteRequest): Boolean = owned { it.setSongVote(request) }
                            }
                        }
                    registrations.catalogProvider(id, catalogWithExtensions(catalog, recommendations, library))
                }

                override fun log(message: String) {
                    this@Runtime.log(id, message)
                }
            }

        suspend fun startJs() {
            val sources =
                withContext(Dispatchers.IO) {
                    p.directory.walkTopDown().filter { it.isFile && it.extension == "js" }.associate {
                        it.relativeTo(p.directory).invariantSeparatorsPath to
                            it.readText()
                    }
                }
            val q = QuickJs.create(Dispatchers.Default, ModuleLoader { name -> sources[name]?.let { ModuleContent.Source(it) } })
            js = q
            q.memoryLimit = 32L * 1024 * 1024
            q.maxStackSize = 512L * 1024
            q.evaluationTimeoutMillis = 0
            q.function("__ui") { args ->
                check(alive)
                val point = args[0] as String
                val encodedTree = args[1] as String
                require(encodedTree.length <= 512 * 1024) { "UI tree payload limit" }
                val tree = codec.decodeFromString<UiNode>(encodedTree)
                validateTree(tree)
                registrations.ui(UiContribution(id, point, Layer.valueOf(args[2] as String), tree = tree))
                null
            }
            q.function("__uiSubscription") { args ->
                val name = args[0] as String
                if (name.startsWith("socket.message/")) {
                    sockets.subscribe(name.removePrefix("socket.message/"), (args[1] as Number).toInt() > 0)
                }
                if (name in UI_EVENTS) {
                    val active = (args[1] as Number).toInt() > 0
                    uiSubscriptions.update { if (active) it + name else it - name }
                }
                null
            }
            q.asyncFunction("__libraryPage") { args ->
                requireActive()
                val offset = (args[0] as Number).toInt().coerceAtLeast(0)
                val limit = (args[1] as Number).toInt().coerceIn(1, 200)
                codec.encodeToString((host as? UiLibraryHost)?.library(offset, limit)?.first() ?: emptyList())
            }
            q.function("__destination") { _ -> host.navigation.destination.value }
            q.function("__pluginStates") { _ -> codec.encodeToString(pluginUiStates()) }
            q.function("__patch") { args ->
                requireActive()
                registrations.patch(id, args[0] as String, codec.decodeFromString<Map<String, String>>(args[1] as String))
                null
            }
            q.function("__patchMany") { args ->
                requireActive()
                val encoded = args[0] as String
                require(encoded.length <= 512 * 1024)
                registrations.patch(id, codec.decodeFromString<Map<String, Map<String, String>>>(encoded))
                null
            }
            q.function("__log") { args ->
                log(id, args.joinToString())
                null
            }
            q.function("__loginState") { args ->
                requireActive()
                require("login" in p.manifest.capabilities)
                jsLoginState.value = codec.decodeFromString<PluginLoginState>(args[0] as String)
                null
            }
            q.asyncFunction("__player") { args ->
                bridgeWaiters.incrementAndGet()
                try {
                    withContext(Dispatchers.Main.immediate) {
                        val command = args[0] as String
                        require(command in player.capabilities) { "Unsupported player command $command" }
                        when (command) {
                            "toggle" -> {
                                player.toggle()
                            }

                            "play" -> {
                                player.play()
                            }

                            "pause" -> {
                                player.pause()
                            }

                            "next" -> {
                                player.next()
                            }

                            "previous" -> {
                                player.previous()
                            }

                            "stop" -> {
                                context.player.stop()
                            }

                            "seek" -> {
                                player.seek((args[1] as Number).toLong().coerceAtLeast(0))
                            }

                            "volume" -> {
                                player.setVolume((args[1] as Number).toFloat().coerceIn(0f, 1f))
                            }

                            "radio" -> {
                                (player as UiPlaybackControls).startTrackRadio()
                            }

                            "shuffle" -> {
                                (player as UiPlaybackControls).toggleShuffle()
                            }

                            "repeat" -> {
                                (player as UiPlaybackControls).cycleRepeat()
                            }

                            "queueIndex" -> {
                                (player as UiPlaybackControls).selectQueueIndex((args[1] as Number).toInt())
                            }

                            "playTracks" -> {
                                player.playTracks(
                                    codec.decodeFromString<List<Track>>(args[1] as String),
                                    (args[2] as Number).toInt(),
                                )
                            }

                            "addToQueue" -> {
                                player.addToQueue(codec.decodeFromString<List<Track>>(args[1] as String))
                            }

                            "replaceUpcomingQueue" -> {
                                (player as UpcomingQueuePlayer).replaceUpcomingQueue(codec.decodeFromString<List<Track>>(args[1] as String))
                            }

                            else -> {
                                error("Unknown player command")
                            }
                        }
                    }
                    null
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.function("__platform") { platform }
            q.function("__locale") {
                buildJsonObject {
                    put("language", Locale.getDefault().toLanguageTag())
                    put("country", Locale.getDefault().country)
                    put("timeZone", TimeZone.getDefault().id)
                }.toString()
            }
            q.function("__playerState") { codec.encodeToString(player.state.value) }
            q.function("__playerCapabilities") { codec.encodeToString(player.capabilities) }
            q.asyncFunction("__settings") { args ->
                bridgeWaiters.incrementAndGet()
                try {
                    if (args[0] == "set") context.settings.set(args[1] as String, codec.parseToJsonElement(args[2] as String))
                    codec.encodeToString(context.settings.values.value)
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__storage") { args ->
                bridgeWaiters.incrementAndGet()
                try {
                    val key = args[1] as String
                    if (args[0] ==
                        "set"
                    ) {
                        context.storage.set(key, codec.parseToJsonElement(args[2] as String).takeUnless { it == JsonNull })
                    }
                    (context.storage.get(key) ?: JsonNull).toString()
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__http") { args ->
                bridgeWaiters.incrementAndGet()
                try {
                    codec.encodeToString(context.network.request(codec.decodeFromString<HttpRequest>(args[0] as String)))
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__socket") { args ->
                requireActive()
                require("network.websocket" in p.manifest.capabilities) { "WebSocket capability required" }
                bridgeWaiters.incrementAndGet()
                try {
                    owned {
                        when (args[0]) {
                            "open" -> {
                                val request =
                                    try {
                                        codec.decodeFromString<PluginSocketRequest>(args[1] as String)
                                    } catch (_: Exception) {
                                        throw IllegalArgumentException("Invalid socket request")
                                    }
                                sockets.open(request)
                            }

                            "receive" -> {
                                codec.encodeToString(sockets.receive(args[1] as String, (args[2] as Number).toLong()))
                            }

                            "send" -> {
                                sockets.send(args[1] as String, args[2] as String)
                                null
                            }

                            "close" -> {
                                sockets.close(args[1] as String)
                                null
                            }

                            else -> {
                                error("Unknown socket command")
                            }
                        }
                    }
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__catalog") { args ->
                bridgeWaiters.incrementAndGet()
                try {
                    codec.encodeToString(host.catalog.identify(codec.decodeFromString<TrackIdentity>(args[0] as String)))
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__call") { args ->
                val name = args[0] as String
                val target = staged.service(name) ?: registry.service(name)
                require(target?.owner != id || target.value !is JavaScriptBoundService) { "Recursive JavaScript service call" }
                bridgeWaiters.incrementAndGet()
                try {
                    codec.encodeToString(context.callService(name, codec.parseToJsonElement(args[1] as String).jsonObject))
                } finally {
                    bridgeWaiters.decrementAndGet()
                }
            }
            q.asyncFunction("__event") { args ->
                events.publish(args[0] as String, codec.parseToJsonElement(args[1] as String).jsonObject)
                null
            }
            q.function("__contract") { args ->
                context.registerContract(codec.decodeFromString<ServiceContract>(args[0] as String))
                null
            }
            q.function("__hook") { args ->
                val name = args[0] as String
                val phase = HookPhase.valueOf(args[1] as String)
                val key = "$name:${phase.name}"
                context.registerHook(
                    name,
                    phase,
                    object : JsonService {
                        override suspend fun call(request: JsonObject): JsonObject {
                            evaluate("JSON.stringify(await __hooks[${quote(key)}]($request))")
                            return request
                        }
                    },
                )
                null
            }
            q.function("__metadataSource") { args ->
                val sourceId = args[0] as String
                context.registerMetadataSource(
                    object : MetadataSource {
                        override val id = sourceId

                        override suspend fun resolve(request: MetadataRequest): MetadataContribution? =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await __metadataSources[${quote(sourceId)}](${codec.encodeToString(request)}))"),
                            )
                    },
                )
                null
            }
            q.function("__audioSource") { args ->
                val sourceId = args[0] as String
                val caps = codec.decodeFromString<AudioCapabilities>(args[1] as String)
                context.registerAudioSource(
                    object : AudioSource {
                        override val id = sourceId
                        override val capabilities = caps

                        override suspend fun resolve(request: AudioQuery): AudioResult =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await __audioSources[${quote(sourceId)}](${codec.encodeToString(request)}))"),
                            )
                    },
                )
                null
            }
            q.function("__lyrics") { args ->
                val providerId = args[0] as String
                context.registerLyricsProvider(
                    object : LyricsProvider {
                        override val id = providerId

                        override suspend fun lyrics(request: LyricsRequest): LyricsResult? =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await __lyricsProviders[${quote(providerId)}](${codec.encodeToString(request)}))"),
                            )
                    },
                )
                null
            }
            q.function("__search") { args ->
                val providerId = args[0] as String
                context.registerSearchProvider(
                    object : SearchProvider {
                        override val id = providerId

                        override suspend fun search(request: SearchRequest): SearchResult =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await __searchProviders[${quote(providerId)}](${codec.encodeToString(request)}))"),
                            )
                    },
                )
                null
            }
            q.function("__catalogProvider") { args ->
                val providerId = args[0] as String
                val catalog =
                    object : CatalogProvider {
                        override val id = providerId

                        override suspend fun album(id: String): CatalogAlbum? =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await (__catalogProviders[${quote(providerId)}].album?.(${quote(id)}) ?? null))"),
                            )

                        override suspend fun artist(id: String): CatalogArtist? =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await (__catalogProviders[${quote(providerId)}].artist?.(${quote(id)}) ?? null))"),
                            )

                        override suspend fun playlist(id: String): CatalogPlaylist? =
                            codec.decodeFromString(
                                evaluate(
                                    "JSON.stringify(await (__catalogProviders[${quote(providerId)}].playlist?.(${quote(id)}) ?? null))",
                                ),
                            )
                    }
                val recommendations =
                    if (args.getOrNull(1) == true) {
                        object : RecommendationProvider {
                            override suspend fun recommendations(request: RecommendationRequest): RecommendationResult =
                                codec.decodeFromString(
                                    evaluate(
                                        "JSON.stringify(await __catalogProviders[${quote(
                                            providerId,
                                        )}].recommendations(${codec.encodeToString(request)}))",
                                    ),
                                )
                        }
                    } else {
                        null
                    }
                val library =
                    if (args.getOrNull(2) == true) {
                        object : LibraryProvider {
                            override val supportsSongVotes = args.getOrNull(3) == true

                            override val supportsLikedSongs = args.getOrNull(4) == true

                            override suspend fun library(request: LibraryRequest): LibraryPage =
                                codec.decodeFromString(
                                    evaluate(
                                        "JSON.stringify(await __catalogProviders[${quote(
                                            providerId,
                                        )}].library(${codec.encodeToString(request)}))",
                                    ),
                                )

                            override suspend fun setSongVote(request: SongVoteRequest): Boolean =
                                codec.decodeFromString(
                                    evaluate(
                                        "JSON.stringify(await __catalogProviders[${quote(
                                            providerId,
                                        )}].setSongVote(${codec.encodeToString(request)}))",
                                    ),
                                )
                        }
                    } else {
                        null
                    }
                context.registerCatalogProvider(catalogWithExtensions(catalog, recommendations, library))
                null
            }
            q.function("__service") { args ->
                val name = args[0] as String
                context.registerService(
                    name,
                    object : JavaScriptBoundService {
                        override suspend fun call(request: JsonObject): JsonObject =
                            codec.parseToJsonElement(evaluate("JSON.stringify(await __services[${quote(name)}]($request))")).jsonObject
                    },
                )
                null
            }
            q.function("__metadata") {
                context.registerMetadata(
                    object : MetadataProvider {
                        override suspend fun resolve(track: Track): Track =
                            codec.decodeFromString(evaluate("JSON.stringify(await __metadataFn(${codec.encodeToString(track)}))"))
                    },
                )
                null
            }
            q.function("__audio") { args ->
                val name = args[0] as String
                context.registerAudio(
                    name,
                    object : AudioProvider {
                        override val capabilities = setOf("mock")
                        override val platformRequirements = emptySet<String>()

                        override suspend fun resolve(request: AudioRequest): AudioResolution =
                            codec.decodeFromString(
                                evaluate("JSON.stringify(await __audioFns[${quote(name)}](${codec.encodeToString(request)}))"),
                            )
                    },
                )
                null
            }
            jsBudget { q.evaluate<Any?>(BRIDGE) }
            jsBudget {
                q.evaluate<Any?>(
                    requireNotNull(sources[p.manifest.script]) { "Missing script entry" },
                    filename = p.manifest.script!!,
                    asModule = true,
                )
            }
            jsBudget {
                q.evaluate<Any?>(
                    "if (!globalThis.plugin || typeof plugin.init !== 'function') throw Error('Plugin must export globalThis.plugin.init'); if (plugin.dispose != null && typeof plugin.dispose !== 'function') throw Error('Invalid plugin.dispose'); await plugin.init(host);",
                    asModule = true,
                )
            }
            sockets.startDelivery()
            @OptIn(FlowPreview::class)
            val playbackChanges = player.state.sample(250)
            observeUi("playback_changed", playbackChanges) { codec.encodeToString(it) }
            observeUi("ui_home_changed", homes.pages) { "{}" }
            observeUi("navigation_changed", host.navigation.destination) { codec.encodeToString(it) }
            observeUi("plugins_changed", plugins.map { pluginUiStates() }.distinctUntilChanged()) { codec.encodeToString(it) }
            (host as? UiLibraryHost)?.let { library ->
                observeUi("library_changed", flow { emitAll(library.library(0, 100)) }) { codec.encodeToString(it) }
            }
            events.subscribe(observers) { event ->
                try {
                    evaluate("JSON.stringify(await __emit(${quote(event.name)},${event.payload}))")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    scope.launch { quarantine(id, e) }
                }
            }
        }

        private fun pluginUiStates() = plugins.value.map { UiPluginState(it.manifest.id, it.manifest.name, it.state) }

        private fun <T> observeUi(
            name: String,
            updates: Flow<T>,
            encode: (T) -> String,
        ) {
            observers.launch {
                uiSubscriptions.map { name in it }.distinctUntilChanged().collectLatest { active ->
                    if (!active) return@collectLatest
                    updates.collect { state ->
                        try {
                            evaluate("JSON.stringify(await __emit(${quote(name)},${encode(state)}))")
                        } catch (e: CancellationException) {
                            if (e !is TimeoutCancellationException) throw e
                            scope.launch { quarantine(id, e) }
                        } catch (e: Exception) {
                            scope.launch { quarantine(id, e) }
                        }
                    }
                }
            }
        }

        suspend fun evaluate(code: String): String {
            val task = observers.async { evaluateRaw(code) }
            return try {
                task.await()
            } finally {
                task.cancel()
            }
        }

        private suspend fun evaluateRaw(code: String): String =
            jsGate.withLock {
                check(alive)
                // Module top-level await cannot return directly; serialize through a global result.
                jsBudget {
                    js!!.evaluate<Any?>("globalThis.__result = $code;", asModule = true)
                    js!!.evaluate<String>("__result ?? 'null'")
                }
            }

        suspend fun action(
            action: String,
            value: String,
            target: UiTarget? = null,
        ) {
            if (js != null) {
                val context = codec.encodeToString(target)
                evaluate(
                    "JSON.stringify(await (() => {const fn=__actions[${quote(
                        action,
                    )}]; if(!fn) throw Error('Missing action'); return fn(${quote(value)},JSON.parse(${quote(context)}));})())",
                )
            }
        }

        suspend fun close() {
            sockets.close()
            job.cancelAndJoin()
            withContext(NonCancellable) {
                try {
                    withTimeout(2000) {
                        if (js != null && alive) evaluateRaw("JSON.stringify(await globalThis.plugin?.dispose?.())")
                        native?.plugin?.stop()
                    }
                } finally {
                    alive = false
                    jsGate.withLock {
                        js?.close()
                        js = null
                    }
                    native?.close?.invoke()
                    native = null
                    registry.remove(id)
                    staged.remove(id)
                }
            }
        }
    }

    companion object {
        private val UI_EVENTS = setOf("playback_changed", "navigation_changed", "plugins_changed", "library_changed", "ui_home_changed")

        fun quote(s: String) = JsonPrimitive(s).toString()

        fun validateTree(tree: UiNode) {
            val ids = mutableSetOf<String>()
            var count = 0

            fun walk(
                n: UiNode,
                depth: Int,
            ) {
                require(depth <= 32 && ++count <= 2000) { "UI tree limit" }
                require(ids.add(n.id) && n.id.isNotBlank()) { "Duplicate/empty component ID" }
                require(n.props.size <= 64 && n.props.values.all { it.length <= 16384 })
                n.children.forEach { walk(it, depth + 1) }
            }
            walk(tree, 0)
        }

        val BRIDGE =
            """
            globalThis.__services = {}; globalThis.__actions = {}; globalThis.__events = {}; globalThis.__audioFns = {}; globalThis.__metadataSources = {}; globalThis.__audioSources = {}; globalThis.__hooks = {}; globalThis.__lyricsProviders = {}; globalThis.__searchProviders = {}; globalThis.__catalogProviders = {};
            const requireFunction = fn => { if (typeof fn !== 'function') throw Error('Provider or callback must be a function'); return fn; };
            globalThis.__emit = async (event, data) => { for (const fn of (__events[event] || [])) await fn(data); };
            globalThis.host = Object.freeze({
              ui: (point, tree, layer='REPLACE') => __ui(point, JSON.stringify(tree), layer),
              patch: (id, props) => __patch(id, JSON.stringify(props)),
              patchMany: changes => __patchMany(JSON.stringify(changes)),
              log: text => __log(String(text)),
              on: (event, fn) => {
                (__events[event] ||= []).push(requireFunction(fn));
                __uiSubscription(event, __events[event].length);
                return () => {
                  __events[event] = __events[event].filter(x => x !== fn);
                  __uiSubscription(event, __events[event].length);
                };
              },
              action: (id, fn) => { __actions[id] = requireFunction(fn); },
              player: async (action, value=0) => __player(action, value),
              playTracks: async (tracks,startIndex=0) => __player('playTracks',JSON.stringify(tracks),startIndex),
              addToQueue: async tracks => __player('addToQueue',JSON.stringify(tracks)),
              replaceUpcomingQueue: async tracks => __player('replaceUpcomingQueue',JSON.stringify(tracks)),
              playerState: () => JSON.parse(__playerState()),
              platform: () => __platform(),
              locale: () => JSON.parse(__locale()),
              destination: () => __destination(),
              navigate: destination => host.call('navigation.route/v1', {destination}),
              pluginStates: () => JSON.parse(__pluginStates()),
              library: async (offset=0, limit=100) => JSON.parse(await __libraryPage(offset, limit)),
              playerCapabilities: () => JSON.parse(__playerCapabilities()),
              settings: Object.freeze({get: async key => JSON.parse(await __settings('get'))[key], all: async () => JSON.parse(await __settings('get')), set: async (key,value) => JSON.parse(await __settings('set',key,JSON.stringify(value)))}),
              storage: Object.freeze({get: async key => JSON.parse(await __storage('get',key)), set: async (key,value) => JSON.parse(await __storage('set',key,JSON.stringify(value))), remove: async key => __storage('set',key,'null')}),
              login: callbacks => { if(!callbacks || typeof callbacks.request!=='function' || typeof callbacks.login!=='function')throw Error('Login requires request and login callbacks'); if(callbacks.logout!=null)requireFunction(callbacks.logout);globalThis.__login=callbacks; },
              loginState: state => __loginState(JSON.stringify(state)),
              home: callback => { globalThis.__home=requireFunction(callback); },
              http: async request => JSON.parse(await __http(JSON.stringify(request))),
              socket: Object.freeze({open: async request => __socket('open',JSON.stringify(request)), send: async (id,text) => __socket('send',String(id),String(text)), receive: async (id,timeoutMs=10000) => JSON.parse(await __socket('receive',String(id),timeoutMs)), close: async id => __socket('close',String(id))}),
              track: async identity => JSON.parse(await __catalog(JSON.stringify(identity))),
              service: (name, fn) => { __services[name] = requireFunction(fn); __service(name); },
              contract: contract => __contract(JSON.stringify(contract)),
              hook: (name, phase, fn) => { __hooks[name+':'+phase] = fn; __hook(name,phase); },
              emit: async (event, payload) => __event(event,JSON.stringify(payload)),
              call: async (name, data={}) => JSON.parse(await __call(name, JSON.stringify(data))),
              metadata: fn => { globalThis.__metadataFn = requireFunction(fn); __metadata(); },
              metadataSource: (id, fn) => { __metadataSources[id] = requireFunction(fn); __metadataSource(id); },
              audioSource: (id, capabilities, fn) => { __audioSources[id] = requireFunction(fn); __audioSource(id, JSON.stringify(capabilities)); },
              lyrics: (id, fn) => { __lyricsProviders[id] = requireFunction(fn); __lyrics(id); },
              search: (id, fn) => { __searchProviders[id] = requireFunction(fn); __search(id); },
              catalog: (id, provider) => { if(!provider || !['album','artist','playlist','recommendations','library'].some(key=>typeof provider[key]==='function'))throw Error('Catalog requires a lookup callback');for(const key of ['album','artist','playlist','recommendations','library','setSongVote'])if(provider[key]!=null)requireFunction(provider[key]);__catalogProviders[id]=provider;__catalogProvider(id,typeof provider.recommendations==='function',typeof provider.library==='function',typeof provider.setSongVote==='function',provider.supportsLikedSongs===true); },
              audio: (name, fn) => { __audioFns[name] = requireFunction(fn); __audio(name); }
            });
            """.trimIndent()
    }
}
