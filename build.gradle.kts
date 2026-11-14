import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.sentry.android) apply false
    alias(libs.plugins.sentry.jvm) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

val appName =
    providers
        .fileContents(layout.projectDirectory.file("shared/src/commonMain/composeResources/values/strings.xml"))
        .asText
        .map { catalog ->
            Regex("""<string\s+name="app_name"[^>]*>([^<]+)</string>""")
                .find(catalog)
                ?.groupValues
                ?.get(1)
                ?: error("The shared catalog must define app_name")
        }.get()
require(appName.matches(Regex("[A-Za-z][A-Za-z0-9 ]*"))) { "app_name must be a valid native application name" }
extra["appName"] = appName
extra["appArtifactName"] = appName.replace(' ', '-')

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set("1.8.0")
        filter {
            exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated/") }
        }
    }

    tasks.withType<BaseKtLintCheckTask>().configureEach {
        exclude { element ->
            element.file.invariantSeparatorsPath.let { path ->
                path.contains("/build/generated/") || path.contains("/build/kspCaches/")
            }
        }
    }
}
