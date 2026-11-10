namespace Fograin.Windows.Core;

/// <summary>
/// Error payload shared by every fg-ffi call (§3.1 of docs/design.md):
/// <c>{ kind, messageKey, detail }</c>. The UI maps <see cref="MessageKey"/> to
/// localized copy and keeps the technical detail for diagnostics.
/// </summary>
public sealed record CoreErrorPayload(string Kind, string MessageKey, string? Detail)
{
    /// <summary>Used when fg_ffi.dll is absent or cannot be loaded.</summary>
    public static CoreErrorPayload Unavailable { get; } =
        new("unavailable", "error.core.unavailable", null);

    /// <summary>User-facing Chinese copy for the common core errors.</summary>
    public string UserMessage => MessageKey switch
    {
        "error.launch.not_installed" => "游戏资源尚未安装完成，请先在下载页补全资源。",
        "error.launch.incomplete" => "游戏文件不完整，上次下载可能没有完成。请重新下载缺失文件。",
        "error.launch.arm64_natives" => "这个版本只有 x86_64 原生库，请使用 x86_64 的 Java 8 启动。",
        "error.java.none_installed" => "未发现 Java 运行时。请安装 Java 后再启动。",
        "error.java.required_missing" => "没有找到匹配的 Java 版本，请安装对应版本的 Java。",
        "error.java.too_new" => "只找到更新的 Java，旧版本游戏可能会启动失败。",
        "error.java.too_old" => "只找到较旧的 Java，新版本游戏可能会启动失败。",
        "error.instance.duplicate" => "同名实例已经存在，请换一个实例名称。",
        "error.instance.delete_running" => "游戏正在运行，退出后才能删除实例。",
        "error.instance.version_mismatch" => "实例的 Minecraft 版本与要安装的版本不一致。",
        "error.instance.invalid_id" => "实例名称无法转换为安全的目录名。",
        "error.launch.already_running" => "已经有一个游戏在运行了。",
        "error.launch.exit_code" => "游戏异常退出。请查看日志确认原因。",
        "error.task.cancelled" => "任务已取消。",
        "error.task.already_running" => "已有下载任务在进行，请等待完成或取消。",
        "error.network" => "网络请求失败，请检查网络或代理设置。",
        "error.install.failed" => "安装没有完成，请重试。",
        "error.rpc.unknown_method" or "error.rpc.parse" or "error.rpc.params" =>
            "启动器核心拒绝了这次请求。",
        "error.core.unavailable" => "未找到启动器核心 fg_ffi.dll。请把它放在程序目录后重试。",
        _ => Detail is { Length: > 0 } detail ? detail : MessageKey,
    };

    /// <summary>Short technical description for debug output and error details.</summary>
    public string TechnicalDetail => string.IsNullOrWhiteSpace(Detail) ? MessageKey : Detail!;
}

/// <summary>Raised when the core rejects an RPC or the ABI cannot be reached.</summary>
public sealed class CoreException : Exception
{
    public CoreException(CoreErrorPayload error)
        : base(error.UserMessage)
    {
        Error = error;
    }

    public CoreException(string messageKey, string? detail = null)
        : this(new CoreErrorPayload("internal", messageKey, detail))
    {
    }

    public CoreErrorPayload Error { get; }
}
