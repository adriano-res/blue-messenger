import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.sqldelight) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        parallel = true
    }

    extensions.configure<KtlintExtension> {
        filter {
            exclude { element -> element.file.path.contains("/build/generated/") }
        }
    }

    tasks.withType<Detekt>().configureEach {
        exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated/") }
        jvmTarget = "17"
        reports {
            html.required.set(true)
            xml.required.set(false)
            txt.required.set(false)
        }
    }

    tasks.named("detekt").configure {
        if (this is Detekt) {
            setSource(files("src"))
            include("**/*.kt")
            include("**/*.kts")
            exclude("**/build/**")
        }
        dependsOn(
            tasks.matching {
                it.name in setOf(
                    "detektJvmMain",
                    "detektDebug",
                    "detektAndroidDebug",
                    "detektMetadataCommonMain",
                )
            },
        )
    }
}
