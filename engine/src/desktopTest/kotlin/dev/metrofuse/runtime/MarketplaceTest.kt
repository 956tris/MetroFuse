package dev.metrofuse.runtime

import dev.metrofuse.api.API_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketplaceTest {
    private val digest = "0123456789abcdef".repeat(4)
    private fun artifact(file: String) = MarketplaceArtifact("https://example.org/releases/$file", digest, file)
    private fun entry(downloads: Map<String, MarketplaceArtifact>, api: String = API_VERSION) = MarketplaceEntry(
        "example.provider", "Example", "Fixture", "Fixture provider", "1.2.0", api, downloads,
    )

    @Test
    fun selectsDistinctNativeArtifactsAndOneUniversalJavascriptArtifact() {
        val native = entry(mapOf("desktop" to artifact("provider.jar"), "android" to artifact("provider.apk")))
        assertEquals("provider.jar", native.artifact("desktop")?.fileName)
        assertEquals("provider.apk", native.artifact("android")?.fileName)
        val javascript = entry(mapOf("universal" to artifact("provider.mfp")))
        assertEquals(javascript.artifact("desktop"), javascript.artifact("android"))
        assertNull(entry(mapOf("desktop" to artifact("provider.jar"))).artifact("android"))
        assertNull(entry(mapOf("universal" to artifact("provider.jar"))).artifact("android"))
        assertNull(entry(mapOf("universal" to artifact("provider.mfp")), "99.0.0").artifact("desktop"))
        val desktop = entry(mapOf("desktop" to artifact("provider.mfp"), "universal" to artifact("shared.mfp")))
        assertEquals("shared.mfp", desktop.artifact("desktop")?.fileName)
        assertNull(desktop.artifact("unknown"))
    }

    @Test
    fun exposesPlatformAvailabilitySeparatelyFromCompatibility() {
        val newer = entry(mapOf("android" to artifact("provider.apk")), "99.0.0")
        assertTrue(newer.supports("android"))
        assertFalse(newer.supports("desktop"))
        assertNull(newer.artifact("android"))
    }

    @Test
    fun supportsExplicitCategoriesAndSafeSourceLinks() {
        val listing = entry(mapOf("universal" to artifact("provider.mfp"))).copy(
            capabilities = listOf("audio"),
            sourceRepository = "https://github.com/example/provider",
        )
        assertEquals(listOf("audio"), listing.displayCategories)
        assertEquals(listOf("Music"), listing.copy(categories = listOf("Music", "Music", "")).displayCategories)
        assertEquals("https://github.com/example/provider", listing.sourceUrl)
        assertNull(listing.copy(sourceRepository = "javascript:alert(1)").sourceUrl)
    }

    @Test
    fun rejectsUnsafeRegistryArtifactsBeforeDownload() {
        val safe = artifact("provider.mfp")
        for (invalid in listOf(
            safe.copy(sha256 = "missing"),
            safe.copy(url = "http://example.org/plugin.mfp"),
            safe.copy(fileName = "../plugin.mfp"),
            safe.copy(fileName = "CON.mfp"),
        )) {
            assertFailsWith<IllegalArgumentException> { entry(mapOf("universal" to invalid)).artifact("desktop") }
        }
    }
}
