import Foundation
#if canImport(Metal)
import Metal
#endif

/// Host information the shell can show today without waiting for `fg-sys`.
///
/// Java discovery, GPU selection and memory allocation move to the Rust core in
/// later milestones; this type only feeds the static M1 runtime card.
public struct SystemInfo: Sendable, Equatable {
    public let gpuName: String?
    public let memoryBytes: UInt64
    public let logicalCoreCount: Int
    public let architecture: String

    public static func current() -> SystemInfo {
        var gpuName: String?
        #if canImport(Metal)
        gpuName = MTLCreateSystemDefaultDevice()?.name
        #endif

        let processInfo = ProcessInfo.processInfo
        return SystemInfo(
            gpuName: gpuName,
            memoryBytes: processInfo.physicalMemory,
            logicalCoreCount: processInfo.processorCount,
            architecture: architectureName
        )
    }

    public var memoryText: String {
        let gigabytes = Double(memoryBytes) / 1_073_741_824
        return "\(max(1, Int(gigabytes.rounded()))) GB"
    }

    public var gpuText: String {
        gpuName.map { "Metal · \($0)" } ?? "Metal 不可用"
    }

    private static var architectureName: String {
        #if arch(arm64)
        "arm64"
        #elseif arch(x86_64)
        "x86_64"
        #else
        "unknown"
        #endif
    }
}
