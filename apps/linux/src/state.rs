//! Live launcher state shared by every page (the Linux counterpart of
//! macOS's `AppModel`).
//!
//! It owns the RPC bridge, keeps the decoded instance/task/version data, and
//! translates the core's progress and session events into UI behaviour: toasts,
//! blocking errors, navigation and the hero start/stop control. Pages subscribe to
//! [`AppState::subscribe`] and redraw from the copied accessors; only this
//! module talks to the core.

use std::{
    cell::{Cell, RefCell},
    path::PathBuf,
    rc::Rc,
};

use serde_json::{Value, json};

use crate::core::{Core, CoreError, CoreResult, Event};
use crate::events::{EventSink, ToastLevel, UiEvent};
use crate::models::{
    AppSnapshot, DownloadChannel, DownloadRow, GameConfigView, Instance, PlanPreview, SessionState,
    VersionEntry, format_play_time, format_speed, task_title, unique_instance_id,
};
use crate::platform::system::SystemInfo;

/// Everything the downloads page renders.
#[derive(Clone, Debug)]
pub struct DownloadPageState {
    pub versions: Vec<VersionEntry>,
    pub loading: bool,
    pub versions_error: Option<String>,
    pub plan: Option<PlanPreview>,
    pub plan_error: Option<String>,
    pub selected: Option<String>,
    pub channel: DownloadChannel,
    pub install_running: bool,
}

/// Decoded state shared by the pages.
pub struct AppData {
    pub instances: Vec<Instance>,
    pub current_instance_id: String,
    pub downloads: Vec<DownloadRow>,
    pub snapshot: Option<AppSnapshot>,
    pub game_config: GameConfigView,
    pub session: SessionState,
    pub initial_loading: bool,
    pub versions: Vec<VersionEntry>,
    pub loading_versions: bool,
    pub versions_error: Option<String>,
    pub versions_channel: Option<DownloadChannel>,
    pub selected_version: Option<String>,
    pub plan: Option<PlanPreview>,
    pub plan_error: Option<String>,
    pub channel: DownloadChannel,
    pub game_install_running: bool,
}

impl Default for AppData {
    fn default() -> Self {
        Self {
            instances: Vec::new(),
            current_instance_id: String::new(),
            downloads: Vec::new(),
            snapshot: None,
            game_config: GameConfigView::default(),
            session: SessionState::default(),
            initial_loading: true,
            versions: Vec::new(),
            loading_versions: false,
            versions_error: None,
            versions_channel: None,
            selected_version: None,
            plan: None,
            plan_error: None,
            channel: DownloadChannel::Release,
            game_install_running: false,
        }
    }
}

pub struct AppState {
    core: Rc<Core>,
    sink: Rc<EventSink>,
    data: RefCell<AppData>,
    observers: RefCell<Vec<Rc<dyn Fn()>>>,
    pending_install_instance: RefCell<Option<String>>,
    pending_install_task: RefCell<Option<String>>,
    pending_install_version: RefCell<Option<String>>,
    stop_requested: Cell<bool>,
    download_generation: Cell<u64>,
}

impl AppState {
    #[must_use]
    pub fn new(core: Rc<Core>, sink: Rc<EventSink>) -> Rc<Self> {
        Rc::new(Self {
            core,
            sink,
            data: RefCell::new(AppData::default()),
            observers: RefCell::new(Vec::new()),
            pending_install_instance: RefCell::new(None),
            pending_install_task: RefCell::new(None),
            pending_install_version: RefCell::new(None),
            stop_requested: Cell::new(false),
            download_generation: Cell::new(0),
        })
    }

    // MARK: - Accessors

    #[must_use]
    pub fn instances(&self) -> Vec<Instance> {
        self.data.borrow().instances.clone()
    }

    #[must_use]
    pub fn instance(&self, id: &str) -> Option<Instance> {
        self.data
            .borrow()
            .instances
            .iter()
            .find(|instance| instance.id == id)
            .cloned()
    }

    #[must_use]
    pub fn current_instance(&self) -> Option<Instance> {
        let data = self.data.borrow();
        data.instances
            .iter()
            .find(|instance| instance.id == data.current_instance_id)
            .or_else(|| data.instances.first())
            .cloned()
    }

