package com.metrolist.desktop.storage

import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineQueueAvailabilityTest {
    @Test
    fun localQueueAvailabilityRequiresANonemptyReadableFile() =
        runBlocking {
            val databasePath = Files.createTempFile("offline-queue", ".db")
            Files.delete(databasePath)
            val audio = Files.createTempFile("offline-audio", ".mp3")
            val empty = Files.createTempFile("offline-empty", ".mp3")
            val missing = Files.createTempFile("offline-missing", ".mp3")
            Files.delete(missing)
            Files.write(audio, ByteArray(2048))
            val db = getDatabaseBuilder(databasePath.toFile()).build()
            try {
                for (file in listOf(audio, empty, missing)) {
                    db.dao().insert(SongEntity(file.toUri().toString(), file.fileName.toString(), isLocal = true))
                }
                val availability = desktopSongOfflineAvailability(db, downloads = { emptyList() }, cachedIds = { emptySet() })
                assertEquals(setOf(audio.toUri().toString()), availability.songIds())
            } finally {
                db.close()
                Files.deleteIfExists(databasePath)
                Files.deleteIfExists(audio)
                Files.deleteIfExists(empty)
            }
        }
}
