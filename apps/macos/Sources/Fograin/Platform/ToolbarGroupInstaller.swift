import AppKit

/// Installs a native `NSToolbarItemGroup` for the instance actions. SwiftUI's
/// `ControlGroup` bridges to an AppKit group whose subitems are custom views,
/// which the toolbar renders as empty segments; constructing the group with
/// `images:` gives the system's native segmented capsule instead.
@MainActor
final class ToolbarGroupInstaller: NSObject, NSToolbarDelegate {
    static let shared = ToolbarGroupInstaller()

    private static let groupIdentifier = NSToolbarItem.Identifier("com.fograin.instance-actions")
    private static let searchIdentifier = "library-search"

    private let actionsTarget = InstanceActionsTarget()
    private weak var window: NSWindow?
    private weak var toolbar: NSToolbar?
    private var originalDelegate: NSToolbarDelegate?
    private var showsActions = false

    func install(on window: NSWindow, showsActions: Bool) {
        self.window = window
        self.showsActions = showsActions

        guard let toolbar = window.toolbar else { return }
        self.toolbar = toolbar

        if toolbar.delegate !== self {
            originalDelegate = toolbar.delegate
            toolbar.delegate = self
        }

        sync()
    }

    private func makeGroup() -> NSToolbarItemGroup {
        let images = [
            NSImage(systemSymbolName: "plus", accessibilityDescription: "新建实例"),
            NSImage(systemSymbolName: "square.and.arrow.down", accessibilityDescription: "导入整合包"),
            NSImage(systemSymbolName: "folder", accessibilityDescription: "打开数据目录"),
        ].compactMap { $0 }

        let group = NSToolbarItemGroup(
            itemIdentifier: Self.groupIdentifier,
            images: images,
            selectionMode: .momentary,
            labels: ["新建实例", "导入整合包", "打开数据目录"],
            target: actionsTarget,
            action: #selector(InstanceActionsTarget.perform(_:))
        )
        group.label = "实例操作"
        group.paletteLabel = "实例操作"
        group.controlRepresentation = .expanded
        return group
    }

    private func sync() {
        guard let toolbar else { return }

        let groupIndex = toolbar.items.firstIndex {
            $0.itemIdentifier == Self.groupIdentifier
        }

        guard showsActions,
              let searchIndex = toolbar.items.firstIndex(where: {
                  $0.itemIdentifier.rawValue == Self.searchIdentifier
              }) else {
            if let groupIndex {
                toolbar.removeItem(at: groupIndex)
            }
            return
        }

        if let groupIndex, groupIndex == searchIndex - 1 {
            return
        }

        if let groupIndex {
            toolbar.removeItem(at: groupIndex)
        }

        guard let currentSearchIndex = toolbar.items.firstIndex(where: {
            $0.itemIdentifier.rawValue == Self.searchIdentifier
        }) else {
            return
        }
        toolbar.insertItem(withItemIdentifier: Self.groupIdentifier, at: currentSearchIndex)
    }

    // MARK: - NSToolbarDelegate

    func toolbar(
        _ toolbar: NSToolbar,
        itemForItemIdentifier itemIdentifier: NSToolbarItem.Identifier,
        willBeInsertedIntoToolbar flag: Bool
    ) -> NSToolbarItem? {
        if itemIdentifier == Self.groupIdentifier {
            return makeGroup()
        }
        return originalDelegate?.toolbar?(
            toolbar,
            itemForItemIdentifier: itemIdentifier,
            willBeInsertedIntoToolbar: flag
        )
    }

    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        var identifiers = originalDelegate?.toolbarAllowedItemIdentifiers?(toolbar) ?? []
        if !identifiers.contains(Self.groupIdentifier) {
            identifiers.append(Self.groupIdentifier)
        }
        return identifiers
    }

    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        var identifiers = originalDelegate?.toolbarDefaultItemIdentifiers?(toolbar) ?? []
        guard showsActions,
              let searchIndex = identifiers.firstIndex(where: {
                  $0.rawValue == Self.searchIdentifier
              }) else {
            return identifiers
        }
        if !identifiers.contains(Self.groupIdentifier) {
            identifiers.insert(Self.groupIdentifier, at: searchIndex)
        }
        return identifiers
    }

    func toolbarSelectableItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] {
        originalDelegate?.toolbarSelectableItemIdentifiers?(toolbar) ?? []
    }

    func toolbarImmovableItemIdentifiers(_ toolbar: NSToolbar) -> Set<NSToolbarItem.Identifier> {
        originalDelegate?.toolbarImmovableItemIdentifiers?(toolbar) ?? []
    }

    func toolbarWillAddItem(_ notification: Notification) {
        originalDelegate?.toolbarWillAddItem?(notification)
    }

    func toolbarDidRemoveItem(_ notification: Notification) {
        originalDelegate?.toolbarDidRemoveItem?(notification)
    }
}

@MainActor
private final class InstanceActionsTarget: NSObject {
    @objc func perform(_ sender: NSToolbarItemGroup) {
        switch sender.selectedIndex {
        case 0:
            AppModel.shared.newInstance()
        case 1:
            AppModel.shared.importModpack()
        case 2:
            AppModel.shared.openDataRoot()
        default:
            break
        }
    }
}
