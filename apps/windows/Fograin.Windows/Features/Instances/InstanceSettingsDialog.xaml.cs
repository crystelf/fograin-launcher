using System.Text.Json.Nodes;
using Fograin.Windows.Core;
using Fograin.Windows.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;

namespace Fograin.Windows.Features.Instances;

/// <summary>
/// 游戏设置 (macOS InstanceSettingsScreen): launch/display options write the
/// instance `[override]` section through the core, `null` inherits `[game]`.
/// Content rows owned by M8/M9 stay disabled.
/// </summary>
public sealed partial class InstanceSettingsDialog : ContentDialog
{
    private static readonly int[] MemoryChoices = [0, 2, 4, 6, 8, 12, 16];

    private static readonly ResolutionOption[] ResolutionOptions =
    [
        new("1280 × 720", 1280, 720),
        new("1600 × 900", 1600, 900),
        new("1920 × 1080", 1920, 1080),
        new("2560 × 1440", 2560, 1440),
    ];

    private readonly InstanceRow _instance;
    private CoreOverrides _overrides = new(null, null, null, null, null);
    private bool _loading;
    private bool _applying;

    public InstanceSettingsDialog(InstanceRow instance)
    {
        InitializeComponent();

        _instance = instance;
        HeaderTileIcon.Symbol = instance.Icon;
        HeaderNameText.Text = instance.Name;
        HeaderSubtitleText.Text = $"游戏设置 · {instance.Name} 的启动与显示选项";
        OverviewVersionText.Text = instance.DisplayVersion;
        OverviewDetailText.Text = instance.Subtitle;
        ContentMilestones.ItemsSource = MilestoneRows;

        BuildMemoryChoices();
        BuildResolutionChoices();

        Opened += async (_, _) => await LoadAsync();
    }

    public IReadOnlyList<MilestoneRow> MilestoneRows { get; } =
    [
        new("模组管理", "M8"),
        new("资源包管理", "M8"),
        new("光影包管理", "M8"),
        new("世界管理", "M9"),
        new("原理图管理", "M9"),
        new("更新整合包", "M9"),
        new("日志与崩溃报告", "M9"),
        new("导出与备份", "M9"),
    ];

    private void BuildMemoryChoices()
    {
        MemoryComboBox.Items.Clear();
        foreach (var gigabytes in MemoryChoices)
        {
            MemoryComboBox.Items.Add(gigabytes == 0 ? "自动" : $"{gigabytes} GB");
        }
    }

    private void BuildResolutionChoices()
    {
        ResolutionComboBox.Items.Clear();
        ResolutionComboBox.Items.Add("跟随游戏默认");
        foreach (var option in ResolutionOptions)
        {
            ResolutionComboBox.Items.Add(option.Label);
        }
    }

    private async Task LoadAsync()
    {
        _loading = true;
        ErrorBar.IsOpen = false;
        LoadingOverlay.Visibility = Visibility.Visible;
        LoadingRing.IsActive = true;

        try
        {
            var payload = await CoreBridge.RpcAsync(
                "instances.settings.get",
                new JsonObject { ["id"] = _instance.Id });
            var settings = CoreWire.ParseInstanceSettings(payload)
                ?? throw new CoreException(
                    "error.instance.settings",
                    "instances.settings.get returned no overrides");

            _overrides = settings.Overrides;
            ApplyOverrides();
        }
        catch (Exception error)
        {
            ErrorBar.Message = Describe(error);
            ErrorBar.IsOpen = true;
        }
        finally
        {
            _loading = false;
            LoadingRing.IsActive = false;
            LoadingOverlay.Visibility = Visibility.Collapsed;
        }
    }

    private void ApplyOverrides()
    {
        _applying = true;
        try
        {
            var memoryGb = _overrides.MemoryMb is { } memory ? memory / 1024 : 0;
            SelectMemory(memoryGb);
            JvmBox.Text = _overrides.JvmArgs ?? string.Empty;
            JavaRuntimeText.Text = string.IsNullOrWhiteSpace(_overrides.Java)
                ? "自动发现"
                : _overrides.Java!;
            FullscreenToggle.IsOn = _overrides.Fullscreen == true;

            var resolutionIndex = 0;
            if (_overrides.Resolution is { Count: >= 2 } resolution)
            {
                var match = Array.FindIndex(
                    ResolutionOptions,
                    option => option.Width == resolution[0] && option.Height == resolution[1]);
                if (match >= 0)
                {
                    resolutionIndex = match + 1;
                }
            }

            ResolutionComboBox.SelectedIndex = resolutionIndex;
        }
        finally
        {
            _applying = false;
        }
    }

