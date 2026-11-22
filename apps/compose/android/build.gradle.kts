plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

val fograinVersion = providers.gradleProperty("fograin.version").get()

/** major*10000 + minor*100 + patch, so 0.1.0 becomes versionCode 100. */
val fograinVersionCode: Int = fograinVersion
    .substringBefore('-')
    .substringBefore('+')
    .split('.')
    .map { it.toIntOrNull() ?: 0 }
    .let { parts -> (parts.getOrElse(0) { 0 } * 10_000) + (parts.getOrElse(1) { 0 } * 100) + parts.getOrElse(2) { 0 } }

// Release signing is opt-in: a local build without these variables still
// produces the normal unsigned release APK, while the Release workflow decodes
// the keystore secret and exports the same four variables before Gradle runs.
// See docs/RELEASING.md for the keystore setup.
val releaseKeystorePath = providers.environmentVariable("FOGRAIN_ANDROID_KEYSTORE").orNull
    ?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = providers.environmentVariable("FOGRAIN_ANDROID_KEYSTORE_PASSWORD")
    .orNull
    ?.takeIf { it.isNotBlank() }
val releaseKeyAlias = providers.environmentVariable("FOGRAIN_ANDROID_KEY_ALIAS").orNull
    ?.takeIf { it.isNotBlank() }
val releaseKeyPassword = providers.environmentVariable("FOGRAIN_ANDROID_KEY_PASSWORD").orNull
    ?.takeIf { it.isNotBlank() }

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

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                val keystore = file(releaseKeystorePath)
                require(keystore.isFile) {
                    "FOGRAIN_ANDROID_KEYSTORE points at a missing file: ${keystore.absolutePath}"
                }
                val password = requireNotNull(releaseKeystorePassword) {
                    "FOGRAIN_ANDROID_KEYSTORE_PASSWORD is required when signing the release APK"
                }
                val alias = requireNotNull(releaseKeyAlias) {
                    "FOGRAIN_ANDROID_KEY_ALIAS is required when signing the release APK"
                }
                storeFile = keystore
                storePassword = password
                keyAlias = alias
                keyPassword = releaseKeyPassword ?: password
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
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
