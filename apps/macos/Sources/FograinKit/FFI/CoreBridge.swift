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

    /// User-facing Chinese copy for the common core errors. Unknown keys fall
    /// back to the technical detail so diagnostics are never lost.
    public var userMessage: String {
        switch messageKey {
        case "error.launch.not_installed":
            "游戏资源尚未安装完成，请先在下载页补全资源。"
        case "error.launch.incomplete":
            "游戏文件不完整，上次下载可能没有完成。请重新下载缺失文件。"
        case "error.launch.arm64_natives":
            "这个版本只有 x86_64 原生库。请使用 Rosetta 下的 x86_64 Java 8。"
        case "error.java.none_installed":
            "未发现 Java 运行时。请安装 Java 后再启动。"
        case "error.java.required_missing":
            "没有找到匹配的 Java 版本，请安装对应版本的 Java。"
        case "error.java.too_new":
            "只找到更新的 Java，旧版本游戏可能会启动失败。"
        case "error.java.too_old":
            "只找到较旧的 Java，新版本游戏可能会启动失败。"
        case "error.instance.duplicate":
            "同名实例已经存在，请换一个实例名称。"
        case "error.instance.delete_running":
            "游戏正在运行，退出后才能删除实例。"
        case "error.launch.already_running":
            "已经有一个游戏在运行了。"
        case "error.launch.exit_code":
            "游戏异常退出。请查看日志确认原因。"
        case "error.task.cancelled":
            "任务已取消。"
        case "error.task.already_running":
            "已有下载任务在进行，请等待完成或取消。"
        case "error.network":
            "网络请求失败，请检查网络或代理设置。"
        case "error.install.failed":
            "安装没有完成，请重试。"
        case "error.instance.invalid_id":
            "实例名称无法转换为安全的目录名。"
        case "error.rpc.unknown_method", "error.rpc.parse", "error.rpc.params":
            "启动器核心拒绝了这次请求。"
        default:
            detail ?? messageKey
        }
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

public enum CoreBridgeError: Error, Equatable, Sendable {
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
    /// Dedicated queue for the blocking C calls. Using a Dispatch queue instead
    /// of `Task.detached` keeps the cooperative pool from being starved by a
    /// long `version.plan` / `install.start` call.
    private static let rpcQueue = DispatchQueue(
        label: "dev.fograin.launcher.core-rpc",
        qos: .userInitiated,
        attributes: .concurrent
    )

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

    // MARK: M4 RPC surface

    /// Dispatches one JSON command. Success responses are merged into the
    /// top-level object (camelCase keys); failures carry `error`.
    @discardableResult
    public static func rpc(_ method: String, params: [String: Any] = [:]) throws -> [String: Any] {
        let request = try requestData(method, params: params)
        return try parseResponse(rawCall(request))
    }

    /// Async variant used by page loads and install/launch actions.
    ///
    /// `fg_rpc_call` blocks on the core runtime, so it must not run on the main
    /// thread. The request is serialized before crossing into the detached
    /// task; only `Sendable` `Data` values cross the concurrency boundary.
    public static func rpcAsync(
        _ method: String,
        params: [String: Any] = [:]
    ) async throws -> [String: Any] {
        let request = try requestData(method, params: params)
        let response: Data = try await withCheckedThrowingContinuation { continuation in
            rpcQueue.async {
                do {
                    continuation.resume(returning: try rawCall(request))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
        return try parseResponse(response)
    }

    private static func requestData(_ method: String, params: [String: Any]) throws -> Data {
        let body: [String: Any] = ["method": method, "params": params]
        guard JSONSerialization.isValidJSONObject(body) else {
            throw CoreBridgeError.malformedResponse("rpc params are not valid JSON")
        }
        return try JSONSerialization.data(withJSONObject: body)
    }

    /// The blocking C call; keep this off the main actor.
    private static func rawCall(_ request: Data) throws -> Data {
        guard let text = String(data: request, encoding: .utf8) else {
            throw CoreBridgeError.malformedResponse("rpc params are not UTF-8")
        }
        guard let pointer = fg_rpc_call(text) else {
            throw CoreBridgeError.unavailable
        }
        defer { fg_string_free(pointer) }
        return Data(String(cString: pointer).utf8)
    }

    private static func parseResponse(_ data: Data) throws -> [String: Any] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CoreBridgeError.malformedResponse(
                String(data: data, encoding: .utf8) ?? "<non-utf8>"
            )
        }

        if let ok = object["ok"] as? Bool, ok {
            return object
        }
        if let error = object["error"] as? [String: Any],
           let data = try? JSONSerialization.data(withJSONObject: error) {
            let payload = try? JSONDecoder().decode(CoreErrorPayload.self, from: data)
            throw CoreBridgeError.rejected(
                payload ?? CoreErrorPayload(
                    kind: "internal",
                    messageKey: (error["messageKey"] as? String) ?? "error.internal",
                    detail: error["detail"] as? String
                )
            )
        }
        throw CoreBridgeError.malformedResponse(
            String(data: data, encoding: .utf8) ?? "<non-utf8>"
        )
    }

    /// Drains pending progress/game events as raw envelopes.
    public static func nextEvents() -> [[String: Any]] {
        guard let pointer = fg_events_next() else { return [] }
        defer { fg_string_free(pointer) }
        let text = String(cString: pointer)
        guard let data = text.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array
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
