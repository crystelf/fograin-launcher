import XCTest

@testable import FograinKit

final class CoreWireTests: XCTestCase {
    func testInstanceRowDecodesTheCamelCaseContract() throws {
        let json = """
        {
          "id": "1.21.4",
          "name": "雾林生存",
          "minecraft": "1.21.4",
          "loaderKind": "vanilla",
          "loaderVersion": "",
          "favorite": true,
          "installed": true,
          "modCount": 3,
          "sizeBytes": 1048576,
          "playSeconds": 90,
          "lastPlayedMs": 1730000000000
        }
        """
        let row = try JSONDecoder().decode(CoreInstanceRow.self, from: Data(json.utf8))
        XCTAssertEqual(row.id, "1.21.4")
        XCTAssertEqual(row.loaderKind, "vanilla")
        XCTAssertEqual(row.modCount, 3)
        XCTAssertEqual(row.playSeconds, 90)
        XCTAssertEqual(row.lastPlayedMs, 1_730_000_000_000)
        XCTAssertTrue(row.favorite)
        XCTAssertTrue(row.installed)
    }

    func testGameConfigDecodesTheWireShape() throws {
        let json = """
        {
          "username": "Alex",
          "memoryMb": 8192,
          "jvmArgs": "-XX:+UseG1GC",
          "resolution": [1920, 1080],
          "fullscreen": false,
          "showLog": true,
          "autoCompleteResources": true
        }
        """
        let config = try JSONDecoder().decode(CoreGameConfig.self, from: Data(json.utf8))
        XCTAssertEqual(config.username, "Alex")
        XCTAssertEqual(config.memoryMb, 8192)
        XCTAssertEqual(config.resolution, [1920, 1080])
        XCTAssertTrue(config.showLog)
    }

    func testVersionEntryToleratesMissingReleaseTime() {
        let entry = CoreVersionEntry(object: ["id": "1.21.5", "kind": "release"])
        XCTAssertEqual(entry?.id, "1.21.5")
        XCTAssertEqual(entry?.kind, "release")
        XCTAssertNil(entry?.released)

        XCTAssertNil(CoreVersionEntry(object: ["kind": "release"]))
    }

    func testPlanPreviewReadsNumericPayload() {
        let preview = CorePlanPreview(object: [
            "versionId": "1.21.4",
            "baseId": "1.21.4",
            "javaMajor": 21,
            "files": 479,
            "totalBytes": 48_447_488,
        ])
        XCTAssertEqual(preview?.javaMajor, 21)
        XCTAssertEqual(preview?.files, 479)
        XCTAssertEqual(preview?.totalBytes, 48_447_488)
    }


    func testSanitizedInstanceIDFallsBackForCJKNames() {
        XCTAssertEqual(InstanceFormat.sanitizedInstanceID("我的世界"), "")
        XCTAssertEqual(InstanceFormat.sanitizedInstanceID("my-world"), "my-world")
        XCTAssertEqual(InstanceFormat.sanitizedInstanceID("../escape"), "escape")
    }

    func testTaskTitleStripsInstallPrefix() {
        XCTAssertEqual(InstanceFormat.taskTitle("install:1.21.4"), "安装 1.21.4")
        XCTAssertEqual(InstanceFormat.taskTitle("install:rd-132211"), "安装 rd-132211")
        XCTAssertEqual(InstanceFormat.taskTitle("other"), "other")
    }

    func testThemePreferenceWireValues() {
        XCTAssertEqual(ThemePreference.system.rawValue, "system")
        XCTAssertEqual(ThemePreference.light.rawValue, "light")
        XCTAssertEqual(ThemePreference.dark.rawValue, "dark")
    }
}