    private void SelectMemory(int gigabytes)
    {
        var index = Array.IndexOf(MemoryChoices, gigabytes);
        if (index < 0)
        {
            MemoryComboBox.Items.Add($"{gigabytes} GB");
            index = MemoryComboBox.Items.Count - 1;
        }

        MemoryComboBox.SelectedIndex = index;
    }

    private int SelectedMemoryGb()
    {
        var index = MemoryComboBox.SelectedIndex;
        if (index < 0)
        {
            return 0;
        }

        if (index < MemoryChoices.Length)
        {
            return MemoryChoices[index];
        }

        var text = MemoryComboBox.Items[index]?.ToString() ?? string.Empty;
        var head = text.Split(' ')[0];
        return int.TryParse(head, out var gigabytes) ? gigabytes : 0;
    }

    private async void MemoryComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_applying || _loading)
        {
            return;
        }

        var gigabytes = SelectedMemoryGb();
        _overrides = _overrides with { MemoryMb = gigabytes == 0 ? null : gigabytes * 1024 };
        await PersistAsync(
            "memory",
            gigabytes == 0 ? null : JsonValue.Create(gigabytes * 1024));
    }

    private async void FullscreenToggle_Toggled(object sender, RoutedEventArgs e)
    {
        if (_applying || _loading)
        {
            return;
        }

        var fullscreen = FullscreenToggle.IsOn;
        _overrides = _overrides with { Fullscreen = fullscreen ? true : null };
        await PersistAsync("fullscreen", fullscreen ? JsonValue.Create(true) : null);
    }

    private async void ResolutionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_applying || _loading)
        {
            return;
        }

        var index = ResolutionComboBox.SelectedIndex;
        if (index <= 0)
        {
            _overrides = _overrides with { Resolution = null };
            await PersistAsync("resolution", null);
            return;
        }

        var option = ResolutionOptions[index - 1];
        _overrides = _overrides with { Resolution = new[] { option.Width, option.Height } };
        await PersistAsync("resolution", new JsonArray(option.Width, option.Height));
    }

    private async void JvmBox_LostFocus(object sender, RoutedEventArgs e) => await PersistJvmAsync();

    private async void JvmBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != global::Windows.System.VirtualKey.Enter)
        {
            return;
        }

        e.Handled = true;
        await PersistJvmAsync();
    }

    private async Task PersistJvmAsync()
    {
        if (_applying || _loading)
        {
            return;
        }

        var text = JvmBox.Text.Trim();
        _overrides = _overrides with { JvmArgs = text.Length == 0 ? null : text };
        await PersistAsync("jvmArgs", text.Length == 0 ? null : JsonValue.Create(text));
    }

    private async Task PersistAsync(string setting, JsonNode? value)
    {
        var saved = await App.Model.ConfigureInstanceAsync(_instance.Id, setting, value);
        if (saved)
        {
            ErrorBar.IsOpen = false;
            return;
        }

        ErrorBar.Message = "设置保存失败，请重试。";
        ErrorBar.IsOpen = true;
    }

    private async void OpenFolder_Click(object sender, RoutedEventArgs e) =>
        await App.Model.OpenInstanceFolderAsync(_instance);

    private async void Delete_Click(object sender, RoutedEventArgs e)
    {
        if (await App.Model.DeleteInstanceAsync(_instance))
        {
            Hide();
        }
    }

    private static string Describe(Exception error) => error is CoreException core
        ? string.IsNullOrWhiteSpace(core.Error.Detail)
            ? core.Error.UserMessage
            : $"{core.Error.UserMessage}\n{core.Error.Detail}"
        : error.Message;

    private sealed record ResolutionOption(string Label, int Width, int Height);
}
