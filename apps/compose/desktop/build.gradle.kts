import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(compose.desktop.uiTestJUnit4)
}

val fograinPackageVersion = providers.gradleProperty("fograin.packageVersion").get()

compose.desktop {
    application {
        mainClass = "com.fograin.launcher.desktop.MainKt"
        // macOS derives the title-bar appearance from this property; without
        // it a dark launcher keeps a white Aqua title bar in `:desktop:run`
        // and in the packaged app. Ignored on Windows/Linux.
        jvmArgs += listOf("-Dapple.awt.application.appearance=system")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Fograin"
            // jpackage rejects versions whose first component is 0, so the
            // installer line (fograin.packageVersion) differs from the app
            // version (fograin.version) until the app itself reaches 1.0.
            packageVersion = fograinPackageVersion
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