    #[must_use]
    pub fn current_instance_id(&self) -> String {
        self.current_instance()
            .map(|instance| instance.id)
            .unwrap_or_default()
    }

    #[must_use]
    pub fn session(&self) -> SessionState {
        self.data.borrow().session.clone()
    }

    #[must_use]
    pub fn app_snapshot(&self) -> Option<AppSnapshot> {
        self.data.borrow().snapshot.clone()
    }

    #[must_use]
    pub fn game_config(&self) -> GameConfigView {
        self.data.borrow().game_config.clone()
    }

    #[must_use]
    pub fn active_downloads(&self) -> Vec<DownloadRow> {
        self.data
            .borrow()
            .downloads
            .iter()
            .filter(|row| row.is_active())
            .cloned()
            .collect()
    }

    #[must_use]
    pub fn active_download_count(&self) -> usize {
        self.data
            .borrow()
            .downloads
            .iter()
            .filter(|row| row.is_active())
            .count()
    }

    #[must_use]
    pub fn downloads_speed_text(&self) -> String {
        let active = self.active_downloads();
        let total: f64 = active.iter().map(|row| row.speed).sum();
        if total > 0.0 {
            format_speed(total)
        } else if active.is_empty() {
            "空闲".to_owned()
        } else {
            "—".to_owned()
        }
    }

    #[must_use]
    pub fn download_page(&self) -> DownloadPageState {
        let data = self.data.borrow();
        DownloadPageState {
            versions: data.versions.clone(),
            loading: data.loading_versions,
            versions_error: data.versions_error.clone(),
            plan: data.plan.clone(),
            plan_error: data.plan_error.clone(),
            selected: data.selected_version.clone(),
            channel: data.channel,
            install_running: data.game_install_running,
        }
    }

    #[must_use]
    pub fn is_initial_loading(&self) -> bool {
        self.data.borrow().initial_loading
    }

    #[must_use]
    pub fn java_summary(&self) -> String {
        match self.app_snapshot() {
            None => "扫描中…".to_owned(),
            Some(snapshot) if snapshot.java_count == 0 => "未发现 Java".to_owned(),
            Some(snapshot) => format!("已发现 {} 个运行时", snapshot.java_count),
        }
    }

    #[must_use]
    pub fn memory_summary(&self) -> String {
        let memory_mb = self.game_config().memory_mb;
        let physical = SystemInfo::current().memory_text();
        if memory_mb == 0 {
            format!("自动 · 物理内存 {physical}")
        } else {
            format!("{} GB / {physical}", (memory_mb / 1024).max(1))
        }
    }

    /// True when this instance's files are being installed right now.
    #[must_use]
    pub fn is_downloading(&self, instance: &Instance) -> bool {
        if self.pending_install_instance.borrow().as_deref() == Some(instance.id.as_str()) {
            return true;
        }
        self.active_downloads()
            .iter()
            .any(|row| row.title.contains(&instance.minecraft))
    }

    // MARK: - Subscription

    /// Registers a listener invoked after every state change. Pages capture a
    /// `Weak` to themselves so the state does not keep the widget tree alive.
    pub fn subscribe(&self, observer: impl Fn() + 'static) {
        self.observers.borrow_mut().push(Rc::new(observer));
    }

    fn notify(&self) {
        let observers: Vec<Rc<dyn Fn()>> = self.observers.borrow().clone();
        for observer in observers {
            observer();
        }
    }

    fn update(&self, apply: impl FnOnce(&mut AppData)) {
        {
            let mut data = self.data.borrow_mut();
            apply(&mut data);
        }
        self.notify();
    }

    // MARK: - Core refresh

    /// Reloads instances, settings and tasks. This is the macOS
    /// `refreshCoreData` equivalent.
    pub fn refresh_core_data(self: &Rc<Self>) {
        let state = Rc::clone(self);
        self.reload_instances(move || {
            state.update(|data| data.initial_loading = false);
            state.refresh_snapshot();
            state.refresh_game_config();
            state.refresh_downloads();
        });
    }

