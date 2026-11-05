//! Task graphs: dependencies, bounded parallelism, cancellation and pause (§6.3).
//!
//! A graph is a set of named nodes with weights and dependency edges. Nodes with
//! no unfinished dependencies run concurrently up to [`RunOptions::max_parallel`].
//! A failing node either fails the whole graph (`FailFast`, the default for
//! installation) or lets independent branches continue (`ContinueOthers`).

use std::{
    collections::VecDeque,
    future::Future,
    path::{Path, PathBuf},
    pin::Pin,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use fg_core::{Error, ErrorKind, Result};
use serde_json::Value;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::{
    id::TaskId,
    persist::{PersistedGraph, PersistedNode, PersistedState, now_ms, save_graph},
    progress::{GraphProgress, GraphProgressState, NodeProgress, NodeState, ProgressSnapshot},
};

/// Future produced by a graph node.
pub type TaskFuture = Pin<Box<dyn Future<Output = Result<()>> + Send>>;

type TaskFn = Box<dyn FnOnce(TaskCx) -> TaskFuture + Send>;

/// Handle to one node inside a graph.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct NodeId(usize);

impl NodeId {
    /// Zero-based position in the graph.
    #[must_use]
    pub const fn index(self) -> usize {
        self.0
    }
}

/// Why a graph stopped before finishing.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopReason {
    Cancelled,
    Paused,
}

impl StopReason {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Cancelled => "cancelled",
            Self::Paused => "paused",
        }
    }
}

impl serde::Serialize for StopReason {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> serde::Deserialize<'de> for StopReason {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        let value = String::deserialize(deserializer)?;
        match value.as_str() {
            "cancelled" => Ok(Self::Cancelled),
            "paused" => Ok(Self::Paused),
            other => Err(serde::de::Error::unknown_variant(
                other,
                &["cancelled", "paused"],
            )),
        }
    }
}

/// Pause/cancel handle shared by the UI and the graph.
#[derive(Debug, Clone)]
pub struct Control {
    token: CancellationToken,
    reason: Arc<Mutex<Option<StopReason>>>,
}

impl Default for Control {
    fn default() -> Self {
        Self::new()
    }
}

impl Control {
    #[must_use]
    pub fn new() -> Self {
        Self {
            token: CancellationToken::new(),
            reason: Arc::new(Mutex::new(None)),
        }
    }

    /// Stops the graph while keeping the progress it reached (§6.3 "pause").
    pub fn pause(&self) {
        *self.reason.lock().expect("control poisoned") = Some(StopReason::Paused);
        self.token.cancel();
    }

    /// Stops the graph and discards the remaining work.
    pub fn cancel(&self) {
        *self.reason.lock().expect("control poisoned") = Some(StopReason::Cancelled);
        self.token.cancel();
    }

    #[must_use]
    pub fn is_stopped(&self) -> bool {
        self.token.is_cancelled()
    }

    #[must_use]
    pub fn reason(&self) -> Option<StopReason> {
        *self.reason.lock().expect("control poisoned")
    }

    /// Resolves once a pause or cancel was requested.
    pub async fn cancelled(&self) {
        self.token.cancelled().await;
    }

    /// Fail-fast abort: cancels running nodes without claiming a user reason.
    pub(crate) fn abort(&self) {
        self.token.cancel();
    }
}

/// How a node failure affects the rest of the graph.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum FailPolicy {
    /// Cancel in-flight nodes and stop scheduling (default).
    #[default]
    FailFast,
    /// Let independent branches finish; dependents of the failure are skipped.
    ContinueOthers,
}

/// Execution limits.
#[derive(Debug, Clone)]
pub struct RunOptions {
    /// Maximum nodes running at the same time.
    pub max_parallel: usize,
    pub fail_policy: FailPolicy,
}

impl Default for RunOptions {
    fn default() -> Self {
        Self {
            max_parallel: std::thread::available_parallelism()
                .map_or(4, |value| value.get())
                .clamp(1, 64),
            fail_policy: FailPolicy::FailFast,
        }
    }
}

