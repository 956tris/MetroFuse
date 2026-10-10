package com.metrolist.kugou

import com.metrolist.kugou.models.AudioSearchResponse
import com.metrolist.kugou.models.AudioStreamResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.MessageDigest

/**
 * KuGou audio (stream URL) access over the legacy no-auth endpoints.
 *
 * Search uses the mobile CDN song search (which exposes per-tier file
 * hashes), playback URLs come from the tracker CDN v2 endpoint keyed by
 * md5(fileHash + "kgcloudv2"), with the classic getSongInfo.php endpoint
 * as a fallback. No login, cookie, or API key is required.
 */
object KuGouAudio {
    enum class Quality {
        STANDARD_128,
        HIGH_320,
        LOSSLESS_FLAC,
    }

    // KuGou serves its full catalog (including region-gated tracks) to
    // Chinese egress only; elsewhere the same queries return knockoffs or
    // nothing. These headers pin us to the Chinese catalog on every call.
    // The IPs are generated fresh on each app start inside allocated
    // Chinese ranges and stay stable for the whole session (a normal user
    // doesn't change location per request), with failover between ranges.
    private val regionIps: List<String> by lazy {
        val random = java.util.Random()
        listOf(
            "222.128.${random.nextInt(256)}.${random.nextInt(254) + 1}",
            "114.240.${random.nextInt(256)}.${random.nextInt(254) + 1}",
            "117.136.${random.nextInt(256)}.${random.nextInt(254) + 1}",
            "121.22.${random.nextInt(256)}.${random.nextInt(254) + 1}",
        )
    }
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val SIGNED_APP_ID = "1005"
    private const val SIGNED_CLIENT_VER = "20489"
    private const val SIGN_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"
    private const val ANDROID_SIGN_SALT = "OIlwieks28dk2k092lksi2UIkp"

    data class SongHit(
        val hash: String,
        val hash320: String,
        val hashFlac: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val privilege: Int = 0,
        val privilege320: Int = 0,
        val privilegeFlac: Int = 0,
        val mvhash: String = "",
    ) {
        fun isFreeFor(quality: Quality): Boolean =
            when (quality) {
                Quality.LOSSLESS_FLAC -> privilegeFlac == 0
                Quality.HIGH_320 -> privilege320 == 0
                Quality.STANDARD_128 -> privilege == 0
            }
    }

    data class Stream(
        val url: String,
        val extName: String,
        val fileSize: Long?,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private val client = HttpClient {
        expectSuccess = true

        install(ContentNegotiation) {
            val json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
            }
            json(json)
            json(json, ContentType.Text.Html)
            json(json, ContentType.Text.Plain)
        }

        install(ContentEncoding) {
            gzip()
            deflate()
        }
    }

    suspend fun searchSongs(
        keyword: String,
        pageSize: Int = 20,
    ): List<SongHit> =
        runCatching {
            withRegionIp { regionIp ->
                // NOTE: mobilecdn.kugou.com serves a certificate that is not
                // valid for that hostname, so Android TLS rejects it outright.
                // mobileservice.kugou.com (the same host the lyrics module
                // uses) returns the same rich search payload over valid TLS.
                client.get("https://mobileservice.kugou.com/api/v3/search/song") {
                    applyRegionHeaders(regionIp)
                    parameter("version", 9108)
                    parameter("plat", 0)
                    parameter("pagesize", pageSize.coerceIn(1, 50))
                    parameter("showtype", 0)
                    url.encodedParameters.append(
                        "keyword",
                        keyword.encodeURLParameter(spaceToPlus = false),
                    )
                }.body<AudioSearchResponse>()
                    .data?.info.orEmpty()
                    .filter { it.hash.isNotBlank() }
                    .map {
                        SongHit(
                            hash = it.hash,
                            hash320 = it.hash320,
                            hashFlac = it.hashFlac,
                            title = it.songname,
                            artist = it.singername,
                        album = it.albumName,
                        durationSec = it.duration,
                        privilege = it.privilege,
                        privilege320 = it.privilege320,
                        privilegeFlac = it.privilegeFlac,
                        mvhash = it.mvhash,
                    )
                    }
            }
        }.getOrDefault(emptyList())

    /**
     * Returns the file hash to request for [quality], falling back down
     * the tier ladder when a tier hash is missing.
     */
    fun pickHash(
        hit: SongHit,
        quality: Quality,
    ): String =
        when (quality) {
            Quality.LOSSLESS_FLAC ->
                hit.hashFlac.ifBlank { hit.hash320.ifBlank { hit.hash } }
            Quality.HIGH_320 ->
                hit.hash320.ifBlank { hit.hash }
            Quality.STANDARD_128 ->
                hit.hash
        }

    suspend fun streamUrl(fileHash: String): Stream {
        require(fileHash.isNotBlank()) { "KuGou file hash is blank" }
        // v2-app and v2-web serve the same file from different CDN hosts
        // (fsandroid/fsmobile vs fspc), so chain them before the legacy
        // getSongInfo fallback for host redundancy.
        runCatching { streamUrlV2(fileHash) }
            .onSuccess { return it }
        runCatching { streamUrlWeb(fileHash) }
            .onSuccess { return it }
        return streamUrlLegacy(fileHash)
    }