    fn reload_instances(self: &Rc<Self>, after: impl FnOnce() + 'static) {
        let state = Rc::clone(self);
        self.core.rpc("instances.list", json!({}), move |result| {
            match result {
                Ok(value) => {
                    let instances = Instance::list_from_value(&value);
                    let mut current = state.data.borrow().current_instance_id.clone();
                    if current.is_empty() || !instances.iter().any(|row| row.id == current) {
                        current = instances
                            .first()
                            .map(|row| row.id.clone())
                            .unwrap_or_default();
                    }
                    state.update(|data| {
                        data.instances = instances;
                        data.current_instance_id = current;
                        data.initial_loading = false;
                    });
                }
                Err(error) => {
                    state.update(|data| data.initial_loading = false);
                    state.show_error("无法读取实例列表", &error);
                }
            }
            after();
        });
    }

    fn refresh_snapshot(self: &Rc<Self>) {
        let state = Rc::clone(self);
        self.core.rpc("app.snapshot", json!({}), move |result| {
            if let Ok(value) = result
                && let Some(snapshot) = AppSnapshot::from_value(&value)
            {
                state.update(|data| data.snapshot = Some(snapshot));
            }
        });
    }

    fn refresh_game_config(self: &Rc<Self>) {
        let state = Rc::clone(self);
        self.core.rpc("gameConfig.get", json!({}), move |result| {
            if let Ok(value) = result
                && let Some(config) = GameConfigView::from_value(&value)
            {
                state.update(|data| data.game_config = config);
            }
        });
    }

    fn refresh_downloads(self: &Rc<Self>) {
        let state = Rc::clone(self);
        self.core.rpc("tasks.list", json!({}), move |result| {
            if let Ok(value) = result {
                let rows = DownloadRow::list_from_value(&value);
                state.update(|data| data.downloads = rows);
            }
        });
    }

    // MARK: - Navigation and selection

    pub fn set_current_instance(&self, id: &str) {
        if self.data.borrow().current_instance_id == id {
            return;
        }
        self.update(|data| data.current_instance_id = id.to_owned());
    }

    /// Double-click / management entry: show this instance in the library.
    pub fn open_instance(&self, id: &str) {
        self.set_current_instance(id);
        self.sink.emit(UiEvent::OpenInstance(id.to_owned()));
    }

    pub fn show_instance_settings(&self, id: &str) {
        self.sink.emit(UiEvent::ShowInstanceSettings(id.to_owned()));
    }

    // MARK: - Session

    pub fn start_game(self: &Rc<Self>, instance: &Instance) {
        self.set_current_instance(&instance.id);

        if !instance.installed {
            if self.is_downloading(instance) {
                self.show_notice(
                    format!("“{}”的资源仍在下载，请稍候再启动", instance.name),
                    ToastLevel::Info,
                );
            } else {
                self.complete_resources(instance);
            }
            return;
        }

        if self.session().running {
            self.show_error_message("无法启动游戏", "已经有一个游戏在运行了。", None);
            return;
        }
        if self.session().loading {
            return;
        }

        let state = Rc::clone(self);
        let id = instance.id.clone();
        let name = instance.name.clone();
        let username = self.game_config().username;
        self.update(|data| data.session.loading = true);
        self.core.rpc(
            "launch.start",
            json!({ "instanceId": id, "username": username }),
            move |result| match result {
                Ok(_) => {
                    // Keep the spinner until `game.started` arrives; the event
                    // handler clears `loading`.
                }
                Err(error) => {
                    state.update(|data| data.session.loading = false);
                    state.show_error(&format!("无法启动“{name}”"), &error);
                }
            },
        );
    }

    pub fn stop_game(self: &Rc<Self>) {
        if !self.session().running {
            self.show_notice("没有正在运行的游戏", ToastLevel::Info);
            return;
        }
        self.stop_requested.set(true);
        let state = Rc::clone(self);
        self.core
            .rpc("session.stop", json!({}), move |result| match result {
                Ok(value) => {
                    if value.get("stopped").and_then(Value::as_bool) == Some(false) {
                        state.stop_requested.set(false);
                        state.show_notice("没有正在运行的游戏", ToastLevel::Info);
                    }
                }
                Err(error) => {
                    state.stop_requested.set(false);
                    state.show_error("无法停止游戏", &error);
                }
            });
    }

