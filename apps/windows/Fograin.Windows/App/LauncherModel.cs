using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Text.Json.Nodes;
using Fograin.Windows.Core;
using Fograin.Windows.Models;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Shell;

/// <summary>Short status capsule shown by the shell (macOS notice overlay).</summary>
public sealed record Notice(string Message, InfoBarSeverity Severity = InfoBarSeverity.Informational);

/// <summary>Blocking error dialog; failures must never be a transient capsule.</summary>
public sealed record AppAlert(string Title, string Message, string? Detail = null)
{
    public string CombinedMessage
    {
        get
        {
            if (string.IsNullOrWhiteSpace(Detail))
            {
                return Message;
            }

            var detail = Detail!.Length > 480 ? Detail[..480] + "…" : Detail;
            return $"{Message}\n\n{detail}";
        }
    }
}

/// <summary>
/// The Windows equivalent of macOS <c>AppModel</c>: owns the core RPC state,
/// drains the event queue and exposes presentation-ready properties for the
/// pages. Every mutation happens on the UI thread; blocking core calls are
/// pushed to the thread pool by <see cref="CoreBridge.RpcAsync"/>.
/// </summary>
public sealed class LauncherModel : INotifyPropertyChanged
{
    private const int ProgressIntervalMs = 250;

    private readonly DispatcherQueueTimer _eventTimer;
    private readonly List<VersionEntry> _allDownloadVersions = new();

    private bool _initialized;
    private bool _started;
    private bool _draining;
    private Task? _refreshTask;
    private bool _refreshAgain;
    private bool _instancesAlertShown;
    private bool _stopRequested;

    private AppSection _selection = AppSection.Home;
    private string _currentInstanceId = string.Empty;
    private string? _librarySelectionId;
    private string _libraryQuery = string.Empty;
    private HomeInstanceFilter _homeFilter = HomeInstanceFilter.Recent;
    private HomeLayoutKind _homeLayout = HomeLayoutKind.List;
    private LibrarySortOrder _librarySort = LibrarySortOrder.Recent;

    private CoreAppSnapshot? _appSnapshot;
    private CoreGameConfig _gameConfig = CoreGameConfig.Default;
    private bool _isInitialLoading = true;

    private bool _sessionRunning;
    private bool _isSessionLoading;
    private int? _sessionPid;

    private bool _isGameDownloadRunning;
    private string? _pendingDownloadTaskId;
    private string? _pendingDownloadInstanceId;

    private DownloadsCategory _downloadsCategory = DownloadsCategory.Game;
    private DownloadVersionChannel _downloadVersionChannel = DownloadVersionChannel.Release;
    private ContentLoaderFilter _downloadContentLoader = ContentLoaderFilter.All;
    private string _downloadsQuery = string.Empty;
    private bool _isLoadingDownloadVersions;
    private string? _downloadVersionsError;
    private DownloadVersionChannel? _loadedChannel;
    private DownloadVersionChannel? _loadingChannel;
    private int _downloadVersionsGeneration;
    private string? _downloadSelectedVersionId;
    private PlanView? _downloadPlan;
    private string? _downloadPlanError;
    private bool _isPlanLoading;
    private int _planGeneration;

    public LauncherModel(CoreSettingsSnapshot settings, DispatcherQueue dispatcher)
    {
        Settings = settings;

        _eventTimer = dispatcher.CreateTimer();
        _eventTimer.Interval = TimeSpan.FromMilliseconds(ProgressIntervalMs);
        _eventTimer.Tick += (_, _) => _ = DrainEventsAsync();
    }

    /// <summary>
    /// Starts the core load and the event pump. The shell calls this after it
    /// has subscribed to <see cref="NoticeRaised"/>/<see cref="AlertRaised"/> so
    /// startup diagnostics are not dropped.
    /// </summary>
    public void Start()
    {
        if (_started)
        {
            return;
        }

        _started = true;
        _eventTimer.Start();
        _ = InitializeAsync();
    }

    // ---- Events used by the shell -------------------------------------

    /// <summary>Navigation requested by the model (download page, settings jump).</summary>
    public event EventHandler<AppSection>? SectionRequested;

    /// <summary>Open 游戏设置 for an instance id.</summary>
    public event EventHandler<string>? SettingsRequested;

    /// <summary>Open the install dialog, optionally with a preselected version.</summary>
    public event EventHandler<string>? InstallDialogRequested;

    public event EventHandler<Notice>? NoticeRaised;

    public event EventHandler<AppAlert>? AlertRaised;

    /// <summary>Badges, taskbar progress and session state changed.</summary>
    public event EventHandler? StateChanged;

    /// <summary>The instance/task collections were replaced.</summary>
    public event EventHandler? DataRefreshed;

    public event PropertyChangedEventHandler? PropertyChanged;

    // ---- Shell state --------------------------------------------------

    public CoreSettingsSnapshot Settings { get; }

    public AppSection Selection
    {
        get => _selection;
        private set
        {
            if (Set(ref _selection, value))
            {
                Raise(nameof(SelectionTitle));
                Raise(nameof(SelectionSubtitle));
            }
        }
    }

    public string SelectionTitle => Selection.Title();

    public string SelectionSubtitle => Selection.PageSubtitle();

    public string LibraryQuery
    {
        get => _libraryQuery;
        set
        {
            if (!Set(ref _libraryQuery, value ?? string.Empty))
            {
                return;
            }

            RefreshHomeInstances();
            RebuildLibraryGroups();
        }
    }

    public HomeInstanceFilter HomeFilter
    {
        get => _homeFilter;
        set
        {
            if (Set(ref _homeFilter, value))
            {
                RefreshHomeInstances();
            }
        }
    }

    public HomeLayoutKind HomeLayout
    {
        get => _homeLayout;
        set
        {
            if (Set(ref _homeLayout, value))
            {
                Raise(nameof(ListVisibility));
                Raise(nameof(GridVisibility));
                Raise(nameof(IsListLayout));
                Raise(nameof(IsGridLayout));
            }
        }
    }

    public LibrarySortOrder LibrarySort
    {
        get => _librarySort;
        set
        {
            if (Set(ref _librarySort, value))
            {
                RebuildLibraryGroups();
            }
        }
    }

    // ---- Instances ----------------------------------------------------

    public ObservableCollection<InstanceRow> Instances { get; } = new();

    public ObservableCollection<InstanceRow> HomeInstances { get; } = new();

    public IReadOnlyList<InstanceGroup> LibraryGroups { get; private set; } = Array.Empty<InstanceGroup>();

    public Visibility LibraryEmptyVisibility =>
        LibraryGroups.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

