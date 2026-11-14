package dev.metrofuse.runtime

import java.io.File

data class PluginHomeSource(
    val owner: String,
    val title: String,
    val icon: String?,
)

fun pluginHomeSources(plugins: List<PluginStatus>): List<PluginHomeSource> =
    plugins.filter { it.state == "Enabled" && "home" in it.manifest.capabilities }.map { plugin ->
        val manifest = plugin.manifest
        val icon =
            (manifest.home?.icon ?: manifest.icon)?.let { path ->
                File(plugin.directory, path)
                    .takeIf {
                        it.isFile && it.length() in 1..4 * 1024 * 1024 &&
                            it.canonicalPath.startsWith(plugin.directory.canonicalPath + File.separator)
                    }?.absolutePath
            }
        PluginHomeSource(manifest.id, manifest.home?.title ?: manifest.name, icon)
    }
