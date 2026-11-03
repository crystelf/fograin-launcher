import Foundation

public struct Account: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let initial: String
    public let kind: String
    public let isOnline: Bool

    public init(id: String, name: String, initial: String, kind: String, isOnline: Bool) {
        self.id = id
        self.name = name
        self.initial = initial
        self.kind = kind
        self.isOnline = isOnline
    }
}

public struct DownloadTask: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let detail: String
    /// 0...1
    public let progress: Double
    public let speed: String

    public init(id: String, title: String, detail: String, progress: Double, speed: String) {
        self.id = id
        self.title = title
        self.detail = detail
        self.progress = progress
        self.speed = speed
    }
}

public struct NewsItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let detail: String

    public init(id: String, title: String, detail: String) {
        self.id = id
        self.title = title
        self.detail = detail
    }
}

public struct DetailItem: Identifiable, Equatable, Sendable {
    public let label: String
    public let value: String
    public var id: String { label }

    public init(label: String, value: String) {
        self.label = label
        self.value = value
    }
}

/// Static fixtures for the M1 shell. The values mirror the pen screens and the
/// previous React prototype; every page replaces them with FFI data later.
public enum SampleData {
    public static let account = Account(
        id: "steve-fog",
        name: "Steve_Fog",
        initial: "S",
        kind: "Microsoft 正版",
        isOnline: true
    )

    public static let currentInstanceID = "fog-forest"

    public static let instances: [Instance] = makeInstances()

    public static var currentInstance: Instance {
        instances.first { $0.id == currentInstanceID } ?? instances[0]
    }

    public static var recentInstances: [Instance] {
        Array(
            instances
                .sorted { lhs, rhs in
                    (lhs.lastPlayed ?? .distantPast) > (rhs.lastPlayed ?? .distantPast)
                }
                .prefix(5)
        )
    }

    public static var favoriteInstances: [Instance] {
        instances.filter(\.isFavorite)
    }

    public static let downloads: [DownloadTask] = [
        DownloadTask(
            id: "sodium",
            title: "Sodium 0.6.5 · 1.21.4",
            detail: "3.1 / 4.2 MB",
            progress: 0.74,
            speed: "6.8 MB/s"
        ),
        DownloadTask(
            id: "create-resources",
            title: "机械动力 6 · 资源补全",
            detail: "312 / 1,240 文件",
            progress: 0.25,
            speed: "11.6 MB/s"
        ),
    ]

    public static let news: [NewsItem] = [
        NewsItem(
            id: "release-1.21.5",
            title: "Java 版 1.21.5 正式发布",
            detail: "新增生物群系变种 · 今天"
        ),
        NewsItem(
            id: "snapshot-25w14a",
            title: "快照 25w14a",
            detail: "实验性功能 · 3 天前"
        ),
    ]

    public static func details(for instance: Instance) -> [DetailItem] {
        [
            DetailItem(
                label: "模组",
                value: instance.modCount.map { "\($0) 个已启用 · 3 个可更新" } ?? "未安装"
            ),
            DetailItem(label: "资源包", value: "Faithful 32x"),
            DetailItem(label: "光影", value: "Complementary Reimagined"),
            DetailItem(label: "世界", value: "3 个存档"),
            DetailItem(label: "Java", value: "Temurin 21 · 6 GB"),
            DetailItem(label: "占用空间", value: InstanceFormat.byteSize(instance.sizeBytes)),
            DetailItem(label: "游玩时长", value: instance.playTimeText),
        ]
    }

    private static func makeInstances() -> [Instance] {
        let now = Date()

        func hoursAgo(_ hours: Double) -> Date {
            now.addingTimeInterval(-hours * 3_600)
        }

        func daysAgo(_ days: Double) -> Date {
            now.addingTimeInterval(-days * 86_400)
        }

        return [
            Instance(
                id: "fog-forest",
                name: "雾林生存",
                loader: .fabric,
                loaderVersion: "0.16.9",
                minecraftVersion: "1.21.4",
                modCount: 42,
                sizeBytes: 3_100_000_000,
                lastPlayed: hoursAgo(2),
                isFavorite: true,
                iconImageName: "instance-fog",
                iconSymbolName: "tree.fill",
                palette: .forest,
                playTimeMinutes: 7_560
            ),
            Instance(
                id: "create-6",
                name: "机械动力 6",
                loader: .forge,
                loaderVersion: "47.2.0",
                minecraftVersion: "1.20.1",
                modCount: 186,
                sizeBytes: 4_800_000_000,
                lastPlayed: daysAgo(1),
                isFavorite: true,
                iconSymbolName: "gearshape.2.fill",
                palette: .copper,
                playTimeMinutes: 5_280
            ),
            Instance(
                id: "vanilla",
                name: "原版纯净",
                loader: .vanilla,
                loaderVersion: "",
                minecraftVersion: "1.21.4",
                modCount: nil,
                sizeBytes: 410_000_000,
                lastPlayed: daysAgo(3),
                iconSymbolName: "cube.fill",
                palette: .slate,
                playTimeMinutes: 720
            ),
            Instance(
                id: "sky-tech",
                name: "空岛科技",
                loader: .neoforge,
                loaderVersion: "21.1.72",
                minecraftVersion: "1.19.2",
                modCount: 97,
                sizeBytes: 3_600_000_000,
                lastPlayed: daysAgo(7),
                iconSymbolName: "cloud.fill",
                palette: .violet,
                playTimeMinutes: 3_240
            ),
            Instance(
                id: "nostalgia",
                name: "怀旧 1.8.9",
                loader: .optifine,
                loaderVersion: "HD U M5",
                minecraftVersion: "1.8.9",
                modCount: nil,
                sizeBytes: 620_000_000,
                lastPlayed: daysAgo(21),
                iconSymbolName: "clock.fill",
                palette: .clay,
                playTimeMinutes: 1_860
            ),
            Instance(
                id: "deep-sea",
                name: "深海探索",
                loader: .fabric,
                loaderVersion: "0.16.9",
                minecraftVersion: "1.21.1",
                modCount: 28,
                sizeBytes: 1_900_000_000,
                lastPlayed: daysAgo(35),
                iconSymbolName: "water.waves",
                palette: .lagoon,
                playTimeMinutes: 1_200
            ),
            Instance(
                id: "architecture",
                name: "建筑灵感",
                loader: .fabric,
                loaderVersion: "0.16.9",
                minecraftVersion: "1.21.4",
                modCount: 12,
                sizeBytes: 860_000_000,
                lastPlayed: daysAgo(65),
                iconSymbolName: "building.2.fill",
                palette: .sand,
                playTimeMinutes: 480
            ),
            Instance(
                id: "twilight",
                name: "暮色森林",
                loader: .forge,
                loaderVersion: "47.2.0",
                minecraftVersion: "1.20.1",
                modCount: 64,
                sizeBytes: 2_200_000_000,
                lastPlayed: daysAgo(70),
                iconSymbolName: "tree.fill",
                palette: .moss,
                playTimeMinutes: 2_520
            ),
            Instance(
                id: "snapshot",
                name: "快照 25w14a",
                loader: .vanilla,
                loaderVersion: "",
                minecraftVersion: "25w14a",
                modCount: nil,
                sizeBytes: 380_000_000,
                lastPlayed: nil,
                iconSymbolName: "sparkles",
                palette: .ash
            ),
        ]
    }
}