    public string CurrentInstanceId => _currentInstanceId;

    public InstanceRow? CurrentInstance => FindInstance(_currentInstanceId) ?? Instances.FirstOrDefault();

    public string CurrentInstanceName => CurrentInstance?.Name ?? "未选择实例";

    public Symbol CurrentInstanceIcon => CurrentInstance?.Icon ?? Symbol.AllApps;

    public string CurrentInstanceMeta
    {
        get
        {
            var instance = CurrentInstance;
            if (instance is null)
            {
                return "通过下载页选择版本与加载器，几分钟后就能开始游戏。";
            }

            var parts = new List<string> { instance.JavaLabel, instance.LoaderLabel };
            if (instance.ModCount is { } mods)
            {
                parts.Add($"{mods} 个模组");
            }

            return string.Join(" · ", parts);
        }
    }

    public string CurrentInstanceStatusText
    {
        get
        {
            var instance = CurrentInstance;
            if (instance is null)
            {
                return "还没有实例";
            }

            if (SessionRunning)
            {
                return _sessionPid is { } pid ? $"游戏运行中 · 进程 {pid}" : "游戏运行中";
            }

            if (IsSessionLoading)
            {
                return "正在启动游戏…";
            }

            return instance.Installed ? "已就绪 · 资源校验完成" : "资源未安装 · 开始游戏会先补全资源";
        }
    }

    public Visibility HeroVisibility =>
        Instances.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

    public Visibility EmptyHeroVisibility =>
        Instances.Count > 0 ? Visibility.Collapsed : Visibility.Visible;

    public Visibility LoadingVisibility =>
        IsInitialLoading ? Visibility.Visible : Visibility.Collapsed;

    public Visibility ListVisibility =>
        HomeLayout == HomeLayoutKind.List ? Visibility.Visible : Visibility.Collapsed;

    public Visibility GridVisibility =>
        HomeLayout == HomeLayoutKind.Grid ? Visibility.Visible : Visibility.Collapsed;

    public bool IsListLayout => HomeLayout == HomeLayoutKind.List;

    public bool IsGridLayout => HomeLayout == HomeLayoutKind.Grid;

    public string HomeSubtitle
    {
        get
        {
            var latest = Instances
                .Where(instance => instance.LastPlayed is not null)
                .OrderByDescending(instance => instance.LastPlayedSortKey)
                .FirstOrDefault();
            return $"{Instances.Count} 个实例 · 上次游玩 {InstanceFormat.RelativeDate(latest?.LastPlayed)}";
        }
    }

    public string LibrarySubtitle
    {
        get
        {
            var total = Instances.Aggregate(0UL, (sum, instance) => sum + instance.SizeBytes);
            return $"{Instances.Count} 个实例 · 占用 {InstanceFormat.ByteSizeOrZero(total)}";
        }
    }

    public InstanceRow? SelectedLibraryInstance =>
        _librarySelectionId is null ? null : FindInstance(_librarySelectionId);

    public string? LibrarySelectionId
    {
        get => _librarySelectionId;
        set
        {
            if (Set(ref _librarySelectionId, value))
            {
                RaiseSelectionProperties();
            }
        }
    }

    public Visibility PreviewVisibility =>
        SelectedLibraryInstance is null ? Visibility.Collapsed : Visibility.Visible;

    public Visibility NoPreviewVisibility =>
        SelectedLibraryInstance is null ? Visibility.Visible : Visibility.Collapsed;

    public bool HasSelectedInstance => SelectedLibraryInstance is not null;

    public string SelectedName => SelectedLibraryInstance?.Name ?? string.Empty;

    public string SelectedSubtitle => SelectedLibraryInstance?.Subtitle ?? string.Empty;

    public string SelectedMinecraftText => SelectedLibraryInstance?.Minecraft ?? string.Empty;

    public string SelectedLoaderText => SelectedLibraryInstance?.LoaderLabel ?? string.Empty;

    public string SelectedModsText => SelectedLibraryInstance?.ModsText ?? "—";

    public string SelectedSizeText => SelectedLibraryInstance?.SizeText ?? "—";

    public string SelectedPlayTimeText => SelectedLibraryInstance?.PlayTimeText ?? "—";

    public string SelectedLastPlayedText => SelectedLibraryInstance?.LastPlayedText ?? "从未游玩";

    public string SelectedStatusText => SelectedLibraryInstance?.StatusText ?? string.Empty;

    public Visibility SelectedFavoriteVisibility =>
        SelectedLibraryInstance?.Favorite == true ? Visibility.Visible : Visibility.Collapsed;

    /// <summary>Keeps the preview star legible but muted when not favorited.</summary>
    public double SelectedFavoriteOpacity =>
        SelectedLibraryInstance?.Favorite == true ? 1.0 : 0.45;

    // ---- Runtime ------------------------------------------------------

    public CoreAppSnapshot? AppSnapshot
    {
        get => _appSnapshot;
        private set
        {
            if (Set(ref _appSnapshot, value))
            {
                Raise(nameof(JavaSummary));
                Raise(nameof(DiskFreeText));
            }
        }
    }

    public CoreGameConfig GameConfig
    {
        get => _gameConfig;
        private set
        {
            if (Set(ref _gameConfig, value))
            {
                Raise(nameof(MemorySummary));
            }
        }
    }

    public string JavaSummary => AppSnapshot switch
    {
        null => "扫描中…",
        { JavaCount: 0 } => "未发现 Java",
        { } snapshot => $"已发现 {snapshot.JavaCount} 个运行时",
    };

    public string MemorySummary => GameConfig.MemoryMb == 0
        ? $"自动 · 物理内存 {SystemInfo.MemoryText}"
        : $"{Math.Max(1u, GameConfig.MemoryMb / 1024u)} GB / {SystemInfo.MemoryText}";

    public string RenderSummary => SystemInfo.RendererText;

    public string DiskFreeText =>
        AppSnapshot is null ? "—" : InstanceFormat.ByteSize(AppSnapshot.DiskFreeBytes);

    public string AppVersionText => Settings.FromCore
        ? $"{Settings.AppVersion} · ABI v{Settings.AbiVersion}"
        : $"未连接核心 · {Settings.AppVersion}";

    // ---- Session ------------------------------------------------------

    public bool SessionRunning
    {
        get => _sessionRunning;
        private set
        {
            if (Set(ref _sessionRunning, value))
            {
                Raise(nameof(CanStartGame));
                Raise(nameof(CanStopGame));
                Raise(nameof(CurrentInstanceStatusText));
                Raise(nameof(SessionVisibility));
            }
        }
    }

