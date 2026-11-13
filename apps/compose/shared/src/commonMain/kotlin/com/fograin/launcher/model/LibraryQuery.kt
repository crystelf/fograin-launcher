package com.fograin.launcher.model

/** Filter above the instance library. */
enum class LibraryScope {
    ALL,
    FAVORITES,
    FABRIC,
    FORGE,
    NEOFORGE,
    VANILLA,
    ;

    val group: LibraryGroup?
        get() = when (this) {
            ALL, FAVORITES -> null
            FABRIC -> LibraryGroup.FABRIC
            FORGE -> LibraryGroup.FORGE
            NEOFORGE -> LibraryGroup.NEOFORGE
            VANILLA -> LibraryGroup.VANILLA
        }
}

/** Ordering of the instance library list. */
enum class LibrarySort {
    RECENT,
    NAME,
    SIZE,
}

/** One loader group of the library table. */
data class LibrarySection(
    val group: LibraryGroup,
    val instances: List<Instance>,
)

/**
 * Pure filtering, ordering and grouping for the instance library.
 *
 * The pen groups rows by loader first and applies the selected order inside
 * each group; empty groups are skipped so filtering never leaves orphan
 * headers. Kept free of Compose and `java.text` so it stays unit-tested in
 * `commonTest`.
 */
object LibraryQuery {
    fun filter(
        instances: List<Instance>,
        scope: LibraryScope,
        query: String,
    ): List<Instance> {
        val needle = query.trim().lowercase()
        return instances.filter { instance ->
            matchesScope(instance, scope) &&
                (needle.isEmpty() || instance.searchText.lowercase().contains(needle))
        }
    }

    fun sort(instances: List<Instance>, sort: LibrarySort): List<Instance> = when (sort) {
        LibrarySort.RECENT -> instances.sortedWith(
            compareByDescending<Instance> { it.lastPlayedEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() },
        )

        LibrarySort.NAME -> instances.sortedBy { it.name.lowercase() }

        LibrarySort.SIZE -> instances.sortedWith(
            compareByDescending<Instance> { it.sizeBytes }
                .thenBy { it.name.lowercase() },
        )
    }

    fun sections(
        instances: List<Instance>,
        scope: LibraryScope,
        sort: LibrarySort,
        query: String,
    ): List<LibrarySection> {
        val visible = filter(instances, scope, query)
        return LibraryGroup.entries.mapNotNull { group ->
            val matches = visible.filter { it.group == group }
            if (matches.isEmpty()) null else LibrarySection(group, sort(matches, sort))
        }
    }

    fun select(
        instances: List<Instance>,
        scope: LibraryScope,
        sort: LibrarySort,
        query: String,
    ): List<Instance> = sort(filter(instances, scope, query), sort)

    fun totalSize(instances: List<Instance>): Long = instances.sumOf { it.sizeBytes }

    private fun matchesScope(instance: Instance, scope: LibraryScope): Boolean = when (scope) {
        LibraryScope.ALL -> true
        LibraryScope.FAVORITES -> instance.isFavorite
        else -> scope.group?.let { instance.group == it } ?: true
    }
}
