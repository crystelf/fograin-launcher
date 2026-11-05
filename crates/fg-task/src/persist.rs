//! Persisted task-queue entries (`cache/tasks/*.json`, §6.3).
//!
//! A running graph cannot be serialized as futures, so the graph persists a
//! descriptor per node: id, weight, dependencies, an opaque payload and the
//! state it reached. The installer that rebuilds the graph decides what the
//! payload means.

use std::{
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::progress::NodeState;

/// Current persisted-task schema version.
pub const SCHEMA_VERSION: u32 = 1;

/// Serialized form of a task graph.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersistedGraph {
    pub schema_version: u32,
    pub id: String,
    pub name: String,
    pub created_ms: u64,
    pub updated_ms: u64,
    pub nodes: Vec<PersistedNode>,
}

/// Serialized form of one node.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PersistedNode {
    pub id: usize,
    pub name: String,
    pub weight: u32,
    #[serde(default)]
    pub deps: Vec<usize>,
    /// Opaque data used by the crate that rebuilds the graph.
    #[serde(default)]
    pub payload: Option<Value>,
    pub state: PersistedState,
}

/// Node state as written to disk.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PersistedState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Skipped,
}

impl From<NodeState> for PersistedState {
    fn from(state: NodeState) -> Self {
        match state {
            NodeState::Pending => Self::Pending,
            NodeState::Running => Self::Running,
            NodeState::Succeeded => Self::Succeeded,
            NodeState::Failed => Self::Failed,
            NodeState::Skipped => Self::Skipped,
        }
    }
}

impl PersistedGraph {
    /// Nodes that still need work after a restart; interrupted `Running` nodes
    /// are treated as pending because their futures were lost.
    pub fn pending_nodes(&self) -> impl Iterator<Item = &PersistedNode> {
        self.nodes.iter().filter(|node| {
            matches!(
                node.state,
                PersistedState::Pending | PersistedState::Running
            )
        })
    }

    /// `true` when every node finished successfully.
    #[must_use]
    pub fn is_complete(&self) -> bool {
        self.nodes
            .iter()
            .all(|node| node.state == PersistedState::Succeeded)
    }
}

/// Unix milliseconds.
#[must_use]
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

/// Path used for a graph id inside the task directory.
#[must_use]
pub fn graph_path(dir: &Path, id: &str) -> PathBuf {
    dir.join(format!("{id}.json"))
}

/// Atomically writes `graph` to `<dir>/<id>.json`.
///
/// # Errors
///
/// Propagates filesystem and serialization failures.
pub fn save_graph(dir: &Path, graph: &PersistedGraph) -> Result<PathBuf> {
    fs::create_dir_all(dir).map_err(|error| io_error(dir, &error))?;

    let path = graph_path(dir, &graph.id);
    let encoded = serde_json::to_vec_pretty(graph).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.task.serialize",
            error.to_string(),
        )
    })?;

    let temporary = path.with_extension("json.tmp");
    fs::write(&temporary, encoded).map_err(|error| io_error(&temporary, &error))?;

    match fs::rename(&temporary, &path) {
        Ok(()) => Ok(path),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists || cfg!(windows) => {
            let _ = fs::remove_file(&path);
            fs::rename(&temporary, &path).map_err(|error| io_error(&path, &error))?;
            Ok(path)
        }
        Err(error) => Err(io_error(&path, &error)),
    }
}

/// Reads one persisted graph.
///
/// # Errors
///
/// Returns [`ErrorKind::Parse`] for invalid JSON and [`ErrorKind::Io`] when the
/// file cannot be read.
pub fn load_graph(dir: &Path, id: &str) -> Result<PersistedGraph> {
    let path = graph_path(dir, id);
    let text = fs::read_to_string(&path).map_err(|error| io_error(&path, &error))?;
    let graph: PersistedGraph = serde_json::from_str(&text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.task.parse",
            format!("{}: {error}", path.display()),
        )
    })?;

    if graph.schema_version > SCHEMA_VERSION {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.task.schema",
            format!(
                "task {} uses schema {} (supported: {SCHEMA_VERSION})",
                graph.id, graph.schema_version
            ),
        ));
    }

    Ok(graph)
}

