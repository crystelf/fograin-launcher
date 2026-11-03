import Foundation

/// A launcher instance shown by the M1 shell.
///
/// This is presentation data backed by static fixtures for now. M4 replaces
/// `SampleData.instances` with `instance_list` from the FFI without changing
/// the view layer.
public struct Instance: Identifiable, Hashable, Sendable {
    /// Accent family for the generated instance tile.
    public enum Palette: String, CaseIterable, Sendable {
        case forest
        case copper
        case slate
        case violet
        case clay
        case lagoon
        case sand
        case moss
        case ash
    }

    public let id: String
    public let name: String
    public let loader: LoaderKind
    public let loaderVersion: String
    public let minecraftVersion: String
    public let modCount: Int?
    public let sizeBytes: Int64
    public let lastPlayed: Date?
    public let isFavorite: Bool
    /// Bitmap icon in the app bundle; falls back to `iconSymbolName`.
    public let iconImageName: String?
    /// SF Symbol used when there is no bitmap icon.
    public let iconSymbolName: String?
    public let palette: Palette
    public let playTimeMinutes: Int?

    public init(
        id: String,
        name: String,
        loader: LoaderKind,
        loaderVersion: String,
        minecraftVersion: String,
        modCount: Int?,
        sizeBytes: Int64,
        lastPlayed: Date?,
        isFavorite: Bool = false,
        iconImageName: String? = nil,
        iconSymbolName: String? = nil,
        palette: Palette,
        playTimeMinutes: Int? = nil
    ) {
        self.id = id
        self.name = name
        self.loader = loader
        self.loaderVersion = loaderVersion
        self.minecraftVersion = minecraftVersion
        self.modCount = modCount
        self.sizeBytes = sizeBytes
        self.lastPlayed = lastPlayed
        self.isFavorite = isFavorite
        self.iconImageName = iconImageName
        self.iconSymbolName = iconSymbolName
        self.palette = palette
        self.playTimeMinutes = playTimeMinutes
    }

    public var group: LibraryGroup { loader.libraryGroup }

    /// `1.21.4 · Fabric 0.16.9`, or just the Minecraft version for vanilla.
    public var displayVersion: String {
        if loaderVersion.isEmpty {
            return minecraftVersion
        }
        return "\(minecraftVersion) · \(loader.title) \(loaderVersion)"
    }

    public var modsText: String {
        modCount.map(String.init) ?? "—"
    }

    /// `1.21.4 · Fabric · 42 模组` used by the home instance rows.
    public var homeDetail: String {
        var parts = [minecraftVersion]
        if loader != .vanilla {
            parts.append(loader.title)
        } else {
            parts.append("原版")
        }
        if let modCount {
            parts.append("\(modCount) 模组")
        }
        return parts.joined(separator: " · ")
    }

    public var iconSymbol: String {
        iconSymbolName ?? loader.symbolName
    }

    /// Sort key so unmodded instances sort below modded ones.
    public var modCountForSort: Int {
        modCount ?? 0
    }

    public var playTimeText: String {
        InstanceFormat.playTime(playTimeMinutes)
    }

    var searchText: String {
        [
            name,
            minecraftVersion,
            loader.title,
            loaderVersion,
        ].joined(separator: " ")
    }
}
