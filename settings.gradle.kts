@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        // Content filters stop Gradle from querying every repo for every
        // artifact: JitPack and the bravepipe mirror only serve com.github.*
        // groups, so Central/Google-only lookups skip them entirely.
        maven {
            setUrl("https://raw.githubusercontent.com/bravepipeproject/maven-repo/master/repository")
            content { includeGroupByRegex("com\\.github\\.bravepipeproject.*") }
        }
        maven {
            setUrl("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\..*") }
        }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
}

// F-Droid doesn't support foojay-resolver plugin
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
// }

rootProject.name = "Metroapple"
include(":app")
include(":innertube")
include(":kugou")
include(":lrclib")
include(":lastfm")
include(":betterlyrics")
include(":shazamkit")
include(":paxsenix")

// Use a local copy of NewPipe Extractor by uncommenting the lines below.
// We assume, that Metrolist and NewPipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in innertube/build.gradle.kts
// to one which does not specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.teamnewpipe:NewPipeExtractor")
//includeBuild("../NewPipeExtractor") {
//    dependencySubstitution {
//        substitute(module("com.github.teamnewpipe:NewPipeExtractor")).using(project(":extractor"))
//    }
//}
