//! Async launcher service behind the C ABI (§13).
//!
//! The service owns one Tokio runtime, the launcher data root, the download
//! engine and the task queue. Native clients call it through short JSON RPC
//! functions ([`crate::fg_rpc_call`]) and observe progress through the event
//! queue ([`crate::fg_events_next`]). Every payload serializes with camelCase
//! keys so the Swift, Kotlin and C# clients can share one decoding helper.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use fg_auth::{
    Account, AccountKind, AccountStore, AuthResponse, AuthSession, AuthlibInjector, InjectorSource,
    OfflineAccount, YggdrasilClient, YggdrasilServer, random_token, validate_name,
};
use fg_content::{
    ContentClient, ContentIndex, ContentKind, ContentSource, InstallRequest, LocalContent,
    SearchQuery, check_updates, delete_local, plan_install, scan_local, set_enabled,
};
use fg_core::{Error, ErrorKind, GameConfig, LauncherConfig, Result, config};
use fg_diag::{Analysis, BundleRequest, LogBundle, LogSourceKind, RuleSet};
use fg_game::{
    instance::Instance,
    launch::{GameEvent, GameProcess, LaunchOptions, LaunchPlan},
    layout::Layout,
    manager::{InheritableSetting, InstanceManager, SettingValue},
    rules::RuleContext,
};
use fg_java::{InstalledRuntime, JavaArch, JavaManager, JavaRuntime};
use fg_loader::{LoaderKind, LoaderPaths, LoaderPlanner};
use fg_net::{DownloadOptions, DownloadRequest, Downloader, MirrorTable, Priority, Region};
use fg_task::{Control, NodeState, RunOptions, StopReason, TaskCx, TaskGraph};
use fg_world::{Backup, World};

pub use crate::events::EventQueue;

/// How often task progress is pushed into the event queue (§6.3 throttle).
pub const PROGRESS_INTERVAL_MS: u64 = 250;

/// Launcher state shared by every `fg_*` call in the process.
pub struct Service {
    root: PathBuf,
    layout: Layout,
    downloader: Arc<Downloader>,
    table: MirrorTable,
    region: Region,
    manager: Mutex<InstanceManager>,
    tasks: Mutex<Vec<QueuedTask>>,
    task_counter: AtomicU64,
    events: Arc<EventQueue>,
    runtime: tokio::runtime::Runtime,
    session: Mutex<Option<SessionHandle>>,
    accounts: Mutex<AccountStore>,
    /// Yggdrasil logins waiting for the user to choose a profile. Passwords
    /// are never stored; only the token pair and profile list live here for
    /// at most [`PENDING_LOGIN_TTL`].
    pending_logins: Mutex<HashMap<String, PendingLogin>>,
}

/// How long a multi-profile login stays selectable.
const PENDING_LOGIN_TTL: Duration = Duration::from_secs(600);

#[derive(Debug, Clone)]
struct PendingLogin {
    server: YggdrasilServer,
    response: AuthResponse,
    created: Instant,
}

/// Live game session; the process itself lives in the watcher task.
#[derive(Debug, Clone)]
pub struct SessionHandle {
    pub instance_id: String,
    pub pid: Option<u32>,
    pub stop: Arc<tokio::sync::Notify>,
    pub finished: Arc<Mutex<Option<Value>>>,
}

#[derive(Debug, Clone)]
struct SessionState {
    instance_id: String,
    pid: Option<u32>,
    started: std::time::Instant,
    stop: Arc<tokio::sync::Notify>,
    finished: Arc<Mutex<Option<Value>>>,
}

/// RPC call that can recreate a paused graph.
///
/// Task graphs are one-shot (`FnOnce` nodes), so resuming means replaying the
/// operation that produced the graph. Download caches make the second run pick
/// up the bytes that were already on disk.
#[derive(Debug, Clone)]
struct TaskReplay {
    method: &'static str,
    params: Value,
}

/// One queued graph the UI can observe through `tasks.list`.
struct QueuedTask {
    id: String,
    name: String,
    control: Control,
    graph: Arc<TaskGraph>,
    /// Set for `java:<major>` tasks so automatic provisioning can reuse a
    /// runtime download that is already running.
    runtime_id: Option<String>,
    /// Original request, used by `tasks.resume`.
    replay: Option<TaskReplay>,
    done: Arc<TaskDone>,
}

/// Completion slot for a queued graph.
///
/// `launch.start` / `install.start` wait on this when they had to download a
/// runtime first; `Notify` wakes the waiter and the result slot makes the wait
/// race-free.
#[derive(Default)]
struct TaskDone {
    result: Mutex<Option<Result<()>>>,
    notify: tokio::sync::Notify,
}

/// Row of `tasks.list`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskRow {
    pub id: String,
    pub name: String,
    pub state: &'static str,
    pub fraction: f64,
    pub speed: f64,
}

/// One instance as the UI sees it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InstanceRow {
    pub id: String,
    pub name: String,
    pub minecraft: String,
    pub loader_kind: String,
    pub loader_version: String,
    pub favorite: bool,
    pub installed: bool,
    pub mod_count: Option<usize>,
    pub size_bytes: u64,
    pub play_seconds: u64,
    pub last_played_ms: Option<i64>,
}

/// One discovered runtime as the Java page sees it.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRuntimeRow {
    /// Marker id when this runtime was installed by the launcher.
    pub id: Option<String>,
    pub vendor: String,
    pub version: String,
    pub major: Option<u32>,
    pub arch: String,
    pub home: String,
    pub executable: String,
    pub source: String,
    pub managed: bool,
    pub size_bytes: u64,
    pub installed_at_ms: Option<u64>,
    pub component: Option<String>,
    pub platform: Option<String>,
}

impl JavaRuntimeRow {
    fn from_runtime(runtime: &JavaRuntime, managed: &[InstalledRuntime]) -> Self {
        let marker = managed
            .iter()
            .find(|installed| installed.runtime.home == runtime.home)
            .map(|installed| &installed.marker);
        Self {
            id: marker.map(|marker| marker.id.clone()),
            vendor: marker
                .map(|marker| marker.vendor.clone())
                .or_else(|| runtime.vendor.clone())
                .unwrap_or_else(|| runtime.source.label().to_string()),
            version: runtime
                .version
                .as_ref()
                .map_or_else(|| "?".to_string(), |version| version.full.clone()),
            major: runtime.major(),
            arch: runtime.arch.to_string(),
            home: runtime.home.display().to_string(),
            executable: runtime.executable.display().to_string(),
            source: runtime.source.label().to_string(),
            managed: marker.is_some(),
            size_bytes: marker.map_or(0, |marker| marker.total_bytes),
            installed_at_ms: marker.map(|marker| marker.installed_at_ms),
            component: marker.map(|marker| marker.component.clone()),
            platform: marker.map(|marker| marker.platform.clone()),
        }
    }
}

/// One downloadable runtime on the Java page.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaCatalogRow {
    pub runtime_id: String,
    pub major: u32,
    pub component: String,
    pub version: String,
    pub arch: String,
    pub platform: String,
    pub fallback: bool,
    pub installed: bool,
    pub installed_version: Option<String>,
}

/// One account as the account panel sees it. Secrets never cross this row.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountRow {
    pub id: String,
    pub kind: &'static str,
    pub name: String,
    pub uuid: String,
    pub server: Option<String>,
    pub server_name: Option<String>,
    pub selected: bool,
}

impl AccountRow {
    fn from_account(account: &Account, selected_id: &str) -> Self {
        Self {
            id: account.id.clone(),
            kind: account.kind.as_str(),
            name: account.name.clone(),
            uuid: account.uuid.clone(),
            server: account.server.clone(),
            server_name: account.server_name.clone(),
            selected: account.id == selected_id,
        }
    }
}

impl Service {
    /// Builds the engine, task queue and instance manager for `root`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the layout cannot be created or the
    /// Tokio runtime cannot start.
    pub fn new(root: PathBuf) -> Result<Arc<Self>> {
        let layout = Layout::new(&root);
        layout.ensure_base_dirs()?;
        let config = config::read_config(&root)?;
        let downloader = Downloader::builder(&root)
            .options(DownloadOptions::from_config(
                &config.download,
                &config.network,
            ))
            .build()
            .map_err(|error| {
                Error::with_detail(ErrorKind::Io, "error.io.engine", error.to_string())
            })?
            .into();
        let accounts = AccountStore::load_or_default(&root, &config.game.username)?;
        let manager = InstanceManager::new(layout.clone(), config.game.clone());
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .map_err(|error| {
                Error::with_detail(ErrorKind::Internal, "error.runtime", error.to_string())
            })?;
        let table = MirrorTable::load(&root);
        Ok(Arc::new(Self {
            root,
            layout,
            downloader,
            table,
            region: Region::detect(),
            manager: Mutex::new(manager),
            tasks: Mutex::new(Vec::new()),
            task_counter: AtomicU64::new(0),
            events: Arc::new(EventQueue::new()),
            runtime,
            session: Mutex::new(None),
            accounts: Mutex::new(accounts),
            pending_logins: Mutex::new(HashMap::new()),
        }))
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    #[must_use]
    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    #[must_use]
    pub fn events(&self) -> &EventQueue {
        &self.events
    }

    /// Runs a future to completion inside the embedded runtime.
    pub fn block_on<T>(&self, future: impl std::future::Future<Output = T>) -> T {
        self.runtime.block_on(future)
    }

    fn manager(&self) -> InstanceManager {
        self.manager
            .lock()
            .expect("instance manager poisoned")
            .clone()
    }

    fn set_manager_globals(&self, globals: GameConfig) {
        *self.manager.lock().expect("instance manager poisoned") =
            InstanceManager::new(self.layout.clone(), globals);
    }

    fn config(&self) -> Result<LauncherConfig> {
        config::read_config(&self.root)
    }

    fn resolver(&self) -> Result<fg_game::resolver::VersionResolver> {
        Ok(fg_game::resolver::VersionResolver::new(
            self.downloader.clone(),
            self.layout.clone(),
            self.table.clone(),
            self.config()?.download.version_source,
            self.region,
        ))
    }

    fn installer(&self) -> Result<fg_game::install::Installer> {
        self.installer_with_java(None)
    }

    fn installer_with_java(&self, java: Option<PathBuf>) -> Result<fg_game::install::Installer> {
        let config = self.config()?;
        Ok(fg_game::install::Installer::new(
            self.downloader.clone(),
            self.resolver()?,
            self.layout.clone(),
            self.table.clone(),
            config.download.source,
            self.region,
            RuleContext::current(),
        )
        .with_java(java))
    }

    fn loader_planner(&self) -> Result<LoaderPlanner<'_>> {
        let config = self.config()?;
        Ok(LoaderPlanner::new(
            &self.downloader,
            &self.table,
            config.download.source,
            self.region,
            LoaderPaths::new(
                self.layout.root(),
                self.layout.libraries(),
                self.layout.cache(),
            ),
        ))
    }

    fn java_manager(&self) -> Result<JavaManager> {
        let config = self.config()?;
        Ok(JavaManager::new(
            self.layout.root().to_path_buf(),
            self.downloader.clone(),
            self.table.clone(),
            config.download.source,
            self.region,
        ))
    }

    /// Every discovered runtime plus the managed markers that describe it.
    fn java_rows(&self) -> Result<Vec<JavaRuntimeRow>> {
        let managed = self.java_manager()?.installed();
        let scanner = fg_java::JavaScanner::new().with_extra_root(self.layout.runtimes());
        let mut rows: Vec<JavaRuntimeRow> = scanner
            .scan()
            .into_iter()
            .map(|runtime| JavaRuntimeRow::from_runtime(&runtime, &managed))
            .collect();
        rows.sort_by(|left, right| {
            right
                .managed
                .cmp(&left.managed)
                .then_with(|| right.major.cmp(&left.major))
                .then_with(|| left.home.cmp(&right.home))
        });
        Ok(rows)
    }

