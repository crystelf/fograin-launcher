using System.ComponentModel;
using Fograin.Windows.Shell;
using Fograin.Windows.Models;
using Fograin.Windows.Platform;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace Fograin.Windows.Features.Library;

/// <summary>
/// 实例库: grouped live instance table plus the preview inspector, mirroring
/// macOS LibraryView (grouping by loader, sort menu, double-click → 游戏设置).
/// </summary>
public sealed partial class LibraryPage : Page
{
    private CollectionViewSource? _groupedSource;
    private bool _syncingSelection;
    private bool _syncingSort;

    public LibraryPage()
    {
        InitializeComponent();
        Loaded += LibraryPage_Loaded;
        Unloaded += LibraryPage_Unloaded;
    }

    public LauncherModel Model => App.Model;

    private void LibraryPage_Loaded(object sender, RoutedEventArgs e)
    {
        App.Model.PropertyChanged -= Model_PropertyChanged;
        App.Model.PropertyChanged += Model_PropertyChanged;
        SyncSort();
        RebuildGroups();
    }

    private void LibraryPage_Unloaded(object sender, RoutedEventArgs e)
    {
        App.Model.PropertyChanged -= Model_PropertyChanged;
    }

    private void Model_PropertyChanged(object? sender, PropertyChangedEventArgs args)
    {
        switch (args.PropertyName)
        {
            case nameof(LauncherModel.LibraryGroups):
                RebuildGroups();
                break;
            case nameof(LauncherModel.SelectedLibraryInstance):
                SyncSelection();
                break;
        }
    }

    private void RebuildGroups()
    {
        _groupedSource = new CollectionViewSource
        {
            IsSourceGrouped = true,
            Source = Model.LibraryGroups,
        };

        LibraryList.ItemsSource = _groupedSource.View;
        SyncSelection();
    }

    private void SyncSelection()
    {
        _syncingSelection = true;
        try
        {
            LibraryList.SelectedItem = Model.SelectedLibraryInstance;
        }
        finally
        {
            _syncingSelection = false;
        }
    }

    private void SyncSort()
    {
        _syncingSort = true;
        try
        {
            SortComboBox.SelectedIndex = Model.LibrarySort switch
            {
                LibrarySortOrder.Name => 1,
                LibrarySortOrder.Size => 2,
                _ => 0,
            };
        }
        finally
        {
            _syncingSort = false;
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

    private void ClearSearch_Click(object sender, RoutedEventArgs e) => Model.LibraryQuery = string.Empty;

    private void SortComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSort)
        {
            return;
        }

        Model.LibrarySort = SortComboBox.SelectedIndex switch
        {
            1 => LibrarySortOrder.Name,
            2 => LibrarySortOrder.Size,
            _ => LibrarySortOrder.Recent,
        };
    }

    // ---- Table --------------------------------------------------------

    private void LibraryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_syncingSelection || LibraryList.SelectedItem is not InstanceRow row)
        {
            return;
        }

        Model.LibrarySelectionId = row.Id;
    }

    private void LibraryList_DoubleTapped(object sender, DoubleTappedRoutedEventArgs e)
    {
        if (InstanceFrom(e.OriginalSource) is { } instance)
        {
            Model.ManageInstance(instance);
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

    private void RowManage_Click(object sender, RoutedEventArgs e)
    {
        if (ResolveRow(sender) is { } instance)
        {
            Model.ManageInstance(instance);
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

    // ---- Preview actions ---------------------------------------------

    private async void PreviewPlay_Click(object sender, RoutedEventArgs e)
    {
        if (Model.SelectedLibraryInstance is { } instance)
        {
            await Model.StartGameAsync(instance);
        }
    }

    private void PreviewManage_Click(object sender, RoutedEventArgs e)
    {
        if (Model.SelectedLibraryInstance is { } instance)
        {
            Model.ManageInstance(instance);
        }
    }

    private async void PreviewFolder_Click(object sender, RoutedEventArgs e)
    {
        if (Model.SelectedLibraryInstance is { } instance)
        {
            await Model.OpenInstanceFolderAsync(instance);
        }
    }

    private async void PreviewFavorite_Click(object sender, RoutedEventArgs e)
    {
        if (Model.SelectedLibraryInstance is { } instance)
        {
            await Model.ToggleFavoriteAsync(instance);
        }
    }

    private InstanceRow? ResolveRow(object sender) =>
        Model.FindInstance((sender as FrameworkElement)?.Tag as string);

    private static InstanceRow? InstanceFrom(object? originalSource)
    {
        DependencyObject? element = originalSource as DependencyObject;
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
