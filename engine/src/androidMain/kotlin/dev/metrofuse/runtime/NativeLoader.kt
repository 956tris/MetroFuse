package dev.metrofuse.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import dalvik.system.DexClassLoader
import dev.metrofuse.api.NativePlugin
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

private const val MAX_NATIVE_ASSET_BYTES = 4L * 1024 * 1024
private val STALE_CACHE_DIR_PATTERN = Regex("metrofuse-[a-f0-9-]{36}")
private val SHARED_PACKAGE_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "android.",
        "dalvik.",
        "kotlin.",
        "kotlinx.coroutines.",
        "kotlinx.serialization.",
        "androidx.compose.",
        "org.jetbrains.compose.resources.",
        "dev.metrofuse.api.",
        "dev.metrofuse.sdk.",
    )

@Volatile
private var nativeApplication: Context? = null

private class NativePluginDexClassLoader(
    path: String,
    cache: String,
    libraries: String?,
) : DexClassLoader(path, cache, libraries, NativePlugin::class.java.classLoader) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(this) {
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

@Synchronized
fun initializeNativePlatform(context: Context) {
    val firstInit = nativeApplication == null
    val appContext = context.applicationContext
    nativeApplication = appContext
    if (firstInit) {
        val cache = appContext.codeCacheDir
        Thread(
            {
                runCatching {
                    val parentCanonical = cache.canonicalFile
                    cache
                        .listFiles()
                        .orEmpty()
                        .filter { it.isDirectory && STALE_CACHE_DIR_PATTERN.matches(it.name) }
                        .forEach { directory ->
                            if (directory.canonicalFile.parentFile == parentCanonical) directory.deleteRecursively()
                        }
                }
            },
            "metrofuse-native-cache-cleanup",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

internal fun readNativeArtifactAsset(
    artifact: File,
    name: String,
    platformResources: Resources? = null,
): ByteArray {
    PackageStore.validatePath(name)
    require(name.startsWith("assets/"))
    if (platformResources != null) {
        runCatching {
            return platformResources.assets.open(name.removePrefix("assets/")).use { it.readBytes() }
        }
    }
    ZipFile(artifact).use { zip ->
        val entry = zip.getEntry(name) ?: throw IllegalArgumentException("Missing native asset: $name")
        require(!entry.isDirectory && entry.size in 0..MAX_NATIVE_ASSET_BYTES) { "Invalid or oversized native asset: $name" }
        zip.getInputStream(entry).use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_NATIVE_ASSET_BYTES) { "Oversized native asset: $name" }
            return bytes
        }
    }
}

/** APK stays in private storage. Android 14+ requires executable files to be read-only. */
actual fun loadNative(
    path: String,
    entryClass: String,
): NativeHandle {
    val application = requireNotNull(nativeApplication) { "Initialize the native Android platform before loading plugins" }
    val source = File(path)
    require(source.isFile && source.extension == "apk") { "Expected native plugin APK" }
    val directory = File(application.codeCacheDir, "metrofuse-${UUID.randomUUID()}")
    require(directory.mkdir())
    val artifact = File(directory, "plugin.apk")
    var resourceLoadStarted = false
    try {
        artifact.outputStream().use { output ->
            require(artifact.setReadOnly()) { "Cannot mark Android plugin executable read-only" }
            source.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
        val packageManager = application.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageArchiveInfo(artifact.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(artifact.absolutePath, 0)
            }
        val resources =
            info?.applicationInfo?.let { applicationInfo ->
                require(applicationInfo.minSdkVersion <= Build.VERSION.SDK_INT) {
                    "Plugin requires Android API ${applicationInfo.minSdkVersion}; this device provides ${Build.VERSION.SDK_INT}"
                }
                applicationInfo.sourceDir = artifact.absolutePath
                applicationInfo.publicSourceDir = artifact.absolutePath
                resourceLoadStarted = true
                runCatching { packageManager.getResourcesForApplication(applicationInfo) }.getOrNull()
            }
        val libraryDirectory = File(directory, "lib")
        ZipFile(artifact).use { zip ->
            val abi =
                Build.SUPPORTED_ABIS.firstOrNull { supported ->
                    zip.entries().asSequence().any { it.name.startsWith("lib/$supported/") && it.name.endsWith(".so") }
                }
            require(abi != null || zip.entries().asSequence().none { it.name.startsWith("lib/") && it.name.endsWith(".so") }) {
                "Plugin native libraries do not support this device ABI"
            }
            if (abi != null) {
                require(libraryDirectory.mkdir())
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
                    .forEach { entry ->
                        val name = entry.name.removePrefix("lib/$abi/")
                        PackageStore.validatePath(name)
                        require(!name.contains('/'))
                        val library = File(libraryDirectory, name)
                        library.outputStream().use { output ->
                            require(library.setReadOnly())
                            zip.getInputStream(entry).use { it.copyTo(output) }
                        }
                    }
            }
        }
        val loader =
            NativePluginDexClassLoader(
                artifact.absolutePath,
                directory.absolutePath,
                libraryDirectory.takeIf { it.isDirectory }?.absolutePath,
            )
        val type = loader.loadClass(entryClass)
        require(type.classLoader === loader) { "Entry class was supplied by host" }
        require(NativePlugin::class.java.isAssignableFrom(type)) { "Entrypoint does not implement the host NativePlugin API" }
        return NativeHandle(
            type.getDeclaredConstructor().newInstance() as NativePlugin,
            readAsset = { name -> readNativeArtifactAsset(artifact, name, resources) },
            platformResources = resources,
            requiresRestartForReplacement = libraryDirectory.isDirectory,
            // Android may refresh its cached resources after this handle closes.
            // Keep the APK available until the next process's cache cleanup.
            close = {},
        )
    } catch (error: Throwable) {
        if (!resourceLoadStarted) directory.deleteRecursively()
        throw error
    }
}