/// Result of running a graph.
#[derive(Debug, Clone)]
pub struct GraphReport {
    pub id: TaskId,
    pub name: String,
    pub elapsed: Duration,
    pub succeeded: usize,
    pub failed: Vec<(String, Error)>,
    /// Nodes that never ran because of a failure, cancellation or pause.
    pub skipped: Vec<String>,
    pub interrupted: Option<StopReason>,
}

impl GraphReport {
    #[must_use]
    pub fn is_success(&self) -> bool {
        self.failed.is_empty() && self.skipped.is_empty() && self.interrupted.is_none()
    }
}

/// Context handed to a running node.
#[derive(Clone)]
pub struct TaskCx {
    graph_id: TaskId,
    name: String,
    progress: Arc<NodeProgress>,
    cancel: CancellationToken,
}

impl TaskCx {
    /// Identifier of the owning graph.
    #[must_use]
    pub fn id(&self) -> &TaskId {
        &self.graph_id
    }

    /// Display name of this node (an i18n key in the UI layer).
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// Current progress of this node.
    #[must_use]
    pub fn progress(&self) -> ProgressSnapshot {
        let snapshot = self.progress.snapshot();
        ProgressSnapshot {
            fraction: snapshot.fraction,
            done: snapshot.done,
            total: snapshot.total,
            speed: snapshot.speed,
        }
    }

    /// Declares the byte total, which switches progress to `done / total`.
    pub fn set_total(&self, total: u64) {
        self.progress.set_total(Some(total));
    }

    pub fn set_fraction(&self, fraction: f64) {
        self.progress.set_fraction(fraction);
    }

    /// Reports `bytes` of work; used by the download engine's progress sink.
    pub fn advance(&self, bytes: u64) {
        self.progress.advance(bytes);
    }

    /// Sets a transient status line (usually an i18n key).
    pub fn set_message(&self, message: impl Into<String>) {
        self.progress.set_message(message);
    }

    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }

    /// Clone of the cancellation token, so long-running work (for example
    /// `fg_net::DownloadCtx`) stops together with the graph.
    #[must_use]
    pub fn cancel_handle(&self) -> CancellationToken {
        self.cancel.clone()
    }

    /// Returns [`ErrorKind::Cancelled`] once the graph was paused or cancelled.
    ///
    /// # Errors
    ///
    /// Always returns an error when the graph is stopping.
    pub fn check(&self) -> Result<()> {
        if self.is_cancelled() {
            Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"))
        } else {
            Ok(())
        }
    }

    /// Tracing span carrying the graph and node names.
    #[must_use]
    pub fn span(&self) -> tracing::Span {
        tracing::info_span!("task", graph = %self.graph_id, node = %self.name)
    }
}

impl std::fmt::Debug for TaskCx {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TaskCx")
            .field("graph", &self.graph_id)
            .field("node", &self.name)
            .finish_non_exhaustive()
    }
}

struct NodeDef {
    id: NodeId,
    name: String,
    weight: u32,
    deps: Vec<NodeId>,
    payload: Option<Value>,
    task: Mutex<Option<TaskFn>>,
    progress: Arc<NodeProgress>,
}

/// Fluent builder for [`TaskGraph`].
pub struct GraphBuilder {
    name: String,
    nodes: Vec<NodeDef>,
}

