use super::params::*;
use super::*;

/// Keeps only the file stem/extension of a remote file name.
pub(super) fn sanitize_download_name(name: &str) -> Option<String> {
    let base = name
        .rsplit(['/', '\\'])
        .next()
        .unwrap_or_default()
        .trim()
        .trim_matches('.');
    if base.is_empty() || base == "." || base == ".." {
        return None;
    }
    let mut safe = String::with_capacity(base.len());
    for character in base.chars() {
        if character.is_alphanumeric() || matches!(character, '.' | '-' | '_' | '+' | ' ') {
            safe.push(character);
        } else {
            safe.push('_');
        }
    }
    let safe = safe.trim_matches('.').to_string();
    if safe.is_empty() { None } else { Some(safe) }
}

pub(super) struct TaskSink {
    pub(super) cx: TaskCx,
}

impl fg_net::ProgressSink for TaskSink {
    fn on_bytes(&self, bytes: u64) {
        self.cx.advance(bytes);
    }
}

/// Derives a safe instance id for a modpack import, avoiding collisions.
pub(super) fn modpack_instance_id(manager: &InstanceManager, name: &str) -> String {
    let mut base: String = name
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect();
    base.truncate(24);
    let base = if base.is_empty() {
        "modpack".to_string()
    } else {
        base
    };
    if !manager.exists(&base) {
        return base;
    }
    let mut counter = 2_u32;
    loop {
        let candidate = format!("{base}-{counter}");
        if !manager.exists(&candidate) {
            return candidate;
        }
        counter += 1;
    }
}

pub(super) fn count_mods(game_dir: &Path) -> Option<usize> {
    let directory = game_dir.join("mods");
    if !directory.is_dir() {
        return None;
    }
    let count = std::fs::read_dir(directory)
        .ok()?
        .filter_map(|entry| entry.ok())
        .filter(|entry| {
            let name = entry.file_name().to_string_lossy().to_lowercase();
            name.ends_with(".jar") || name.ends_with(".jar.disabled")
        })
        .count();
    Some(count)
}

pub(super) async fn discover_java(layout: &Layout) -> Vec<fg_java::JavaRuntime> {
    let scanner = fg_java::JavaScanner::new().with_extra_root(layout.runtimes());
    scanner.scan_probing(Duration::from_secs(2)).await
}

/// Resolves an explicit Java home/executable from `[override] java`.
pub(super) fn explicit_java(path: &Path) -> Result<JavaRuntime> {
    let home = if path.is_dir() {
        fg_java::normalize_home(path)
    } else {
        path.parent()
            .and_then(|bin| bin.parent())
            .map(|home| fg_java::normalize_home(home).unwrap_or_else(|| home.to_path_buf()))
    }
    .ok_or_else(|| {
        Error::with_detail(
            ErrorKind::NotFound,
            "error.java.not_found",
            format!("{} does not contain bin/java", path.display()),
        )
    })?;
    JavaRuntime::from_home(home, fg_java::JavaSource::ExtraRoot).ok_or_else(|| {
        Error::with_detail(
            ErrorKind::NotFound,
            "error.java.not_found",
            format!("{} does not contain bin/java", path.display()),
        )
    })
}

pub(super) fn java_count(layout: &Layout) -> usize {
    fg_java::JavaScanner::new()
        .with_extra_root(layout.runtimes())
        .scan()
        .len()
}

pub(super) fn non_empty(value: Option<&str>) -> Option<String> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "auto")
        .map(str::to_string)
}

pub(super) fn inherit_text(value: &Value) -> SettingValue {
    match value.as_str() {
        Some(text) if !text.trim().is_empty() => SettingValue::Text(text.to_string()),
        _ => SettingValue::Text(String::new()),
    }
}

pub(super) fn resolution_of(value: &Value) -> [u32; 2] {
    let Some(array) = value.as_array() else {
        return [0, 0];
    };
    [
        array.first().and_then(Value::as_u64).unwrap_or(0) as u32,
        array.get(1).and_then(Value::as_u64).unwrap_or(0) as u32,
    ]
}

