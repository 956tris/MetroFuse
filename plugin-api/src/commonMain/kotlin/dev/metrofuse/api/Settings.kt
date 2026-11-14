package dev.metrofuse.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SettingType { BOOLEAN, STRING, NUMBER, ENUM, OBJECT }

@Serializable
data class SettingDefinition(
    val key: String,
    val title: String,
    val type: SettingType,
    val default: JsonElement,
    val options: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
)

interface PluginSettings {
    val values: StateFlow<Map<String, JsonElement>>
    suspend fun set(key: String, value: JsonElement)
}
