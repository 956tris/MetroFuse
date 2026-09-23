package com.metrolist.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * QQ Music lyrics path (lyrics-only — QQ refuses audio streams to this
 * region, so the vkey/audio endpoints are never used):
 *
 *   1. Search: POST u.y.qq.com/cgi-bin/musicu.fcg
 *      (DoSearchForQQMusicDesktop -> req.data.body.song.list[] with
 *      mid/songmid, id/songid, name/title, singer[].name, album.name,
 *      interval, time_public).
 *   2. QRC first: c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg using the
 *      numeric songid, triple-DES + inflate (see QQMusicDes.kt), converted to
 *      the app's rich-sync format so word-by-word highlighting works.
 *   3. LRC fallback only when QRC is unavailable:
 *      GET c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg
 *      ?songmid=<mid>&format=json&nobase64=1 ("lyric" field).
 *
 * Matching is score-based (never first-hit): the artist must match one of
 * singer[].name, then title/album/duration (|interval - duration| <= 2s)
 * decide, with the official album preferred over reuploads. Results and
 * misses are cached in memory and on disk.
 */
internal object QQMusicLyrics {
    private const val TAG = "QQLyrics"

    /** Hard timeout so a slow QQ call never delays lyrics. */
    private const val QQ_TIMEOUT_MS = 1800L

    /** Minimum score for a candidate to be accepted (see scoring below). */
    private const val MIN_SCORE = 150

    /** Miss cache TTL: failed lookups aren't repeated within a day. */
    private const val MISS_TTL_MS = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val KEY1 = "!@#)(NHLiuy*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY2 = "123ZXC!@#)(*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY3 = "!@#)(*$%^&abcDEF".toByteArray(Charsets.ISO_8859_1)

    private val qrcLineRegex = Regex("""^\[(\d+),(\d+)\](.*)$""")
    private val qrcWordRegex = Regex("""([^(]*)\((\d+),(\d+)\)""")
    private val lyricContentRegex = Regex("""<Lyric_1[^>]*LyricContent="(.*?)"\s*/?>""", RegexOption.DOT_MATCHES_ALL)

    private lateinit var httpClient: HttpClient

    fun init(client: HttpClient) {
        httpClient = client
    }

    // ---- Disk + memory cache: (normalisedTitle, normalisedArtist, durationBucket) -> songmid ----

    private var diskDir: File? = null
    private val midMem = ConcurrentHashMap<String, String>()
    private val songidMem = ConcurrentHashMap<String, Long>()
    private val lyricsMem = ConcurrentHashMap<String, String>()
    private val missMem = ConcurrentHashMap<String, Long>()