/// Version ids whose release date is not reliable (1.RV-Pre1 shipped on
/// 2016-03-31 UTC) or which are not in the current manifest (2.0).
pub(super) const APRIL_FOOLS_IDS: &[&str] = &[
    "2.0",
    "15w14a",
    "1.RV-Pre1",
    "3D Shareware v1.34",
    "20w14infinite",
    "22w13oneblockatatime",
    "23w13a_or_b",
    "24w14potato",
    "25w14craftmine",
];

pub(super) fn is_april_fools(id: &str, version_type: &str, release_time: Option<&str>) -> bool {
    if APRIL_FOOLS_IDS.contains(&id) {
        return true;
    }
    // A snapshot published on April 1 is an April Fools snapshot. Keep release
    // versions out of this rule: 26.1.1 shipped on 2026-04-01 and is a normal
    // release, not a joke version.
    version_type == "snapshot"
        && release_time.is_some_and(|value| value.get(5..10) == Some("04-01"))
}

pub(super) fn kind_matches(
    request: &str,
    id: &str,
    version_type: &str,
    release_time: Option<&str>,
) -> bool {
    let april_fools = is_april_fools(id, version_type, release_time);
    match request {
        "release" => version_type == "release" && !april_fools,
        "snapshot" => version_type == "snapshot" && !april_fools,
        "april_fools" => april_fools,
        "old" => (version_type == "old_beta" || version_type == "old_alpha") && !april_fools,
        _ => true,
    }
}

/// Wire mapping of `[game]` used by settings surfaces.
pub(super) fn game_json(game: &GameConfig) -> Value {
    json!({
        "username": game.username,
        "memoryMb": game.memory_mb,
        "java": game.java.as_ref().map(|path| path.display().to_string()),
        "jvmArgs": game.jvm_args,
        "resolution": game.resolution,
        "fullscreen": game.fullscreen,
        "showLog": game.show_log,
        "autoCompleteResources": game.auto_complete_resources,
    })
}

pub(super) fn settings_json(config: &LauncherConfig) -> Value {
    json!({
        "theme": config.appearance.theme.as_str(),
        "language": config.appearance.language.as_str(),
        "accent": config.appearance.accent,
        "initialPage": config.general.initial_page.as_str(),
        "defaultDisplayAccount": config.general.default_display_account.as_str(),
        "download": {
            "source": config.download.source.as_str(),
            "versionSource": config.download.version_source.as_str(),
            "concurrency": config.download.concurrency,
            "perHost": config.download.per_host,
            "rateLimitKib": config.download.rate_limit_kib,
            "segments": config.download.segments,
            "segmentThresholdMb": config.download.segment_threshold_mb,
            "retries": config.download.retries,
        },
        "network": {
            "proxy": config.network.proxy,
            "timeoutSecs": config.network.timeout_secs,
        },
    })
}

pub(super) fn parse_wire_theme(value: &str) -> Result<fg_core::ThemePreference> {
    match value {
        "system" => Ok(fg_core::ThemePreference::System),
        "light" => Ok(fg_core::ThemePreference::Light),
        "dark" => Ok(fg_core::ThemePreference::Dark),
        other => Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.theme.invalid",
            other.to_string(),
        )),
    }
}

pub(super) fn parse_display_account(value: &str) -> Result<fg_core::DisplayAccount> {
    match value {
        "community" => Ok(fg_core::DisplayAccount::Community),
        "launcher" => Ok(fg_core::DisplayAccount::Launcher),
        other => Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.settings.display_account",
            other.to_string(),
        )),
    }
}

pub(super) fn parse_initial_page(value: &str) -> Result<fg_core::InitialPage> {
    match value {
        "home" => Ok(fg_core::InitialPage::Home),
        "library" => Ok(fg_core::InitialPage::Library),
        "downloads" => Ok(fg_core::InitialPage::Downloads),
        other => Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.settings.initial_page",
            other.to_string(),
        )),
    }
}