impl GraphBuilder {
    #[must_use]
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            nodes: Vec::new(),
        }
    }

    /// Adds a node. `weight` is its share of the graph progress bar.
    ///
    /// # Panics
    ///
    /// Panics when `weight` is zero; use `1` for equal weighting.
    pub fn task<F, Fut>(&mut self, name: impl Into<String>, weight: u32, task: F) -> NodeId
    where
        F: FnOnce(TaskCx) -> Fut + Send + 'static,
        Fut: Future<Output = Result<()>> + Send + 'static,
    {
        assert!(weight > 0, "task weight must be positive");

        let id = NodeId(self.nodes.len());
        let name = name.into();
        self.nodes.push(NodeDef {
            id,
            progress: Arc::new(NodeProgress::new(id.0, name.clone(), weight)),
            name,
            weight,
            deps: Vec::new(),
            payload: None,
            task: Mutex::new(Some(Box::new(move |cx| Box::pin(task(cx))))),
        });
        id
    }

    /// Attaches an opaque payload used to rebuild the graph after a restart.
    pub fn payload(&mut self, node: NodeId, payload: Value) -> &mut Self {
        self.node_mut(node).payload = Some(payload);
        self
    }

    /// Declares that `node` must wait for `dependency`.
    pub fn depends_on(&mut self, node: NodeId, dependency: NodeId) -> &mut Self {
        assert_ne!(node, dependency, "a task cannot depend on itself");
        self.check_node(node);
        self.check_node(dependency);

        let deps = &mut self.node_mut(node).deps;
        if !deps.contains(&dependency) {
            deps.push(dependency);
        }
        self
    }

    /// Declares several dependencies at once.
    pub fn after(&mut self, node: NodeId, dependencies: &[NodeId]) -> &mut Self {
        for dependency in dependencies {
            self.depends_on(node, *dependency);
        }
        self
    }

    /// Finishes the graph and validates that it is acyclic.
    ///
    /// # Panics
    ///
    /// Panics when the dependency graph contains a cycle.
    #[must_use]
    pub fn build(self) -> TaskGraph {
        self.assert_acyclic();

        let progresses: Vec<Arc<NodeProgress>> = self
            .nodes
            .iter()
            .map(|node| node.progress.clone())
            .collect();

        TaskGraph {
            id: TaskId::new(),
            name: self.name,
            created_ms: now_ms(),
            progress: Arc::new(GraphProgressState::new(progresses)),
            nodes: self.nodes,
        }
    }

    fn node_mut(&mut self, node: NodeId) -> &mut NodeDef {
        self.check_node(node);
        &mut self.nodes[node.0]
    }

    fn check_node(&self, node: NodeId) {
        assert!(node.0 < self.nodes.len(), "node {} does not exist", node.0);
    }

    fn assert_acyclic(&self) {
        let mut remaining: Vec<usize> = self.nodes.iter().map(|node| node.deps.len()).collect();
        let mut dependents: Vec<Vec<usize>> = vec![Vec::new(); self.nodes.len()];
        for node in &self.nodes {
            for dependency in &node.deps {
                dependents[dependency.0].push(node.id.0);
            }
        }

        let mut queue: VecDeque<usize> = (0..self.nodes.len())
            .filter(|index| remaining[*index] == 0)
            .collect();
        let mut visited = 0;

        while let Some(index) = queue.pop_front() {
            visited += 1;
            for dependent in &dependents[index] {
                remaining[*dependent] -= 1;
                if remaining[*dependent] == 0 {
                    queue.push_back(*dependent);
                }
            }
        }

        assert!(
            visited == self.nodes.len(),
            "task graph contains a dependency cycle"
        );
    }
}

/// One runnable graph. Run it once; tasks are `FnOnce`.
pub struct TaskGraph {
    id: TaskId,
    name: String,
    created_ms: u64,
    nodes: Vec<NodeDef>,
    progress: Arc<GraphProgressState>,
}

impl TaskGraph {
    /// Starts building a graph with `name` (an i18n key in the UI).
    #[must_use]
    pub fn builder(name: impl Into<String>) -> GraphBuilder {
        GraphBuilder::new(name)
    }

    #[must_use]
    pub fn id(&self) -> &TaskId {
        &self.id
    }

    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    #[must_use]
    pub fn node_count(&self) -> usize {
        self.nodes.len()
    }

    /// Current aggregated progress.
    #[must_use]
    pub fn progress(&self) -> GraphProgress {
        self.progress.snapshot()
    }

    /// Shared progress handle for a manager ticker.
    #[must_use]
    pub(crate) fn progress_handle(&self) -> Arc<GraphProgressState> {
        self.progress.clone()
    }

    /// Snapshots the graph for persistence.
    #[must_use]
    pub fn snapshot(&self) -> PersistedGraph {
        PersistedGraph {
            schema_version: 1,
            id: self.id.to_string(),
            name: self.name.clone(),
            created_ms: self.created_ms,
            updated_ms: now_ms(),
            nodes: self
                .nodes
                .iter()
                .map(|node| PersistedNode {
                    id: node.id.0,
                    name: node.name.clone(),
                    weight: node.weight,
                    deps: node.deps.iter().map(|dep| dep.0).collect(),
                    payload: node.payload.clone(),
                    state: PersistedState::from(node.progress.state()),
                })
                .collect(),
        }
    }

