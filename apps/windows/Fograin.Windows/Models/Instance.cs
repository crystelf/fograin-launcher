using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>
/// A launcher instance rendered by the prototype screens. The data is static
/// sample content for milestone M1; the FFI-backed model arrives in M4.
/// </summary>
public sealed class Instance
{
    public string Name { get; init; } = string.Empty;

    public string MinecraftVersion { get; init; } = string.Empty;

    public string Loader { get; init; } = "原版";

    public string LoaderVersion { get; init; } = string.Empty;

    public int ModCount { get; init; }

    public string SizeText { get; init; } = "—";

    public string LastPlayedText { get; init; } = "未游玩";

    public Symbol Icon { get; init; } = Symbol.AllApps;

    public bool IsFavorite { get; init; }

    public bool IsCurrent { get; init; }

    public string StatusText { get; init; } = "资源校验完成";

    public string PlayTimeText { get; init; } = "0 小时";

    public string ModsDetail { get; init; } = "0 个";

    public string ResourcePacksText { get; init; } = "0 个";

    public string ShadersText { get; init; } = "0 个";

    public string WorldsText { get; init; } = "0 个存档";

    public int UpdateCount { get; init; }

    public string UpdateDetail { get; init; } = string.Empty;

    /// <summary>One-line summary used by the home list and command rows.</summary>
    public string Detail => ModCount > 0
        ? $"{MinecraftVersion} · {Loader} · {ModCount} 模组"
        : $"{MinecraftVersion} · {Loader}";

    public string JavaLabel => $"Java 版 {MinecraftVersion}";

    public string LoaderLabel => string.IsNullOrWhiteSpace(LoaderVersion)
        ? Loader
        : $"{Loader} {LoaderVersion}";

    public string ModsLabel => $"{ModCount} 个模组";

    public string ModCountText => ModCount > 0
        ? ModCount.ToString(CultureInfo.InvariantCulture)
        : "—";

    public string Subtitle => string.IsNullOrWhiteSpace(LoaderVersion)
        ? $"{Loader} · Java 版 {MinecraftVersion}"
        : $"{Loader} {LoaderVersion} · Java 版 {MinecraftVersion}";

    public string StatusDetail => UpdateCount > 0
        ? $"{StatusText} · {UpdateCount} 个模组可更新"
        : StatusText;

    public string UpdateTitle => $"{UpdateCount} 个模组有更新";

    public Visibility FavoriteVisibility => IsFavorite ? Visibility.Visible : Visibility.Collapsed;

    public Visibility UpdateVisibility => UpdateCount > 0 ? Visibility.Visible : Visibility.Collapsed;
}

/// <summary>Group of instances in the library, keyed by loader.</summary>
public sealed class InstanceGroup : List<Instance>
{
    public InstanceGroup(string loader, IEnumerable<Instance> instances)
        : base(instances)
    {
        Loader = loader;
    }

    public string Loader { get; }

    public string TotalText => Count.ToString(CultureInfo.InvariantCulture);
}

public sealed record DownloadTask(string Name, string ProgressText, double Percent);

public sealed record RuntimeRow(string Key, string Value);

public sealed record NewsItem(string Headline, string Detail);

public sealed record AccountInfo(string Initials, string Name, string Kind);
