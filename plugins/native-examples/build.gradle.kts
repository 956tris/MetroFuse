import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

apply(from = rootProject.file("tooling/metrofuse-plugin.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val findMetroFuseAndroidSdk = extra["findMetroFuseAndroidSdk"] as (File) -> File
@Suppress("UNCHECKED_CAST")
val findLatestBuildToolsDir = extra["findLatestBuildToolsDir"] as (File, String) -> File
@Suppress("UNCHECKED_CAST")
val findAndroidPlatformJar = extra["findAndroidPlatformJar"] as (File, String) -> File
@Suppress("UNCHECKED_CAST")
val writeStoredOrDeflatedApk = extra["writeStoredOrDeflatedApk"] as (File, File, List<File>, File?) -> Unit

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        getByName("desktopMain").dependencies {
            compileOnly(project(":plugin-api"))
            compileOnly(project(":plugin-sdk"))
        }
    }
}

val examples =
    mapOf(
        "minimal" to "MinimalPlugin",
        "audio" to "FixtureAudioPlugin",
        "settings" to "ComposeSettingsPlugin",
        "simple" to "SimpleDslPlugin",
    )

fun exampleApiVersion(example: String): String = if (example == "minimal") "1.1.0" else "1.2.0"

val nativeJars =
    examples.map { (example, entrypoint) ->
        val apiVer = exampleApiVersion(example)
        val abi = "kotlin-${libs.versions.kotlin.get()}_compose-${libs.versions.compose.get()}_api-$apiVer"
        val generateManifest =
            tasks.register("generate${example.replaceFirstChar(Char::uppercase)}Manifest") {
                val output = layout.buildDirectory.dir("manifests/$example")
                outputs.dir(output)
                inputs.property("composeAbi", abi)
                inputs.property("apiVersion", apiVer)
                doLast {
                    output.get().asFile.mkdirs()
                    output.get().file("manifest.json").asFile.writeText(
                        """{
                  "id": "example.native.$example", "name": "Native $example example", "version": "1.0.0",
                  "author": "MetroFuse", "api": "$apiVer", "runtime": "kotlin", "platforms": ["desktop", "android"],
                  "entryClass": "dev.metrofuse.examples.$entrypoint",
                  "composeAbi": "$abi",
                  "capabilities": ${
                            when (example) {
                                "audio", "simple" -> """["audio", "search"]"""
                                "settings" -> """["ui", "settings"]"""
                                else -> """["logging"]"""
                            }
                        },
                  "description": "Deterministic native SDK $example example", "integrity": {},
                  "settings": ${
                            when (example) {
                                "settings" -> """[{"key":"playbackLabel","title":"Playback button label","type":"STRING","default":"Toggle playback"},{"key":"enabled","title":"Enable example setting","type":"BOOLEAN","default":true}]"""
                                "audio" -> """[{"key":"fixtureUri","title":"Local WAV fixture URI","type":"STRING","default":""}]"""
                                else -> "[]"
                            }
                        }
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

val exampleClassesJar =
    tasks.register<Jar>("packageExampleClassesJar") {
        dependsOn("desktopMainClasses")
        archiveFileName.set("native-examples-classes.jar")
        destinationDirectory.set(layout.buildDirectory.dir("tmp/dex-input"))
        from(kotlin.targets["desktop"].compilations["main"].output.allOutputs)
    }

val dexExampleClasses =
    tasks.register("dexExampleClasses") {
        dependsOn(exampleClassesJar)
        val inputJar = exampleClassesJar.flatMap { it.archiveFile }
        val compileClasspath = kotlin.targets["desktop"].compilations["main"].compileDependencyFiles
        val dexOutput = layout.buildDirectory.dir("dex-classes")
        inputs.file(inputJar).withPathSensitivity(PathSensitivity.NONE)
        inputs.files(compileClasspath).withPathSensitivity(PathSensitivity.NONE)
        outputs.dir(dexOutput)
        doLast {
            val sdk = findMetroFuseAndroidSdk(rootProject.projectDir)
            val tools = findLatestBuildToolsDir(sdk, "37.0.0")
            val androidJar = findAndroidPlatformJar(sdk, "android-37.0")
            val outDir = dexOutput.get().asFile
            outDir.deleteRecursively()
            outDir.mkdirs()
            val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get().executablePath.asFile
            val d8Command =
                mutableListOf(
                    javaLauncher.absolutePath,
                    "-cp",
                    File(tools, "lib/d8.jar").absolutePath,
                    "com.android.tools.r8.D8",
                    "--min-api",
                    "24",
                    "--lib",
                    androidJar.absolutePath,
                    "--output",
                    outDir.absolutePath,
                )
            compileClasspath.files.filter { it.isFile && it.extension == "jar" }.forEach { d8Command += listOf("--classpath", it.absolutePath) }
            d8Command += inputJar.get().asFile.absolutePath
            val dexProcess = ProcessBuilder(d8Command).inheritIO().start()
            require(dexProcess.waitFor() == 0) { "D8 plugin compilation failed" }
        }
    }

val nativeApks =
    examples.keys.mapIndexed { index, example ->
        val jarTask = nativeJars[index]
        val linkTask =
            tasks.register("link${example.replaceFirstChar(Char::uppercase)}Resources") {
                dependsOn(jarTask)
                val manifestJar = jarTask.flatMap { it.archiveFile }
                val resApk = layout.buildDirectory.file("apk/$example/resources.apk")
                inputs.file(manifestJar).withPathSensitivity(PathSensitivity.NONE)
                outputs.file(resApk)
                doLast {
                    val sdk = findMetroFuseAndroidSdk(rootProject.projectDir)
                    val tools = findLatestBuildToolsDir(sdk, "37.0.0")
                    val androidJar = findAndroidPlatformJar(sdk, "android-37.0")
                    val workspace = layout.buildDirectory.dir("apk/$example").get().asFile.apply { mkdirs() }
                    val assets = File(workspace, "assets/metrofuse").apply { mkdirs() }
                    ZipFile(manifestJar.get().asFile).use { zip ->
                        zip.getInputStream(zip.getEntry("manifest.json")).use { source ->
                            File(assets, "manifest.json").outputStream().use { destination -> source.copyTo(destination) }
                        }
                    }
                    val androidManifest = File(workspace, "AndroidManifest.xml")
                    androidManifest.writeText(
                        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.metrofuse.examples.$example"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="37"/><application android:label="MetroFuse native $example example" android:hasCode="true"/></manifest>""",
                    )
                    val aapt = File(tools, if (System.getProperty("os.name").startsWith("Windows")) "aapt2.exe" else "aapt2")
                    val resourcesProcess =
                        ProcessBuilder(
                            aapt.absolutePath,
                            "link",
                            "--manifest",
                            androidManifest.absolutePath,
                            "-I",
                            androidJar.absolutePath,
                            "-A",
                            assets.parentFile.absolutePath,
                            "--min-sdk-version",
                            "24",
                            "--target-sdk-version",
                            "37",
                            "-o",
                            resApk.get().asFile.absolutePath,
                        ).inheritIO().start()
                    require(resourcesProcess.waitFor() == 0) { "AAPT2 plugin resource packaging failed" }
                }
            }
        tasks.register("package${example.replaceFirstChar(Char::uppercase)}Apk") {
            dependsOn(dexExampleClasses, linkTask)
            val resApk = linkTask.flatMap { it.outputs.files.elements }.map { it.single().asFile }
            val dexDir = dexExampleClasses.map { it.outputs.files.singleFile }
            val output = layout.buildDirectory.file("outputs/plugins/native-$example.apk")
            inputs.file(resApk).withPathSensitivity(PathSensitivity.NONE)
            inputs.dir(dexDir).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.file(output)
            doLast {
                val sdk = findMetroFuseAndroidSdk(rootProject.projectDir)
                val tools = findLatestBuildToolsDir(sdk, "37.0.0")
                val zipalignExe = File(tools, if (System.getProperty("os.name").startsWith("Windows")) "zipalign.exe" else "zipalign")
                val dexFiles = dexDir.get().listFiles().orEmpty().filter { it.extension == "dex" }
                require(dexFiles.isNotEmpty()) { "Missing compiled DEX files" }
                writeStoredOrDeflatedApk(output.get().asFile, resApk.get(), dexFiles, zipalignExe)
            }
        }
    }
tasks.register("packageNativeApks") { dependsOn(nativeApks) }