    /// Dispatches one RPC method. Names and payloads match
    /// `bindings/README.md` so the native clients stay decoupled from Rust.
    pub async fn rpc(self: &Arc<Self>, method: &str, params: Value) -> Result<Value> {
        let object = params.as_object().cloned().unwrap_or_default();
        let params = Value::Object(object);
        match method {
            "app.snapshot" => self.app_snapshot().await,
            "instances.list" => to_value(self.instance_rows().await?),
            "instances.summary" => self.instances_summary().await,
            "instances.create" => self.instances_create(&params),
            "instances.import" => self.instances_import(&params).await,
            "instances.rename" => self.instances_rename(&params).await,
            "instances.favorite" => self.instances_favorite(&params).await,
            "instances.delete" => self.instances_delete(&params).await,
            "instances.settings.get" => self.instance_settings(&params).await,
            "instances.settings.set" => self.instance_settings_set(&params).await,
            "instances.verify" => self.instance_verify(&params).await,
            "instances.dir" => self.instance_dir(&params).await,

            "versions.list" => self.versions_list(&params).await,
            "loaders.versions" => self.loaders_versions(&params).await,
            "version.plan" => self.version_plan(&params).await,
            "versions.news" => self.versions_news(&params).await,
            "install.start" => self.install_start(&params).await,
            "tasks.list" => self.tasks_list().await,
            "tasks.pause" => self.tasks_pause(&params).await,
            "tasks.resume" => self.tasks_resume(&params).await,
            "tasks.cancel" => self.tasks_cancel(&params).await,

            "launch.start" => self.launch_start(&params).await,
            "launch.status" => self.launch_status().await,
            "session.wait" => self.session_wait().await,
            "session.stop" => self.session_stop(&params).await,
            "game.log.recent" => self.game_log_recent(&params).await,

            "gameConfig.get" => self.game_config_value(),
            "gameConfig.set" => self.game_config_set(&params).await,

            "accounts.list" => self.accounts_list(),
            "accounts.addOffline" => self.accounts_add_offline(&params),
            "accounts.select" => self.accounts_select(&params),
            "accounts.remove" => self.accounts_remove(&params).await,
            "accounts.refresh" => self.accounts_refresh(&params).await,
            "accounts.yggdrasil.discover" => self.accounts_yggdrasil_discover(&params).await,
            "accounts.yggdrasil.login" => self.accounts_yggdrasil_login(&params).await,
            "accounts.yggdrasil.selectProfile" => {
                self.accounts_yggdrasil_select_profile(&params).await
            }

            "content.local" => self.content_local(&params).await,
            "content.toggle" => self.content_toggle(&params).await,
            "content.delete" => self.content_delete(&params).await,
            "content.search" => self.content_search(&params).await,
            "content.versions" => self.content_versions(&params).await,
            "content.plan" => self.content_plan(&params).await,
            "content.install" => self.content_install(&params).await,
            "content.updates" => self.content_updates(&params).await,
            "worlds.list" => self.worlds_list(&params).await,
            "worlds.backups" => self.worlds_backups(&params).await,
            "worlds.backup" => self.worlds_backup(&params).await,
            "worlds.restore" => self.worlds_restore(&params).await,
            "worlds.delete" => self.worlds_delete(&params).await,
            "worlds.backup.delete" => self.worlds_backup_delete(&params).await,

            "diag.logs" => self.diag_logs(&params).await,
            "diag.analyze" => self.diag_analyze(&params).await,
            "diag.export" => self.diag_export(&params).await,

            "datapacks.list" => self.datapacks_list(&params).await,
            "datapacks.toggle" => self.datapacks_toggle(&params).await,
            "datapacks.delete" => self.datapacks_delete(&params).await,
            "datapacks.import" => self.datapacks_import(&params).await,

            "modpack.inspect" => self.modpack_inspect(&params).await,
            "modpack.install" => self.modpack_install(&params).await,
            "modpack.import" => self.modpack_import(&params).await,
            "modpack.export" => self.modpack_export(&params).await,

            "java.list" => self.java_list().await,
            "java.catalog" => self.java_catalog().await,
            "java.install" => self.java_install(&params).await,
            "java.remove" => self.java_remove(&params).await,
            "java.policy.set" => self.java_policy_set(&params).await,

            _ => Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.rpc.unknown_method",
                method.to_string(),
            )),
        }
    }

    async fn app_snapshot(&self) -> Result<Value> {
        let instances = self.instance_rows().await?;
        Ok(json!({
            "platform": fg_core::Platform::detect().id(),
            "appVersion": env!("CARGO_PKG_VERSION"),
            "dataRoot": self.root.display().to_string(),
            "javaCount": java_count(&self.layout),
            "activeTasks": self.tasks_list_len(),
            "diskFreeBytes": disk_free_bytes(&self.root),
            "totalMemoryBytes": total_memory_bytes(),
            "instanceCount": instances.len(),
        }))
    }

    async fn instance_rows(&self) -> Result<Vec<InstanceRow>> {
        let rows = self
            .manager()
            .list()?
            .into_iter()
            .map(|summary| {
                let instance = summary.instance().clone();
                InstanceRow {
                    id: summary.id().to_string(),
                    name: summary.name().to_string(),
                    minecraft: summary.minecraft().to_string(),
                    loader_kind: summary.loader_kind().to_string(),
                    loader_version: summary.loader_version().to_string(),
                    favorite: summary.favorite(),
                    installed: summary.installed(),
                    mod_count: count_mods(&instance.game_dir(&self.layout)),
                    size_bytes: summary.size_bytes(),
                    play_seconds: summary.play_seconds(),
                    last_played_ms: summary.last_played_epoch_ms(),
                }
            })
            .collect();
        Ok(rows)
    }

    /// Home aggregate: recent list, favorites and totals (§13).
    async fn instances_summary(&self) -> Result<Value> {
        let mut rows = self.instance_rows().await?;
        rows.sort_by(|a, b| {
            b.last_played_ms
                .unwrap_or(i64::MIN)
                .cmp(&a.last_played_ms.unwrap_or(i64::MIN))
                .then_with(|| a.id.cmp(&b.id))
        });
        let recent: Vec<InstanceRow> = rows.iter().take(6).cloned().collect();
        let favorites: Vec<InstanceRow> = rows.iter().filter(|row| row.favorite).cloned().collect();
        Ok(json!({
            "recent": recent,
            "favorites": favorites,
            "all": rows,
            "sizeBytesTotal": rows.iter().map(|row| row.size_bytes).sum::<u64>(),
            "playSecondsTotal": rows.iter().map(|row| row.play_seconds).sum::<u64>(),
        }))
    }

    fn instances_create(&self, params: &Value) -> Result<Value> {
        let request: InstanceCreateRequest = from_value(params)?;
        let manager = self.manager();
        let loader_kind = non_empty(Some(request.loader_kind.as_str()));
        let instance = manager.create(
            &request.id,
            &request.name,
            &request.minecraft,
            loader_kind.as_deref(),
            request
                .loader_version
                .as_deref()
                .filter(|v| !v.trim().is_empty()),
        )?;
        Ok(json!({
            "id": instance.id,
            "name": instance.name,
            "minecraft": instance.minecraft,
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    async fn instances_import(&self, params: &Value) -> Result<Value> {
        let request: InstanceImportRequest = from_value(params)?;
        let directory = PathBuf::from(&request.directory);
        let instance = self.manager().import_external(
            &directory,
            request.name.as_deref(),
            request.minecraft.as_deref(),
        )?;
        Ok(json!({
            "id": instance.id,
            "name": instance.name,
            "minecraft": instance.minecraft,
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    async fn instances_rename(&self, params: &Value) -> Result<Value> {
        let request: IdNameRequest = from_value(params)?;
        self.manager().rename(&request.id, &request.name)?;
        Ok(json!(null))
    }

    async fn instances_favorite(&self, params: &Value) -> Result<Value> {
        let request: IdFlagRequest = from_value(params)?;
        let favorite = self.manager().set_favorite(&request.id, request.favorite)?;
        Ok(json!({ "favorite": favorite }))
    }

    async fn instances_delete(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        if let Some(session) = self.session.lock().expect("session poisoned").as_ref()
            && session.instance_id == request.id
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.instance.delete_running",
                format!("instance {} is running", request.id),
            ));
        }
        self.manager().delete(&request.id, false)?;
        Ok(json!(null))
    }

    async fn instance_settings(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let manager = self.manager();
        let instance = manager.load(&request.id)?;
        Ok(json!({
            "effective": manager.effective_json(&instance),
            "overrides": json!({
                "java": instance.overrides.java.as_ref().map(|path| path.display().to_string()),
                "memoryMb": instance.overrides.memory_mb,
                "jvmArgs": instance.overrides.jvm_args,
                "resolution": instance.overrides.resolution,
                "fullscreen": instance.overrides.fullscreen,
                "gameDir": instance.overrides.game_dir.as_ref().map(|path| path.display().to_string()),
            }),
            "globals": self.game_config_value()?,
        }))
    }

    async fn instance_settings_set(&self, params: &Value) -> Result<Value> {
        let request: SettingsSetRequest = from_value(params)?;
        let manager = self.manager();
        for update in &request.updates {
            let setting = update.setting.as_str();
            match setting {
                "java" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Java,
                        inherit_text(&update.value),
                    )?;
                }
                "memory" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Memory,
                        SettingValue::Memory(update.value.as_u64().unwrap_or(0) as u32),
                    )?;
                }
                "jvmArgs" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::JvmArgs,
                        inherit_text(&update.value),
                    )?;
                }
                "resolution" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Resolution,
                        SettingValue::Resolution(resolution_of(&update.value)),
                    )?;
                }
                "fullscreen" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Fullscreen,
                        SettingValue::Flag(update.value.as_bool().unwrap_or(false)),
                    )?;
                }
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.instance.setting_unknown",
                        other.to_string(),
                    ));
                }
            }
        }
        Ok(json!(null))
    }

    async fn instance_verify(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let manager = self.manager();
        let instance = manager.load(&request.id)?;
        let version_id = instance.version_id().to_string();
        let resolved = self
            .resolver()?
            .resolve_local(&version_id)
            .map_err(|error| {
                if error.kind == ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.launch.not_installed",
                        format!("{version_id} is not installed"),
                    )
                } else {
                    error
                }
            })?;
        match fg_game::launch::verify_installed(&self.layout, &resolved, &RuleContext::current()) {
            Ok(()) => Ok(json!({ "ok": true, "missing": 0 })),
            Err(error) => Ok(json!({
                "ok": false,
                "messageKey": error.message_key,
                "detail": error.detail.unwrap_or_default(),
            })),
        }
    }

    async fn instance_dir(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let instance = self.manager().load(&request.id)?;
        Ok(json!({
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    // ------------------------------------------------------------------
    // Content and worlds (M8)
    // ------------------------------------------------------------------

    fn content_client(&self) -> Result<ContentClient> {
        let config = self.config()?;
        Ok(ContentClient::new(
            self.http_client()?,
            self.layout.cache(),
            self.table.clone(),
            config.download.source,
            self.region,
        ))
    }

    async fn content_local(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let index = ContentIndex::load(&instance_dir)?;

        let mut items: Vec<LocalContent> = Vec::new();
        let mut mods = 0_usize;
        let mut resourcepacks = 0_usize;
        let mut shaderpacks = 0_usize;
        let mut enabled = 0_usize;
        for kind in ContentKind::ALL {
            let scanned = scan_local(&game_dir, kind, Some(&index))?;
            match kind {
                ContentKind::Mod => mods = scanned.len(),
                ContentKind::ResourcePack => resourcepacks = scanned.len(),
                ContentKind::ShaderPack => shaderpacks = scanned.len(),
                ContentKind::Modpack | ContentKind::Datapack => {}
            }
            enabled += scanned.iter().filter(|item| item.enabled).count();
            items.extend(scanned);
        }

        Ok(json!({
            "items": items,
            "counts": {
                "mod": mods,
                "resourcepack": resourcepacks,
                "shaderpack": shaderpacks,
                "enabled": enabled,
            },
            "indexPath": instance_dir.join(fg_content::CONTENT_INDEX_FILE).display().to_string(),
        }))
    }

    async fn content_toggle(&self, params: &Value) -> Result<Value> {
        let request: ContentToggleRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let new_name = set_enabled(&game_dir, kind, &request.file_name, request.enabled)?;

        let mut index = ContentIndex::load(&instance_dir)?;
        if let Some(mut entry) = index.find(&request.file_name).cloned() {
            index.remove(&request.file_name);
            entry.file_name.clone_from(&new_name);
            index.upsert(entry);
            index.save(&instance_dir)?;
        }
        Ok(json!({ "ok": true, "fileName": new_name }))
    }

    async fn content_delete(&self, params: &Value) -> Result<Value> {
        let request: ContentDeleteRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        delete_local(&game_dir, kind, &request.file_name)?;

        let mut index = ContentIndex::load(&instance_dir)?;
        if index.find(&request.file_name).is_some() {
            index.remove(&request.file_name);
            index.save(&instance_dir)?;
        }
        Ok(json!({ "ok": true }))
    }

    async fn content_search(&self, params: &Value) -> Result<Value> {
        let request: ContentSearchRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.optional_instance(request.instance_id.as_deref())?;
        let (game_version, loader) = content_context(
            instance.as_ref(),
            kind,
            request.game_version.as_deref(),
            request.loader.as_deref(),
        );

        let query = SearchQuery::new(kind)
            .with_query(request.query.trim())
            .with_game_version(game_version)
            .with_loader(loader)
            .with_page(request.offset, request.limit.clamp(1, 50));
        let client = self.content_client()?;
        let page = match source {
            ContentSource::Modrinth => client.modrinth_search(&query).await?,
            ContentSource::Curseforge => client.curseforge_search(&query).await?,
        };
        to_value(page)
    }

    async fn content_versions(&self, params: &Value) -> Result<Value> {
        let request: ContentVersionsRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.optional_instance(request.instance_id.as_deref())?;
        let (game_version, loader) = content_context(
            instance.as_ref(),
            kind,
            request.game_version.as_deref(),
            request.loader.as_deref(),
        );
        let client = self.content_client()?;

        let project = match source {
            ContentSource::Modrinth => client.modrinth_project(&request.project_id).await?,
            ContentSource::Curseforge => client.curseforge_project(&request.project_id).await?,
        };
        let versions = match source {
            ContentSource::Modrinth => {
                client
                    .modrinth_versions(
                        &request.project_id,
                        game_version.as_deref(),
                        loader.as_deref(),
                    )
                    .await?
            }
            ContentSource::Curseforge => {
                client
                    .curseforge_versions(
                        &request.project_id,
                        game_version.as_deref(),
                        loader.as_deref(),
                    )
                    .await?
            }
        };
        Ok(json!({
            "source": source.as_str(),
            "project": project,
            "versions": versions,
        }))
    }

    /// Resolves an install without queueing anything. The content sheet uses
    /// this to list missing prerequisites before the user starts a download.
    async fn content_plan(&self, params: &Value) -> Result<Value> {
        let request: ContentInstallRequest = from_value(params)?;
        let plan = self
            .build_content_plan(&request, request.install_dependencies)
            .await?;
        Ok(content_plan_summary(&plan))
    }

    async fn build_content_plan(
        &self,
        request: &ContentInstallRequest,
        install_dependencies: bool,
    ) -> Result<fg_content::ContentPlan> {
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let index = ContentIndex::load(&instance_dir)?;
        let installed_projects = index
            .items
            .iter()
            .map(|entry| entry.project_id.clone())
            .collect::<Vec<_>>();
        let loader = if kind.loader_specific() {
            fg_content::model::normalize_loader(&instance.loader.kind)
        } else {
            None
        };

        let client = self.content_client()?;
        plan_install(
            &client,
            InstallRequest {
                source,
                project_id: request.project_id.clone(),
                version_id: request.version_id.clone(),
                kind,
                instance_id: instance.id.clone(),
                instance_dir,
                game_dir,
                game_version: instance.minecraft.clone(),
                loader,
                world: request.world.clone(),
                replace_files: request.replace_files.clone(),
                installed_projects,
                install_dependencies,
            },
        )
        .await
    }

    async fn content_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ContentInstallRequest = from_value(params)?;
        let plan = self
            .build_content_plan(&request, request.install_dependencies)
            .await?;
        let summary = content_plan_summary(&plan);

        let plan = Arc::new(plan);
        let task_name = format!("content:{}", plan.project_name);
        let client = self.content_client()?;
        let graph = plan.graph(self.downloader.clone(), client);
        let control = Control::new();
        let task_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "content.install",
                params: params.clone(),
            }),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, task_name));

        let mut response = summary;
        if let Value::Object(object) = &mut response {
            object.insert("taskId".to_string(), Value::String(task_id));
        }
        Ok(response)
    }

    async fn content_updates(&self, params: &Value) -> Result<Value> {
        let request: ContentUpdatesRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let kind = request
            .kind
            .as_deref()
            .filter(|value| !value.trim().is_empty())
            .map(ContentKind::parse)
            .transpose()?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let loader = fg_content::model::normalize_loader(&instance.loader.kind);
        let client = self.content_client()?;
        let check = check_updates(
            &client,
            &instance_dir,
            &game_dir,
            kind,
            &instance.minecraft,
            loader.as_deref(),
        )
        .await?;
        to_value(check)
    }

    async fn worlds_list(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let worlds = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "worlds": worlds, "backups": backups }))
    }

    async fn worlds_backups(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "backups": backups }))
    }

    async fn worlds_backup(&self, params: &Value) -> Result<Value> {
        let request: WorldNameRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let root = self.root.clone();
        let instance_id = instance.id.clone();
        let game_dir = instance.game_dir(&self.layout);
        let name = request.name;
        let backup: Backup = tokio::task::spawn_blocking(move || {
            fg_world::backup_world(&root, &instance_id, &game_dir, &name)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.world.backup",
                format!("backup worker failed: {error}"),
            )
        })??;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "ok": true, "backup": backup, "backups": backups }))
    }

    async fn worlds_restore(&self, params: &Value) -> Result<Value> {
        let request: WorldRestoreRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let root = self.root.clone();
        let instance_id = instance.id.clone();
        let game_dir = instance.game_dir(&self.layout);
        let backup = request.backup;
        let name = request.name;
        let path = tokio::task::spawn_blocking(move || {
            fg_world::restore_backup(&root, &instance_id, &game_dir, &backup, &name)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.world.restore",
                format!("restore worker failed: {error}"),
            )
        })??;
        let worlds: Vec<World> = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        let backups: Vec<Backup> = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({
            "ok": true,
            "path": path.display().to_string(),
            "worlds": worlds,
            "backups": backups,
        }))
    }

    async fn worlds_delete(&self, params: &Value) -> Result<Value> {
        let request: WorldNameRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        fg_world::delete_world(&instance.game_dir(&self.layout), &request.name)?;
        let worlds = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        Ok(json!({ "ok": true, "worlds": worlds }))
    }

    async fn worlds_backup_delete(&self, params: &Value) -> Result<Value> {
        let request: WorldBackupRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        fg_world::delete_backup(&self.root, &instance.id, &request.backup)?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "ok": true, "backups": backups }))
    }

    // ------------------------------------------------------------------
    // Servers, diagnostics, data packs and modpacks (M9)
    // ------------------------------------------------------------------

    /// Collects the diagnostic sources shared by `diag.logs` / `diag.analyze`.
    fn diagnostic_bundle(&self, instance_id: &str) -> Result<(Instance, LogBundle)> {
        let instance = self.manager().load(instance_id)?;
        let stdout = self.events.recent_game_lines(&instance.id, 2000);
        let bundle = fg_diag::collect(&instance.game_dir(&self.layout), stdout, 2000);
        Ok((instance, bundle))
    }

    fn diagnostic_rules(&self) -> RuleSet {
        RuleSet::load(Some(&self.root.join("diag").join("rules"))).unwrap_or_default()
    }

    async fn diag_logs(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (_instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let mut texts = serde_json::Map::new();
        for input in &bundle.inputs {
            texts.insert(
                input.kind.as_str().to_string(),
                Value::String(input.lines.join("\n")),
            );
        }
        Ok(json!({
            "sources": bundle.sources,
            "crash": bundle.crash,
            "texts": Value::Object(texts),
            "rules": self.diagnostic_rules().len(),
        }))
    }

    async fn diag_analyze(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (_instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let analysis = fg_diag::analyze(&bundle, &self.diagnostic_rules());
        to_value(analysis)
    }

    async fn diag_export(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let analysis: Analysis = fg_diag::analyze(&bundle, &self.diagnostic_rules());

        let mut log_texts: Vec<(String, String)> = Vec::new();
        for input in &bundle.inputs {
            let name = match input.kind {
                LogSourceKind::Stdout => "logs/session.log".to_string(),
                LogSourceKind::Log => "logs/latest.log".to_string(),
                LogSourceKind::Crash => analysis
                    .crash
                    .as_ref()
                    .map(|crash| {
                        crash
                            .file
                            .rsplit(['/', '\\'])
                            .next()
                            .unwrap_or("crash.txt")
                            .to_string()
                    })
                    .map(|name| format!("crash-reports/{name}"))
                    .unwrap_or_else(|| "crash-reports/latest.txt".to_string()),
                LogSourceKind::HsErr => bundle
                    .sources
                    .iter()
                    .find(|source| source.kind == LogSourceKind::HsErr)
                    .and_then(|source| source.path.rsplit(['/', '\\']).next().map(str::to_string))
                    .unwrap_or_else(|| "hs_err.log".to_string()),
            };
            log_texts.push((name, input.lines.join("\n")));
        }

        let manager = self.manager();
        let effective = manager.effective_json(&instance);
        let java = effective
            .get("java")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .unwrap_or("自动");
        let memory_mb = effective
            .get("memoryMb")
            .and_then(Value::as_u64)
            .and_then(|value| u32::try_from(value).ok())
            .unwrap_or_default();
        let loader = if instance.loader.is_vanilla() {
            String::new()
        } else {
            format!("{} {}", instance.loader.kind, instance.loader.version)
        };
        let instance_json = json!({
            "instance": {
                "id": instance.id,
                "name": instance.name,
                "minecraft": instance.minecraft,
                "loaderKind": instance.loader.kind,
                "loaderVersion": instance.loader.version,
                "favorite": instance.meta.favorite,
            },
            "effective": effective,
            "contentIndex": fg_content::ContentIndex::load(&self.layout.instance_dir(&instance.id))
                .map(|index| index.items.len())
                .unwrap_or(0),
        })
        .to_string();

        let request = BundleRequest {
            data_root: &self.root,
            instance_id: &instance.id,
            instance_name: &instance.name,
            minecraft: &instance.minecraft,
            loader: &loader,
            java,
            memory_mb,
            analysis: &analysis,
            log_texts,
            instance_json,
            app_version: env!("CARGO_PKG_VERSION"),
            platform: fg_core::Platform::detect().id(),
        };
        let bundle = fg_diag::export_bundle(&request)?;
        Ok(json!({ "bundle": bundle, "analysis": analysis }))
    }

    async fn datapacks_list(&self, params: &Value) -> Result<Value> {
        let request: DatapackRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let items = fg_world::datapack::list(&instance.game_dir(&self.layout), &request.world)?;
        Ok(json!({ "items": items, "count": items.len() }))
    }

    async fn datapacks_toggle(&self, params: &Value) -> Result<Value> {
        let request: DatapackToggleRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let file_name = fg_world::datapack::set_enabled(
            &game_dir,
            &request.world,
            &request.file_name,
            request.enabled,
        )?;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items, "fileName": file_name }))
    }

    async fn datapacks_delete(&self, params: &Value) -> Result<Value> {
        let request: DatapackDeleteRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        fg_world::datapack::delete(&game_dir, &request.world, &request.file_name)?;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items }))
    }

    async fn datapacks_import(&self, params: &Value) -> Result<Value> {
        let request: DatapackImportRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let source = PathBuf::from(&request.path);
        let path = source.clone();
        let world = request.world.clone();
        let target_game = game_dir.clone();
        let file_name = tokio::task::spawn_blocking(move || {
            fg_world::datapack::import(&target_game, &world, &path)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.datapack.write",
                error.to_string(),
            )
        })??;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items, "fileName": file_name }))
    }

    async fn modpack_inspect(&self, params: &Value) -> Result<Value> {
        let request: ModpackInspectRequest = from_value(params)?;
        let path = PathBuf::from(&request.path);
        let pack = tokio::task::spawn_blocking(move || fg_content::modpack::inspect(&path))
            .await
            .map_err(|error| {
                Error::with_detail(ErrorKind::Internal, "error.modpack.read", error.to_string())
            })??;
        to_value(pack)
    }

    /// Downloads a Modrinth/CurseForge modpack and imports it.
    ///
    /// The archive download is a normal task so the queue shows progress; once
    /// it finishes the background orchestrator calls the same import path as a
    /// local `.mrpack`, which queues the base install and the pack graph.
    async fn modpack_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ModpackInstallRequest = from_value(params)?;
        let source = parse_content_source(request.source.as_deref().unwrap_or_default())?;
        let client = self.content_client()?;
        let version = match source {
            ContentSource::Modrinth => {
                client
                    .modrinth_resolve_version(
                        &request.project_id,
                        Some(&request.version_id),
                        None,
                        None,
                    )
                    .await?
            }
            ContentSource::Curseforge => {
                client
                    .curseforge_resolve_version(
                        &request.project_id,
                        Some(&request.version_id),
                        None,
                        None,
                    )
                    .await?
            }
        };
        let mut file = version.file.clone().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.content.no_files",
                request.project_id.clone(),
            )
        })?;
        if file.url.trim().is_empty() {
            if source == ContentSource::Curseforge {
                let file_id = version.version_id.parse::<u64>().map_err(|_| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.content.version_id",
                        version.version_id.clone(),
                    )
                })?;
                file.url = client
                    .curseforge_download_url(&request.project_id, file_id)
                    .await?;
            } else {
                return Err(Error::with_detail(
                    ErrorKind::NotFound,
                    "error.content.no_files",
                    request.project_id.clone(),
                ));
            }
        }
        let name = non_empty(request.name.as_deref()).unwrap_or_else(|| version.project_id.clone());
        let directory = self.layout.cache().join("modpacks");
        std::fs::create_dir_all(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", directory.display()),
            )
        })?;
        let file_name = sanitize_download_name(&file.file_name)
            .unwrap_or_else(|| format!("{}-{}.mrpack", version.project_id, version.version_id));
        let dest = directory.join(format!("{}-{}", version.version_id, file_name));
        let candidates = client.candidates(&file.url)?;
        if candidates.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.content.no_candidates",
                file.url.clone(),
            ));
        }
        let mut download = DownloadRequest::new(dest.clone(), candidates)
            .with_priority(Priority::Mod)
            .with_size_hint(file.size);
        if let Some(sha1) = &file.sha1 {
            download = download.with_sha1(sha1);
        } else if let Some(sha512) = &file.sha512 {
            download = download.with_integrity(fg_net::Integrity::sha512(sha512));
        } else if file.size > 0 {
            download = download.with_size(file.size);
        }
        let weight = (file.size / (64 * 1024)).clamp(1, 200_000) as u32;
        let downloader = Arc::clone(&self.downloader);
        let mut builder = TaskGraph::builder(format!("modpack-download:{}", name));
        builder.task("modpack.download", weight, move |cx| {
            let downloader = Arc::clone(&downloader);
            let download = download.clone();
            async move {
                cx.check()?;
                downloader
                    .download_with(
                        download,
                        fg_net::DownloadCtx {
                            cancel: cx.cancel_handle(),
                            progress: Arc::new(TaskSink { cx: cx.clone() }),
                        },
                    )
                    .await
                    .map(|_| ())
            }
        });
        let graph = builder.build();
        let control = Control::new();
        let task_name = format!("整合包下载:{}", name);
        let task_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "modpack.install",
                params: params.clone(),
            }),
        );
        let this = Arc::clone(self);
        let wait_id = task_id.clone();
        let import_name = name.clone();
        self.runtime.spawn(async move {
            if let Err(error) = this.wait_task(&wait_id).await {
                tracing::warn!(%error, "modpack download failed; import skipped");
                return;
            }
            let payload = json!({ "path": dest, "name": import_name });
            if let Err(error) = this.modpack_import(&payload).await {
                tracing::warn!(%error, "modpack import failed after download");
            }
        });
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, task_name));

        Ok(json!({
            "taskId": task_id,
            "name": name,
            "versionId": version.version_id,
            "versionNumber": version.version_number,
            "source": source.as_str(),
        }))
    }

    async fn modpack_import(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ModpackImportRequest = from_value(params)?;
        let path = PathBuf::from(&request.path);
        let pack = tokio::task::spawn_blocking(move || fg_content::modpack::inspect(&path))
            .await
            .map_err(|error| {
                Error::with_detail(ErrorKind::Internal, "error.modpack.read", error.to_string())
            })??;
        let name = non_empty(request.name.as_deref()).unwrap_or_else(|| pack.name.clone());
        if pack.minecraft.trim().is_empty() {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.modpack.minecraft",
                pack.source_path.clone(),
            ));
        }

        if pack.external {
            let game_dir = pack.game_dir.clone().ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.modpack.directory",
                    pack.source_path.clone(),
                )
            })?;
            let manager = self.manager();
            let instance = manager.import_external(
                Path::new(&game_dir),
                Some(&name),
                Some(&pack.minecraft),
            )?;
            if !pack.loader_kind.eq_ignore_ascii_case("vanilla") {
                let _ =
                    manager.set_loader(&instance.id, &pack.loader_kind, &pack.loader_version, "");
            }
            return Ok(json!({
                "instanceId": instance.id,
                "taskId": Value::Null,
                "external": true,
                "pack": pack,
            }));
        }

        let instance_id = modpack_instance_id(&self.manager(), &name);
        let loader_kind = if pack.loader_kind.eq_ignore_ascii_case("vanilla") {
            String::new()
        } else {
            pack.loader_kind.clone()
        };
        let install = self
            .install_start(&json!({
                "version": pack.minecraft,
                "instanceId": instance_id,
                "instanceName": name,
                "loaderKind": loader_kind,
                "loaderVersion": pack.loader_version,
            }))
            .await?;
        let base_task = install
            .get("taskId")
            .and_then(Value::as_str)
            .map(str::to_string);

        let archive = PathBuf::from(&pack.source_path);
        let replay_params = json!({
            "path": pack.source_path,
            "name": pack.name,
        });
        let this = Arc::clone(self);
        let task_name = format!("整合包:{}", pack.name);
        let pack_for_task = pack.clone();
        let instance_for_task = instance_id.clone();
        let task_name_for_task = task_name.clone();
        let base_task_for_task = base_task.clone();
        self.runtime.spawn(async move {
            if let Some(task) = base_task_for_task
                && let Err(error) = this.wait_task(&task).await
            {
                tracing::warn!(%error, "base install failed; modpack import stopped");
                return;
            }
            let instance = match this.manager().load(&instance_for_task) {
                Ok(instance) => instance,
                Err(error) => {
                    tracing::warn!(%error, "modpack instance disappeared");
                    return;
                }
            };
            let client = match this.content_client() {
                Ok(client) => client,
                Err(error) => {
                    tracing::warn!(%error, "modpack import has no content client");
                    return;
                }
            };
            let plan = fg_content::ModpackPlan::new(
                pack_for_task,
                archive,
                this.layout.instance_dir(&instance_for_task),
                instance.game_dir(&this.layout),
            );
            let graph = plan.graph(Arc::clone(&this.downloader), client);
            let control = Control::new();
            let task_id = this.queue_task(
                &task_name_for_task,
                control.clone(),
                graph,
                None,
                Some(TaskReplay {
                    method: "modpack.import",
                    params: replay_params,
                }),
            );
            this.clone()
                .watch_task(task_id, control, task_name_for_task)
                .await;
        });

        Ok(json!({
            "instanceId": instance_id,
            "taskId": base_task,
            "external": false,
            "pack": pack,
        }))
    }

    async fn modpack_export(&self, params: &Value) -> Result<Value> {
        let request: ModpackExportRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let loader_kind = instance.loader.kind.clone();
        let loader_version = instance.loader.version.clone();
        let name = instance.name.clone();
        let minecraft = instance.minecraft.clone();
        let game_dir = instance.game_dir(&self.layout);
        let exports_dir = self.root.join("exports");
        let export = tokio::task::spawn_blocking(move || {
            fg_content::modpack::export_mrpack(
                &name,
                &minecraft,
                &loader_kind,
                &loader_version,
                &game_dir,
                &exports_dir,
            )
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.modpack.export",
                error.to_string(),
            )
        })??;
        to_value(export)
    }

    async fn versions_list(&self, params: &Value) -> Result<Value> {
        let request: VersionsListRequest = from_value(params)?;
        let manifest = self.resolver()?.manifest(request.refresh).await?;
        let entries: Vec<Value> = manifest
            .versions
            .iter()
            .filter(|version| {
                kind_matches(
                    &request.kind,
                    &version.id,
                    &version.version_type,
                    version.release_time.as_deref(),
                )
            })
            .take(request.limit.clamp(1, 500))
            .map(|version| {
                json!({
                    "id": version.id,
                    "kind": version.version_type,
                    "released": version.release_time,
                })
            })
            .collect();
        Ok(json!({ "versions": entries }))
    }

    async fn versions_news(&self, params: &Value) -> Result<Value> {
        let request: NewsRequest = from_value(params)?;
        let manifest = self.resolver()?.manifest(request.refresh).await?;
        let news: Vec<Value> = manifest
            .versions
            .iter()
            .filter(|version| version.version_type == "release")
            .take(request.limit.clamp(1, 20))
            .map(|version| {
                json!({
                    "id": version.id,
                    "kind": version.version_type,
                    "released": version.release_time,
                })
            })
            .collect();
        Ok(json!({ "news": news }))
    }

    async fn loaders_versions(&self, params: &Value) -> Result<Value> {
        let request: LoaderVersionsRequest = from_value(params)?;
        let kind = LoaderKind::parse(&request.kind)?;
        let planner = self.loader_planner()?;
        let versions = fg_loader::versions(
            planner.downloader,
            planner.table,
            planner.source,
            planner.region,
            fg_loader::VersionListQuery {
                kind,
                minecraft: &request.minecraft,
                limit: request.limit,
            },
        )
        .await?;
        Ok(json!({ "versions": versions }))
    }

    async fn version_plan(&self, params: &Value) -> Result<Value> {
        let request: VersionRequest = from_value(params)?;
        let loader = requested_loader(&request)?;
        let plan = if let Some((kind, version)) = loader {
            let version = if version.trim().is_empty() {
                self.loader_planner()?
                    .latest_version(kind, &request.version)
                    .await?
                    .version
            } else {
                version
            };
            self.installer()?
                .plan_loader(&request.version, kind, &version, None)
                .await?
        } else {
            self.installer()?
                .plan(&request.version, false, None)
                .await?
        };
        Ok(json!({
            "versionId": plan.version_id,
            "baseId": plan.base_id,
            "javaMajor": plan.java_major,
            "files": plan.files.len(),
            "totalBytes": plan.total_bytes,
            "loaderKind": plan.loader.as_ref().map(|loader| loader.kind.as_str()),
            "loaderVersion": plan.loader.as_ref().map(|loader| loader.loader_version.clone()),
        }))
    }

    async fn install_start(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: InstallStartRequest = from_value(params)?;
        let target_instance = if let Some(id) = non_empty(request.instance_id.as_deref()) {
            let manager = self.manager();
            if manager.exists(&id) {
                let instance = manager.load(&id)?;
                if instance.minecraft != request.version {
                    return Err(Error::with_detail(
                        ErrorKind::Conflict,
                        "error.instance.version_mismatch",
                        format!(
                            "instance {id} is for {}, not {}",
                            instance.minecraft, request.version
                        ),
                    ));
                }
                Some(instance)
            } else {
                None
            }
        } else {
            None
        };

        // The completion flow sends the instance's existing loader so a repair
        // install does not silently fall back to vanilla.
        let loader_selection = resolve_loader_selection(&request, target_instance.as_ref())?;
        let (loader_kind, loader_version) = match loader_selection {
            Some((kind, version)) => {
                let version = if version.trim().is_empty() {
                    self.loader_planner()?
                        .latest_version(kind, &request.version)
                        .await?
                        .version
                } else {
                    version
                };
                (Some(kind), Some(version))
            }
            None => (None, None),
        };

        // Validate the version before queueing so the wizard can surface a
        // problem in place instead of through a task failure.
        let resolved = self.resolver()?.resolve_remote(&request.version).await?;
        let java = if loader_kind.is_some_and(LoaderKind::requires_java) {
            let configured = target_instance
                .as_ref()
                .and_then(|instance| instance.overrides.java.clone())
                .or_else(|| self.config().ok().and_then(|config| config.game.java));
            if let Some(path) = configured {
                Some(explicit_java(&path)?.executable)
            } else {
                let selection = self
                    .ensure_java(
                        resolved.java_major,
                        resolved.java_component(),
                        JavaArch::host(),
                        false,
                    )
                    .await?;
                Some(selection.runtime.executable)
            }
        } else {
            None
        };
        let installer = self.installer_with_java(java)?;
        let plan = match (loader_kind, loader_version.as_deref()) {
            (Some(kind), Some(version)) => {
                installer
                    .plan_loader(
                        &request.version,
                        kind,
                        version,
                        request.instance_id.as_deref(),
                    )
                    .await?
            }
            _ => {
                installer
                    .plan_resolved(&resolved, request.instance_id.as_deref())
                    .await?
            }
        };
        plan.save(&self.layout)?;
        let plan_version_id = plan.version_id.clone();
        let graph = installer.graph(&plan);
        drop(plan);

        // Create the instance row before queueing the install: the UI opens
        // its 游戏设置 page from the finished event, and an already-installed
        // version can complete the graph before any detached create task runs.
        // An existing instance with the same Minecraft version is reused, so
        // pressing 开始游戏 on an incomplete instance completes its resources
        // instead of failing with a duplicate-id error.
        if let Some(id) = non_empty(request.instance_id.as_deref()) {
            let manager = self.manager();
            if target_instance.is_none() {
                let name =
                    non_empty(request.instance_name.as_deref()).unwrap_or_else(|| id.clone());
                let kind = loader_kind.map_or("vanilla", LoaderKind::as_str);
                manager.create(
                    &id,
                    &name,
                    &request.version,
                    Some(kind),
                    loader_version.as_deref(),
                )?;
            }
            if let Some(kind) = loader_kind {
                manager.set_loader(
                    &id,
                    kind.as_str(),
                    loader_version.as_deref().unwrap_or_default(),
                    &plan_version_id,
                )?;
            }
        }

        let task_name = format!("install:{}", request.version);
        let control = Control::new();
        let queue_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "install.start",
                params: params.clone(),
            }),
        );
        self.runtime.spawn(
            self.clone()
                .watch_task(queue_id.clone(), control, task_name),
        );
        Ok(json!({
            "taskId": queue_id,
            "versionId": plan_version_id,
        }))
    }

    /// Pushes throttled progress events and a terminal `task.finished`.
    async fn watch_task(self: Arc<Self>, task_id: String, control: Control, name: String) {
        let (graph, events) = {
            let tasks = self.tasks.lock().expect("task queue poisoned");
            let Some(task) = tasks.iter().find(|task| task.id == task_id) else {
                return;
            };
            (task.graph.clone(), self.events.clone())
        };
        loop {
            let progress = graph.progress();
            events.push_task_progress(&task_id, &name, progress.fraction, progress.speed);
            tokio::time::sleep(Duration::from_millis(PROGRESS_INTERVAL_MS)).await;
            // `run_task` owns the single terminal `task.finished` event; this
            // watcher only reports progress and exits when the graph is done.
            if control.is_stopped()
                || progress.is_complete()
                || progress
                    .nodes
                    .iter()
                    .any(|node| node.state == NodeState::Failed)
            {
                break;
            }
        }
    }

    fn queue_task(
        self: &Arc<Self>,
        name: &str,
        control: Control,
        graph: TaskGraph,
        runtime_id: Option<String>,
        replay: Option<TaskReplay>,
    ) -> String {
        let id = format!(
            "task-{}",
            self.task_counter.fetch_add(1, Ordering::Relaxed) + 1
        );
        let done = Arc::new(TaskDone::default());
        self.tasks
            .lock()
            .expect("task queue poisoned")
            .push(QueuedTask {
                id: id.clone(),
                name: name.to_string(),
                control,
                graph: Arc::new(graph),
                runtime_id,
                replay,
                done: done.clone(),
            });
        self.runtime.spawn(self.clone().run_task(id.clone(), done));
        id
    }

    /// Runs one graph to completion and wakes anyone waiting on it.
    async fn run_task(self: Arc<Self>, task_id: String, done: Arc<TaskDone>) {
        let (control, graph) = {
            let tasks = self.tasks.lock().expect("task queue poisoned");
            let Some(task) = tasks.iter().find(|task| task.id == task_id) else {
                return;
            };
            (task.control.clone(), task.graph.clone())
        };
        let report = graph.run(&control, &RunOptions::default()).await;
        let paused = report.interrupted == Some(StopReason::Paused);
        let state = if report.is_success() {
            "succeeded"
        } else if paused {
            "paused"
        } else if report.interrupted.is_some() {
            "cancelled"
        } else {
            "failed"
        };
        let outcome = if report.is_success() {
            Ok(())
        } else {
            let detail = report
                .failed
                .first()
                .map(|(node, error)| format!("{node}: {error}"))
                .unwrap_or_else(|| match report.interrupted {
                    Some(StopReason::Paused) => "task paused".to_string(),
                    Some(StopReason::Cancelled) => "task cancelled".to_string(),
                    None => "task failed".to_string(),
                });
            Err(Error::with_detail(
                if report.interrupted.is_some() {
                    ErrorKind::Cancelled
                } else {
                    ErrorKind::Internal
                },
                if report.interrupted.is_some() {
                    "error.task.cancelled"
                } else {
                    "error.task.failed"
                },
                detail,
            ))
        };
        *done.result.lock().expect("task result poisoned") = Some(outcome);
        done.notify.notify_one();
        self.events
            .push_task_finished_ex(&task_id, graph.name(), state, &report);
    }

    /// Waits for one queued graph to finish.
    async fn wait_task(&self, task_id: &str) -> Result<()> {
        let done = {
            let tasks = self.tasks.lock().expect("task queue poisoned");
            let Some(task) = tasks.iter().find(|task| task.id == task_id) else {
                return Err(Error::with_detail(
                    ErrorKind::NotFound,
                    "error.task.not_found",
                    task_id.to_string(),
                ));
            };
            task.done.clone()
        };
        let notified = done.notify.notified();
        if let Some(result) = done.result.lock().expect("task result poisoned").clone() {
            return result;
        }
        notified.await;
        done.result
            .lock()
            .expect("task result poisoned")
            .clone()
            .unwrap_or_else(|| Err(Error::new(ErrorKind::Internal, "error.task.not_finished")))
    }

    /// Reuses a running `java:<major>` task, if any.
    fn running_java_task(&self, name: &str) -> Option<(String, Option<String>)> {
        let tasks = self.tasks.lock().expect("task queue poisoned");
        tasks
            .iter()
            .rev()
            .find(|task| {
                task.name == name
                    && !task.graph.progress().is_complete()
                    && !task.control.is_stopped()
            })
            .map(|task| (task.id.clone(), task.runtime_id.clone()))
    }

    fn tasks_list_len(&self) -> usize {
        self.tasks.lock().expect("task queue poisoned").len()
    }

    async fn tasks_list(&self) -> Result<Value> {
        let rows: Vec<TaskRow> = self
            .tasks
            .lock()
            .expect("task queue poisoned")
            .iter()
            .map(|task| {
                let progress = task.graph.progress();
                let failed = progress
                    .nodes
                    .iter()
                    .any(|node| node.state == NodeState::Failed);
                let reason = task.control.reason();
                let state = if failed {
                    "failed"
                } else if progress.is_complete() {
                    "succeeded"
                } else if reason == Some(StopReason::Paused) {
                    "paused"
                } else if reason == Some(StopReason::Cancelled) || task.control.is_stopped() {
                    "cancelled"
                } else {
                    "running"
                };
                TaskRow {
                    id: task.id.clone(),
                    name: task.name.clone(),
                    state,
                    fraction: progress.fraction,
                    speed: if state == "running" {
                        progress.speed
                    } else {
                        0.0
                    },
                }
            })
            .collect();
        to_value(rows)
    }

    async fn tasks_pause(&self, params: &Value) -> Result<Value> {
        let request: TaskIdRequest = from_value(params)?;
        let tasks = self.tasks.lock().expect("task queue poisoned");
        let Some(task) = tasks.iter().find(|task| task.id == request.task_id) else {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.task.not_found",
                request.task_id,
            ));
        };
        if task.graph.progress().is_complete() {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.task.finished",
                request.task_id,
            ));
        }
        task.control.pause();
        Ok(json!({ "taskId": task.id, "state": "paused" }))
    }

    /// Replays the RPC that produced a paused task.
    ///
    /// Graphs are one-shot, so "resume" queues a fresh graph from the original
    /// request and drops the paused row once the replacement exists. Downloads
    /// already in the content cache are not fetched again.
    async fn tasks_resume(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: TaskIdRequest = from_value(params)?;
        let (replay, old_name) = {
            let tasks = self.tasks.lock().expect("task queue poisoned");
            let Some(task) = tasks.iter().find(|task| task.id == request.task_id) else {
                return Err(Error::with_detail(
                    ErrorKind::NotFound,
                    "error.task.not_found",
                    request.task_id,
                ));
            };
            if task.control.reason() != Some(StopReason::Paused) {
                return Err(Error::with_detail(
                    ErrorKind::Conflict,
                    "error.task.not_paused",
                    request.task_id,
                ));
            }
            let replay = task.replay.clone().ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::Unsupported,
                    "error.task.resume_unsupported",
                    task.name.clone(),
                )
            })?;
            (replay, task.name.clone())
        };

        // `rpc` -> `tasks_resume` -> `rpc` is recursive; box the replay
        // future so the compiler does not need an infinite-size future.
        let payload = Box::pin(self.rpc(replay.method, replay.params)).await?;
        let task_id = payload
            .get("taskId")
            .and_then(Value::as_str)
            .map(str::to_string)
            .ok_or_else(|| {
                Error::with_detail(ErrorKind::Internal, "error.task.resume", old_name)
            })?;
        {
            let mut tasks = self.tasks.lock().expect("task queue poisoned");
            if let Some(index) = tasks.iter().position(|task| {
                task.id == request.task_id && task.control.reason() == Some(StopReason::Paused)
            }) {
                tasks.remove(index);
            }
        }
        Ok(json!({ "taskId": task_id, "oldTaskId": request.task_id }))
    }

    async fn tasks_cancel(&self, params: &Value) -> Result<Value> {
        let request: TaskIdRequest = from_value(params)?;
        let tasks = self.tasks.lock().expect("task queue poisoned");
        let Some(task) = tasks.iter().find(|task| task.id == request.task_id) else {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.task.not_found",
                request.task_id,
            ));
        };
        if !task.graph.progress().is_complete() || task.control.is_stopped() {
            task.control.cancel();
        }
        Ok(json!({ "taskId": task.id, "state": "cancelled" }))
    }

    async fn java_list(&self) -> Result<Value> {
        let config = self.config()?;
        let supported = !fg_java::host_platforms().is_empty();
        Ok(json!({
            "runtimes": self.java_rows()?,
            "autoDownload": config.java.auto_download,
            "supported": supported,
            "platform": fg_core::Platform::detect().id(),
            "hostArch": JavaArch::host().to_string(),
        }))
    }

    async fn java_catalog(&self) -> Result<Value> {
        let config = self.config()?;
        let manager = self.java_manager()?;
        let installed = manager.installed();
        let candidates = manager.catalog().await?;
        let rows: Vec<JavaCatalogRow> = candidates
            .into_iter()
            .map(|candidate| {
                let hit = installed.iter().find(|runtime| {
                    runtime.marker.major == candidate.major && runtime.marker.arch == candidate.arch
                });
                JavaCatalogRow {
                    runtime_id: fg_java::sanitize_id(&candidate.runtime_id()),
                    major: candidate.major,
                    component: candidate.component,
                    version: candidate.version,
                    arch: candidate.arch.to_string(),
                    platform: candidate.platform,
                    fallback: candidate.fallback,
                    installed: hit.is_some(),
                    installed_version: hit.map(|runtime| runtime.marker.version.clone()),
                }
            })
            .collect();
        Ok(json!({
            "versions": rows,
            "autoDownload": config.java.auto_download,
            "supported": !fg_java::host_platforms().is_empty(),
            "platform": fg_core::Platform::detect().id(),
            "hostArch": JavaArch::host().to_string(),
        }))
    }

    async fn java_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: JavaInstallRequest = from_value(params)?;
        if request.major == 0 {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.java.runtime_major",
                "major must be positive",
            ));
        }
        let name = format!("java:{}", request.major);
        if let Some((task_id, runtime_id)) = self.running_java_task(&name) {
            return Ok(json!({
                "taskId": task_id,
                "runtimeId": runtime_id,
                "existing": true,
            }));
        }

        let manager = Arc::new(self.java_manager()?);
        let component = non_empty(request.component.as_deref());
        let candidate = manager
            .candidate(request.major, component.as_deref())
            .await?;
        let plan = manager.plan(&candidate).await?;
        plan.save(&self.root)?;
        let runtime_id = plan.id.clone();
        let summary = json!({
            "runtimeId": runtime_id,
            "major": plan.major,
            "component": plan.component,
            "version": plan.version,
            "files": plan.files.len(),
            "totalBytes": plan.total_bytes,
        });
        let graph = manager.graph(plan);
        let control = Control::new();
        let java_replay = TaskReplay {
            method: "java.install",
            params: json!({
                "major": request.major,
                "component": request.component,
            }),
        };
        let task_id = self.queue_task(
            &name,
            control.clone(),
            graph,
            Some(runtime_id.clone()),
            Some(java_replay),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, name));
        Ok(json!({
            "taskId": task_id,
            "runtimeId": runtime_id,
            "existing": false,
            "major": summary["major"],
            "component": summary["component"],
            "version": summary["version"],
            "files": summary["files"],
            "totalBytes": summary["totalBytes"],
        }))
    }

    async fn java_remove(&self, params: &Value) -> Result<Value> {
        let request: JavaRemoveRequest = from_value(params)?;
        let id = request.id.trim();
        if id.is_empty() {
            return Err(Error::new(ErrorKind::InvalidInput, "error.java.runtime_id"));
        }
        let manager = self.java_manager()?;
        if let Some(existing) = self.session.lock().expect("session poisoned").as_ref()
            && existing
                .finished
                .lock()
                .expect("exit slot poisoned")
                .is_none()
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.java.runtime_in_use",
                format!("game {} is running", existing.instance_id),
            ));
        }
        if self
            .tasks
            .lock()
            .expect("task queue poisoned")
            .iter()
            .any(|task| {
                task.runtime_id.as_deref() == Some(id)
                    && !task.graph.progress().is_complete()
                    && !task.control.is_stopped()
            })
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.java.runtime_in_use",
                format!("{id} is still being installed"),
            ));
        }
        let marker = manager.remove(id)?;
        Ok(json!({ "ok": true, "id": marker.id }))
    }

    async fn java_policy_set(&self, params: &Value) -> Result<Value> {
        let Some(auto_download) = params.get("autoDownload").and_then(Value::as_bool) else {
            return Err(Error::new(ErrorKind::InvalidInput, "error.rpc.params"));
        };
        let mut config = self.config()?;
        config.java.auto_download = auto_download;
        config::write_config(&self.root, &config)?;
        Ok(json!({ "autoDownload": auto_download }))
    }

    /// Returns a matching runtime, downloading the official one first when
    /// `[java] auto_download` is on and nothing suitable is installed.
    ///
    /// `require_arch` is set for Apple Silicon legacy versions: those need an
    /// x86_64 Java 8 under Rosetta, so an arm64 runtime must not satisfy the
    /// check even when its major matches.
    async fn ensure_java(
        self: &Arc<Self>,
        required: u32,
        component: Option<&str>,
        arch: JavaArch,
        require_arch: bool,
    ) -> Result<fg_java::JavaSelection> {
        let runtimes = discover_java(&self.layout).await;
        let selection = fg_java::select(&runtimes, required, arch);
        if let Ok(selection) = &selection
            && (!require_arch || (selection.exact && arch_matches(selection.runtime.arch, arch)))
        {
            return Ok(selection.clone());
        }

        if !self.config()?.java.auto_download {
            return match selection {
                Ok(selection)
                    if require_arch
                        && (!selection.exact || !arch_matches(selection.runtime.arch, arch)) =>
                {
                    Err(Error::with_detail(
                        ErrorKind::Unsupported,
                        "error.java.x86_64_required",
                        format!("need an exact {arch} Java {required} for this version"),
                    ))
                }
                other => other,
            };
        }

        let manager = Arc::new(self.java_manager()?);
        let name = format!("java:{required}");
        if let Some((task_id, _)) = self.running_java_task(&name) {
            self.wait_task(&task_id).await?;
            // The running task may have installed a different architecture
            // than a legacy launch needs (for example arm64 Java 17 from the
            // management page while 1.18 needs x86_64). Re-check before
            // trusting it.
            let runtimes = discover_java(&self.layout).await;
            if let Ok(selection) = fg_java::select(&runtimes, required, arch)
                && (!require_arch
                    || (selection.exact && arch_matches(selection.runtime.arch, arch)))
            {
                return Ok(selection);
            }
        }
        let candidate = manager.candidate_for(required, component, arch).await?;
        let plan = manager.plan(&candidate).await?;
        plan.save(&self.root)?;
        let runtime_id = plan.id.clone();
        let graph = manager.graph(plan);
        let control = Control::new();
        let java_replay = TaskReplay {
            method: "java.install",
            params: json!({
                "major": required,
                "component": component,
            }),
        };
        let task_id = self.queue_task(
            &name,
            control.clone(),
            graph,
            Some(runtime_id),
            Some(java_replay),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, name));
        self.wait_task(&task_id).await?;
        let runtimes = discover_java(&self.layout).await;
        let selection = fg_java::select(&runtimes, required, arch)?;
        if require_arch && (!selection.exact || !arch_matches(selection.runtime.arch, arch)) {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.java.x86_64_required",
                format!(
                    "downloaded Java {} is {}, but this version needs {arch}",
                    selection
                        .runtime
                        .version
                        .as_ref()
                        .map_or("?", |value| value.full.as_str()),
                    selection.runtime.arch
                ),
            ));
        }
        Ok(selection)
    }

    async fn launch_start(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: LaunchStartRequest = from_value(params)?;
        if let Some(existing) = self.session.lock().expect("session poisoned").as_ref()
            && existing
                .finished
                .lock()
                .expect("exit slot poisoned")
                .is_none()
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.launch.already_running",
                format!("instance {} is still running", existing.instance_id),
            ));
        }
        let plan = self
            .build_launch_plan(
                &request.instance_id,
                request.account_id.as_deref(),
                request.username.as_deref(),
            )
            .await?;
        let process = GameProcess::spawn(&plan)?;
        let pid = process.pid();
        let events = self.events.clone();
        events.push_game_started(&request.instance_id, pid);

        let state = SessionState {
            instance_id: request.instance_id.clone(),
            pid,
            started: std::time::Instant::now(),
            stop: Arc::new(tokio::sync::Notify::new()),
            finished: Arc::new(Mutex::new(None)),
        };
        let watcher_state = state.clone();
        let layout = self.layout.clone();
        self.runtime.spawn(async move {
            run_session(watcher_state, process, events, layout).await;
        });

        *self.session.lock().expect("session poisoned") = Some(SessionHandle {
            instance_id: state.instance_id,
            pid: state.pid,
            stop: state.stop.clone(),
            finished: state.finished.clone(),
        });
        Ok(json!({ "pid": pid }))
    }

    async fn launch_status(&self) -> Result<Value> {
        let session = self.session.lock().expect("session poisoned").clone();
        Ok(json!({
            "running": session.is_some(),
            "instanceId": session.as_ref().map(|session| session.instance_id.clone()),
            "pid": session.as_ref().and_then(|session| session.pid),
        }))
    }

    async fn session_wait(&self) -> Result<Value> {
        let session = self.session.lock().expect("session poisoned").clone();
        let Some(session) = session else {
            return Ok(json!({ "running": false, "finished": null }));
        };
        if let Some(finished) = session.finished.lock().expect("exit slot poisoned").clone() {
            *self.session.lock().expect("session poisoned") = None;
            return Ok(finished);
        }
        Ok(json!({
            "running": true,
            "instanceId": session.instance_id,
            "pid": session.pid,
        }))
    }

    async fn session_stop(&self, params: &Value) -> Result<Value> {
        let request: SessionStopRequest = from_value(params)?;
        let Some(session) = self.session.lock().expect("session poisoned").clone() else {
            return Ok(json!({ "stopped": false }));
        };
        if let Some(id) = request.instance_id
            && id != session.instance_id
        {
            return Ok(json!({ "stopped": false }));
        }
        session.stop.notify_one();
        Ok(json!({ "stopped": true }))
    }

    async fn game_log_recent(&self, params: &Value) -> Result<Value> {
        let request: GameLogRequest = from_value(params)?;
        let lines = self
            .events
            .recent_game_lines(&request.instance_id, request.limit.clamp(1, 500));
        Ok(json!({ "lines": lines }))
    }

    /// Manual camelCase mapping: `launcher.toml` keeps the snake_case keys of
    /// the other sections while the wire format stays camelCase.
    fn game_config_value(&self) -> Result<Value> {
        let game = self.config()?.game;
        Ok(game_json(&game))
    }

    async fn game_config_set(&self, params: &Value) -> Result<Value> {
        let fields = params
            .as_object()
            .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "error.config.fields"))?;
        let mut config = self.config()?;
        for (key, value) in fields {
            match key.as_str() {
                "username" => config.game.username = as_string(value),
                "java" => {
                    config.game.java = if value.is_null() {
                        None
                    } else {
                        Some(PathBuf::from(as_string(value)))
                    };
                }
                "memoryMb" => config.game.memory_mb = as_u32(value),
                "showLog" => config.game.show_log = as_bool(value),
                "jvmArgs" => config.game.jvm_args = as_string(value),
                "fullscreen" => config.game.fullscreen = as_bool(value),
                "resolution" => {
                    config.game.resolution = if value.is_null() {
                        None
                    } else {
                        Some([as_u32(&value[0]), as_u32(&value[1])])
                    };
                }
                "autoCompleteResources" => config.game.auto_complete_resources = as_bool(value),
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.config.unknown_field",
                        other.to_string(),
                    ));
                }
            }
        }
        config::write_config(&self.root, &config)?;
        self.set_manager_globals(config.game.clone());
        self.game_config_value()
    }

    // ------------------------------------------------------------------
    // Accounts (M7): offline + Yggdrasil / authlib-injector.
    // ------------------------------------------------------------------

    fn account_rows(&self) -> (Vec<AccountRow>, String) {
        let store = self.accounts.lock().expect("accounts poisoned");
        let selected = store.selected_id().unwrap_or_default().to_string();
        let rows = store
            .accounts()
            .iter()
            .map(|account| AccountRow::from_account(account, &selected))
            .collect();
        (rows, selected)
    }

    fn accounts_list(&self) -> Result<Value> {
        let (accounts, selected_id) = self.account_rows();
        Ok(json!({ "accounts": accounts, "selectedId": selected_id }))
    }

    fn accounts_add_offline(&self, params: &Value) -> Result<Value> {
        let request: AccountNameRequest = from_value(params)?;
        let name = request.name.trim();
        let mut store = self.accounts.lock().expect("accounts poisoned");
        let account = store.add_offline(name)?.clone();
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({
            "account": AccountRow::from_account(&account, &selected),
            "selectedId": selected,
        }))
    }

    fn accounts_select(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.select(&request.id)?;
        store.save()?;
        Ok(json!({ "selectedId": request.id }))
    }

    async fn accounts_remove(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            store.account(&request.id).cloned()
        };
        if let Some(account) = account.as_ref()
            && account.is_yggdrasil()
        {
            self.invalidate_best_effort(account).await;
        }

        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.remove(&request.id);
        if store.accounts().is_empty() {
            let fallback = self.config()?.game.username;
            let name = if validate_name(&fallback).is_ok() {
                fallback
            } else {
                "Steve".to_string()
            };
            store.add_offline(&name)?;
        }
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({ "ok": true, "selectedId": selected }))
    }

    async fn accounts_refresh(&self, params: &Value) -> Result<Value> {
        let request: OptionalIdRequest = from_value(params)?;
        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            match request.id.as_deref() {
                Some(id) => store.account(id).cloned(),
                None => store.selected().cloned(),
            }
        }
        .ok_or_else(|| {
            Error::new(
                ErrorKind::NotFound,
                if request.id.is_some() {
                    "error.auth.account_not_found"
                } else {
                    "error.auth.no_account"
                },
            )
        })?;

        let refreshed = account.ensure_valid(&self.yggdrasil_client()?).await?;
        let mut store = self.accounts.lock().expect("accounts poisoned");
        store.update_account(refreshed.clone())?;
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        Ok(json!({
            "account": AccountRow::from_account(&refreshed, &selected),
            "selectedId": selected,
        }))
    }

    async fn accounts_yggdrasil_discover(&self, params: &Value) -> Result<Value> {
        let request: ServerRequest = from_value(params)?;
        let server = self.yggdrasil_client()?.discover(&request.server).await?;
        Ok(json!({
            "server": server.api_root(),
            "serverName": server.server_name(),
            "implementationName": server.implementation_name(),
            "implementationVersion": server.implementation_version(),
        }))
    }

    async fn accounts_yggdrasil_login(&self, params: &Value) -> Result<Value> {
        let request: YggdrasilLoginRequest = from_value(params)?;
        let client = self.yggdrasil_client()?;
        let server = client.discover(&request.server).await?;
        let client_token = random_token();
        let response = client
            .authenticate(
                &server,
                request.username.trim(),
                &request.password,
                &client_token,
            )
            .await?;

        if let Some(profile) = response.selected_profile.clone() {
            let mut store = self.accounts.lock().expect("accounts poisoned");
            let account = store
                .upsert_yggdrasil(&server, &response, &profile)?
                .clone();
            store.save()?;
            let selected = store.selected_id().unwrap_or_default().to_string();
            return Ok(json!({
                "account": AccountRow::from_account(&account, &selected),
                "selectedId": selected,
            }));
        }

        if response.available_profiles.is_empty() {
            return Err(Error::new(ErrorKind::Auth, "error.auth.no_profiles"));
        }

        let pending_id = random_token();
        {
            let mut pending = self.pending_logins.lock().expect("pending logins poisoned");
            pending.retain(|_, value| value.created.elapsed() < PENDING_LOGIN_TTL);
            pending.insert(
                pending_id.clone(),
                PendingLogin {
                    server: server.clone(),
                    response: response.clone(),
                    created: Instant::now(),
                },
            );
        }
        Ok(json!({
            "selectionRequired": true,
            "pendingId": pending_id,
            "server": server.api_root(),
            "serverName": server.server_name(),
            "profiles": response.available_profiles,
        }))
    }

    async fn accounts_yggdrasil_select_profile(&self, params: &Value) -> Result<Value> {
        let request: ProfileSelectRequest = from_value(params)?;
        let pending = {
            let pending = self.pending_logins.lock().expect("pending logins poisoned");
            pending
                .get(&request.pending_id)
                .filter(|value| value.created.elapsed() < PENDING_LOGIN_TTL)
                .cloned()
        }
        .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.selection_expired"))?;

        let wanted = request.profile_id.replace('-', "");
        let profile = pending
            .response
            .available_profiles
            .iter()
            .find(|profile| profile.id == request.profile_id || profile.id_hex() == wanted)
            .cloned()
            .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.profile_not_found"))?;

        let response = self
            .yggdrasil_client()?
            .refresh(
                &pending.server,
                &pending.response.access_token,
                &pending.response.client_token,
                Some(&profile),
            )
            .await?;

        let mut store = self.accounts.lock().expect("accounts poisoned");
        let account = store
            .upsert_yggdrasil(&pending.server, &response, &profile)?
            .clone();
        store.save()?;
        let selected = store.selected_id().unwrap_or_default().to_string();
        drop(store);
        self.pending_logins
            .lock()
            .expect("pending logins poisoned")
            .remove(&request.pending_id);
        Ok(json!({
            "account": AccountRow::from_account(&account, &selected),
            "selectedId": selected,
        }))
    }

    /// Best-effort revocation when the user deletes a third-party account.
    async fn invalidate_best_effort(&self, account: &Account) {
        let (Some(server), Some(access_token), Some(client_token)) = (
            account.server.as_deref(),
            account.access_token(),
            account.client_token(),
        ) else {
            return;
        };
        let server = YggdrasilServer::new(
            server,
            account.server_name.clone().unwrap_or_default(),
            None,
            None,
        );
        match self.yggdrasil_client() {
            Ok(client) => {
                if let Err(error) = client.invalidate(&server, access_token, client_token).await {
                    tracing::warn!(
                        target: "fg_ffi",
                        server = server.api_root(),
                        detail = %error,
                        "could not invalidate the removed account token"
                    );
                }
            }
            Err(error) => {
                tracing::warn!(
                    target: "fg_ffi",
                    detail = %error,
                    "could not build a client to invalidate the removed account token"
                );
            }
        }
    }

    fn http_client(&self) -> Result<fg_net::Client> {
        let config = self.config()?;
        fg_net::Client::builder()
            .from_config(&config.network)?
            .build()
    }

    fn yggdrasil_client(&self) -> Result<YggdrasilClient> {
        Ok(YggdrasilClient::new(self.http_client()?))
    }

    fn injector_source(&self) -> Result<InjectorSource> {
        let config = self.config()?;
        Ok(InjectorSource::new(
            self.http_client()?,
            self.table.clone(),
            config.download.source,
            self.region,
        ))
    }

    /// Resolves the launch account, refreshing Yggdrasil tokens and preparing
    /// authlib-injector before the command is built.
    async fn resolve_auth_session(
        &self,
        account_id: Option<&str>,
        username: Option<&str>,
    ) -> Result<AuthSession> {
        if let Some(username) = username.map(str::trim).filter(|name| !name.is_empty()) {
            return Ok(AuthSession::offline(&OfflineAccount::new(username)?));
        }

        let account = {
            let store = self.accounts.lock().expect("accounts poisoned");
            match account_id {
                Some(id) => store.account(id).cloned(),
                None => store.selected().cloned(),
            }
        };
        let Some(account) = account else {
            return Err(Error::new(
                ErrorKind::NotFound,
                if account_id.is_some() {
                    "error.auth.account_not_found"
                } else {
                    "error.auth.no_account"
                },
            ));
        };

        match account.kind {
            AccountKind::Offline => account.session(None),
            AccountKind::Yggdrasil => {
                let refreshed = account.ensure_valid(&self.yggdrasil_client()?).await?;
                {
                    let mut store = self.accounts.lock().expect("accounts poisoned");
                    store.update_account(refreshed.clone())?;
                    store.save()?;
                }
                let server = refreshed
                    .server
                    .clone()
                    .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.login_required"))?;
                let jar = self
                    .injector_source()?
                    .ensure(&self.root, &self.downloader)
                    .await?;
                refreshed.session(Some(AuthlibInjector::new(jar, server)))
            }
            AccountKind::Microsoft => Err(Error::new(
                ErrorKind::Unsupported,
                "error.auth.microsoft_pending",
            )),
        }
    }

    async fn build_launch_plan(
        self: &Arc<Self>,
        instance_id: &str,
        account_id: Option<&str>,
        username: Option<&str>,
    ) -> Result<LaunchPlan> {
        let manager = self.manager();
        let instance = manager.load(instance_id)?;
        let globals = self.config()?.game;
        let merged = Instance {
            overrides: fg_game::manager::overrides_for_launch(&instance, &globals),
            ..instance
        };
        let version_id = merged.version_id().to_string();
        let resolved = self
            .resolver()?
            .resolve_local(&version_id)
            .map_err(|error| {
                if error.kind == ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.launch.not_installed",
                        format!("{version_id} is not installed"),
                    )
                } else {
                    error
                }
            })?;

        // An explicit instance/global Java path always wins; only the automatic
        // path downloads a runtime. This is what makes the Java picker in the
        // instance settings sheet effective.
        let java = if let Some(path) = merged.java() {
            explicit_java(path)?
        } else {
            let legacy_x64 = cfg!(target_os = "macos")
                && JavaArch::host() == JavaArch::Arm64
                && fg_game::launch::needs_x86_64_java(&resolved.base_id);
            self.ensure_java(
                resolved.java_major,
                resolved.java_component(),
                if legacy_x64 {
                    JavaArch::X86_64
                } else {
                    JavaArch::host()
                },
                legacy_x64,
            )
            .await?
            .runtime
        };
        let session = self.resolve_auth_session(account_id, username).await?;
        LaunchPlan::build(
            &self.layout,
            &merged,
            &resolved,
            &java,
            &session,
            &LaunchOptions::default(),
        )
    }
}

