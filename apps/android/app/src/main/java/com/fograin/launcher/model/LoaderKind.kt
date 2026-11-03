package com.fograin.launcher.model

/**
 * Mod loader families the launcher knows about.
 *
 * Mirrors the shared core's `LoaderKind`; the Android client only needs the
 * display name until the FFI bindings land in M4.
 */
enum class LoaderKind(val title: String) {
    VANILLA("原版"),
    OPTIFINE("OptiFine"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
}
