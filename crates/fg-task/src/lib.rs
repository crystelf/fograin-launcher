//! Task graphs, progress aggregation, cancellation and persistence (§6.3).
//!
//! This crate turns the launcher's long-running work into a DAG of weighted
//! nodes. It knows nothing about downloading or installing: a node is just a
//! future with a name, a weight and dependencies, which keeps the scheduling
//! logic testable and the `fg-net` engine independent.

pub mod graph;
pub mod id;
pub mod manager;
pub mod persist;
pub mod progress;

pub use graph::{
    Control, FailPolicy, GraphBuilder, GraphReport, NodeId, RunOptions, StopReason, TaskCx,
    TaskFuture, TaskGraph,
};
pub use id::TaskId;
pub use manager::{TaskEvent, TaskManager, TaskState, TaskSummary};
pub use persist::{
    PersistedGraph, PersistedNode, PersistedState, list_graphs, load_graph, remove_graph,
    save_graph,
};
pub use progress::{GraphProgress, NodeSnapshot, NodeState, ProgressSnapshot};