/// Session watcher: forwards log lines, honours the stop request and records
/// play seconds once the game exits.
async fn run_session(
    state: SessionState,
    process: GameProcess,
    events: Arc<EventQueue>,
    layout: Layout,
) {
    let mut logs = process.subscribe();
    let log_events = events.clone();
    let instance_id = state.instance_id.clone();
    let log_task = tokio::spawn(async move {
        loop {
            match logs.recv().await {
                Ok(GameEvent::Line { text, .. }) => {
                    log_events.push_game_line(&instance_id, &text);
                }
                Ok(GameEvent::WindowReady) => {
                    log_events.push_game_window_ready(&instance_id);
                }
                Ok(_) => {}
                Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                    tracing::warn!(target: "fg_ffi", "dropped {n} game log lines");
                }
                Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
            }
        }
    });

    // `wait_with_kill` owns the child handle, so a stop request is delivered as
    // a future instead of racing a log receiver. Without this waiter a clean
    // exit or a requested stop never produced `game.exited`, leaving
    // `play_seconds` stale and the UI's stop button stuck.
    let stop = state.stop.clone();
    let exit = process
        .wait_with_kill(async move { stop.notified().await })
        .await;
    let _ = log_task.await;

    match exit {
        Ok(game_exit) => {
            finish_session(&state, game_exit.code, events, layout).await;
        }
        Err(error) => {
            tracing::warn!(
                target: "fg_ffi",
                "session watcher failed for {}: {error}",
                state.instance_id
            );
            finish_session(&state, None, events, layout).await;
        }
    }
}

