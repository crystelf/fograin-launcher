using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Fograin.Windows.Platform;

/// <summary>
/// Native Windows pickers. 游戏目录 imports an existing .minecraft; the modpack
/// picker keeps its milestone message until the M9 importer lands.
/// </summary>
public static class PickerActions
{
    /// <summary>Opens the native folder picker and returns the chosen path.</summary>
    public static async Task<string?> PickMinecraftDirectoryAsync(Page page)
    {
        var window = App.Shell;
        if (window is null)
        {
            return null;
        }

        try
        {
            var picker = new FolderPicker
            {
                SuggestedStartLocation = PickerLocationId.ComputerFolder,
            };
            picker.FileTypeFilter.Add("*");

            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(window));

            var folder = await picker.PickSingleFolderAsync();
            return folder?.Path;
        }
        catch (Exception exception)
        {
            await ShowAsync(page, "无法打开目录选择器", exception.Message);
            return null;
        }
    }

    public static async Task PickImportPackageAsync(Page page)
    {
        var window = App.Shell;
        if (window is null)
        {
            return;
        }

        try
        {
            var picker = new FileOpenPicker
            {
                SuggestedStartLocation = PickerLocationId.Downloads,
                ViewMode = PickerViewMode.List,
            };
            picker.FileTypeFilter.Add(".mrpack");
            picker.FileTypeFilter.Add(".zip");

            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(window));

            var file = await picker.PickSingleFileAsync();
            if (file is not null)
            {
                await ShowAsync(
                    page,
                    "导入整合包",
                    $"已选择：{file.Path}\n\n整合包解析与导入将在 M9 接入；当前可先用「游戏目录」登记已有的 .minecraft。");
            }
        }
        catch (Exception exception)
        {
            await ShowAsync(page, "无法打开文件选择器", exception.Message);
        }
    }

    private static async Task ShowAsync(Page page, string title, string message)
    {
        if (page.XamlRoot is null)
        {
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = page.XamlRoot,
            Title = title,
            Content = new TextBlock
            {
                Text = message,
                TextWrapping = Microsoft.UI.Xaml.TextWrapping.Wrap,
            },
            CloseButtonText = "知道了",
            DefaultButton = ContentDialogButton.Close,
        };

        try
        {
            await dialog.ShowAsync();
        }
        catch (Exception)
        {
            // Another ContentDialog is already open; the caller surfaces the
            // same information through the notice capsule in that case.
        }
    }
}
