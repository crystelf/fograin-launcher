plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fograin.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fograin.launcher"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // The JNI library is shared with the Compose client; filtering keeps
        // 32-bit androidx natives out of an APK that has no matching .so.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Legacy client: reuse the library staged for apps/compose instead of
    // keeping a second copy in this module.
    sourceSets["main"].jniLibs.srcDir("../../compose/android/src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // org.json is part of android.jar at runtime but not on the JVM unit-test
    // classpath; the wire models are tested without an emulator.
    testImplementation("org.json:json:20240303")
}
