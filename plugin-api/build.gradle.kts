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

kotlin {
    android {
        namespace = "dev.metrofuse.api"
        compileSdk { version = release(37) { minorApiLevel = 1 } }
        minSdk = 24
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
    jvmToolchain(25)
    sourceSets {
        commonMain.dependencies {
            api(libs.runtime)
            api(libs.foundation)
            api(libs.material3)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
