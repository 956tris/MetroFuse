import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "dev.metrofuse"
version = "1.1.0"

compose.resources {
    packageOfResClass = "dev.metrofuse.sdk.resources"
    publicResClass = true
    generateResClass = always
}

kotlin {
    android {
        namespace = "dev.metrofuse.sdk"
        compileSdk { version = release(37) { minorApiLevel = 1 } }
        minSdk = 24
        androidResources.enable = true
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        commonMain.dependencies {
            api(project(":plugin-api"))
            api(compose.components.resources)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val common = getByName("commonMain")
        val jvmSharedMain = create("jvmSharedMain") { dependsOn(common) }
        getByName("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies { implementation(libs.activity) }
        }
        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.github.kdroidfilter:composewebview:1.0.0-beta-02")
                implementation(libs.jna)
            }
        }
        getByName("desktopTest").dependencies { implementation(kotlin("test")) }
    }
}

val helperResources = layout.buildDirectory.dir("generated/browser-resources")
val publishBrowserHelper =
    tasks.register<Exec>("publishBrowserHelper") {
        onlyIf { System.getProperty("os.name").startsWith("Windows") }
        inputs.files(fileTree("windows-webview") { exclude("bin/**", "obj/**", "tests/**") })
        outputs.dir(helperResources)
        commandLine(
            providers.gradleProperty("dotnetExecutable").orElse("dotnet").get(),
            "publish",
            layout.projectDirectory.file("windows-webview/Browser.csproj").asFile,
            "-c",
            "Release",
            "-r",
            "win-x64",
            "--self-contained",
            "true",
            "-o",
            helperResources.get().dir("metrofuse-sdk/windows").asFile,
            "/p:PublishSingleFile=true",
            "/p:IncludeNativeLibrariesForSelfExtract=true",
            "/p:DebugType=None",
            "/p:DebugSymbols=false",
        )
    }
tasks.named<Jar>("desktopJar") {
    dependsOn(publishBrowserHelper)
    from(helperResources) { include("metrofuse-sdk/windows/metrofuse-browser.exe") }
}

tasks.named<Test>("desktopTest") {
    if (System.getProperty("os.name").startsWith("Windows")) {
        dependsOn(publishBrowserHelper)
        systemProperty("metrofuse.sdk.browserExecutable", helperResources.get().file("metrofuse-sdk/windows/metrofuse-browser.exe").asFile)
    }
}
