//! Running task queue for the UI: spawn graphs, pause/cancel them and publish
//! throttled progress events (§3.1, §6.3).

use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use serde::{Deserialize, Serialize};
use tokio::sync::broadcast;

use crate::{
    graph::{Control, FailPolicy, RunOptions, StopReason, TaskGraph},
    id::TaskId,
    progress::GraphProgressState,
};

/// Coarse state of a queued task.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum TaskState {
    Queued,
    Running,
    Paused,
    Succeeded,
    Failed,
    Cancelled,
}

/// Events pushed to UI clients (FFI `task.progress` / `task.state`, §3.1).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "type")]
pub enum TaskEvent {
    Started {
        id: String,
        name: String,
    },
    Progress {
        id: String,
        fraction: f64,
        speed: f64,
        message: String,
    },
    Finished {
        id: String,
        name: String,
        state: TaskState,
        failed: Vec<String>,
        interrupted: Option<String>,
    },
}

impl TaskEvent {
    /// Task this event belongs to.
    #[must_use]
    pub fn id(&self) -> &str {
        match self {
            Self::Started { id, .. } | Self::Progress { id, .. } | Self::Finished { id, .. } => id,
        }
    }
}

/// Snapshot of one task for menus and badges.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskSummary {
    pub id: String,
    pub name: String,
    pub state: TaskState,
    pub fraction: f64,
    pub speed: f64,
}

struct TaskHandle {
    name: String,
    control: Control,
    progress: Arc<GraphProgressState>,
    state: Arc<Mutex<TaskState>>,
}

/// Owns running graphs and broadcasts their events.
pub struct TaskManager {
    events: broadcast::Sender<TaskEvent>,
    tasks: Mutex<HashMap<String, TaskHandle>>,
    max_parallel: usize,
}

impl Default for TaskManager {
    fn default() -> Self {
        Self::new()
    }
}

impl TaskManager {
    #[must_use]
    pub fn new() -> Self {
        Self {
            events: broadcast::channel(256).0,
            tasks: Mutex::new(HashMap::new()),
            max_parallel: RunOptions::default().max_parallel,
        }
    }

    /// Caps how many nodes run at once across a single graph.
    #[must_use]
    pub fn with_max_parallel(mut self, max_parallel: usize) -> Self {
        self.max_parallel = max_parallel.max(1);
        self
    }

    /// Subscribes to progress/state events.
    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<TaskEvent> {
        self.events.subscribe()
    }

    /// Starts a graph in the background and returns its id.
    ///
    /// # Panics
    ///
    /// Panics when called outside a Tokio runtime.
    pub fn spawn(&self, graph: Arc<TaskGraph>) -> TaskId {
        let id = graph.id().clone();
        let name = graph.name().to_string();
        let control = Control::new();
        let progress = graph.progress_handle();
        let state = Arc::new(Mutex::new(TaskState::Queued));
        let finished = Arc::new(AtomicBool::new(false));

        self.tasks.lock().expect("task manager poisoned").insert(
            id.to_string(),
            TaskHandle {
                name: name.clone(),
                control: control.clone(),
                progress: progress.clone(),
                state: state.clone(),
            },
        );

        self.spawn_ticker(id.to_string(), control.clone(), progress, finished.clone());
        self.spawn_runner(graph, id.clone(), name, control, state, finished);

        id
    }

    fn spawn_ticker(
        &self,
        id: String,
        control: Control,
        progress: Arc<GraphProgressState>,
        finished: Arc<AtomicBool>,
    ) {
        let events = self.events.clone();

        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_millis(200));
            interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

