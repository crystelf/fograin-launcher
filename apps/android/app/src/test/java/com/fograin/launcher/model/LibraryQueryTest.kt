package com.fograin.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        palette = Instance.InstancePalette.FOREST,
    )

    private val instances = listOf(
        instance("a", "雾林生存", lastPlayedDaysAgo = 2, favorite = true, size = 3_000_000_000),
        instance("b", "机械动力 6", loader = LoaderKind.FORGE, version = "1.20.1", lastPlayedDaysAgo = 40, size = 4_000_000_000),
        instance("c", "原版纯净", loader = LoaderKind.VANILLA, lastPlayedDaysAgo = null, size = 500_000_000),
        instance("d", "空岛科技", loader = LoaderKind.NEOFORGE, version = "1.19.2", lastPlayedDaysAgo = 10, favorite = true, size = 2_000_000_000),
        instance("e", "暮色森林", loader = LoaderKind.OPTIFINE, version = "1.20.1", lastPlayedDaysAgo = 5, size = 1_500_000_000),
        instance("f", "深海探索", loader = LoaderKind.QUILT, version = "1.21.1", lastPlayedDaysAgo = 3, size = 1_200_000_000),
    )

    @Test
    fun favoritesScopeKeepsFavoritesOnly() {
        val result = LibraryQuery.filter(instances, LibraryScope.FAVORITES, query = "")
        assertEquals(listOf("a", "d"), result.map { it.id })
    }

    @Test
    fun loaderScopesGroupQuiltWithFabricAndOptifineWithVanilla() {
        assertEquals(
            listOf("a", "f"),
            LibraryQuery.filter(instances, LibraryScope.FABRIC, "").map { it.id },
        )
        assertEquals(
            listOf("b"),
            LibraryQuery.filter(instances, LibraryScope.FORGE, "").map { it.id },
        )
        assertEquals(
            listOf("d"),
            LibraryQuery.filter(instances, LibraryScope.NEOFORGE, "").map { it.id },
        )
        assertEquals(
            listOf("c", "e"),
            LibraryQuery.filter(instances, LibraryScope.VANILLA, "").map { it.id },
        )
    }

    @Test
    fun searchMatchesNameVersionAndLoader() {
        assertEquals(
            listOf("a"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "雾林").map { it.id },
        )
        assertEquals(
            listOf("b", "e"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "1.20.1").map { it.id },
        )
        assertEquals(
            listOf("d"),
            LibraryQuery.filter(instances, LibraryScope.ALL, "neoforge").map { it.id },
        )
    }

    @Test
    fun searchCombinesWithScope() {
        val result = LibraryQuery.select(
            instances = instances,
            scope = LibraryScope.FAVORITES,
            sort = LibrarySort.RECENT,
            query = "1.19",
        )
        assertEquals(listOf("d"), result.map { it.id })
    }

    @Test
    fun recentSortPutsNeverPlayedLast() {
        val result = LibraryQuery.sort(instances, LibrarySort.RECENT)
        assertEquals(listOf("a", "f", "e", "d", "b", "c"), result.map { it.id })
    }

    @Test
    fun sizeSortIsDescending() {
        val result = LibraryQuery.sort(instances, LibrarySort.SIZE)
        assertEquals(listOf("b", "a", "d", "e", "f", "c"), result.map { it.id })
    }

    @Test
    fun nameSortUsesCaseInsensitiveOrder() {
        val result = LibraryQuery.sort(instances, LibrarySort.NAME)
        assertEquals(listOf("c", "e", "b", "f", "d", "a"), result.map { it.id })
    }

    @Test
    fun sectionsFollowPenOrderAndSkipEmptyGroups() {
        val sections = LibraryQuery.sections(
            instances = instances,
            scope = LibraryScope.FAVORITES,
            sort = LibrarySort.RECENT,
            query = "",
        )
        assertEquals(listOf(LibraryGroup.FABRIC, LibraryGroup.NEOFORGE), sections.map { it.group })
        assertEquals(listOf("a"), sections[0].instances.map { it.id })
        assertEquals(listOf("d"), sections[1].instances.map { it.id })
    }

    @Test
    fun totalSizeSumsEveryInstance() {
        val total = instances.sumOf { it.sizeBytes }
        assertEquals(total, LibraryQuery.totalSize(instances))
        assertTrue(total > 0)
    }
}
