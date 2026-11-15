package dev.metrofuse.runtime

import dev.metrofuse.api.API_VERSION
import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.Manifest
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Imports .mfp JavaScript archives and direct .jar/.apk native artifacts. */
class PackageStore(private val root: File, private val platform: String) {
    fun recoverInterruptedInstallations() {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("\\.staging-[a-f0-9-]{36}")) }.forEach { staging ->
            require(staging.canonicalFile.parentFile == root.canonicalFile)
            require(staging.deleteRecursively()) { "Cannot remove interrupted installation staging directory" }
        }
    }

    fun install(input: InputStream, fileName: String = "plugin.mfp"): File {
        require(root.mkdirs() || root.isDirectory) { "Cannot create plugin storage" }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in setOf("mfp", "jar", "apk")) { "Expected .mfp, .jar or .apk" }
        require(extension != "jar" || platform == "desktop") { "JAR plugins require desktop" }
        require(extension != "apk" || platform == "android") { "APK plugins require Android" }
        val staging = File(root, ".staging-${UUID.randomUUID()}")
        require(staging.mkdir()) { "Cannot create staging directory" }
        try {
            if (extension == "mfp") extractJavascript(input, staging)
            else importNative(input, extension, staging)
            val manifest = verify(staging)
            val destination = File(root, manifest.id)
            require(!destination.exists()) { "Already installed: use the update operation" }
            require(staging.renameTo(destination)) { "Cannot commit package installation" }
            return destination
        } finally {
            if (staging.exists()) require(staging.deleteRecursively()) { "Cannot clean staging directory" }
        }
    }

    private fun extractJavascript(input: InputStream, staging: File) {
        var total = 0L
        var entries = 0
        val names = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 512) { "Package exceeds 512 entries" }
                val name = entry.name.removeSuffix("/")
                validatePath(name)
                require(names.add(name.lowercase())) { "Duplicate or case-colliding path" }
                require(!name.endsWith(".jar", true) && !name.endsWith(".apk", true) && !name.endsWith(".dex", true)) {
                    ".mfp packages contain JavaScript, not native executables"
                }
                val target = File(staging, name)
                require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                if (entry.isDirectory) {
                    require(target.mkdirs() || target.isDirectory)
                    continue
                }
                val parent = requireNotNull(target.parentFile)
                require(parent.mkdirs() || parent.isDirectory)
                val limit = if (name == "manifest.json") 128L * 1024 else 16L * 1024 * 1024
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var length = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        length += count
                        total += count
                        require(length <= limit && total <= 64L * 1024 * 1024) { "Package size limit exceeded" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        val manifestFile = File(staging, "manifest.json")
        val manifest = readManifest(manifestFile)
        require(manifest.runtime == "javascript") { ".mfp requires runtime javascript" }
        if (manifest.integrity.isEmpty()) {
            val integrity = staging.walkTopDown().filter { it.isFile && it != manifestFile }.associate { it.relativeTo(staging).invariantSeparatorsPath to sha256(it) }
            manifestFile.writeText(codec.encodeToString(manifest.copy(integrity = integrity)))
        }
    }

    private fun importNative(input: InputStream, extension: String, staging: File) {
        val artifact = File(staging, "native/plugin.$extension")
        require(requireNotNull(artifact.parentFile).mkdir())
        input.use { source -> artifact.outputStream().use { output -> copyBounded(source, output, 256L * 1024 * 1024) } }
        val manifestPath = if (extension == "jar") "manifest.json" else "assets/metrofuse/manifest.json"
        val integrity = mutableMapOf("native/plugin.$extension" to sha256(artifact))
        ZipFile(artifact).use { zip ->
            val manifestEntry = requireNotNull(zip.getEntry(manifestPath)) { "Missing $manifestPath" }
            val manifestBytes = zip.getInputStream(manifestEntry).use { readBounded(it, 128 * 1024) }
            val manifest = decodeManifest(manifestBytes.decodeToString())
            require(manifest.runtime == "kotlin") { "Native artifacts require runtime kotlin" }
            validateManifest(manifest, platform)
            var total = 0L
            val names = mutableSetOf<String>()
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 8192) { "Native archive has too many entries" }
            entries.forEach { entry ->
                val name = entry.name.removeSuffix("/")
                validatePath(name)
                require(names.add(name.lowercase())) { "Duplicate or case-colliding native path" }
                if (entry.isDirectory) return@forEach
                require(entry.size in 0..256L * 1024 * 1024) { "Native archive size limit" }
                total += entry.size
                require(total <= 512L * 1024 * 1024) { "Native archive size limit" }
                if (extension == "jar") require(!isHostClass(name)) { "Plugin bundles host API/runtime class: $name" }
                if (extension == "apk" && name.matches(Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex"))) {
                    validateDex(zip.getInputStream(entry).use { readBounded(it, 128 * 1024 * 1024) })
                }
                val asset = if (extension == "jar" && name.startsWith("assets/")) name
                    else if (extension == "apk" && name.startsWith("assets/") && name != manifestPath) name
                    else null
                if (asset != null) {
                    val target = File(staging, asset)
                    val parent = requireNotNull(target.parentFile)
                    require(parent.mkdirs() || parent.isDirectory)
                    zip.getInputStream(entry).use { source -> target.outputStream().use { copyBounded(source, it, 16L * 1024 * 1024) } }
                    integrity[asset] = sha256(target)
                }
            }
            require(extension != "apk" || zip.getEntry("classes.dex") != null) { "APK contains no classes.dex" }
            require(extension != "jar" || zip.getEntry(requireNotNull(manifest.entryClass).replace('.', '/') + ".class") != null) { "Missing native entrypoint" }
            File(staging, "manifest.json").writeText(codec.encodeToString(manifest.copy(integrity = integrity)))
        }
    }

    fun verify(directory: File): Manifest {
        val manifestFile = File(directory, "manifest.json")
        val manifest = readManifest(manifestFile)
        validateManifest(manifest, platform)
        val files = directory.walkTopDown().filter { it.isFile && it != manifestFile }.toList()
        require(files.size <= 8192)
        val paths = files.map { it.relativeTo(directory).invariantSeparatorsPath }.toSet()
        require(manifest.integrity.keys == paths) { "Integrity index must cover every payload file exactly" }
        var total = 0L
        files.forEach { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            validatePath(path)
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            val limit = if (manifest.runtime == "kotlin" && path.startsWith("native/")) 256L * 1024 * 1024 else 16L * 1024 * 1024
            require(file.length() <= limit)
            total += file.length()
            require(total <= 512L * 1024 * 1024)
            require(sha256(file) == manifest.integrity[path]) { "SHA-256 mismatch: $path" }
        }
        if (manifest.runtime == "javascript") {
            require(manifest.script in paths) { "Script entry missing" }
            require(paths.none { it.endsWith(".jar", true) || it.endsWith(".apk", true) || it.endsWith(".dex", true) }) { "JavaScript packages cannot contain native executables" }
        } else {
            require((if (platform == "desktop") "native/plugin.jar" else "native/plugin.apk") in paths) { "Platform executable missing" }
        }
        return manifest
    }

    companion object {
        fun validateManifest(manifest: Manifest, platform: String) {
            require(manifest.id.matches(Regex("[a-z][a-z0-9.-]{2,79}")) && !manifest.id.contains("..")) { "Invalid plugin ID" }
            require(manifest.id != "builtin" && !manifest.id.startsWith("builtin.")) { "The built-in owner namespace is reserved" }
            validatePath(manifest.id)
            require(manifest.name.isNotBlank() && manifest.name.length <= 120)
            require(manifest.author.isNotBlank()) { "Author is required" }
            SemVer.parse(manifest.version)
            require(manifest.api == API_VERSION) { "Unsupported plugin API ${manifest.api}" }
            require(platform in manifest.platforms) { "Unsupported platform $platform" }
            require(manifest.runtime in setOf("javascript", "kotlin")) { "Expected javascript or kotlin runtime" }
            if (manifest.runtime == "javascript") {
                validatePath(requireNotNull(manifest.script) { "Missing script entrypoint" })
                require(manifest.entryClass == null) { "JavaScript packages cannot declare a native entrypoint" }
            } else {
                require(manifest.entryClass?.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) == true) { "Invalid entry class" }
                require(manifest.script == null) { "Native artifacts cannot declare a JavaScript entrypoint" }
                require(manifest.composeAbi == COMPOSE_ABI) { "Compose ABI mismatch; rebuild against $COMPOSE_ABI" }
            }
            require(manifest.dependencies.keys.none { it == manifest.id }) { "Self dependency" }
            manifest.dependencies.forEach { (id, version) ->
                require(id.matches(Regex("[a-z][a-z0-9.-]{2,79}")) && !id.contains("..")) { "Invalid dependency ID" }
                SemVer.parse(version.removePrefix(">="))
            }
            manifest.minimumHostVersion?.let(SemVer::parse)
            manifest.icon?.let(::validatePath)
            manifest.home?.let { home ->
                home.title?.let { require(it.isNotBlank() && it.length <= 120) }
                home.icon?.let {
                    validatePath(it)
                    require(it.startsWith("assets/"))
                }
            }
            require(
                manifest.settings
                    .map { it.key }
                    .toSet()
                    .size == manifest.settings.size,
            ) { "Duplicate setting keys" }
            manifest.settings.forEach { PersistentPluginSettings.validate(it, it.default) }
            require(manifest.integrity.values.all { it.matches(Regex("[a-f0-9]{64}")) }) { "Invalid SHA-256 digest" }
        }

        private fun readManifest(file: File): Manifest {
            require(file.isFile && file.length() in 1..128 * 1024) { "Missing/oversized manifest" }
            return decodeManifest(file.readText())
        }

        private fun decodeManifest(source: String): Manifest {
            require(codec.parseToJsonElement(source).jsonObject.containsKey("api")) { "Package must explicitly declare its API version" }
            return codec.decodeFromString(source)
        }

        fun validatePath(path: String) {
            require(path.length in 1..220 && !path.contains('\\') && !path.contains(':') && !path.startsWith('/')) { "Unsafe package path" }
            val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")
            val parts = path.split('/')
            require(parts.size <= 16 && parts.all { it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') && it.none { c -> c.code < 32 || c in "<>\"|?*" } && !reserved.matches(it) }) { "Unsafe package path" }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun copyBounded(input: InputStream, output: OutputStream, maximum: Long) {
            var size = 0L
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= maximum) { "Artifact exceeds size limit" }
                output.write(buffer, 0, count)
            }
        }

        private fun readBounded(input: InputStream, maximum: Int): ByteArray = ByteArrayOutputStream().use {
            copyBounded(input, it, maximum.toLong())
            it.toByteArray()
        }

        private fun isHostClass(name: String): Boolean {
            val classPath = name.replace(Regex("^META-INF/versions/[0-9]+/"), "")
            return classPath.endsWith(".class") && listOf("dev/metrofuse/api/", "dev/metrofuse/sdk/", "androidx/compose/", "kotlin/", "kotlinx/coroutines/", "kotlinx/serialization/").any(classPath::startsWith)
        }

        /** Reads defined class descriptors, rather than rejecting references to host APIs. */
        private fun validateDex(bytes: ByteArray) {
            require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) { "Invalid DEX header" }
            fun integer(offset: Int): Int {
                require(offset >= 0 && offset <= bytes.size - 4) { "Invalid DEX offset" }
                return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
            }
            val stringsCount = integer(56)
            val stringsOffset = integer(60)
            val typesCount = integer(64)
            val typesOffset = integer(68)
            val classesCount = integer(96)
            val classesOffset = integer(100)
            require(stringsCount >= 0 && typesCount >= 0 && classesCount in 0..1_000_000)
            repeat(classesCount) { index ->
                val typeIndex = integer(classesOffset + index * 32)
                require(typeIndex in 0 until typesCount)
                val stringIndex = integer(typesOffset + typeIndex * 4)
                require(stringIndex in 0 until stringsCount)
                var offset = integer(stringsOffset + stringIndex * 4)
                var prefixBytes = 0
                do {
                    require(offset in bytes.indices && ++prefixBytes <= 5)
                    val next = bytes[offset++].toInt() and 255
                } while (next and 128 != 0)
                val start = offset
                while (offset in bytes.indices && bytes[offset].toInt() != 0) offset++
                require(offset in bytes.indices && offset - start <= 65536)
                val descriptor = bytes.copyOfRange(start, offset).decodeToString()
                require(!listOf("Ldev/metrofuse/api/", "Ldev/metrofuse/sdk/", "Landroidx/compose/", "Lkotlin/", "Lkotlinx/coroutines/", "Lkotlinx/serialization/").any(descriptor::startsWith)) { "APK bundles host API/runtime class: $descriptor" }
            }
        }
    }
}