    public bool IsSessionLoading
    {
        get => _isSessionLoading;
        private set
        {
            if (Set(ref _isSessionLoading, value))
            {
                Raise(nameof(CanStartGame));
                Raise(nameof(CurrentInstanceStatusText));
                Raise(nameof(LaunchingVisibility));
            }
        }
    }

    public bool CanStartGame => CurrentInstance is not null && !SessionRunning && !IsSessionLoading;

    public bool CanStopGame => SessionRunning;

    public Visibility SessionVisibility =>
        SessionRunning ? Visibility.Visible : Visibility.Collapsed;

    public Visibility LaunchingVisibility =>
        IsSessionLoading ? Visibility.Visible : Visibility.Collapsed;

    public string SessionPidText => _sessionPid is { } pid ? $"进程 {pid}" : string.Empty;

    // ---- Tasks --------------------------------------------------------

    public ObservableCollection<TaskRow> Tasks { get; } = new();

    public ObservableCollection<TaskRow> ActiveTasks { get; } = new();

    public bool HasActiveTasks => ActiveTasks.Count > 0;

    public int ActiveDownloadCount => ActiveTasks.Count;

    public Visibility QueueVisibility =>
        HasActiveTasks ? Visibility.Visible : Visibility.Collapsed;

    public Visibility EmptyQueueVisibility =>
        HasActiveTasks ? Visibility.Collapsed : Visibility.Visible;

    public string DownloadsSpeedText
    {
        get
        {
            var speed = ActiveTasks.Sum(task => task.Speed);
            if (speed <= 0)
            {
                return ActiveTasks.Count == 0 ? "空闲" : "—";
            }

            return $"{InstanceFormat.ByteRate(speed)}/s";
        }
    }

    public bool IsInitialLoading
    {
        get => _isInitialLoading;
        private set
        {
            if (Set(ref _isInitialLoading, value))
            {
                Raise(nameof(LoadingVisibility));
            }
        }
    }

    // ---- Downloads ----------------------------------------------------

    public DownloadsCategory DownloadsCategory
    {
        get => _downloadsCategory;
        private set
        {
            if (!Set(ref _downloadsCategory, value))
            {
                return;
            }

            RaiseCategoryProperties();
        }
    }

    public DownloadVersionChannel DownloadVersionChannel
    {
        get => _downloadVersionChannel;
        private set
        {
            if (!Set(ref _downloadVersionChannel, value))
            {
                return;
            }

            Raise(nameof(VersionEmptyMessage));
            Raise(nameof(SelectedVersionChannelText));
        }
    }

    public ContentLoaderFilter DownloadContentLoader
    {
        get => _downloadContentLoader;
        private set
        {
            if (Set(ref _downloadContentLoader, value))
            {
                Raise(nameof(ComingSoonDescription));
                Raise(nameof(ContentLoaderText));
            }
        }
    }

    public string DownloadsQuery
    {
        get => _downloadsQuery;
        set
        {
            if (Set(ref _downloadsQuery, value ?? string.Empty))
            {
                RebuildVisibleVersions();
            }
        }
    }

    public ObservableCollection<VersionEntry> DownloadVersions { get; } = new();

    /// <summary>Every version loaded for the current channel (unfiltered).</summary>
    public IReadOnlyList<VersionEntry> AllDownloadVersions => _allDownloadVersions;

    public Visibility GameCategoryVisibility =>
        DownloadsCategory == DownloadsCategory.Game ? Visibility.Visible : Visibility.Collapsed;

    public Visibility ComingSoonVisibility =>
        DownloadsCategory == DownloadsCategory.Game ? Visibility.Collapsed : Visibility.Visible;

    public string ComingSoonTitle => DownloadsCategory.Title();

    public string ComingSoonDescription => DownloadsCategory.Description(DownloadContentLoader);

    public string ComingSoonMilestone => $"{DownloadsCategory.Milestone() ?? "后续"} 接入";

    public Symbol ComingSoonIcon => DownloadsCategory.Icon();

    public Visibility ChannelVisibility =>
        DownloadsCategory == DownloadsCategory.Game ? Visibility.Visible : Visibility.Collapsed;

    public Visibility LoaderFilterVisibility =>
        DownloadsCategory is DownloadsCategory.Mod or DownloadsCategory.Modpack
            ? Visibility.Visible
            : Visibility.Collapsed;

    public string ContentLoaderText => DownloadContentLoader.Title();

    public string SelectedVersionChannelText => DownloadVersionChannel.Title();

    public string VersionEmptyMessage => DownloadVersionChannel.EmptyMessage();

    public Visibility VersionLoadingVisibility =>
        _isLoadingDownloadVersions ? Visibility.Visible : Visibility.Collapsed;

    public Visibility VersionListVisibility =>
        !_isLoadingDownloadVersions && DownloadVersions.Count > 0
            ? Visibility.Visible
            : Visibility.Collapsed;

    public Visibility VersionEmptyVisibility =>
        !_isLoadingDownloadVersions && DownloadVersions.Count == 0 && _downloadVersionsError is null
            ? Visibility.Visible
            : Visibility.Collapsed;

    public Visibility VersionErrorVisibility =>
        _downloadVersionsError is not null && DownloadVersions.Count == 0
            ? Visibility.Visible
            : Visibility.Collapsed;

    public string DownloadVersionsError => _downloadVersionsError ?? string.Empty;

    public string? DownloadSelectedVersionId => _downloadSelectedVersionId;

    public VersionEntry? SelectedVersionEntry =>
        _downloadSelectedVersionId is null
            ? null
            : DownloadVersions.FirstOrDefault(version => version.Id == _downloadSelectedVersionId);

    public Visibility DetailVisibility =>
        SelectedVersionEntry is null ? Visibility.Collapsed : Visibility.Visible;

    public Visibility NoDetailVisibility =>
        SelectedVersionEntry is null ? Visibility.Visible : Visibility.Collapsed;

    public string SelectedVersionTitle => SelectedVersionEntry?.Id ?? "选择一个版本";

    public string SelectedVersionKindText => SelectedVersionEntry?.KindText ?? string.Empty;

    public string SelectedVersionReleasedText => SelectedVersionEntry?.ReleasedText ?? "—";

    public Visibility PlanVisibility =>
        _downloadPlan is not null ? Visibility.Visible : Visibility.Collapsed;

    public Visibility PlanLoadingVisibility =>
        _isPlanLoading ? Visibility.Visible : Visibility.Collapsed;

    public Visibility PlanErrorVisibility =>
        _downloadPlanError is null ? Visibility.Collapsed : Visibility.Visible;