    /// PCL-style: pressing play on an incomplete instance starts its resource
    /// download and jumps to the queue instead of dead-ending.
    pub fn complete_resources(self: &Rc<Self>, instance: &Instance) {
        if self.data.borrow().game_install_running {
            self.show_notice("已有下载任务在进行", ToastLevel::Info);
            return;
        }
        let state = Rc::clone(self);
        let instance_id = instance.id.clone();
        let instance_name = instance.name.clone();
        let version = instance.minecraft.clone();
        // Remember the target before the RPC returns: a tiny install graph can
        // finish before the response is pumped, and the finished event must
        // still be recognised as ours.
        *self.pending_install_instance.borrow_mut() = Some(instance_id.clone());
        *self.pending_install_version.borrow_mut() = Some(version.clone());
        self.core.rpc(
            "install.start",
            json!({
                "version": version,
                "instanceId": instance_id,
                "instanceName": instance_name,
            }),
            move |result| match result {
                Ok(value) => {
                    if state.pending_install_instance.borrow().as_deref()
                        != Some(instance_id.as_str())
                    {
                        // `finish_game_download` already consumed this install.
                        return;
                    }
                    *state.pending_install_task.borrow_mut() = value
                        .get("taskId")
                        .and_then(Value::as_str)
                        .map(str::to_owned);
                    state.update(|data| data.game_install_running = true);
                    state
                        .sink
                        .emit(UiEvent::Navigate(crate::navigation::Section::Downloads));
                    state.show_notice(
                        format!("正在补全“{instance_name}”的游戏资源"),
                        ToastLevel::Info,
                    );
                    state.refresh_core_data();
                }
                Err(error) => {
                    if state.pending_install_instance.borrow().as_deref()
                        == Some(instance_id.as_str())
                    {
                        *state.pending_install_instance.borrow_mut() = None;
                        *state.pending_install_version.borrow_mut() = None;
                    }
                    state.show_error(&format!("无法补全“{instance_name}”的资源"), &error);
                }
            },
        );
    }

    // MARK: - Install flow

    /// Starts a fresh install from the install dialog. `done` reports the
    /// accepted command (the download itself continues in the queue).
    pub fn start_game_install(
        self: &Rc<Self>,
        version: &str,
        instance_name: &str,
        done: impl FnOnce(Result<(), String>) + 'static,
    ) {
        if self.data.borrow().game_install_running {
            done(Err("已有下载任务在进行，请等待完成或取消。".to_owned()));
            return;
        }

        let name = instance_name.trim();
        let id = unique_instance_id(name, version, &self.instances());
        let display_name = if name.is_empty() {
            id.clone()
        } else {
            name.to_owned()
        };
        let state = Rc::clone(self);
        let version = version.to_owned();
        let id_for_callback = id.clone();
        let version_for_callback = version.clone();
        *self.pending_install_instance.borrow_mut() = Some(id.clone());
        *self.pending_install_version.borrow_mut() = Some(version.clone());
        self.core.rpc(
            "install.start",
            json!({
                "version": version,
                "instanceId": id,
                "instanceName": display_name,
            }),
            move |result| match result {
                Ok(value) => {
                    if state.pending_install_instance.borrow().as_deref()
                        != Some(id_for_callback.as_str())
                    {
                        // The finished event beat the response; the install is
                        // already being consumed by `finish_game_download`.
                        done(Ok(()));
                        return;
                    }
                    *state.pending_install_task.borrow_mut() = value
                        .get("taskId")
                        .and_then(Value::as_str)
                        .map(str::to_owned);
                    state.update(|data| data.game_install_running = true);
                    state
                        .sink
                        .emit(UiEvent::Navigate(crate::navigation::Section::Downloads));
                    state.show_notice(
                        format!("已开始安装 {version_for_callback}"),
                        ToastLevel::Info,
                    );
                    state.refresh_core_data();
                    done(Ok(()));
                }
                Err(error) => {
                    if state.pending_install_instance.borrow().as_deref()
                        == Some(id_for_callback.as_str())
                    {
                        *state.pending_install_instance.borrow_mut() = None;
                        *state.pending_install_version.borrow_mut() = None;
                    }
                    done(Err(error.user_message()));
                }
            },
        );
    }

