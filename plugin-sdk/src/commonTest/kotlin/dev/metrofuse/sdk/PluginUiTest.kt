package dev.metrofuse.sdk

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginUiTest {
    @Test
    fun playbackUnitsAndBounds() {
        assertEquals(0.5f, playbackFraction(90_000, 180_000))
        assertEquals(0f, playbackFraction(100, 0))
        assertEquals(0f, playbackFraction(-1, 180_000))
        assertEquals(1f, playbackFraction(200_000, 180_000))
    }
}
