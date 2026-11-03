using Fograin.Windows.Core;
using Microsoft.UI.Xaml;

namespace Fograin.Windows;

public partial class App : Application
{
    public App()
    {
        InitializeComponent();

        // The theme can only be set while the app is starting, so the core is
        // read here, before OnLaunched creates the first window.
        Settings = CoreBridge.LoadSettings();
        ApplyStartupTheme(Settings.Theme);
    }

    public static new App Current => (App)Application.Current;

    /// <summary>Snapshot read from the Rust core before the first window exists.</summary>
    public CoreSettingsSnapshot Settings { get; private set; } = CoreSettingsSnapshot.Fallback;

    public static MainWindow? Shell { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        Shell = new MainWindow();
        Shell.Activate();
    }

    /// <summary>
    /// §4.1.1: the core owns `appearance.theme`; the shell resolves the native
    /// appearance before the first frame so the window does not flash.
    /// </summary>
    private void ApplyStartupTheme(ThemePreference theme)
    {
        if (theme == ThemePreference.System)
        {
            return;
        }

        try
        {
            RequestedTheme = theme == ThemePreference.Light
                ? ApplicationTheme.Light
                : ApplicationTheme.Dark;
        }
        catch (Exception exception)
        {
            System.Diagnostics.Debug.WriteLine($"RequestedTheme failed: {exception.Message}");
        }
    }
}
