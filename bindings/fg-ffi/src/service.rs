//! Async launcher service behind the C ABI (§13).
//!
//! The service owns one Tokio runtime, the launcher data root, the download
//! engine and the task queue. Native clients call it through short JSON RPC
//! functions ([`crate::fg_rpc_call`]) and observe progress through the event
//! queue ([`crate::fg_events_next`]). Every payload serializes with camelCase
//! keys so the Swift, Kotlin and C# clients can share one decoding helper.

use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use fg_core::{Error, ErrorKind, GameConfig, LauncherConfig, Result, config};
use fg_game::{
    instance::Instance,
    launch::{GameEvent, GameProcess, LaunchOptions, LaunchPlan},
    layout::Layout,
    manager::{InheritableSetting, InstanceManager, SettingValue},
    rules::RuleContext,
};
use fg_java::JavaArch;
use fg_net::{DownloadOptions, Downloader, MirrorTable, Region};
use fg_task::{Control, NodeState, RunOptions, TaskGraph};

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

/// One queued graph the UI can observe through `tasks.list`.
struct QueuedTask {
    id: String,
    name: String,
    control: Control,
    graph: Arc<TaskGraph>,
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
        let config = self.config()?;
        Ok(fg_game::install::Installer::new(
            self.downloader.clone(),
            self.resolver()?,
            self.layout.clone(),
            self.table.clone(),
            config.download.source,
            self.region,
            RuleContext::current(),
        ))
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
            "version.plan" => self.version_plan(&params).await,
            "versions.news" => self.versions_news(&params).await,
            "install.start" => self.install_start(&params).await,
            "tasks.list" => self.tasks_list().await,
            "tasks.cancel" => self.tasks_cancel(&params).await,

            "launch.start" => self.launch_start(&params).await,
            "launch.status" => self.launch_status().await,
            "session.wait" => self.session_wait().await,
            "session.stop" => self.session_stop(&params).await,
            "game.log.recent" => self.game_log_recent(&params).await,

