using System.Diagnostics;
using System.Runtime.InteropServices;

namespace Fograin.Windows.Platform;

/// <summary>
/// Native taskbar progress for the download queue (docs/design.md §14). The
/// Windows equivalent of the macOS Dock badge: active installs paint a progress
/// bar on the taskbar button.
/// </summary>
public static class TaskbarProgress
{
    private const int CompletedRange = 1000;

    private static ITaskbarList3? _taskbar;
    private static IntPtr _window;
    private static bool _attached;

    /// <summary>Binds the taskbar API to one window handle (call once, UI thread).</summary>
    public static void Attach(IntPtr window)
    {
        _window = window;
        if (_attached || window == IntPtr.Zero)
        {
            return;
        }

        try
        {
            _taskbar = (ITaskbarList3)new TaskbarList();
            _attached = _taskbar.HrInit() >= 0;
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"taskbar progress unavailable: {exception.Message}");
            _taskbar = null;
            _attached = false;
        }
    }

    /// <summary>Shows aggregate progress across active tasks, hiding the bar when idle.</summary>
    public static void Update(double fraction, int activeTasks)
    {
        if (!_attached || _taskbar is null || _window == IntPtr.Zero)
        {
            return;
        }

        try
        {
            if (activeTasks <= 0)
            {
                _taskbar.SetProgressState(_window, TaskbarProgressState.NoProgress);
                return;
            }

            _taskbar.SetProgressState(_window, TaskbarProgressState.Normal);
            var completed = (ulong)(Math.Clamp(fraction, 0, 1) * CompletedRange);
            _taskbar.SetProgressValue(_window, completed, CompletedRange);
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"taskbar progress failed: {exception.Message}");
        }
    }

    [Flags]
    private enum TaskbarProgressState
    {
        NoProgress = 0,
        Indeterminate = 1,
        Normal = 2,
        Error = 4,
        Paused = 8,
    }

    [ComImport]
    [Guid("56FDF344-FD6D-11d0-958A-006097C9A090")]
    [ClassInterface(ClassInterfaceType.None)]
    private class TaskbarList
    {
    }

    /// <summary>
    /// ITaskbarList3 as a single vtable: ITaskbarList → ITaskbarList2 →
    /// ITaskbarList3. The order matters even though only the progress methods
    /// are called.
    /// </summary>
    [ComImport]
    [Guid("EA1AFB91-9E28-4B86-90E9-9E9F8A5EECAF")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ITaskbarList3
    {
        [PreserveSig]
        int HrInit();

        [PreserveSig]
        int AddTab(IntPtr window);

        [PreserveSig]
        int DeleteTab(IntPtr window);

        [PreserveSig]
        int ActivateTab(IntPtr window);

        [PreserveSig]
        int SetActiveAlt(IntPtr window);

        [PreserveSig]
        int MarkFullscreenWindow(
            IntPtr window,
            [MarshalAs(UnmanagedType.Bool)] bool fullscreen);

        [PreserveSig]
        int SetProgressValue(IntPtr window, ulong completed, ulong total);

        [PreserveSig]
        int SetProgressState(IntPtr window, TaskbarProgressState state);

        [PreserveSig]
        int RegisterTab(IntPtr window, IntPtr tab);

        [PreserveSig]
        int UnregisterTab(IntPtr tab);

        [PreserveSig]
        int SetTabOrder(IntPtr tab, IntPtr insertBefore);

        [PreserveSig]
        int SetTabActive(IntPtr tab, IntPtr mainWindow, uint reserved);

        [PreserveSig]
        int ThumbBarAddButtons(IntPtr window, uint count, IntPtr buttons);

        [PreserveSig]
        int ThumbBarUpdateButtons(IntPtr window, uint count, IntPtr buttons);

        [PreserveSig]
        int ThumbBarSetImageList(IntPtr window, IntPtr imageList);

        [PreserveSig]
        int SetOverlayIcon(
            IntPtr window,
            IntPtr icon,
            [MarshalAs(UnmanagedType.LPWStr)] string description);

        [PreserveSig]
        int SetThumbnailTooltip(
            IntPtr window,
            [MarshalAs(UnmanagedType.LPWStr)] string tooltip);

        [PreserveSig]
        int SetThumbnailClip(IntPtr window, IntPtr clip);
    }
}
