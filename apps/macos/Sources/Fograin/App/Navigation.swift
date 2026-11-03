import SwiftUI

/// Sidebar sections and their page metadata.
enum AppSection: String, CaseIterable, Identifiable, Hashable {
    case home
    case library
    case servers
    case downloads
    case java
    case accounts
    case settings
    case about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: "主页"
        case .library: "实例库"
        case .servers: "服务器"
        case .downloads: "下载"
        case .java: "Java 运行时"
        case .accounts: "账户"
        case .settings: "设置"
        case .about: "关于"
        }
    }

    var pageSubtitle: String {
        switch self {
        case .home: "启动上次游玩的实例"
        case .library: "管理所有实例、模组与存档"
        case .servers: "收藏、局域网发现与状态 Ping"
        case .downloads: "下载队列与资源搜索"
        case .java: "扫描、安装与测试运行"
        case .accounts: "离线、外置登录与皮肤"
        case .settings: "外观与全局设置"
        case .about: "版本、构建与致谢"
        }
    }

    var symbol: String {
        switch self {
        case .home: "house"
        case .library: "shippingbox"
        case .servers: "server.rack"
        case .downloads: "arrow.down.circle"
        case .java: "cup.and.saucer"
        case .accounts: "person.crop.circle"
        case .settings: "gearshape"
        case .about: "info.circle"
        }
    }

    var group: SidebarGroup {
        switch self {
        case .home, .library, .servers: .launch
        case .downloads, .java: .resources
        case .accounts, .settings, .about: .system
        }
    }

    /// One-based position used for the ⌘1…⌘8 navigation shortcuts.
    var shortcutNumber: Int {
        (AppSection.allCases.firstIndex(of: self) ?? 0) + 1
    }

    var keyEquivalent: KeyEquivalent {
        KeyEquivalent(Character(String(shortcutNumber)))
    }

    var milestone: Milestone? {
        switch self {
        case .servers:
            Milestone(
                name: "M9",
                detail: "服务器列表、SLP Ping 与局域网发现将在 M9 接入。"
            )
        case .downloads:
            Milestone(
                name: "M8",
                detail: "资源浏览、依赖解析与下载队列将在 M8 接入。"
            )
        case .java:
            Milestone(
                name: "M6",
                detail: "Java 扫描、运行时下载与测试运行将在 M6 接入。"
            )
        case .accounts:
            Milestone(
                name: "M7",
                detail: "离线与外置登录将在 M7 接入；微软正版登录在 M11。"
            )
        case .settings:
            Milestone(
                name: "M10",
                detail: "完整设置页将在 M10 补齐；外观已经可用。"
            )
        case .home, .library, .about:
            nil
        }
    }
}

enum SidebarGroup: String, CaseIterable, Identifiable {
    case launch = "启动"
    case resources = "资源"
    case system = "系统"

    var id: String { rawValue }

    var title: String { rawValue }

    var sections: [AppSection] {
        AppSection.allCases.filter { $0.group == self }
    }
}

struct Milestone: Hashable {
    let name: String
    let detail: String
}
