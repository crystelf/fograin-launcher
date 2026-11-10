package com.fograin.launcher.model

/**
 * Loader families offered by the install sheet.
 *
 * Only 原版 is installable in M4; the other entries are reserved slots with
 * placeholder versions so the flow and naming are settled before the installers
 * land in M5. Mirrors macOS `GameLoaderKind`.
 */
enum class GameLoaderKind(
    val title: String,
    val subtitle: String,
    val milestone: String?,
    val placeholderVersions: List<String>,
) {
    VANILLA("原版", "纯净游戏，不需要额外加载器", null, emptyList()),
    FABRIC("Fabric", "轻量、更新快的模组加载器", "M5", listOf("0.16.9（最新）", "0.16.10", "0.15.11")),
    FABRIC_API("Fabric API", "多数 Fabric 模组的前置", "M5", listOf("0.116.0（最新）", "0.115.6")),
    QUILT("Quilt", "Fabric 的社区分支", "M5", listOf("0.27.0（最新）", "0.26.11")),
    FORGE("Forge", "老牌模组加载器", "M5", listOf("47.2.0（最新）", "47.3.0")),
    NEOFORGE("NeoForge", "Forge 的现代分支", "M5", listOf("21.1.72（最新）", "21.1.80")),
    OPTIFINE("OptiFine", "光影与性能优化", "M5", listOf("HD U M5（最新）", "HD U I7")),
    SQL_API("SQL API", "预留的扩展接口", "M5", listOf("预留"));

    val isAvailable: Boolean
        get() = this == VANILLA
}
