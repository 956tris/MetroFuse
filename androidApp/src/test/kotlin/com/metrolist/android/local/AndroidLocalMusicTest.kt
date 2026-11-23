package com.metrolist.android.local

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.local.LocalLibraryImporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32, 33])
class AndroidLocalMusicTest {
    @Test
    fun modernMediaStoreGrammarRejectsLegacyLocalizedCollation() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                validateMediaStoreQuery(
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE),
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE LOCALIZED ASC",
                )
            }
        assertEquals("Invalid token LOCALIZED", failure.message)
    }

    @Test
    fun scanRequiresThePlatformAudioPermissionAndPreservesLibraryOnRevocation() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val provider = LocalAudioFixtureProvider(File(context.cacheDir, "fixture.mp3").apply { writeBytes(ByteArray(2048)) })
            provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                val scanner = AndroidLocalMusicScanner(context, LocalLibraryImporter(database))
                val expected =
                    if (Build.VERSION.SDK_INT >=
                        33
                    ) {
                        Manifest.permission.READ_MEDIA_AUDIO
                    } else {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }
                val unrelated =
                    if (Build.VERSION.SDK_INT >=
                        33
                    ) {
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    } else {
                        Manifest.permission.READ_MEDIA_AUDIO
                    }
                assertEquals(expected, scanner.permission)
                shadowOf(context).denyPermissions(expected)
                shadowOf(context).grantPermissions(unrelated)
                assertEquals(LocalMusicScanStatus.PERMISSION_REQUIRED, scanner.scan().status)
                assertEquals(0, provider.queries)
                shadowOf(context).grantPermissions(expected)
                val first = scanner.scan()
                assertEquals(LocalMusicScanStatus.COMPLETE, first.status)
                assertEquals(1, first.found)
                assertEquals(1, first.imported)
                val uri = "content://media/external/audio/media/17"
                val stored = database.dao().getSongById(uri)
                assertNotNull(stored)
                assertEquals("Fixture", stored!!.song.title)
                assertEquals(123, stored.song.duration)
                assertEquals(2026, stored.song.year)
                assertTrue(stored.song.isLocal)
                assertTrue(stored.song.albumId!!.startsWith("LAL:scanned:"))
                assertEquals("Artist", stored.artists.single().name)
                assertEquals(0, scanner.scan().imported)
                provider.denied = true
                assertEquals(LocalMusicScanStatus.PERMISSION_REQUIRED, scanner.scan().status)
                assertNotNull(database.dao().getSongById(uri))
                assertEquals(0, provider.deletions)
            } finally {
                database.close()
            }
        }

    @Test
    fun rescanReplacesSharedAlbumCoversWithEachFilesArtworkAndRefreshesEditedFiles() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.cacheDir, "artwork-fixture.mp3").apply { writeBytes(ByteArray(2048)) }
            val provider = LocalAudioFixtureProvider(file, listOf(17L, 18L))
            provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                val scanner = AndroidLocalMusicScanner(context, LocalLibraryImporter(database))
                shadowOf(context).grantPermissions(scanner.permission)
                val uris = listOf("content://media/external/audio/media/17", "content://media/external/audio/media/18")
                uris.forEach { uri ->
                    database.dao().upsert(
                        SongEntity(
                            uri,
                            "Old title",
                            thumbnailUrl = "content://media/external/audio/albumart/29",
                            liked = true,
                            isLocal = true,
                        ),
                    )
                }
                assertEquals(LocalMusicScanStatus.COMPLETE, scanner.scan().status)
                val songs = uris.map { database.dao().getSongById(it)!! }
                val artwork = songs.map { it.thumbnailUrl!! }
                assertNotEquals(songs[0].title, songs[1].title)
                assertEquals(songs[0].song.albumId, songs[1].song.albumId)
                assertNotEquals(artwork[0], artwork[1])
                assertEquals(uris, artwork.map { Uri.parse(it).getQueryParameter("uri") })
                assertTrue(songs.all { it.song.liked })

                provider.modifiedSeconds++
                assertEquals(0, scanner.scan().imported)
                uris.forEachIndexed { index, uri ->
                    val updated = database.dao().getSongById(uri)!!
                    assertNotEquals(artwork[index], updated.thumbnailUrl)
                    assertEquals(uri, Uri.parse(updated.thumbnailUrl).getQueryParameter("uri"))
                    assertTrue(updated.song.liked)
                }
            } finally {
                database.close()
                file.delete()
            }
        }

    @Test
    @Config(sdk = [33])
    fun localUriResolutionUsesReadableDescriptorsAndDoesNotFetchRemoteSources() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.cacheDir, "readable.mp3").apply { writeBytes(ByteArray(2048)) }
            val provider = LocalAudioFixtureProvider(file)
            provider.attachInfo(context, ProviderInfo().apply { authority = "local-audio-fixture" })
            ShadowContentResolver.registerProviderInternal("local-audio-fixture", provider)
            val uri = "content://local-audio-fixture/audio/17"
            val content = localPlaybackResource(context, uri, 123L)
            assertNotNull(content)
            assertEquals(uri, content!!.url)
            assertEquals("audio/mpeg", content.mimeType)
            assertEquals(2048L, content.contentLengthBytes)
            assertEquals(123L, content.durationSeconds)
            assertFalse(content.requireBoundedRange)
            assertFalse(content.allowPlaybackTrackingFallback)
            assertNotNull(localPlaybackResource(context, Uri.fromFile(file).toString(), 123L))
            assertNull(localPlaybackResource(context, Uri.fromFile(File(context.cacheDir, "missing.mp3")).toString(), null))
            assertNull(localPlaybackResource(context, "https://fixture.invalid/audio.mp3", 123L))
            assertEquals(1, provider.opens)
            provider.denied = true
            assertNull(localPlaybackResource(context, uri, 123L))
            assertTrue(file.isFile)
            assertEquals(0, provider.deletions)
        }

    @Test
    @Config(sdk = [33])
    fun scanSkipsZeroByteZeroDurationPendingTrashedAndNonAudioRows() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val file = File(context.cacheDir, "filter-fixture.mp3").apply { writeBytes(ByteArray(2048)) }
            val validRow =
                mapOf<String, Any?>(
                    MediaStore.Audio.Media._ID to 17L,
                    MediaStore.Audio.Media.TITLE to "Valid Song",
                    MediaStore.Audio.Media.DISPLAY_NAME to "Valid.mp3",
                    MediaStore.Audio.Media.DURATION to 123000L,
                    MediaStore.Audio.Media.ARTIST to "Artist",
                    MediaStore.Audio.Media.ALBUM to "Album",
                    MediaStore.Audio.Media.YEAR to 2026,
                    MediaStore.Audio.Media.TRACK to 1,
                    MediaStore.Audio.Media.DATE_MODIFIED to 1700000000L,
                    MediaStore.Audio.Media.SIZE to 2048L,
                    MediaStore.Audio.Media.MIME_TYPE to "audio/mpeg",
                    MediaStore.Audio.Media.IS_PENDING to 0,
                    MediaStore.Audio.Media.IS_TRASHED to 0,
                )
            val provider =
                LocalAudioFixtureProvider(
                    file = file,
                    customRows =
                        listOf(
                            validRow,
                            validRow + mapOf(MediaStore.Audio.Media._ID to 18L, MediaStore.Audio.Media.SIZE to 0L),
                            validRow + mapOf(MediaStore.Audio.Media._ID to 19L, MediaStore.Audio.Media.DURATION to 0L),
                            validRow + mapOf(MediaStore.Audio.Media._ID to 20L, MediaStore.Audio.Media.IS_PENDING to 1),
                            validRow + mapOf(MediaStore.Audio.Media._ID to 21L, MediaStore.Audio.Media.IS_TRASHED to 1),
                            validRow + mapOf(MediaStore.Audio.Media._ID to 22L, MediaStore.Audio.Media.MIME_TYPE to "video/mp4"),
                        ),
                )
            provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", provider)
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                val scanner = AndroidLocalMusicScanner(context, LocalLibraryImporter(database))
                shadowOf(context).grantPermissions(scanner.permission)
                val result = scanner.scan()
                assertEquals(LocalMusicScanStatus.COMPLETE, result.status)
                assertEquals(1, result.found)
                assertEquals(1, result.imported)
                assertNotNull(database.dao().getSongById("content://media/external/audio/media/17"))
                listOf(18L, 19L, 20L, 21L, 22L).forEach { skippedId ->
                    assertNull(database.dao().getSongById("content://media/external/audio/media/$skippedId"))
                }
            } finally {
                database.close()
                file.delete()
            }
        }

    private class LocalAudioFixtureProvider(
        private val file: File,
        private val trackIds: List<Long> = listOf(17L),
        private val customRows: List<Map<String, Any?>>? = null,
    ) : ContentProvider() {
        var denied = false
        var queries = 0
        var opens = 0
        var deletions = 0
        var modifiedSeconds = 1700000000L

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (denied) throw SecurityException("Fixture permission revoked")
            queries++
            assertEquals("${MediaStore.Audio.Media.IS_MUSIC} != 0", selection)
            val values =
                mapOf<String, Any?>(
                    MediaStore.Audio.Media._ID to 17,
                    MediaStore.Audio.Media.TITLE to MediaStore.UNKNOWN_STRING,
                    MediaStore.Audio.Media.DISPLAY_NAME to "Fixture.mp3",
                    MediaStore.Audio.Media.DURATION to 123000L,
                    MediaStore.Audio.Media.ARTIST to "Artist",
                    MediaStore.Audio.Media.ALBUM to "Album",
                    MediaStore.Audio.Media.YEAR to 2026,
                    MediaStore.Audio.Media.TRACK to 2003,
                    MediaStore.Audio.Media.ALBUM_ID to 29,
                    MediaStore.Audio.Media.DATE_MODIFIED to modifiedSeconds,
                )
            val columns = requireNotNull(projection)
            validateMediaStoreQuery(columns, selection, selectionArgs, sortOrder)
            return MatrixCursor(columns).apply {
                if (customRows != null) {
                    customRows.forEach { rowValues ->
                        addRow(columns.map { rowValues[it] }.toTypedArray())
                    }
                } else {
                    trackIds.forEach { id ->
                        val displayName = if (id == 17L) "Fixture.mp3" else "Other-$id.mp3"
                        val trackValues = values + mapOf(MediaStore.Audio.Media._ID to id, MediaStore.Audio.Media.DISPLAY_NAME to displayName)
                        addRow(columns.map { trackValues[it] }.toTypedArray())
                    }
                }
            }
        }

        override fun openAssetFile(
            uri: Uri,
            mode: String,
        ): AssetFileDescriptor {
            if (denied) throw SecurityException("Fixture permission revoked")
            opens++
            return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, file.length())
        }

        override fun getType(uri: Uri): String = "audio/mpeg"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            deletions++
            return 1
        }
    }
}

private fun validateMediaStoreQuery(
    projection: Array<out String>,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
) {
    val columns = (projection.toList() + MediaStore.Audio.Media.IS_MUSIC).distinct()
    SQLiteDatabase.create(null).use { database ->
        database.execSQL("CREATE TABLE audio (${columns.joinToString { "$it TEXT" }})")
        SQLiteQueryBuilder()
            .apply {
                tables = "audio"
                projectionMap = columns.associateWith { it }
                setStrict(true)
                setStrictColumns(true)
                setStrictGrammar(true)
            }.query(database, projection, selection, selectionArgs, null, null, sortOrder)
            .use { }
    }
}
