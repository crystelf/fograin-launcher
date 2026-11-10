using System.ComponentModel;
using System.Diagnostics;
using Fograin.Windows.Shell;
using Fograin.Windows.Features.Downloads;
using Fograin.Windows.Features.Home;
using Fograin.Windows.Features.Instances;
using Fograin.Windows.Features.Library;
using Fograin.Windows.Features.Placeholder;
using Fograin.Windows.Platform;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using WinRT.Interop;

namespace Fograin.Windows;

public sealed partial class MainWindow : Window
{
    private readonly DispatcherQueueTimer _noticeTimer;
    private readonly Queue<string> _pendingSettings = new();
    private string _currentTag = string.Empty;
    private bool _dialogOpen;
    private bool _syncingSearch;
    private int _lastLibraryBadge = -1;
    private int _lastDownloadsBadge = -1;

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

        _noticeTimer = DispatcherQueue.CreateTimer();
        _noticeTimer.Interval = TimeSpan.FromSeconds(2.8);
        _noticeTimer.IsRepeating = false;
        _noticeTimer.Tick += (_, _) => NoticeBar.IsOpen = false;

        RootGrid.Loaded += (_, _) => WindowChrome.ApplyTheme(this, RootGrid.ActualTheme);
        RootGrid.ActualThemeChanged += (_, _) => WindowChrome.ApplyTheme(this, RootGrid.ActualTheme);

        RootFrame.Navigated += RootFrame_Navigated;

        var model = App.Model;
        model.SectionRequested += Model_SectionRequested;
        model.SettingsRequested += Model_SettingsRequested;
        model.InstallDialogRequested += Model_InstallDialogRequested;
        model.NoticeRaised += Model_NoticeRaised;
        model.AlertRaised += Model_AlertRaised;
        model.StateChanged += Model_StateChanged;
        model.PropertyChanged += Model_PropertyChanged;

        Closed += MainWindow_Closed;

        TaskbarProgress.Attach(WindowNative.GetWindowHandle(this));
        model.Start();

