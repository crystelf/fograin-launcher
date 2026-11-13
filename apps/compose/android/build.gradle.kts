plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
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
