using System.Globalization;
using Fograin.Windows.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>
/// One instance as the pages render it. Built from <see cref="CoreInstanceRow"/>
/// and immutable: a refresh replaces the whole collection so the UI never has
/// to merge partial updates.
/// </summary>
public sealed class InstanceRow
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public required string Minecraft { get; init; }

    public required LoaderKind Loader { get; init; }

    public string LoaderVersion { get; init; } = string.Empty;

    public bool Favorite { get; init; }

    public bool Installed { get; init; }

    public int? ModCount { get; init; }

    public ulong SizeBytes { get; init; }

    public ulong PlaySeconds { get; init; }

    public DateTimeOffset? LastPlayed { get; init; }

    public int? PlayTimeMinutes { get; init; }

    public static InstanceRow From(CoreInstanceRow row)
    {
        DateTimeOffset? lastPlayed = null;
        if (row.LastPlayedMs is { } milliseconds)
        {
            try
            {
                lastPlayed = DateTimeOffset.FromUnixTimeMilliseconds(milliseconds).ToLocalTime();
            }
            catch (ArgumentOutOfRangeException)
            {
                lastPlayed = null;
            }
        }

        return new InstanceRow
        {
            Id = row.Id,
            Name = string.IsNullOrWhiteSpace(row.Name) ? row.Id : row.Name,
            Minecraft = row.Minecraft,
            Loader = LoaderKinds.Parse(row.LoaderKind),
            LoaderVersion = row.LoaderVersion,
            Favorite = row.Favorite,
            Installed = row.Installed,
            ModCount = row.ModCount,
            SizeBytes = row.SizeBytes,
            PlaySeconds = row.PlaySeconds,
            LastPlayed = lastPlayed,
            PlayTimeMinutes = row.PlaySeconds > 0
                ? (int)Math.Min(row.PlaySeconds / 60, int.MaxValue)
                : null,
        };
    }

    // ---- Presentation ------------------------------------------------

    public Symbol Icon => Loader.Icon();

    public Visibility FavoriteVisibility => Favorite ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>Context-menu label that flips with the favorite flag.</summary>
    public string FavoriteActionText => Favorite ? "取消收藏" : "收藏";

    public Visibility InstallWarningVisibility =>
        Installed ? Visibility.Collapsed : Visibility.Visible;

    public string StatusText => Installed ? "资源校验完成" : "资源未安装";

    /// <summary>`1.21.4 · Fabric · 42 模组` used by the home rows.</summary>
    public string Detail
    {
        get
        {
            var parts = new List<string> { Minecraft, Loader.Title() };
            if (ModCount is { } mods)
            {
                parts.Add($"{mods} 模组");
            }

            return string.Join(" · ", parts);
        }
    }

    /// <summary>`1.21.4 · Fabric 0.16.9`, or just the Minecraft version.</summary>
    public string DisplayVersion => string.IsNullOrWhiteSpace(LoaderVersion)
        ? Minecraft
        : $"{Minecraft} · {Loader.Title()} {LoaderVersion}";

    public string JavaLabel => $"Java 版 {Minecraft}";

    public string LoaderLabel => string.IsNullOrWhiteSpace(LoaderVersion)
        ? Loader.Title()
        : $"{Loader.Title()} {LoaderVersion}";

    /// <summary>`Fabric 0.16.9 · Java 版 1.21.4` for the settings overview.</summary>
    public string Subtitle => $"{LoaderLabel} · Java 版 {Minecraft}";

    public string ModsText => ModCount?.ToString(CultureInfo.InvariantCulture) ?? "—";

    public string SizeText => InstanceFormat.ByteSize(SizeBytes);

    public string LastPlayedText => InstanceFormat.RelativeDate(LastPlayed);

    public string PlayTimeText => InstanceFormat.PlayTime(PlayTimeMinutes);

    public string HomeDetail => Detail;

    /// <summary>Stable sort key so instances never played sort last.</summary>
    public long LastPlayedSortKey => LastPlayed?.UtcTicks ?? long.MinValue;

    /// <summary>Free-text haystack used by the toolbar search.</summary>
    public string SearchText =>
        $"{Name} {Minecraft} {Loader.Title()} {LoaderVersion} {Id}";

    public bool Matches(string? query)
    {
        if (string.IsNullOrWhiteSpace(query))
        {
            return true;
        }

        return SearchText.Contains(query.Trim(), StringComparison.CurrentCultureIgnoreCase);
    }
}

/// <summary>Group of instances in the library, keyed by loader family.</summary>
public sealed class InstanceGroup : List<InstanceRow>
{
    public InstanceGroup(string title, IEnumerable<InstanceRow> instances)
        : base(instances)
    {
        Title = title;
    }

    public string Title { get; }

    public string TotalText => Count.ToString(CultureInfo.InvariantCulture);
}

public enum HomeInstanceFilter
{
    Recent,
    Favorites,
    All,
}

public enum HomeLayoutKind
{
    List,
    Grid,
}

public enum LibrarySortOrder
{
    Recent,
    Name,
    Size,
}

/// <summary>Library grouping and sort order (mirrors macOS LibraryOrganizer).</summary>
public static class LibraryOrganizer
{
    private static readonly LibraryGroupKind[] GroupOrder =
    [
        LibraryGroupKind.Fabric,
        LibraryGroupKind.Forge,
        LibraryGroupKind.NeoForge,
        LibraryGroupKind.Vanilla,
    ];

    /// <summary>Sections in pen order, skipping empty groups.</summary>
    public static IReadOnlyList<InstanceGroup> Sections(
        IEnumerable<InstanceRow> instances,
        LibrarySortOrder sort)
    {
        var rows = instances.ToList();
        var sections = new List<InstanceGroup>();
        foreach (var group in GroupOrder)
        {
            var matches = rows.Where(row => row.Loader.Group() == group).ToList();
            if (matches.Count == 0)
            {
                continue;
            }

            Sort(matches, sort);
            sections.Add(new InstanceGroup(group.GroupTitle(), matches));
        }

        return sections;
    }

    public static List<InstanceRow> Sort(List<InstanceRow> rows, LibrarySortOrder sort)
    {
        rows.Sort(sort switch
        {
            LibrarySortOrder.Name => (left, right) =>
                StringComparer.CurrentCulture.Compare(left.Name, right.Name),
            LibrarySortOrder.Size => (left, right) => right.SizeBytes.CompareTo(left.SizeBytes),
            _ => (left, right) => right.LastPlayedSortKey.CompareTo(left.LastPlayedSortKey),
        });
        return rows;
    }
}
