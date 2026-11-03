namespace Fograin.Windows.Core;

/// <summary>How the shell picks its color theme (`appearance.theme` in launcher.toml).</summary>
public enum ThemePreference
{
    System,
    Light,
    Dark,
}

/// <summary>
/// The part of the Rust core's settings snapshot the Windows shell needs before
/// its first frame (§4.1.1 of docs/design.md).
/// </summary>
public sealed record CoreSettingsSnapshot
{
    public uint AbiVersion { get; init; }

    public string AppVersion { get; init; } = "0.1.0";

    public string Platform { get; init; } = "windows";

    public ThemePreference Theme { get; init; } = ThemePreference.System;

    public string DataRoot { get; init; } = string.Empty;

    public string ConfigPath { get; init; } = string.Empty;

    public CoreError? ConfigError { get; init; }

    /// <summary>False when fg_ffi.dll is missing or returned unusable JSON.</summary>
    public bool FromCore { get; init; }

    public string ThemeLabel => Theme switch
    {
        ThemePreference.Light => "浅色",
        ThemePreference.Dark => "深色",
        _ => "跟随系统",
    };

    /// <summary>Usable defaults so the shell still starts without the core DLL.</summary>
    public static CoreSettingsSnapshot Fallback
    {
        get
        {
            var root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                "Fograin");

            return new CoreSettingsSnapshot
            {
                AbiVersion = 0,
                DataRoot = root,
                ConfigPath = Path.Combine(root, "launcher.toml"),
            };
        }
    }
}

/// <summary>Error payload shared by every fg-ffi call (§3.1 of docs/design.md).</summary>
public sealed record CoreError(string Kind, string MessageKey, string? Detail);
