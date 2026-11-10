using Fograin.Windows.Core;
using Fograin.Windows.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Features.Downloads;

/// <summary>
/// Install dialog (macOS GameInstallSheet): the only place that asks for game
/// version, loader, loader version and instance name. Memory and display
/// options belong to 游戏设置 after the download.
/// </summary>
public sealed partial class GameInstallDialog : ContentDialog
{
    private readonly string _initialVersion;
    private bool _initializing = true;
    private bool _installing;
    private GameLoaderOption _loader = GameLoaderOption.All[0];

    public GameInstallDialog(string initialVersion)
    {
        InitializeComponent();

        _initialVersion = initialVersion?.Trim() ?? string.Empty;

        LoaderGrid.ItemsSource = GameLoaderOption.All;
        LoaderGrid.SelectedItem = _loader;

        BuildGameVersionOptions();
        SyncLoaderUi();
        UpdateFooter();

        _initializing = false;
    }

    private string? SelectedVersion => GameVersionComboBox.SelectedItem as string;

    private bool CanInstall =>
        _loader.IsAvailable
        && !_installing
        && !string.IsNullOrWhiteSpace(SelectedVersion);

    private void BuildGameVersionOptions()
    {
        var versions = new List<string>();
        if (_initialVersion.Length > 0)
        {
            versions.Add(_initialVersion);
        }

        foreach (var entry in App.Model.AllDownloadVersions)
        {
            if (!versions.Contains(entry.Id))
            {
                versions.Add(entry.Id);
            }
        }

        GameVersionComboBox.ItemsSource = versions;
        GameVersionComboBox.SelectedItem = _initialVersion.Length > 0 && versions.Contains(_initialVersion)
            ? _initialVersion
            : versions.FirstOrDefault();
        InstanceNameBox.PlaceholderText = SelectedVersion ?? "版本号";
    }

    private void GameVersionComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing)
        {
            return;
        }

        InstanceNameBox.PlaceholderText = SelectedVersion ?? "版本号";
        UpdateFooter();
    }

    private void LoaderGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing || LoaderGrid.SelectedItem is not GameLoaderOption option)
        {
            return;
        }

        _loader = option;
        ErrorBar.IsOpen = false;
        SyncLoaderUi();
        UpdateFooter();
    }

    private void SyncLoaderUi()
    {
        LoaderVersionPanel.Visibility = _loader.IsAvailable
            ? Visibility.Collapsed
            : Visibility.Visible;
        LoaderVersionLabel.Text = $"{_loader.Title} 版本";
        LoaderVersionComboBox.ItemsSource = _loader.PlaceholderVersions;
        LoaderVersionComboBox.SelectedIndex = _loader.PlaceholderVersions.Count > 0 ? 0 : -1;
        LoaderVersionHint.Text =
            $"{_loader.Title} 安装器将在 M5 接入；版本列表是预留位。当前请选择原版安装。";
    }

    private void UpdateFooter()
    {
        FooterText.Text = _loader.IsAvailable
            ? $"将安装 {SelectedVersion ?? "—"}（原版）"
            : $"{_loader.Title} 暂不可用";
        IsPrimaryButtonEnabled = CanInstall;
    }

    private async void OnPrimaryButtonClick(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        if (!CanInstall)
        {
            args.Cancel = true;
            return;
        }

        var deferral = args.GetDeferral();
        args.Cancel = true;
        _installing = true;
        ErrorBar.IsOpen = false;
        UpdateFooter();

        try
        {
            var version = SelectedVersion;
            if (string.IsNullOrWhiteSpace(version))
            {
                ErrorBar.Message = "请选择一个游戏版本。";
                ErrorBar.IsOpen = true;
                return;
            }

            await App.Model.StartGameInstallAsync(version, InstanceNameBox.Text);
            args.Cancel = false;
        }
        catch (Exception error)
        {
            ErrorBar.Message = Describe(error);
            ErrorBar.IsOpen = true;
        }
        finally
        {
            _installing = false;
            UpdateFooter();
            deferral.Complete();
        }
    }

    private static string Describe(Exception error) => error is CoreException core
        ? string.IsNullOrWhiteSpace(core.Error.Detail)
            ? core.Error.UserMessage
            : $"{core.Error.UserMessage}\n{core.Error.Detail}"
        : error.Message;
}
