import Foundation

/// Human-readable formatting shared by every page and covered by unit tests.
public enum InstanceFormat {
    public static func byteSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowedUnits = [.useMB, .useGB]
        formatter.isAdaptive = true
        return formatter.string(fromByteCount: bytes)
    }

    public static func totalSize(_ instances: [Instance]) -> String {
        byteSize(instances.reduce(0) { $0 + $1.sizeBytes })
    }

    /// `2 小时前` / `昨天` / `从未游玩` for a missing date.
    public static func relativeDate(_ date: Date?, relativeTo now: Date = Date()) -> String {
        guard let date else {
            return "从未游玩"
        }
        if abs(now.timeIntervalSince(date)) < 60 {
            return "刚刚"
        }

        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: "zh_Hans_CN")
        formatter.unitsStyle = .full
        formatter.dateTimeStyle = .named
        return formatter.localizedString(for: date, relativeTo: now)
    }

    public static func playTime(_ minutes: Int?) -> String {
        guard let minutes, minutes > 0 else {
            return "—"
        }
        if minutes < 60 {
            return "\(minutes) 分钟"
        }

        let hours = minutes / 60
        let remainder = minutes % 60
        if remainder == 0 {
            return "\(hours) 小时"
        }
        return "\(hours) 小时 \(remainder) 分钟"
    }
}
