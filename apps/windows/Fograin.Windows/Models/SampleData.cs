using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>
/// Static sample content for the M1 prototype. Values follow the Windows 11
/// reference screen in fograin.pen; nothing here is read from disk or the core.
/// </summary>
public static class SampleData
{
    private static readonly Instance[] Instances =
    [
        new Instance
        {
            Name = "雾林生存",
            MinecraftVersion = "1.21.4",
            Loader = "Fabric",
            LoaderVersion = "0.16.9",
            ModCount = 42,
            SizeText = "3.1 GB",
            LastPlayedText = "2 小时前",
            Icon = Symbol.World,
            IsFavorite = true,
            IsCurrent = true,
            PlayTimeText = "126 小时",
            ModsDetail = "42 个 · 3 个可更新",
            ResourcePacksText = "4 个 · 3 个已启用",
            ShadersText = "2 个",
            WorldsText = "3 个存档",
            UpdateCount = 3,
            UpdateDetail = "Sodium、Iris、Lithium",
        },
        new Instance
        {
            Name = "深海探索",
            MinecraftVersion = "1.21.1",
            Loader = "Fabric",
            LoaderVersion = "0.16.6",
            ModCount = 28,
            SizeText = "1.9 GB",
            LastPlayedText = "1 个月前",
            Icon = Symbol.Map,
            PlayTimeText = "38 小时",
            ModsDetail = "28 个",
            ResourcePacksText = "2 个 · 1 个已启用",
            ShadersText = "1 个",
            WorldsText = "1 个存档",
        },
        new Instance
        {
            Name = "建筑灵感",
            MinecraftVersion = "1.21.4",
            Loader = "Fabric",
            LoaderVersion = "0.16.9",
            ModCount = 12,
            SizeText = "860 MB",
            LastPlayedText = "2 个月前",
            Icon = Symbol.Page,
            PlayTimeText = "12 小时",
            ModsDetail = "12 个",
            ResourcePacksText = "6 个 · 4 个已启用",
            ShadersText = "2 个",
            WorldsText = "5 个存档",
        },
        new Instance
        {
            Name = "机械动力 6",
            MinecraftVersion = "1.20.1",
            Loader = "Forge",
            LoaderVersion = "47.3.0",
            ModCount = 186,
            SizeText = "4.8 GB",
            LastPlayedText = "昨天",
            Icon = Symbol.Setting,
            PlayTimeText = "204 小时",
            ModsDetail = "186 个 · 1 个可更新",
            ResourcePacksText = "3 个 · 2 个已启用",
            ShadersText = "2 个",
            WorldsText = "4 个存档",
            UpdateCount = 1,
            UpdateDetail = "Create 6.0.2",
        },
        new Instance
        {
            Name = "暮色森林",
            MinecraftVersion = "1.20.1",
            Loader = "Forge",
            LoaderVersion = "47.2.1",
            ModCount = 64,
            SizeText = "2.2 GB",
            LastPlayedText = "2 个月前",
            Icon = Symbol.World,
            PlayTimeText = "57 小时",
            ModsDetail = "64 个",
            ResourcePacksText = "2 个 · 2 个已启用",
            ShadersText = "1 个",
            WorldsText = "2 个存档",
        },
        new Instance
        {
            Name = "空岛科技",
            MinecraftVersion = "1.19.2",
            Loader = "NeoForge",
            LoaderVersion = "42.0.9",
            ModCount = 97,
            SizeText = "3.6 GB",
            LastPlayedText = "上周",
            Icon = Symbol.Globe,
            PlayTimeText = "83 小时",
            ModsDetail = "97 个",
            ResourcePacksText = "1 个 · 1 个已启用",
            ShadersText = "2 个",
            WorldsText = "1 个存档",
        },
        new Instance
        {
            Name = "原版纯净",
            MinecraftVersion = "1.21.4",
            Loader = "原版",
            ModCount = 0,
            SizeText = "410 MB",
            LastPlayedText = "3 天前",
            Icon = Symbol.AllApps,
            PlayTimeText = "9 小时",
            ResourcePacksText = "1 个 · 1 个已启用",
        },
        new Instance
        {
            Name = "快照 25w14a",
            MinecraftVersion = "25w14a",
            Loader = "原版",
            ModCount = 0,
            SizeText = "380 MB",
            LastPlayedText = "未游玩",
            Icon = Symbol.View,
            ResourcePacksText = "0 个",
        },
    ];

    public static IReadOnlyList<Instance> All => Instances;

    public static Instance Current => Instances[0];

    public static IReadOnlyList<Instance> Recent =>
    [
        Instances[0],
        Instances[3],
        Instances[6],
        Instances[5],
    ];

    public static IReadOnlyList<InstanceGroup> Groups =>
    [
        new("Fabric", Instances.Where(instance => instance.Loader == "Fabric")),
        new("Forge", Instances.Where(instance => instance.Loader == "Forge")),
        new("NeoForge", Instances.Where(instance => instance.Loader == "NeoForge")),
        new("原版", Instances.Where(instance => instance.Loader == "原版")),
    ];

    public static IReadOnlyList<DownloadTask> Downloads =>
    [
        new("Sodium 0.6.5 · 1.21.4", "3.1 / 4.2 MB", 74),
        new("机械动力 6 · 资源补全", "312 / 1,240 文件", 25),
    ];

    public static IReadOnlyList<RuntimeRow> Runtime =>
    [
        new("Java", "Temurin 21.0.5 · x64"),
        new("内存分配", "6 GB / 32 GB"),
        new("渲染", "DirectX 12 · RTX 4070"),
    ];

    public static IReadOnlyList<NewsItem> News =>
    [
        new("Java 版 1.21.5 正式发布", "新增生物群系变种 · 今天"),
        new("快照 25w14a", "实验性功能 · 3 天前"),
    ];

    public static AccountInfo Account { get; } = new("S", "Steve_Fog", "Microsoft 账户 · 正版");

    public static int InstanceCount => Instances.Length;
}
