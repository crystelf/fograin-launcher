import Foundation

/// Mod loader families the launcher knows about.
public enum LoaderKind: String, CaseIterable, Codable, Identifiable, Sendable {
    case vanilla
    case optifine
    case fabric
    case quilt
    case forge
    case neoforge

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .vanilla: "原版"
        case .optifine: "OptiFine"
        case .fabric: "Fabric"
        case .quilt: "Quilt"
        case .forge: "Forge"
        case .neoforge: "NeoForge"
        }
    }

    /// SF Symbol used for the instance tile and loader badges.
    public var symbolName: String {
        switch self {
        case .vanilla: "cube.fill"
        case .optifine: "sparkles"
        case .fabric: "puzzlepiece.fill"
        case .quilt: "square.grid.2x2.fill"
        case .forge: "hammer.fill"
        case .neoforge: "flame.fill"
        }
    }

    /// Library grouping follows the pen instance library: OptiFine sits with
    /// vanilla and Quilt with Fabric, keeping the four groups of the design.
    public var libraryGroup: LibraryGroup {
        switch self {
        case .fabric, .quilt: .fabric
        case .forge: .forge
        case .neoforge: .neoforge
        case .vanilla, .optifine: .vanilla
        }
    }
}

/// The four groups shown in the pen's instance library.
public enum LibraryGroup: String, CaseIterable, Identifiable, Sendable {
    case fabric
    case forge
    case neoforge
    case vanilla

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .fabric: "Fabric"
        case .forge: "Forge"
        case .neoforge: "NeoForge"
        case .vanilla: "原版"
        }
    }
}

/// Filter above the library table. `all` and `favorites` are not loader groups.
public enum LibraryScope: String, CaseIterable, Identifiable, Sendable {
    case all
    case favorites
    case fabric
    case forge
    case neoforge
    case vanilla

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .all: "全部实例"
        case .favorites: "收藏"
        case .fabric: "Fabric"
        case .forge: "Forge"
        case .neoforge: "NeoForge"
        case .vanilla: "原版"
        }
    }

    public var symbolName: String {
        switch self {
        case .all: "square.grid.2x2"
        case .favorites: "star"
        case .fabric, .forge, .neoforge: "shippingbox"
        case .vanilla: "cube"
        }
    }

    public var group: LibraryGroup? {
        switch self {
        case .all, .favorites: nil
        case .fabric: .fabric
        case .forge: .forge
        case .neoforge: .neoforge
        case .vanilla: .vanilla
        }
    }
}
