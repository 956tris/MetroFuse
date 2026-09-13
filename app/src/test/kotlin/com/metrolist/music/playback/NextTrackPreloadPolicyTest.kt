/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTrackPreloadPolicyTest {
    @Test
    fun `count zero disables the window`() {
        assertTrue(select(count = 0).isEmpty())
    }

    @Test
    fun `count one selects only the immediate next item`() {
        assertEquals(listOf(1 to "B"), select(count = 1))
    }

    @Test
    fun `count three selects three next items in order`() {
        assertEquals(listOf(1 to "B", 2 to "C", 3 to "D"), select(count = 3))
    }

    @Test
    fun `count ten is honored when the queue contains enough items`() {
        val items = (0..11).map(Int::toString)
        assertEquals(10, select(items = items, count = 10).size)
    }

    @Test
    fun `end of queue without repeat produces no targets`() {
        assertTrue(select(currentIndex = 4, count = 3).isEmpty())
    }

    @Test
    fun `repeat one never preloads copies of the current item`() {
        assertTrue(select(count = 3, repeatMode = Player.REPEAT_MODE_ONE).isEmpty())
    }

    @Test
    fun `repeat all wraps without selecting the current item twice`() {
        assertEquals(
            listOf(1 to "A", 2 to "B", 3 to "C"),
            select(currentIndex = 4, count = 3, repeatMode = Player.REPEAT_MODE_ALL),
        )
    }

    @Test
    fun `shuffle follows the timeline supplied next order`() {
        val shuffledNext = mapOf(0 to 3, 3 to 1, 1 to 4, 4 to 2, 2 to C.INDEX_UNSET)
        assertEquals(
            listOf(1 to "D", 2 to "B", 3 to "E"),
            select(count = 3, next = { shuffledNext[it] ?: C.INDEX_UNSET }),
        )
    }

    @Test
    fun `queue change cancels stale work and starts new priority order`() {
        val diff = preloadWindowDiff(
            active = setOf("B", "C", "D"),
            completed = emptySet(),
            wantedInPriorityOrder = listOf("G", "H", "I"),
        )
        assertEquals(setOf("B", "C", "D"), diff.cancel)
        assertEquals(listOf("G", "H", "I"), diff.start)
    }

    @Test
    fun `completed cache hit does not start redundant work`() {
        val diff = preloadWindowDiff(
            active = emptySet(),
            completed = setOf("B"),
            wantedInPriorityOrder = listOf("B", "C"),
        )
        assertEquals(listOf("C"), diff.start)
    }

    @Test
    fun `cache hit has no missing range`() {
        assertNull(NextTrackPreloadPolicy.missingRange(cachedPrefixBytes = 1_000, targetBytes = 1_000))
    }

    @Test
    fun `partial cache starts at first missing byte`() {
        assertEquals(
            PreloadByteRange(position = 400, length = 600),
            NextTrackPreloadPolicy.missingRange(cachedPrefixBytes = 400, targetBytes = 1_000),
        )
    }

    @Test
    fun `cache miss starts at zero`() {
        assertEquals(
            PreloadByteRange(position = 0, length = 1_000),
            NextTrackPreloadPolicy.missingRange(cachedPrefixBytes = 0, targetBytes = 1_000),
        )
    }

    @Test
    fun `nearer tracks receive a larger adaptive byte target`() {
        val first = NextTrackPreloadPolicy.targetBytes(1, bitrate = 320_000, contentLength = 0)
        val second = NextTrackPreloadPolicy.targetBytes(2, bitrate = 320_000, contentLength = 0)
        val third = NextTrackPreloadPolicy.targetBytes(3, bitrate = 320_000, contentLength = 0)
        assertTrue(first > second)
        assertTrue(second > third)
    }

    @Test
    fun `quality invalidation makes completed items eligible again`() {
        val before = preloadWindowDiff(emptySet(), setOf("B"), listOf("B"))
        val after = preloadWindowDiff(emptySet(), emptySet(), listOf("B"))
        assertTrue(before.start.isEmpty())
        assertEquals(listOf("B"), after.start)
    }

    @Test
    fun `provider failure is contained as best effort`() = runBlocking {
        val failure = IllegalStateException("provider failed")
        val result = runBestEffortPreload { throw failure }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is never swallowed`() = runBlocking {
        runBestEffortPreload { throw CancellationException("left window") }
    }

    private fun select(
        items: List<String> = listOf("A", "B", "C", "D", "E"),
        currentIndex: Int = 0,
        count: Int,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
        next: ((Int) -> Int)? = null,
    ): List<Pair<Int, String>> =
        NextTrackPreloadPolicy.selectNext(
            currentIndex = currentIndex,
            windowCount = items.size,
            preloadCount = count,
            repeatMode = repeatMode,
            itemAt = items::get,
            idOf = { it },
            nextIndex = next ?: { index ->
                when {
                    index + 1 < items.size -> index + 1
                    repeatMode == Player.REPEAT_MODE_ALL -> 0
                    else -> C.INDEX_UNSET
                }
            },
        )
}