/// Lists every valid persisted graph, newest first.
///
/// Invalid files are skipped with a warning: a corrupt queue entry must not
/// stop the launcher from resuming the rest.
///
/// # Errors
///
/// Propagates directory read failures.
pub fn list_graphs(dir: &Path) -> Result<Vec<PersistedGraph>> {
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(error) => return Err(io_error(dir, &error)),
    };

    let mut graphs = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|error| io_error(dir, &error))?;
        let path = entry.path();
        if path.extension().and_then(|value| value.to_str()) != Some("json") {
            continue;
        }

        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        match serde_json::from_str::<PersistedGraph>(&text) {
            Ok(graph) => graphs.push(graph),
            Err(error) => tracing::warn!(
                path = %path.display(),
                detail = %error,
                "skipping unreadable persisted task"
            ),
        }
    }

    graphs.sort_by_key(|graph| std::cmp::Reverse(graph.updated_ms));
    Ok(graphs)
}

/// Removes a persisted graph after it finished.
///
/// # Errors
///
/// Propagates filesystem failures other than a missing file.
pub fn remove_graph(dir: &Path, id: &str) -> Result<()> {
    let path = graph_path(dir, id);
    match fs::remove_file(&path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(io_error(&path, &error)),
    }
}

fn io_error(path: &Path, error: &std::io::Error) -> Error {
    Error::with_detail(
        ErrorKind::Io,
        "error.io",
        format!("{}: {error}", path.display()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(id: &str) -> PersistedGraph {
        PersistedGraph {
            schema_version: SCHEMA_VERSION,
            id: id.to_string(),
            name: "install".to_string(),
            created_ms: 10,
            updated_ms: 20,
            nodes: vec![
                PersistedNode {
                    id: 0,
                    name: "version".to_string(),
                    weight: 1,
                    deps: vec![],
                    payload: Some(serde_json::json!({"kind": "manifest"})),
                    state: PersistedState::Succeeded,
                },
                PersistedNode {
                    id: 1,
                    name: "assets".to_string(),
                    weight: 5,
                    deps: vec![0],
                    payload: None,
                    state: PersistedState::Pending,
                },
            ],
        }
    }

    #[test]
    fn graphs_round_trip_through_disk() {
        let dir = tempfile::tempdir().expect("temp dir");
        let graph = sample("task-1");

        let path = save_graph(dir.path(), &graph).expect("save");
        assert!(path.exists());
        assert_eq!(load_graph(dir.path(), "task-1").expect("load"), graph);

        // Persisted data must be stable across repeated saves.
        save_graph(dir.path(), &graph).expect("resave");
        assert_eq!(load_graph(dir.path(), "task-1").expect("load"), graph);
    }

    #[test]
    fn pending_nodes_include_interrupted_running_work() {
        let mut graph = sample("task-2");
        graph.nodes[1].state = PersistedState::Running;
        assert_eq!(graph.pending_nodes().count(), 1);
        assert!(!graph.is_complete());

        graph.nodes[1].state = PersistedState::Succeeded;
        assert!(graph.is_complete());
    }

    #[test]
    fn listing_skips_corrupt_files_and_sorts_newest_first() {
        let dir = tempfile::tempdir().expect("temp dir");
        let mut older = sample("older");
        older.updated_ms = 1;
        let mut newer = sample("newer");
        newer.updated_ms = 99;

        save_graph(dir.path(), &older).expect("save older");
        save_graph(dir.path(), &newer).expect("save newer");
        fs::write(dir.path().join("broken.json"), b"{").expect("write broken");

        let graphs = list_graphs(dir.path()).expect("list");
        assert_eq!(graphs.len(), 2);
        assert_eq!(graphs[0].id, "newer");
        assert_eq!(graphs[1].id, "older");
    }

    #[test]
    fn unsupported_schema_versions_are_rejected() {
        let dir = tempfile::tempdir().expect("temp dir");
        let mut graph = sample("future");
        graph.schema_version = SCHEMA_VERSION + 1;
        save_graph(dir.path(), &graph).expect("save");

        let error = load_graph(dir.path(), "future").expect_err("rejected");
        assert_eq!(error.kind, ErrorKind::Unsupported);
    }

    #[test]
    fn removing_a_missing_graph_is_not_an_error() {
        let dir = tempfile::tempdir().expect("temp dir");
        remove_graph(dir.path(), "absent").expect("idempotent");
    }
}
