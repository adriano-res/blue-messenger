import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
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
