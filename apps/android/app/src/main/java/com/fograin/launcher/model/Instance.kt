package com.fograin.launcher.model

/**
 * One launcher instance, as shown by the M1 shell.
 *
 * The values come from [SampleData] for now. M4 replaces the fixtures with
 * `instance_list` from the shared Rust core without changing this model.
 */
data class Instance(
    val id: String,
    val name: String,
    val loader: LoaderKind,
    val loaderVersion: String,
    val minecraftVersion: String,
    val modCount: Int?,
    val sizeBytes: Long,
    val lastPlayedEpochMillis: Long?,
    val isFavorite: Boolean = false,
    val hasUpdate: Boolean = false,
    val palette: InstancePalette,
    val playTimeMinutes: Int? = null,
    val memoryMb: Int = 4096,
    val javaMajorVersion: Int = 21,
) {
    /** Accent family for the generated instance tile, from `tokens.json`. */
    enum class InstancePalette {
        FOREST,
        COPPER,
        SLATE,
        VIOLET,
        CLAY,
        LAGOON,
        SAND,
        MOSS,
        ASH,
    }

    /** `1.21.4 · Fabric · 42 模组`, the supporting line used by both lists. */
    val meta: String
        get() = listOf(minecraftVersion, loader.title, "${modCount ?: 0} 模组")
            .joinToString(" · ")

    /** `1.21.4 · Fabric 0.16.9`, or just the Minecraft version for vanilla. */
    val versionLine: String
        get() = if (loaderVersion.isBlank()) {
            minecraftVersion
        } else {
            "$minecraftVersion · ${loader.title} $loaderVersion"
        }

    /** Sort key so unmodded instances sort below modded ones. */
    val modCountForSort: Int
        get() = modCount ?: 0
}
