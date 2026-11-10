using System.Globalization;

namespace Fograin.Windows.Models;

/// <summary>Human-readable formatting shared by every page (mirrors macOS InstanceFormat).</summary>
public static class InstanceFormat
{
    /// <summary>1000-based file size like `3.1 GB`; `—` for unknown values.</summary>
    public static string ByteSize(ulong bytes)
    {
        if (bytes == 0)
        {
            return "—";
        }

        string[] units = ["B", "KB", "MB", "GB", "TB"];
        var value = (double)bytes;
        var unit = 0;
        while (value >= 1000 && unit < units.Length - 1)
        {
            value /= 1000;
            unit++;
        }

        return unit == 0
            ? $"{bytes} B"
            : value.ToString("0.0", CultureInfo.InvariantCulture) + " " + units[unit];
    }

    /// <summary>Byte size for planned downloads, where `0` means "empty".</summary>
    public static string ByteSizeOrZero(ulong bytes) => bytes == 0 ? "0 B" : ByteSize(bytes);

    /// <summary>Speed text like `12.3 MB`; accepts a bytes-per-second value.</summary>
    public static string ByteRate(double bytesPerSecond)
    {
        if (bytesPerSecond <= 0)
        {
            return "—";
        }

        return ByteSize((ulong)Math.Max(1, Math.Round(bytesPerSecond)));
    }

    /// <summary>`2 小时前` / `昨天` / `从未游玩` for a missing date.</summary>
    public static string RelativeDate(DateTimeOffset? date, DateTimeOffset? now = null)
    {
        if (date is null)
        {
            return "从未游玩";
        }

        var current = now ?? DateTimeOffset.Now;
        var value = date.Value.ToLocalTime();
        var delta = current - value;
        if (delta < TimeSpan.FromMinutes(1))
        {
            return "刚刚";
        }

        if (delta < TimeSpan.FromHours(1))
        {
            return $"{Math.Max(1, (int)delta.TotalMinutes)} 分钟前";
        }

        if (value.Date == current.Date)
        {
            return $"{Math.Max(1, (int)delta.TotalHours)} 小时前";
        }

        if (value.Date == current.Date.AddDays(-1))
        {
            return "昨天";
        }

        if (delta < TimeSpan.FromDays(30))
        {
            return $"{Math.Max(2, (int)delta.TotalDays)} 天前";
        }

        if (delta < TimeSpan.FromDays(365))
        {
            return $"{Math.Max(1, (int)(delta.TotalDays / 30))} 个月前";
        }

        return $"{Math.Max(1, (int)(delta.TotalDays / 365))} 年前";
    }

    /// <summary>`1.21.4` style date for version lists.</summary>
    public static string ReleaseDate(string? released)
    {
        if (string.IsNullOrWhiteSpace(released))
        {
            return "—";
        }

        return DateTimeOffset.TryParse(
            released,
            CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal,
            out var date)
            ? date.ToLocalTime().ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
            : released[..Math.Min(10, released.Length)];
    }

    /// <summary>
    /// Turns a display name into a filesystem-safe instance id (empty when the
    /// name carries no usable characters, e.g. pure CJK).
    /// </summary>
    public static string SanitizedInstanceId(string raw)
    {
        var filtered = raw.Where(character =>
            character is >= 'a' and <= 'z'
            or >= 'A' and <= 'Z'
            or >= '0' and <= '9'
            or '-' or '_' or '.').ToArray();
        var id = new string(filtered).Trim('.');
        return id;
    }

    /// <summary>Task graph name shown in queue rows (`install:1.21.4` → 安装 1.21.4).</summary>
    public static string TaskTitle(string graphName)
    {
        const string prefix = "install:";
        return graphName.StartsWith(prefix, StringComparison.Ordinal)
            ? $"安装 {graphName[prefix.Length..]}"
            : graphName;
    }

    /// <summary>`126 小时` / `12 分钟` / `—`.</summary>
    public static string PlayTime(int? minutes)
    {
        if (minutes is not > 0)
        {
            return "—";
        }

        var value = minutes.Value;
        if (value < 60)
        {
            return $"{value} 分钟";
        }

        var hours = value / 60;
        var remainder = value % 60;
        return remainder == 0 ? $"{hours} 小时" : $"{hours} 小时 {remainder} 分钟";
    }
}
