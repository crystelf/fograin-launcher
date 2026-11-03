using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace Fograin.Windows.Platform;

/// <summary>
/// Native Windows pickers for the prototype shell. The M1 milestone only
/// proves that the platform dialogs open and return a path; importing a pack or
/// registering a game directory is wired in later milestones.
/// </summary>
public static class PickerActions
{
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
                    $"已选择：{file.Path}\n\nM1 原型只验证原生文件选择；整合包解析将在 M9 接入。");
            }
        }
        catch (Exception exception)
        {
            await ShowAsync(page, "无法打开文件选择器", exception.Message);
        }
    }

    public static async Task PickGameDirectoryAsync(Page page)
    {
        var window = App.Shell;
        if (window is null)
        {
            return;
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
            if (folder is not null)
            {
                await ShowAsync(
                    page,
                    "游戏目录",
                    $"已选择：{folder.Path}\n\nM1 原型只验证原生目录选择；实例目录登记将在 M4 接入。");
            }
        }
        catch (Exception exception)
        {
            await ShowAsync(page, "无法打开目录选择器", exception.Message);
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
            Content = new TextBlock { Text = message, TextWrapping = Microsoft.UI.Xaml.TextWrapping.Wrap },
            CloseButtonText = "知道了",
            DefaultButton = ContentDialogButton.Close,
        };

        try
        {
            await dialog.ShowAsync();
        }
        catch (Exception)
        {
            // Another ContentDialog is already open; the prototype ignores it.
        }
    }
}
