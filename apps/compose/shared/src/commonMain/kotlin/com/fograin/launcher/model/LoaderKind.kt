package com.fograin.launcher.model

/**
 * Mod loader families the launcher knows about.
 *
 * Mirrors the shared core's loader kind. The library groups OptiFine with
 * vanilla and Quilt with Fabric, matching the pen's four groups.
 */
enum class LoaderKind(val title: String, val libraryGroup: LibraryGroup) {
    VANILLA("原版", LibraryGroup.VANILLA),
    OPTIFINE("OptiFine", LibraryGroup.VANILLA),
    FABRIC("Fabric", LibraryGroup.FABRIC),
    QUILT("Quilt", LibraryGroup.FABRIC),
    FORGE("Forge", LibraryGroup.FORGE),
    NEOFORGE("NeoForge", LibraryGroup.NEOFORGE),
    ;

    companion object {
        /** Parses `loaderKind` from `instances.list`; unknown values are vanilla. */
        fun fromWire(raw: String?): LoaderKind =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: VANILLA
    }
}

/** The four groups shown in the instance library, in pen order. */
enum class LibraryGroup {
    FABRIC,
    FORGE,
    NEOFORGE,
    VANILLA,
}
