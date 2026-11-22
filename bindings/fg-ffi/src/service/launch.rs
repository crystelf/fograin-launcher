use super::helpers::*;
use super::params::*;
use super::*;

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

impl Service {
    pub(super) async fn launch_start(self: &Arc<Self>, params: &Value) -> Result<Value> {
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

    pub(super) async fn launch_status(&self) -> Result<Value> {
        let session = self.session.lock().expect("session poisoned").clone();
        Ok(json!({
            "running": session.is_some(),
            "instanceId": session.as_ref().map(|session| session.instance_id.clone()),
            "pid": session.as_ref().and_then(|session| session.pid),
        }))
    }

    pub(super) async fn session_wait(&self) -> Result<Value> {
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

    pub(super) async fn session_stop(&self, params: &Value) -> Result<Value> {
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

    pub(super) async fn game_log_recent(&self, params: &Value) -> Result<Value> {
        let request: GameLogRequest = from_value(params)?;
        let lines = self
            .events
            .recent_game_lines(&request.instance_id, request.limit.clamp(1, 500));
        Ok(json!({ "lines": lines }))
    }

    pub(super) async fn build_launch_plan(
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
