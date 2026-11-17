package dev.metrofuse.runtime

import dev.metrofuse.api.HomeSourceMetadata
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.PluginLoginState
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiSourceMetadataTest {
    private fun plugin(
        id: String,
        state: String = "Enabled",
    ) = PluginStatus(
        Manifest(id, "Catalog", "1.0.0", "tests", runtime = "javascript", home = HomeSourceMetadata(title = "Account source")),
        File("."),
        state = state,
        phase = if (state == "Enabled") LifecyclePhase.ACTIVE else LifecyclePhase.DISABLED,
    )

    @Test
    fun onlyTheSelectedActiveSourcesAccountIsExposed() {
        val sources = listOf(plugin("test.first"), plugin("test.second"))
        val accounts =
            mapOf(
                "test.first" to PluginAccountState(PluginLoginState(true, "First account")),
                "test.second" to PluginAccountState(PluginLoginState(true, "Second account")),
            )
        val data = uiSourceMetadata("test.first", sources, accounts)
        assertEquals("test.first", data.getValue("id").jsonPrimitive.content)
        assertEquals("Account source", data.getValue("title").jsonPrimitive.content)
        assertEquals("First account", data.getValue("accountName").jsonPrimitive.content)
        assertFalse(data.getValue("offline").jsonPrimitive.boolean)
        assertTrue(data.getValue("loggedIn").jsonPrimitive.boolean)
    }

    @Test
    fun disabledOrMissingSourcesCannotExposeStaleAccounts() {
        val accounts = mapOf("test.first" to PluginAccountState(PluginLoginState(true, "Previous account")))
        for (selected in listOf("test.first", "", "test.missing")) {
            val data = uiSourceMetadata(selected, listOf(plugin("test.first", "Disabled")), accounts)
            assertEquals("", data.getValue("id").jsonPrimitive.content)
            assertTrue(data.getValue("offline").jsonPrimitive.boolean)
            assertFalse(data.getValue("loggedIn").jsonPrimitive.boolean)
            assertNull(data["accountName"])
            assertNull(data["title"])
        }
    }
}
