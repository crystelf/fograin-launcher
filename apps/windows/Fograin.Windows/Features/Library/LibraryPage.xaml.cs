using Fograin.Windows.Models;
using Fograin.Windows.Platform;
using Fograin.Windows.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Data;

namespace Fograin.Windows.Features.Library;

public sealed partial class LibraryPage : Page
{
    private readonly CollectionViewSource _groupedSource;

    public LibraryPage()
    {
        InitializeComponent();

        _groupedSource = new CollectionViewSource
        {
            IsSourceGrouped = true,
            Source = ViewModel.Groups,
        };

        LibraryList.ItemsSource = _groupedSource.View;
        LibraryList.SelectedItem = ViewModel.SelectedInstance;
    }

    public LibraryViewModel ViewModel { get; } = new();

    private void LibraryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (LibraryList.SelectedItem is Instance instance)
        {
            ViewModel.SelectedInstance = instance;
        }
    }

    private async void ImportPackage_Click(object sender, RoutedEventArgs e)
    {
        await PickerActions.PickImportPackageAsync(this);
    }

    private async void GameDirectory_Click(object sender, RoutedEventArgs e)
    {
        await PickerActions.PickGameDirectoryAsync(this);
    }
}