pub(super) fn apply_download_settings(
    download: &mut fg_core::DownloadConfig,
    value: &Value,
) -> Result<()> {
    let fields = value
        .as_object()
        .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "error.config.fields"))?;
    for (key, value) in fields {
        match key.as_str() {
            "source" => download.source = parse_download_source(&as_string(value))?,
            "versionSource" => {
                download.version_source = parse_download_source(&as_string(value))?;
            }
            "concurrency" => download.concurrency = as_u32(value),
            "perHost" => download.per_host = as_u32(value),
            "rateLimitKib" => download.rate_limit_kib = as_u32(value),
            "segments" => download.segments = as_u32(value),
            "segmentThresholdMb" => download.segment_threshold_mb = as_u32(value),
            "retries" => download.retries = as_u32(value),
            other => {
                return Err(Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.config.unknown_field",
                    format!("download.{other}"),
                ));
            }
        }
    }
    Ok(())
}

pub(super) fn apply_network_settings(
    network: &mut fg_core::NetworkConfig,
    value: &Value,
) -> Result<()> {
    let fields = value
        .as_object()
        .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "error.config.fields"))?;
    for (key, value) in fields {
        match key.as_str() {
            "proxy" => {
                let proxy = as_string(value).trim().to_string();
                if !proxy.is_empty()
                    && !proxy.eq_ignore_ascii_case("auto")
                    && !proxy.eq_ignore_ascii_case("none")
                {
                    // Reject a malformed URL now: the client parses the proxy
                    // on startup, and a bad value would otherwise stop the
                    // core from launching on the next restart.
                    NetUrl::parse(&proxy).map_err(|error| {
                        Error::with_detail(
                            ErrorKind::InvalidInput,
                            "error.settings.proxy",
                            error.to_string(),
                        )
                    })?;
                }
                network.proxy = if proxy.is_empty() {
                    "auto".to_string()
                } else {
                    proxy
                };
            }
            "timeoutSecs" => network.timeout_secs = as_u32(value),
            other => {
                return Err(Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.config.unknown_field",
                    format!("network.{other}"),
                ));
            }
        }
    }
    Ok(())
}

pub(super) fn parse_download_source(value: &str) -> Result<fg_core::DownloadSource> {
    match value {
        "auto" => Ok(fg_core::DownloadSource::Auto),
        "official" => Ok(fg_core::DownloadSource::Official),
        "mirror" => Ok(fg_core::DownloadSource::Mirror),
        other => Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.settings.source",
            other.to_string(),
        )),
    }
}

pub(super) fn as_string(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_string()
}

pub(super) fn as_u32(value: &Value) -> u32 {
    value.as_u64().unwrap_or(0) as u32
}

pub(super) fn as_bool(value: &Value) -> bool {
    value.as_bool().unwrap_or(false)
}

pub(super) fn from_value<T: serde::de::DeserializeOwned>(params: &Value) -> Result<T> {
    serde_json::from_value(params.clone()).map_err(|error| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.rpc.params",
            error.to_string(),
        )
    })
}

pub(super) fn to_value<T: Serialize>(payload: T) -> Result<Value> {
    serde_json::to_value(payload).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.rpc.serialize",
            error.to_string(),
        )
    })
}

pub(super) fn requested_loader(request: &VersionRequest) -> Result<Option<(LoaderKind, String)>> {
    let Some(kind) = non_empty(Some(request.loader_kind.as_str())) else {
        return Ok(None);
    };
    Ok(Some((
        LoaderKind::parse(&kind)?,
        request.loader_version.clone().unwrap_or_default(),
    )))
}

pub(super) fn resolve_loader_selection(
    request: &InstallStartRequest,
    instance: Option<&Instance>,
) -> Result<Option<(LoaderKind, String)>> {
    if let Some(kind) = non_empty(Some(request.loader_kind.as_str())) {
        return Ok(Some((
            LoaderKind::parse(&kind)?,
            request.loader_version.clone().unwrap_or_default(),
        )));
    }
    let Some(instance) = instance else {
        return Ok(None);
    };
    if instance.loader.is_vanilla() {
        return Ok(None);
    }
    Ok(Some((
        LoaderKind::parse(&instance.loader.kind)?,
        instance.loader.version.clone(),
    )))
}

// ---- Content helpers --------------------------------------------------
pub(super) fn parse_content_source(value: &str) -> Result<ContentSource> {
    if value.trim().is_empty() {
        Ok(ContentSource::Modrinth)
    } else {
        ContentSource::parse(value)
    }
}

