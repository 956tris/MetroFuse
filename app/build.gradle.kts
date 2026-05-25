import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL
import java.util.Properties
import javax.inject.Inject

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val baseApplicationId = "com.metrofuse.music"
val metroFuseVersionCode = 500
val metroFuseVersionName = "5.0"
val metroFuseUpdateRepository = "956tris/MetroFuse"
val applicationIdOverride = System.getenv("METROLIST_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }
val debugKeystorePathOverride = System.getenv("METROLIST_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystorePassword = System.getenv("METROLIST_DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val debugKeyAlias = System.getenv("METROLIST_DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val debugKeyPassword = System.getenv("METROLIST_DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val persistentDebugKeystoreFile = file("persistent-debug.keystore")
val workflowDebugKeystoreFile = debugKeystorePathOverride?.let(::file)
val releaseSigningFile = rootProject.file("../release-apks/MetroApple-release-keystore-password.txt")
val releaseSigningFileValues =
    if (releaseSigningFile.isFile) {
        releaseSigningFile
            .readLines()
            .mapNotNull { line ->
                val separatorIndex = listOf(line.indexOf('='), line.indexOf(':'))
                    .filter { it >= 0 }
                    .minOrNull()
                if (separatorIndex == null) {
                    null
                } else {
                    val name = line.substring(0, separatorIndex).trim().uppercase()
                    val value = line.substring(separatorIndex + 1).trim()
                    name.takeIf { it.isNotBlank() }?.let { it to value }
                }
            }.toMap()
    } else {
        emptyMap()
    }
val releaseApksKeystoreFile = rootProject.file("../release-apks/MetroApple-release.keystore.p12")
val releaseKeystoreFile =
    System.getenv("RELEASE_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }?.let(::file)
        ?: providers.gradleProperty("RELEASE_KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }?.let(::file)
        ?: releaseApksKeystoreFile.takeIf { it.isFile }
        ?: file("keystore/release.keystore")
fun releaseSigningValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: when (name) {
            "STORE_PASSWORD" -> releaseSigningFileValues["STORE_PASSWORD"] ?: releaseSigningFileValues["PASSWORD"]
            "KEY_ALIAS" -> releaseSigningFileValues["KEY_ALIAS"] ?: releaseSigningFileValues["ALIAS"]
            "KEY_PASSWORD" -> releaseSigningFileValues["KEY_PASSWORD"] ?: releaseSigningFileValues["PASSWORD"]
            else -> null
        }

val releaseSigningEnvironment =
    mapOf(
        "STORE_PASSWORD" to releaseSigningValue("STORE_PASSWORD"),
        "KEY_ALIAS" to releaseSigningValue("KEY_ALIAS"),
        "KEY_PASSWORD" to releaseSigningValue("KEY_PASSWORD"),
    )
val releaseBuildRequested =
    gradle.startParameter.taskNames.any { taskName ->
        val task = taskName.substringAfterLast(':')
        task.equals("assemble", ignoreCase = true) ||
            task.equals("bundle", ignoreCase = true) ||
            task.equals("build", ignoreCase = true) ||
            task.endsWith("Release", ignoreCase = true)
    }
if (releaseBuildRequested) {
    if (!releaseKeystoreFile.isFile) {
        throw org.gradle.api.GradleException(
            "Release signing keystore not found at ${releaseKeystoreFile.path}.",
        )
    }
    val missingSigningValues = releaseSigningEnvironment.filterValues { it == null }.keys
    if (missingSigningValues.isNotEmpty()) {
        throw org.gradle.api.GradleException(
            "Missing release signing environment values: ${missingSigningValues.joinToString()}. " +
                "Set them as environment variables or Gradle properties. " +
                "Release APKs must be signed with ${releaseKeystoreFile.path}.",
        )
    }
}
val releaseStorePassword = releaseSigningEnvironment.getValue("STORE_PASSWORD")
val releaseKeyAlias = releaseSigningEnvironment.getValue("KEY_ALIAS")
val releaseKeyPassword = releaseSigningEnvironment.getValue("KEY_PASSWORD")

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateProtoTask : DefaultTask() {
    @get:Input
    abstract val protocUrl: Property<String>

    @get:InputFile
    abstract val protoSourceFile: RegularFileProperty

    @get:Internal
    abstract val generatedSourcesDir: DirectoryProperty

    @get:Internal
    abstract val protocExecutable: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val protoFile = protoSourceFile.get().asFile
        val outputDir = generatedSourcesDir.get().asFile
        val protocFile = protocExecutable.get().asFile

        outputDir.mkdirs()

        if (!protocFile.exists() || protocFile.length() == 0L) {
            val url = protocUrl.get()
            logger.lifecycle("Downloading protoc ${url.substringAfterLast('/')} from $url")
            protocFile.parentFile.mkdirs()
            URL(url).openStream().use { input ->
                protocFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            protocFile.setExecutable(true)
        }

        logger.lifecycle("Generating protobuf files in $outputDir")
        execOperations.exec {
            executable = protocFile.absolutePath
            args(
                "--java_out=lite:$outputDir",
                "--kotlin_out=$outputDir",
                "-I=${protoFile.parentFile}",
                protoFile.absolutePath,
            )
        }
        logger.lifecycle("Protobuf files generated successfully")
    }
}

android {
    namespace = "com.metrolist.music"
    compileSdk = 36

    defaultConfig {
        applicationId = applicationIdOverride ?: baseApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = metroFuseVersionCode
        versionName = metroFuseVersionName
        resValue("string", "app_name", appNameOverride ?: "MetroFuse")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // LastFM API keys from GitHub Secrets
        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY") ?: System.getenv("LASTFM_API_KEY") ?: ""
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET") ?: System.getenv("LASTFM_SECRET") ?: ""

        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")
        buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$metroFuseUpdateRepository\"")
    }

    flavorDimensions += listOf("variant")
    productFlavors {
        // FOSS variant (default) - F-Droid compatible, no Google Play Services
        create("foss") {
            dimension = "variant"
            isDefault = true
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // GMS variant - with Google Cast support (requires Google Play Services)
        create("gms") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "true")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // IzzyOnDroid variant - no Google Cast, no built-in updater (store handles updates)
        create("izzy") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")
        }
    }

    signingConfigs {
        create("persistentDebug") {
            storeFile = persistentDebugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("workflowDebug") {
            storeFile = workflowDebugKeystoreFile ?: persistentDebugKeystoreFile
            storePassword = debugKeystorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
        create("release") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            if (appNameOverride == null) {
                resValue("string", "app_name", "MetroFuse")
            }
            signingConfig =
                if (workflowDebugKeystoreFile != null) {
                    signingConfigs.getByName("workflowDebug")
                } else if (persistentDebugKeystoreFile.exists()) {
                    signingConfigs.getByName("persistentDebug")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols +=
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

val protocVersion = libs.versions.protobuf.get()

fun getProtocUrl(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osName = when {
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("windows") -> "windows"
        else -> "linux"
    }

    val archName = when {
        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("x86") -> "x86_32"
        else -> "x86_64"
    }

    return "https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protocVersion/protoc-$protocVersion-$osName-$archName.exe"
}

val protoDir = rootProject.file("metroproto")
val protoFile = protoDir.resolve("listentogether.proto")

val generateProto = if (protoFile.exists()) {
    val protocUrl = getProtocUrl()
    val protocFileName = URL(protocUrl).path.substringAfterLast('/')

    tasks.register<GenerateProtoTask>("generateProto") {
        group = "build"
        description = "Generate Kotlin protobuf files"

        protoSourceFile.set(protoFile)
        generatedSourcesDir.set(file("src/main/java"))
        this.protocUrl.set(protocUrl)
        protocExecutable.set(layout.buildDirectory.file("protoc/$protocFileName"))
    }
} else {
    logger.warn("Proto file not found at $protoFile. Skipping protobuf generation.")
    null
}

tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("assemble")) {
        generateProto?.let { dependsOn(it) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
        suppressWarnings.set(false)
    }
}

// Android provides org.json as a platform API (/apex/com.android.art/javalib/core-libart.jar).
// The standalone org.json:json artefact bundles an older Apache Harmony copy of JSONArray that
// contains an internal `myArrayList` field absent from the platform class.  Without obfuscation
// R8 inlines against this internal field; at runtime the platform class is resolved instead,
// producing a NoSuchFieldError.  Excluding the artefact globally ensures only the platform
// class is ever referenced.
configurations.configureEach {
    exclude(group = "org.json", module = "json")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.materialKolor)

    implementation(libs.appcompat)
    implementation(libs.webkit)
    implementation(libs.accompanist.webview)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    implementation(libs.ucrop)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation(libs.media3.hls)
    implementation(libs.media3.dash)
    implementation(libs.media3.ffmpeg.decoder)

    // Google Cast - only included in GMS flavor (not available in F-Droid/FOSS builds)
    "gmsImplementation"(libs.media3.cast)
    "gmsImplementation"(libs.mediarouter)
    "gmsImplementation"(libs.cast.framework)

    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.jsoup)
    ksp(libs.hilt.compiler)

    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":discordrpc"))
    implementation(project(":lastfm"))
    implementation(project(":betterlyrics"))
    implementation(project(":shazamkit"))
    implementation(project(":paxsenix"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Protobuf for message serialization (lite version for Android)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)
}
