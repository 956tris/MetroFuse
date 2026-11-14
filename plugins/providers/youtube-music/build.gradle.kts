import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val innerTubeXVersion = providers.gradleProperty("innerTubeXVersion").get()
val innerTubeGroup = "com.github.MetrolistGroup.innertubex"
val quackGroup = "com.github.MetrolistGroup.quackjs"
val quackVersion = "0.1.4"

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        getByName("desktopMain").dependencies {
            compileOnly(project(":plugin-api"))
            compileOnly(libs.ktor.client.core)
            compileOnly(libs.kotlinx.serialization.json)
            compileOnly("$innerTubeGroup:innertubex-desktop:$innerTubeXVersion")
        }
        getByName("desktopTest").dependencies {
            implementation(project(":plugin-api"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.cio)
            implementation("$innerTubeGroup:innertubex-desktop:$innerTubeXVersion")
            implementation(kotlin("test"))
        }
    }
}

val providerJvm = configurations.create("providerJvm") { isTransitive = false }
val providerAndroid = configurations.create("providerAndroid") { isTransitive = false }
dependencies {
    add(providerJvm.name, "$innerTubeGroup:innertubex-desktop:$innerTubeXVersion")
    add(providerJvm.name, "$quackGroup:quackjs-jvm:$quackVersion")
    add(providerAndroid.name, "$innerTubeGroup:innertubex-android:$innerTubeXVersion@aar")
    add(providerAndroid.name, "$quackGroup:quackjs-android:$quackVersion@aar")
}

val compilation = kotlin.targets["desktop"].compilations["main"]
val manifestSource = layout.projectDirectory.file("manifest.json")
val manifestFile = layout.buildDirectory.file("plugin-manifest/manifest.json")
val generatePluginManifest =
    tasks.register("generatePluginManifest") {
        val composeAbi = "kotlin-${libs.versions.kotlin.get()}_compose-${libs.versions.compose.get()}_api-1.1.0"
        inputs.file(manifestSource)
        inputs.property("composeAbi", composeAbi)
        outputs.file(manifestFile)
        doLast {
            val output = manifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(manifestSource.asFile.readText().replaceFirst("{", "{\n  \"composeAbi\": \"$composeAbi\","))
        }
    }
val androidManifestFile = layout.projectDirectory.file("AndroidManifest.xml")
val providerJar =
    tasks.register<Jar>("packagePluginJar") {
        dependsOn("desktopMainClasses", generatePluginManifest)
        archiveFileName.set("youtube-music.jar")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/plugins"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(compilation.output.allOutputs)
        from(manifestFile)
        from(rootProject.file("LICENSE"))
        from(layout.projectDirectory.file("SOURCE.md"))
        from({ providerJvm.files.map { zipTree(it) } }) {
            exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
        }
    }

val preparedAndroid = layout.buildDirectory.dir("plugin-apk/input")
val preparePluginApk =
    tasks.register<Sync>("preparePluginApk") {
        dependsOn("desktopMainClasses", generatePluginManifest)
        into(preparedAndroid)
        from(manifestFile) { into("assets/metrofuse") }
        from(rootProject.file("LICENSE")) { into("assets") }
        from(layout.projectDirectory.file("SOURCE.md")) { into("assets") }
        from({ providerAndroid.files.map { zipTree(it) } }) {
            include("jni/**", "assets/**")
            eachFile { if (path.startsWith("jni/")) path = "lib/" + path.removePrefix("jni/") }
            includeEmptyDirs = false
        }
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
        val sdkPath = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        file(
            requireNotNull(sdkPath) {
                "Set sdk.dir or ANDROID_HOME"
            },
        )
    }
val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val dexOutput = layout.buildDirectory.dir("plugin-apk/dex")
val resourcesApk = layout.buildDirectory.file("plugin-apk/resources.apk")
val dexPlugin =
    tasks.register<Exec>("dexPluginApk") {
        dependsOn(preparePluginApk, androidCode)
        inputs
            .files(
                compilation.compileDependencyFiles,
            ).withPropertyName("compileClasspath")
            .withNormalizer(ClasspathNormalizer::class.java)
        inputs.files(providerAndroid).withPropertyName("androidLibraries").withNormalizer(ClasspathNormalizer::class.java)
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
            val dependencyDir =
                layout.buildDirectory
                    .dir("plugin-apk/dependencies")
                    .get()
                    .asFile
                    .apply { mkdirs() }
            val program =
                providerAndroid.files.sortedBy(File::getName).map { artifact ->
                    dependencyDir.resolve("${artifact.nameWithoutExtension}.jar").also { target ->
                        ZipFile(artifact).use { archive ->
                            archive.getInputStream(requireNotNull(archive.getEntry("classes.jar"))).use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                }
            val classpath =
                compilation.compileDependencyFiles.files.filter {
                    it.extension == "jar" && !it.name.startsWith("innertubex-") &&
                        !it.name.startsWith("quackjs-")
                }
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
                    classpath.flatMap { listOf("--classpath", it) } + program +
                    androidCode
                        .get()
                        .archiveFile
                        .get()
                        .asFile,
            )
        }
    }

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

tasks.register<Zip>("packagePluginApk") {
    dependsOn(dexPlugin, linkPluginResources)
    archiveFileName.set("youtube-music.apk")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/plugins"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(dexOutput)
    from(preparedAndroid.map { it.dir("lib") }) { into("lib") }
    from({ zipTree(resourcesApk.get().asFile) })
}
