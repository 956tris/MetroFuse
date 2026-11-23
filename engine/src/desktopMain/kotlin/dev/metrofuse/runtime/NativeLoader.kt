package dev.metrofuse.runtime

import dev.metrofuse.api.NativePlugin
import java.io.File
import java.net.URLClassLoader
import java.util.UUID
import java.util.zip.ZipFile

private const val MAX_NATIVE_ASSET_BYTES = 4L * 1024 * 1024
private val SHARED_PACKAGE_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "sun.",
        "jdk.",
        "kotlin.",
        "kotlinx.coroutines.",
        "kotlinx.serialization.",
        "androidx.compose.",
        "org.jetbrains.compose.resources.",
        "dev.metrofuse.api.",
        "dev.metrofuse.sdk.",
    )

private class NativePluginClassLoader(
    artifact: File,
) : URLClassLoader(arrayOf(artifact.toURI().toURL()), NativePlugin::class.java.classLoader) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val shared = SHARED_PACKAGE_PREFIXES.any(name::startsWith)
            val type =
                findLoadedClass(name)
                    ?: if (shared) {
                        super.loadClass(name, false)
                    } else {
                        try {
                            findClass(name)
                        } catch (_: ClassNotFoundException) {
                            super.loadClass(name, false)
                        }
                    }
            if (resolve) resolveClass(type)
            type
        }
}

actual fun loadNative(
    path: String,
    entryClass: String,
): NativeHandle {
    val source = File(path)
    require(source.isFile && source.extension == "jar") { "Expected native plugin JAR" }
    val directory = File(System.getProperty("java.io.tmpdir"), "metrofuse-native-${UUID.randomUUID()}")
    require(directory.mkdir())
    val artifact = File(directory, "${PackageStore.sha256(source)}.jar")
    var loader: NativePluginClassLoader? = null

    fun cleanup() {
        try {
            loader?.close()
        } finally {
            artifact.setWritable(true)
            if (artifact.exists() && !artifact.delete()) artifact.deleteOnExit()
            if (!directory.delete()) directory.deleteOnExit()
        }
    }
    try {
        source.copyTo(artifact)
        require(artifact.setReadOnly()) { "Cannot protect the plugin activation copy" }
        val activeLoader = NativePluginClassLoader(artifact)
        loader = activeLoader
        val type = activeLoader.loadClass(entryClass)
        require(type.classLoader === activeLoader) { "Entry class was supplied by host; external loading required" }
        require(NativePlugin::class.java.isAssignableFrom(type)) { "Entrypoint does not implement the host NativePlugin API" }
        return NativeHandle(
            type.getDeclaredConstructor().newInstance() as NativePlugin,
            readAsset = { name ->
                PackageStore.validatePath(name)
                require(name.startsWith("assets/")) { "Native resource access is limited to packaged assets" }
                ZipFile(artifact).use { zip ->
                    val entry = zip.getEntry(name) ?: throw IllegalArgumentException("Missing native asset: $name")
                    require(!entry.isDirectory && entry.size in 0..MAX_NATIVE_ASSET_BYTES) {
                        "Invalid or oversized native asset: $name"
                    }
                    zip.getInputStream(entry).use { input ->
                        val bytes = input.readBytes()
                        require(bytes.size <= MAX_NATIVE_ASSET_BYTES) { "Oversized native asset: $name" }
                        bytes
                    }
                }
            },
            close = ::cleanup,
        )
    } catch (error: Throwable) {
        try {
            cleanup()
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
}
