import AppKit
import FograinKit
import SwiftUI

struct AppCommands: Commands {
    @ObservedObject var model: AppModel

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("新建实例") {
                model.newInstance()
            }
            .keyboardShortcut("n")

            Button("导入整合包…") {
                model.importModpack()
            }
            .keyboardShortcut("i", modifiers: [.command, .shift])

            Button("导入 .minecraft 目录…") {
                model.chooseMinecraftDirectory()
            }
            .keyboardShortcut("o", modifiers: [.command, .shift])
        }

        CommandGroup(after: .saveItem) {
            Divider()
            Button("打开数据目录") {
                model.openDataRoot()
            }
            .keyboardShortcut("d", modifiers: [.command, .shift])
        }

        CommandMenu("转到") {
            ForEach(AppSection.allCases) { section in
                Button(section.title) {
                    model.navigate(to: section)
                }
                .keyboardShortcut(section.keyEquivalent, modifiers: .command)
            }

            Divider()

            Button("搜索实例…") {
                model.focusLibrarySearch()
            }
            .keyboardShortcut("f")
        }

        CommandGroup(after: .toolbar) {
            Picker("外观", selection: themeSelection) {
                ForEach(ThemePreference.allCases) { theme in
                    Text(theme.title).tag(theme)
                }
            }
        }

        CommandGroup(replacing: .appInfo) {
            Button("关于雾雨启动器") {
                NSApplication.shared.orderFrontStandardAboutPanel(options: [
                    .applicationName: "雾雨启动器",
                    .applicationVersion: model.settings.appVersion,
                    .credits: NSAttributedString(
                        string: "Minecraft Java 版启动器\nRust 核心 · SwiftUI + AppKit"
                    ),
                ])
            }
        }

        CommandGroup(replacing: .help) {
            Button("雾雨启动器帮助") {
                model.showNotice("帮助与文档将在 M10 接入", symbol: "questionmark.circle")
            }
            Button("开源许可与致谢") {
                model.navigate(to: .about)
            }
        }
    }

    private var themeSelection: Binding<ThemePreference> {
        Binding(
            get: { model.themePreference },
            set: { model.setTheme($0) }
        )
    }
}
