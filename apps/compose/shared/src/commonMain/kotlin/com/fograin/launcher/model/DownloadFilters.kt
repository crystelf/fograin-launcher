package com.fograin.launcher.model

/** Top-level categories of the download page (§13). */
enum class DownloadsCategory {
    GAME,
    MOD,
    MODPACK,
    RESOURCEPACK,
    SHADERPACK,
    DATAPACK,
    ;

    /** Minecraft version browser; every other tab browses content projects. */
    val isGame: Boolean get() = this == GAME

    /** Project browser (Modrinth / CurseForge). */
    val isContent: Boolean get() = this != GAME

    /** Modpacks install into a new instance instead of an existing one. */
    val installsToNewInstance: Boolean get() = this == MODPACK

    /** Data packs are installed into one world. */
    val worldScoped: Boolean get() = this == DATAPACK

    /** Wire value understood by `content.search` / `content.install`. */
    val contentKind: String?
        get() = when (this) {
            MOD -> "mod"
            MODPACK -> "modpack"
            RESOURCEPACK -> "resourcepack"
            SHADERPACK -> "shaderpack"
            DATAPACK -> "datapack"
            GAME -> null
        }
}

/** Version families shown in the game tab. */
enum class DownloadVersionChannel(val kindValue: String) {
    RELEASE("release"),
    SNAPSHOT("snapshot"),
    APRIL_FOOLS("april_fools"),
    OLD("old"),
}

/** Loader filter for the 整合包 tab. */
enum class ContentLoaderFilter {
    ALL,
    FORGE,
    NEOFORGE,
    FABRIC,
    QUILT,
}

/**
 * Release-channel filter for the version list inside the content sheet.
 * The wire spelling matches Modrinth/CurseForge (`release` / `beta` / `alpha`).
 */
enum class ContentVersionChannel(val wire: String) {
    RELEASE("release"),
    BETA("beta"),
    ALPHA("alpha"),
    ALL("all"),
    ;

    /** Whether [versionType] belongs to this bucket; [ALL] accepts everything. */
    fun matches(versionType: String): Boolean =
        this == ALL || versionType.equals(wire, ignoreCase = true)
}
