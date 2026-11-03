import Foundation

/// Scope plus free-text query applied to the instance library.
public struct LibraryFilter: Equatable, Sendable {
    public var scope: LibraryScope
    public var query: String

    public init(scope: LibraryScope = .all, query: String = "") {
        self.scope = scope
        self.query = query
    }

    public var isActive: Bool {
        scope != .all || !normalizedQuery.isEmpty
    }

    public func apply(to instances: [Instance]) -> [Instance] {
        let needle = normalizedQuery

        return instances.filter { instance in
            guard matchesScope(instance) else { return false }
            guard !needle.isEmpty else { return true }
            return instance.searchText.localizedCaseInsensitiveContains(needle)
        }
    }

    private var normalizedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func matchesScope(_ instance: Instance) -> Bool {
        switch scope {
        case .all:
            true
        case .favorites:
            instance.isFavorite
        default:
            scope.group.map { instance.group == $0 } ?? true
        }
    }
}
