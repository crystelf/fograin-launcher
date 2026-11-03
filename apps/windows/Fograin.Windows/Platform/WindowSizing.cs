using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Windows.Graphics;
using WinRT.Interop;

namespace Fograin.Windows.Platform;

/// <summary>Window sizing and centering through the native AppWindow API.</summary>
public static class WindowSizing
{
    // The reference window in fograin.pen is 1400x880 DIP; keep the M1 shell at
    // the same content size.
    private const int DesignWidth = 1400;
    private const int DesignHeight = 880;

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr hwnd);

    public static void ApplyDesignSize(Window window)
    {
        try
        {
            var appWindow = window.AppWindow;
            var handle = WindowNative.GetWindowHandle(window);
            var scale = GetDpiForWindow(handle) / 96.0;
            if (scale <= 0)
            {
                scale = 1;
            }

            var workArea = DisplayArea
                .GetFromWindowId(appWindow.Id, DisplayAreaFallback.Nearest)
                .WorkArea;

            // Keep the reference size when it fits, but never open a window
            // larger than the display (for example at 200% scaling).
            var width = Math.Min((int)Math.Round(DesignWidth * scale), (int)(workArea.Width * 0.92));
            var height = Math.Min((int)Math.Round(DesignHeight * scale), (int)(workArea.Height * 0.92));
            appWindow.ResizeClient(new SizeInt32(width, height));

            var size = appWindow.Size;
            appWindow.Move(new PointInt32(
                workArea.X + ((workArea.Width - size.Width) / 2),
                workArea.Y + ((workArea.Height - size.Height) / 2)));
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"window sizing failed: {exception.Message}");
        }
    }

    /// <summary>Tall caption buttons match the 48 px custom title bar.</summary>
    public static void UseTallTitleBar(Window window)
    {
        try
        {
            if (AppWindowTitleBar.IsCustomizationSupported())
            {
                window.AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
            }
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"PreferredHeightOption failed: {exception.Message}");
        }
    }
}
