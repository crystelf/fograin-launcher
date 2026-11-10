using Fograin.Windows.Shell;
using Fograin.Windows.Models;
using Fograin.Windows.Platform;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Fograin.Windows.Features.Home;

/// <summary>
/// 主页: live current instance hero, 最近/收藏/全部 instance list and the
/// queue/runtime/news rail (same information architecture as macOS HomeView).
/// </summary>
public sealed partial class HomePage : Page
{
    private bool _syncingSelection;
    private bool _syncingFilter;

    public HomePage()
    {
        InitializeComponent();
        Loaded += HomePage_Loaded;
        Unloaded += HomePage_Unloaded;
    }

    public LauncherModel Model => App.Model;

    private void HomePage_Loaded(object sender, RoutedEventArgs e)
    {
        App.Model.DataRefreshed -= Model_DataRefreshed;
        App.Model.DataRefreshed += Model_DataRefreshed;
        SyncFromModel();
    }

    private void HomePage_Unloaded(object sender, RoutedEventArgs e)
    {
        App.Model.DataRefreshed -= Model_DataRefreshed;
    }

    private void Model_DataRefreshed(object? sender, EventArgs args) => SyncFromModel();

    private void SyncFromModel()
    {
        _syncingFilter = true;
        HomeFilterBar.SelectedItem = Model.HomeFilter switch
        {
            HomeInstanceFilter.Favorites => FavoritesFilterItem,
            HomeInstanceFilter.All => AllFilterItem,
            _ => RecentFilterItem,
        };
        _syncingFilter = false;

        SyncLayoutButtons();
        SyncSelection();
    }

    private void SyncLayoutButtons()
    {
        ListLayoutButton.IsChecked = Model.IsListLayout;
        GridLayoutButton.IsChecked = Model.IsGridLayout;
    }

    private void SyncSelection()
    {
        var current = Model.CurrentInstance;
        var visible = current is not null && Model.HomeInstances.Contains(current);

        _syncingSelection = true;
        try
        {
            if (Model.IsListLayout)
            {
                RecentGrid.SelectedItem = null;
                RecentList.SelectedItem = visible ? current : null;
            }
            else
            {
                RecentList.SelectedItem = null;
                RecentGrid.SelectedItem = visible ? current : null;
            }
        }
        finally
        {
            _syncingSelection = false;
        }
    }

    // ---- Header actions ----------------------------------------------

    private void AddInstance_Click(object sender, RoutedEventArgs e) => Model.NewInstance();

    private async void ImportDirectory_Click(object sender, RoutedEventArgs e)
    {
        var path = await PickerActions.PickMinecraftDirectoryAsync(this);
        if (!string.IsNullOrWhiteSpace(path))
        {
            await Model.ImportMinecraftDirectoryAsync(path);
        }
    }

    private async void Refresh_Click(object sender, RoutedEventArgs e) =>
        await Model.RefreshCoreDataAsync();

    private async void StartCurrent_Click(object sender, RoutedEventArgs e) =>
        await Model.StartCurrentGameAsync();

    private async void StopCurrent_Click(object sender, RoutedEventArgs e) =>
        await Model.StopGameAsync();

    private void SettingsCurrent_Click(object sender, RoutedEventArgs e) =>
        Model.ManageCurrentInstance();

    private async void FolderCurrent_Click(object sender, RoutedEventArgs e)
    {
        if (Model.CurrentInstance is { } instance)
        {
            await Model.OpenInstanceFolderAsync(instance);
        }
    }

    private async void NewsInstall_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string version && version.Length > 0)
        {
            await Model.RequestInstallForVersionAsync(version);
        }
    }

    // ---- Instance list ------------------------------------------------

    private void HomeFilter_SelectionChanged(
        SelectorBar sender,
        SelectorBarSelectionChangedEventArgs args)
    {
        if (_syncingFilter)
        {
            return;
        }

        Model.HomeFilter = sender.SelectedItem == FavoritesFilterItem
            ? HomeInstanceFilter.Favorites
            : sender.SelectedItem == AllFilterItem
                ? HomeInstanceFilter.All
                : HomeInstanceFilter.Recent;
        SyncSelection();
    }

    private void ListLayout_Click(object sender, RoutedEventArgs e)
    {
        Model.HomeLayout = HomeLayoutKind.List;
        SyncLayoutButtons();
        SyncSelection();
    }

    private void GridLayout_Click(object sender, RoutedEventArgs e)
    {
        Model.HomeLayout = HomeLayoutKind.Grid;
        SyncLayoutButtons();
        SyncSelection();
    }

    private void HomeList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection || RecentList.SelectedItem is not InstanceRow row)
        {
            return;
        }

        _syncingSelection = true;
        RecentGrid.SelectedItem = null;
        _syncingSelection = false;
        Model.SelectHomeInstance(row);
    }

    private void HomeGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection || RecentGrid.SelectedItem is not InstanceRow row)
        {
            return;
        }

        _syncingSelection = true;
        RecentList.SelectedItem = null;
        _syncingSelection = false;
        Model.SelectHomeInstance(row);
    }

    private void HomeList_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (InstanceFrom(e.OriginalSource) is { } instance)
        {
            Model.OpenInstance(instance);
        }
    }

    // ---- Row actions --------------------------------------------------

    private void RowPlay_Click(object sender, RoutedEventArgs e)
    {
        if (ResolveRow(sender) is { } instance)
        {
            _ = Model.StartGameAsync(instance);
        }
    }

    private void RowOpenLibrary_Click(object sender, RoutedEventArgs e)
    {
        if (ResolveRow(sender) is { } instance)
        {
            Model.OpenInstance(instance);
        }
    }

    private async void RowOpenFolder_Click(object sender, RoutedEventArgs e)
    {
        if (ResolveRow(sender) is { } instance)
        {
            await Model.OpenInstanceFolderAsync(instance);
        }
    }

    private async void RowToggleFavorite_Click(object sender, RoutedEventArgs e)
    {
        if (ResolveRow(sender) is { } instance)
        {
            await Model.ToggleFavoriteAsync(instance);
        }
    }

    private InstanceRow? ResolveRow(object sender) =>
        Model.FindInstance((sender as FrameworkElement)?.Tag as string);

    private static InstanceRow? InstanceFrom(object? originalSource)
    {
        if (originalSource is not DependencyObject element)
        {
            return null;
        }

        while (element is not null)
        {
            if (element is FrameworkElement { DataContext: InstanceRow row })
            {
                return row;
            }

            element = VisualTreeHelper.GetParent(element);
        }

        return null;
    }
}
