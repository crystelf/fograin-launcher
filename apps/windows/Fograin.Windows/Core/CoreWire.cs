using System.Text.Json;
using System.Text.Json.Nodes;

namespace Fograin.Windows.Core;

/// <summary>Small, null-tolerant accessors shared by every wire payload (§13).</summary>
internal static class Json
{
    public static string? AsString(JsonNode? node) =>
        node is JsonValue value
        && value.GetValueKind() == JsonValueKind.String
        && value.TryGetValue<string>(out var text)
            ? text
            : null;

    public static string? Str(this JsonObject source, string key) => AsString(source[key]);

    public static string StrOr(this JsonObject source, string key, string fallback) =>
        AsString(source[key]) ?? fallback;

    public static long AsLong(JsonNode? node, long fallback = 0)
    {
        if (node is not JsonValue value || value.GetValueKind() != JsonValueKind.Number)
        {
            return fallback;
        }

        if (value.TryGetValue<long>(out var number))
        {
            return number;
        }

        return value.TryGetValue<double>(out var real) ? (long)real : fallback;
    }

    public static ulong AsULong(JsonNode? node, ulong fallback = 0)
    {
        if (node is not JsonValue value || value.GetValueKind() != JsonValueKind.Number)
        {
            return fallback;
        }

        if (value.TryGetValue<ulong>(out var number))
        {
            return number;
        }

        if (value.TryGetValue<long>(out var signed) && signed >= 0)
        {
            return (ulong)signed;
        }

        return value.TryGetValue<double>(out var real) && real > 0 ? (ulong)real : fallback;
    }

    public static double AsDouble(JsonNode? node, double fallback = 0)
    {
        if (node is not JsonValue value || value.GetValueKind() != JsonValueKind.Number)
        {
            return fallback;
        }

        if (value.TryGetValue<double>(out var number))
        {
            return number;
        }

        return value.TryGetValue<long>(out var whole) ? whole : fallback;
    }

    public static bool AsBool(JsonNode? node, bool fallback = false)
    {
        if (node is not JsonValue value)
        {
            return fallback;
        }

        var kind = value.GetValueKind();
        if (kind is not (JsonValueKind.True or JsonValueKind.False))
        {
            return fallback;
        }

        return value.GetValue<bool>();
    }

    public static bool? AsNullableBool(JsonNode? node)
    {
        if (node is not JsonValue value)
        {
            return null;
        }

        return value.GetValueKind() switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null,
        };
    }

    public static long Long(this JsonObject source, string key, long fallback = 0) =>
        AsLong(source[key], fallback);

    public static ulong ULong(this JsonObject source, string key, ulong fallback = 0) =>
        AsULong(source[key], fallback);

    public static int Int(this JsonObject source, string key, int fallback = 0)
    {
        var value = AsLong(source[key], fallback);
        return (int)Math.Clamp(value, int.MinValue, int.MaxValue);
    }

    public static double Double(this JsonObject source, string key, double fallback = 0) =>
        AsDouble(source[key], fallback);

    public static bool Bool(this JsonObject source, string key, bool fallback = false) =>
        AsBool(source[key], fallback);

    public static JsonObject? Obj(this JsonObject source, string key) => source[key] as JsonObject;

    public static JsonArray? Arr(this JsonObject source, string key) => source[key] as JsonArray;
}

// ---- Wire rows ---------------------------------------------------------

/// <summary>One row of <c>instances.list</c>.</summary>
public sealed record CoreInstanceRow(
    string Id,
    string Name,
    string Minecraft,
    string LoaderKind,
    string LoaderVersion,
    bool Favorite,
    bool Installed,
    int? ModCount,
    ulong SizeBytes,
    ulong PlaySeconds,
    long? LastPlayedMs);

/// <summary><c>[game]</c> section as the core serializes it.</summary>
public sealed record CoreGameConfig(
    string Username,
    uint MemoryMb,
    string JvmArgs,
    IReadOnlyList<int>? Resolution,
    bool Fullscreen,
    bool ShowLog,
    bool AutoCompleteResources)
{
    public static CoreGameConfig Default { get; } = new(
        "Steve",
        0,
        string.Empty,
        null,
        false,
        false,
        true);
}

