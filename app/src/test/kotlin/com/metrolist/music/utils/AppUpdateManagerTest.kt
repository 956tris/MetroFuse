package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun calculatesKnownDownloadProgress() {
        val progress =
            UpdateDownloadProgress(
                downloadedBytes = 5L * 1024L * 1024L,
                totalBytes = 10L * 1024L * 1024L,
                bytesPerSecond = 1024L * 1024L,
                elapsedSeconds = 5L,
            )

        assertEquals(0.5f, progress.fraction!!, 0.001f)
        assertEquals(5L, progress.remainingSeconds)
    }

    @Test
    fun handlesUnknownDownloadSize() {
        val progress =
            UpdateDownloadProgress(
                downloadedBytes = 1024L,
                totalBytes = -1L,
                bytesPerSecond = 512L,
                elapsedSeconds = 2L,
            )

        assertNull(progress.fraction)
        assertNull(progress.remainingSeconds)
    }

    @Test
    fun formatsDownloadMetrics() {
        assertEquals("1.5 MB", AppUpdateManager.formatBytes(1536L * 1024L))
        assertEquals("02:05", AppUpdateManager.formatDuration(125L))
        assertEquals("--:--", AppUpdateManager.formatDuration(null))
    }
}
