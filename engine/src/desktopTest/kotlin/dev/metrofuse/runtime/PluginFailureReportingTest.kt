package dev.metrofuse.runtime

import dev.metrofuse.api.JsonService
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.ResolutionMode
import dev.metrofuse.api.ServiceContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginFailureReportingTest {
    @Test
    fun failedServiceIsReportedWhileFallbackStillSucceeds() =
        runBlocking {
            val reports = mutableListOf<Triple<String, String, Throwable>>()
            val registry = Registry { owner, operation, error -> reports.add(Triple(owner, operation, error)) }
            val failure = IllegalStateException("token=private")
            val name = "fixture.service/v1"
            registry.contract("host", ServiceContract(name, setOf(ResolutionMode.FIRST_SUCCESS), ResolutionMode.FIRST_SUCCESS))
            registry.service(
                "a.failed",
                name,
                object : JsonService {
                    override suspend fun call(request: JsonObject): JsonObject = throw failure
                },
            )
            registry.service(
                "b.healthy",
                name,
                object : JsonService {
                    override suspend fun call(request: JsonObject) = JsonObject(mapOf("success" to JsonPrimitive(true)))
                },
            )
            assertEquals(JsonPrimitive(true), registry.invoke(name, JsonObject(emptyMap()))["success"])
            assertEquals("a.failed", reports.single().first)
            assertEquals("service.$name", reports.single().second)
            assertEquals(
                failure.javaClass.name,
                reports
                    .single()
                    .third.javaClass.name,
            )
            assertEquals(failure.message, reports.single().third.message)
        }

    @Test
    fun reportingIgnoresCancellationAndLimitsRepeatedFailuresWithoutRetainingSecrets() {
        var now = 0L
        val reports = PluginFailureReports { now }
        val sent = mutableListOf<PluginFailure>()
        val failure = PluginFailure("fixture", "1.0.0", "javascript", "home.load", IllegalStateException("private-session"))
        reports.report(failure.copy(error = CancellationException()), sent::add)
        reports.report(failure, sent::add)
        reports.report(failure, sent::add)
        assertEquals(1, sent.size)
        now = 60_000_000_000L
        reports.report(failure, sent::add)
        assertEquals(2, sent.size)
        val diagnostic = PluginOperationException(failure)
        assertFalse(diagnostic.message.orEmpty().contains("private-session"))
        assertNull(diagnostic.cause)
        assertTrue(diagnostic.stackTrace.contentEquals(failure.error.stackTrace))
        reports.report(failure.copy(operation = "ui.render")) { throw IllegalStateException("Reporter unavailable") }
    }

    @Test
    fun approvedAudioFailuresAreGroupedByCodeAndUnknownCodesAreRedacted() {
        val sent = mutableListOf<PluginFailure>()
        val reports = PluginFailureReports { 0L }
        val failure = PluginFailure("fixture", "1.0.0", "kotlin", "audio.resolve", PluginProviderException("TIMEOUT", true, 1, 250))
        reports.report(failure, sent::add)
        reports.report(failure, sent::add)
        reports.report(failure.copy(error = PluginProviderException("LOGIN_REQUIRED", false, 1, 10)), sent::add)
        val unknown = failure.copy(error = PluginProviderException("PRIVATE_API_KEY", false, 2, 15))
        reports.report(unknown, sent::add)
        assertEquals(listOf("TIMEOUT", "LOGIN_REQUIRED", "PROVIDER_ERROR"), sent.map { it.audioDiagnostic?.code })
        assertEquals(PluginAudioDiagnostic("TIMEOUT", true, 1, 250), sent.first().audioDiagnostic)
        assertFalse(PluginOperationException(unknown).message.orEmpty().contains("PRIVATE_API_KEY"))
    }

    @Test
    fun realJavaScriptActivationFailureIncludesInstalledPluginMetadata() =
        runBlocking {
            val storage = Files.createTempDirectory("metrofuse-reporting-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val reports = mutableListOf<PluginFailure>()
            val runtime = Runtime(storage, "desktop", scope, EngineTestHost(), onPluginFailure = reports::add)
            try {
                runtime.initialize()
                val manifest = Manifest("fixture.failure", "Fixture", "1.2.3", "Tests", runtime = "javascript", script = "index.js")
                val bytes =
                    ByteArrayOutputStream().use { output ->
                        ZipOutputStream(output).use { zip ->
                            for ((name, content) in mapOf(
                                "manifest.json" to codec.encodeToString(manifest),
                                "index.js" to "globalThis.plugin = { async init() { throw new Error('private-session'); } };",
                            )) {
                                zip.putNextEntry(ZipEntry(name))
                                zip.write(content.toByteArray())
                                zip.closeEntry()
                            }
                        }
                        output.toByteArray()
                    }
                runtime.install(ByteArrayInputStream(bytes))
                runtime.enable(manifest.id)
                assertEquals(
                    "Failed",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                val failure = reports.single()
                assertEquals(manifest.id, failure.owner)
                assertEquals(manifest.version, failure.version)
                assertEquals("javascript", failure.runtime)
                assertEquals("lifecycle.activate", failure.operation)
                assertFalse(PluginOperationException(failure).message.orEmpty().contains("private-session"))
            } finally {
                runtime.close()
                scope.cancel()
                storage.deleteRecursively()
            }
        }
}
