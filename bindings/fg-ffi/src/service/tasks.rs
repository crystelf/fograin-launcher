use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    /// Pushes throttled progress events and a terminal `task.finished`.
    pub(super) async fn watch_task(
        self: Arc<Self>,
        task_id: String,
        control: Control,
        name: String,
    ) {
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

    pub(super) fn queue_task(
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
    pub(super) async fn run_task(self: Arc<Self>, task_id: String, done: Arc<TaskDone>) {
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
    pub(super) async fn wait_task(&self, task_id: &str) -> Result<()> {
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
    pub(super) fn running_java_task(&self, name: &str) -> Option<(String, Option<String>)> {
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

    pub(super) fn tasks_list_len(&self) -> usize {
        self.tasks.lock().expect("task queue poisoned").len()
    }

    pub(super) async fn tasks_list(&self) -> Result<Value> {
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

    pub(super) async fn tasks_pause(&self, params: &Value) -> Result<Value> {
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
    pub(super) async fn tasks_resume(self: &Arc<Self>, params: &Value) -> Result<Value> {
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

    pub(super) async fn tasks_cancel(&self, params: &Value) -> Result<Value> {
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
}
