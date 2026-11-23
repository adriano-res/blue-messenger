import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.bluemessenger.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Rpm)
            packageName = "blue-messenger"
            packageVersion = "1.0.0"
            description = "Offline P2P E2EE Bluetooth Messenger"
            vendor = "Blue Messenger"

            modules(
                "java.compiler",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.naming",
                "java.rmi",
                "java.sql",
                "jdk.security.auth",
                "jdk.unsupported",
            )

            linux {
                packageName = "blue-messenger"
                menuGroup = "Network;InstantMessaging;"
                appCategory = "Network"
                rpmLicenseType = "MIT"
            }
        }
    }
}
