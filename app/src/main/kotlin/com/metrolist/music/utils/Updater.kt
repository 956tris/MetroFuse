/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val description: String,
    val releaseDate: String,
    val assets: List<ReleaseAsset>
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val architecture: String,
    val variant: String // "foss" or "gms"
)

object Updater {
    private val client = HttpClient(OkHttp)
    var lastCheckTime = -1L
        private set
    
    private var cachedReleaseInfo: ReleaseInfo? = null
    private var cachedAllReleases: List<ReleaseInfo> = emptyList()
    
    private const val CHECK_INTERVAL_MILLIS = 2 * 60 * 60 * 1000L // 2 hours
    private val githubApiBase = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}"
    private val versionNumberRegex = Regex("\\d+")

    fun currentArchitecture(): String {
        val arch = System.getProperty("os.arch").orEmpty().lowercase(Locale.US)
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64-v8a"
            arch.contains("arm") || arch.contains("armeabi") -> "armeabi-v7a"
            arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
            arch.contains("x86") -> "x86"
            else -> BuildConfig.ARCHITECTURE
        }
    }

    /**
     * Compares two version strings.
     * Returns: 1 if v1 > v2, -1 if v1 < v2, 0 if equal
     */
    fun compareVersions(v1: String, v2: String): Int {
        val v1Parts = versionParts(v1)
        val v2Parts = versionParts(v2)
        val maxLength = maxOf(v1Parts.size, v2Parts.size)
        
        for (i in 0 until maxLength) {
            val part1 = v1Parts.getOrNull(i) ?: 0
            val part2 = v2Parts.getOrNull(i) ?: 0
            when {
                part1 > part2 -> return 1
                part1 < part2 -> return -1
            }
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> {
        val parts = versionNumberRegex
            .findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()

        return parts.ifEmpty { listOf(0) }
    }

    /**
     * Checks if the latest version is newer than the current version.
     * Returns true if an update is available (latestVersion > currentVersion)
     */
    fun isUpdateAvailable(currentVersion: String, latestVersion: String): Boolean {
        return compareVersions(latestVersion, currentVersion) > 0
    }

    /**
     * Get the current app's architecture and variant
     */
    private fun getCurrentAppVariant(): Pair<String, String> {
        val architecture = currentArchitecture()
        val variant = if (BuildConfig.CAST_AVAILABLE) "gms" else "foss"
        return architecture to variant
    }

    /**
     * Parse release assets from GitHub API response
     */
    private fun parseAssets(assetsArray: JSONArray): List<ReleaseAsset> {
        val assets = mutableListOf<ReleaseAsset>()
        
        for (i in 0 until assetsArray.length()) {
            val asset = assetsArray.getJSONObject(i)
            val name = asset.getString("name")
            
            // Skip non-APK files
            if (!name.lowercase().endsWith(".apk")) continue
            
            val downloadUrl = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            val (arch, variant) = inferAssetTarget(name) ?: continue
            
            assets.add(ReleaseAsset(name, downloadUrl, size, arch, variant))
        }
        
        return assets
    }

    private fun inferAssetTarget(name: String): Pair<String, String>? {
        val lowerName = name.lowercase()
        if (!lowerName.endsWith(".apk")) return null
        if (lowerName.contains("debug")) return null

        val variant = when {
            lowerName == "metrolist-with-google-cast.apk" ||
                lowerName.contains("with-google-cast") ||
                lowerName.contains("google-cast") ||
                lowerName.contains("gms") -> "gms"
            lowerName.contains("izzy") -> return null
            else -> "foss"
        }

        val architecture = when {
            lowerName == "metrolist.apk" || lowerName == "metrofuse.apk" -> "universal"
            lowerName.contains("universal") -> "universal"
            lowerName.contains("arm64-v8a") || lowerName.contains("arm64") -> "arm64-v8a"
            lowerName.contains("armeabi-v7a") || lowerName.contains("armeabi") -> "armeabi-v7a"
            lowerName.contains("x86_64") || lowerName.contains("x86-64") -> "x86_64"
            lowerName.contains("x86") -> "x86"
            lowerName.startsWith("app-") && lowerName.endsWith("-release.apk") ->
                lowerName.removePrefix("app-").removeSuffix("-release.apk")
            lowerName.startsWith("metrofuse") -> "universal"
            else -> return null
        }

        return architecture to variant
    }

    private fun parseReleaseInfo(json: JSONObject): ReleaseInfo {
        val tagName = json.getString("tag_name")
        val displayName = json.optString("name")
            .trim()
            .ifBlank { tagName }

        return ReleaseInfo(
            tagName = tagName,
            versionName = displayName,
            description = json.optString("body"),
            releaseDate = json.getString("published_at"),
            assets = parseAssets(json.getJSONArray("assets"))
        )
    }

    /**
     * Fetch latest release from GitHub API
     */
    suspend fun getLatestRelease(forceRefresh: Boolean = false): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Return cached if available and not forcing refresh
                if (cachedReleaseInfo != null && !forceRefresh) {
                    return@runCatching cachedReleaseInfo!!
                }
                
                val response = client.get("$githubApiBase/releases/latest")
                    .bodyAsText()
                val json = JSONObject(response)
                
                val releaseInfo = parseReleaseInfo(json)
                
                cachedReleaseInfo = releaseInfo
                lastCheckTime = System.currentTimeMillis()
                releaseInfo
            }
        }

    /**
     * Fetch all releases from GitHub API (paginated)
     */
    suspend fun getAllReleases(forceRefresh: Boolean = false): Result<List<ReleaseInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cachedAllReleases.isNotEmpty() && !forceRefresh) {
                    return@runCatching cachedAllReleases
                }
                
                val releases = mutableListOf<ReleaseInfo>()
                var page = 1
                var hasMore = true
                
                while (hasMore && page <= 10) { // Limit to 10 pages
                    val response = client.get("$githubApiBase/releases?page=$page&per_page=30")
                        .bodyAsText()
                    val json = JSONArray(response)
                    
                    if (json.length() == 0) {
                        hasMore = false
                        break
                    }
                    
                    for (i in 0 until json.length()) {
                        val releaseObj = json.getJSONObject(i)
                        releases.add(parseReleaseInfo(releaseObj))
                    }
                    
                    page++
                }
                
                cachedAllReleases = releases
                releases
            }
        }

    /**
     * Get the download URL for the correct app variant
     */
    fun getDownloadUrlForCurrentVariant(releaseInfo: ReleaseInfo): String? {
        val (currentArch, currentVariant) = getCurrentAppVariant()
        
        val matchingAsset = releaseInfo.assets
            .find { it.architecture == currentArch && it.variant == currentVariant }
            ?: releaseInfo.assets.find { it.architecture == "universal" && it.variant == currentVariant }
            ?: releaseInfo.assets.find { it.variant == currentVariant }
            ?: releaseInfo.assets.find { it.architecture == "universal" }
            ?: releaseInfo.assets.firstOrNull()

        return matchingAsset?.downloadUrl
    }

    /**
     * Get all available download URLs for a release
     */
    fun getAllDownloadUrls(releaseInfo: ReleaseInfo): Map<String, String> {
        return releaseInfo.assets.associate { "${it.architecture}-${it.variant}" to it.downloadUrl }
    }

    /**
     * Check if update is needed (respects 2-hour cache)
     */
    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo?, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Check if we should fetch (2 hour interval)
                val shouldFetch = forceRefresh || 
                    (System.currentTimeMillis() - lastCheckTime) > CHECK_INTERVAL_MILLIS
                
                if (!shouldFetch && cachedReleaseInfo != null) {
                    val hasUpdate = isUpdateAvailable(
                        BuildConfig.VERSION_NAME,
                        cachedReleaseInfo!!.tagName
                    )
                    return@runCatching cachedReleaseInfo!! to hasUpdate
                }
                
                val result = getLatestRelease(forceRefresh = true)
                if (result.isSuccess) {
                    val releaseInfo = result.getOrThrow()
                    val hasUpdate = isUpdateAvailable(
                        BuildConfig.VERSION_NAME,
                        releaseInfo.tagName
                    )
                    releaseInfo to hasUpdate
                } else {
                    throw result.exceptionOrNull() ?: Exception("Unknown error")
                }
            }
        }

    /**
     * Get the download URL for the correct app variant
     * Returns null if no matching asset is found
     */
    fun getLatestDownloadUrl(): String? {
        return cachedReleaseInfo?.let { getDownloadUrlForCurrentVariant(it) }
    }
    
    /**
     * Get the latest release info (cached)
     */
    fun getCachedLatestRelease(): ReleaseInfo? = cachedReleaseInfo
}
