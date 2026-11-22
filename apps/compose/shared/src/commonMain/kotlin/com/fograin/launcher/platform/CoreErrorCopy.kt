package com.fograin.launcher.platform

/**
 * Chinese copy for the core's `messageKey` values.
 *
 * The Rust core emits a stable key plus a technical `detail`; this table is the
 * only place that turns a key into user-facing prose. Keys not listed here fall
 * back to `detail` (then to the raw key), so a newer core never produces an
 * empty dialog.
 *
 * The copy is Chinese-only. Moving it into `composeResources` needs a
 * translation for every entry, which is a separate content task.
 */
internal fun coreErrorCopy(messageKey: String, detail: String?): String =
    when (messageKey) {
        "error.launch.not_installed" -> "游戏资源尚未安装完成，请先在下载页补全资源。"
        "error.launch.incomplete" -> "游戏文件不完整，上次下载可能没有完成。请重新下载缺失文件。"
        "error.launch.arm64_natives" -> "这个版本只有 x86_64 原生库。请开启 Java 自动下载，或改用 x86_64 Java 8（Rosetta）。"
        "error.java.none_installed" -> "未发现 Java 运行时。请先在设置中下载，或开启自动下载。"
        "error.java.required_missing" -> "没有找到匹配的 Java 版本，请安装对应版本的 Java。"
        "error.java.runtime_unsupported" -> "当前平台没有可自动下载的官方 Java。请手动安装系统 Java。"
        "error.java.x86_64_required" -> "这个旧版本需要 x86_64 Java 8（Rosetta）。请开启自动下载或在 Java 运行时页手动下载。"
        "error.java.runtime_install" -> "Java 运行时下载没有完成，请检查网络后重试。"
        "error.java.runtime_manifest", "error.java.runtime_manifest_hash",
        "error.java.runtime_manifest_empty", "error.java.runtime_index",
        "error.java.runtime_index_empty", "error.java.runtime_incomplete",
        "error.java.runtime_plan_version", "error.java.runtime_marker_missing" ->
        "Java 运行时的官方清单无法读取或校验失败。"
        "error.java.runtime_in_use" -> "这个 Java 运行时仍在使用，退出游戏或等待下载完成后再删除。"
        "error.java.runtime_not_found" -> "这个 Java 运行时已经不存在了。"
        "error.java.runtime_id", "error.java.runtime_path", "error.java.runtime_major",
        "error.java.runtime_link", "error.java.runtime_permission" ->
        "Java 运行时参数或本地文件不正确。"
        "error.java.runtime_plan_serialize", "error.java.runtime_marker" ->
        "Java 运行时状态无法保存。"
        "error.java.runtime_remove" -> "删除 Java 运行时失败，请检查文件权限。"
        "error.java.too_new" -> "只找到更新的 Java，旧版本游戏可能会启动失败。"
        "error.java.too_old" -> "只找到较旧的 Java，新版本游戏可能会启动失败。"
        "error.instance.duplicate" -> "同名实例已经存在，请换一个实例名称。"
        "error.instance.delete_running" -> "游戏正在运行，退出后才能删除实例。"
        "error.instance.version_mismatch" -> "这个实例的游戏版本与下载版本不一致。"
        "error.launch.already_running" -> "已经有一个游戏在运行了。"
        "error.launch.exit_code" -> "游戏异常退出。请查看日志确认原因。"
        "error.task.cancelled" -> "任务已取消。"
        "error.task.failed" -> "任务执行失败，请查看详情。"
        "error.task.not_found" -> "找不到这个任务。"
        "error.task.finished" -> "这个任务已经结束，无法暂停。"
        "error.task.not_paused" -> "只有已暂停的任务可以继续下载。"
        "error.task.resume", "error.task.resume_unsupported" ->
        "这个任务无法继续，请重新开始下载。"
        "error.task.already_running" -> "已有下载任务在进行，请等待完成或取消。"
        "error.network" -> "网络请求失败，请检查网络或代理设置。"
        "error.install.failed" -> "安装没有完成，请重试。"
        "error.loader.unknown_kind" -> "不支持的加载器类型。"
        "error.loader.unsupported_minecraft" -> "这个加载器没有适配该 Minecraft 版本。"
        "error.loader.version_invalid" -> "加载器版本格式不正确。"
        "error.loader.version_missing" -> "请选择加载器版本。"
        "error.loader.profile_unavailable" -> "无法获取加载器版本信息，请稍后重试。"
        "error.loader.profile_parse", "error.loader.install_profile",
        "error.loader.version_json", "error.loader.version_json_missing" ->
        "加载器安装文件无法解析。"
        "error.loader.installer_download" -> "加载器安装包下载失败，请检查网络或镜像设置。"
        "error.loader.installer_zip", "error.loader.installer_entry" ->
        "加载器安装包不完整或已损坏。"
        "error.loader.installer_missing" -> "加载器安装包缺失，请重新下载。"
        "error.loader.processor_failed", "error.loader.processor_spawn",
        "error.loader.processor_jar", "error.loader.processor_classpath",
        "error.loader.processor_manifest" ->
        "Forge/NeoForge 安装处理器执行失败，请查看日志。"
        "error.loader.patcher_failed", "error.loader.patcher_spawn",
        "error.loader.client_jar" -> "OptiFine 补丁执行失败，请确认游戏文件完整。"
        "error.loader.java_required" -> "安装这个加载器需要 Java。请在设置中下载或开启自动下载。"
        "error.loader.output_hash" -> "加载器安装产物校验失败，请重试或更换镜像。"
        "error.loader.minecraft_mismatch" -> "加载器安装包与所选 Minecraft 版本不一致。"
        "error.loader.mappings_missing" -> "该版本缺少 Mojang 映射文件，无法安装 Forge/NeoForge。"
        "error.instance.invalid_id" -> "实例名称无法转换为安全的目录名。"
        "error.auth.server_url" -> "第三方登录地址不正确，请输入 https:// 开头的 Yggdrasil 地址。"
        "error.auth.server_unreachable" -> "无法连接第三方登录服务器，请检查地址、网络或代理设置。"
        "error.auth.server_metadata", "error.auth.server_status" ->
        "第三方登录服务器返回了无法识别的响应，请确认这是 authlib-injector / Yggdrasil 服务。"
        "error.auth.credentials_required" -> "请输入账户和密码。"
        "error.auth.invalid_credentials" -> "用户名或密码不正确。"
        "error.auth.forbidden" -> "第三方登录服务器拒绝了这个请求。"
        "error.auth.response" -> "第三方登录响应不完整，请稍后重试。"
        "error.auth.no_profiles" -> "这个账户还没有可用的游戏角色。"
        "error.auth.profile_not_found", "error.auth.profile_required" ->
        "请选择要使用的游戏角色。"
        "error.auth.selection_expired" -> "角色选择已过期，请重新登录。"
        "error.auth.login_required" -> "这个账户需要重新登录。"
        "error.auth.expired" -> "登录已过期，请在账户页重新登录。"
        "error.auth.account_not_found", "error.auth.no_account" ->
        "找不到这个账户，请重新选择或添加账户。"
        "error.auth.kind_mismatch", "error.auth.injector_required",
        "error.auth.injector_metadata", "error.auth.injector_download" ->
        "第三方登录组件不可用，请检查网络后重试。"
        "error.auth.microsoft_pending" -> "正版登录将在后续版本提供。"
        "error.auth.conflicting_account" -> "不能同时指定账户和离线用户名。"
        "error.auth.accounts_parse", "error.auth.accounts_read",
        "error.auth.accounts_write", "error.auth.accounts_serialize",
        "error.auth.credentials_write", "error.auth.accounts_schema" ->
        "账户数据无法读取或保存，请检查数据目录权限。"
        "error.content.kind" -> "不支持的内容类型。"
        "error.content.source" -> "不支持的内容来源。"
        "error.content.project_id", "error.content.version_id" ->
        "项目或版本标识不正确。"
        "error.content.request", "error.content.response" ->
        "内容服务器返回了无法识别的响应，请稍后重试或切换镜像。"
        "error.content.version_missing" -> "这个项目没有适配当前实例的版本。"
        "error.content.version_mismatch", "error.content.version_incompatible" ->
        "这个版本与实例的 Minecraft 版本或加载器不兼容。"
        "error.content.no_files", "error.content.download_disabled" ->
        "这个版本没有可下载文件。"
        "error.content.loader_required" ->
        "模组需要 Fabric、Quilt、Forge 或 NeoForge 实例；请先安装加载器。"
        "error.content.file", "error.content.file_missing", "error.content.file_conflict" ->
        "内容文件不存在或名称冲突。"
        "error.content.delete" -> "删除内容文件失败，请检查文件权限。"
        "error.content.index_read", "error.content.index_parse",
        "error.content.index_write", "error.content.index_serialize",
        "error.content.index_schema" ->
        "内容索引无法读取或保存，请检查实例目录权限。"
        "error.content.update" -> "检查内容更新失败，请稍后重试。"
        "error.content.no_candidates" -> "没有可用的下载源。"
        "error.world.name", "error.world.not_a_world" -> "世界名称或目录不正确。"
        "error.world.missing" -> "找不到这个世界。"
        "error.world.delete", "error.world.restore" -> "世界操作失败，请检查文件权限。"
        "error.world.exists" -> "同名世界已经存在，请换一个名称。"
        "error.diag.read", "error.diag.missing" -> "无法读取这日志文件，可能已被删除或没有权限。"
        "error.diag.export" -> "诊断包写入失败，请检查数据目录的磁盘空间与权限。"
        "error.diag.rule_id", "error.diag.rule_pattern", "error.diag.rules_parse" ->
        "诊断规则文件格式不正确，请检查 diag/rules 下的 TOML。"
        "error.datapack.read", "error.datapack.write", "error.datapack.delete" ->
        "数据包文件无法读取或写入，请检查世界目录权限。"
        "error.datapack.name", "error.datapack.missing", "error.datapack.source" ->
        "数据包名称或文件不正确。"
        "error.datapack.exists" -> "同名数据包已经存在。"
        "error.modpack.missing" -> "找不到这个整合包文件。"
        "error.modpack.read", "error.modpack.manifest" ->
        "整合包清单无法读取，请确认文件没有损坏。"
        "error.modpack.format" -> "无法识别这个整合包格式，支持 .mrpack、CurseForge/HMCL zip 与 MultiMC 实例目录。"
        "error.modpack.minecraft" -> "整合包没有声明 Minecraft 版本。"
        "error.modpack.file" -> "整合包里有一个文件缺少下载来源。"
        "error.modpack.directory" -> "这个目录不是 MultiMC/Prism 实例（缺少 mmc-pack.json 与游戏目录）。"
        "error.modpack.overrides", "error.modpack.record", "error.modpack.export" ->
        "整合包文件写入失败，请检查数据目录的磁盘空间与权限。"
        "error.modpack.import" -> "整合包导入没有完成，请检查网络后重试。"
        "error.world.backup", "error.world.backup_read", "error.world.backup_write",
        "error.world.backup_parse", "error.world.backup_missing",
        "error.world.backup_name", "error.world.backup_delete",
        "error.world.backup_entries", "error.world.backup_entry",
        "error.world.backup_too_large" ->
        "世界备份不可用或无法写入，请检查备份目录。"
        "error.core.unavailable" -> "无法连接启动器核心。请确认应用已正确安装原生库。"
        "error.data_root.already_set", "error.data_root.service_started" ->
        "启动器核心已经启动，无法再切换数据目录。请重启应用。"
        "error.rpc.unknown_method", "error.rpc.parse", "error.rpc.params" ->
        "启动器核心拒绝了这次请求。"
        else -> detail ?: messageKey
    }
