package dev.metrofuse.runtime

import dev.metrofuse.api.Manifest

data class SemVer(val major: Long, val minor: Long, val patch: Long) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        fun parse(value: String): SemVer {
            require(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) { "Version must be stable SemVer x.y.z: $value" }
            val components = value.split('.').map { requireNotNull(it.toLongOrNull()) { "Version component too large" } }
            return SemVer(components[0], components[1], components[2])
        }
    }
}

/** Stable topological ordering. User preference breaks ties only among dependency-ready nodes. */
object PluginOrdering {
    /** Dependency values are minimum stable versions, with optional >= prefix. */
    fun validateDependencies(manifests: Map<String, Manifest>, requested: Collection<String> = manifests.keys) {
        val pending = ArrayDeque(requested)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val manifest = requireNotNull(manifests[id]) { "Missing dependency: $id" }
            manifest.dependencies.forEach { (dependencyId, minimum) ->
                val dependency = requireNotNull(manifests[dependencyId]) { "${manifest.id} requires missing dependency $dependencyId" }
                require(SemVer.parse(dependency.version) >= SemVer.parse(minimum.removePrefix(">="))) { "${manifest.id} requires $dependencyId >= $minimum; installed ${dependency.version}" }
                pending.addLast(dependencyId)
            }
        }
        resolve(visited, manifests.mapValues { it.value.dependencies.keys })
    }

    fun resolve(owners: Collection<String>, dependencies: Map<String, Set<String>>, preferred: List<String> = emptyList(), global: List<String> = emptyList(), priorities: Map<String, Int> = emptyMap(), localPriorities: Map<String, Int> = emptyMap()): List<String> {
        val nodes = owners.toMutableSet()
        fun include(id: String) { dependencies[id].orEmpty().forEach { if (nodes.add(it)) include(it) } }
        owners.forEach(::include)
        val result = mutableListOf<String>()
        val remaining = nodes.toMutableSet()
        fun rank(list: List<String>, id: String) = list.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        val comparator = compareBy<String> { if (it == "builtin" || it.startsWith("builtin.")) 1 else 0 }
            .thenBy { rank(preferred, it) }.thenByDescending { localPriorities[it] ?: 0 }
            .thenBy { rank(global, it) }.thenByDescending { priorities[it] ?: 0 }.thenBy { it }
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { dependencies[it].orEmpty().none { dep -> dep in remaining } }
            require(ready.isNotEmpty()) { "Dependency cycle: ${remaining.sorted().joinToString(" -> ")}" }
            val next = ready.minWith(comparator)
            result += next; remaining -= next
        }
        return result.filter { it in owners }
    }
}
