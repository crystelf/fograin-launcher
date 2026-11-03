import CFograinFFI
import Foundation
import os

/// Error payload used by every Rust FFI call (§3.1 of docs/design.md).
public struct CoreErrorPayload: Decodable, Equatable, Sendable {
    public let kind: String
    public let messageKey: String
    public let detail: String?

    public init(kind: String, messageKey: String, detail: String?) {
        self.kind = kind
        self.messageKey = messageKey
        self.detail = detail
    }

    public var localizedDescription: String {
        detail ?? messageKey
    }
}

/// Snapshot of the settings the shell needs before it renders its first frame.
public struct CoreSettingsSnapshot: Decodable, Equatable, Sendable {
    public let abiVersion: UInt32
    public let appVersion: String
    public let platform: String
    public let theme: ThemePreference
    public let dataRoot: String
    public let configPath: String
    public let configError: CoreErrorPayload?

    public init(
        abiVersion: UInt32,
        appVersion: String,
        platform: String,
        theme: ThemePreference,
        dataRoot: String,
        configPath: String,
        configError: CoreErrorPayload? = nil
    ) {
        self.abiVersion = abiVersion
        self.appVersion = appVersion
        self.platform = platform
        self.theme = theme
        self.dataRoot = dataRoot
        self.configPath = configPath
        self.configError = configError
    }

    /// Used when the Rust core is missing or returns malformed JSON so the
    /// shell can still start and report the problem.
    public static var fallback: CoreSettingsSnapshot {
        let root = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?
            .appendingPathComponent("Fograin", isDirectory: true)
            ?? FileManager.default.homeDirectoryForCurrentUser
                .appendingPathComponent("Library/Application Support/Fograin", isDirectory: true)

        return CoreSettingsSnapshot(
            abiVersion: 0,
            appVersion: "0.1.0",
            platform: "macos",
            theme: .system,
            dataRoot: root.path,
            configPath: root.appendingPathComponent("launcher.toml").path
        )
    }
}

/// Result envelope returned by `fg_settings_set_theme`.
public struct CoreWriteResult: Decodable, Equatable, Sendable {
    public let ok: Bool
    public let theme: ThemePreference?
    public let snapshot: CoreSettingsSnapshot?
    public let error: CoreErrorPayload?
}

public enum CoreBridgeError: Error, Equatable {
    case unavailable
    case rejected(CoreErrorPayload)
    case malformedResponse(String)

    public var message: String {
        switch self {
        case .unavailable:
            "无法连接启动器核心。"
        case let .rejected(payload):
            payload.localizedDescription
        case let .malformedResponse(detail):
            "核心返回了无法解析的数据：\(detail)"
        }
    }
}

/// The only place the macOS client talks to the Rust core.
///
/// All calls happen on the main actor at startup or from a settings write; the
/// command surface grows here as milestones land. Strings returned by the core
/// are always released through `fg_string_free`.
public enum CoreBridge {
    private static let logger = Logger(subsystem: "dev.fograin.launcher", category: "core")

    /// Version of the C ABI the core was built with.
    public static var abiVersion: UInt32 {
        fg_abi_version()
    }

    /// Reads `launcher.toml` through the core. Falls back to a usable snapshot
    /// if the library call fails; the failure is logged for diagnostics.
    public static func settingsSnapshot() -> CoreSettingsSnapshot {
        guard let pointer = fg_settings_snapshot() else {
            logger.error("fg_settings_snapshot returned NULL")
            return .fallback
        }
        defer { fg_string_free(pointer) }

        do {
            return try decode(CoreSettingsSnapshot.self, from: pointer)
        } catch {
            logger.error("settings snapshot decode failed: \(String(describing: error), privacy: .public)")
            return .fallback
        }
    }

    /// Persists the theme preference through the core and returns the refreshed
    /// snapshot.
    public static func setTheme(_ theme: ThemePreference) throws -> CoreSettingsSnapshot {
        guard let pointer = fg_settings_set_theme(theme.rawValue) else {
            throw CoreBridgeError.unavailable
        }
        defer { fg_string_free(pointer) }

        let result: CoreWriteResult
        do {
            result = try decode(CoreWriteResult.self, from: pointer)
        } catch {
            throw CoreBridgeError.malformedResponse(String(describing: error))
        }

        guard result.ok else {
            throw CoreBridgeError.rejected(
                result.error ?? CoreErrorPayload(
                    kind: "internal",
                    messageKey: "error.settings.write",
                    detail: nil
                )
            )
        }

        return result.snapshot ?? .fallback
    }

    private static func decode<T: Decodable>(
        _ type: T.Type,
        from pointer: UnsafeMutablePointer<CChar>
    ) throws -> T {
        let text = String(cString: pointer)
        guard let data = text.data(using: .utf8) else {
            throw CoreBridgeError.malformedResponse(text)
        }
        return try JSONDecoder().decode(type, from: data)
    }
}