    /// Writes the snapshot to `<dir>/<task-id>.json`.
    ///
    /// # Errors
    ///
    /// Propagates filesystem and serialization failures.
    pub fn save(&self, dir: &Path) -> Result<PathBuf> {
        save_graph(dir, &self.snapshot())
    }

    /// Runs every node, honouring `control` and `options`.
    pub async fn run(&self, control: &Control, options: &RunOptions) -> GraphReport {
        let started = Instant::now();
        let limit = options.max_parallel.max(1);
        let count = self.nodes.len();

        let mut remaining: Vec<usize> = self.nodes.iter().map(|node| node.deps.len()).collect();
        let mut dependents: Vec<Vec<usize>> = vec![Vec::new(); count];
        for node in &self.nodes {
            for dependency in &node.deps {
                dependents[dependency.0].push(node.id.0);
            }
        }

        let mut ready: VecDeque<usize> =
            (0..count).filter(|index| remaining[*index] == 0).collect();

        let (sender, mut receiver) = mpsc::unbounded_channel::<Completion>();
        let mut running = 0_usize;
        let mut failed: Vec<(String, Error)> = Vec::new();
        let mut aborted = false;

        loop {
            while !aborted && !control.is_stopped() && running < limit {
                let Some(index) = ready.pop_front() else {
                    break;
                };

                let node = &self.nodes[index];
                let Some(task) = node.task.lock().expect("task node poisoned").take() else {
                    continue;
                };

                node.progress.set_state(NodeState::Running);
                let cx = TaskCx {
                    graph_id: self.id.clone(),
                    name: node.name.clone(),
                    progress: node.progress.clone(),
                    cancel: control.token.clone(),
                };

                let future = task(cx);
                let sender = sender.clone();
                running += 1;

                tokio::spawn(async move {
                    let result = future.await;
                    let _ = sender.send(Completion { index, result });
                });
            }

            if running == 0 {
                break;
            }

            let Some(completion) = receiver.recv().await else {
                break;
            };
            running -= 1;

            let node = &self.nodes[completion.index];
            match completion.result {
                Ok(()) => {
                    node.progress.complete();
                    node.progress.set_state(NodeState::Succeeded);
                    for dependent in &dependents[completion.index] {
                        remaining[*dependent] -= 1;
                        if remaining[*dependent] == 0 {
                            ready.push_back(*dependent);
                        }
                    }
                }
                Err(error) => {
                    if control.is_stopped() {
                        // Pause/cancel surfaces as a cancelled node error.
                        node.progress.set_state(NodeState::Skipped);
                        aborted = true;
                    } else {
                        node.progress.set_state(NodeState::Failed);
                        failed.push((node.name.clone(), error));
                        if options.fail_policy == FailPolicy::FailFast {
                            aborted = true;
                            control.abort();
                        }
                    }
                }
            }
        }

        let mut skipped = Vec::new();
        for node in &self.nodes {
            match node.progress.state() {
                NodeState::Pending => {
                    node.progress.set_state(NodeState::Skipped);
                    skipped.push(node.name.clone());
                }
                // Already marked by the cancellation branch.
                NodeState::Skipped => skipped.push(node.name.clone()),
                _ => {}
            }
        }

        let succeeded = self
            .nodes
            .iter()
            .filter(|node| node.progress.state() == NodeState::Succeeded)
            .count();

        GraphReport {
            id: self.id.clone(),
            name: self.name.clone(),
            elapsed: started.elapsed(),
            succeeded,
            failed,
            skipped,
            interrupted: if control.is_stopped() {
                control.reason()
            } else {
                None
            },
        }
    }
}

impl std::fmt::Debug for TaskGraph {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("TaskGraph")
            .field("id", &self.id)
            .field("name", &self.name)
            .field(
                "nodes",
                &self
                    .nodes
                    .iter()
                    .map(|node| node.name.as_str())
                    .collect::<Vec<_>>(),
            )
            .finish()
    }
}

struct Completion {
    index: usize,
    result: Result<()>,
}

#[cfg(test)]
mod tests {
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
    use std::time::Duration;

