// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "Fograin",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Fograin", targets: ["Fograin"])
    ],
    targets: [
        .executableTarget(
            name: "Fograin",
            path: "Sources/Fograin",
            resources: [.process("Resources")]
        )
    ]
)