        RootNavigation.SelectedItem = HomeItem;
        NavigateTo("home");
        UpdateChrome();
    }

    private void MainWindow_Closed(object sender, WindowEventArgs args)
    {
        _noticeTimer.Stop();
        var model = App.Model;
        model.SectionRequested -= Model_SectionRequested;
        model.SettingsRequested -= Model_SettingsRequested;
        model.InstallDialogRequested -= Model_InstallDialogRequested;
        model.NoticeRaised -= Model_NoticeRaised;
        model.AlertRaised -= Model_AlertRaised;
        model.StateChanged -= Model_StateChanged;
        model.PropertyChanged -= Model_PropertyChanged;
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
        // Top-level NavigationView destinations do not build a history; the
        // TitleBar back button stays visible but disabled like the pen frame.
        RootFrame.BackStack.Clear();
        AppTitleBar.IsBackButtonEnabled = RootFrame.CanGoBack;
    }

    // ---- Navigation ---------------------------------------------------

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
        var section = AppSections.FromTag(tag) ?? AppSection.Home;
        App.Model.SelectSection(section);

        if (string.Equals(_currentTag, tag, StringComparison.Ordinal))
        {
            UpdateChrome();
            return;
        }

        _currentTag = tag;

        switch (section)
        {
            case AppSection.Home:
                RootFrame.Navigate(typeof(HomePage));
                break;
            case AppSection.Library:
                RootFrame.Navigate(typeof(LibraryPage));
                break;
            case AppSection.Downloads:
                RootFrame.Navigate(typeof(DownloadsPage));
                break;
            case AppSection.Servers:
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "服务器",
                    "服务器列表、状态 Ping、局域网发现与 Quick Play 加入将在 M9 接入。",
                    Symbol.Globe));
                break;
            case AppSection.Java:
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "Java 运行时",
                    "Java 扫描、自动匹配与运行时下载将在 M6 接入。",
                    Symbol.Repair));
                break;
            case AppSection.Accounts:
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "账户",
                    "离线账户、Yggdrasil 外置登录与皮肤管理将在 M7 接入。",
                    Symbol.Contact));
                break;
            case AppSection.Settings:
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "设置",
                    "八个设置分组与外观切换将在 M10 接入；外观已经由 launcher.toml 决定。",
                    Symbol.Setting));
                break;
            default:
                RootFrame.Navigate(typeof(PlaceholderPage), new PlaceholderArgs(
                    "关于",
                    "版本、构建信息与开源许可将在 M10 接入。",
                    Symbol.Page));
                break;
        }

        AppTitleBar.IsBackButtonEnabled = RootFrame.CanGoBack;
        UpdateChrome();
    }

    private void Model_SectionRequested(object? sender, AppSection section)
    {
        var tag = section.Tag();
        if (FindNavigationItem(tag) is { } item)
        {
            RootNavigation.SelectedItem = item;
        }

        NavigateTo(tag);
    }

    private NavigationViewItem? FindNavigationItem(string tag) =>
        RootNavigation.MenuItems
            .Concat(RootNavigation.FooterMenuItems)
            .OfType<NavigationViewItem>()
            .FirstOrDefault(item => string.Equals(item.Tag as string, tag, StringComparison.Ordinal));

    // ---- Chrome -------------------------------------------------------

    private void Model_StateChanged(object? sender, EventArgs args) => UpdateChrome();

    private void Model_PropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        if (args.PropertyName == nameof(LauncherModel.LibraryQuery))
        {
            SyncSearchText();
        }

        UpdateChrome();
    }

    private void UpdateChrome()
    {
        if (App.Model is null)
        {
            return;
        }

        var model = App.Model;
        var section = model.Selection;

        AppTitleBar.Title = section.Title();
        AppTitleBar.Subtitle = $"雾雨启动器 · {section.PageSubtitle()}";

        var searchable = section is AppSection.Home or AppSection.Library;
        TitleBarSearchBox.Visibility = searchable ? Visibility.Visible : Visibility.Collapsed;
        TitleBarSearchBox.PlaceholderText = section == AppSection.Library
            ? "搜索实例"
            : "搜索实例、模组…";
        SyncSearchText();

        var libraryCount = model.Instances.Count;
        if (libraryCount != _lastLibraryBadge)
        {
            _lastLibraryBadge = libraryCount;
            LibraryItem.InfoBadge = libraryCount > 0 ? new InfoBadge { Value = libraryCount } : null;
        }

        var activeTasks = model.ActiveTasks;
        var activeCount = activeTasks.Count;
        if (activeCount != _lastDownloadsBadge)
        {
            _lastDownloadsBadge = activeCount;
            DownloadsItem.InfoBadge = activeCount > 0 ? new InfoBadge { Value = activeCount } : null;
        }

        var fraction = activeCount == 0
            ? 0
            : activeTasks.Average(task => Math.Clamp(task.Fraction, 0, 1));
        TaskbarProgress.Update(fraction, activeCount);
    }

    private void SyncSearchText()
    {
        if (TitleBarSearchBox.Text == App.Model.LibraryQuery)
        {
            return;
        }

        _syncingSearch = true;
        TitleBarSearchBox.Text = App.Model.LibraryQuery;
        _syncingSearch = false;
    }

    private void TitleBarSearchBox_TextChanged(
        AutoSuggestBox sender,
        AutoSuggestBoxTextChangedEventArgs args)
    {
        if (_syncingSearch)
        {
            return;
        }

        if (args.Reason == AutoSuggestionBoxTextChangeReason.UserInput)
        {
            App.Model.LibraryQuery = sender.Text;
        }
    }

    private void TitleBarSearchBox_QuerySubmitted(
        AutoSuggestBox sender,
        AutoSuggestBoxQuerySubmittedEventArgs args)
    {
        App.Model.LibraryQuery = args.QueryText;
        App.Model.FocusLibrarySearch();
    }

    // ---- Notices, alerts and dialogs ----------------------------------

    private void Model_NoticeRaised(object? sender, Notice notice)
    {
        NoticeBar.Severity = notice.Severity;
        NoticeBar.Message = notice.Message;
        NoticeBar.IsOpen = true;
        _noticeTimer.Stop();
        _noticeTimer.Start();
    }

    private async void Model_AlertRaised(object? sender, AppAlert alert)
    {
        if (RootGrid.XamlRoot is null)
        {
            return;
        }

        if (_dialogOpen)
        {
            // A ContentDialog cannot stack; keep the message visible without
            // hiding the dialog the user is working in.
            Model_NoticeRaised(this, new Notice(alert.Message, InfoBarSeverity.Warning));
            return;
        }

        _dialogOpen = true;
        try
        {
            var dialog = new ContentDialog
            {
                XamlRoot = RootGrid.XamlRoot,
                Title = alert.Title,
                Content = new TextBlock
                {
                    Text = alert.CombinedMessage,
                    TextWrapping = TextWrapping.Wrap,
                },
                CloseButtonText = "好",
                DefaultButton = ContentDialogButton.Close,
            };
            await dialog.ShowAsync();
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"alert dialog failed: {exception.Message}");
        }
        finally
        {
            _dialogOpen = false;
            await ShowNextPendingSettingsAsync();
        }
    }

    private async void Model_SettingsRequested(object? sender, string instanceId) =>
        await ShowInstanceSettingsAsync(instanceId);

    private async void Model_InstallDialogRequested(object? sender, string version)
    {
        if (RootGrid.XamlRoot is null)
        {
            return;
        }

        if (_dialogOpen)
        {
            App.Model.ShowNotice("请先关闭当前对话框", InfoBarSeverity.Warning);
            return;
        }

        _dialogOpen = true;
        try
        {
            var dialog = new GameInstallDialog(version)
            {
                XamlRoot = RootGrid.XamlRoot,
            };
            await dialog.ShowAsync();
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"install dialog failed: {exception.Message}");
        }
        finally
        {
            _dialogOpen = false;
            await ShowNextPendingSettingsAsync();
        }
    }

    private async Task ShowInstanceSettingsAsync(string instanceId)
    {
        if (RootGrid.XamlRoot is null || App.Model.FindInstance(instanceId) is not { } instance)
        {
            return;
        }

        if (_dialogOpen)
        {
            _pendingSettings.Enqueue(instanceId);
            return;
        }

        _dialogOpen = true;
        try
        {
            var dialog = new InstanceSettingsDialog(instance)
            {
                XamlRoot = RootGrid.XamlRoot,
            };
            await dialog.ShowAsync();
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"instance settings dialog failed: {exception.Message}");
        }
        finally
        {
            _dialogOpen = false;
            await ShowNextPendingSettingsAsync();
        }
    }

    private async Task ShowNextPendingSettingsAsync()
    {
        if (_pendingSettings.Count == 0 || _dialogOpen)
        {
            return;
        }

        await ShowInstanceSettingsAsync(_pendingSettings.Dequeue());
    }
}
