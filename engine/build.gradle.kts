import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        namespace = "dev.metrofuse.engine"
        compileSdk { version = release(37) { minorApiLevel = 1 } }
        minSdk = 24
        androidResources.enable = true
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
        withHostTest {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        commonMain.dependencies { api(project(":plugin-api")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.github.dokar3:quickjs-kt:1.0.15")
                implementation(libs.okhttp)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
        getByName("androidMain") { dependsOn(jvmSharedMain) }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit4)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.cio)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.junit4)
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("metrofuse.root", rootProject.projectDir.absolutePath)
}

tasks.named<Test>("desktopTest") {
    dependsOn(":plugin-jiosaavn-native:packagePluginJar")
    if (providers.gradleProperty("buildYoutubeMusicPlugin").orNull == "true") {
        dependsOn(":plugin-youtube-music:packagePluginJar")
    }
}

val prepareNativeDeviceFixture by tasks.registering(Copy::class) {
    dependsOn(":plugin-native-examples:packageMinimalApk", ":plugin-native-examples:packageAudioApk")
    dependsOn(":plugin-jiosaavn-native:packagePluginApk")
    from(rootProject.layout.projectDirectory.file("plugins/native-examples/build/outputs/plugins/native-minimal.apk"))
    from(rootProject.layout.projectDirectory.file("plugins/native-examples/build/outputs/plugins/native-audio.apk"))
    from(rootProject.layout.buildDirectory.file("plugin-examples/jiosaavn.apk"))
    into(layout.projectDirectory.dir("src/androidDeviceTest/assets"))
}

tasks.matching { it.name == "mergeAndroidDeviceTestAssets" }.configureEach {
    dependsOn(prepareNativeDeviceFixture)
}
