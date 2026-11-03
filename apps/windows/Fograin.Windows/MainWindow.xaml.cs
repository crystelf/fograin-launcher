using System.Diagnostics;
using Fograin.Windows.Features.Home;
using Fograin.Windows.Features.Library;
using Fograin.Windows.Features.Placeholder;
using Fograin.Windows.Platform;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Fograin.Windows;

public sealed partial class MainWindow : Window
{
    private string _currentTag = string.Empty;

    public MainWindow()
    {
        InitializeComponent();

        Title = "雾雨启动器";

        // WinUI 3 requires the extension flag in code; the TitleBar control
        // then owns the caption buttons, drag areas and interactive content.
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        WindowSizing.UseTallTitleBar(this);
        WindowSizing.ApplyDesignSize(this);
        SetWindowIcon();

        RootGrid.Loaded += (_, _) => WindowChrome.ApplyTheme(this, RootGrid.ActualTheme);
        RootGrid.ActualThemeChanged += (_, _) => WindowChrome.ApplyTheme(this, RootGrid.ActualTheme);

        RootFrame.Navigated += RootFrame_Navigated;
        RootNavigation.SelectedItem = HomeItem;
        NavigateTo("home");
    }

    private void SetWindowIcon()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Assets", "app-icon.ico");
        if (!File.Exists(iconPath))
        {
            return;
        }

        try
        {
            AppWindow.SetIcon(iconPath);
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"SetIcon failed: {exception.Message}");
        }
    }

    private void AppTitleBar_BackRequested(TitleBar sender, object args)
    {
        if (RootFrame.CanGoBack)
        {
            RootFrame.GoBack();
        }
    }

    private void RootFrame_Navigated(object sender, NavigationEventArgs args)
    {
        // Top-level NavigationView destinations do not build a history in M1;
        // the TitleBar back button stays visible but disabled like the pen frame.
        RootFrame.BackStack.Clear();
        AppTitleBar.IsBackButtonEnabled = RootFrame.CanGoBack;
    }

    private void RootNavigation_SelectionChanged(
        NavigationView sender,
        NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is NavigationViewItem { Tag: string tag })
        {
            NavigateTo(tag);
        }
    }

    private void NavigateTo(string tag)
    {
        if (string.Equals(_currentTag, tag, StringComparison.Ordinal))
        {
            return;
        }

        _currentTag = tag;

        switch (tag)
        {
            case "home":
                RootFrame.Navigate(typeof(HomePage));
                break;
            case "library":
                RootFrame.Navigate(typeof(LibraryPage));
                break;
            case "servers":
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "服务器",
                    "服务器列表、状态 Ping、局域网发现与 Quick Play 加入将在 M9 接入。",
                    Symbol.Globe));
                break;
            case "downloads":
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "下载",
                    "资源搜索、安装计划与下载队列将在 M2 / M8 接入。",
                    Symbol.Download));
                break;
            case "java":
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "Java 运行时",
                    "Java 扫描、自动匹配与运行时下载将在 M6 接入。",
                    Symbol.Repair));
                break;
            case "account":
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "账户",
                    "离线账户、Yggdrasil 外置登录与皮肤管理将在 M7 接入。",
                    Symbol.Contact));
                break;
            case "settings":
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "设置",
                    "八个设置分组与外观切换将在 M10 接入。",
                    Symbol.Setting));
                break;
        }

        AppTitleBar.IsBackButtonEnabled = RootFrame.CanGoBack;
    }
}
