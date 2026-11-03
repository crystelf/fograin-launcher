using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using WinRT.Interop;

namespace Fograin.Windows.Platform;

/// <summary>
/// Keeps the non-client window chrome (Mica tint, caption buttons, borders
/// drawn by DWM) in step with the content theme chosen in §4.1.1.
/// </summary>
public static class WindowChrome
{
    private const int DwmwaUseImmersiveDarkMode = 20;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    public static void ApplyTheme(Window window, ElementTheme theme)
    {
        try
        {
            var hwnd = WindowNative.GetWindowHandle(window);
            var value = theme == ElementTheme.Dark ? 1 : 0;
            var result = DwmSetWindowAttribute(hwnd, DwmwaUseImmersiveDarkMode, ref value, sizeof(int));
            if (result != 0)
            {
                Debug.WriteLine($"DwmSetWindowAttribute returned 0x{result:X8}");
            }
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"window chrome theme failed: {exception.Message}");
        }
    }
}
