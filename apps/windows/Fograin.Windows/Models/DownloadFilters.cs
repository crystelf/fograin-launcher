using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>Top-level categories of the download page (§13).</summary>
public enum DownloadsCategory
{
    Game,
    Mod,
    Modpack,
    Resourcepack,
    Shaderpack,
    Datapack,
}

/// <summary>Version families shown by the channel dropdown on the 游戏 tab.</summary>
public enum DownloadVersionChannel
{
    Release,
    Snapshot,
    AprilFools,
    Old,
}

/// <summary>Loader filter shown on 模组 / 整合包; the actual search lands in M8/M9.</summary>
public enum ContentLoaderFilter
{
    All,
    Forge,
    NeoForge,
    Fabric,
    Quilt,
    OptiFine,
}

public static class DownloadFilters
{
    public static string Title(this DownloadsCategory category) => category switch
    {
        DownloadsCategory.Game => "游戏版本",
        DownloadsCategory.Mod => "模组",
        DownloadsCategory.Modpack => "整合包",
        DownloadsCategory.Resourcepack => "资源包",
        DownloadsCategory.Shaderpack => "光影包",
        _ => "数据包",
    };

    /// <summary>Compact label for the category switcher.</summary>
    public static string ShortTitle(this DownloadsCategory category) => category switch
    {
        DownloadsCategory.Game => "游戏",
        DownloadsCategory.Mod => "模组",
        DownloadsCategory.Modpack => "整合包",
        DownloadsCategory.Resourcepack => "资源包",
        DownloadsCategory.Shaderpack => "光影",
        _ => "数据包",
    };

    public static Symbol Icon(this DownloadsCategory category) => category switch
    {
        DownloadsCategory.Game => Symbol.AllApps,
        DownloadsCategory.Mod => Symbol.ViewAll,
        DownloadsCategory.Modpack => Symbol.Library,
        DownloadsCategory.Resourcepack => Symbol.Page,
        DownloadsCategory.Shaderpack => Symbol.Emoji2,
        _ => Symbol.Document,
    };

    /// <summary>Milestone owning the category, or <c>null</c> when it is live.</summary>
    public static string? Milestone(this DownloadsCategory category) => category switch
    {
        DownloadsCategory.Game => null,
        DownloadsCategory.Modpack => "M9",
        _ => "M8",
    };

    public static string Description(this DownloadsCategory category, ContentLoaderFilter loader) => category switch
    {
        DownloadsCategory.Mod => loader == ContentLoaderFilter.All
            ? "在 Modrinth 与 CurseForge 中搜索模组，解析依赖后安装到实例。"
            : $"筛选 {loader.Title()} 模组；搜索、依赖解析与安装将在 M8 接入。",
        DownloadsCategory.Modpack => loader == ContentLoaderFilter.All
            ? "导入与更新 Modrinth、CurseForge、MultiMC 与 HMCL 整合包。"
            : $"筛选 {loader.Title()} 整合包；导入与更新将在 M9 接入。",
        DownloadsCategory.Resourcepack => "浏览资源包并按实例的 MC 版本过滤兼容性。",
        DownloadsCategory.Shaderpack => "为已安装光影加载器的实例选择光影包。",
        DownloadsCategory.Datapack => "把数据包放进存档或世界的 datapacks 目录。",
        _ => string.Empty,
    };

    public static string Title(this DownloadVersionChannel channel) => channel switch
    {
        DownloadVersionChannel.Release => "正式版",
        DownloadVersionChannel.Snapshot => "快照",
        DownloadVersionChannel.AprilFools => "愚人节",
        _ => "远古版本",
    };

    /// <summary>`kind` argument understood by the Rust `versions.list` RPC.</summary>
    public static string KindValue(this DownloadVersionChannel channel) => channel switch
    {
        DownloadVersionChannel.Release => "release",
        DownloadVersionChannel.Snapshot => "snapshot",
        DownloadVersionChannel.AprilFools => "april_fools",
        _ => "old",
    };

    public static string EmptyMessage(this DownloadVersionChannel channel) => channel switch
    {
        DownloadVersionChannel.Release => "暂无正式版",
        DownloadVersionChannel.Snapshot => "暂无快照",
        DownloadVersionChannel.AprilFools => "暂无愚人节版本",
        _ => "暂无远古版本",
    };

    public static string Title(this ContentLoaderFilter filter) => filter switch
    {
        ContentLoaderFilter.All => "全部加载器",
        ContentLoaderFilter.Forge => "Forge",
        ContentLoaderFilter.NeoForge => "NeoForge",
        ContentLoaderFilter.Fabric => "Fabric",
        ContentLoaderFilter.Quilt => "Quilt",
        _ => "OptiFine",
    };
}
