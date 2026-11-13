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
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.datetime)
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
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
