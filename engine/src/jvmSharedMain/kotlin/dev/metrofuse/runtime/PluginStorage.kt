package dev.metrofuse.runtime

import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.PluginStorage
import dev.metrofuse.api.SettingDefinition
import dev.metrofuse.api.SettingType
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

class PersistentPluginSettings(private val file: File, definitions: List<SettingDefinition>) : PluginSettings {
    private val schema = definitions.associateBy { it.key }
    private val gate = Mutex()
    override val values = MutableStateFlow(schema.mapValues { it.value.default })

    init {
        require(schema.size == definitions.size) { "Duplicate setting key" }
        definitions.forEach { validate(it, it.default) }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        gate.withLock {
            if (file.exists()) {
                require(file.length() <= 128 * 1024) { "Plugin settings too large" }
                val stored = codec.parseToJsonElement(file.readText()).jsonObject
                values.value = schema.mapValues { (key, definition) ->
                    stored[key]?.also { validate(definition, it) } ?: definition.default
                }
            }
        }
    }

    override suspend fun set(key: String, value: JsonElement) = withContext(Dispatchers.IO) {
        gate.withLock {
            validate(requireNotNull(schema[key]) { "Unknown plugin setting $key" }, value)
            val next = values.value + (key to value)
            writePluginJson(file, JsonObject(next), 128 * 1024)
            values.value = next
        }
    }

    companion object {
        fun validate(definition: SettingDefinition, value: JsonElement) {
            require(definition.key.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,79}"))) { "Invalid setting key" }
            val primitive = value as? JsonPrimitive
            when (definition.type) {
                SettingType.BOOLEAN -> require(primitive?.booleanOrNull != null && !primitive.isString)
                SettingType.STRING -> require(primitive?.isString == true && primitive.content.length <= 16384)
                SettingType.NUMBER -> {
                    val numberPrimitive = requireNotNull(primitive) { "Expected number" }
                    val number = requireNotNull(numberPrimitive.doubleOrNull) { "Expected number" }
                    val minimum = definition.minimum
                    val maximum = definition.maximum
                    require(!numberPrimitive.isString && number.isFinite())
                    require(minimum == null || number >= minimum)
                    require(maximum == null || number <= maximum)
                }
                SettingType.ENUM -> require(primitive?.isString == true && primitive.content in definition.options)
                SettingType.OBJECT -> require(value is JsonObject && value.toString().length <= 65536)
            }
        }
    }
}

class PersistentPluginStorage(private val file: File) : PluginStorage {
    private val gate = Mutex()
    private var loaded: Map<String, JsonElement>? = null

    private fun contents(): Map<String, JsonElement> = loaded ?: run {
        require(!file.exists() || file.length() <= 1024 * 1024) { "Plugin storage too large" }
        val result = if (file.exists()) codec.parseToJsonElement(file.readText()).jsonObject else emptyMap()
        loaded = result
        result
    }

    override suspend fun get(key: String): JsonElement? = withContext(Dispatchers.IO) {
        gate.withLock { validateKey(key); contents()[key] }
    }

    override suspend fun set(key: String, value: JsonElement?) = withContext(Dispatchers.IO) {
        gate.withLock {
            validateKey(key)
            val next = if (value == null) contents() - key else contents() + (key to value)
            require(next.size <= 1024) { "Plugin storage key limit" }
            writePluginJson(file, JsonObject(next), 1024 * 1024)
            loaded = next
        }
    }

    private fun validateKey(key: String) { require(key.length in 1..120 && key.none { it.code < 32 }) { "Invalid storage key" } }
}

internal fun writePluginJson(file: File, value: JsonObject, maxBytes: Int) {
    val bytes = value.toString().toByteArray()
    require(bytes.size <= maxBytes) { "Plugin storage size limit" }
    require(file.parentFile.mkdirs() || file.parentFile.isDirectory)
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.outputStream().use { it.write(bytes); it.fd.sync() }
    try {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
