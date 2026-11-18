package dev.metrofuse.runtime

import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.HomeSourceMetadata
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.PluginLoginState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiSourceMetadataTest {
    private fun plugin(
        id: String,
        state: String = "Enabled",
    ) = PluginStatus(
        Manifest(
            id, "Catalog", "1.0.0", "tests", runtime = "javascript",
            home = HomeSourceMetadata(title = "Account source"), capabilities = listOf("home"),
        ),
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

    @Test
    fun customShellCanSelectOnlyAvailableSourcesAndRestoreOffline() = runBlocking {
        var selected = "test.disabled"
        val host = object : HostServices by EngineTestHost(), UiFrontendSelectionHost {
            override val settings = object : HostSettings {
                override fun get(key: String) = selected.takeIf { key == "music.frontend" }

                override fun set(key: String, value: String?) = error("Selection must use the host bridge")
            }

            override suspend fun selectUiFrontend(owner: String) {
                selected = owner
            }
        }
        val directory = Files.createTempDirectory("metrofuse-ui-frontends").toFile()
        val runtime = Runtime(directory, "desktop", this, host)
        try {
            runtime.registerUiCatalogServices()
            runtime.plugins.value = listOf(plugin("test.active"), plugin("test.disabled", "Disabled"))
            suspend fun request(owner: String? = null) = runtime.registry.invoke(
                UI_FRONTENDS_SERVICE,
                buildJsonObject { owner?.let { put("select", it) } },
            )
            val snapshot = request()
            assertEquals("", snapshot.getValue("selected").jsonPrimitive.content)
            assertEquals("test.active", snapshot.getValue("sources").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> { request("test.disabled") }
            assertEquals("test.disabled", selected)
            assertEquals("test.active", request("test.active").getValue("selected").jsonPrimitive.content)
            assertEquals("", request("").getValue("selected").jsonPrimitive.content)
            assertEquals("", selected)
        } finally {
            runtime.close()
            directory.deleteRecursively()
        }
    }
}
