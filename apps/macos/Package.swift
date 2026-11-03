// swift-tools-version: 5.10
import Foundation
import PackageDescription

// The Rust core is built by scripts/build-macos.sh into .build/rust/lib before
// SwiftPM links the app. `#filePath` keeps the path valid regardless of where
// the repository is checked out.
let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let rustLibraryDirectory = packageRoot
    .appendingPathComponent(".build/rust/lib")
    .path

// Rust static-library objects carry `sdk n/a`, so SwiftPM/linker otherwise
// records the deployment target (14.0) as the SDK in LC_BUILD_VERSION. macOS
// then treats the app as a legacy build and does not enable Liquid Glass.
// scripts/build-macos.sh exports the real SDK version.
func detectedMacOSSDKVersion() -> String {
    if let version = ProcessInfo.processInfo.environment["FOGRAIN_MACOS_SDK_VERSION"],
       !version.isEmpty {
        return version
    }

    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/xcrun")
    process.arguments = ["--sdk", "macosx", "--show-sdk-version"]
    let pipe = Pipe()
    process.standardOutput = pipe
    do {
        try process.run()
        process.waitUntilExit()
    } catch {
        return "14.0"
    }

    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    let version = String(data: data, encoding: .utf8)?
        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return version.isEmpty ? "14.0" : version
}

let macOSSDKVersion = detectedMacOSSDKVersion()

let package = Package(
    name: "Fograin",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Fograin", targets: ["Fograin"])
    ],
    targets: [
        .target(
            name: "CFograinFFI",
            path: "Sources/CFograinFFI",
            publicHeadersPath: "include",
            linkerSettings: [
                .unsafeFlags(["-L", rustLibraryDirectory]),
                .linkedLibrary("fg_ffi"),
            ]
        ),
        .target(
            name: "FograinKit",
            dependencies: ["CFograinFFI"],
            path: "Sources/FograinKit"
        ),
        .executableTarget(
            name: "Fograin",
            dependencies: ["FograinKit"],
            path: "Sources/Fograin",
            exclude: ["Platform/README.md"],
            resources: [.process("Resources")],
            linkerSettings: [
                .unsafeFlags([
                    "-Xlinker", "-platform_version",
                    "-Xlinker", "macos",
                    "-Xlinker", "14.0",
                    "-Xlinker", macOSSDKVersion,
                ])
            ]
        ),
        .testTarget(
            name: "FograinKitTests",
            dependencies: ["FograinKit"],
            path: "Tests/FograinKitTests"
        ),
    ]
)
