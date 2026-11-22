//! The launcher service: one process-wide object that owns instances, tasks,
//! downloads, accounts and the running game session.
//!
//! The implementation is split by domain; every file adds an `impl Service`
//! block and shares the state declared here. [`Service::rpc`] is the only
//! entry point the FFI layer calls, so its method names are part of the ABI.
//! Async launcher service behind the C ABI (§13).
//!
//! The service owns one Tokio runtime, the launcher data root, the download
//! engine and the task queue. Native clients call it through short JSON RPC
//! functions ([`crate::fg_rpc_call`]) and observe progress through the event
//! queue ([`crate::fg_events_next`]). Every payload serializes with camelCase
//! keys so the Swift, Kotlin and C# clients can share one decoding helper.
mod accounts;
mod content;
mod datapacks;
mod diagnostics;
mod helpers;
mod instances;
mod java;
mod launch;
mod modpacks;
mod params;
mod settings;
mod tasks;
#[cfg(test)]
mod tests;
mod versions;
mod worlds;

use self::helpers::*;

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
use fg_net::{
    DownloadOptions, DownloadRequest, Downloader, MirrorTable, Priority, Region, Url as NetUrl,
};
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
    login_name: String,
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
    /// Login email/username for third-party accounts; `None` for offline.
    pub email: Option<String>,
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
            email: account.email.clone(),
            selected: account.id == selected_id,
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingUpdate {
    setting: String,
    #[serde(default)]
    value: Value,
}

impl Service {
    /// Builds the engine, task queue and instance manager for `root`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the layout cannot be created or the
    /// Tokio runtime cannot start.
    pub fn new(root: PathBuf) -> Result<Arc<Self>> {
        crate::logging::init(&root);
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

    /// Dispatches one RPC method. Kotlin binds to these names through
    /// `CoreClient`, so method strings are part of the ABI.
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

            "settings.get" => self.settings_value(),
            "settings.set" => self.settings_set(&params).await,
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
            "modpack.export.plan" => self.modpack_export_plan(&params).await,
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

    fn optional_instance(&self, id: Option<&str>) -> Result<Option<Instance>> {
        match id.map(str::trim).filter(|value| !value.is_empty()) {
            Some(id) => self.manager().load(id).map(Some),
            None => Ok(None),
        }
    }
}
