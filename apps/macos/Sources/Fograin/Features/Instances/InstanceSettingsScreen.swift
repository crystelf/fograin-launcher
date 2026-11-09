import FograinKit
import SwiftUI

/// Instance detail · 游戏设置 (M4 scope): the 启动/内存/显示/启动行为 form follows
/// the pen's right-hand column. 内容量 sections that M8/M9 own render as
/// disabled rows so the structure matches the design without stub logic.
struct InstanceSettingsScreen: View {
    let instance: Instance
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var overrides = InstanceOverridesWire()
    @State private var globals = CoreGameConfig(
        username: "Steve",
        memoryMb: 0,
        jvmArgs: "",
        resolution: nil,
        fullscreen: false,
        showLog: false,
        autoCompleteResources: true
    )
    @State private var loadedInstanceID = ""
    @State private var loadError: String?

    /// Overrides as the core reports them; `nil` means inherit.
    struct InstanceOverridesWire: Equatable {
        var java: String?
        var memoryMb: Int?
        var jvmArgs: String?
        var resolution: [Int]?
        var fullscreen: Bool?
    }

    private var effectiveMemoryMB: Int {
        overrides.memoryMb ?? (Int(globals.memoryMb) == 0 ? 4_096 : Int(globals.memoryMb))
    }

    private var effectiveMemoryGB: Int {
        max(1, effectiveMemoryMB / 1024)
    }

