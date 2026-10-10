package com.metrolist.paxsenix

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * QQ Music lyrics path (lyrics-only — QQ refuses audio streams to this
 * region, so the vkey/audio endpoints are never used):
 *
 *   1. Search: POST u.y.qq.com/cgi-bin/musicu.fcg
 *      (DoSearchForQQMusicDesktop -> req.data.body.song.list[] with
 *      mid/songmid, id/songid, name/title, singer[].name, album.name,
 *      interval, time_public).
 *   2. QRC first (word-timed): c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg
 *      using the numeric songid, triple-DES cascade + inflate (see
 *      QQMusicDes.kt), converted to the app's rich-sync format.
 *   3. Word-by-word fallback (also word-timed, different endpoint — the
 *      musichallSong.PlayLyricInfo "qrc" payload, same verified DES cascade):
 *      POST u.y.qq.com/cgi-bin/musicu.fcg GetPlayLyricInfo.
 *      QRC and word-by-word are raced in parallel; QRC wins.
 *   4. LRC last resort:
 *      GET c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg
 *      ?songmid=<mid>&format=json&nobase64=1 ("lyric" field).
 *
 * Matching is score-based (never first-hit) in two passes: a strict pass
 * on the "title artist" search, then a loose title-only pass for
 * unreleased/alternate versions QQ ranks poorly under the full query.
 * Artist AND title must always match one of singer[].name / the track
 * title (vetoes - wrong-song acceptance is worse than a miss); duration
 * is purely advisory so live/unreleased length differences never veto an
 * exact match, with the official album preferred over reuploads. Results
 * and misses are cached in memory and on disk.
 */
internal object QQMusicLyrics {
    private const val TAG = "QQLyrics"

    /**
     * Total internal budget: search (~350ms) + QRC/word-by-word race (~350ms
     * each, in parallel) + LRC fallback. Must stay under LyricsHelper's
     * 8s per-provider cap. (A previous 1.8s budget timed out on real
     * mobile networks before any lyric call finished — total breakage.)
     */
    private const val QQ_TIMEOUT_MS = 7500L

    /** Minimum score for a candidate to be accepted (see scoring below). */
    private const val MIN_SCORE = 150

    /** Loose-pass bar: same vetoes, less supporting evidence required. */
    private const val LOOSE_MIN_SCORE = 80

    /**
     * Miss cache TTL: failed lookups retry after a few hours, not a day, so
     * a transient blip (or QQ indexing an unreleased song late) never
     * poisons the track for long.
     */
    private const val MISS_TTL_MS = 6L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val KEY1 = "!@#)(NHLiuy*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY2 = "123ZXC!@#)(*$%^&".toByteArray(Charsets.ISO_8859_1)
    private val KEY3 = "!@#)(*$%^&abcDEF".toByteArray(Charsets.ISO_8859_1)