/// <summary>Aggregate returned by <c>app.snapshot</c>.</summary>
public sealed record CoreAppSnapshot(
    string Platform,
    string AppVersion,
    string DataRoot,
    int JavaCount,
    int ActiveTasks,
    ulong DiskFreeBytes,
    ulong TotalMemoryBytes,
    int InstanceCount);

/// <summary>One row of <c>tasks.list</c>.</summary>
public sealed record CoreTaskRow(
    string Id,
    string Name,
    string State,
    double Fraction,
    double Speed);

/// <summary>One entry of <c>versions.list</c> / <c>versions.news</c>.</summary>
public sealed record CoreVersionEntry(string Id, string Kind, string? Released);

/// <summary>Download preview from <c>version.plan</c>.</summary>
public sealed record CorePlanPreview(
    string VersionId,
    string BaseId,
    uint JavaMajor,
    int Files,
    ulong TotalBytes);

/// <summary>Per-instance overrides; <c>null</c> means "inherit [game]".</summary>
public sealed record CoreOverrides(
    string? Java,
    int? MemoryMb,
    string? JvmArgs,
    IReadOnlyList<int>? Resolution,
    bool? Fullscreen);

/// <summary>Result of <c>instances.settings.get</c>.</summary>
public sealed record CoreInstanceSettings(
    uint EffectiveMemoryMb,
    uint GlobalMemoryMb,
    CoreOverrides Overrides);

/// <summary>Event pushed by the core event queue (camelCase, bindings/fg-ffi).</summary>
public abstract record CoreEvent
{
    public sealed record TaskProgress(string TaskId, string Name, double Fraction, double Speed) : CoreEvent;

    public sealed record TaskFinished(
        string TaskId,
        string Name,
        string State,
        IReadOnlyList<string> Detail) : CoreEvent;

    public sealed record GameStarted(string InstanceId, int? Pid) : CoreEvent;

    public sealed record GameWindowReady(string InstanceId) : CoreEvent;

    public sealed record GameLine(string InstanceId, string Text) : CoreEvent;

    public sealed record GameExited(string InstanceId, int? ExitCode, ulong PlaySeconds) : CoreEvent;

    /// <summary>Parses one envelope; unknown event types are ignored.</summary>
    public static CoreEvent? Parse(JsonObject json)
    {
        var type = json.Str("type");
        if (type is null)
        {
            return null;
        }

        var instanceId = json.Str("instanceId") ?? string.Empty;
        return type switch
        {
            "task.progress" => json.Str("taskId") is { } taskId
                ? new TaskProgress(
                    taskId,
                    json.Str("name") ?? taskId,
                    json.Double("fraction"),
                    json.Double("speed"))
                : null,
            "task.finished" => json.Str("taskId") is { } finishedId
                ? new TaskFinished(
                    finishedId,
                    json.Str("name") ?? finishedId,
                    json.Str("state") ?? "succeeded",
                    json.Arr("detail")?.Select(item => Json.AsString(item) ?? string.Empty).ToList()
                        ?? new List<string>())
                : null,
            "game.started" => new GameStarted(instanceId, json["pid"] is null ? null : json.Int("pid")),
            "game.windowReady" => new GameWindowReady(instanceId),
            "game.line" => new GameLine(instanceId, json.Str("text") ?? string.Empty),
            "game.exited" => new GameExited(
                instanceId,
                json["exitCode"] is null ? null : json.Int("exitCode"),
                json.ULong("playSeconds")),
            _ => null,
        };
    }
}

/// <summary>Decoders for the JSON envelopes documented in <c>bindings/README.md</c>.</summary>
public static class CoreWire
{
    public static IReadOnlyList<CoreInstanceRow> ParseInstances(JsonObject payload) =>
        Items(payload, "result").Select(ParseInstance).ToList();

    public static CoreInstanceRow ParseInstance(JsonObject row) => new(
        row.StrOr("id", string.Empty),
        row.StrOr("name", string.Empty),
        row.StrOr("minecraft", string.Empty),
        row.StrOr("loaderKind", "vanilla"),
        row.StrOr("loaderVersion", string.Empty),
        row.Bool("favorite"),
        row.Bool("installed"),
        row["modCount"] is { } mods && mods.GetValueKind() == JsonValueKind.Number
            ? row.Int("modCount")
            : null,
        row.ULong("sizeBytes"),
        row.ULong("playSeconds"),
        row["lastPlayedMs"] is { } lastPlayed && lastPlayed.GetValueKind() == JsonValueKind.Number
            ? row.Long("lastPlayedMs")
            : null);

