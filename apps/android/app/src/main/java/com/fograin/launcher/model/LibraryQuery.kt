package com.fograin.launcher.model

/** Filter above the instance library. */
enum class LibraryScope(val chipTitle: String, val title: String) {
    ALL("全部", "全部实例"),
    RECENT("最近", "最近游玩"),
    FAVORITES("收藏", "收藏"),
    UPDATABLE("可更新", "可更新"),
}

/** Ordering of the instance library list. */
enum class LibrarySort(val title: String) {
    LAST_PLAYED("最近游玩"),
    NAME("名称"),
    SIZE("占用空间"),
}

/**
 * Pure filtering and ordering for the instance library.
 *
 * Kept free of Compose so M4 can replace the fixture source with FFI data and
 * keep the same behaviour under unit test.
 */
object LibraryQuery {
    /** Instances played within the last 30 days count as "recent". */
    const val RECENT_WINDOW_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L

    fun filter(
        instances: List<Instance>,
        scope: LibraryScope,
        query: String,
        nowMillis: Long,
    ): List<Instance> {
        val needle = query.trim().lowercase()
        return instances.filter { instance ->
            matchesScope(instance, scope, nowMillis) && matchesQuery(instance, needle)
        }
    }

    fun sort(instances: List<Instance>, sort: LibrarySort): List<Instance> = when (sort) {
        LibrarySort.LAST_PLAYED -> instances.sortedWith(
            compareByDescending<Instance> { it.lastPlayedEpochMillis ?: Long.MIN_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

        LibrarySort.NAME -> instances.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )

        LibrarySort.SIZE -> instances.sortedWith(
            compareByDescending<Instance> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    fun select(
        instances: List<Instance>,
        scope: LibraryScope,
        sort: LibrarySort,
        query: String,
        nowMillis: Long,
    ): List<Instance> = sort(filter(instances, scope, query, nowMillis), sort)

    fun totalSize(instances: List<Instance>): Long = instances.sumOf { it.sizeBytes }

    private fun matchesScope(instance: Instance, scope: LibraryScope, nowMillis: Long): Boolean =
        when (scope) {
            LibraryScope.ALL -> true
            LibraryScope.RECENT -> {
                val played = instance.lastPlayedEpochMillis
                played != null && nowMillis - played <= RECENT_WINDOW_MILLIS
            }

            LibraryScope.FAVORITES -> instance.isFavorite
            LibraryScope.UPDATABLE -> instance.hasUpdate
        }

    private fun matchesQuery(instance: Instance, needle: String): Boolean {
        if (needle.isEmpty()) return true
        val text = buildString {
            append(instance.name).append(' ')
            append(instance.minecraftVersion).append(' ')
            append(instance.loader.title).append(' ')
            append(instance.loaderVersion)
        }
        return text.lowercase().contains(needle)
    }
}