    public string PlanErrorText => $"无法计算安装内容：{_downloadPlanError}";

    public string PlanJavaText => _downloadPlan?.JavaText ?? "—";

    public string PlanFilesText => _downloadPlan?.FilesText ?? "—";

    public string PlanSizeText => _downloadPlan?.SizeText ?? "—";

    public bool CanInstall => SelectedVersionEntry is not null && !IsGameDownloadRunning;

    public bool IsGameDownloadRunning
    {
        get => _isGameDownloadRunning;
        private set
        {
            if (Set(ref _isGameDownloadRunning, value))
            {
                Raise(nameof(CanInstall));
                Raise(nameof(DownloadRunningVisibility));
            }
        }
    }

    public Visibility DownloadRunningVisibility =>
        IsGameDownloadRunning ? Visibility.Visible : Visibility.Collapsed;

    // ---- News ---------------------------------------------------------

    public ObservableCollection<NewsItem> News { get; } = new();

    public Visibility NewsVisibility =>
        News.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

    public Visibility NewsEmptyVisibility =>
        News.Count > 0 ? Visibility.Collapsed : Visibility.Visible;

    // ---- Navigation and instance actions -----------------------------

    public void SelectSection(AppSection section) => Selection = section;

    public InstanceRow? FindInstance(string? id) =>
        id is null ? null : Instances.FirstOrDefault(instance => instance.Id == id);

    public void SelectHomeInstance(InstanceRow instance) => SetCurrentInstance(instance.Id);

    public void OpenInstance(InstanceRow instance)
    {
        SetCurrentInstance(instance.Id);
        LibrarySelectionId = instance.Id;
        RequestSection(AppSection.Library);
    }

    public void FocusLibrarySearch()
    {
        RequestSection(AppSection.Library);
        LibrarySelectionId ??= CurrentInstance?.Id;
    }

    public void NewInstance()
    {
        DownloadsCategory = DownloadsCategory.Game;
        RequestSection(AppSection.Downloads);
        _ = LoadDownloadVersionsAsync();
    }

    public void ManageInstance(InstanceRow instance)
    {
        SetCurrentInstance(instance.Id);
        LibrarySelectionId = instance.Id;
        SettingsRequested?.Invoke(this, instance.Id);
    }

    public void ManageCurrentInstance()
    {
        if (CurrentInstance is { } instance)
        {
            ManageInstance(instance);
        }
    }

    public async Task StartCurrentGameAsync()
    {
        if (CurrentInstance is { } instance)
        {
            await StartGameAsync(instance);
        }
    }

    /// <summary>
    /// Starts an instance. An incomplete instance is completed first
    /// (PCL-style) instead of dead-ending with `error.launch.not_installed`.
    /// </summary>
    public async Task StartGameAsync(InstanceRow instance)
    {
        SetCurrentInstance(instance.Id);
        if (!instance.Installed)
        {
            if (IsDownloading(instance))
            {
                ShowNotice($"“{instance.Name}”的资源仍在下载，请稍候再启动", InfoBarSeverity.Informational);
            }
            else
            {
                await CompleteResourcesAsync(instance);
            }

            return;
        }

        if (SessionRunning)
        {
            ShowAlert("无法启动游戏", "已经有一个游戏在运行了。");
            return;
        }

        if (IsSessionLoading)
        {
            return;
        }

        IsSessionLoading = true;
        try
        {
            await CoreBridge.RpcAsync(
                "launch.start",
                Params(
                    ("instanceId", instance.Id),
                    ("username", GameConfig.Username)));
            // Keep the launch indicator until game.started arrives; the event
            // handler clears IsSessionLoading.
        }
        catch (Exception error)
        {
            IsSessionLoading = false;
            ShowError($"无法启动“{instance.Name}”", error);
        }
    }

    public async Task StopGameAsync()
    {
        if (!SessionRunning)
        {
            ShowNotice("没有正在运行的游戏", InfoBarSeverity.Informational);
            return;
        }

        _stopRequested = true;
        try
        {
            var payload = await CoreBridge.RpcAsync("session.stop");
            if (!payload.Bool("stopped"))
            {
                _stopRequested = false;
                ShowNotice("没有正在运行的游戏", InfoBarSeverity.Informational);
            }
        }
        catch (Exception error)
        {
            _stopRequested = false;
            ShowError("无法停止游戏", error);
        }
    }

    /// <summary>PCL-style: pressing play on an incomplete instance downloads its resources.</summary>
    public async Task CompleteResourcesAsync(InstanceRow instance)
    {
        if (IsGameDownloadRunning)
        {
            ShowNotice("已有下载任务在进行", InfoBarSeverity.Informational);
            return;
        }

        try
        {
            var response = await CoreBridge.RpcAsync(
                "install.start",
                Params(
                    ("version", instance.Minecraft),
                    ("instanceId", instance.Id),
                    ("instanceName", instance.Name)));
            _pendingDownloadInstanceId = instance.Id;
            _pendingDownloadTaskId = response.Str("taskId");
            IsGameDownloadRunning = true;
            DownloadsCategory = DownloadsCategory.Game;
            RequestSection(AppSection.Downloads);
            ShowNotice($"正在补全“{instance.Name}”的游戏资源", InfoBarSeverity.Informational);
            await RefreshCoreDataAsync();
            await CompletePendingInstallIfFinishedAsync();
        }
        catch (Exception error)
        {
            ShowError($"无法补全“{instance.Name}”的资源", error);
        }
    }

    public async Task ToggleFavoriteAsync(InstanceRow instance)
    {
        try
        {
            await CoreBridge.RpcAsync(
                "instances.favorite",
                Params(("id", instance.Id), ("favorite", !instance.Favorite)));
            await RefreshCoreDataAsync();
        }
        catch (Exception error)
        {
            ShowError("无法更新收藏", error);
        }
    }

    public async Task<bool> DeleteInstanceAsync(InstanceRow instance)
    {
        try
        {
            await CoreBridge.RpcAsync("instances.delete", Params(("id", instance.Id)));
            ShowNotice($"已删除“{instance.Name}”", InfoBarSeverity.Success);
            await RefreshCoreDataAsync();
            return true;
        }
        catch (Exception error)
        {
            ShowError($"无法删除“{instance.Name}”", error);
            return false;
        }
    }