    fun initCacheDir(dir: File) {
        if (diskDir != null) return
        diskDir = runCatching { File(dir, "qq_lyrics").apply { mkdirs() } }.getOrNull()
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = runCatching {
        val startMs = android.os.SystemClock.elapsedRealtime()
        val query = "$title $artist"
        Timber.tag(TAG).d("lookup query='%s' duration=%ss album='%s'", query, duration, album)

        val bucket = (duration / 5) * 5
        val normTitle = normalizeForMatch(title, keepTagsOf("$title $artist"))
        val normArtist = normalizeForMatch(artist, keepTagsOf("$title $artist"))
        val cacheKey = "$normTitle\n$normArtist\n$bucket"

        // Miss cache (with expiry): don't repeat failed lookups.
        val missTs = missMem[cacheKey] ?: readMissDisk(cacheKey)
        if (missTs != null && startMs - missTs < MISS_TTL_MS) {
            throw IllegalStateException("Cached miss for '$query'")
        }

        val lyrics = withTimeout(QQ_TIMEOUT_MS) {
            // Cached songmid fast-path (songid travels with it so the QRC
            // attempt still works on repeat plays).
            val cached = midMem[cacheKey]?.let { it to songidMem[it] } ?: readMidDisk(cacheKey)
            if (cached != null) {
                val (cachedMid, cachedSongid) = cached
                midMem[cacheKey] = cachedMid
                cachedSongid?.let { songidMem[cachedMid] = it }
                Timber.tag(TAG).d("query='%s' cache=hit mid=%s", query, cachedMid)
                lyricsMem[cachedMid] ?: readLyricsDisk(cachedMid)?.also { lyricsMem[cachedMid] = it }
                    ?: fetchLyricContent(cachedMid, query, songidHint = cachedSongid)
            } else {
                val candidates = search(query)
                Timber.tag(TAG).d("query='%s' candidates=%d", query, candidates.size)
                if (candidates.isEmpty()) throw IllegalStateException("No QQ Music search results for '$query'")

                val (best, score) = scoreCandidates(candidates, title, artist, album, duration)
                    ?: throw IllegalStateException("No QQ match above threshold for '$query' (${candidates.size} candidates)")
                val mid = best.mid
                    ?: throw IllegalStateException("QQ Music result missing songmid")
                Timber.tag(TAG).d("query='%s' candidates=%d chosenMid=%s score=%d", query, candidates.size, mid, score)

                midMem[cacheKey] = mid
                best.songid?.let { songidMem[mid] = it }
                writeMidDisk(cacheKey, mid, best.songid)
                lyricsMem[mid] ?: readLyricsDisk(mid)?.also { lyricsMem[mid] = it }
                    ?: fetchLyricContent(mid, query, songidHint = best.songid)
            }
        }

        val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
        Timber.tag(TAG).i("query='%s' success chars=%d timeMs=%d", query, lyrics.length, elapsed)
        lyrics
    }.onFailure { e ->
        // Timeouts are transient (never cached); external cancellation must propagate.
        if (e is kotlinx.coroutines.TimeoutCancellationException) {
            Timber.tag(TAG).w("query='%s %s' timed out after %dms", title, artist, QQ_TIMEOUT_MS)
            return@onFailure
        }
        if (e is kotlinx.coroutines.CancellationException) throw e
        val missKey = fetchCacheKey(title, artist, duration)
        writeMissDisk(missKey)
        Timber.tag(TAG).w("query='%s %s' failed: %s", title, artist, e.message)
    }

    private fun fetchCacheKey(title: String, artist: String, duration: Int): String {
        val keep = keepTagsOf("$title $artist")
        return "${normalizeForMatch(title, keep)}\n${normalizeForMatch(artist, keep)}\n${(duration / 5) * 5}"
    }

    /** QRC first (word-timed, by numeric songid), LRC fallback (by songmid). Only LRC when QRC is unavailable. */
    private suspend fun fetchLyricContent(mid: String, query: String, songidHint: Long? = null): String {
        // QRC attempt when we have a numeric songid.
        if (songidHint != null) {
            val qrc = downloadAndDecrypt(songidHint).getOrNull()?.let(::toAppLyricsFormat)
            if (!qrc.isNullOrBlank() && qrc.contains('[')) {
                lyricsMem[mid] = qrc
                writeLyricsDisk(mid, qrc)
                return qrc
            }
        }
        // LRC fallback.
        val lrc = fetchLrc(mid).getOrThrow()
        lyricsMem[mid] = lrc
        writeLyricsDisk(mid, lrc)
        return lrc
    }

    // ---- New musicu.fcg search ----

    private data class Candidate(
        val raw: JsonObject,
        val mid: String?,
        val songid: Long?,
        val title: String?,
        val singers: List<String>,
        val album: String?,
        val interval: Long?,
    )

    private suspend fun search(query: String): List<Candidate> = runCatching {
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 11)
                put("cv", "1003006")
                put("format", "json")
                put("inCharset", "utf-8")
                put("outCharset", "utf-8")
            }
            putJsonObject("req") {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                putJsonObject("param") {
                    put("query", query)
                    put("num_per_page", 10)
                    put("page_num", 1)
                    put("search_type", 0)
                }
            }
        }
        val response = httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
            header("Referer", "https://y.qq.com/")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching emptyList()
        val list = root["req"]?.jsonObject
            ?.get("data")?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("song")?.jsonObject
            ?.get("list")?.jsonArray
            ?: return@runCatching emptyList()
        list.mapNotNull { (it as? JsonObject)?.toCandidate() }
    }.onFailure { e ->
        Timber.tag(TAG).w("search failed for '%s': %s", query, e.message)
    }.getOrDefault(emptyList())

    private fun JsonObject.toCandidate(): Candidate? {
        val singers = (this["singer"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.qqField("name") }
            .orEmpty()
        val albumName = (this["album"] as? JsonObject)?.qqField("name")
            ?: qqField("albumname", "album")
        return Candidate(
            raw = this,
            mid = qqField("mid", "songmid"),
            songid = qqLong("id", "songid"),
            title = qqField("name", "title", "songname"),
            singers = singers,
            album = albumName,
            interval = qqLong("interval", "duration"),
        )
    }

    // ---- Scoring ----

    private fun scoreCandidates(
        candidates: List<Candidate>,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Pair<Candidate, Int>? {
        val keep = keepTagsOf("$title $artist")
        val normTitle = normalizeForMatch(title, keep)
        val normAlbum = album?.let { normalizeForMatch(it, keep) }.orEmpty()
        val trackArtists = splitArtists(artist).map { normalizeForMatch(it, keep) }.filter { it.isNotBlank() }

        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            val normCTitle = c.title?.let { t -> normalizeForMatch(t, keepTagsOf(t)) }.orEmpty()
            val normCSingers = c.singers.map { normalizeForMatch(it, keepTagsOf(it)) }

            // Hard requirement: the track artist must match any listed singer.
            val artistScore = when {
                trackArtists.isEmpty() -> 0
                trackArtists.any { ta -> normCSingers.any { it == ta } } -> 100
                trackArtists.any { ta -> normCSingers.any { s -> s.contains(ta) || ta.contains(s) } } -> 50
                else -> continue
            }

            var score = artistScore
            score += when {
                normCTitle.isEmpty() || normTitle.isEmpty() -> 0
                normCTitle == normTitle -> 80
                normCTitle.contains(normTitle) || normTitle.contains(normCTitle) -> 40
                else -> 0
            }
            // Official-album preference: reuploads carry a different album name.
            val normCAlbum = c.album?.let { a -> normalizeForMatch(a, keepTagsOf(a)) }.orEmpty()
            score += when {
                normAlbum.isEmpty() || normCAlbum.isEmpty() -> 0
                normCAlbum == normAlbum -> 60
                normCAlbum.contains(normAlbum) || normAlbum.contains(normCAlbum) -> 25
                else -> 0
            }
            score += c.interval?.let { d ->
                when (abs(d - duration)) {
                    in 0..2 -> 100
                    in 3..5 -> 50
                    in 6..10 -> 10
                    else -> -50
                }
            } ?: 0

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        val winner = best ?: return null
        return if (bestScore >= MIN_SCORE) winner to bestScore else null
    }

    // ---- Normalisation: lowercase, strip tag segments unless the track itself has them ----

    private val tagWords = listOf(
        "explicit", "live", "remaster", "acoustic", "remix", "cover",
        "karaoke", "instrumental", "sped up", "slowed", "reverb",
    )

    private fun keepTagsOf(raw: String): Set<String> {
        val lower = raw.lowercase()
        return buildSet {
            tagWords.forEach { if (it in lower) add(it) }
            if (Regex("""\bfeat\.?\b""").containsMatchIn(lower)) add("feat")
            if (Regex("""\bft\.?\b""").containsMatchIn(lower)) add("feat")
        }
    }

    private fun normalizeForMatch(raw: String, keep: Set<String>): String {
        var s = raw.lowercase()
        // Drop bracketed tag segments ("(Explicit)", "[Live]") unless the
        // current track carries that tag itself.
        s = Regex("""[\(\[][^)\]]*[\)\]]""").replace(s) { m ->
            val inner = m.value.lowercase()
            if (tagWords.any { it in inner && it !in keep }) "" else " ${m.value} "
        }
        // Drop trailing feat. credits unless the track has them.
        if ("feat" !in keep) {
            s = s.replace(Regex("""\s+feat\.?.*$"""), "")
            s = s.replace(Regex("""\s+ft\.?.*$"""), "")
        }
        // Keep letters (incl. CJK), digits and spaces.
        s = s.replace(Regex("""[^a-z0-9\u4e00-\u9fff\s]"""), " ")
        return s.split(Regex("""\s+""")).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    private fun splitArtists(artist: String): List<String> =
        artist.split(Regex("""\s*(?:&|/|,|、|x|\bfeat\.?|\bft\.?|\bwith\b|\band\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(artist.trim()) }

    // ---- LRC fallback (new endpoint) ----

    private suspend fun fetchLrc(mid: String): Result<String> = runCatching {
        val response = httpClient.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
            header("Referer", "https://y.qq.com/")
            parameter("songmid", mid)
            parameter("format", "json")
            parameter("nobase64", 1)
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw IllegalStateException("Bad LRC response for mid=$mid")
        val lyric = root["lyric"]?.jsonPrimitive?.contentOrNull
            ?.let(::unescapeEntities)?.trim()
            ?.takeIf { it.isNotBlank() && it.contains('[') }
            ?: throw IllegalStateException("No LRC lyric for mid=$mid")
        lyric
    }.onFailure { e ->
        Timber.tag(TAG).w("LRC fetch failed for mid=%s: %s", mid, e.message)
    }

    private fun unescapeEntities(s: String): String =
        s.replace("&#10;", "\n")
            .replace("&#13;", "")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    // ---- QRC download/decrypt (unchanged) ----

    private suspend fun downloadAndDecrypt(songid: Long): Result<String> = runCatching {
        val response = httpClient.get("https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg") {
            header("Referer", "https://y.qq.com/")
            parameter("version", "15")
            parameter("miniversion", "82")
            parameter("lrctype", "4")
            parameter("musicid", songid)
        }
        val xml = response.bodyAsText().replace("<!--", "").replace("-->", "")
        val contentBlock = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No lyric content for songid=$songid")
        // The content block is itself CDATA-wrapped (<![CDATA[<hex>]]>); strip that
        // wrapper explicitly rather than relying on hexToBytes' char filter, since
        // "CDATA" itself contains valid hex letters (C, D, A) that would otherwise
        // get silently spliced into the decrypted byte stream.
        val hex = Regex("""<!\[CDATA\[(.*?)]]>""", RegexOption.DOT_MATCHES_ALL)
            .find(contentBlock)?.groupValues?.get(1)?.trim()
            ?: contentBlock

        val encrypted = hexToBytes(hex)
        val step1 = QQMusicDes.crypt(encrypted, KEY1, decrypt = true)
        val step2 = QQMusicDes.crypt(step1, KEY2, decrypt = false)
        val step3 = QQMusicDes.crypt(step2, KEY3, decrypt = true)
        val inflated = inflate(step3)
        String(inflated, Charsets.UTF_8)
    }.onFailure { e ->
        Timber.tag(TAG).w("QRC download/decrypt failed for songid=%d: %s", songid, e.message)
    }

    /** Converts either QRC XML (word-timed) or plain LRC into the app's LyricsUtils rich-sync format. */
    private fun toAppLyricsFormat(decoded: String): String {
        val qrcBody = lyricContentRegex.find(decoded)?.groupValues?.get(1) ?: decoded
        if (!qrcBody.contains(Regex("""\(\d+,\d+\)"""))) {
            // Not word-timed QRC (e.g. plain LRC) — pass through as-is.
            return qrcBody
        }

        val out = StringBuilder()
        qrcBody.lines().forEach { rawLine ->
            val m = qrcLineRegex.find(rawLine.trim()) ?: return@forEach
            val lineStartMs = m.groupValues[1].toLongOrNull() ?: return@forEach
            val wordsPart = m.groupValues[3]
            val words = qrcWordRegex.findAll(wordsPart).toList()
            if (words.isEmpty()) return@forEach

            out.append('[').append(formatTime(lineStartMs)).append(']')
            var lastWordEndMs: Long? = null
            words.forEach { w ->
                val text = w.groupValues[1]
                val startMs = w.groupValues[2].toLongOrNull() ?: 0L
                val durMs = w.groupValues[3].toLongOrNull() ?: 0L
                if (text.isNotEmpty()) {
                    out.append('<').append(formatTime(startMs)).append('>').append(text)
                    lastWordEndMs = startMs + durMs
                }
            }
            // QQ's QRC data gives us each word's real (startMs, durMs), so we know
            // the true end time of the last word in the line — emit it as a trailing
            // timestamp instead of letting LyricsUtils interpolate to the *next
            // line's* start time, which stretches the last word across any
            // instrumental gap between lines.
            lastWordEndMs?.let { endMs -> out.append('<').append(formatTime(endMs)).append('>') }
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Single-DES cascade (not real 3DES — see QQMusicDes.kt), matches QQMusicCommon.dll's des/Ddes exactly. */

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    // ---- Disk cache helpers ----

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { b -> append(((b.toInt() and 0xff).toString(16)).padStart(2, '0')) }
        }
    }

    private fun diskFile(prefix: String, key: String): File? =
        diskDir?.let { File(it, "${prefix}_${md5(key)}") }

    private fun readMidDisk(key: String): Pair<String, Long?>? = runCatching {
        val text = diskFile("mid", key)?.takeIf { it.exists() }?.readText()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val parts = text.split('|')
        val mid = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        mid to parts.getOrNull(1)?.toLongOrNull()
    }.getOrNull()

    private fun writeMidDisk(key: String, mid: String, songid: Long?) {
        runCatching { diskFile("mid", key)?.writeText("$mid|${songid ?: -1}") }
    }

    private fun readLyricsDisk(mid: String): String? = runCatching {
        diskFile("lyrics", mid)?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeLyricsDisk(mid: String, lyrics: String) {
        runCatching { diskFile("lyrics", mid)?.writeText(lyrics) }
    }

    private fun readMissDisk(key: String): Long? = runCatching {
        diskFile("miss", key)?.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull().also { ts ->
        if (ts != null) missMem[key] = ts
    }

    private fun writeMissDisk(key: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        missMem[key] = now
        runCatching { diskFile("miss", key)?.writeText(now.toString()) }
    }

    private fun JsonObject.qqField(vararg keys: String): String? {
        for (k in keys) {
            (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun JsonObject.qqLong(vararg keys: String): Long? {
        for (k in keys) {
            (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull?.let { return it }
        }
        return null
    }
}