/// Resolves the game version and loader filter for a content API call.
///
/// An explicit request value wins over the instance; without either the filter
/// is omitted so browsing still works before an instance is selected.
pub(super) fn content_plan_summary(plan: &fg_content::ContentPlan) -> Value {
    json!({
        "source": plan.source.as_str(),
        "projectId": plan.project_id,
        "projectName": plan.project_name,
        "versionId": plan.version_id,
        "versionNumber": plan.version_number,
        "kind": plan.kind.as_str(),
        "files": plan.items.len(),
        "totalBytes": plan.total_bytes,
        "warnings": plan.warnings,
        "items": plan.items.iter().map(|item| json!({
            "fileName": item.file_name,
            "projectId": item.project_id,
            "projectName": item.project_name,
            "versionId": item.version_id,
            "versionNumber": item.version_number,
            "dependency": matches!(item.dependency, fg_content::PlannedFileKind::RequiredDependency),
            "size": item.size,
        })).collect::<Vec<_>>(),
    })
}

pub(super) fn content_context(
    instance: Option<&Instance>,
    kind: ContentKind,
    game_version: Option<&str>,
    loader: Option<&str>,
) -> (Option<String>, Option<String>) {
    let game_version = non_empty(game_version)
        .or_else(|| instance.map(|instance| instance.minecraft.clone()))
        .filter(|value| !value.is_empty());
    let loader = if kind.loader_specific() {
        non_empty(loader)
            .or_else(|| instance.map(|instance| instance.loader.kind.clone()))
            .and_then(|value| fg_content::model::normalize_loader(&value))
    } else {
        None
    };
    (game_version, loader)
}

// ---- host info --------------------------------------------------------
#[cfg(target_os = "macos")]
pub(super) fn disk_free_bytes(root: &Path) -> u64 {
    use std::os::unix::ffi::OsStrExt;

    let mut stats: libc::statfs = unsafe { std::mem::zeroed() };
    let path = match std::ffi::CString::new(root.as_os_str().as_bytes()) {
        Ok(path) => path,
        Err(_) => return 0,
    };
    if unsafe { libc::statfs(path.as_ptr(), &mut stats) } != 0 {
        return 0;
    }
    stats.f_bsize as u64 * stats.f_bavail as u64
}

#[cfg(all(unix, not(target_os = "macos")))]
pub(super) fn disk_free_bytes(root: &Path) -> u64 {
    use std::os::unix::ffi::OsStrExt;

    let mut stats: libc::statvfs = unsafe { std::mem::zeroed() };
    let path = match std::ffi::CString::new(root.as_os_str().as_bytes()) {
        Ok(path) => path,
        Err(_) => return 0,
    };
    if unsafe { libc::statvfs(path.as_ptr(), &mut stats) } != 0 {
        return 0;
    }
    stats.f_bsize as u64 * stats.f_bavail as u64
}

#[cfg(not(unix))]
pub(super) fn disk_free_bytes(_root: &Path) -> u64 {
    0
}

#[cfg(target_os = "macos")]
pub(super) fn total_memory_bytes() -> u64 {
    let mut value: u64 = 0;
    let mut length = std::mem::size_of_val(&value);
    let name = b"hw.memsize\0";
    if unsafe {
        libc::sysctlbyname(
            name.as_ptr() as *const libc::c_char,
            &mut value as *mut u64 as *mut libc::c_void,
            &mut length,
            std::ptr::null_mut(),
            0,
        )
    } != 0
    {
        return 0;
    }
    value
}

#[cfg(all(unix, not(target_os = "macos")))]
pub(super) fn total_memory_bytes() -> u64 {
    let mut value: libc::sysinfo = unsafe { std::mem::zeroed() };
    if unsafe { libc::sysinfo(&mut value) } != 0 {
        return 0;
    }
    // `totalram` is `c_ulong`: already `u64` on 64-bit targets, but `u32` on
    // 32-bit ones. Keep the widening conversion so both stay correct; clippy
    // only sees the 64-bit case in CI.
    #[allow(clippy::useless_conversion)]
    {
        u64::from(value.totalram) * u64::from(value.mem_unit)
    }
}

#[cfg(not(unix))]
pub(super) fn total_memory_bytes() -> u64 {
    0
}
