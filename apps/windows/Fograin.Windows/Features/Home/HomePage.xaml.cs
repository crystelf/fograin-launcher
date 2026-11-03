using Fograin.Windows.Platform;
using Fograin.Windows.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Features.Home;

public sealed partial class HomePage : Page
{
    public HomePage()
    {
        InitializeComponent();

        // The pen highlights the current instance; use the native ListView
        // selection state instead of drawing a custom accent bar.
        RecentList.Loaded += (_, _) => RecentList.SelectedItem = ViewModel.Current;
    }

    public HomeViewModel ViewModel { get; } = new();

    private async void ImportPackage_Click(object sender, RoutedEventArgs e)
    {
        await PickerActions.PickImportPackageAsync(this);
    }

    private async void GameDirectory_Click(object sender, RoutedEventArgs e)
    {
        await PickerActions.PickGameDirectoryAsync(this);
    }
}
