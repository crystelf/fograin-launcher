package com.fograin.launcher.model

/** Top-level categories of the download page (§13). */
enum class DownloadsCategory(val title: String, val shortTitle: String) {
    GAME("游戏版本", "游戏"),
    MOD("模组", "模组"),
    MODPACK("整合包", "整合包"),
    RESOURCEPACK("资源包", "资源包"),
    SHADERPACK("光影包", "光影"),
    DATAPACK("数据包", "数据包");

    /** Milestone that will wire this category; `null` when M4 already does. */
    val milestone: String?
        get() = when (this) {
            GAME -> null
            MOD, RESOURCEPACK, SHADERPACK, DATAPACK -> "M8"
            MODPACK -> "M9"
        }
}

/** Version families shown in the game tab. */
enum class DownloadVersionChannel(
    val title: String,
    val kindValue: String,
    val emptyMessage: String,
) {
    RELEASE("正式版", "release", "暂无正式版"),
    SNAPSHOT("快照", "snapshot", "暂无快照"),
    APRIL_FOOLS("愚人节", "april_fools", "暂无愚人节版本"),
    OLD("远古版本", "old", "暂无远古版本"),
}

/** Loader filter for the 模组 / 整合包 tabs (content lands in M8/M9). */
enum class ContentLoaderFilter(val title: String) {
    ALL("全部加载器"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    OPTIFINE("OptiFine"),
}
