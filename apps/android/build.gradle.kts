plugins {
    // AGP 9 compiles Kotlin with built-in support; the standalone
    // org.jetbrains.kotlin.android plugin must not be applied.
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
