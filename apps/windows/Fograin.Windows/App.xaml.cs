using Fograin.Windows.Shell;
using Fograin.Windows.Core;
using Microsoft.UI.Dispatching;
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

    /// <summary>
    /// Process-wide launcher state. Created on the UI thread in
    /// <see cref="OnLaunched"/> before the shell is constructed.
    /// </summary>
    public static LauncherModel Model { get; private set; } = null!;

    public static MainWindow? Shell { get; private set; }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var dispatcher = DispatcherQueue.GetForCurrentThread()
            ?? throw new InvalidOperationException("The WinUI dispatcher queue is unavailable.");

        Model = new LauncherModel(Settings, dispatcher);
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
