package dev.metrofuse.runtime

enum class LifecyclePhase { DISCOVERED, INSTALLED, LOADING, LOADED, ENABLED, ACTIVE, DISABLED, FAILED, UNLOADED }
data class LifecycleTransition(val phase: LifecyclePhase, val atMillis: Long = System.currentTimeMillis())
data class ResourceSnapshot(val sessions: Int, val eventSubscriptions: Int, val uiContributions: Int, val metadataSources: Int, val audioSources: Int)