    use fg_core::{Error, ErrorKind};

    use super::*;
    use crate::persist::load_graph;

    fn fast_options(max_parallel: usize) -> RunOptions {
        RunOptions {
            max_parallel,
            fail_policy: FailPolicy::FailFast,
        }
    }

    #[tokio::test]
    async fn dependencies_run_before_their_dependents() {
        let order = Arc::new(Mutex::new(Vec::new()));

        let mut builder = GraphBuilder::new("install");
        let first = {
            let order = order.clone();
            builder.task("manifest", 1, move |_cx| {
                let order = order.clone();
                async move {
                    order.lock().expect("order").push("manifest");
                    Ok(())
                }
            })
        };
        let second = {
            let order = order.clone();
            builder.task("jar", 1, move |_cx| {
                let order = order.clone();
                async move {
                    order.lock().expect("order").push("jar");
                    Ok(())
                }
            })
        };
        builder.depends_on(second, first);

        let graph = builder.build();
        let report = graph.run(&Control::new(), &fast_options(2)).await;

        assert!(report.is_success(), "{report:?}");
        assert_eq!(*order.lock().expect("order"), vec!["manifest", "jar"]);
        // Both nodes finished, so the weighted fraction is full.
        assert_eq!(graph.progress().fraction, 1.0);
    }

    #[tokio::test]
    async fn independent_nodes_run_in_parallel() {
        let barrier = Arc::new(tokio::sync::Barrier::new(2));

        let mut builder = GraphBuilder::new("parallel");
        for index in 0..2 {
            let barrier = barrier.clone();
            builder.task(format!("branch-{index}"), 1, move |_cx| {
                let barrier = barrier.clone();
                async move {
                    barrier.wait().await;
                    Ok(())
                }
            });
        }

        let graph = builder.build();
        let report = tokio::time::timeout(
            Duration::from_secs(2),
            graph.run(&Control::new(), &fast_options(2)),
        )
        .await
        .expect("branches must overlap");

        assert!(report.is_success());
        assert_eq!(report.succeeded, 2);
    }

    #[tokio::test]
    async fn fail_fast_skips_the_remaining_work() {
        let mut builder = GraphBuilder::new("install");
        let failing = builder.task("jar", 1, |_cx| async move {
            Err(Error::new(ErrorKind::Network, "error.net.all_candidates"))
        });
        let dependent = builder.task("assets", 1, |_cx| async move { Ok(()) });
        let independent = builder.task("libraries", 1, |_cx| async move { Ok(()) });
        builder.depends_on(dependent, failing);

        let report = builder.build().run(&Control::new(), &fast_options(1)).await;

        assert_eq!(report.failed.len(), 1);
        assert_eq!(report.failed[0].0, "jar");
        assert_eq!(report.succeeded, 0);
        assert_eq!(report.skipped.len(), 2);
        assert!(report.skipped.contains(&"assets".to_string()));
        assert!(report.skipped.contains(&"libraries".to_string()));
        assert!(!report.is_success());
        let _ = independent;
    }

    #[tokio::test]
    async fn continue_others_keeps_independent_branches() {
        let mut builder = GraphBuilder::new("install");
        let failing = builder.task("jar", 1, |_cx| async move {
            Err(Error::new(ErrorKind::Network, "error.net.all_candidates"))
        });
        let dependent = builder.task("assets", 1, |_cx| async move { Ok(()) });
        builder.task("metadata", 1, |_cx| async move { Ok(()) });
        builder.depends_on(dependent, failing);

        let options = RunOptions {
            max_parallel: 1,
            fail_policy: FailPolicy::ContinueOthers,
        };
        let report = builder.build().run(&Control::new(), &options).await;

        assert_eq!(report.failed.len(), 1);
        assert_eq!(report.succeeded, 1);
        assert_eq!(report.skipped, vec!["assets".to_string()]);
    }

