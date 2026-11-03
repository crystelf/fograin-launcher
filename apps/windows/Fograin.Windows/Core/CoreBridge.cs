using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Fograin.Windows.Core;

/// <summary>
/// The only place the Windows client talks to the Rust core. The C ABI lives in
/// <c>bindings/fg-ffi</c>; this milestone reads the settings snapshot before the
/// first frame so the shell can apply the configured theme.
///
/// If <c>fg_ffi.dll</c> is not next to the executable the bridge degrades to a
/// usable fallback: the prototype UI still opens and reports the missing core.
/// </summary>
public static class CoreBridge
{
    private const string Library = "fg_ffi";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern uint fg_abi_version();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr fg_settings_snapshot();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl)]
    private static extern void fg_string_free(IntPtr value);

    /// <summary>Reads the ABI version. Returns 0 when the core library is absent.</summary>
    public static uint AbiVersion
    {
        get
        {
            try
            {
                return fg_abi_version();
            }
            catch (Exception exception)
            {
                Debug.WriteLine($"fg_abi_version unavailable: {exception.Message}");
                return 0;
            }
        }
    }

    /// <summary>
    /// Reads <c>launcher.toml</c> through the core. Never throws: a missing DLL,
    /// a bad config file or malformed JSON all fall back to defaults.
    /// </summary>
    public static CoreSettingsSnapshot LoadSettings()
    {
        var abiVersion = AbiVersion;
        IntPtr pointer = IntPtr.Zero;

        try
        {
            pointer = fg_settings_snapshot();
            if (pointer == IntPtr.Zero)
            {
                return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
            }

            var json = Marshal.PtrToStringUTF8(pointer);
            if (string.IsNullOrWhiteSpace(json))
            {
                return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
            }

            var payload = JsonSerializer.Deserialize<SnapshotPayload>(json, JsonOptions);
            if (payload is null)
            {
                return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
            }

            return new CoreSettingsSnapshot
            {
                AbiVersion = payload.AbiVersion == 0 ? abiVersion : payload.AbiVersion,
                AppVersion = payload.AppVersion ?? "0.1.0",
                Platform = payload.Platform ?? "windows",
                Theme = ParseTheme(payload.Theme),
                DataRoot = payload.DataRoot ?? CoreSettingsSnapshot.Fallback.DataRoot,
                ConfigPath = payload.ConfigPath ?? CoreSettingsSnapshot.Fallback.ConfigPath,
                ConfigError = payload.ConfigError is { } error
                    ? new CoreError(error.Kind ?? "unknown", error.MessageKey ?? "error.unknown", error.Detail)
                    : null,
                FromCore = true,
            };
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"fg_settings_snapshot failed: {exception.Message}");
            return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
        }
        finally
        {
            if (pointer != IntPtr.Zero)
            {
                fg_string_free(pointer);
            }
        }
    }

    private static ThemePreference ParseTheme(string? value) => value switch
    {
        "light" => ThemePreference.Light,
        "dark" => ThemePreference.Dark,
        _ => ThemePreference.System,
    };

    // Mirrors SettingsSnapshot in bindings/fg-ffi/src/lib.rs.
    private sealed class SnapshotPayload
    {
        public uint AbiVersion { get; init; }

        public string? AppVersion { get; init; }

        public string? Platform { get; init; }

        public string? Theme { get; init; }

        public string? DataRoot { get; init; }

        public string? ConfigPath { get; init; }

        public ErrorPayload? ConfigError { get; init; }
    }

    private sealed class ErrorPayload
    {
        public string? Kind { get; init; }

        public string? MessageKey { get; init; }

        public string? Detail { get; init; }
    }
}
