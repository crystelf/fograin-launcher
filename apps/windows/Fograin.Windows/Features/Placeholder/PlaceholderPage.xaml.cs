using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Fograin.Windows.Features.Placeholder;

/// <summary>Navigation payload for a page owned by a later milestone.</summary>
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
            ? $"M4 · 启动器核心 ABI v{settings.AbiVersion} · 主题：{settings.ThemeLabel} · 数据目录：{settings.DataRoot}"
            : "M4 · 未连接启动器核心（fg_ffi.dll 未找到），界面以空数据运行";
    }
}
