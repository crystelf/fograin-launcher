import XCTest

@testable import FograinKit

final class CoreBridgeTests: XCTestCase {
    func testAbiVersionMatchesTheRustCore() {
        // Contract test: a mismatch means the Swift shell and the core archive
        // in .build/rust/lib came from different revisions.
        XCTAssertEqual(CoreBridge.abiVersion, 1)
    }

    func testSettingsSnapshotDecodesTheWireContract() throws {
        let json = """
        {
          "abiVersion": 1,
          "appVersion": "0.1.0",
          "platform": "macos",
          "theme": "dark",
          "dataRoot": "/tmp/fograin",
          "configPath": "/tmp/fograin/launcher.toml"
        }
        """
        let snapshot = try JSONDecoder().decode(
            CoreSettingsSnapshot.self,
            from: Data(json.utf8)
        )

        XCTAssertEqual(snapshot.abiVersion, 1)
        XCTAssertEqual(snapshot.platform, "macos")
        XCTAssertEqual(snapshot.theme, .dark)
        XCTAssertEqual(snapshot.dataRoot, "/tmp/fograin")
        XCTAssertNil(snapshot.configError)
    }

    func testSettingsSnapshotDecodesAConfigError() throws {
        let json = """
        {
          "abiVersion": 1,
          "appVersion": "0.1.0",
          "platform": "macos",
          "theme": "system",
          "dataRoot": "/tmp/fograin",
          "configPath": "/tmp/fograin/launcher.toml",
          "configError": {
            "kind": "parse",
            "messageKey": "error.config.parse",
            "detail": "launcher.toml: expected an equals"
          }
        }
        """
        let snapshot = try JSONDecoder().decode(
            CoreSettingsSnapshot.self,
            from: Data(json.utf8)
        )

        XCTAssertEqual(snapshot.configError?.kind, "parse")
        XCTAssertEqual(snapshot.configError?.messageKey, "error.config.parse")
    }

    func testWriteResultDecodesFailure() throws {
        let json = """
        {
          "ok": false,
          "error": {
            "kind": "invalidInput",
            "messageKey": "error.theme.invalid",
            "detail": "expected system, light or dark"
          }
        }
        """
        let result = try JSONDecoder().decode(CoreWriteResult.self, from: Data(json.utf8))

        XCTAssertFalse(result.ok)
        XCTAssertNil(result.theme)
        XCTAssertEqual(result.error?.messageKey, "error.theme.invalid")
    }

    func testCoreErrorPayloadUsesFriendlyChineseMessages() {
        let payload = CoreErrorPayload(
            kind: "notFound",
            messageKey: "error.launch.not_installed",
            detail: "1.21.4 is not installed"
        )
        XCTAssertEqual(payload.userMessage, "游戏资源尚未安装完成，请先在下载页补全资源。")

        let unknown = CoreErrorPayload(
            kind: "internal",
            messageKey: "error.something.else",
            detail: "technical detail"
        )
        XCTAssertEqual(unknown.userMessage, "technical detail")
    }

    func testAsyncRPCReturnsTheAppSnapshot() async throws {
        let payload = try await CoreBridge.rpcAsync("app.snapshot")
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertNotNil(payload["platform"] as? String)
        XCTAssertNotNil(payload["diskFreeBytes"] as? NSNumber)
    }

    func testThemePreferenceWireValues() {
        XCTAssertEqual(ThemePreference.system.rawValue, "system")
        XCTAssertEqual(ThemePreference.light.rawValue, "light")
        XCTAssertEqual(ThemePreference.dark.rawValue, "dark")
        XCTAssertNil(ThemePreference.system.resolved)
        XCTAssertEqual(ThemePreference.dark.resolved, .dark)
    }
}
