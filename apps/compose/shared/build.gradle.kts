@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // CI and the release jobs build with Temurin 21; pinning the toolchain
    // keeps a local JDK 25 from changing bytecode or breaking Compose Desktop.
    jvmToolchain(21)
    jvm("desktop")

    android {
        namespace = "com.fograin.launcher.shared"
        compileSdk = 37
        minSdk = 26
        // Packages composeResources/* into the Android assets so
        // StringResource/painterResource work from the shared module.
        androidResources.enable = true
    }

    // common -> jvm -> (android, desktop). `jvmMain` is shared by both targets.
    applyDefaultHierarchyTemplate {
        common {
            group("jvm") {
                withJvm()
                group("android") {
                    withCompilations { it.platformType == KotlinPlatformType.androidJvm }
                }
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets.commonMain.dependencies {
        implementation(libs.compose.runtime)
        implementation(libs.compose.foundation)
        implementation(libs.compose.material3)
        implementation(libs.compose.components.resources)
        implementation(libs.compose.material.icons.extended)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.datetime)
        implementation(libs.coil.compose)
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }

    // Android needs the Main dispatcher for AppModel and the activity
    // BackHandler behind the shared `BackDispatcher` interface.
    sourceSets.getByName("androidMain").dependencies {
        implementation(libs.androidx.activity.compose)
        implementation(libs.kotlinx.coroutines.android)
    }

    // Coil's OkHttp network fetcher is JVM-only; `jvmMain` covers Android and desktop.
    sourceSets.getByName("jvmMain").dependencies {
        implementation(libs.coil.network.okhttp)
    }

    sourceSets.getByName("desktopMain").dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.kotlinx.coroutines.swing)
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.fograin.launcher.resources"
}