    /**
     * Browser UA for every QQ call: the shared client defaults to
     * Metrolist/<version>, and QQ's lyric hosts 403 non-browser agents.
     */
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

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
                    ?: fetchLyricContent(cachedMid, query, title, artist, album, duration, songidHint = cachedSongid)
            } else {
                val candidates = search(query)
                Timber.tag(TAG).d("query='%s' candidates=%d", query, candidates.size)
                // Strict pass first (accurate), then loose passes for
                // unreleased/alternate versions: same vetoes, lower bar,
                // then a title-only query QQ ranks better without the artist.
                var picked = scoreCandidates(candidates, title, artist, album, duration, MIN_SCORE)
                if (picked == null) {
                    picked = scoreCandidates(candidates, title, artist, album, duration, LOOSE_MIN_SCORE)
                }
                if (picked == null && artist.isNotBlank()) {
                    val loose = search(title)
                    Timber.tag(TAG).d("query='%s' loose candidates=%d", title, loose.size)
                    picked = scoreCandidates(loose, title, artist, album, duration, LOOSE_MIN_SCORE)
                }
                val (best, score) = picked
                    ?: throw IllegalStateException("No QQ match above threshold for '$query' (${candidates.size} candidates)")
                val mid = best.mid
                    ?: throw IllegalStateException("QQ Music result missing songmid")
                Timber.tag(TAG).d("query='%s' candidates=%d chosenMid=%s score=%d", query, candidates.size, mid, score)

                midMem[cacheKey] = mid
                best.songid?.let { songidMem[mid] = it }
                writeMidDisk(cacheKey, mid, best.songid)
                lyricsMem[mid] ?: readLyricsDisk(mid)?.also { lyricsMem[mid] = it }
                    ?: fetchLyricContent(mid, query, title, artist, album, duration, songidHint = best.songid)
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

    /**
     * Lyric chain: QRC first, word-by-word fallback, LRC last resort. All
     * three launch together; QRC wins, word-by-word is next, LRC is only
     * awaited when both word-timed stages miss - so the fallback path
     * costs zero extra wait.
     */
    private suspend fun fetchLyricContent(
        mid: String,
        query: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        songidHint: Long? = null,
    ): String = coroutineScope {
        val qrcDeferred = async {
            runCatching {
                val songid = songidHint ?: throw IllegalStateException("no songid for QRC")
                val decoded = downloadAndDecrypt(songid).getOrThrow()
                toAppLyricsFormat(decoded).takeIf { it.isNotBlank() && hasConvertedWordTimings(it) }
                    ?: throw IllegalStateException("QRC has no word timings (songid=$songid)")
            }
        }
        val wbwDeferred = async {
            runCatching {
                val songid = songidHint ?: throw IllegalStateException("no songid for word-by-word")
                val decoded = fetchWordByWord(title, artist, album, duration, songid, mid).getOrThrow()
                toAppLyricsFormat(decoded).takeIf { it.isNotBlank() && hasConvertedWordTimings(it) }
                    ?: throw IllegalStateException("word-by-word has no timings (mid=$mid)")
            }
        }
        // LRC flies alongside the word-timed race; only awaited on a full miss.
        val lrcDeferred = async {
            runCatching { fetchLrc(mid).getOrThrow() }
        }
        qrcDeferred.await().onSuccess { qrc ->
            Timber.tag(TAG).d("query='%s' stage=QRC chars=%d", query, qrc.length)
            lyricsMem[mid] = qrc
            writeLyricsDisk(mid, qrc)
            return@coroutineScope qrc
        }.onFailure {
            Timber.tag(TAG).d("query='%s' QRC miss: %s", query, it.message)
        }
        wbwDeferred.await().onSuccess { wbw ->
            Timber.tag(TAG).d("query='%s' stage=word-by-word chars=%d", query, wbw.length)
            lyricsMem[mid] = wbw
            writeLyricsDisk(mid, wbw)
            return@coroutineScope wbw
        }.onFailure {
            Timber.tag(TAG).d("query='%s' word-by-word miss: %s", query, it.message)
        }
        // LRC last resort (already in flight - see above).
        val lrc = lrcDeferred.await().getOrThrow()
        Timber.tag(TAG).d("query='%s' stage=LRC chars=%d", query, lrc.length)
        lyricsMem[mid] = lrc
        writeLyricsDisk(mid, lrc)
        lrc
    }

    /** Raw-payload check: QRC word timings look like (startMs,durMs). */
    private fun hasWordTimings(text: String): Boolean =
        text.contains(Regex("""\(\d+,\d+\)"""))

    /**
     * Converted-output check: [toAppLyricsFormat] emits
     * `[lineStart]<wordStart>word...<lineEnd>`, so a genuinely word-timed
     * line carries 2+ angle-bracket stamps. (The old code ran the raw
     * paren detector over converted output, which can never match - every
     * QRC lookup failed closed on it.)
     */
    private fun hasConvertedWordTimings(text: String): Boolean =
        text.lineSequence().any { line -> line.count { it == '<' } >= 2 }

    // ---- Word-by-word via musichallSong.PlayLyricInfo (different endpoint,
    // same verified DES cascade — confirmed against QQMusicDecoder,
    // qqmusic_api and folia-major, which all use this one cipher) ----

    private suspend fun fetchWordByWord(
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        songid: Long,
        mid: String,
    ): Result<String> = runCatching {
        val body = buildJsonObject {
            putJsonObject("comm") {
                put("ct", 11)
                put("cv", "1003006")
                put("v", "1003006")
                put("os_ver", "15")
                put("tmeAppID", "qqmusiclight")
                put("nettype", "NETWORK_WIFI")
                put("udid", "0")
                put("uid", "0")
            }
            // Envelope MUST be req_1 (same as search): any other key is
            // ignored by QQ and the response comes back bodyless.
            putJsonObject("req_1") {
                put("module", "music.musichallSong.PlayLyricInfo")
                put("method", "GetPlayLyricInfo")
                putJsonObject("param") {
                    put("albumName", b64(album.orEmpty()))
                    put("crypt", 1)
                    put("ct", 19)
                    put("cv", 2111)
                    put("interval", duration)
                    put("lrc_t", 0)
                    put("qrc", 1)
                    put("qrc_t", 0)
                    put("roma", 1)
                    put("roma_t", 0)
                    put("singerName", b64(artist))
                    // Reference clients send songId (lowercase d); songMid
                    // rides along as fallback — both name this same song.
                    put("songId", songid)
                    put("songMid", mid)
                    put("songName", b64(title))
                    put("trans", 1)
                    put("trans_t", 0)
                    put("type", 1)
                }
            }
        }
        val response = httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
            header("Referer", "https://y.qq.com/")
            header("Cookie", "tmeLoginType=-1")
            header("User-Agent", BROWSER_UA)
            // TextContent, NOT setBody(String): the shared client has
            // ContentNegotiation installed, which would serialize a raw
            // String body as a quoted JSON string ("{...}") that QQ cannot
            // parse. TextContent bypasses conversion - bytes go as-is.
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw IllegalStateException("Bad PlayLyricInfo response")
        // The envelope key echoes the request (req_1).
        val reqObj = root["req_1"] as? JsonObject
            ?: root["request"] as? JsonObject
            ?: root["req"] as? JsonObject
        val data = reqObj?.get("data") as? JsonObject
            ?: throw IllegalStateException("No PlayLyricInfo data (codes root=${root["code"]})")
        // Word-timed blob lives in "qrc" when present, else "lyric".
        // (Length guard: "qrc" can also be a 0/1 flag, never the blob.)
        val hex = data["qrc"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() && it.length > 16 }
            ?: data["lyric"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No word-by-word lyric payload")
        // crypt=1 means DES-cascade hex; if the payload already carries
        // timestamps (crypt=0 style), use it directly.
        if (!hasWordTimings(hex) && !hex.contains('[')) {
            return@runCatching decryptQrcPayload(hex)
        }
        hex
    }.onFailure { e ->
        Timber.tag(TAG).d("word-by-word fetch failed: %s", e.message)
    }

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** Shared QRC cipher: hex -> triple-DES cascade -> inflate -> UTF-8. */
    private fun decryptQrcPayload(hex: String): String =
        String(inflate(decryptQrcPayloadBytes(hexToBytes(hex))), Charsets.UTF_8)

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
            // Envelope key MUST be req_1 (bare "req" is ignored by QQ and
            // yields an empty body — every working client uses req_1).
            // The response echoes the same key.
            putJsonObject("req_1") {
                put("module", "music.search.SearchCgiService")
                put("method", "DoSearchForQQMusicDesktop")
                putJsonObject("param") {
                    put("remoteplace", "txt.yqq.center")
                    put("query", query)
                    // Wide net in a single round-trip: the loose pass needs
                    // lower-ranked versions (live/unreleased) to even see them.
                    put("num_per_page", 20)
                    put("page_num", 1)
                    put("search_type", 0)
                }
            }
        }
        val response = httpClient.post("https://u.y.qq.com/cgi-bin/musicu.fcg") {
            header("Referer", "https://y.qq.com/")
            header("User-Agent", BROWSER_UA)
            // TextContent, NOT setBody(String): the shared client has
            // ContentNegotiation installed, which would serialize a raw
            // String body as a quoted JSON string ("{...}") that QQ cannot
            // parse - this silently killed every search. TextContent bypasses
            // conversion - bytes go as-is.
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: return@runCatching emptyList()
        // Envelope key echoes the request (req_1); lenient fallbacks for
        // older/newer shapes so a key drift never means zero candidates.
        val reqObj = root["req_1"] as? JsonObject
            ?: root["req"] as? JsonObject
            ?: root["request"] as? JsonObject
            ?: root["music.search.SearchCgiService"] as? JsonObject
        if (reqObj == null) {
            Timber.tag(TAG).d(
                "search '%s': no envelope (code=%s keys=%s)",
                query,
                root["code"]?.toString(),
                root.keys.joinToString(","),
            )
            return@runCatching emptyList()
        }
        val songObj = reqObj["data"]?.jsonObject
            ?.get("body")?.jsonObject
            ?.get("song")?.jsonObject
        val list = songObj?.get("list")?.jsonArray
            ?: reqObj["data"]?.jsonObject
                ?.get("body")?.jsonObject
                ?.get("songlist")?.jsonObject
                ?.get("list")?.jsonArray
            ?: return@runCatching emptyList()
        list.mapNotNull { (it as? JsonObject)?.toCandidate() }
    }.onFailure { e ->
        Timber.tag(TAG).w("search failed for '%s': %s", query, e.message)
    }.getOrDefault(emptyList())

    private fun JsonObject.toCandidate(): Candidate? {
        val singers = (this["singer"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.qqField("name", "title") }
            .orEmpty()
        // album is an object {name,title,mid,id} in musicu.fcg responses
        // (flat "albumname" only in the legacy client_search_cp shape).
        val albumName = (this["album"] as? JsonObject)?.qqField("name", "title")
            ?: qqField("albumname", "album")
        return Candidate(
            raw = this,
            mid = qqField("mid", "songmid"),
            songid = qqLong("id", "songid"),
            title = qqField("title", "name", "songname"),
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
        minScore: Int,
    ): Pair<Candidate, Int>? {
        val keep = keepTagsOf("$title $artist")
        val normTitle = normalizeForMatch(title, keep)
        val normAlbum = album?.let { normalizeForMatch(it, keep) }.orEmpty()
        val trackArtists = splitArtists(artist).map { normalizeForMatch(it, keep) }.filter { it.isNotBlank() }
        val wantSecs = duration.toLong()

        var best: Candidate? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            val normCTitle = c.title?.let { t -> normalizeForMatch(t, keepTagsOf(t)) }.orEmpty()
            val normCSingers = c.singers.map { normalizeForMatch(it, keepTagsOf(it)) }

            // Veto 1: the track artist must match any listed singer. A wrong
            // artist with the right title is worse than no lyrics at all.
            val artistScore = when {
                trackArtists.isEmpty() -> 0
                trackArtists.any { ta -> normCSingers.any { it == ta } } -> 100
                trackArtists.any { ta -> normCSingers.any { s -> s.contains(ta) || ta.contains(s) } } -> 50
                else -> continue
            }

            // Veto 2: the title must match at least by substring. Same artist
            // + same album + similar length with a different title is a
            // different song - previously accepted, now rejected.
            val titleScore = when {
                normCTitle.isEmpty() || normTitle.isEmpty() -> 0
                normCTitle == normTitle -> 80
                normCTitle.contains(normTitle) || normTitle.contains(normCTitle) -> 40
                else -> continue
            }

            var score = artistScore + titleScore
            // Official-album preference: reuploads carry a different album name.
            val normCAlbum = c.album?.let { a -> normalizeForMatch(a, keepTagsOf(a)) }.orEmpty()
            score += when {
                normAlbum.isEmpty() || normCAlbum.isEmpty() -> 0
                normCAlbum == normAlbum -> 60
                normCAlbum.contains(normAlbum) || normAlbum.contains(normCAlbum) -> 25
                else -> 0
            }
            // Duration is purely advisory: live/unreleased versions run long
            // or short, so length differences score down but never veto an
            // otherwise exact match.
            score += c.interval?.let { d ->
                val diff = if (d >= wantSecs) d - wantSecs else wantSecs - d
                when {
                    diff <= 3L -> 100
                    diff <= 8L -> 50
                    diff <= 15L -> 10
                    else -> 0
                }
            } ?: 0

            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        val winner = best ?: return null
        return if (bestScore >= minScore) winner to bestScore else null
    }

    // ---- Normalisation: lowercase, strip tag segments unless the track itself has them ----

    private val tagWords = listOf(
        "explicit", "live", "remaster", "acoustic", "remix", "cover",
        "karaoke", "instrumental", "sped up", "slowed", "reverb",
    )

    /**
     * Words that never distinguish versions (upload metadata, not music):
     * always dropped from bracketed segments on both sides, so
     * "Song (Official Video)" matches QQ's "Song" exactly.
     */
    private val fillerWords = setOf(
        "official", "video", "audio", "lyrics", "lyric", "mv",
        "visualizer", "visualiser", "hq", "hd", "topic",
    )
    private val tagSingleWords = tagWords.flatMap { it.split(' ') }.toSet()

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
        // current track carries that tag itself. Pure-filler segments
        // ("(Official Video)", "[Audio]") go on both sides unconditionally -
        // they are upload metadata, never part of the song identity.
        s = Regex("""[\(\[][^)\]]*[\)\]]""").replace(s) { m ->
            val inner = m.value.lowercase()
            val innerWords = inner.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            if (innerWords.isNotEmpty() && innerWords.all { it in fillerWords || it in tagSingleWords }) {
                " "
            } else if (tagWords.any { it in inner && it !in keep }) {
                ""
            } else {
                " ${m.value} "
            }
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
        // NOTE: the "x" collab separator requires whitespace on BOTH sides,
        // so a leading/trailing solo x ("X Ambassadors", "Model X") is never
        // mistaken for a separator and mangled into a wrong artist name.
        artist.split(Regex("""\s*(?:&|/|,|、|\bfeat\.?|\bft\.?|\bwith\b|\band\b)\s*|\s+x\s+""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(artist.trim()) }

    // ---- LRC fallback (new endpoint) ----

    private suspend fun fetchLrc(mid: String): Result<String> = runCatching {
        val response = httpClient.get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg") {
            header("Referer", "https://y.qq.com/")
            header("User-Agent", BROWSER_UA)
            parameter("songmid", mid)
            parameter("format", "json")
            parameter("nobase64", 1)
            parameter("g_tk", 5381)
            parameter("loginUin", 0)
            parameter("hostUin", 0)
            parameter("inCharset", "utf8")
            parameter("outCharset", "utf-8")
            parameter("notice", 0)
            parameter("platform", "yqq")
            parameter("needNewCode", 0)
        }
        val root = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
            ?: throw IllegalStateException("Bad LRC response for mid=$mid")
        val raw = root["lyric"]?.jsonPrimitive?.contentOrNull
            ?.let(::unescapeEntities)?.trim()
            ?.takeIf { it.isNotBlank() }
        // Plain text with timestamps wins; otherwise the payload may still
        // be base64 despite nobase64=1 — decode and re-check.
        val lyric = when {
            raw != null && raw.contains('[') -> raw
            raw != null -> runCatching {
                String(Base64.getDecoder().decode(raw), Charsets.UTF_8).trim()
            }.getOrNull()?.takeIf { it.contains('[') }
            else -> null
        } ?: throw IllegalStateException("No LRC lyric for mid=$mid")
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
            header("User-Agent", BROWSER_UA)
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

        // Normally hex; some songs serve base64 — try it before giving up
        // (hexToBytes silently drops non-hex chars, which would otherwise
        // feed garbage into DES and fail closed in inflate).
        val encrypted = hexToBytes(hex).takeIf { it.isNotEmpty() }
            ?: runCatching { Base64.getDecoder().decode(hex.trim()) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Unparseable QRC payload for songid=$songid")
        val decrypted = decryptQrcPayloadBytes(encrypted)
        // Normally zlib-wrapped; fall back to raw deflate.
        val inflated = runCatching { inflate(decrypted, nowrap = false) }
            .getOrElse { inflate(decrypted, nowrap = true) }
        String(inflated, Charsets.UTF_8)
    }.onFailure { e ->
        Timber.tag(TAG).w("QRC download/decrypt failed for songid=%d: %s", songid, e.message)
    }

    /** Triple-DES cascade over raw bytes (shared by both word-timed stages). */
    private fun decryptQrcPayloadBytes(encrypted: ByteArray): ByteArray {
        val step1 = QQMusicDes.crypt(encrypted, KEY1, decrypt = true)
        val step2 = QQMusicDes.crypt(step1, KEY2, decrypt = false)
        return QQMusicDes.crypt(step2, KEY3, decrypt = true)
    }

    /** Converts either QRC XML (word-timed) or plain LRC into the app's LyricsUtils rich-sync format. */
    private fun toAppLyricsFormat(decoded: String): String {
        val qrcBody = lyricContentRegex.find(decoded)?.groupValues?.get(1) ?: decoded
        if (!hasWordTimings(qrcBody)) {
            // Not word-timed QRC (e.g. plain LRC) — pass through as-is.
            return qrcBody
        }

        // QRC ships line breaks as &#10; entities inside the XML attribute;
        // without this the whole blob is one "line" and only it converts.
        val out = StringBuilder()
        qrcBody.replace("&#13;", "").replace("&#10;", "\n").lines().forEach { rawLine ->
            val m = qrcLineRegex.find(rawLine.trim()) ?: return@forEach
            val lineStartMs = m.groupValues[1].toLongOrNull() ?: return@forEach
            val wordsPart = m.groupValues[3]
            val words = qrcWordRegex.findAll(wordsPart).toList()
            if (words.isEmpty()) {
                // Timed line without word stamps: keep it as a plain synced
                // line instead of dropping it.
                val plain = wordsPart.trim()
                if (plain.isNotEmpty()) {
                    out.append('[').append(formatTime(lineStartMs)).append(']').append(plain).append('\n')
                }
                return@forEach
            }

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

    private fun inflate(data: ByteArray, nowrap: Boolean = false): ByteArray {
        val inflater = Inflater(nowrap)
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
