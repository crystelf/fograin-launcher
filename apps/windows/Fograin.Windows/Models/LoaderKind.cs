using Microsoft.UI.Xaml.Controls;

namespace Fograin.Windows.Models;

/// <summary>Mod loader families the launcher knows about (mirrors macOS LoaderKind).</summary>
public enum LoaderKind
{
    Vanilla,
    OptiFine,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

/// <summary>The four groups shown in the instance library, in pen order.</summary>
public enum LibraryGroupKind
{
    Fabric,
    Forge,
    NeoForge,
    Vanilla,
}

public static class LoaderKinds
{
    /// <summary>Display title; unknown wire values fall back to vanilla.</summary>
    public static string Title(this LoaderKind kind) => kind switch
    {
        LoaderKind.OptiFine => "OptiFine",
        LoaderKind.Fabric => "Fabric",
        LoaderKind.Quilt => "Quilt",
        LoaderKind.Forge => "Forge",
        LoaderKind.NeoForge => "NeoForge",
        _ => "原版",
    };

    /// <summary>Segoe Fluent glyph family used by the instance tile.</summary>
    public static Symbol Icon(this LoaderKind kind) => kind switch
    {
        LoaderKind.OptiFine => Symbol.Important,
        LoaderKind.Fabric => Symbol.Library,
        LoaderKind.Quilt => Symbol.ViewAll,
        LoaderKind.Forge => Symbol.Repair,
        LoaderKind.NeoForge => Symbol.Emoji2,
        _ => Symbol.AllApps,
    };

    /// <summary>OptiFine sits with vanilla and Quilt with Fabric, as in the pen.</summary>
    public static LibraryGroupKind Group(this LoaderKind kind) => kind switch
    {
        LoaderKind.Fabric or LoaderKind.Quilt => LibraryGroupKind.Fabric,
        LoaderKind.Forge => LibraryGroupKind.Forge,
        LoaderKind.NeoForge => LibraryGroupKind.NeoForge,
        _ => LibraryGroupKind.Vanilla,
    };

    public static string GroupTitle(this LibraryGroupKind group) => group switch
    {
        LibraryGroupKind.Fabric => "Fabric",
        LibraryGroupKind.Forge => "Forge",
        LibraryGroupKind.NeoForge => "NeoForge",
        _ => "原版",
    };

    /// <summary>
    /// Maps <c>instances.list.loaderKind</c> to the UI enum. Empty, "vanilla"
    /// and every unknown loader (for example an imported pack) read as vanilla.
    /// </summary>
    public static LoaderKind Parse(string? raw)
    {
        var lowered = (raw ?? string.Empty).Trim().ToLowerInvariant();
        return lowered switch
        {
            "fabric" => LoaderKind.Fabric,
            "quilt" => LoaderKind.Quilt,
            "forge" => LoaderKind.Forge,
            "neoforge" => LoaderKind.NeoForge,
            "optifine" => LoaderKind.OptiFine,
            _ => LoaderKind.Vanilla,
        };
    }
}