    #[tokio::test]
    async fn pausing_marks_pending_nodes_as_skipped() {
        let control = Control::new();
        let node_control = control.clone();

        let mut builder = GraphBuilder::new("install");
        let first = builder.task("jar", 1, move |cx| {
            let node_control = node_control.clone();
            async move {
                cx.set_fraction(0.5);
                node_control.pause();
                Ok(())
            }
        });
        let second = builder.task("assets", 1, |_cx| async move { Ok(()) });
        builder.depends_on(second, first);

        let report = builder.build().run(&control, &fast_options(2)).await;

        assert_eq!(report.interrupted, Some(StopReason::Paused));
        assert_eq!(report.succeeded, 1);
        assert_eq!(report.skipped, vec!["assets".to_string()]);
    }

    #[tokio::test]
    async fn cancellation_surfaces_from_node_checks() {
        let control = Control::new();
        control.cancel();

        let mut builder = GraphBuilder::new("install");
        builder.task("jar", 1, |cx| async move { cx.check() });

        let report = builder.build().run(&control, &fast_options(2)).await;

        assert_eq!(report.interrupted, Some(StopReason::Cancelled));
        assert_eq!(report.succeeded, 0);
        assert_eq!(report.skipped, vec!["jar".to_string()]);
    }

    #[tokio::test]
    async fn completed_nodes_fill_their_progress_share() {
        let mut builder = GraphBuilder::new("install");
        builder.task("small", 1, |cx| async move {
            cx.set_total(10);
            cx.advance(10);
            Ok(())
        });
        builder.task("large", 3, |cx| async move {
            // A task that finishes without reporting bytes still counts as 100%.
            cx.set_fraction(0.0);
            Ok(())
        });

        let graph = builder.build();
        let report = graph.run(&Control::new(), &fast_options(2)).await;
        assert!(report.is_success());

        let progress = graph.progress();
        assert!((progress.fraction - 1.0).abs() < 1e-9);
        assert_eq!(progress.nodes.len(), 2);
        assert!(
            progress
                .nodes
                .iter()
                .all(|node| node.state == NodeState::Succeeded)
        );
    }

    #[tokio::test]
    async fn snapshots_persist_dependencies_and_payloads() {
        let mut builder = GraphBuilder::new("install");
        let manifest = builder.task("manifest", 1, |_cx| async move { Ok(()) });
        builder.payload(
            manifest,
            serde_json::json!({"url": "https://example.com/manifest.json"}),
        );
        let assets = builder.task("assets", 4, |_cx| async move { Ok(()) });
        builder.depends_on(assets, manifest);

        let graph = builder.build();
        let snapshot = graph.snapshot();
        assert_eq!(snapshot.schema_version, 1);
        assert_eq!(snapshot.id, graph.id().to_string());
        assert_eq!(snapshot.nodes.len(), 2);
        assert_eq!(snapshot.nodes[1].deps, vec![0]);
        assert_eq!(
            snapshot.nodes[0].payload,
            Some(serde_json::json!({"url": "https://example.com/manifest.json"}))
        );

        let dir = tempfile::tempdir().expect("temp dir");
        let path = graph.save(dir.path()).expect("save");
        assert!(path.exists());

        let mut loaded = load_graph(dir.path(), graph.id().as_str()).expect("load");
        // `save` takes a fresh snapshot, so only its timestamp can differ.
        loaded.updated_ms = snapshot.updated_ms;
        assert_eq!(loaded, snapshot);
    }

    #[test]
    #[should_panic(expected = "cycle")]
    fn cycles_are_rejected_at_build_time() {
        let mut builder = GraphBuilder::new("loop");
        let first = builder.task("first", 1, |_cx| async move { Ok(()) });
        let second = builder.task("second", 1, |_cx| async move { Ok(()) });
        builder.depends_on(first, second);
        builder.depends_on(second, first);
        let _ = builder.build();
    }

    #[tokio::test]
    async fn node_counter_reports_through_the_context() {
        let observed = Arc::new(AtomicUsize::new(0));

        let mut builder = GraphBuilder::new("counter");
        let observed_clone = observed.clone();
        builder.task("bytes", 1, move |cx| {
            let observed = observed_clone.clone();
            async move {
                cx.set_total(100);
                cx.advance(40);
                observed.store(cx.progress().done as usize, Ordering::SeqCst);
                cx.check()
            }
        });

        builder.build().run(&Control::new(), &fast_options(1)).await;
        assert_eq!(observed.load(Ordering::SeqCst), 40);
    }
}