async fn finish_session(
    state: &SessionState,
    code: Option<i32>,
    events: Arc<EventQueue>,
    layout: Layout,
) {
    let seconds = state.started.elapsed().as_secs();
    if let Ok(mut instance) = Instance::load(&layout, &state.instance_id) {
        instance.record_session(seconds);
        let _ = instance.save(&layout);
    }
    events.push_game_exited(&state.instance_id, code, seconds);
    *state.finished.lock().expect("exit slot poisoned") = Some(json!({
        "running": false,
        "finished": {
            "instanceId": state.instance_id,
            "exitCode": code,
            "playSeconds": seconds,
        },
    }));
}

/// Keeps only the file stem/extension of a remote file name.
fn sanitize_download_name(name: &str) -> Option<String> {
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

struct TaskSink {
    cx: TaskCx,
}

impl fg_net::ProgressSink for TaskSink {
    fn on_bytes(&self, bytes: u64) {
        self.cx.advance(bytes);
    }
}

/// Derives a safe instance id for a modpack import, avoiding collisions.
fn modpack_instance_id(manager: &InstanceManager, name: &str) -> String {
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

fn count_mods(game_dir: &Path) -> Option<usize> {
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

async fn discover_java(layout: &Layout) -> Vec<fg_java::JavaRuntime> {
    let scanner = fg_java::JavaScanner::new().with_extra_root(layout.runtimes());
    scanner.scan_probing(Duration::from_secs(2)).await
}

fn arch_matches(actual: JavaArch, wanted: JavaArch) -> bool {
    actual == wanted || actual == JavaArch::Unknown
}

/// Resolves an explicit Java home/executable from `[override] java`.
fn explicit_java(path: &Path) -> Result<JavaRuntime> {
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

fn java_count(layout: &Layout) -> usize {
    fg_java::JavaScanner::new()
        .with_extra_root(layout.runtimes())
        .scan()
        .len()
}

fn non_empty(value: Option<&str>) -> Option<String> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty() && *value != "auto")
        .map(str::to_string)
}

fn inherit_text(value: &Value) -> SettingValue {
    match value.as_str() {
        Some(text) if !text.trim().is_empty() => SettingValue::Text(text.to_string()),
        _ => SettingValue::Text(String::new()),
    }
}

fn resolution_of(value: &Value) -> [u32; 2] {
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
const APRIL_FOOLS_IDS: &[&str] = &[
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

fn is_april_fools(id: &str, version_type: &str, release_time: Option<&str>) -> bool {
    if APRIL_FOOLS_IDS.contains(&id) {
        return true;
    }
    // A snapshot published on April 1 is an April Fools snapshot. Keep release
    // versions out of this rule: 26.1.1 shipped on 2026-04-01 and is a normal
    // release, not a joke version.
    version_type == "snapshot"
        && release_time.is_some_and(|value| value.get(5..10) == Some("04-01"))
}

fn kind_matches(request: &str, id: &str, version_type: &str, release_time: Option<&str>) -> bool {
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
fn game_json(game: &GameConfig) -> Value {
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

fn as_string(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_string()
}

fn as_u32(value: &Value) -> u32 {
    value.as_u64().unwrap_or(0) as u32
}

fn as_bool(value: &Value) -> bool {
    value.as_bool().unwrap_or(false)
}

fn from_value<T: serde::de::DeserializeOwned>(params: &Value) -> Result<T> {
    serde_json::from_value(params.clone()).map_err(|error| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.rpc.params",
            error.to_string(),
        )
    })
}

fn to_value<T: Serialize>(payload: T) -> Result<Value> {
    serde_json::to_value(payload).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.rpc.serialize",
            error.to_string(),
        )
    })
}

fn requested_loader(request: &VersionRequest) -> Result<Option<(LoaderKind, String)>> {
    let Some(kind) = non_empty(Some(request.loader_kind.as_str())) else {
        return Ok(None);
    };
    Ok(Some((
        LoaderKind::parse(&kind)?,
        request.loader_version.clone().unwrap_or_default(),
    )))
}

fn resolve_loader_selection(
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

fn parse_content_source(value: &str) -> Result<ContentSource> {
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
fn content_plan_summary(plan: &fg_content::ContentPlan) -> Value {
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

fn content_context(
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

impl Service {
    fn optional_instance(&self, id: Option<&str>) -> Result<Option<Instance>> {
        match id.map(str::trim).filter(|value| !value.is_empty()) {
            Some(id) => self.manager().load(id).map(Some),
            None => Ok(None),
        }
    }
}

// ---- Request payloads -------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstanceOnlyRequest {
    instance_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentToggleRequest {
    instance_id: String,
    kind: String,
    file_name: String,
    enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentDeleteRequest {
    instance_id: String,
    kind: String,
    file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentSearchRequest {
    #[serde(default)]
    source: String,
    kind: String,
    #[serde(default)]
    query: String,
    #[serde(default)]
    instance_id: Option<String>,
    #[serde(default)]
    game_version: Option<String>,
    #[serde(default)]
    loader: Option<String>,
    #[serde(default)]
    offset: u32,
    #[serde(default = "default_content_limit")]
    limit: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentVersionsRequest {
    #[serde(default)]
    source: String,
    project_id: String,
    kind: String,
    #[serde(default)]
    instance_id: Option<String>,
    #[serde(default)]
    game_version: Option<String>,
    #[serde(default)]
    loader: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentInstallRequest {
    #[serde(default)]
    source: String,
    project_id: String,
    #[serde(default)]
    version_id: Option<String>,
    instance_id: String,
    kind: String,
    /// Target world for data packs.
    #[serde(default)]
    world: Option<String>,
    #[serde(default)]
    replace_files: Vec<String>,
    /// Defaults to true so older clients keep auto-installing prerequisites.
    #[serde(default = "default_true")]
    install_dependencies: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ContentUpdatesRequest {
    instance_id: String,
    #[serde(default)]
    kind: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldNameRequest {
    instance_id: String,
    name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldRestoreRequest {
    instance_id: String,
    backup: String,
    name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WorldBackupRequest {
    instance_id: String,
    backup: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DatapackRequest {
    instance_id: String,
    world: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DatapackToggleRequest {
    instance_id: String,
    world: String,
    file_name: String,
    enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DatapackDeleteRequest {
    instance_id: String,
    world: String,
    file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DatapackImportRequest {
    instance_id: String,
    world: String,
    path: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModpackInspectRequest {
    path: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModpackInstallRequest {
    #[serde(default)]
    source: Option<String>,
    project_id: String,
    version_id: String,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModpackImportRequest {
    path: String,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModpackExportRequest {
    instance_id: String,
}

fn default_content_limit() -> u32 {
    20
}

const fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstanceCreateRequest {
    id: String,
    #[serde(default)]
    name: String,
    minecraft: String,
    #[serde(default)]
    loader_kind: String,
    #[serde(default)]
    loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstanceImportRequest {
    directory: String,
    #[serde(default)]
    name: Option<String>,
    #[serde(default)]
    minecraft: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdRequest {
    id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdNameRequest {
    id: String,
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct IdFlagRequest {
    id: String,
    #[serde(default)]
    favorite: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SettingsSetRequest {
    id: String,
    #[serde(default)]
    updates: Vec<SettingUpdate>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingUpdate {
    setting: String,
    #[serde(default)]
    value: Value,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct VersionsListRequest {
    #[serde(default)]
    kind: String,
    #[serde(default = "default_limit")]
    limit: usize,
    #[serde(default)]
    refresh: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NewsRequest {
    #[serde(default)]
    refresh: bool,
    #[serde(default = "default_limit")]
    limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct VersionRequest {
    version: String,
    #[serde(default)]
    loader_kind: String,
    #[serde(default)]
    loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LoaderVersionsRequest {
    kind: String,
    minecraft: String,
    #[serde(default = "default_limit")]
    limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallStartRequest {
    version: String,
    #[serde(default)]
    instance_id: Option<String>,
    #[serde(default)]
    instance_name: Option<String>,
    #[serde(default)]
    loader_kind: String,
    #[serde(default)]
    loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TaskIdRequest {
    task_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct JavaInstallRequest {
    pub major: u32,
    #[serde(default)]
    pub component: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct JavaRemoveRequest {
    pub id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LaunchStartRequest {
    instance_id: String,
    #[serde(default)]
    account_id: Option<String>,
    /// Legacy one-shot offline override. The account panel sends `accountId`
    /// instead.
    #[serde(default)]
    username: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AccountNameRequest {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OptionalIdRequest {
    #[serde(default)]
    id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ServerRequest {
    server: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct YggdrasilLoginRequest {
    server: String,
    username: String,
    password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProfileSelectRequest {
    pending_id: String,
    profile_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SessionStopRequest {
    #[serde(default)]
    instance_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct GameLogRequest {
    instance_id: String,
    #[serde(default)]
    limit: usize,
}

fn default_limit() -> usize {
    100
}

// ---- host info --------------------------------------------------------

#[cfg(target_os = "macos")]
fn disk_free_bytes(root: &Path) -> u64 {
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
fn disk_free_bytes(root: &Path) -> u64 {
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
fn disk_free_bytes(_root: &Path) -> u64 {
    0
}

#[cfg(target_os = "macos")]
fn total_memory_bytes() -> u64 {
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
fn total_memory_bytes() -> u64 {
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
fn total_memory_bytes() -> u64 {
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn fixture() -> (TempDir, Arc<Service>) {
        let directory = TempDir::new().expect("tempdir");
        let service = Service::new(directory.path().to_path_buf()).expect("service");
        (directory, service)
    }

    #[test]
    fn app_snapshot_reports_counts() {
        let (_dir, service) = fixture();
        service.block_on(async {
            let snapshot = service
                .rpc("app.snapshot", json!({}))
                .await
                .expect("snapshot");
            assert_eq!(
                snapshot["platform"],
                json!(fg_core::Platform::detect().id())
            );
            assert_eq!(snapshot["instanceCount"], json!(0));
            assert!(snapshot["diskFreeBytes"].is_u64());
            assert!(snapshot["totalMemoryBytes"].is_u64());
        });
    }

    #[test]
    fn instances_list_round_trip() {
        let (_dir, service) = fixture();
        service.block_on(async {
            let created = service
                .rpc(
                    "instances.create",
                    json!({ "id": "w1", "name": "雾林", "minecraft": "1.21.4", "loaderKind": "vanilla" }),
                )
                .await
                .expect("create");
            assert_eq!(created["id"], json!("w1"));

            let rows = service.rpc("instances.list", json!({})).await.expect("rows");
            assert_eq!(rows.as_array().expect("array").len(), 1);
            assert_eq!(rows[0]["name"], json!("雾林"));
            assert_eq!(rows[0]["installed"], json!(false));

            let summary = service.rpc("instances.summary", json!({})).await.expect("summary");
            assert_eq!(summary["recent"].as_array().expect("recent").len(), 1);
        });
    }

    #[test]
    fn content_context_follows_the_instance_version_and_loader() {
        let (_dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "forge", "name": "Forge", "minecraft": "1.20.1", "loaderKind": "forge" }),
                )
                .await
                .expect("create");
        });
        let instance = service.manager().load("forge").expect("instance");

        // A Forge instance searches Forge mods for its own Minecraft version.
        assert_eq!(
            content_context(Some(&instance), ContentKind::Mod, None, None),
            (Some("1.20.1".to_string()), Some("forge".to_string()))
        );
        // Resource packs are version-scoped but loader-agnostic.
        assert_eq!(
            content_context(Some(&instance), ContentKind::ResourcePack, None, None),
            (Some("1.20.1".to_string()), None)
        );
        // Explicit callers (the modpack tab) may override the instance context.
        assert_eq!(
            content_context(
                Some(&instance),
                ContentKind::Modpack,
                Some("1.21.4"),
                Some("fabric"),
            ),
            (Some("1.21.4".to_string()), Some("fabric".to_string()))
        );
    }

    #[test]
    fn duplicate_create_is_conflict() {
        let (_dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "dup", "name": "a", "minecraft": "1.21.4" }),
                )
                .await
                .expect("first");
            let error = service
                .rpc(
                    "instances.create",
                    json!({ "id": "dup", "name": "b", "minecraft": "1.21.4" }),
                )
                .await
                .expect_err("conflict");
            assert_eq!(error.message_key, "error.instance.duplicate");
        });
    }

    #[test]
    fn rename_favorite_delete() {
        let (_dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "inst", "name": "a", "minecraft": "1.21.4" }),
                )
                .await
                .expect("create");

            service
                .rpc(
                    "instances.rename",
                    json!({ "id": "inst", "name": "新名字" }),
                )
                .await
                .expect("rename");
            service
                .rpc(
                    "instances.favorite",
                    json!({ "id": "inst", "favorite": true }),
                )
                .await
                .expect("favorite");

            let rows = service
                .rpc("instances.list", json!({}))
                .await
                .expect("rows");
            assert_eq!(rows[0]["name"], json!("新名字"));
            assert_eq!(rows[0]["favorite"], json!(true));

            service
                .rpc("instances.delete", json!({ "id": "inst" }))
                .await
                .expect("delete");
            let rows = service
                .rpc("instances.list", json!({}))
                .await
                .expect("rows");
            assert!(rows.as_array().expect("array").is_empty());
        });
    }

    #[test]
    fn game_config_round_trip() {
        let (_dir, service) = fixture();
        service.block_on(async {
            let updated = service
                .rpc(
                    "gameConfig.set",
                    json!({ "username": "Alex", "memoryMb": 8192, "showLog": true }),
                )
                .await
                .expect("set");
            assert_eq!(updated["username"], json!("Alex"));
            assert_eq!(updated["memoryMb"], json!(8192));

            let read = service.rpc("gameConfig.get", json!({})).await.expect("get");
            assert_eq!(read["username"], json!("Alex"));
            assert_eq!(read["memoryMb"], json!(8192));
            assert_eq!(read["showLog"], json!(true));
        });
    }

    #[test]
    fn settings_get_reports_inheritance() {
        let (_dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "inst", "name": "a", "minecraft": "1.21.4" }),
                )
                .await
                .expect("create");

            let settings = service
                .rpc("instances.settings.get", json!({ "id": "inst" }))
                .await
                .expect("settings");
            assert_eq!(settings["effective"]["memoryMb"], json!(0));
            assert_eq!(settings["globals"]["username"], json!("Steve"));

            service
                .rpc(
                    "instances.settings.set",
                    json!({ "id": "inst", "updates": [ { "setting": "memory", "value": 6144 } ] }),
                )
                .await
                .expect("set memory");

            let settings = service
                .rpc("instances.settings.get", json!({ "id": "inst" }))
                .await
                .expect("settings");
            assert_eq!(settings["overrides"]["memoryMb"], json!(6144));
        });
    }

    #[test]
    fn import_registers_external_directory() {
        let (_dir, service) = fixture();
        service.block_on(async {
            let external = _dir.path().join("MyMinecraft");
            std::fs::create_dir_all(&external).expect("dir");
            std::fs::write(
                external.join("launcher_profiles.json"),
                r#"{"selectedProfile":{"lastVersionId":"1.20.1"}}"#,
            )
            .expect("profiles");

            let imported = service
                .rpc(
                    "instances.import",
                    json!({ "directory": external.display().to_string(), "name": "外部" }),
                )
                .await
                .expect("import");
            assert_eq!(imported["minecraft"], json!("1.20.1"));

            let rows = service
                .rpc("instances.list", json!({}))
                .await
                .expect("rows");
            assert_eq!(rows[0]["minecraft"], json!("1.20.1"));
        });
    }

    #[test]
    fn loader_selection_is_validated_and_inherited() {
        let request = InstallStartRequest {
            version: "1.21.4".into(),
            instance_id: None,
            instance_name: None,
            loader_kind: "bogus".into(),
            loader_version: None,
        };
        let error = resolve_loader_selection(&request, None).expect_err("bad kind");
        assert_eq!(error.message_key, "error.loader.unknown_kind");

        let request = InstallStartRequest {
            version: "1.21.4".into(),
            instance_id: None,
            instance_name: None,
            loader_kind: String::new(),
            loader_version: None,
        };
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        layout.ensure_base_dirs().expect("dirs");
        let mut instance =
            Instance::create(&layout, "loader-selection", "1.21.4").expect("instance");
        instance.set_loader("fabric", "0.16.9", "fabric-loader-0.16.9-1.21.4");
        let selection = resolve_loader_selection(&request, Some(&instance))
            .expect("selection")
            .expect("loader");
        assert_eq!(selection.0, LoaderKind::Fabric);
        assert_eq!(selection.1, "0.16.9");
    }

    #[test]
    fn java_list_merges_managed_markers_and_policy() {
        let (_dir, service) = fixture();
        let layout = service.layout().clone();
        install_fake_managed_runtime(&layout, "mojang-java-runtime-delta-21.0.7-x86_64", 21);

        service.block_on(async {
            let listed = service
                .rpc("java.list", json!({}))
                .await
                .expect("java.list");
            let runtimes = listed["runtimes"].as_array().expect("runtimes");
            let managed = runtimes
                .iter()
                .find(|row| row["id"] == json!("mojang-java-runtime-delta-21.0.7-x86_64"))
                .expect("managed runtime");
            assert_eq!(managed["managed"], json!(true));
            assert_eq!(managed["major"], json!(21));
            assert_eq!(managed["vendor"], json!("mojang"));
            assert_eq!(managed["source"], json!("Fograin runtimes"));
            assert_eq!(listed["autoDownload"], json!(true));

            let snapshot = service
                .rpc("app.snapshot", json!({}))
                .await
                .expect("snapshot");
            assert!(
                snapshot["javaCount"].as_u64().expect("count") >= 1,
                "{snapshot}"
            );

            let updated = service
                .rpc("java.policy.set", json!({ "autoDownload": false }))
                .await
                .expect("policy");
            assert_eq!(updated["autoDownload"], json!(false));
            let listed = service
                .rpc("java.list", json!({}))
                .await
                .expect("java.list");
            assert_eq!(listed["autoDownload"], json!(false));
        });

        let text =
            std::fs::read_to_string(fg_core::config::config_path(layout.root())).expect("config");
        assert!(text.contains("[java]"), "{text}");
        assert!(text.contains("auto_download = false"), "{text}");

        let removed = service
            .block_on(service.rpc(
                "java.remove",
                json!({
                    "id": "mojang-java-runtime-delta-21.0.7-x86_64"
                }),
            ))
            .expect("remove");
        assert_eq!(
            removed["id"],
            json!("mojang-java-runtime-delta-21.0.7-x86_64")
        );
        assert!(
            !layout
                .runtimes()
                .join("mojang-java-runtime-delta-21.0.7-x86_64")
                .exists()
        );
    }

    #[test]
    fn explicit_java_path_is_resolved_from_home_or_executable() {
        let directory = tempfile::tempdir().expect("tempdir");
        let home = directory.path().join("jdk-21");
        std::fs::create_dir_all(home.join("bin")).expect("bin");
        let executable = home
            .join("bin")
            .join(if cfg!(windows) { "java.exe" } else { "java" });
        std::fs::write(&executable, b"java").expect("java");
        std::fs::write(home.join("release"), "JAVA_VERSION=\"21.0.9\"\n").expect("release");

        let from_home = explicit_java(&home).expect("home");
        assert_eq!(from_home.major(), Some(21));
        let from_binary = explicit_java(&executable).expect("binary");
        assert_eq!(from_binary.home, home);

        let error = explicit_java(&directory.path().join("missing")).expect_err("missing");
        assert_eq!(error.message_key, "error.java.not_found");
    }

    #[test]
    fn java_install_rejects_a_zero_major_without_network() {
        let (_dir, service) = fixture();
        let error = service
            .block_on(service.rpc("java.install", json!({ "major": 0 })))
            .expect_err("zero major");
        assert_eq!(error.message_key, "error.java.runtime_major");
    }

    fn install_fake_managed_runtime(layout: &Layout, id: &str, major: u32) {
        let directory = layout.runtimes().join(id);
        std::fs::create_dir_all(directory.join("bin")).expect("runtime bin");
        let executable =
            directory
                .join("bin")
                .join(if cfg!(windows) { "java.exe" } else { "java" });
        std::fs::write(&executable, b"fake java").expect("java");
        std::fs::write(
            directory.join("release"),
            format!("JAVA_VERSION=\"{major}.0.7\"\nOS_ARCH=\"x86_64\"\n"),
        )
        .expect("release");
        std::fs::write(
            directory.join(fg_java::RUNTIME_MARKER),
            serde_json::to_vec(&json!({
                "schemaVersion": 1,
                "id": id,
                "vendor": "mojang",
                "component": "java-runtime-delta",
                "version": format!("{major}.0.7"),
                "major": major,
                "arch": "x86_64",
                "platform": "linux",
                "fileCount": 1,
                "totalBytes": 9,
                "installedAtMs": 1,
            }))
            .expect("marker"),
        )
        .expect("write marker");
    }

    #[test]
    fn unknown_method_is_invalid_input() {
        let (_dir, service) = fixture();
        service.block_on(async {
            let error = service
                .rpc("nope.nothing", json!({}))
                .await
                .expect_err("unknown");
            assert_eq!(error.message_key, "error.rpc.unknown_method");
        });
    }

    #[test]
    fn install_start_creates_the_instance_and_completes_a_local_install() {
        let (_dir, service) = fixture();
        let layout = service.layout().clone();
        // Minimal local version: resolve_remote prefers installed JSON, and the
        // client jar is already present, so the graph only finalizes metadata.
        let version_id = "test-version";
        let jar_bytes = b"test";
        let sha1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
        std::fs::create_dir_all(layout.version_dir(version_id)).expect("version dir");
        std::fs::write(layout.version_jar(version_id), jar_bytes).expect("jar");
        std::fs::write(
            layout.version_json(version_id),
            format!(
                r#"{{"id":"{version_id}","type":"release","mainClass":"TestMain","downloads":{{"client":{{"url":"https://example.invalid/client.jar","sha1":"{sha1}","size":{}}}}}}}"#,
                jar_bytes.len()
            ),
        )
        .expect("version json");

        let started = service.block_on(async {
            service
                .rpc(
                    "install.start",
                    json!({
                        "version": version_id,
                        "instanceId": "inst-local",
                        "instanceName": "本地实例",
                    }),
                )
                .await
                .expect("install started")
        });
        assert!(started["taskId"].is_string(), "{started}");

        // The instance row exists before the graph finishes so the UI can open
        // its settings page from the finished event.
        assert!(service.manager().exists("inst-local"));

        let mut succeeded = false;
        for _ in 0..80 {
            let rows = service.block_on(service.tasks_list()).expect("rows");
            if rows
                .as_array()
                .expect("array")
                .iter()
                .any(|row| row["state"] == json!("succeeded"))
            {
                succeeded = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(25));
        }
        assert!(succeeded, "install task did not finish");
        assert!(layout.install_meta(version_id).is_file());
        let rows = service
            .block_on(service.instance_rows())
            .expect("rows after install");
        assert!(
            rows[0].installed,
            "installed flag must require the meta marker"
        );

        // Pressing play on an existing incomplete instance reuses the row and
        // queues the missing resources instead of failing as a duplicate.
        let restarted = service.block_on(async {
            service
                .rpc(
                    "install.start",
                    json!({
                        "version": version_id,
                        "instanceId": "inst-local",
                        "instanceName": "本地实例",
                    }),
                )
                .await
                .expect("existing instance install")
        });
        assert!(restarted["taskId"].is_string());
        let rows = service
            .block_on(service.instance_rows())
            .expect("rows after restart");
        assert_eq!(rows.len(), 1);
    }

    #[test]
    fn version_channels_separate_april_fools() {
        assert!(kind_matches(
            "release",
            "26.1.1",
            "release",
            Some("2026-04-01T09:06:36+00:00")
        ));
        assert!(!kind_matches(
            "april_fools",
            "26.1.1",
            "release",
            Some("2026-04-01T09:06:36+00:00")
        ));
        assert!(kind_matches(
            "april_fools",
            "1.RV-Pre1",
            "snapshot",
            Some("2016-03-31T16:18:53+00:00")
        ));
        assert!(!kind_matches(
            "snapshot",
            "1.RV-Pre1",
            "snapshot",
            Some("2016-03-31T16:18:53+00:00")
        ));
        assert!(kind_matches(
            "april_fools",
            "26w14a",
            "snapshot",
            Some("2026-04-01T12:13:30+00:00")
        ));
        assert!(!kind_matches(
            "snapshot",
            "26w14a",
            "snapshot",
            Some("2026-04-01T12:13:30+00:00")
        ));
        assert!(kind_matches(
            "old",
            "a1.2.0",
            "old_alpha",
            Some("2010-10-29T22:00:00+00:00")
        ));
        assert!(kind_matches(
            "april_fools",
            "2.0",
            "old_alpha",
            Some("2013-04-01T00:00:00+00:00")
        ));
        assert!(!kind_matches(
            "old",
            "2.0",
            "old_alpha",
            Some("2013-04-01T00:00:00+00:00")
        ));
    }

    #[test]
    fn datapacks_are_managed_per_world() {
        let (dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "packs", "name": "数据包", "minecraft": "1.21.4" }),
                )
                .await
                .expect("create");
            let game_dir = dir.path().join("instances/packs/.minecraft");
            let saves = game_dir.join("saves/World/datapacks");
            std::fs::create_dir_all(&saves).expect("saves");
            fg_world::datapack::write_pack(&saves.join("terralith.zip"), 48, "Terralith")
                .expect("pack");

            let listed = service
                .rpc(
                    "datapacks.list",
                    json!({ "instanceId": "packs", "world": "World" }),
                )
                .await
                .expect("list");
            assert_eq!(listed["count"], json!(1));
            assert_eq!(listed["items"][0]["title"], json!("Terralith"));

            let toggled = service
                .rpc(
                    "datapacks.toggle",
                    json!({
                        "instanceId": "packs",
                        "world": "World",
                        "fileName": "terralith.zip",
                        "enabled": false
                    }),
                )
                .await
                .expect("toggle");
            assert_eq!(toggled["fileName"], json!("terralith.zip.disabled"));

            let deleted = service
                .rpc(
                    "datapacks.delete",
                    json!({
                        "instanceId": "packs",
                        "world": "World",
                        "fileName": "terralith.zip.disabled"
                    }),
                )
                .await
                .expect("delete");
            assert!(deleted["items"].as_array().expect("array").is_empty());
        });
    }

    #[test]
    fn diagnostics_find_a_memory_crash_and_export_a_bundle() {
        let (dir, service) = fixture();
        service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "diag", "name": "诊断", "minecraft": "1.21.4" }),
                )
                .await
                .expect("create");
            let game_dir = dir.path().join("instances/diag/.minecraft");
            std::fs::create_dir_all(game_dir.join("logs")).expect("logs");
            std::fs::write(
                game_dir.join("logs/latest.log"),
                "[main/ERROR]: java.lang.OutOfMemoryError: Java heap space\n",
            )
            .expect("log");

            let analysis = service
                .rpc("diag.analyze", json!({ "instanceId": "diag" }))
                .await
                .expect("analyze");
            let findings = analysis["findings"].as_array().expect("findings");
            assert!(
                findings
                    .iter()
                    .any(|finding| finding["id"] == json!("java.memory"))
            );
            assert_eq!(analysis["severity"], json!("error"));
            assert!(analysis["scannedLines"].as_u64().unwrap_or_default() >= 1);

            let logs = service
                .rpc("diag.logs", json!({ "instanceId": "diag" }))
                .await
                .expect("logs");
            assert!(
                logs["texts"]["log"]
                    .as_str()
                    .unwrap_or_default()
                    .contains("OutOfMemory")
            );

            let exported = service
                .rpc("diag.export", json!({ "instanceId": "diag" }))
                .await
                .expect("export");
            let path = exported["bundle"]["path"].as_str().expect("path");
            assert!(std::path::Path::new(path).is_file());
            assert!(exported["bundle"]["sizeBytes"].as_u64().unwrap_or_default() > 0);
        });
    }

    #[test]
    fn task_queue_pauses_and_cancels_and_rejects_unsupported_resume() {
        let (_dir, service) = fixture();
        let mut builder = fg_task::TaskGraph::builder("slow");
        builder.task("slow", 1, |cx| async move {
            cx.cancel_handle().cancelled().await;
            Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"))
        });
        let graph = builder.build();
        let control = Control::new();
        let task_id = service.queue_task("test", control, graph, None, None);

        let state = |service: &Arc<Service>, task_id: &str| {
            service
                .block_on(service.tasks_list())
                .expect("rows")
                .as_array()
                .expect("array")
                .iter()
                .find(|row| row["id"] == json!(task_id))
                .map(|row| row["state"].as_str().unwrap_or_default().to_string())
                .unwrap_or_default()
        };

        for _ in 0..40 {
            if state(&service, &task_id) == "running" {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(state(&service, &task_id), "running");

        service
            .block_on(service.rpc("tasks.pause", json!({ "taskId": task_id })))
            .expect("pause");
        for _ in 0..40 {
            if state(&service, &task_id) == "paused" {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(state(&service, &task_id), "paused");
        // Wait for `run_task` to exit so the service (and its runtime) is not
        // dropped from one of its own worker threads at the end of the test.
        assert!(service.block_on(service.wait_task(&task_id)).is_err());

        let error = service
            .block_on(service.rpc("tasks.resume", json!({ "taskId": task_id })))
            .expect_err("a graph without replay data cannot resume");
        assert_eq!(error.message_key, "error.task.resume_unsupported");

        service
            .block_on(service.rpc("tasks.cancel", json!({ "taskId": task_id })))
            .expect("cancel");
        assert_eq!(state(&service, &task_id), "cancelled");
    }

    #[test]
    fn task_queue_resume_replays_the_original_rpc() {
        let (_dir, service) = fixture();
        let layout = service.layout().clone();
        let version_id = "resume-version";
        let jar_bytes = b"test";
        std::fs::create_dir_all(layout.version_dir(version_id)).expect("version dir");
        std::fs::write(layout.version_jar(version_id), jar_bytes).expect("jar");
        std::fs::write(
            layout.version_json(version_id),
            format!(
                r#"{{"id":"{version_id}","type":"release","mainClass":"TestMain","downloads":{{"client":{{"url":"https://example.invalid/client.jar","sha1":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3","size":{}}}}}}}"#,
                jar_bytes.len()
            ),
        )
        .expect("version json");

        let mut builder = fg_task::TaskGraph::builder("slow");
        builder.task("slow", 1, |cx| async move {
            cx.cancel_handle().cancelled().await;
            Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"))
        });
        let graph = builder.build();
        let control = Control::new();
        let replay = Some(TaskReplay {
            method: "install.start",
            params: json!({
                "version": version_id,
                "instanceId": "resume-instance",
                "instanceName": "恢复下载",
            }),
        });
        let task_id = service.queue_task("slow", control, graph, None, replay);
        std::thread::sleep(Duration::from_millis(30));
        service
            .block_on(service.rpc("tasks.pause", json!({ "taskId": task_id })))
            .expect("pause");
        assert!(service.block_on(service.wait_task(&task_id)).is_err());

        let resumed = service
            .block_on(service.rpc("tasks.resume", json!({ "taskId": task_id })))
            .expect("resume");
        let new_task = resumed["taskId"].as_str().expect("new task").to_string();
        assert_ne!(new_task, task_id);
        assert_eq!(resumed["oldTaskId"], json!(task_id));
        service
            .block_on(service.wait_task(&new_task))
            .expect("replayed install should finish");
        let rows = service.block_on(service.tasks_list()).expect("rows");
        assert!(
            rows.as_array()
                .expect("array")
                .iter()
                .all(|row| row["id"] != json!(task_id)),
            "paused row should be replaced by its replay"
        );
        assert!(service.manager().exists("resume-instance"));
    }

    #[test]
    fn task_rows_expose_running_graphs() {
        let (_dir, service) = fixture();
        let mut builder = fg_task::TaskGraph::builder("t");
        builder.task("noop", 1, |_cx| async { Ok(()) });
        let graph = builder.build();
        let control = Control::new();
        service.tasks.lock().expect("queue").push(QueuedTask {
            id: "task-1".into(),
            name: "test".into(),
            control: control.clone(),
            graph: Arc::new(graph),
            runtime_id: None,
            replay: None,
            done: Arc::new(TaskDone::default()),
        });
        let rows = service.block_on(service.tasks_list()).expect("rows");
        let list = rows.as_array().expect("array");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0]["id"], json!("task-1"));
    }
}