            "gameConfig.get" => self.game_config_value(),
            "gameConfig.set" => self.game_config_set(&params).await,

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
            "javaCount": java_count(),
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
        let resolved = self
            .resolver()?
            .resolve_local(&instance.minecraft)
            .map_err(|error| {
                if error.kind == ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.launch.not_installed",
                        format!("{} is not installed", instance.minecraft),
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

    async fn version_plan(&self, params: &Value) -> Result<Value> {
        let request: VersionRequest = from_value(params)?;
        let plan = self
            .installer()?
            .plan(&request.version, false, None)
            .await?;
        Ok(json!({
            "versionId": plan.version_id,
            "baseId": plan.base_id,
            "javaMajor": plan.java_major,
            "files": plan.files.len(),
            "totalBytes": plan.total_bytes,
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

        // Validate the version before queueing so the wizard can surface a
        // problem in place instead of through a task failure.
        let resolved = self.resolver()?.resolve_remote(&request.version).await?;
        let installer = self.installer()?;
        let plan = installer
            .plan_resolved(&resolved, request.instance_id.as_deref())
            .await?;
        plan.save(&self.layout)?;
        let graph = installer.graph(&plan);
        drop(plan);

        // Create the instance row before queueing the install: the UI opens
        // its 游戏设置 page from the finished event, and an already-installed
        // version can complete the graph before any detached create task runs.
        // An existing instance with the same Minecraft version is reused, so
        // pressing 开始游戏 on an incomplete instance completes its resources
        // instead of failing with a duplicate-id error.
        if target_instance.is_none()
            && let Some(id) = non_empty(request.instance_id.as_deref())
        {
            let manager = self.manager();
            let version_id = request.version.clone();
            let name = non_empty(request.instance_name.as_deref()).unwrap_or(id.to_string());
            manager.create(&id, &name, &version_id, Some("vanilla"), None)?;
        }

        let task_name = format!("install:{}", request.version);
        let control = Control::new();
        let queue_id = self.queue_task(&task_name, control.clone(), graph);
        self.runtime.spawn(
            self.clone()
                .watch_task(queue_id.clone(), control, task_name),
        );
        Ok(json!({ "taskId": queue_id }))
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

    fn queue_task(self: &Arc<Self>, name: &str, control: Control, graph: TaskGraph) -> String {
        let id = format!(
            "task-{}",
            self.task_counter.fetch_add(1, Ordering::Relaxed) + 1
        );
        self.tasks
            .lock()
            .expect("task queue poisoned")
            .push(QueuedTask {
                id: id.clone(),
                name: name.to_string(),
                control,
                graph: Arc::new(graph),
            });
        self.runtime.spawn(self.clone().run_task(id.clone()));
        id
    }

    /// Runs one graph to completion.
    async fn run_task(self: Arc<Self>, task_id: String) {
        let (control, graph, run_id) = {
            let tasks = self.tasks.lock().expect("task queue poisoned");
            let Some(task) = tasks.iter().find(|task| task.id == task_id) else {
                return;
            };
            (
                task.control.clone(),
                task.graph.clone(),
                task.graph.id().to_string(),
            )
        };
        let _ = run_id;
        let report = graph.run(&control, &RunOptions::default()).await;
        let state = if report.is_success() {
            "succeeded"
        } else if report.interrupted.is_some() {
            "cancelled"
        } else {
            "failed"
        };
        self.events
            .push_task_finished_ex(&task_id, graph.name(), state, &report);
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
                let stopped = task.control.is_stopped();
                TaskRow {
                    id: task.id.clone(),
                    name: task.name.clone(),
                    state: if failed {
                        "failed"
                    } else if stopped {
                        "paused"
                    } else if progress.is_complete() {
                        "succeeded"
                    } else {
                        "running"
                    },
                    fraction: progress.fraction,
                    speed: progress.speed,
                }
            })
            .collect();
        to_value(rows)
    }

    async fn tasks_cancel(&self, params: &Value) -> Result<Value> {
        let request: TaskCancelRequest = from_value(params)?;
        self.tasks
            .lock()
            .expect("task queue poisoned")
            .iter()
            .filter(|task| task.id == request.task_id)
            .for_each(|task| task.control.cancel());
        Ok(json!(null))
    }

    async fn launch_start(&self, params: &Value) -> Result<Value> {
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
            .build_launch_plan(&request.instance_id, request.username.as_deref())
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

    async fn build_launch_plan(
        &self,
        instance_id: &str,
        username: Option<&str>,
    ) -> Result<LaunchPlan> {
        let manager = self.manager();
        let instance = manager.load(instance_id)?;
        let globals = self.config()?.game;
        let merged = Instance {
            overrides: fg_game::manager::overrides_for_launch(&instance, &globals),
            ..instance
        };
        let resolved = self
            .resolver()?
            .resolve_local(&merged.minecraft)
            .map_err(|error| {
                if error.kind == ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.launch.not_installed",
                        format!("{} is not installed", merged.minecraft),
                    )
                } else {
                    error
                }
            })?;

        let runtimes = discover_java(self.layout()).await;
        let selection = fg_java::select(&runtimes, resolved.java_major, JavaArch::host())?;
        let account = fg_auth::offline::OfflineAccount::new(username.unwrap_or(&globals.username))?;
        LaunchPlan::build(
            &self.layout,
            &merged,
            &resolved,
            &selection.runtime,
            &account,
            &LaunchOptions::default(),
        )
    }
}

/// Session watcher: forwards log lines, honours the stop request and records
/// play seconds once the game exits.
async fn run_session(
    state: SessionState,
    mut process: GameProcess,
    events: Arc<EventQueue>,
    layout: Layout,
) {
    let mut logs = process.subscribe();
    let mut timed_out = false;
    loop {
        tokio::select! {
            event = logs.recv() => {
                match event {
                    Ok(GameEvent::Started { .. }) => {}
                    Ok(GameEvent::Line { text, .. }) => {
                        events.push_game_line(&state.instance_id, &text);
                    }
                    Ok(GameEvent::WindowReady) => {
                        events.push_game_window_ready(&state.instance_id);
                    }
                    Ok(GameEvent::Exited { code, .. }) => {
                        finish_session(&state, code, events, layout).await;
                        return;
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!(target: "fg_ffi", "dropped {n} game log lines");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
            _ = state.stop.notified(), if !timed_out => {
                timed_out = true;
                let _ = process.kill();
            }
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

fn java_count() -> usize {
    fg_java::JavaScanner::new().scan().len()
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

// ---- Request payloads -------------------------------------------------

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
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallStartRequest {
    version: String,
    #[serde(default)]
    instance_id: Option<String>,
    #[serde(default)]
    instance_name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TaskCancelRequest {
    task_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LaunchStartRequest {
    instance_id: String,
    #[serde(default)]
    username: Option<String>,
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
    u64::from(value.totalram) * u64::from(value.mem_unit)
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
        });
        let rows = service.block_on(service.tasks_list()).expect("rows");
        let list = rows.as_array().expect("array");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0]["id"], json!("task-1"));
    }
}