            loop {
                tokio::select! {
                    _ = interval.tick() => {}
                    () = control.cancelled() => break,
                }

                if finished.load(Ordering::Relaxed) {
                    break;
                }

                let snapshot = progress.snapshot();
                let message = snapshot
                    .nodes
                    .iter()
                    .find(|node| !node.message.is_empty())
                    .map(|node| node.message.clone())
                    .unwrap_or_default();

                let _ = events.send(TaskEvent::Progress {
                    id: id.clone(),
                    fraction: snapshot.fraction,
                    speed: snapshot.speed,
                    message,
                });
            }
        });
    }

    fn spawn_runner(
        &self,
        graph: Arc<TaskGraph>,
        id: TaskId,
        name: String,
        control: Control,
        state: Arc<Mutex<TaskState>>,
        finished: Arc<AtomicBool>,
    ) {
        let events = self.events.clone();
        let options = RunOptions {
            max_parallel: self.max_parallel,
            fail_policy: FailPolicy::FailFast,
        };

        tokio::spawn(async move {
            *state.lock().expect("task state poisoned") = TaskState::Running;
            let _ = events.send(TaskEvent::Started {
                id: id.to_string(),
                name: name.clone(),
            });

            let report = graph.run(&control, &options).await;

            let final_state = if report.interrupted == Some(StopReason::Paused) {
                TaskState::Paused
            } else if report.interrupted == Some(StopReason::Cancelled) {
                TaskState::Cancelled
            } else if report.is_success() {
                TaskState::Succeeded
            } else {
                TaskState::Failed
            };

            *state.lock().expect("task state poisoned") = final_state;
            finished.store(true, Ordering::Relaxed);

            let _ = events.send(TaskEvent::Finished {
                id: id.to_string(),
                name,
                state: final_state,
                failed: report
                    .failed
                    .iter()
                    .map(|(node, error)| format!("{node}: {error}"))
                    .collect(),
                interrupted: report.interrupted.map(|reason| reason.as_str().to_string()),
            });
        });
    }

    /// Pauses a running task; finished work is kept (§6.3).
    pub fn pause(&self, id: &str) -> bool {
        let tasks = self.tasks.lock().expect("task manager poisoned");
        let Some(handle) = tasks.get(id) else {
            return false;
        };
        if matches!(
            *handle.state.lock().expect("task state poisoned"),
            TaskState::Succeeded | TaskState::Failed | TaskState::Cancelled
        ) {
            return false;
        }
        handle.control.pause();
        *handle.state.lock().expect("task state poisoned") = TaskState::Paused;
        true
    }

    /// Cancels a running task.
    pub fn cancel(&self, id: &str) -> bool {
        let tasks = self.tasks.lock().expect("task manager poisoned");
        let Some(handle) = tasks.get(id) else {
            return false;
        };
        handle.control.cancel();
        *handle.state.lock().expect("task state poisoned") = TaskState::Cancelled;
        true
    }

    #[must_use]
    pub fn state(&self, id: &str) -> Option<TaskState> {
        self.tasks
            .lock()
            .expect("task manager poisoned")
            .get(id)
            .map(|handle| *handle.state.lock().expect("task state poisoned"))
    }

    /// Snapshots every known task, ordered by id.
    #[must_use]
    pub fn summaries(&self) -> Vec<TaskSummary> {
        let tasks = self.tasks.lock().expect("task manager poisoned");
        let mut summaries: Vec<TaskSummary> = tasks
            .iter()
            .map(|(id, handle)| {
                let snapshot = handle.progress.snapshot();
                TaskSummary {
                    id: id.clone(),
                    name: handle.name.clone(),
                    state: *handle.state.lock().expect("task state poisoned"),
                    fraction: snapshot.fraction,
                    speed: snapshot.speed,
                }
            })
            .collect();
        summaries.sort_by(|left, right| left.id.cmp(&right.id));
        summaries
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{Duration, Instant};

    use crate::graph::GraphBuilder;

    async fn wait_for_finished(receiver: &mut broadcast::Receiver<TaskEvent>) -> TaskEvent {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            let event = tokio::time::timeout(remaining, receiver.recv())
                .await
                .expect("event within deadline")
                .expect("channel open");

            if matches!(event, TaskEvent::Finished { .. }) {
                return event;
            }
        }
    }

    #[tokio::test]
    async fn runs_a_graph_and_reports_success() {
        let manager = TaskManager::new();
        let mut events = manager.subscribe();

        let mut builder = GraphBuilder::new("install");
        builder.task("manifest", 1, |cx| async move {
            cx.set_total(4);
            cx.advance(4);
            Ok(())
        });
        let graph = Arc::new(builder.build());
        let id = manager.spawn(graph);

        match wait_for_finished(&mut events).await {
            TaskEvent::Finished {
                id: finished,
                state,
                ..
            } => {
                assert_eq!(finished, id.to_string());
                assert_eq!(state, TaskState::Succeeded);
            }
            other => panic!("unexpected event: {other:?}"),
        }
        assert_eq!(manager.state(id.as_str()), Some(TaskState::Succeeded));
        assert_eq!(manager.summaries().len(), 1);
    }

    #[tokio::test]
    async fn pause_stops_a_running_graph() {
        let manager = TaskManager::new();
        let mut events = manager.subscribe();

        let mut builder = GraphBuilder::new("install");
        builder.task("long", 1, |cx| async move {
            while !cx.is_cancelled() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            cx.check()
        });
        let graph = Arc::new(builder.build());
        let id = manager.spawn(graph);

        tokio::time::sleep(Duration::from_millis(40)).await;
        assert!(manager.pause(id.as_str()));

        match wait_for_finished(&mut events).await {
            TaskEvent::Finished {
                state, interrupted, ..
            } => {
                assert_eq!(state, TaskState::Paused);
                assert_eq!(interrupted.as_deref(), Some("paused"));
            }
            other => panic!("unexpected event: {other:?}"),
        }
    }

    #[tokio::test]
    async fn cancel_reports_cancelled() {
        let manager = TaskManager::new();
        let mut events = manager.subscribe();

        let mut builder = GraphBuilder::new("install");
        builder.task("long", 1, |cx| async move {
            while !cx.is_cancelled() {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
            cx.check()
        });
        let id = manager.spawn(Arc::new(builder.build()));

        tokio::time::sleep(Duration::from_millis(30)).await;
        assert!(manager.cancel(id.as_str()));

        match wait_for_finished(&mut events).await {
            TaskEvent::Finished { state, .. } => assert_eq!(state, TaskState::Cancelled),
            other => panic!("unexpected event: {other:?}"),
        }
    }
}
