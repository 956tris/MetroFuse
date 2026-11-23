import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Reusable cacheable split-dexing APK & JAR packager for MetroFuse native plugins.
 * Apply from a plugin's build.gradle.kts and configure via extra properties or `packageMetroFusePlugin(...)`.
 */
fun findMetroFuseAndroidSdk(root: File): File {
    val properties = Properties()
    File(root, "local.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
    val path =
        properties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Set sdk.dir in local.properties or ANDROID_HOME")
    return File(path)
}

fun findLatestBuildToolsDir(sdk: File, preferred: String = "37.0.0"): File {
    val preferredDir = File(sdk, "build-tools/$preferred")
    if (preferredDir.isDirectory && File(preferredDir, "lib/d8.jar").isFile) return preferredDir
    return File(sdk, "build-tools")
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, "lib/d8.jar").isFile }
        .maxByOrNull { it.name }
        ?: error("No Android build-tools with lib/d8.jar found under ${sdk.absolutePath}")
}

fun findAndroidPlatformJar(sdk: File, preferred: String = "android-37.0"): File {
    val preferredJar = File(sdk, "platforms/$preferred/android.jar")
    if (preferredJar.isFile) return preferredJar
    return File(sdk, "platforms")
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, "android.jar").isFile }
        .maxByOrNull { it.name }
        ?.let { File(it, "android.jar") }
        ?: error("No android.jar found under ${sdk.absolutePath}/platforms")
}

fun writeStoredOrDeflatedApk(
    outputApk: File,
    resourcesApk: File,
    dexFiles: List<File>,
    zipalignExe: File?,
) {
    outputApk.parentFile.mkdirs()
    val rawApk = File(outputApk.parentFile, "${outputApk.name}.unaligned")
    ZipOutputStream(rawApk.outputStream().buffered()).use { archive ->
        ZipFile(resourcesApk).use { resources ->
            val names = mutableSetOf<String>()
            resources
                .entries()
                .asSequence()
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val name = entry.name.replace('\\', '/')
                    require(names.add(name.lowercase())) { "Resource archive has duplicate normalized paths: $name" }
                    val bytes = resources.getInputStream(entry).use { it.readBytes() }
                    val outEntry = ZipEntry(name)
                    if (name == "resources.arsc") {
                        // Android 11+ (API 30+) requires resources.arsc to be UNCOMPRESSED (STORED) and 4-byte aligned.
                        val crc = CRC32().apply { update(bytes) }
                        outEntry.method = ZipEntry.STORED
                        outEntry.size = bytes.size.toLong()
                        outEntry.compressedSize = bytes.size.toLong()
                        outEntry.crc = crc.value
                    }
                    archive.putNextEntry(outEntry)
                    archive.write(bytes)
                    archive.closeEntry()
                }
        }
        dexFiles.sortedBy(File::getName).forEach { dex ->
            archive.putNextEntry(ZipEntry(dex.name))
            dex.inputStream().use { it.copyTo(archive) }
            archive.closeEntry()
        }
    }
    if (zipalignExe != null && zipalignExe.isFile) {
        val process = ProcessBuilder(zipalignExe.absolutePath, "-f", "-p", "4", rawApk.absolutePath, outputApk.absolutePath).inheritIO().start()
        val code = process.waitFor()
        rawApk.delete()
        require(code == 0) { "zipalign failed with exit code $code" }
    } else {
        if (outputApk.exists()) outputApk.delete()
        check(rawApk.renameTo(outputApk)) { "Failed to move ${rawApk.name} to ${outputApk.name}" }
    }
}

extra["findMetroFuseAndroidSdk"] = ::findMetroFuseAndroidSdk
extra["findLatestBuildToolsDir"] = ::findLatestBuildToolsDir
extra["findAndroidPlatformJar"] = ::findAndroidPlatformJar
extra["writeStoredOrDeflatedApk"] = ::writeStoredOrDeflatedApk
