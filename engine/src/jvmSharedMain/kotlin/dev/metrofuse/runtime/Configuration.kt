package dev.metrofuse.runtime

import kotlinx.serialization.Serializable
import dev.metrofuse.api.MetadataPolicy
import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.ResolutionMode

@Serializable data class Configuration(
    val enabled: Set<String> = emptySet(),
    val priorities: Map<String, Int> = emptyMap(),
    val selections: Map<String, String> = emptyMap(),
    val base: Set<String> = emptySet(),
    val globalOrder: List<String> = emptyList(),
    val pointOrder: Map<String, List<String>> = emptyMap(),
    val pointPriorities: Map<String, Map<String, Int>> = emptyMap(),
    val metadataPolicy: MetadataPolicy = MetadataPolicy(),
    val audioPolicy: AudioPolicy = AudioPolicy(),
    val serviceModes: Map<String, ResolutionMode> = emptyMap(),
)
