package com.fograin.launcher.model

/**
 * Static fixtures for the M1 shell.
 *
 * The values mirror the pen screens and the macOS M1 client so the four native
 * clients show the same information architecture. Every page replaces these
 * with FFI data in later milestones (M4 for instances, M7 for accounts).
 */
object SampleData {
    private const val HOUR = 60L * 60L * 1_000L
    private const val DAY = 24L * HOUR

    data class Account(
        val name: String,
        val initial: String,
        val kind: String,
        val isOnline: Boolean,
    )

    val account = Account(
        name = "Steve_Fog",
        initial = "S",
        kind = "正版账户",
        isOnline = true,
    )

    val currentInstanceId = "fog-forest"

    val instances: List<Instance> = makeInstances()

    val currentInstance: Instance
        get() = instances.firstOrNull { it.id == currentInstanceId } ?: instances.first()

    fun recentInstances(limit: Int = 3, excludingId: String? = null): List<Instance> = instances
        .asSequence()
        .filter { it.id != excludingId }
        .sortedByDescending { it.lastPlayedEpochMillis ?: Long.MIN_VALUE }
        .take(limit)
        .toList()

    private fun makeInstances(): List<Instance> {
        val now = System.currentTimeMillis()

        fun hoursAgo(hours: Long): Long = now - hours * HOUR
        fun daysAgo(days: Long): Long = now - days * DAY

        return listOf(
            Instance(
                id = "fog-forest",
                name = "雾林生存",
                loader = LoaderKind.FABRIC,
                loaderVersion = "0.16.9",
                minecraftVersion = "1.21.4",
                modCount = 42,
                sizeBytes = 3_100_000_000,
                lastPlayedEpochMillis = hoursAgo(2),
                isFavorite = true,
                palette = Instance.InstancePalette.FOREST,
                playTimeMinutes = 7_560,
                memoryMb = 6_144,
                javaMajorVersion = 21,
            ),
            Instance(
                id = "create-6",
                name = "机械动力 6",
                loader = LoaderKind.FORGE,
                loaderVersion = "47.2.0",
                minecraftVersion = "1.20.1",
                modCount = 186,
                sizeBytes = 4_800_000_000,
                lastPlayedEpochMillis = daysAgo(1),
                isFavorite = true,
                hasUpdate = true,
                palette = Instance.InstancePalette.COPPER,
                playTimeMinutes = 5_280,
                memoryMb = 8_192,
                javaMajorVersion = 17,
            ),
            Instance(
                id = "vanilla",
                name = "原版纯净",
                loader = LoaderKind.VANILLA,
                loaderVersion = "",
                minecraftVersion = "1.21.4",
                modCount = null,
                sizeBytes = 410_000_000,
                lastPlayedEpochMillis = daysAgo(3),
                palette = Instance.InstancePalette.SLATE,
                playTimeMinutes = 720,
                memoryMb = 4_096,
                javaMajorVersion = 21,
            ),
            Instance(
                id = "sky-tech",
                name = "空岛科技",
                loader = LoaderKind.NEOFORGE,
                loaderVersion = "21.1.72",
                minecraftVersion = "1.19.2",
                modCount = 97,
                sizeBytes = 3_600_000_000,
                lastPlayedEpochMillis = daysAgo(7),
                hasUpdate = true,
                palette = Instance.InstancePalette.VIOLET,
                playTimeMinutes = 3_240,
                memoryMb = 6_144,
                javaMajorVersion = 17,
            ),
            Instance(
                id = "nostalgia",
                name = "怀旧 1.8.9",
                loader = LoaderKind.OPTIFINE,
                loaderVersion = "HD U M5",
                minecraftVersion = "1.8.9",
                modCount = null,
                sizeBytes = 620_000_000,
                lastPlayedEpochMillis = daysAgo(21),
                palette = Instance.InstancePalette.CLAY,
                playTimeMinutes = 1_860,
                memoryMb = 2_048,
                javaMajorVersion = 8,
            ),
            Instance(
                id = "deep-sea",
                name = "深海探索",
                loader = LoaderKind.FABRIC,
                loaderVersion = "0.16.9",
                minecraftVersion = "1.21.1",
                modCount = 28,
                sizeBytes = 1_900_000_000,
                lastPlayedEpochMillis = daysAgo(35),
                palette = Instance.InstancePalette.LAGOON,
                playTimeMinutes = 1_200,
                memoryMb = 4_096,
                javaMajorVersion = 21,
            ),
            Instance(
                id = "architecture",
                name = "建筑灵感",
                loader = LoaderKind.FABRIC,
                loaderVersion = "0.16.9",
                minecraftVersion = "1.21.4",
                modCount = 12,
                sizeBytes = 860_000_000,
                lastPlayedEpochMillis = daysAgo(65),
                palette = Instance.InstancePalette.SAND,
                playTimeMinutes = 480,
                memoryMb = 4_096,
                javaMajorVersion = 21,
            ),
            Instance(
                id = "twilight",
                name = "暮色森林",
                loader = LoaderKind.FORGE,
                loaderVersion = "47.2.0",
                minecraftVersion = "1.20.1",
                modCount = 64,
                sizeBytes = 2_200_000_000,
                lastPlayedEpochMillis = daysAgo(70),
                palette = Instance.InstancePalette.MOSS,
                playTimeMinutes = 2_520,
                memoryMb = 6_144,
                javaMajorVersion = 17,
            ),
            Instance(
                id = "snapshot",
                name = "快照 25w14a",
                loader = LoaderKind.VANILLA,
                loaderVersion = "",
                minecraftVersion = "25w14a",
                modCount = null,
                sizeBytes = 380_000_000,
                lastPlayedEpochMillis = null,
                palette = Instance.InstancePalette.ASH,
            ),
        )
    }
}
