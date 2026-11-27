package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.local.LocalLibraryImporter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.OsUtil
import com.metrolist.shared.utils.XdgUtils
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.ShlObj
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.math.abs

internal data class DesktopLocalMusicState(
    val folders: List<File> = emptyList(),
    val scanning: Boolean = false,
    val foundTracks: Int? = null,
    val failed: Boolean = false,
)

internal object DesktopLocalMusicLibrary {
    private val mutableState = MutableStateFlow(DesktopLocalMusicState())
    val state = mutableState.asStateFlow()
    private var database: MusicDatabase? = null
    private var importer: LocalLibraryImporter? = null
    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    private var scheduledScanJob: Job? = null

    fun initialize(
        database: MusicDatabase,
        scope: CoroutineScope,
        autoScan: Boolean = false,
    ) {
        if (importer != null) return
        this.database = database
        importer = LocalLibraryImporter(database)
        this.scope = scope
        try {
            val saved = Settings.shared.desktopMusicFolders
            val folders =
                if (saved.isBlank()) {
                    listOfNotNull(
                        defaultMusicFolder(),
                    )
                } else {
                    Json.decodeFromString<List<String>>(saved).map(::File)
                }
            saveFolders(folders, previousEncoded = saved)
            if (autoScan) scan(incremental = true)
        } catch (error: Exception) {
            mutableState.update { it.copy(failed = true) }
            AppLogger.warning("LocalMusic", "Could not initialize music folders", error)
        }
    }

    fun scheduleStartupScan(delayMs: Long = 5_000L) {
        val scope = scope ?: return
        if (state.value.folders.isEmpty() || scanJob?.isActive == true || scheduledScanJob?.isActive == true) return
        scheduledScanJob =
            scope.launch(Dispatchers.IO) {
                delay(delayMs)
                scan(incremental = true)
            }
    }

    fun addFolder(folder: File) {
        require(folder.isDirectory && folder.canRead()) { "Music directory is unavailable" }
        saveFolders(state.value.folders + folder)
        scan(incremental = true)
    }

    fun removeFolder(folder: File) {
        saveFolders(state.value.folders.filterNot { it == folder })
    }

    private fun saveFolders(
        folders: List<File>,
        previousEncoded: String? = null,
    ) {
        val normalized = folders.map { it.canonicalFile }.distinct()
        val encoded = Json.encodeToString(normalized.map { it.path })
        if (encoded != previousEncoded) {
            Settings.shared.desktopMusicFolders = encoded
        }
        mutableState.update { it.copy(folders = normalized) }
    }

    fun scan() = scan(incremental = true)

    fun scan(incremental: Boolean) {
        if (scanJob?.isActive == true) return
        val importer = importer ?: return
        val scope = scope ?: return
        scheduledScanJob?.cancel()
        mutableState.update { it.copy(scanning = true, failed = false, foundTracks = null) }
        scanJob =
            scope.launch(Dispatchers.IO) {
                try {
                    val existingModified =
                        if (incremental) {
                            database
                                ?.dao()
                                ?.allSongs()
                                ?.first()
                                .orEmpty()
                                .asSequence()
                                .filter { it.song.isLocal }
                                .mapNotNull { entry ->
                                    val modified = entry.song.dateModified?.toInstant(TimeZone.UTC)?.toEpochMilliseconds()
                                        ?: return@mapNotNull null
                                    entry.id to modified
                                }.toMap()
                        } else {
                            emptyMap()
                        }
                    val result =
                        DesktopLocalMusicScanner().scan(state.value.folders) { uri, lastModified ->
                            val saved = existingModified[uri] ?: return@scan false
                            lastModified > 0L && abs(saved - lastModified) < 2_000L
                        }
                    if (result.tracks.isNotEmpty()) {
                        result.tracks.chunked(200).forEach { importer.importTracks(it) }
                    }
                    mutableState.update {
                        it.copy(
                            foundTracks = result.tracks.size + result.skippedUnchangedCount,
                            failed = result.unreadablePaths.isNotEmpty(),
                        )
                    }
                    if (result.unreadablePaths.isNotEmpty()) {
                        AppLogger.warning(
                            "LocalMusic",
                            "Skipped ${result.unreadablePaths.size} unreadable paths",
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutableState.update { it.copy(failed = true) }
                    AppLogger.warning("LocalMusic", "Music scan failed", error)
                } finally {
                    mutableState.update { it.copy(scanning = false) }
                }
            }
    }
}

private fun defaultMusicFolder(): File? =
    when {
        OsUtil.isWindows -> {
            File(Shell32Util.getFolderPath(ShlObj.CSIDL_MYMUSIC))
        }

        OsUtil.isMac -> {
            File(System.getProperty("user.home"), "Music")
        }

        else -> {
            val home = System.getProperty("user.home")
            val direct = System.getenv("XDG_MUSIC_DIR")?.takeIf(String::isNotBlank)
            val configured =
                File(XdgUtils.getXdgConfigHome(), "user-dirs.dirs").takeIf(File::isFile)?.useLines { lines ->
                    lines
                        .firstOrNull { it.trimStart().startsWith("XDG_MUSIC_DIR=") }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.removeSurrounding("\"")
                }
            (direct ?: configured)?.replace("\$HOME", home)?.let(::File)
        }
    }

internal suspend fun desktopLocalPlaybackResource(
    id: String,
    song: SongItem?,
): PlaybackResource? =
    withContext(Dispatchers.IO) {
        if (!id.startsWith("file:", ignoreCase = true) || song?.isLocal == false) return@withContext null
        val file =
            try {
                File(URI(id)).canonicalFile
            } catch (_: Exception) {
                return@withContext null
            }
        if (!file.isFile || !Files.isReadable(file.toPath())) return@withContext null
        PlaybackResource(
            videoId = id,
            url = file.absolutePath,
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            contentLengthBytes = file.length().takeIf { it > 0 },
            mimeType = Files.probeContentType(file.toPath()),
            durationSeconds = song?.duration,
            clientName = "local",
            profileId = "local",
            requireBoundedRange = false,
        )
    }
