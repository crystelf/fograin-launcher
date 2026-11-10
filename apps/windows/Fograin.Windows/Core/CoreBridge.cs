using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace Fograin.Windows.Core;

/// <summary>
/// The only place the Windows client talks to the Rust core. The C ABI lives in
/// <c>bindings/fg-ffi</c>; every UI read goes through the JSON RPC surface and
/// progress/game events are drained from the same process-wide event queue.
///
/// <para>
/// If <c>fg_ffi.dll</c> is not next to the executable the bridge degrades to a
/// usable fallback: the shell still opens and reports the missing core instead
/// of failing to start.
/// </para>
/// </summary>
public static class CoreBridge
{
    private const string Library = "fg_ffi";

    /// <summary>C ABI version this client is written against (fg-ffi `ABI_VERSION`).</summary>
    public const uint ExpectedAbiVersion = 1;

    private static readonly Lazy<uint> AbiVersionCache = new(() =>
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
    });

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern uint fg_abi_version();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_settings_snapshot();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_settings_set_theme(IntPtr theme);

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_rpc_call(IntPtr request);

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern IntPtr fg_events_next();

    [DllImport(Library, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern void fg_string_free(IntPtr value);

    /// <summary>Version of the C ABI the core was built with; 0 when it is absent.</summary>
    public static uint AbiVersion => AbiVersionCache.Value;

    /// <summary>True when fg_ffi.dll answered <c>fg_abi_version()</c>.</summary>
    public static bool IsAvailable => AbiVersion != 0;

    // ---- Settings -----------------------------------------------------

    /// <summary>
    /// Reads <c>launcher.toml</c> through the core. Never throws: a missing DLL,
    /// a bad config file or malformed JSON all fall back to defaults.
    /// </summary>
    public static CoreSettingsSnapshot LoadSettings()
    {
        var abiVersion = AbiVersion;
        if (!IsAvailable)
        {
            return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
        }

        IntPtr pointer = IntPtr.Zero;
        try
        {
            pointer = fg_settings_snapshot();
            if (pointer == IntPtr.Zero)
            {
                return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
            }

            var payload = Marshal.PtrToStringUTF8(pointer);
            if (string.IsNullOrWhiteSpace(payload)
                || JsonNode.Parse(payload) is not JsonObject snapshot)
            {
                return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
            }

            return ParseSnapshot(snapshot, abiVersion);
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"fg_settings_snapshot failed: {exception.Message}");
            return CoreSettingsSnapshot.Fallback with { AbiVersion = abiVersion };
        }
        finally
        {
            Release(pointer);
        }
    }

    /// <summary>
    /// Persists <c>appearance.theme</c> through the core and returns the
    /// refreshed snapshot. Throws <see cref="CoreException"/> when the write is
    /// rejected (for example by an unparsable launcher.toml).
    /// </summary>
    public static CoreSettingsSnapshot SetTheme(ThemePreference theme)
    {
        if (!IsAvailable)
        {
            throw new CoreException(CoreErrorPayload.Unavailable);
        }

        var wire = theme switch
        {
            ThemePreference.Light => "light",
            ThemePreference.Dark => "dark",
            _ => "system",
        };

        IntPtr argument = Marshal.StringToCoTaskMemUTF8(wire);
        IntPtr pointer = IntPtr.Zero;
        try
        {
            pointer = fg_settings_set_theme(argument);
            if (pointer == IntPtr.Zero)
            {
                throw new CoreException("error.core.unavailable", "fg_settings_set_theme returned NULL");
            }

            var response = ParseObject(Marshal.PtrToStringUTF8(pointer), "theme write");
            if (!response.Bool("ok"))
            {
                throw new CoreException(ParseError(response));
            }

            return response["snapshot"] is JsonObject snapshot
                ? ParseSnapshot(snapshot, AbiVersion)
                : CoreSettingsSnapshot.Fallback with { AbiVersion = AbiVersion };
        }
        finally
        {
            Marshal.FreeCoTaskMem(argument);
            Release(pointer);
        }
    }

    // ---- RPC ----------------------------------------------------------

    /// <summary>
    /// Dispatches one JSON command and returns the success envelope with the
    /// payload merged at the top level (camelCase keys). Blocks on the core
    /// runtime, so UI code should use <see cref="RpcAsync"/>.
    /// </summary>
    public static JsonObject Rpc(string method, JsonObject? parameters = null)
    {
        if (!IsAvailable)
        {
            throw new CoreException(CoreErrorPayload.Unavailable);
        }

        var request = new JsonObject
        {
            ["method"] = method,
            ["params"] = parameters ?? new JsonObject(),
        };

        IntPtr argument = Marshal.StringToCoTaskMemUTF8(request.ToJsonString());
        IntPtr pointer = IntPtr.Zero;
        try
        {
            pointer = fg_rpc_call(argument);
            if (pointer == IntPtr.Zero)
            {
                throw new CoreException("error.core.unavailable", $"fg_rpc_call({method}) returned NULL");
            }

            var response = ParseObject(Marshal.PtrToStringUTF8(pointer), method);
            if (response.Bool("ok"))
            {
                return response;
            }

            throw new CoreException(ParseError(response));
        }
        catch (CoreException)
        {
            throw;
        }
        catch (Exception exception)
        {
            throw new CoreException("error.core.call_failed", $"{method}: {exception.Message}");
        }
        finally
        {
            Marshal.FreeCoTaskMem(argument);
            Release(pointer);
        }
    }

    /// <summary>
    /// Async variant used by page loads and install/launch actions. The blocking
    /// C call runs on the thread pool; the response is parsed there and handed
    /// back as an immutable JSON object.
    /// </summary>
    public static Task<JsonObject> RpcAsync(string method, JsonObject? parameters = null)
    {
        // Detach from the caller's instance before crossing threads: a JsonNode
        // cannot have two parents and the caller may reuse its parameter map.
        var detached = parameters is null ? null : (JsonObject)parameters.DeepClone();
        return Task.Run(() => Rpc(method, detached));
    }

    /// <summary>
    /// Drains pending progress/game events. The queue is in-process and never
    /// blocks, so the UI may poll it from its event timer.
    /// </summary>
    public static IReadOnlyList<JsonObject> NextEvents()
    {
        if (!IsAvailable)
        {
            return Array.Empty<JsonObject>();
        }

        IntPtr pointer = IntPtr.Zero;
        try
        {
            pointer = fg_events_next();
            if (pointer == IntPtr.Zero)
            {
                return Array.Empty<JsonObject>();
            }

            var payload = Marshal.PtrToStringUTF8(pointer);
            if (string.IsNullOrWhiteSpace(payload))
            {
                return Array.Empty<JsonObject>();
            }

            return JsonNode.Parse(payload) is JsonArray array
                ? array.OfType<JsonObject>().ToList()
                : Array.Empty<JsonObject>();
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"fg_events_next failed: {exception.Message}");
            return Array.Empty<JsonObject>();
        }
        finally
        {
            Release(pointer);
        }
    }

    // ---- Parsing ------------------------------------------------------

    private static CoreSettingsSnapshot ParseSnapshot(JsonObject root, uint abiVersion)
    {
        var configError = root["configError"] is JsonObject error
            ? ParseErrorObject(error)
            : null;

        return new CoreSettingsSnapshot
        {
            AbiVersion = (uint)root.Long("abiVersion", abiVersion),
            AppVersion = root.StrOr("appVersion", "0.1.0"),
            Platform = root.StrOr("platform", "windows"),
            Theme = ParseTheme(root.Str("theme")),
            DataRoot = root.StrOr("dataRoot", CoreSettingsSnapshot.Fallback.DataRoot),
            ConfigPath = root.StrOr("configPath", CoreSettingsSnapshot.Fallback.ConfigPath),
            ConfigError = configError,
            FromCore = true,
        };
    }

    private static ThemePreference ParseTheme(string? value) => value switch
    {
        "light" => ThemePreference.Light,
        "dark" => ThemePreference.Dark,
        _ => ThemePreference.System,
    };

    private static JsonObject ParseObject(string? payload, string context)
    {
        if (string.IsNullOrWhiteSpace(payload))
        {
            throw new CoreException("error.core.malformed_response", $"{context}: empty response");
        }

        try
        {
            return JsonNode.Parse(payload) as JsonObject
                ?? throw new CoreException(
                    "error.core.malformed_response",
                    $"{context}: response is not a JSON object");
        }
        catch (System.Text.Json.JsonException exception)
        {
            throw new CoreException("error.core.malformed_response", $"{context}: {exception.Message}");
        }
    }

    private static CoreErrorPayload ParseError(JsonObject envelope) =>
        envelope["error"] is JsonObject error
            ? ParseErrorObject(error)
            : new CoreErrorPayload("internal", "error.unknown", envelope.ToJsonString());

    private static CoreErrorPayload ParseErrorObject(JsonObject error) => new(
        error.Str("kind") ?? "unknown",
        error.Str("messageKey") ?? "error.unknown",
        error.Str("detail"));

    private static void Release(IntPtr pointer)
    {
        if (pointer == IntPtr.Zero)
        {
            return;
        }

        try
        {
            fg_string_free(pointer);
        }
        catch (Exception exception)
        {
            Debug.WriteLine($"fg_string_free failed: {exception.Message}");
        }
    }
}
