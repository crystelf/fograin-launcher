using System.Runtime.InteropServices;

namespace Fograin.Windows.Models;

/// <summary>
/// Physical memory reported by the native Windows API for the home runtime rail
/// (§13). Missing values degrade to `未知` instead of failing the page.
/// </summary>
public static class SystemInfo
{
    private static readonly Lazy<ulong> TotalMemory = new(QueryTotalMemory);

    public static ulong TotalMemoryBytes => TotalMemory.Value;

    /// <summary>`32.0 GB`, or `未知` when the query is unavailable.</summary>
    public static string MemoryText =>
        TotalMemoryBytes == 0 ? "未知" : InstanceFormat.ByteSize(TotalMemoryBytes);

    /// <summary>The launcher does not choose a renderer; the game uses its default.</summary>
    public static string RendererText => "DirectX · 系统默认";

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryStatusEx
    {
        public uint Length;
        public uint MemoryLoad;
        public ulong TotalPhysical;
        public ulong AvailablePhysical;
        public ulong TotalPageFile;
        public ulong AvailablePageFile;
        public ulong TotalVirtual;
        public ulong AvailableVirtual;
        public ulong AvailableExtendedVirtual;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalMemoryStatusEx(ref MemoryStatusEx buffer);

    private static ulong QueryTotalMemory()
    {
        try
        {
            var status = new MemoryStatusEx
            {
                Length = (uint)Marshal.SizeOf<MemoryStatusEx>(),
            };

            return GlobalMemoryStatusEx(ref status) ? status.TotalPhysical : 0;
        }
        catch (Exception exception)
        {
            System.Diagnostics.Debug.WriteLine($"GlobalMemoryStatusEx failed: {exception.Message}");
            return 0;
        }
    }
}