    private suspend fun streamUrlV2(fileHash: String): Stream {
        val key = md5Hex(fileHash + "kgcloudv2")
        val response =
            withRegionIp { regionIp ->
                client.get("https://trackercdn.kugou.com/i/v2/") {
                    applyRegionHeaders(regionIp)
                    parameter("cmd", 25)
                    parameter("hash", fileHash)
                    parameter("key", key)
                    parameter("pid", 2)
                    parameter("behavior", "play")
                    parameter("appid", 1005)
                }.body<AudioStreamResponse>()
            }
        val url = response.firstUrl()
            ?: throw IllegalStateException("KuGou tracker returned no URL for $fileHash")
        return Stream(
            url = url,
            extName = response.extName?.lowercase().orEmpty(),
            fileSize = response.fileSize,
        )
    }

    private suspend fun streamUrlWeb(fileHash: String): Stream {
        val key = md5Hex(fileHash + "kgcloudv2")
        val response =
            withRegionIp { regionIp ->
                client.get("https://trackercdnbj.kugou.com/i/v2/") {
                    applyRegionHeaders(regionIp)
                    parameter("cmd", 23)
                    parameter("hash", fileHash)
                    parameter("key", key)
                    parameter("pid", 1)
                    parameter("behavior", "play")
                }.body<AudioStreamResponse>()
            }
        val url = response.firstUrl()
            ?: throw IllegalStateException("KuGou web tracker returned no URL for $fileHash")
        return Stream(
            url = url,
            extName = response.extName?.lowercase().orEmpty(),
            fileSize = response.fileSize,
        )
    }

    private suspend fun streamUrlLegacy(fileHash: String): Stream {
        val response =
            withRegionIp { regionIp ->
                client.get("https://m.kugou.com/app/i/getSongInfo.php") {
                    applyRegionHeaders(regionIp)
                    parameter("cmd", "playInfo")
                    parameter("hash", fileHash)
                }.body<AudioStreamResponse>()
            }
        val url = response.firstUrl()
            ?: throw IllegalStateException("KuGou playInfo returned no URL for $fileHash")
        return Stream(
            url = url,
            extName = response.extName?.lowercase().orEmpty(),
            fileSize = response.fileSize,
        )
    }

    /**
     * Official-MV audio fallback. Unlike the audio tracker endpoints, the
     * trackermv endpoint does not enforce the audio privilege flags, so
     * gated originals play when they have an MV. The returned MP4 carries
     * the full song in its audio track, but note it may be the video edit
     * (skits, clean lyrics, longer runtime) rather than the album cut.
     */
    suspend fun videoUrl(mvhash: String): Stream {
        require(mvhash.isNotBlank()) { "KuGou MV hash is blank" }
        val params = linkedMapOf(
            "backupdomain" to "1",
            "cmd" to "123",
            "ext" to "mp4",
            "ismp3" to "0",
            "hash" to mvhash,
            "pid" to "1",
            "type" to "1",
            "dfid" to "-",
            "mid" to deviceMid,
            "uuid" to "-",
            "appid" to SIGNED_APP_ID,
            "clientver" to SIGNED_CLIENT_VER,
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            "key" to md5Hex(mvhash + SIGN_KEY_SALT + SIGNED_APP_ID + deviceMid + "0"),
        )
        val signature = md5Hex(
            ANDROID_SIGN_SALT + params.toSortedMap().entries.joinToString("") { it.key + "=" + it.value } + ANDROID_SIGN_SALT,
        )
        val response =
            withRegionIp { regionIp ->
                client.get("https://gateway.kugou.com/v2/interface/index") {
                    applyRegionHeaders(regionIp)
                    header("x-router", "trackermv.kugou.com")
                    params.forEach { (key, value) -> parameter(key, value) }
                    parameter("signature", signature)
                }.body<com.metrolist.kugou.models.VideoUrlResponse>()
            }
        val url = response.firstUrl()
            ?: throw IllegalStateException("KuGou MV endpoint returned no URL for $mvhash")
        return Stream(
            url = url,
            extName = "mp4",
            fileSize = null,
        )
    }

    // Stable-per-process device identity for the signed gateway endpoints.
    // (Persisting these per install in DataStore would be strictly better
    // for risk-control; random-per-launch is the documented fallback.)
    private val deviceGuid: String by lazy {
        List(32) { "0123456789abcdef".random() }.joinToString("")
    }
    private val deviceMid: String by lazy {
        BigInteger(md5Hex(deviceGuid), 16).toString()
    }

    private fun md5Hex(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyRegionHeaders(regionIp: String?) {
        header("User-Agent", DESKTOP_USER_AGENT)
        header("Referer", "https://www.kugou.com/")
        if (!regionIp.isNullOrBlank()) {
            header("X-Forwarded-For", regionIp)
            header("X-Real-IP", regionIp)
        }
    }

    /**
     * Runs [block] with the session region IP, failing over once to the
     * next range IP since the backend flakes with transient errors.
     */
    private suspend fun <T> withRegionIp(block: suspend (String?) -> T): T {
        val attempts = listOf(regionIps.firstOrNull(), regionIps.getOrNull(1))
        var lastError: Throwable? = null
        attempts.forEach { regionIp ->
            runCatching { return block(regionIp) }
                .onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("KuGou request failed with no region IP")
    }
}
