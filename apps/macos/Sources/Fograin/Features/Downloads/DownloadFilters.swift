import Foundation

/// Version families shown in the titlebar dropdown on the 游戏 tab.
enum DownloadVersionChannel: String, CaseIterable, Identifiable {
    case release
    case snapshot
    case aprilFools
    case old

    var id: String { rawValue }

    var title: String {
        switch self {
        case .release: "正式版"
        case .snapshot: "快照"
        case .aprilFools: "愚人节"
        case .old: "远古版本"
        }
    }

    /// `kind` argument understood by the Rust `versions.list` RPC.
    var kindValue: String {
        switch self {
        case .release: "release"
        case .snapshot: "snapshot"
        case .aprilFools: "april_fools"
        case .old: "old"
        }
    }

    var emptyMessage: String {
        switch self {
        case .release: "暂无正式版"
        case .snapshot: "暂无快照"
        case .aprilFools: "暂无愚人节版本"
        case .old: "暂无远古版本"
        }
    }
}

/// Loader filter shown in the same titlebar slot on 模组 / 整合包 tabs.
/// The actual Modrinth/CurseForge search lands in M8/M9; the filter is wired
/// now so the toolbar contract and state do not change later.
enum ContentLoaderFilter: String, CaseIterable, Identifiable {
    case all
    case forge
    case neoforge
    case fabric
    case quilt
    case optifine

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "全部加载器"
        case .forge: "Forge"
        case .neoforge: "NeoForge"
        case .fabric: "Fabric"
        case .quilt: "Quilt"
        case .optifine: "OptiFine"
        }
    }
}