    /// After the files land, walk the user to 游戏设置 of the freshly created
    /// instance (PCL-style: download → 设置 → 启动).
    fn finish_game_download(self: &Rc<Self>) {
        let instance_id = self.pending_install_instance.borrow_mut().take();
        self.pending_install_task.borrow_mut().take();
        self.pending_install_version.borrow_mut().take();
        self.update(|data| data.game_install_running = false);

        let Some(instance_id) = instance_id else {
            self.refresh_core_data();
            return;
        };
        let state = Rc::clone(self);
        self.reload_instances(move || {
            state.update(|data| data.current_instance_id = instance_id.clone());
            state.sink.emit(UiEvent::OpenInstance(instance_id.clone()));
            state.sink.emit(UiEvent::ShowInstanceSettings(instance_id));
            state.refresh_snapshot();
            state.refresh_downloads();
        });
    }

    // MARK: - Instance actions

    pub fn configure_instance(self: &Rc<Self>, id: &str, setting: &str, value: Value) {
        let state = Rc::clone(self);
        self.core.rpc(
            "instances.settings.set",
            json!({
                "id": id,
                "updates": [{ "setting": setting, "value": value }],
            }),
            move |result| match result {
                Ok(_) => state.refresh_core_data(),
                Err(error) => state.show_error("设置保存失败", &error),
            },
        );
    }

    /// Loads effective settings and `[override]` values for the dialog.
    pub fn instance_settings(
        self: &Rc<Self>,
        id: &str,
        callback: impl FnOnce(CoreResult) + 'static,
    ) {
        self.core
            .rpc("instances.settings.get", json!({ "id": id }), callback);
    }

    pub fn toggle_favorite(self: &Rc<Self>, instance: &Instance) {
        let state = Rc::clone(self);
        self.core.rpc(
            "instances.favorite",
            json!({ "id": instance.id, "favorite": !instance.favorite }),
            move |result| match result {
                Ok(_) => state.refresh_core_data(),
                Err(error) => state.show_error("无法更新收藏", &error),
            },
        );
    }

    pub fn delete_instance(self: &Rc<Self>, instance: &Instance) {
        let state = Rc::clone(self);
        let id = instance.id.clone();
        let name = instance.name.clone();
        self.core.rpc(
            "instances.delete",
            json!({ "id": id }),
            move |result| match result {
                Ok(_) => {
                    state.update(|data| {
                        if data.current_instance_id == id {
                            data.current_instance_id.clear();
                        }
                    });
                    state.show_notice(format!("已删除实例“{name}”"), ToastLevel::Success);
                    state.refresh_core_data();
                }
                Err(error) => state.show_error(&format!("无法删除“{name}”"), &error),
            },
        );
    }

    /// Registers an external `.minecraft` directory without moving files.
    pub fn import_directory(self: &Rc<Self>, directory: PathBuf) {
        let state = Rc::clone(self);
        let label = directory.file_name().map_or_else(
            || directory.display().to_string(),
            |name| name.to_string_lossy().into_owned(),
        );
        let path = directory.display().to_string();
        self.core.rpc(
            "instances.import",
            json!({ "directory": path }),
            move |result| match result {
                Ok(value) => {
                    let name = value
                        .get("name")
                        .and_then(Value::as_str)
                        .unwrap_or(&label)
                        .to_owned();
                    state.show_notice(format!("已导入“{name}”"), ToastLevel::Success);
                    state.refresh_core_data();
                }
                Err(error) => state.show_error("无法导入 .minecraft 目录", &error),
            },
        );
    }

    pub fn open_instance_folder(self: &Rc<Self>, instance: &Instance) {
        let state = Rc::clone(self);
        let id = instance.id.clone();
        self.core.rpc(
            "instances.dir",
            json!({ "id": id }),
            move |result| match result {
                Ok(value) => {
                    let Some(directory) = value.get("gameDir").and_then(Value::as_str) else {
                        state.show_error_message(
                            "无法打开实例目录",
                            "启动器核心没有返回实例目录。",
                            None,
                        );
                        return;
                    };
                    if let Err(error) =
                        crate::platform::files::open_directory(std::path::Path::new(directory))
                    {
                        state.show_error_message("无法打开实例目录", &error.to_string(), None);
                    }
                }
                Err(error) => state.show_error("无法打开实例目录", &error),
            },
        );
    }

