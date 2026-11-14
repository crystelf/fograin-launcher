plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

val fograinVersion = providers.gradleProperty("fograin.version").get()

/** major*10000 + minor*100 + patch, so 0.1.0 becomes versionCode 100. */
val fograinVersionCode: Int = fograinVersion
    .split('.')
    .mapNotNull { it.toIntOrNull() }
    .let { parts -> (parts.getOrElse(0) { 0 } * 10_000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 } }

android {
    namespace = "com.fograin.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fograin.launcher"
        minSdk = 26
        targetSdk = 37
        versionCode = fograinVersionCode
        versionName = fograinVersion

        // Stage the JNI library with scripts/build-android.sh.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // The Rust core is loaded with System.loadLibrary, so the .so must
            // stay a real file on the filesystem rather than a memory mapping
            // from the APK.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
}
