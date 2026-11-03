package com.fograin.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryQueryTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1_000L

    private fun instance(
        id: String,
        name: String,
        loader: LoaderKind = LoaderKind.FABRIC,
        version: String = "1.21.4",
        lastPlayedDaysAgo: Long? = 1,
        favorite: Boolean = false,
        update: Boolean = false,
        size: Long = 1_000_000_000,
    ) = Instance(
        id = id,
        name = name,
        loader = loader,
        loaderVersion = "0.16.9",
        minecraftVersion = version,
        modCount = 10,
        sizeBytes = size,
        lastPlayedEpochMillis = lastPlayedDaysAgo?.let { now - it * day },
        isFavorite = favorite,
        hasUpdate = update,
        palette = Instance.InstancePalette.FOREST,
    )

    private val instances = listOf(
        instance("a", "雾林生存", lastPlayedDaysAgo = 2, favorite = true, size = 3_000_000_000),
        instance("b", "机械动力 6", loader = LoaderKind.FORGE, version = "1.20.1", lastPlayedDaysAgo = 40, update = true, size = 4_000_000_000),
        instance("c", "原版纯净", loader = LoaderKind.VANILLA, lastPlayedDaysAgo = null, size = 500_000_000),
        instance("d", "空岛科技", loader = LoaderKind.NEOFORGE, version = "1.19.2", lastPlayedDaysAgo = 10, update = true, favorite = true, size = 2_000_000_000),
    )

    @Test
    fun recentScopeUsesThirtyDayWindow() {
        val result = LibraryQuery.filter(instances, LibraryScope.RECENT, query = "", nowMillis = now)
        assertEquals(listOf("a", "d"), result.map { it.id })
    }

    @Test
    fun favoritesScopeKeepsFavoritesOnly() {
        val result = LibraryQuery.filter(instances, LibraryScope.FAVORITES, query = "", nowMillis = now)
        assertEquals(listOf("a", "d"), result.map { it.id })
    }

    @Test
    fun updatableScopeKeepsOutdatedInstancesOnly() {
        val result = LibraryQuery.filter(instances, LibraryScope.UPDATABLE, query = "", nowMillis = now)
        assertEquals(listOf("b", "d"), result.map { it.id })
    }

    @Test
    fun searchMatchesNameVersionAndLoader() {
        assertEquals(
            listOf("a"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "雾林", now).map { it.id },
        )
        assertEquals(
            listOf("b"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "1.20.1", now).map { it.id },
        )
        assertEquals(
            listOf("d"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "neoforge", now).map { it.id },
        )
    }

    @Test
    fun searchCombinesWithScope() {
        val result = LibraryQuery.select(
            instances = instances,
            scope = LibraryScope.FAVORITES,
            sort = LibrarySort.LAST_PLAYED,
            query = "1.19",
            nowMillis = now,
        )
        assertEquals(listOf("d"), result.map { it.id })
    }

    @Test
    fun lastPlayedSortPutsNeverPlayedLast() {
        val result = LibraryQuery.sort(instances, LibrarySort.LAST_PLAYED)
        assertEquals(listOf("a", "d", "b", "c"), result.map { it.id })
    }

    @Test
    fun sizeSortIsDescending() {
        val result = LibraryQuery.sort(instances, LibrarySort.SIZE)
        assertEquals(listOf("b", "a", "d", "c"), result.map { it.id })
    }

    @Test
    fun nameSortUsesCaseInsensitiveOrder() {
        val result = LibraryQuery.sort(instances, LibrarySort.NAME)
        assertEquals(listOf("c", "b", "d", "a"), result.map { it.id })
    }

    @Test
    fun totalSizeSumsEveryInstance() {
        assertEquals(9_500_000_000L, LibraryQuery.totalSize(instances))
    }
}
