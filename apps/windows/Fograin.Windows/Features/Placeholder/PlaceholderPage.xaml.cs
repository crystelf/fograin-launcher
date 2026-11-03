using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Fograin.Windows.Features.Placeholder;

/// <summary>Navigation payload for a page that is not part of M1.</summary>
public sealed record PlaceholderArgs(string Title, string Caption, Symbol Icon);

public sealed partial class PlaceholderPage : Page
{
    public PlaceholderPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);

        if (e.Parameter is PlaceholderArgs args)
        {
            PageTitleText.Text = args.Title;
            PageCaptionText.Text = args.Caption;
            PageIcon.Symbol = args.Icon;
        }

        var settings = App.Current.Settings;
        CoreStatusText.Text = settings.FromCore
            ? $"M1 原型 · 启动器核心 ABI v{settings.AbiVersion} · 主题：{settings.ThemeLabel}"
            : "M1 原型 · 未连接启动器核心（fg_ffi.dll 未找到）";
    }
}
