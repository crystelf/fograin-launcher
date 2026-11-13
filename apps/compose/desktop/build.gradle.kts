import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

compose.desktop {
    application {
        mainClass = "com.fograin.launcher.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Fograin"
            // jpackage rejects versions whose first component is 0, so the
            // installer uses 1.0.0 while the app/core version is 0.1.0.
            packageVersion = "1.0.0"
            description = "Fograin Launcher"
            vendor = "Fograin Launcher contributors"

            macOS {
                bundleID = "com.fograin.launcher"
            }
            linux {
                packageName = "fograin"
            }
        }
    }
}
