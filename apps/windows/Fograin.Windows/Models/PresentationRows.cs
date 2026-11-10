using Fograin.Windows.Core;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>One running/finished task row (home queue and downloads page).</summary>
public sealed record TaskRow(string Id, string Name, string State, double Fraction, double Speed)
{
    public string Title => InstanceFormat.TaskTitle(Name);

    public double Percent => Math.Clamp(Fraction, 0, 1) * 100;

    public bool IsActive => State is "running" or "paused";

    public bool CanCancel => State is "running" or "paused";

    /// <summary>`已完成` / `失败` / `已暂停` / `已取消` / `42%`.</summary>
    public string StateText => State switch
    {
        "succeeded" => "已完成",
        "failed" => "失败",
        "paused" => "已暂停",
        "cancelled" => "已取消",
        _ => $"{Math.Round(Percent)}%",
    };

    public string SpeedText => Speed <= 0 ? "—" : $"{InstanceFormat.ByteRate(Speed)}/s";

    public static TaskRow From(CoreTaskRow row) =>
        new(row.Id, row.Name, row.State, row.Fraction, row.Speed);
}

/// <summary>One entry of the version list on the downloads page.</summary>
public sealed record VersionEntry(string Id, string Kind, string? Released)
{
    public string KindText => Kind switch
    {
        "release" => "正式版",
        "snapshot" => "快照",
        "old_beta" or "old_alpha" => "远古版本",
        _ => Kind,
    };

    public string ReleasedText => InstanceFormat.ReleaseDate(Released);

    public static VersionEntry From(CoreVersionEntry entry) =>
        new(entry.Id, entry.Kind, entry.Released);
}

/// <summary>Formatted <c>version.plan</c> preview.</summary>
public sealed record PlanView(uint JavaMajor, int Files, ulong TotalBytes)
{
    public string JavaText => $"Java {JavaMajor}";

    public string FilesText => $"{Files} 个";

    public string SizeText => InstanceFormat.ByteSizeOrZero(TotalBytes);

    public static PlanView From(CorePlanPreview plan) =>
        new(plan.JavaMajor, plan.Files, plan.TotalBytes);
}

/// <summary>Release headline shown by the home rail (`versions.news`).</summary>
public sealed record NewsItem(string VersionId, string Headline, string Detail);

/// <summary>Disabled content row that names the milestone owning the feature.</summary>
public sealed record MilestoneRow(string Title, string Milestone);

/// <summary>Loader family offered by the install dialog (mirrors macOS GameLoaderKind).</summary>
public enum GameLoaderKind
{
    Vanilla,
    Fabric,
    FabricApi,
    Quilt,
    Forge,
    NeoForge,
    OptiFine,
    SqlApi,
}

/// <summary>Loader card content for the install dialog; only vanilla is installable today.</summary>
public sealed record GameLoaderOption(
    GameLoaderKind Kind,
    string Title,
    string Subtitle,
    Symbol Icon,
    string? Milestone,
    bool IsAvailable,
    IReadOnlyList<string> PlaceholderVersions)
{
    public string MilestoneText => Milestone ?? string.Empty;

    public static IReadOnlyList<GameLoaderOption> All { get; } =
    [
        new(GameLoaderKind.Vanilla, "原版", "纯净游戏，不需要额外加载器", Symbol.AllApps, null, true, []),
        new(GameLoaderKind.Fabric, "Fabric", "轻量、更新快的模组加载器", Symbol.Library, "M5", false, ["0.16.9（最新）", "0.16.10", "0.15.11"]),
        new(GameLoaderKind.FabricApi, "Fabric API", "多数 Fabric 模组的前置", Symbol.Page, "M5", false, ["0.116.0（最新）", "0.115.6"]),
        new(GameLoaderKind.Quilt, "Quilt", "Fabric 的社区分支", Symbol.ViewAll, "M5", false, ["0.27.0（最新）", "0.26.11"]),
        new(GameLoaderKind.Forge, "Forge", "老牌模组加载器", Symbol.Repair, "M5", false, ["47.2.0（最新）", "47.3.0"]),
        new(GameLoaderKind.NeoForge, "NeoForge", "Forge 的现代分支", Symbol.Emoji2, "M5", false, ["21.1.72（最新）", "21.1.80"]),
        new(GameLoaderKind.OptiFine, "OptiFine", "光影与性能优化", Symbol.Important, "M5", false, ["HD U M5（最新）", "HD U I7"]),
        new(GameLoaderKind.SqlApi, "SQL API", "预留的扩展接口", Symbol.Setting, "M5", false, ["预留"]),
    ];
}
