package com.fograin.launcher.model

/**
 * Loader families offered by the install page, mirroring `fg-loader`.
 *
 * M5 makes every entry installable. `wireName` is the value the Rust core
 * expects in `install.start` / `loaders.versions`; it is not the enum name
 * because `NEOFORGE` is sent as `neoforge`.
 */
enum class GameLoaderKind(
    val title: String,
    val subtitle: String,
    val wireName: String,
) {
    VANILLA("原版", "纯净游戏，不需要额外加载器", "vanilla"),
    FABRIC("Fabric", "轻量、更新快的模组加载器", "fabric"),
    QUILT("Quilt", "Fabric 的社区分支", "quilt"),
    FORGE("Forge", "老牌模组加载器，覆盖 1.7.10 到最新版", "forge"),
    NEOFORGE("NeoForge", "Forge 的现代分支，覆盖 1.20.1+", "neoforge"),
    OPTIFINE("OptiFine", "光影与性能优化", "optifine"),
    ;

    val isAvailable: Boolean
        get() = true

    companion object {
        fun fromWire(raw: String?): GameLoaderKind =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: VANILLA
    }
}
