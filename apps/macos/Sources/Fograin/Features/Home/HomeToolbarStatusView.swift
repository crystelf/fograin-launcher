import FograinKit
import SwiftUI

/// Scheme-style label in the home toolbar: instance icon (or a native circular
/// launch gauge), selected instance name, and version. It mirrors Xcode's
/// scheme item and lives to the right of the native play/stop capsule; clicking
/// it opens 游戏设置.
struct HomeToolbarStatusView: View {
    @ObservedObject var model: AppModel
    @State private var launchProgress = 0.0

    var body: some View {
        Button {
            model.manageCurrentInstance()
        } label: {
            content
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.currentInstance == nil)
        .help("游戏设置")
    }

    @ViewBuilder
    private var content: some View {
        Group {
            if let instance = model.currentInstance {
                HStack(spacing: 6) {
                    leadingGlyph(instance)

                    VStack(alignment: .leading, spacing: 0) {
                        Text(instance.name)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(FG.textPrimary)
                            .lineLimit(1)
                        Text(versionSubtitle(instance))
                            .font(.system(size: 10))
                            .foregroundStyle(FG.textSecondary)
                            .lineLimit(1)
                    }
                }
                .fixedSize(horizontal: true, vertical: false)
            } else {
                Text("未选择实例")
                    .font(.system(size: 11))
                    .foregroundStyle(FG.textSecondary)
            }
        }
        .padding(.horizontal, 4)
        .frame(height: 30)
    }

    @ViewBuilder
    private func leadingGlyph(_ instance: Instance) -> some View {
        if isLaunching {
            // Native circular capacity gauge, tinted with the launcher accent.
            // It animates while the game process starts; no hand-drawn ring.
            Gauge(value: launchProgress) {
                EmptyView()
            }
            .gaugeStyle(.accessoryCircular)
            .tint(FG.accent)
            .scaleEffect(0.31)
            .frame(width: 22, height: 22)
            .task(id: isLaunching) {
                guard isLaunching else {
                    launchProgress = 0
                    return
                }
                launchProgress = 0.08
                withAnimation(.easeOut(duration: 8)) {
                    launchProgress = 0.9
                }
            }
        } else {
            InstanceTile(instance: instance, size: 22)
        }
    }

    private var isLaunching: Bool {
        #if DEBUG
        if ProcessInfo.processInfo.environment["FOGRAIN_SNAPSHOT_LAUNCHING"] == "1" {
            return true
        }
        #endif
        return model.isSessionLoading
    }

    private func versionSubtitle(_ instance: Instance) -> String {
        if instance.loaderVersion.isEmpty, instance.loader == .vanilla {
            return "Java 版 \(instance.minecraftVersion)"
        }
        return "\(instance.minecraftVersion) · \(instance.loader.title)"
    }
}
