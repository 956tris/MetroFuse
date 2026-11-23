package dev.metrofuse.runtime

import dev.metrofuse.api.Manifest
import dev.metrofuse.api.isSupportedApiVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class MarketplaceArtifact(val url: String, val sha256: String, val fileName: String)

@Serializable
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val version: String,
    val pluginApiVersion: String,
    val downloads: Map<String, MarketplaceArtifact>,
    val capabilities: List<String> = emptyList(),
    val icon: String? = null,
    val sourceRepository: String? = null,
    val releaseChannel: String = "stable",
    val categories: List<String> = emptyList(),
) {
    val displayCategories: List<String>
        get() = categories.ifEmpty { capabilities }.filter(String::isNotBlank).distinct()

    val sourceUrl: String?
        get() = sourceRepository?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()

    fun supports(platform: String): Boolean = platformArtifact(platform) != null

    fun artifact(platform: String): MarketplaceArtifact? {
        if (!isSupportedApiVersion(pluginApiVersion)) return null
        SemVer.parse(version)
        return platformArtifact(platform)?.also(::validateArtifact)
    }

    internal fun validate() {
        SemVer.parse(version)
        require(supports("android") || supports("desktop")) { "Missing supported marketplace artifact" }
        downloads.values.forEach(::validateArtifact)
    }

    private fun platformArtifact(platform: String): MarketplaceArtifact? {
        val extension = when (platform) {
            "android" -> "apk"
            "desktop" -> "jar"
            else -> return null
        }
        return downloads[platform]?.takeIf { it.fileName.endsWith(".$extension", ignoreCase = true) }
            ?: downloads["universal"]?.takeIf { it.fileName.endsWith(".mfp", ignoreCase = true) }
    }

    private fun validateArtifact(candidate: MarketplaceArtifact) {
        require(candidate.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Missing or invalid marketplace checksum" }
        require(candidate.url.toHttpUrl().isHttps) { "Marketplace downloads require HTTPS" }
        PackageStore.validatePath(candidate.fileName)
        require('/' !in candidate.fileName) { "Artifact filename must not contain directories" }
    }
}

@Serializable
data class MarketplaceCatalog(val schemaVersion: Int = 1, val plugins: List<MarketplaceEntry>)

class MarketplaceClient : AutoCloseable {
    @Volatile
    private var lastCatalog: Pair<String, List<MarketplaceEntry>>? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    fun cached(url: String): List<MarketplaceEntry>? =
        lastCatalog?.takeIf { it.first == url }?.second

    suspend fun fetch(url: String): List<MarketplaceEntry> = withContext(Dispatchers.IO) {
        val bytes = download(url, 2 * 1024 * 1024)
        val catalog = codec.decodeFromString<MarketplaceCatalog>(bytes.decodeToString())
        require(catalog.schemaVersion == 1 && catalog.plugins.size <= 1000) { "Unsupported marketplace catalog" }
        require(catalog.plugins.map { it.id }.distinct().size == catalog.plugins.size) { "Duplicate marketplace plugin IDs" }
        catalog.plugins.forEach(MarketplaceEntry::validate)
        lastCatalog = url to catalog.plugins
        catalog.plugins
    }

    suspend fun install(runtime: Runtime, entry: MarketplaceEntry): Manifest = withContext(Dispatchers.IO) {
        val artifact = requireNotNull(entry.artifact(runtime.platform)) { "No compatible artifact" }
        val bytes = download(artifact.url, 64 * 1024 * 1024)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest.equals(artifact.sha256, ignoreCase = true)) { "Marketplace SHA-256 verification failed" }
        val staging = File(runtime.storage, "marketplace-${UUID.randomUUID()}")
        try {
            val store = PackageStore(staging, runtime.platform)
            val candidate = store.install(ByteArrayInputStream(bytes), artifact.fileName)
            val manifest = store.verify(candidate)
            require(manifest.id == entry.id && manifest.version == entry.version) { "Marketplace metadata differs from package manifest" }
            runtime.installOrUpdate(ByteArrayInputStream(bytes), artifact.fileName)
        } finally {
            check(staging.canonicalFile.parentFile == runtime.storage.canonicalFile)
            staging.deleteRecursively()
        }
    }

    private suspend fun download(url: String, maximum: Int): ByteArray {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps) { "Marketplace requires HTTPS" }
        val call = client.newCall(Request.Builder().url(parsed).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            require(it.isSuccessful && it.request.url.isHttps) { "Marketplace HTTP ${it.code}" }
                            require(it.body.contentLength() <= maximum) { "Marketplace download exceeds size limit" }
                            it.body.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    require(output.size() + count <= maximum) { "Marketplace download exceeds size limit" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
