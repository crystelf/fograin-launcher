package com.fograin.launcher.model

import com.fograin.launcher.platform.CoreInstanceRow

/**
 * One launcher instance, backed by `instances.list` from the shared Rust core.
 *
 * Presentation-only fields (palette, formatted strings) are derived here so the
 * screens stay free of wire types.
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
    val palette: InstancePalette,
    val playSeconds: Long = 0,
    val isInstalled: Boolean = false,
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
        ASH;

        companion object {
            /**
             * Assigns tile palettes in the pen's order so every instance gets a
             * stable accent color derived from its id (same djb2 hash as desktop).
             */
            fun forId(id: String): InstancePalette {
                var hash = 5381
                for (character in id) {
                    hash = (hash shl 5) + hash + character.code
                }
                return entries[(hash and Int.MAX_VALUE) % entries.size]
            }
        }
    }

    val group: LibraryGroup
        get() = loader.libraryGroup

    /** `1.21.4 · Fabric · 42 模组`, the supporting line used by both lists. */
    val meta: String
        get() = buildList {
            add(minecraftVersion)
            add(if (loader == LoaderKind.VANILLA) "原版" else loader.title)
            modCount?.let { add("$it 模组") }
        }.joinToString(" · ")

    /** `1.21.4 · Fabric 0.16.9`, or just the Minecraft version for vanilla. */
    val versionLine: String
        get() = if (loaderVersion.isBlank()) {
            minecraftVersion
        } else {
            "$minecraftVersion · ${loader.title} $loaderVersion"
        }

    /** Home rows show the loader even when it is vanilla. */
    val homeDetail: String
        get() = meta

    val playTimeMinutes: Int?
        get() = if (playSeconds <= 0) {
            null
        } else {
            (playSeconds / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    val playTimeText: String
        get() = InstanceFormat.playTime(playTimeMinutes)

    internal val searchText: String
        get() = "$name $minecraftVersion ${loader.title} $loaderVersion"

    companion object {
        /** Converts a core row; returns `null` only for a missing id. */
        fun from(row: CoreInstanceRow): Instance? {
            if (row.id.isBlank()) return null
            return Instance(
                id = row.id,
                name = row.name.ifBlank { row.id },
                loader = LoaderKind.fromWire(row.loaderKind),
                loaderVersion = row.loaderVersion,
                minecraftVersion = row.minecraft,
                modCount = row.modCount,
                sizeBytes = row.sizeBytes,
                lastPlayedEpochMillis = row.lastPlayedMs,
                isFavorite = row.favorite,
                palette = InstancePalette.forId(row.id),
                playSeconds = row.playSeconds,
                isInstalled = row.installed,
            )
        }
    }
}
