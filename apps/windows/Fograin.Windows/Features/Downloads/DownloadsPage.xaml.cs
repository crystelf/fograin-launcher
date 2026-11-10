using System.ComponentModel;
using Fograin.Windows.Shell;
using Fograin.Windows.Models;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Features.Downloads;

/// <summary>
/// 下载页: HMCL/PCL flow — pick a version on the left, preview the plan in the
/// middle, watch the queue on the right, then choose loader/name in the install
/// dialog. Mirrors macOS DownloadsScreen.
/// </summary>
public sealed partial class DownloadsPage : Page
{
    private bool _syncingCategory;
    private bool _syncingChannel;
    private bool _syncingLoader;
    private bool _syncingSearch;
    private bool _syncingVersionSelection;

    public DownloadsPage()
    {
        InitializeComponent();
        Loaded += DownloadsPage_Loaded;
        Unloaded += DownloadsPage_Unloaded;
    }

    public LauncherModel Model => App.Model;

    private async void DownloadsPage_Loaded(object sender, RoutedEventArgs e)
    {
        App.Model.PropertyChanged -= Model_PropertyChanged;
        App.Model.PropertyChanged += Model_PropertyChanged;

        SyncFromModel();
        await Model.LoadDownloadVersionsAsync();
        SyncFromModel();
    }

    private void DownloadsPage_Unloaded(object sender, RoutedEventArgs e)
    {
        App.Model.PropertyChanged -= Model_PropertyChanged;
    }

    private void Model_PropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        switch (args.PropertyName)
        {
            case nameof(LauncherModel.SelectedVersionEntry):
                SyncVersionSelection();
                break;
            case nameof(LauncherModel.DownloadsCategory):
                SyncCategory();
                break;
        }
    }

    private void SyncFromModel()
    {
        SyncCategory();
        SyncChannel();
        SyncLoader();
        SyncSearch();
        SyncVersionSelection();
    }

    private void SyncCategory()
    {
        VersionColumn.Width = Model.DownloadsCategory == DownloadsCategory.Game
            ? new GridLength(300)
            : new GridLength(0);

        _syncingCategory = true;
        try
        {
            CategoryBar.SelectedItem = Model.DownloadsCategory switch
            {
                DownloadsCategory.Mod => ModCategoryItem,
                DownloadsCategory.Modpack => ModpackCategoryItem,
                DownloadsCategory.Resourcepack => ResourcepackCategoryItem,
                DownloadsCategory.Shaderpack => ShaderpackCategoryItem,
                DownloadsCategory.Datapack => DatapackCategoryItem,
                _ => GameCategoryItem,
            };
        }
        finally
        {
            _syncingCategory = false;
        }
    }

    private void SyncChannel()
    {
        _syncingChannel = true;
        try
        {
            ChannelComboBox.SelectedIndex = Model.DownloadVersionChannel switch
            {
                DownloadVersionChannel.Snapshot => 1,
                DownloadVersionChannel.AprilFools => 2,
                DownloadVersionChannel.Old => 3,
                _ => 0,
            };
        }
        finally
        {
            _syncingChannel = false;
        }
    }

    private void SyncLoader()
    {
        _syncingLoader = true;
        try
        {
            LoaderComboBox.SelectedIndex = Model.DownloadContentLoader switch
            {
                ContentLoaderFilter.Forge => 1,
                ContentLoaderFilter.NeoForge => 2,
                ContentLoaderFilter.Fabric => 3,
                ContentLoaderFilter.Quilt => 4,
                ContentLoaderFilter.OptiFine => 5,
                _ => 0,
            };
        }
        finally
        {
            _syncingLoader = false;
        }
    }

    private void SyncSearch()
    {
        if (VersionSearchBox.Text == Model.DownloadsQuery)
        {
            return;
        }

        _syncingSearch = true;
        VersionSearchBox.Text = Model.DownloadsQuery;
        _syncingSearch = false;
    }

    private void SyncVersionSelection()
    {
        _syncingVersionSelection = true;
        try
        {
            VersionList.SelectedItem = Model.SelectedVersionEntry;
        }
        finally
        {
            _syncingVersionSelection = false;
        }
    }

    // ---- Header pickers ----------------------------------------------

    private void CategoryBar_SelectionChanged(
        SelectorBar sender,
        SelectorBarSelectionChangedEventArgs args)
    {
        if (_syncingCategory)
        {
            return;
        }

        var category = sender.SelectedItem == ModCategoryItem ? DownloadsCategory.Mod
            : sender.SelectedItem == ModpackCategoryItem ? DownloadsCategory.Modpack
            : sender.SelectedItem == ResourcepackCategoryItem ? DownloadsCategory.Resourcepack
            : sender.SelectedItem == ShaderpackCategoryItem ? DownloadsCategory.Shaderpack
            : sender.SelectedItem == DatapackCategoryItem ? DownloadsCategory.Datapack
            : DownloadsCategory.Game;

        Model.SetDownloadsCategory(category);
    }

    private void ChannelComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingChannel)
        {
            return;
        }

        Model.SetDownloadChannel(ChannelComboBox.SelectedIndex switch
        {
            1 => DownloadVersionChannel.Snapshot,
            2 => DownloadVersionChannel.AprilFools,
            3 => DownloadVersionChannel.Old,
            _ => DownloadVersionChannel.Release,
        });
    }

    private void LoaderComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingLoader)
        {
            return;
        }

        Model.SetContentLoader(LoaderComboBox.SelectedIndex switch
        {
            1 => ContentLoaderFilter.Forge,
            2 => ContentLoaderFilter.NeoForge,
            3 => ContentLoaderFilter.Fabric,
            4 => ContentLoaderFilter.Quilt,
            5 => ContentLoaderFilter.OptiFine,
            _ => ContentLoaderFilter.All,
        });
    }

    // ---- Version list -------------------------------------------------

    private void VersionSearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_syncingSearch)
        {
            return;
        }

        Model.DownloadsQuery = VersionSearchBox.Text;
    }

    private async void RefreshVersions_Click(object sender, RoutedEventArgs e) =>
        await Model.ReloadDownloadVersionsAsync();

    private void VersionList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingVersionSelection || VersionList.SelectedItem is not VersionEntry entry)
        {
            return;
        }

        Model.SelectDownloadVersion(entry.Id);
    }

    private void Install_Click(object sender, RoutedEventArgs e) =>
        Model.OpenInstallDialogForSelection();

    private async void CancelTask_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string taskId)
        {
            await Model.CancelTaskAsync(taskId);
        }
    }
}