    public async Task OpenInstanceFolderAsync(InstanceRow instance)
    {
        var path = Path.Combine(Settings.DataRoot, "instances", instance.Id);
        try
        {
            var payload = await CoreBridge.RpcAsync("instances.dir", Params(("id", instance.Id)));
            var gameDir = payload.Str("gameDir");
            if (!string.IsNullOrWhiteSpace(gameDir))
            {
                path = gameDir!;
            }
        }
        catch (Exception error)
        {
            Debug.WriteLine($"instances.dir failed: {error.Message}");
        }

        try
        {
            if (Directory.Exists(path))
            {
                Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            }
            else
            {
                ShowNotice("实例目录尚未创建", InfoBarSeverity.Warning);
            }
        }
        catch (Exception error)
        {
            ShowError("无法打开实例目录", error);
        }
    }

    public async Task ImportMinecraftDirectoryAsync(string directory)
    {
        try
        {
            await CoreBridge.RpcAsync(
                "instances.import",
                Params(("directory", directory)));
            var name = Path.GetFileName(directory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar));
            ShowNotice($"已导入“{(string.IsNullOrEmpty(name) ? directory : name)}”", InfoBarSeverity.Success);
            await RefreshCoreDataAsync();
        }
        catch (Exception error)
        {
            ShowError("无法导入 .minecraft 目录", error);
        }
    }

    /// <summary>Persists one instance setting; a `null` value inherits `[game]`.</summary>
    public async Task<bool> ConfigureInstanceAsync(string instanceId, string setting, JsonNode? value)
    {
        try
        {
            var updates = new JsonArray
            {
                new JsonObject
                {
                    ["setting"] = setting,
                    ["value"] = value?.DeepClone(),
                },
            };

            await CoreBridge.RpcAsync(
                "instances.settings.set",
                new JsonObject { ["id"] = instanceId, ["updates"] = updates });
            await RefreshCoreDataAsync();
            return true;
        }
        catch (Exception error)
        {
            ShowError("设置保存失败", error);
            return false;
        }
    }

    public async Task CancelTaskAsync(string taskId)
    {
        try
        {
            await CoreBridge.RpcAsync("tasks.cancel", Params(("taskId", taskId)));
            await RefreshTasksAsync();
        }
        catch (Exception error)
        {
            ShowError("无法取消任务", error);
        }
    }

    // ---- Downloads flow (HMCL/PCL) -----------------------------------

    public void SetDownloadsCategory(DownloadsCategory category)
    {
        if (DownloadsCategory == category)
        {
            return;
        }

        DownloadsCategory = category;
        if (category == DownloadsCategory.Game)
        {
            _ = LoadDownloadVersionsAsync();
        }
    }

    public void SetDownloadChannel(DownloadVersionChannel channel)
    {
        if (DownloadVersionChannel == channel)
        {
            return;
        }

        DownloadVersionChannel = channel;
        if (DownloadsCategory == DownloadsCategory.Game)
        {
            _ = ReloadDownloadVersionsAsync();
        }
    }

    public void SetContentLoader(ContentLoaderFilter loader)
    {
        DownloadContentLoader = loader;
    }

    public async Task LoadDownloadVersionsAsync()
    {
        if (DownloadsCategory != DownloadsCategory.Game)
        {
            return;
        }

        if (_loadedChannel == DownloadVersionChannel)
        {
            await RefreshDownloadPlanAsync();
            return;
        }

        await FetchDownloadVersionsAsync();
    }

    /// <summary>Called by the channel dropdown; clears the list before reloading.</summary>
    public async Task ReloadDownloadVersionsAsync()
    {
        _allDownloadVersions.Clear();
        DownloadVersions.Clear();
        _loadedChannel = null;
        _downloadSelectedVersionId = null;
        _downloadPlan = null;
        _downloadPlanError = null;
        _isPlanLoading = false;
        _downloadVersionsError = null;
        RaiseDetailProperties();
        RaisePlanProperties();
        RaiseVersionStates();
        await FetchDownloadVersionsAsync();
    }

    private async Task FetchDownloadVersionsAsync()
    {
        var channel = DownloadVersionChannel;
        if (_loadingChannel == channel)
        {
            return;
        }

        _loadingChannel = channel;
        _downloadVersionsGeneration++;
        var generation = _downloadVersionsGeneration;
        _isLoadingDownloadVersions = true;
        _downloadVersionsError = null;
        RaiseVersionStates();

        try
        {
            var payload = await CoreBridge.RpcAsync(
                "versions.list",
                Params(
                    ("kind", channel.KindValue()),
                    ("limit", 500),
                    ("refresh", false)));

            if (generation != _downloadVersionsGeneration || channel != DownloadVersionChannel)
            {
                return;
            }

            _allDownloadVersions.Clear();
            _allDownloadVersions.AddRange(CoreWire.ParseVersions(payload).Select(VersionEntry.From));
            _loadedChannel = channel;
            RebuildVisibleVersions();
            _downloadSelectedVersionId = DownloadVersions.FirstOrDefault()?.Id;
            RaiseDetailProperties();
            if (_downloadSelectedVersionId is null)
            {
                _downloadPlan = null;
                _downloadPlanError = null;
                _isPlanLoading = false;
                RaisePlanProperties();
            }
            else
            {
                await RefreshDownloadPlanAsync();
            }
        }
        catch (Exception error)
        {
            if (generation != _downloadVersionsGeneration || channel != DownloadVersionChannel)
            {
                return;
            }

            _downloadVersionsError = Describe(error);
            ShowError("无法读取版本清单", error);
        }
        finally
        {
            if (generation == _downloadVersionsGeneration)
            {
                _loadingChannel = null;
                _isLoadingDownloadVersions = false;
                RaiseVersionStates();
            }
        }
    }

    public void SelectDownloadVersion(string? versionId)
    {
        if (_downloadSelectedVersionId == versionId)
        {
            return;
        }

        _downloadSelectedVersionId = versionId;
        RaiseDetailProperties();
        _ = RefreshDownloadPlanAsync();
    }

    private async Task RefreshDownloadPlanAsync()
    {
        var version = _downloadSelectedVersionId;
        if (string.IsNullOrEmpty(version))
        {
            _downloadPlan = null;
            _downloadPlanError = null;
            _isPlanLoading = false;
            RaisePlanProperties();
            return;
        }

        _planGeneration++;
        var generation = _planGeneration;
        _downloadPlanError = null;
        _isPlanLoading = true;
        RaisePlanProperties();

        try
        {
            var payload = await CoreBridge.RpcAsync("version.plan", Params(("version", version)));
            if (generation != _planGeneration || version != _downloadSelectedVersionId)
            {
                return;
            }

            _downloadPlan = CoreWire.ParsePlan(payload) is { } plan ? PlanView.From(plan) : null;
        }
        catch (Exception error)
        {
            if (generation != _planGeneration || version != _downloadSelectedVersionId)
            {
                return;
            }

            _downloadPlan = null;
            _downloadPlanError = Describe(error);
        }
        finally
        {
            if (generation == _planGeneration)
            {
                _isPlanLoading = false;
                RaisePlanProperties();
            }
        }
    }

    /// <summary>
    /// Starts the download after the install dialog confirms loader, version and
    /// name. No per-instance detail options live here: after the finished event
    /// the shell jumps to 游戏设置 (PCL behaviour).
    /// </summary>
    public async Task StartGameInstallAsync(string version, string instanceName)
    {
        if (IsInitialLoading)
        {
            await RefreshCoreDataAsync();
        }

        if (IsGameDownloadRunning)
        {
            throw new CoreException(new CoreErrorPayload(
                "conflict",
                "error.task.already_running",
                "已有下载任务在进行，请等待完成或取消。"));
        }

        var name = instanceName.Trim();
        var id = UniqueInstanceId(name, version);
        var response = await CoreBridge.RpcAsync(
            "install.start",
            Params(
                ("version", version),
                ("instanceId", id),
                ("instanceName", name.Length == 0 ? id : name)));

        _pendingDownloadInstanceId = id;
        _pendingDownloadTaskId = response.Str("taskId");
        IsGameDownloadRunning = true;
        DownloadsCategory = DownloadsCategory.Game;
        RequestSection(AppSection.Downloads);
        ShowNotice($"已开始安装 {version}", InfoBarSeverity.Informational);
        await RefreshCoreDataAsync();
        await CompletePendingInstallIfFinishedAsync();
    }

    public async Task RequestInstallForVersionAsync(string version)
    {
        if (!string.IsNullOrWhiteSpace(version))
        {
            await LoadDownloadVersionsAsync();
            SelectDownloadVersion(version);
        }

        InstallDialogRequested?.Invoke(this, version ?? string.Empty);
    }

    public void OpenInstallDialogForSelection() =>
        InstallDialogRequested?.Invoke(this, _downloadSelectedVersionId ?? string.Empty);

    /// <summary>Safe directory id for a display name; collisions get a numeric suffix.</summary>
    public string UniqueInstanceId(string name, string version)
    {
        var sanitized = InstanceFormat.SanitizedInstanceId(name);
        var fallback = InstanceFormat.SanitizedInstanceId(version);
        var baseId = sanitized.Length > 0 ? sanitized : fallback.Length > 0 ? fallback : "instance";
        var id = baseId;
        var suffix = 2;
        while (Instances.Any(instance => instance.Id == id))
        {
            id = $"{baseId}-{suffix}";
            suffix++;
        }

        return id;
    }

    public bool IsDownloading(InstanceRow instance) =>
        ActiveTasks.Any(task => task.Title.Contains(instance.Minecraft, StringComparison.Ordinal));

    // ---- Core refresh -------------------------------------------------

    /// <summary>
    /// Reloads instances, the app snapshot, `[game]` and the task queue as one
    /// coalesced pass. Concurrent callers await the same pass instead of
    /// starting a parallel one, so a page that awaits the refresh can rely on
    /// the collections being current.
    /// </summary>
    public Task RefreshCoreDataAsync()
    {
        if (_refreshTask is { } running)
        {
            _refreshAgain = true;
            return running;
        }

        var task = RefreshCoreLoopAsync();
        _refreshTask = task.IsCompleted ? null : task;
        return task;
    }

    private async Task RefreshCoreLoopAsync()
    {
        try
        {
            do
            {
                _refreshAgain = false;
                await RefreshInstancesAsync();
                await RefreshSnapshotAsync();
                await RefreshGameConfigAsync();
                await RefreshTasksAsync();
            }
            while (_refreshAgain);
        }
        finally
        {
            _refreshTask = null;
            IsInitialLoading = false;
        }
    }

    private async Task RefreshInstancesAsync()
    {
        try
        {
            var payload = await CoreBridge.RpcAsync("instances.list");
            var rows = CoreWire.ParseInstances(payload).Select(InstanceRow.From).ToList();

            Instances.Clear();
            foreach (var row in rows)
            {
                Instances.Add(row);
            }

            if (FindInstance(_currentInstanceId) is null)
            {
                _currentInstanceId = rows.FirstOrDefault()?.Id ?? string.Empty;
            }

            if (FindInstance(_librarySelectionId) is null)
            {
                _librarySelectionId = rows.FirstOrDefault()?.Id;
            }

            RefreshHomeInstances();
            RebuildLibraryGroups();
            RaiseCurrentInstanceProperties();
            RaiseSelectionProperties();
            Raise(nameof(HomeSubtitle));
            Raise(nameof(LibrarySubtitle));
            Raise(nameof(HeroVisibility));
            Raise(nameof(EmptyHeroVisibility));
            DataRefreshed?.Invoke(this, EventArgs.Empty);
            StateChanged?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception error)
        {
            Debug.WriteLine($"instances.list failed: {error.Message}");
            if (Instances.Count == 0 && !_instancesAlertShown)
            {
                _instancesAlertShown = true;
                if (error is CoreException { Error.Kind: "unavailable" })
                {
                    ShowNotice("未找到启动器核心 fg_ffi.dll，界面以空数据运行。", InfoBarSeverity.Warning);
                }
                else
                {
                    ShowError("无法读取实例列表", error);
                }
            }
        }
    }

    private async Task RefreshSnapshotAsync()
    {
        try
        {
            var payload = await CoreBridge.RpcAsync("app.snapshot");
            AppSnapshot = CoreWire.ParseAppSnapshot(payload);
        }
        catch (Exception error)
        {
            Debug.WriteLine($"app.snapshot failed: {error.Message}");
        }
    }

    private async Task RefreshGameConfigAsync()
    {
        try
        {
            var payload = await CoreBridge.RpcAsync("gameConfig.get");
            GameConfig = CoreWire.ParseGameConfig(payload) ?? GameConfig;
        }
        catch (Exception error)
        {
            Debug.WriteLine($"gameConfig.get failed: {error.Message}");
        }
    }

    private async Task RefreshTasksAsync()
    {
        try
        {
            var payload = await CoreBridge.RpcAsync("tasks.list");
            var rows = CoreWire.ParseTasks(payload)
                .Where(row => row.Id.Length > 0)
                .Select(TaskRow.From)
                .ToList();

            Tasks.Clear();
            ActiveTasks.Clear();
            foreach (var row in rows)
            {
                Tasks.Add(row);
                if (row.IsActive)
                {
                    ActiveTasks.Add(row);
                }
            }

            RaiseQueueState();
            StateChanged?.Invoke(this, EventArgs.Empty);
        }
        catch (Exception error)
        {
            Debug.WriteLine($"tasks.list failed: {error.Message}");
        }
    }

    private async Task RefreshNewsAsync()
    {
        try
        {
            var payload = await CoreBridge.RpcAsync(
                "versions.news",
                Params(("limit", 2), ("refresh", false)));
            var entries = CoreWire.ParseVersions(payload, "news");

            News.Clear();
            foreach (var entry in entries)
            {
                News.Add(new NewsItem(
                    entry.Id,
                    $"Java 版 {entry.Id}",
                    $"发布于 {InstanceFormat.ReleaseDate(entry.Released)}"));
            }

            Raise(nameof(NewsVisibility));
            Raise(nameof(NewsEmptyVisibility));
        }
        catch (Exception error)
        {
            Debug.WriteLine($"versions.news failed: {error.Message}");
        }
    }

    private void RefreshHomeInstances()
    {
        IEnumerable<InstanceRow> source = HomeFilter switch
        {
            HomeInstanceFilter.Favorites => Instances.Where(instance => instance.Favorite),
            HomeInstanceFilter.All => Instances,
            _ => Instances.Take(6),
        };

        var filtered = source.Where(instance => instance.Matches(LibraryQuery)).ToList();
        HomeInstances.Clear();
        foreach (var row in filtered)
        {
            HomeInstances.Add(row);
        }
    }

    private void RebuildLibraryGroups()
    {
        var filtered = Instances
            .Where(instance => instance.Matches(LibraryQuery))
            .ToList();
        LibraryGroups = LibraryOrganizer.Sections(filtered, LibrarySort);
        Raise(nameof(LibraryGroups));
        Raise(nameof(LibraryEmptyVisibility));
    }

    private void RebuildVisibleVersions()
    {
        var query = DownloadsQuery.Trim();
        DownloadVersions.Clear();
        foreach (var version in _allDownloadVersions)
        {
            if (query.Length == 0
                || version.Id.Contains(query, StringComparison.OrdinalIgnoreCase))
            {
                DownloadVersions.Add(version);
            }
        }

        RaiseVersionStates();
        RaiseDetailProperties();
    }

    // ---- Events -------------------------------------------------------

    private async Task InitializeAsync()
    {
        if (_initialized)
        {
            return;
        }

        _initialized = true;
        if (!CoreBridge.IsAvailable)
        {
            ShowNotice("未找到启动器核心 fg_ffi.dll，界面以空数据运行。", InfoBarSeverity.Warning);
        }
        else if (Settings.ConfigError is { } configError)
        {
            ShowNotice(
                $"读取 launcher.toml 失败，已使用默认外观。{configError.TechnicalDetail}",
                InfoBarSeverity.Warning);
        }

        await RefreshCoreDataAsync();
        _ = RefreshNewsAsync();
    }

    private async Task DrainEventsAsync()
    {
        if (_draining || !CoreBridge.IsAvailable)
        {
            return;
        }

        _draining = true;
        try
        {
            var events = await Task.Run(CoreBridge.NextEvents);
            if (events.Count == 0)
            {
                return;
            }

            foreach (var raw in events)
            {
                if (CoreEvent.Parse(raw) is { } parsed)
                {
                    await HandleEventAsync(parsed);
                }
            }

            await RefreshTasksAsync();
        }
        catch (Exception error)
        {
            Debug.WriteLine($"event pump failed: {error.Message}");
        }
        finally
        {
            _draining = false;
        }
    }

    private async Task HandleEventAsync(CoreEvent coreEvent)
    {
        switch (coreEvent)
        {
            case CoreEvent.TaskProgress:
                break;

            case CoreEvent.TaskFinished finished:
                await HandleTaskFinishedAsync(finished);
                break;

            case CoreEvent.GameStarted started:
                _stopRequested = false;
                _sessionPid = started.Pid;
                SessionRunning = true;
                IsSessionLoading = false;
                Raise(nameof(SessionPidText));
                ShowNotice(
                    started.Pid is { } pid ? $"游戏已启动，进程 {pid}" : "游戏已启动",
                    InfoBarSeverity.Success);
                StateChanged?.Invoke(this, EventArgs.Empty);
                break;

            case CoreEvent.GameWindowReady:
                break;

            case CoreEvent.GameLine:
                // The core keeps the log (game.log.recent); a log pane lands
                // with M9.
                break;

            case CoreEvent.GameExited exited:
                await HandleGameExitedAsync(exited);
                break;
        }
    }

    private async Task HandleTaskFinishedAsync(CoreEvent.TaskFinished finished)
    {
        var title = InstanceFormat.TaskTitle(finished.Name);
        var isOurDownload = finished.TaskId == _pendingDownloadTaskId;
        switch (finished.State)
        {
            case "succeeded":
                ShowNotice($"{title} 完成", InfoBarSeverity.Success);
                if (isOurDownload)
                {
                    await FinishGameDownloadAsync();
                }

                break;

            case "failed":
                var detail = finished.Detail.FirstOrDefault() ?? "未知原因";
                ShowAlert(
                    $"{title} 失败",
                    detail,
                    finished.Detail.Skip(1).DefaultIfEmpty(null).FirstOrDefault());
                if (isOurDownload)
                {
                    IsGameDownloadRunning = false;
                }

                break;

            case "cancelled":
                ShowNotice($"{title} 已取消", InfoBarSeverity.Informational);
                if (isOurDownload)
                {
                    IsGameDownloadRunning = false;
                }

                break;
        }

        await RefreshCoreDataAsync();
    }

    /// <summary>
    /// Covers the install race where the graph finishes before
    /// `install.start` returns and assigns the pending task id: the finished
    /// event was then handled without knowing the task was ours.
    /// </summary>
    private async Task CompletePendingInstallIfFinishedAsync()
    {
        var taskId = _pendingDownloadTaskId;
        if (taskId is null || _pendingDownloadInstanceId is null)
        {
            return;
        }

        var task = Tasks.FirstOrDefault(row => row.Id == taskId);
        switch (task?.State)
        {
            case "succeeded":
                await FinishGameDownloadAsync();
                break;
            case "failed":
            case "cancelled":
                _pendingDownloadTaskId = null;
                _pendingDownloadInstanceId = null;
                IsGameDownloadRunning = false;
                break;
        }
    }

    /// <summary>
    /// After the game files land, walk the user to 游戏设置 of the freshly
    /// created instance (PCL-style: download → 设置 → 启动).
    /// </summary>
    private async Task FinishGameDownloadAsync()
    {
        IsGameDownloadRunning = false;
        _pendingDownloadTaskId = null;
        var instanceId = _pendingDownloadInstanceId;
        _pendingDownloadInstanceId = null;
        if (string.IsNullOrEmpty(instanceId))
        {
            return;
        }

        await RefreshCoreDataAsync();
        if (FindInstance(instanceId) is null)
        {
            return;
        }

        SetCurrentInstance(instanceId!);
        LibrarySelectionId = instanceId;
        RequestSection(AppSection.Library);
        await Task.Yield();
        SettingsRequested?.Invoke(this, instanceId!);
    }

    private async Task HandleGameExitedAsync(CoreEvent.GameExited exited)
    {
        var wasStopped = _stopRequested;
        _stopRequested = false;
        SessionRunning = false;
        IsSessionLoading = false;
        _sessionPid = null;
        Raise(nameof(SessionPidText));

        if (wasStopped)
        {
            ShowNotice("游戏已停止", InfoBarSeverity.Informational);
        }
        else if (exited.ExitCode == 0)
        {
            ShowNotice(
                $"游戏已退出（{InstanceFormat.PlayTime((int)Math.Min(exited.PlaySeconds / 60, (ulong)int.MaxValue))}）",
                InfoBarSeverity.Success);
        }
        else
        {
            ShowAlert(
                "游戏异常退出",
                $"游戏进程以代码 {(exited.ExitCode?.ToString() ?? "signal")} 结束。",
                "请查看实例目录 logs/ 下的最新日志；日志与崩溃报告面板在 M9 完整接入。");
        }

        await RefreshCoreDataAsync();
    }

    private void SetCurrentInstance(string id)
    {
        if (_currentInstanceId == id)
        {
            return;
        }

        _currentInstanceId = id;
        RaiseCurrentInstanceProperties();
    }

    private void RequestSection(AppSection section)
    {
        Selection = section;
        SectionRequested?.Invoke(this, section);
    }

    // ---- Property helpers --------------------------------------------

    public void ShowNotice(string message, InfoBarSeverity severity = InfoBarSeverity.Informational) =>
        NoticeRaised?.Invoke(this, new Notice(message, severity));

    public void ShowAlert(string title, string message, string? detail = null) =>
        AlertRaised?.Invoke(this, new AppAlert(title, message, detail));

    public void ShowError(string title, Exception error)
    {
        Debug.WriteLine($"{title}: {error}");
        if (error is CoreException core)
        {
            AlertRaised?.Invoke(this, new AppAlert(title, core.Error.UserMessage, core.Error.Detail));
        }
        else
        {
            AlertRaised?.Invoke(this, new AppAlert(title, error.Message));
        }
    }

    public static string Describe(Exception error) =>
        error is CoreException core ? core.Error.UserMessage : error.Message;

    private void RaiseCurrentInstanceProperties()
    {
        Raise(nameof(CurrentInstanceId));
        Raise(nameof(CurrentInstance));
        Raise(nameof(CurrentInstanceName));
        Raise(nameof(CurrentInstanceIcon));
        Raise(nameof(CurrentInstanceMeta));
        Raise(nameof(CurrentInstanceStatusText));
        Raise(nameof(CanStartGame));
        Raise(nameof(CanStopGame));
    }

    private void RaiseSelectionProperties()
    {
        Raise(nameof(SelectedLibraryInstance));
        Raise(nameof(PreviewVisibility));
        Raise(nameof(NoPreviewVisibility));
        Raise(nameof(HasSelectedInstance));
        Raise(nameof(SelectedName));
        Raise(nameof(SelectedSubtitle));
        Raise(nameof(SelectedMinecraftText));
        Raise(nameof(SelectedLoaderText));
        Raise(nameof(SelectedModsText));
        Raise(nameof(SelectedSizeText));
        Raise(nameof(SelectedPlayTimeText));
        Raise(nameof(SelectedLastPlayedText));
        Raise(nameof(SelectedStatusText));
        Raise(nameof(SelectedFavoriteVisibility));
        Raise(nameof(SelectedFavoriteOpacity));
    }

    private void RaiseQueueState()
    {
        Raise(nameof(HasActiveTasks));
        Raise(nameof(ActiveDownloadCount));
        Raise(nameof(QueueVisibility));
        Raise(nameof(EmptyQueueVisibility));
        Raise(nameof(DownloadsSpeedText));
        Raise(nameof(CanInstall));
    }

    private void RaiseVersionStates()
    {
        Raise(nameof(VersionLoadingVisibility));
        Raise(nameof(VersionListVisibility));
        Raise(nameof(VersionEmptyVisibility));
        Raise(nameof(VersionErrorVisibility));
        Raise(nameof(DownloadVersionsError));
        Raise(nameof(DetailVisibility));
        Raise(nameof(NoDetailVisibility));
        Raise(nameof(CanInstall));
        Raise(nameof(SelectedVersionEntry));
        Raise(nameof(SelectedVersionTitle));
        Raise(nameof(SelectedVersionKindText));
        Raise(nameof(SelectedVersionReleasedText));
    }

    private void RaiseDetailProperties()
    {
        Raise(nameof(SelectedVersionEntry));
        Raise(nameof(DetailVisibility));
        Raise(nameof(NoDetailVisibility));
        Raise(nameof(SelectedVersionTitle));
        Raise(nameof(SelectedVersionKindText));
        Raise(nameof(SelectedVersionReleasedText));
        Raise(nameof(CanInstall));
    }

    private void RaisePlanProperties()
    {
        Raise(nameof(PlanVisibility));
        Raise(nameof(PlanLoadingVisibility));
        Raise(nameof(PlanErrorVisibility));
        Raise(nameof(PlanErrorText));
        Raise(nameof(PlanJavaText));
        Raise(nameof(PlanFilesText));
        Raise(nameof(PlanSizeText));
    }

    private void RaiseCategoryProperties()
    {
        Raise(nameof(GameCategoryVisibility));
        Raise(nameof(ComingSoonVisibility));
        Raise(nameof(ComingSoonTitle));
        Raise(nameof(ComingSoonDescription));
        Raise(nameof(ComingSoonMilestone));
        Raise(nameof(ComingSoonIcon));
        Raise(nameof(ChannelVisibility));
        Raise(nameof(LoaderFilterVisibility));
    }

    private static JsonObject Params(params (string Key, JsonNode? Value)[] values)
    {
        var parameters = new JsonObject();
        foreach (var (key, value) in values)
        {
            parameters[key] = value;
        }

        return parameters;
    }

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }

        field = value;
        Raise(propertyName);
        return true;
    }

    private void Raise(string? propertyName) =>
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName ?? string.Empty));
}
