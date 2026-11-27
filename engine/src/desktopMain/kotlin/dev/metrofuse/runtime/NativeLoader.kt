package dev.metrofuse.runtime

import dev.metrofuse.api.NativePlugin
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
    val staging = File(directory, "loading-${UUID.randomUUID()}.jar")
    var artifact: File? = null
    var loader: NativePluginClassLoader? = null

    fun cleanup() {
        try {
            loader?.close()
        } finally {
            if (staging.exists() && !staging.delete()) staging.deleteOnExit()
            artifact?.let { file ->
                file.setWritable(true)
                if (file.exists() && !file.delete()) file.deleteOnExit()
            }
            if (!directory.delete()) directory.deleteOnExit()
        }
    }
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            staging.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
        val resolvedArtifact = File(directory, "$sha256.jar")
        Files.move(staging.toPath(), resolvedArtifact.toPath(), StandardCopyOption.REPLACE_EXISTING)
        artifact = resolvedArtifact
        require(resolvedArtifact.setReadOnly()) { "Cannot protect the plugin activation copy" }
        val activeLoader = NativePluginClassLoader(resolvedArtifact)
        loader = activeLoader
        val type = activeLoader.loadClass(entryClass)
        require(type.classLoader === activeLoader) { "Entry class was supplied by host; external loading required" }
        require(NativePlugin::class.java.isAssignableFrom(type)) { "Entrypoint does not implement the host NativePlugin API" }
        return NativeHandle(
            type.getDeclaredConstructor().newInstance() as NativePlugin,
            readAsset = { name ->
                PackageStore.validatePath(name)
                require(name.startsWith("assets/")) { "Native resource access is limited to packaged assets" }
                ZipFile(resolvedArtifact).use { zip ->
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
