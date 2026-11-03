import Foundation

/// Sort options offered by the library toolbar. The pen groups rows by loader
/// first and applies this order inside each group.
public enum LibrarySort: String, CaseIterable, Identifiable, Sendable {
    case recent
    case name
    case size

    public var id: String { rawValue }

    public var title: String {
        switch self {
        case .recent: "最近游玩"
        case .name: "名称"
        case .size: "占用空间"
        }
    }

    public func sorted(_ instances: [Instance]) -> [Instance] {
        switch self {
        case .recent:
            instances.sorted { lhs, rhs in
                (lhs.lastPlayed ?? .distantPast) > (rhs.lastPlayed ?? .distantPast)
            }
        case .name:
            instances.sorted {
                $0.name.localizedStandardCompare($1.name) == .orderedAscending
            }
        case .size:
            instances.sorted { $0.sizeBytes > $1.sizeBytes }
        }
    }
}

public struct LibrarySection: Identifiable, Sendable {
    public let group: LibraryGroup
    public let instances: [Instance]

    public var id: LibraryGroup { group }

    public init(group: LibraryGroup, instances: [Instance]) {
        self.group = group
        self.instances = instances
    }
}

public enum LibraryOrganizer {
    /// Sections in the pen order (Fabric, Forge, NeoForge, 原版), skipping empty
    /// groups so filtering does not leave orphan headers.
    public static func sections(from instances: [Instance], sort: LibrarySort) -> [LibrarySection] {
        LibraryGroup.allCases.compactMap { group in
            let matches = instances.filter { $0.group == group }
            guard !matches.isEmpty else { return nil }
            return LibrarySection(group: group, instances: sort.sorted(matches))
        }
    }
}
