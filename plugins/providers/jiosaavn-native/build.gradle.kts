import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        getByName("desktopMain").dependencies { compileOnly(project(":plugin-api")) }
        getByName("desktopTest").dependencies {
            implementation(project(":plugin-api"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.register<Jar>("packagePluginJar") {
    dependsOn("desktopMainClasses")
    archiveFileName.set("jiosaavn.jar")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("plugin-examples"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(
        kotlin.targets["desktop"]
            .compilations["main"]
            .output.allOutputs,
    )
    from(layout.projectDirectory.file("manifest.json"))
    from(layout.projectDirectory.file("SOURCE"))
    from(rootProject.file("LICENSE"))
}

val compilation = kotlin.targets["desktop"].compilations["main"]
val preparedAndroid = layout.buildDirectory.dir("plugin-apk/input")
val preparePluginApk =
    tasks.register<Sync>("preparePluginApk") {
        into(preparedAndroid)
        from(layout.projectDirectory.file("manifest.json")) { into("assets/metrofuse") }
        from(listOf(rootProject.file("LICENSE"), layout.projectDirectory.file("SOURCE"))) { into("assets") }
    }
val androidCode =
    tasks.register<Jar>("packageAndroidPluginClasses") {
        dependsOn("desktopMainClasses")
        archiveFileName.set("plugin-classes.jar")
        destinationDirectory.set(layout.buildDirectory.dir("plugin-apk"))
        from(compilation.output.allOutputs)
    }
val androidSdk =
    providers.provider {
        val properties = Properties()
        rootProject
            .file("local.properties")
            .takeIf(File::isFile)
            ?.inputStream()
            ?.use(properties::load)
        val configuredSdk = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        file(
            requireNotNull(configuredSdk) {
                "Set sdk.dir or ANDROID_HOME"
            },
        )
    }
val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val dexOutput = layout.buildDirectory.dir("plugin-apk/dex")
val resourcesApk = layout.buildDirectory.file("plugin-apk/resources.apk")
val dexPlugin =
    tasks.register<Exec>("dexPluginApk") {
        dependsOn(androidCode)
        inputs
            .files(
                compilation.compileDependencyFiles,
            ).withPropertyName("compileClasspath")
            .withNormalizer(ClasspathNormalizer::class.java)
        inputs.file(androidCode.flatMap { it.archiveFile })
        outputs.dir(dexOutput)
        doFirst {
            val sdk = androidSdk.get()
            val output = dexOutput.get().asFile.apply { mkdirs() }
            output
                .listFiles()
                .orEmpty()
                .filter { it.extension == "dex" }
                .forEach { it.delete() }
            val classpath = compilation.compileDependencyFiles.files.filter { it.extension == "jar" }
            commandLine(
                listOf(
                    jdk.get().executablePath.asFile,
                    "-cp",
                    sdk.resolve("build-tools/37.0.0/lib/d8.jar"),
                    "com.android.tools.r8.D8",
                    "--min-api",
                    "24",
                    "--lib",
                    sdk.resolve("platforms/android-37.0/android.jar"),
                    "--output",
                    output,
                ) +
                    classpath.flatMap { listOf("--classpath", it) } +
                    androidCode
                        .get()
                        .archiveFile
                        .get()
                        .asFile,
            )
        }
    }
val androidManifestFile = layout.projectDirectory.file("AndroidManifest.xml")
val linkPluginResources =
    tasks.register<Exec>("linkPluginApkResources") {
        dependsOn(preparePluginApk)
        inputs.dir(preparedAndroid.map { it.dir("assets") })
        inputs.file(androidManifestFile)
        outputs.file(resourcesApk)
        doFirst {
            val sdk = androidSdk.get()
            val executable = if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2"
            commandLine(
                sdk.resolve("build-tools/37.0.0/$executable"),
                "link",
                "--manifest",
                androidManifestFile.asFile,
                "-I",
                sdk.resolve("platforms/android-37.0/android.jar"),
                "-A",
                preparedAndroid.get().dir("assets").asFile,
                "--min-sdk-version",
                "24",
                "--target-sdk-version",
                "37",
                "-o",
                resourcesApk.get().asFile,
            )
        }
    }

tasks.register("packagePluginApk") {
    dependsOn(dexPlugin, linkPluginResources)
    val output = rootProject.layout.buildDirectory.file("plugin-examples/jiosaavn.apk")
    inputs.dir(dexOutput)
    inputs.file(resourcesApk)
    outputs.file(output)
    doLast {
        val entries = sortedMapOf<String, ByteArray>()
        ZipFile(resourcesApk.get().asFile).use { resources ->
            resources.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val name = entry.name.replace('\\', '/')
                require(name !in entries) { "Duplicate APK path: $name" }
                entries[name] = resources.getInputStream(entry).use { it.readBytes() }
            }
        }
        dexOutput.get().asFile.listFiles().orEmpty().filter { it.extension == "dex" }.forEach { dex ->
            entries[dex.name] = dex.readBytes()
        }
        require("assets/metrofuse/manifest.json" in entries && "classes.dex" in entries) {
            "Native APK is missing required entries: ${entries.keys}"
        }
        val unaligned =
            layout.buildDirectory
                .file("plugin-apk/unaligned.apk")
                .get()
                .asFile
        unaligned.parentFile.mkdirs()
        ZipOutputStream(unaligned.outputStream()).use { archive ->
            entries.forEach { (name, bytes) ->
                val entry =
                    ZipEntry(name).apply {
                        time = 315532800000L
                        if (name == "resources.arsc") {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            crc = CRC32().apply { update(bytes) }.value
                        }
                    }
                archive.putNextEntry(entry)
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        val sdk = androidSdk.get()
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "zipalign.exe" else "zipalign"
        val destination = output.get().asFile
        destination.parentFile.mkdirs()
        val process =
            ProcessBuilder(
                sdk.resolve("build-tools/37.0.0/$executable").absolutePath,
                "-f",
                "4",
                unaligned.absolutePath,
                destination.absolutePath,
            ).inheritIO().start()
        require(process.waitFor() == 0) { "APK alignment failed" }
    }
}
