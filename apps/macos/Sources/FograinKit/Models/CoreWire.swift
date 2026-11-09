import Foundation

/// Strict camelCase wire models decoded from `bindings/fg-ffi` JSON (§13).
///
/// Rows decoded with `JSONDecoder` for type safety where the shape is stable
/// (instances); dynamic download payloads go through `JSONSerialization` values.
public struct CoreInstanceRow: Decodable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let minecraft: String
    public let loaderKind: String
    public let loaderVersion: String
    public let favorite: Bool
    public let installed: Bool
    public let modCount: Int?
    public let sizeBytes: Int64
    public let playSeconds: UInt64
    public let lastPlayedMs: Int64?
}

/// `[game]` section as the core serializes it.
public struct CoreGameConfig: Decodable, Equatable, Sendable {
    public let username: String
    public let memoryMb: UInt32
    public let jvmArgs: String
    public let resolution: [UInt32]?
    public let fullscreen: Bool
    public let showLog: Bool
    public let autoCompleteResources: Bool

    public init(
        username: String,
        memoryMb: UInt32,
        jvmArgs: String,
        resolution: [UInt32]?,
        fullscreen: Bool,
        showLog: Bool,
        autoCompleteResources: Bool
    ) {
        self.username = username
        self.memoryMb = memoryMb
        self.jvmArgs = jvmArgs
        self.resolution = resolution
        self.fullscreen = fullscreen
        self.showLog = showLog
        self.autoCompleteResources = autoCompleteResources
    }
}

/// Aggregate returned by `app.snapshot`.
public struct CoreAppSnapshot: Decodable, Equatable, Sendable {
    public let platform: String
    public let appVersion: String
    public let dataRoot: String
    public let javaCount: Int
    public let activeTasks: Int
    public let diskFreeBytes: UInt64
    public let totalMemoryBytes: UInt64
    public let instanceCount: Int
}

/// One row of `tasks.list`.
public struct CoreTaskRow: Decodable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let state: String
    public let fraction: Double
    public let speed: Double
}

/// One entry of `versions.list`.
public struct CoreVersionEntry: Identifiable, Equatable, Sendable {
    public let id: String
    public let kind: String
    public let released: String?

    /// Sparse JSON payloads (`released` can be absent) decode through a custom
    /// init so a remote schema change cannot blank the download list.
    public init?(object: [String: Any]) {
        guard let idValue = object["id"] as? String else { return nil }
        id = idValue
        kind = object["kind"] as? String ?? "unknown"
        released = object["released"] as? String
    }
}

/// Download preview from `version.plan`.
public struct CorePlanPreview: Equatable, Sendable {
    public let versionId: String
    public let baseId: String
    public let javaMajor: UInt32
    public let files: Int
    public let totalBytes: UInt64

    public init?(object: [String: Any]) {
        guard let versionId = object["versionId"] as? String else { return nil }
        self.versionId = versionId
        baseId = object["baseId"] as? String ?? versionId
        javaMajor = UInt32(truncatingIfNeeded: (object["javaMajor"] as? NSNumber)?.int64Value ?? 21)
        files = (object["files"] as? NSNumber)?.intValue ?? 0
        totalBytes = UInt64(truncatingIfNeeded: (object["totalBytes"] as? NSNumber)?.int64Value ?? 0)
    }
}

/// Event pushed by the event queue (camelCase, `bindings/fg-ffi`).
public enum CoreEvent: Equatable, Sendable {
    case taskProgress(taskId: String, name: String, fraction: Double, speed: Double)
    case taskFinished(taskId: String, name: String, state: String, detail: [String])
    case gameStarted(instanceId: String, pid: Int?)
    case gameWindowReady(instanceId: String)
    case gameLine(instanceId: String, text: String)
    case gameExited(instanceId: String, exitCode: Int?, playSeconds: UInt64)

    public static func parse(_ object: [String: Any]) -> CoreEvent? {
        switch object["type"] as? String {
        case "task.progress":
            guard let taskId = object["taskId"] as? String else { return nil }
            return .taskProgress(
                taskId: taskId,
                name: object["name"] as? String ?? taskId,
                fraction: (object["fraction"] as? NSNumber)?.doubleValue ?? 0,
                speed: (object["speed"] as? NSNumber)?.doubleValue ?? 0
            )
        case "task.finished":
            guard let taskId = object["taskId"] as? String else { return nil }
            return .taskFinished(
                taskId: taskId,
                name: object["name"] as? String ?? taskId,
                state: object["state"] as? String ?? "succeeded",
                detail: object["detail"] as? [String] ?? []
            )
        case "game.started":
            return .gameStarted(
                instanceId: object["instanceId"] as? String ?? "",
                pid: (object["pid"] as? NSNumber)?.intValue
            )
        case "game.windowReady":
            return .gameWindowReady(instanceId: object["instanceId"] as? String ?? "")
        case "game.line":
            return .gameLine(
                instanceId: object["instanceId"] as? String ?? "",
                text: object["text"] as? String ?? ""
            )
        case "game.exited":
            return .gameExited(
                instanceId: object["instanceId"] as? String ?? "",
                exitCode: (object["exitCode"] as? NSNumber)?.intValue,
                playSeconds: UInt64(truncatingIfNeeded: (object["playSeconds"] as? NSNumber)?.int64Value ?? 0)
            )
        default:
            return nil
        }
    }
}

public enum CoreWire {
    public static func decode<T: Decodable>(_ type: T.Type, from value: [String: Any], key: String) -> T? {
        guard let payload = value[key] else { return nil }
        guard let data = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    /// Decodes a payload whose fields were merged into the envelope top level
    /// (`app.snapshot`, `gameConfig.get`, `version.plan`, …).
    public static func decodeMerged<T: Decodable>(_ type: T.Type, from value: [String: Any]) -> T? {
        var copy = value
        copy.removeValue(forKey: "ok")
        copy.removeValue(forKey: "error")
        guard let data = try? JSONSerialization.data(withJSONObject: copy) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    /// Decodes an array carried under `key` (arrays are merged into `result`).
    public static func decodeArray<T: Decodable>(_ type: T.Type, from value: [String: Any], key: String) -> [T] {
        guard let payload = value[key] as? [[String: Any]] else { return [] }
        return payload.compactMap { decode(type, from: [key: $0], key: key) }
    }
}


