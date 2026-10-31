import SwiftUI

enum AppSection: String, CaseIterable, Identifiable {
    case home, library, servers, downloads, java, accounts, settings

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
        }
    }

    var group: String {
        switch self {
        case .home, .library, .servers: "启动"
        case .downloads, .java: "资源"
        case .accounts, .settings: "系统"
        }
    }
}

struct ContentView: View {
    @State private var selection: AppSection? = .home

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    Text("雾雨")
                        .font(.system(size: 22, weight: .semibold))
                        .tracking(2)
                        .foregroundStyle(FGToken.textPrimary)
                        .padding(.vertical, 18)
                    ForEach(AppSection.allCases.filter { $0.group == "启动" }) { section in
                        sidebarRow(section)
                    }
                }
                Section("资源") {
                    ForEach(AppSection.allCases.filter { $0.group == "资源" }) { section in
                        sidebarRow(section)
                    }
                }
                Section("系统") {
                    ForEach(AppSection.allCases.filter { $0.group == "系统" }) { section in
                        sidebarRow(section)
                    }
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)
            .background(FGToken.sidebar)
            .navigationSplitViewColumnWidth(min: 210, ideal: 240, max: 280)
        } detail: {
            detail(for: selection ?? .home)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(FGToken.content)
        }
        .navigationSplitViewStyle(.balanced)
        .tint(FGToken.accent)
    }

    private func sidebarRow(_ section: AppSection) -> some View {
        Label(section.title, systemImage: section.symbol)
            .font(FGToken.uiFont)
            .tag(section)
    }

    @ViewBuilder
    private func detail(for section: AppSection) -> some View {
        switch section {
        case .home:
            HomePlaceholder()
        default:
            ContentUnavailableView(
                section.title,
                systemImage: section.symbol,
                description: Text("这一页将在原生 UI 的后续里程碑接入。")
            )
        }
    }
}

private struct HomePlaceholder: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("主页").font(.system(size: 28, weight: .semibold))
                    Text("原生 SwiftUI 外壳已就绪").font(.system(size: 12)).foregroundStyle(.secondary)
                }
                ZStack(alignment: .bottomLeading) {
                    Image("hero-forest", bundle: .module)
                        .resizable()
                        .scaledToFill()
                        .frame(height: 330)
                        .clipped()
                    LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .top, endPoint: .bottom)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("雾林生存").font(.system(size: 30, weight: .semibold))
                        Text("Java 版 1.21.4 · Fabric 0.16.9 · 42 个模组")
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    .padding(24)
                }
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                Text("布局只作参考；后续每个平台使用自己的原生控件和交互。")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
            }
            .padding(24)
        }
    }
}
