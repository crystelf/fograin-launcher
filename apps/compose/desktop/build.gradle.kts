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
    // The custom window caption uses Material 3 colours/typography directly;
    // the shared module keeps its Material dependency implementation-only.
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // Windows keeps its native frame styles (DWM animations, Snap, shadow)
    // while the launcher hides the visible caption through WM_NCCALCSIZE and
    // answers WM_NCHITTEST itself. That needs a WndProc subclass.
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.compose.ui.test.junit4)
}

val fograinPackageVersion = providers.gradleProperty("fograin.packageVersion").get()

// Desktop signing is opt-in and driven by the Release workflow. Without these
// variables the installers are built unsigned exactly like a local build.
// See docs/RELEASING.md for the certificate and notarization setup.
fun envValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val macSignIdentity = envValue("FOGRAIN_MAC_SIGN_IDENTITY")
val macKeychain = envValue("FOGRAIN_MAC_KEYCHAIN")
val macAppleId = envValue("FOGRAIN_MAC_APPLE_ID")
val macPassword = envValue("FOGRAIN_MAC_PASSWORD")
val macTeamId = envValue("FOGRAIN_MAC_TEAM_ID")
val macNotarizationReady =
    !macSignIdentity.isNullOrBlank() &&
        !macAppleId.isNullOrBlank() &&
        !macPassword.isNullOrBlank() &&
        !macTeamId.isNullOrBlank()
val macIcons = project.layout.projectDirectory.dir("icons")

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
                appCategory = "public.app-category.games"
                iconFile.set(macIcons.file("icon.icns"))
                if (!macSignIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(macSignIdentity)
                        if (!macKeychain.isNullOrBlank()) {
                            keychain.set(macKeychain)
                        }
                    }
                }
                if (macNotarizationReady) {
                    notarization {
                        appleID.set(macAppleId)
                        password.set(macPassword)
                        teamID.set(macTeamId)
                    }
                }
            }
            windows {
                iconFile.set(macIcons.file("icon.ico"))
                // A stable UUID lets an MSI from a later tag upgrade the
                // existing installation instead of installing side by side.
                upgradeUuid = "bed53dbe-3054-41f8-acbf-43d85abacaa9"
                menuGroup = "Fograin"
                shortcut = true
            }
            linux {
                packageName = "fograin"
                appCategory = "Game"
                iconFile.set(macIcons.file("icon.png"))
            }
        }
    }
}
