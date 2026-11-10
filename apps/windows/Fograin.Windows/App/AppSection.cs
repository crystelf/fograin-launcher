using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Shell;

/// <summary>Sidebar sections and their page metadata.</summary>
public enum AppSection
{
    Home,
    Library,
    Servers,
    Downloads,
    Java,
    Accounts,
    Settings,
    About,
}

public static class AppSections
{
    public static string Tag(this AppSection section) => section switch
    {
        AppSection.Home => "home",
        AppSection.Library => "library",
        AppSection.Servers => "servers",
        AppSection.Downloads => "downloads",
        AppSection.Java => "java",
        AppSection.Accounts => "accounts",
        AppSection.Settings => "settings",
        _ => "about",
    };

    public static AppSection? FromTag(string tag) => tag switch
    {
        "home" => AppSection.Home,
        "library" => AppSection.Library,
        "servers" => AppSection.Servers,
        "downloads" => AppSection.Downloads,
        "java" => AppSection.Java,
        "accounts" or "account" => AppSection.Accounts,
        "settings" => AppSection.Settings,
        "about" => AppSection.About,
        _ => null,
    };

    public static string Title(this AppSection section) => section switch
    {
        AppSection.Home => "主页",
        AppSection.Library => "实例库",
        AppSection.Servers => "服务器",
        AppSection.Downloads => "下载",
        AppSection.Java => "Java 运行时",
        AppSection.Accounts => "账户",
        AppSection.Settings => "设置",
        _ => "关于",
    };

    public static string PageSubtitle(this AppSection section) => section switch
    {
        AppSection.Home => "启动上次游玩的实例",
        AppSection.Library => "管理所有实例、模组与存档",
        AppSection.Servers => "收藏、局域网发现与状态 Ping",
        AppSection.Downloads => "浏览与安装",
        AppSection.Java => "扫描、安装与测试运行",
        AppSection.Accounts => "离线、外置登录与皮肤",
        AppSection.Settings => "外观与全局设置",
        _ => "版本、构建与致谢",
    };

    public static Symbol Icon(this AppSection section) => section switch
    {
        AppSection.Home => Symbol.Home,
        AppSection.Library => Symbol.Library,
        AppSection.Servers => Symbol.Globe,
        AppSection.Downloads => Symbol.Download,
        AppSection.Java => Symbol.Repair,
        AppSection.Accounts => Symbol.Contact,
        AppSection.Settings => Symbol.Setting,
        _ => Symbol.Page,
    };
}