    private var effectiveResolution: String {
        if let overrides = overrides.resolution {
            return "\(overrides[0]) × \(overrides[1])"
        }
        if let globals = globals.resolution {
            return "\(globals[0]) × \(globals[1])（全局）"
        }
        return "默认"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            FGSeparator()
            OverviewRow(instance: instance)
                .padding(.horizontal, FGSpacing.page)
                .padding(.vertical, FGSpacing.lg)
            FGSeparator()
            form
        }
        .frame(width: 720, height: 640)
        .background(FG.background)
        .onAppear(perform: reload)
        .onChange(of: instance.id) {
            reload()
        }
    }

    private var header: some View {
        HStack(alignment: .center, spacing: FGSpacing.lg) {
            LoaderTile(loader: instance.loader, size: 34)
            VStack(alignment: .leading, spacing: 1) {
                Text(instance.name)
                    .font(FGFont.headingBold)
                    .foregroundStyle(FG.textPrimary)
                Text("游戏设置 · \(instance.name) 的启动与显示选项")
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
            Spacer(minLength: FGSpacing.md)
            Button("关闭") { dismiss() }
                .keyboardShortcut(.cancelAction)
        }
        .padding(.horizontal, FGSpacing.page)
        .padding(.vertical, FGSpacing.lg)
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FGSpacing.page) {
                if let loadError {
                    HStack(alignment: .top, spacing: FGSpacing.sm) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(FG.danger)
                        Text(loadError)
                            .font(FGFont.small)
                            .foregroundStyle(FG.textPrimary)
                    }
                    .padding(FGSpacing.md)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(FG.danger.opacity(0.10))
                    )
                }
                launchSection
                FGSeparator()
                displaySection
                FGSeparator()
                contentSection
                FGSeparator()
                maintenanceSection
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, FGSpacing.page)
            .padding(.vertical, FGSpacing.lg)
        }
    }

    private var launchSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.lg) {
            Text("启动")
                .font(FGFont.subheadBold)
                .foregroundStyle(FG.textPrimary)

            settingsRow("内存") {
                Picker("内存", selection: memorySelection) {
                    ForEach(memoryChoices, id: \.self) { value in
                        Text(value == 0 ? "自动" : "\(value) GB").tag(value)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 140)
            }

            settingsRow("JVM 参数") {
                TextField("追加到启动命令的自定义参数", text: jvmBinding)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 280)
            }

            settingsRow("Java 运行时") {
                Text(overrides.java ?? "自动发现")
                    .font(FGFont.small)
                    .foregroundStyle(FG.textSecondary)
            }

            settingsRow("启动行为") {
                VStack(alignment: .leading, spacing: FGSpacing.sm) {
                    Toggle(isOn: fullscreenBinding) { Text("启动时全屏") }
                        .font(FGFont.small)
                }
            }
        }
    }

    private var displaySection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.lg) {
            Text("显示")
                .font(FGFont.subheadBold)
                .foregroundStyle(FG.textPrimary)

            settingsRow("窗口分辨率") {
                Picker("分辨率", selection: resolutionSelection) {
                    Text("跟随游戏默认").tag(0)
                    ForEach(resolutionChoices, id: \.value) { option in
                        Text("\(option.width) × \(option.height)").tag(option.value)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .frame(width: 200)
            }
        }
    }

    private var contentSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            Text("内容")
                .font(FGFont.subheadBold)
                .foregroundStyle(FG.textPrimary)
            ForEach(contentRows, id: \.title) { row in
                HStack(spacing: FGSpacing.md) {
                    Text(row.title)
                        .font(FGFont.small)
                        .foregroundStyle(FG.textPrimary)
                    Spacer(minLength: 0)
                    Text(row.milestone)
                        .font(FGFont.captionMedium)
                        .foregroundStyle(FG.accent)
                        .padding(.horizontal, FGSpacing.sm)
                        .padding(.vertical, FGSpacing.xxs)
                        .background(Capsule().fill(FG.accent.opacity(0.12)))
                }
                .frame(height: 28)
            }
        }
    }

    private var maintenanceSection: some View {
        VStack(alignment: .leading, spacing: FGSpacing.md) {
            Text("维护")
                .font(FGFont.subheadBold)
                .foregroundStyle(FG.textPrimary)
            HStack(spacing: FGSpacing.md) {
                Button {
                    model.openInstanceFolder(instance)
                } label: {
                    Label("打开实例目录", systemImage: "folder")
                }
                .controlSize(.small)

                Button {
                    model.deleteInstance(instance)
                    dismiss()
                } label: {
                    Label("删除实例…", systemImage: "trash")
                }
                .controlSize(.small)

                Spacer(minLength: 0)
            }
        }
    }

    // MARK: settings plumbing

    private func settingsRow<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: FGSpacing.md) {
            Text(title)
                .font(FGFont.small)
                .foregroundStyle(FG.textSecondary)
                .frame(width: 96, alignment: .leading)
            content()
            Spacer(minLength: 0)
        }
    }

    private var memoryChoices: [Int] { [0, 2, 4, 6, 8, 12, 16] }

    private var memorySelection: Binding<Int> {
        Binding(
            get: { overrides.memoryMb.map { Int($0) / 1024 } ?? 0 },
            set: { value in
                overrides.memoryMb = value == 0 ? nil : value * 1024
                persistMemory(value)
            }
        )
    }

    private func persistMemory(_ value: Int) {
        model.configureInstance(
            id: instance.id,
            setting: "memory",
            value: value == 0 ? nil : value * 1024
        )
    }

    private var jvmBinding: Binding<String> {
        Binding(
            get: { overrides.jvmArgs ?? "" },
            set: { text in
                overrides.jvmArgs = text
                persistJvm(text)
            }
        )
    }

    private func persistJvm(_ text: String) {
        model.configureInstance(id: instance.id, setting: "jvmArgs", value: text.isEmpty ? nil : text)
    }

    private var fullscreenBinding: Binding<Bool> {
        Binding(
            get: { overrides.fullscreen ?? false },
            set: { value in
                overrides.fullscreen = value ? true : nil
                model.configureInstance(id: instance.id, setting: "fullscreen", value: value ? true : nil)
            }
        )
    }

    private struct ResolutionOption {
        let width: Int
        let height: Int
        var value: Int { width }
    }

    private var resolutionChoices: [ResolutionOption] { [
        ResolutionOption(width: 1280, height: 720),
        ResolutionOption(width: 1600, height: 900),
        ResolutionOption(width: 1920, height: 1080),
        ResolutionOption(width: 2560, height: 1440),
    ] }

    private var resolutionSelection: Binding<Int> {
        Binding(
            get: { overrides.resolution.map { Int($0[0]) } ?? 0 },
            set: { value in
                if value == 0 {
                    overrides.resolution = nil
                    model.configureInstance(id: instance.id, setting: "resolution", value: nil)
                } else if let option = resolutionChoices.first(where: { $0.width == value }) {
                    overrides.resolution = [option.width, option.height]
                    model.configureInstance(
                        id: instance.id,
                        setting: "resolution",
                        value: [option.width, option.height]
                    )
                }
            }
        )
    }

    private var contentRows: [(title: String, milestone: String)] { [
        ("模组管理", "M8"),
        ("资源包管理", "M8"),
        ("光影包管理", "M8"),
        ("世界管理", "M9"),
        ("原理图管理", "M9"),
        ("更新整合包", "M9"),
        ("日志与崩溃报告", "M9"),
        ("导出与备份", "M9"),
    ] }

    private func reload() {
        guard loadedInstanceID != instance.id else { return }
        loadedInstanceID = instance.id
        do {
            let payload = try CoreBridge.rpc("instances.settings.get", params: ["id": instance.id])
            if let effective = payload["effective"] as? [String: Any] {
                if let memory = effective["memoryMb"] as? NSNumber {
                    globals = CoreGameConfig(
                        username: globals.username,
                        memoryMb: memory.uint32Value,
                        jvmArgs: globals.jvmArgs,
                        resolution: globals.resolution,
                        fullscreen: globals.fullscreen,
                        showLog: globals.showLog,
                        autoCompleteResources: globals.autoCompleteResources
                    )
                }
            }
            if let raw = payload["overrides"] as? [String: Any] {
                overrides = InstanceOverridesWire(
                    java: raw["java"] as? String,
                    memoryMb: (raw["memoryMb"] as? NSNumber)?.intValue,
                    jvmArgs: raw["jvmArgs"] as? String,
                    resolution: (raw["resolution"] as? [NSNumber]).map { $0.map { $0.intValue } },
                    fullscreen: raw["fullscreen"] as? Bool
                )
            }
        } catch {
            let message = (error as? CoreBridgeError)?.message ?? error.localizedDescription
            loadError = message
            model.showError("无法读取实例设置", error)
        }
    }
}

/// Compact top bar inside the settings sheet.
private struct OverviewRow: View {
    let instance: Instance

    var body: some View {
        HStack(spacing: FGSpacing.md) {
            InstanceTile(instance: instance, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(instance.displayVersion)
                    .font(FGFont.bodyMedium)
                    .foregroundStyle(FG.textPrimary)
                Text(instance.homeDetail)
                    .font(FGFont.caption)
                    .foregroundStyle(FG.textTertiary)
            }
            Spacer(minLength: 0)
        }
    }
}