    public static CoreAppSnapshot? ParseAppSnapshot(JsonObject payload)
    {
        if (payload["platform"] is null && payload["instanceCount"] is null)
        {
            return null;
        }

        return new CoreAppSnapshot(
            payload.StrOr("platform", "windows"),
            payload.StrOr("appVersion", "0.1.0"),
            payload.StrOr("dataRoot", CoreSettingsSnapshot.Fallback.DataRoot),
            payload.Int("javaCount"),
            payload.Int("activeTasks"),
            payload.ULong("diskFreeBytes"),
            payload.ULong("totalMemoryBytes"),
            payload.Int("instanceCount"));
    }

    public static CoreGameConfig? ParseGameConfig(JsonObject payload)
    {
        if (payload["username"] is null && payload["memoryMb"] is null)
        {
            return null;
        }

        return new CoreGameConfig(
            payload.StrOr("username", "Steve"),
            (uint)Math.Clamp(payload.Long("memoryMb"), 0, uint.MaxValue),
            payload.StrOr("jvmArgs", string.Empty),
            ParseResolution(payload["resolution"]),
            payload.Bool("fullscreen"),
            payload.Bool("showLog"),
            payload.Bool("autoCompleteResources", true));
    }

    public static IReadOnlyList<CoreTaskRow> ParseTasks(JsonObject payload) =>
        Items(payload, "result")
            .Select(row => new CoreTaskRow(
                row.StrOr("id", string.Empty),
                row.StrOr("name", string.Empty),
                row.StrOr("state", "running"),
                row.Double("fraction"),
                row.Double("speed")))
            .ToList();

    public static IReadOnlyList<CoreVersionEntry> ParseVersions(JsonObject payload, string key = "versions") =>
        Items(payload, key)
            .Select(row => new CoreVersionEntry(
                row.StrOr("id", string.Empty),
                row.StrOr("kind", "unknown"),
                row.Str("released")))
            .Where(entry => entry.Id.Length > 0)
            .ToList();

    public static CorePlanPreview? ParsePlan(JsonObject payload)
    {
        var versionId = payload.Str("versionId");
        if (string.IsNullOrEmpty(versionId))
        {
            return null;
        }

        return new CorePlanPreview(
            versionId,
            payload.StrOr("baseId", versionId),
            (uint)Math.Clamp(payload.Long("javaMajor", 21), 0, uint.MaxValue),
            payload.Int("files"),
            payload.ULong("totalBytes"));
    }

    public static CoreInstanceSettings? ParseInstanceSettings(JsonObject payload)
    {
        if (payload.Obj("overrides") is not { } overrides)
        {
            return null;
        }

        var effective = payload.Obj("effective");
        var globals = payload.Obj("globals");
        return new CoreInstanceSettings(
            (uint)Math.Clamp(effective?.Long("memoryMb") ?? 0, 0, uint.MaxValue),
            (uint)Math.Clamp(globals?.Long("memoryMb") ?? 0, 0, uint.MaxValue),
            new CoreOverrides(
                overrides.Str("java"),
                overrides["memoryMb"] is JsonValue memory && memory.GetValueKind() == JsonValueKind.Number
                    ? overrides.Int("memoryMb")
                    : null,
                overrides.Str("jvmArgs"),
                ParseResolution(overrides["resolution"]),
                Json.AsNullableBool(overrides["fullscreen"])));
    }

    internal static IReadOnlyList<JsonObject> Items(JsonObject payload, string key) =>
        payload[key] is JsonArray array
            ? array.OfType<JsonObject>().ToList()
            : new List<JsonObject>();

    private static IReadOnlyList<int>? ParseResolution(JsonNode? node)
    {
        if (node is not JsonArray array || array.Count < 2)
        {
            return null;
        }

        var values = new List<int>(2);
        foreach (var item in array.Take(2))
        {
            values.Add((int)Math.Clamp(Json.AsLong(item), 0, int.MaxValue));
        }

        return values;
    }
}
