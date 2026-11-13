package com.fograin.launcher.model

/** Top-level categories of the download page (§13). */
enum class DownloadsCategory(val milestone: String?) {
    GAME(null),
    MOD("M8"),
    MODPACK("M9"),
    RESOURCEPACK("M8"),
    SHADERPACK("M8"),
    DATAPACK("M8"),
    ;

    /** Only the game tab is wired in M4. */
    val isGame: Boolean get() = this == GAME
}

/** Version families shown in the game tab. */
enum class DownloadVersionChannel(val kindValue: String) {
    RELEASE("release"),
    SNAPSHOT("snapshot"),
    APRIL_FOOLS("april_fools"),
    OLD("old"),
}

/** Loader filter for the 模组 / 整合包 tabs (content lands in M8/M9). */
enum class ContentLoaderFilter {
    ALL,
    FORGE,
    NEOFORGE,
    FABRIC,
    QUILT,
    OPTIFINE,
}
