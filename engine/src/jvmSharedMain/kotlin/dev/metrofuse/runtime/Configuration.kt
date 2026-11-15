package dev.metrofuse.runtime

import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.MetadataPolicy
import dev.metrofuse.api.ResolutionMode
import kotlinx.serialization.Serializable

internal fun migrateAudioPolicy(policy: AudioPolicy): AudioPolicy =
    if (policy == AudioPolicy(timeoutMillis = 1000)) AudioPolicy() else policy

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
