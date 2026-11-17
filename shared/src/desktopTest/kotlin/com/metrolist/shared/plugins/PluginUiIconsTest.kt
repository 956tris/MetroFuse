package com.metrolist.shared.plugins

import kotlin.test.Test
import kotlin.test.assertEquals

class PluginUiIconsTest {
    @Test
    fun unknownNamesAndPathStringsUseTheSafeFallback() {
        listOf("", "../play", "PLAY", "M0 0h1000000v1000000Z", "https://example.com/icon.svg").forEach {
            assertEquals("more", pluginUiIconName(it))
        }
        listOf("home", "library", "search", "play", "pause", "next", "previous", "shuffle", "repeat", "queue").forEach {
            assertEquals(it, pluginUiIconName(it))
        }
    }
}
