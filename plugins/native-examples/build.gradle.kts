import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        getByName("desktopMain").dependencies { compileOnly(project(":plugin-api")) }
    }
}

val examples = mapOf(
    "minimal" to "MinimalPlugin",
    "audio" to "FixtureAudioPlugin",
    "settings" to "ComposeSettingsPlugin",
)
val nativeJars = examples.map { (example, entrypoint) ->
    val generateManifest = tasks.register("generate${example.replaceFirstChar(Char::uppercase)}Manifest") {
        val output = layout.buildDirectory.dir("manifests/$example")
        outputs.dir(output)
        inputs.property("composeAbi", "kotlin-${libs.versions.kotlin.get()}_compose-${libs.versions.compose.get()}_api-1.1.0")
        doLast {
            output.get().asFile.mkdirs()
            output.get().file("manifest.json").asFile.writeText(
                """{
                  "id": "example.native.$example", "name": "Native $example example", "version": "1.0.0",
                  "author": "MetroFuse", "api": "1.1.0", "runtime": "kotlin", "platforms": ["desktop", "android"],
                  "entryClass": "dev.metrofuse.examples.$entrypoint",
                  "composeAbi": "kotlin-${libs.versions.kotlin.get()}_compose-${libs.versions.compose.get()}_api-1.1.0",
                  "capabilities": ${if (example == "audio") """["audio", "search"]""" else if (example == "settings") """["ui", "settings"]""" else """["logging"]"""},
                  "description": "Deterministic native SDK $example example", "integrity": {},
                  "settings": ${if (example == "settings") """[{"key":"playbackLabel","title":"Playback button label","type":"STRING","default":"Toggle playback"},{"key":"enabled","title":"Enable example setting","type":"BOOLEAN","default":true}]""" else if (example == "audio") """[{"key":"fixtureUri","title":"Local WAV fixture URI","type":"STRING","default":""}]""" else "[]"}
                }""".trimIndent(),
            )
        }
    }
    tasks.register<Jar>("package${example.replaceFirstChar(Char::uppercase)}Jar") {
        dependsOn("desktopMainClasses", generateManifest)
        archiveFileName.set("native-$example.jar")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/plugins"))
        from(kotlin.targets["desktop"].compilations["main"].output.allOutputs)
        from(generateManifest)
    }
}
tasks.register("packageNativeJars") { dependsOn(nativeJars) }

fun androidSdk(): File {
    val properties = Properties()
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use(properties::load)
    return file(requireNotNull(properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")) { "Set sdk.dir in local.properties" })
}

val nativeApks = examples.keys.mapIndexed { index, example ->
    tasks.register("package${example.replaceFirstChar(Char::uppercase)}Apk") {
        val jarTask = nativeJars[index]
        dependsOn(jarTask)
        val output = layout.buildDirectory.file("outputs/plugins/native-$example.apk")
        outputs.file(output)
        inputs.file(jarTask.flatMap { it.archiveFile })
        doLast {
            val sdk = androidSdk()
            val tools = File(sdk, "build-tools/37.0.0")
            val androidJar = File(sdk, "platforms/android-37.0/android.jar")
            require(androidJar.isFile) { "Install Android platform 37.0" }
            require(File(tools, "lib/d8.jar").isFile) { "Install Android build-tools 37.0.0" }
            val workspace = layout.buildDirectory.dir("apk/$example").get().asFile.apply { mkdirs() }
            val dexOutput = File(workspace, "dex").apply { mkdirs() }
            val assets = File(workspace, "assets/metrofuse").apply { mkdirs() }
            ZipFile(jarTask.get().archiveFile.get().asFile).use { zip ->
                zip.getInputStream(zip.getEntry("manifest.json")).use { source -> File(assets, "manifest.json").outputStream().use { destination -> source.copyTo(destination) } }
            }
            val androidManifest = File(workspace, "AndroidManifest.xml")
            androidManifest.writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.metrofuse.examples.$example"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="37"/><application android:label="MetroFuse native $example example" android:hasCode="true"/></manifest>""")
            val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().executablePath.asFile
            val classpath = kotlin.targets["desktop"].compilations["main"].compileDependencyFiles.files
            val d8Command = mutableListOf(javaLauncher.absolutePath, "-cp", File(tools, "lib/d8.jar").absolutePath, "com.android.tools.r8.D8", "--min-api", "24", "--lib", androidJar.absolutePath, "--output", dexOutput.absolutePath)
            classpath.filter { it.isFile && it.extension == "jar" }.forEach { d8Command += listOf("--classpath", it.absolutePath) }
            d8Command += jarTask.get().archiveFile.get().asFile.absolutePath
            val dexProcess = ProcessBuilder(d8Command).inheritIO().start()
            require(dexProcess.waitFor() == 0) { "D8 plugin compilation failed" }
            val resourcesApk = File(workspace, "resources.apk")
            val aapt = File(tools, if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2")
            val resourcesProcess = ProcessBuilder(aapt.absolutePath, "link", "--manifest", androidManifest.absolutePath, "-I", androidJar.absolutePath, "-A", assets.parentFile.absolutePath, "--min-sdk-version", "24", "--target-sdk-version", "37", "-o", resourcesApk.absolutePath).inheritIO().start()
            require(resourcesProcess.waitFor() == 0) { "AAPT2 plugin resource packaging failed" }
            output.get().asFile.parentFile.mkdirs()
            ZipOutputStream(output.get().asFile.outputStream()).use { archive ->
                ZipFile(resourcesApk).use { resources ->
                    val names = mutableSetOf<String>()
                    resources.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        val name = entry.name.replace('\\', '/')
                        require(names.add(name.lowercase())) { "Resource archive has duplicate normalized paths" }
                        archive.putNextEntry(ZipEntry(name))
                        resources.getInputStream(entry).use { it.copyTo(archive) }
                        archive.closeEntry()
                    }
                }
                dexOutput.listFiles().orEmpty().filter { it.extension == "dex" }.forEach { dex ->
                    archive.putNextEntry(ZipEntry(dex.name))
                    dex.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
            }
        }
    }
}
tasks.register("packageNativeApks") { dependsOn(nativeApks) }