    // MARK: - Downloads page

    /// Loads the manifest for the selected channel once, or refreshes the plan.
    pub fn load_download_versions(self: &Rc<Self>, force: bool) {
        let data = self.data.borrow();
        let channel = data.channel;
        let loaded = data.versions_channel == Some(channel);
        drop(data);

        if !force && loaded {
            self.refresh_plan();
            return;
        }

        let generation = self.download_generation.get().wrapping_add(1);
        self.download_generation.set(generation);
        self.update(|data| {
            data.versions.clear();
            data.versions_channel = None;
            data.selected_version = None;
            data.plan = None;
            data.plan_error = None;
            data.versions_error = None;
            data.loading_versions = true;
        });

        let state = Rc::clone(self);
        self.core.rpc(
            "versions.list",
            json!({
                "kind": channel.kind_value(),
                "limit": 500,
                "refresh": false,
            }),
            move |result| {
                if state.download_generation.get() != generation
                    || state.data.borrow().channel != channel
                {
                    return;
                }
                match result {
                    Ok(value) => {
                        let versions = VersionEntry::list_from_value(&value);
                        let selected = versions.first().map(|entry| entry.id.clone());
                        state.update(|data| {
                            data.loading_versions = false;
                            data.versions_channel = Some(channel);
                            data.versions = versions;
                            data.selected_version = selected;
                        });
                        state.refresh_plan();
                    }
                    Err(error) => {
                        state.update(|data| {
                            data.loading_versions = false;
                            data.versions_error = Some(error.user_message());
                        });
                        state.show_error("无法读取版本清单", &error);
                    }
                }
            },
        );
    }

    pub fn set_download_channel(self: &Rc<Self>, channel: DownloadChannel) {
        if self.data.borrow().channel == channel {
            return;
        }
        self.update(|data| data.channel = channel);
        self.load_download_versions(true);
    }

    pub fn select_download_version(self: &Rc<Self>, id: &str) {
        let changed = self.data.borrow().selected_version.as_deref() != Some(id);
        if !changed {
            return;
        }
        self.update(|data| {
            data.selected_version = Some(id.to_owned());
            data.plan = None;
            data.plan_error = None;
        });
        self.refresh_plan();
    }

    fn refresh_plan(self: &Rc<Self>) {
        let Some(version) = self.data.borrow().selected_version.clone() else {
            self.update(|data| {
                data.plan = None;
                data.plan_error = None;
            });
            return;
        };
        let state = Rc::clone(self);
        self.core.rpc(
            "version.plan",
            json!({ "version": version }),
            move |result| {
                if state.data.borrow().selected_version.as_deref() != Some(version.as_str()) {
                    return;
                }
                match result {
                    Ok(value) => {
                        let plan = PlanPreview::from_value(&value);
                        state.update(|data| {
                            data.plan = plan;
                            data.plan_error = None;
                        });
                    }
                    Err(error) => {
                        state.update(|data| {
                            data.plan = None;
                            data.plan_error = Some(error.user_message());
                        });
                    }
                }
            },
        );
    }

    // MARK: - Event pump

    /// Called by the shell timer: dispatch completed RPCs and drain events.
    pub fn tick(self: &Rc<Self>) {
        self.core.pump();
        let events = self.core.events();
        if events.is_empty() {
            return;
        }

        for event in events {
            match event {
                Event::TaskProgress {
                    task_id,
                    fraction,
                    speed,
                    ..
                } => self.apply_progress(&task_id, fraction, speed),
                other => self.handle_event(other),
            }
        }
    }

