package com.metrolist.shared.plugins

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginArtworkSourceTest {
    @Test
    fun preservesRemoteAndOfflineLibraryCovers() {
        val sources =
            listOf(
                "https://i.scdn.co/image/cover",
                "file:/C:/Music/Album%20cover.jpg",
                "file:///storage/emulated/0/Music/cover.jpg",
                "content://media/external/audio/albumart/42",
                "metrofuse-local-artwork://audio?uri=content%3A%2F%2Fmedia%2Fexternal%2Faudio%2Fmedia%2F42&modified=123",
            )
        sources.forEach { assertEquals(it, pluginArtworkSource(it)) }
        val local = File(System.getProperty("java.io.tmpdir"), "Album cover.jpg")
        assertEquals(local.toURI().toString(), pluginArtworkSource(local.absolutePath))
    }

    @Test
    fun rejectsMalformedAndUnsupportedArtworkSources() {
        listOf(
            "",
            "assets/cover.jpg",
            "https://user:secret@example.com/cover.jpg",
            "https://example.com:8443/cover.jpg",
            "http://example.com/cover.jpg",
            "javascript:alert(1)",
            "file://remote-server/share/cover.jpg",
            "file:relative.jpg",
            "content:///cover.jpg",
            "metrofuse-local-artwork://other?uri=content%3A%2F%2Fmedia",
            "https://example.com/cover\n.jpg",
        ).forEach { assertNull(pluginArtworkSource(it), it) }
    }
}