    fn apply_progress(&self, task_id: &str, fraction: f64, speed: f64) {
        let mut changed = false;
        {
            let mut data = self.data.borrow_mut();
            if let Some(row) = data.downloads.iter_mut().find(|row| row.id == task_id) {
                // A final progress tick can arrive after `task.finished`; keep
                // the terminal state so the sidebar badge and queue do not flip
                // back to "running" forever.
                row.update_progress(fraction, speed);
                changed = true;
            }
        }
        if changed {
            self.notify();
        }
    }

    fn handle_event(self: &Rc<Self>, event: Event) {
        match event {
            Event::TaskProgress { .. } => {}
            Event::TaskFinished {
                task_id,
                name,
                state,
                detail,
            } => {
                let title = task_title(&name);
                let is_ours =
                    self.pending_install_task.borrow().as_deref() == Some(task_id.as_str());
                match state.as_str() {
                    "succeeded" => {
                        self.show_notice(format!("{title} 完成"), ToastLevel::Success);
                        if is_ours {
                            self.finish_game_download();
                        } else {
                            self.refresh_core_data();
                        }
                    }
                    "failed" => {
                        let message = detail
                            .first()
                            .cloned()
                            .unwrap_or_else(|| "未知原因".to_owned());
                        self.show_error_message(
                            &format!("{title} 失败"),
                            &message,
                            (!detail.is_empty()).then(|| detail[1..].join("\n")),
                        );
                        if is_ours {
                            *self.pending_install_instance.borrow_mut() = None;
                            *self.pending_install_task.borrow_mut() = None;
                            *self.pending_install_version.borrow_mut() = None;
                            self.update(|data| data.game_install_running = false);
                        }
                        self.refresh_core_data();
                    }
                    "cancelled" => {
                        self.show_notice(format!("{title} 已取消"), ToastLevel::Info);
                        if is_ours {
                            *self.pending_install_instance.borrow_mut() = None;
                            *self.pending_install_task.borrow_mut() = None;
                            *self.pending_install_version.borrow_mut() = None;
                            self.update(|data| data.game_install_running = false);
                        }
                        self.refresh_downloads();
                    }
                    _ => {}
                }
            }
            Event::GameStarted { instance_id, pid } => {
                self.stop_requested.set(false);
                self.update(|data| {
                    data.session = SessionState {
                        running: true,
                        loading: false,
                        pid,
                        instance_id: Some(instance_id),
                    };
                });
                self.show_notice(
                    format!("游戏已启动，进程 {}", pid.unwrap_or(0)),
                    ToastLevel::Success,
                );
            }
            Event::GameWindowReady { .. } => {
                self.show_notice("游戏窗口已就绪", ToastLevel::Success);
            }
            Event::GameLine { .. } => {}
            Event::GameExited {
                exit_code,
                play_seconds,
                ..
            } => {
                let was_stopped = self.stop_requested.replace(false);
                self.update(|data| {
                    data.session = SessionState::default();
                });
                if was_stopped {
                    self.show_notice("游戏已停止", ToastLevel::Info);
                } else if exit_code == Some(0) {
                    self.show_notice(
                        format!("游戏已退出（{}）", format_play_time(play_seconds / 60)),
                        ToastLevel::Success,
                    );
                } else {
                    self.show_error_message(
                        "游戏异常退出",
                        &format!(
                            "游戏进程以代码 {} 结束。",
                            exit_code.map_or_else(|| "signal".to_owned(), |code| code.to_string())
                        ),
                        Some(
                            "请查看实例目录 logs/ 下的最新日志；日志与崩溃报告页将在 M9 接入。"
                                .to_owned(),
                        ),
                    );
                }
                self.refresh_core_data();
            }
        }
    }

    // MARK: - Notices

    pub fn show_notice(&self, message: impl Into<String>, level: ToastLevel) {
        self.sink.emit(UiEvent::Toast {
            message: message.into(),
            level,
        });
    }

    pub fn show_error(&self, title: &str, error: &CoreError) {
        self.sink.emit(UiEvent::Alert {
            title: title.to_owned(),
            message: error.user_message(),
            detail: error.detail.clone(),
        });
    }

    pub fn show_error_message(&self, title: &str, message: &str, detail: Option<String>) {
        self.sink.emit(UiEvent::Alert {
            title: title.to_owned(),
            message: message.to_owned(),
            detail,
        });
    }
}
