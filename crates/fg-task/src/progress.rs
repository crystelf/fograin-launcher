//! Per-node and graph-wide progress accounting (§6.3).

use std::{
    collections::VecDeque,
    sync::{
        Arc, Mutex,
        atomic::{AtomicU8, Ordering},
    },
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};

/// Lifecycle of a graph node.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum NodeState {
    Pending,
    Running,
    Succeeded,
    Failed,
    Skipped,
}

impl NodeState {
    pub(crate) fn as_u8(self) -> u8 {
        match self {
            Self::Pending => 0,
            Self::Running => 1,
            Self::Succeeded => 2,
            Self::Failed => 3,
            Self::Skipped => 4,
        }
    }

    pub(crate) fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::Running,
            2 => Self::Succeeded,
            3 => Self::Failed,
            4 => Self::Skipped,
            _ => Self::Pending,
        }
    }
}

/// Progress of one node at a point in time.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProgressSnapshot {
    /// `0.0..=1.0`.
    pub fraction: f64,
    pub done: u64,
    pub total: Option<u64>,
    /// Bytes per second over the last second.
    pub speed: f64,
}

impl Default for ProgressSnapshot {
    fn default() -> Self {
        Self {
            fraction: 0.0,
            done: 0,
            total: None,
            speed: 0.0,
        }
    }
}

/// Progress of a whole graph.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphProgress {
    /// `Σ(weight × fraction) / Σweight`.
    pub fraction: f64,
    /// Sum of node speeds, bytes per second.
    pub speed: f64,
    pub nodes: Vec<NodeSnapshot>,
}

impl GraphProgress {
    #[must_use]
    pub fn is_complete(&self) -> bool {
        self.fraction >= 1.0
    }
}

/// Progress of one node, including its display name.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NodeSnapshot {
    pub id: usize,
    pub name: String,
    pub weight: u32,
    pub state: NodeState,
    pub fraction: f64,
    pub done: u64,
    pub total: Option<u64>,
    pub message: String,
    pub speed: f64,
}

struct NodeCounters {
    done: u64,
    total: Option<u64>,
    /// Explicit fraction for stage-based tasks that have no byte total.
    fraction: f64,
    message: String,
    speed: SpeedMeter,
}

/// Shared mutable state of one node.
pub(crate) struct NodeProgress {
    id: usize,
    name: String,
    weight: u32,
    status: AtomicU8,
    counters: Mutex<NodeCounters>,
}

impl NodeProgress {
    pub(crate) fn new(id: usize, name: String, weight: u32) -> Self {
        Self {
            id,
            name,
            weight: weight.max(1),
            status: AtomicU8::new(NodeState::Pending.as_u8()),
            counters: Mutex::new(NodeCounters {
                done: 0,
                total: None,
                fraction: 0.0,
                message: String::new(),
                speed: SpeedMeter::new(Instant::now()),
            }),
        }
    }

    pub(crate) fn set_state(&self, state: NodeState) {
        self.status.store(state.as_u8(), Ordering::Release);
    }

    pub(crate) fn state(&self) -> NodeState {
        NodeState::from_u8(self.status.load(Ordering::Acquire))
    }

    pub(crate) fn set_total(&self, total: Option<u64>) {
        let mut counters = self.counters.lock().expect("progress poisoned");
        counters.total = total;
        counters.fraction = match total {
            Some(total) if total > 0 => (counters.done as f64 / total as f64).clamp(0.0, 1.0),
            Some(_) => 0.0,
            None => counters.fraction,
        };
    }

    pub(crate) fn advance(&self, bytes: u64) {
        let now = Instant::now();
        let mut counters = self.counters.lock().expect("progress poisoned");
        counters.done = counters.done.saturating_add(bytes);
        if let Some(total) = counters.total
            && total > 0
        {
            counters.fraction = (counters.done as f64 / total as f64).clamp(0.0, 1.0);
        }
        let done = counters.done;
        counters.speed.record(done, now);
    }

    pub(crate) fn set_fraction(&self, fraction: f64) {
        let mut counters = self.counters.lock().expect("progress poisoned");
        counters.fraction = fraction.clamp(0.0, 1.0);
        let done = counters.done;
        counters.speed.record(done, Instant::now());
    }

    /// Marks the node as finished successfully: its share of the graph bar is
    /// full even when the task never reported a byte total.
    pub(crate) fn complete(&self) {
        let mut counters = self.counters.lock().expect("progress poisoned");
        counters.fraction = 1.0;
        let done = counters.done;
        counters.speed.record(done, Instant::now());
    }

    pub(crate) fn set_message(&self, message: impl Into<String>) {
        self.counters.lock().expect("progress poisoned").message = message.into();
    }

    pub(crate) fn snapshot(&self) -> NodeSnapshot {
        let now = Instant::now();
        let mut counters = self.counters.lock().expect("progress poisoned");
        let speed = counters.speed.speed(now);

        NodeSnapshot {
            id: self.id,
            name: self.name.clone(),
            weight: self.weight,
            state: self.state(),
            fraction: counters.fraction.clamp(0.0, 1.0),
            done: counters.done,
            total: counters.total,
            message: counters.message.clone(),
            speed,
        }
    }

    pub(crate) fn weight(&self) -> u32 {
        self.weight
    }
}

/// Aggregates node progress into the graph-level fraction and speed.
pub(crate) struct GraphProgressState {
    nodes: Vec<Arc<NodeProgress>>,
    total_weight: u32,
}

impl GraphProgressState {
    pub(crate) fn new(nodes: Vec<Arc<NodeProgress>>) -> Self {
        let total_weight = nodes.iter().map(|node| node.weight()).sum::<u32>().max(1);
        Self {
            nodes,
            total_weight,
        }
    }

    pub(crate) fn snapshot(&self) -> GraphProgress {
        let nodes: Vec<NodeSnapshot> = self.nodes.iter().map(|node| node.snapshot()).collect();
        let weighted: f64 = nodes
            .iter()
            .map(|node| f64::from(node.weight) * node.fraction)
            .sum();

        GraphProgress {
            fraction: (weighted / f64::from(self.total_weight)).clamp(0.0, 1.0),
            speed: nodes.iter().map(|node| node.speed).sum(),
            nodes,
        }
    }
}

/// Sliding one-second window used for `bytes/s` (§6.3).
struct SpeedMeter {
    samples: VecDeque<(Instant, u64)>,
}

impl SpeedMeter {
    fn new(now: Instant) -> Self {
        let mut samples = VecDeque::with_capacity(16);
        samples.push_back((now, 0));
        Self { samples }
    }

    fn record(&mut self, cumulative: u64, now: Instant) {
        self.samples.push_back((now, cumulative));
        while self.samples.len() > 2
            && now.saturating_duration_since(self.samples[0].0) > Duration::from_secs(1)
        {
            self.samples.pop_front();
        }
    }

    fn speed(&mut self, now: Instant) -> f64 {
        if self.samples.len() < 2 {
            return 0.0;
        }

        let (first_time, first_bytes) = self.samples[0];
        let (_, last_bytes) = *self.samples.back().expect("non-empty by construction");
        let elapsed = now.saturating_duration_since(first_time).as_secs_f64();
        if elapsed <= 0.0 {
            return 0.0;
        }

        (last_bytes.saturating_sub(first_bytes) as f64 / elapsed).max(0.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fraction_tracks_bytes_and_explicit_stages() {
        let node = NodeProgress::new(0, "jar".into(), 1);

        node.set_total(Some(100));
        node.advance(25);
        assert_eq!(node.snapshot().fraction, 0.25);

        node.set_fraction(0.75);
        assert_eq!(node.snapshot().fraction, 0.75);

        node.set_fraction(2.0);
        assert_eq!(node.snapshot().fraction, 1.0);
    }

    #[test]
    fn graph_fraction_is_weighted() {
        let first = Arc::new(NodeProgress::new(0, "first".into(), 1));
        let second = Arc::new(NodeProgress::new(1, "second".into(), 3));
        let state = GraphProgressState::new(vec![first.clone(), second.clone()]);

        first.set_fraction(1.0);
        second.set_fraction(0.0);
        assert!((state.snapshot().fraction - 0.25).abs() < 1e-9);

        second.set_fraction(1.0);
        assert!((state.snapshot().fraction - 1.0).abs() < 1e-9);
    }

    #[test]
    fn speed_uses_a_one_second_window() {
        let start = Instant::now();
        let mut meter = SpeedMeter::new(start);

        meter.record(1000, start + Duration::from_millis(200));
        meter.record(2000, start + Duration::from_millis(400));
        let speed = meter.speed(start + Duration::from_millis(500));

        assert!((speed - 4000.0).abs() < 1.0, "speed was {speed}");
    }

    #[test]
    fn node_snapshots_carry_state_and_message() {
        let node = NodeProgress::new(2, "assets".into(), 4);
        node.set_state(NodeState::Running);
        node.set_total(Some(10));
        node.advance(5);
        node.set_message("downloading assets");

        let snapshot = node.snapshot();
        assert_eq!(snapshot.id, 2);
        assert_eq!(snapshot.name, "assets");
        assert_eq!(snapshot.weight, 4);
        assert_eq!(snapshot.state, NodeState::Running);
        assert_eq!(snapshot.done, 5);
        assert_eq!(snapshot.total, Some(10));
        assert_eq!(snapshot.message, "downloading assets");
        assert!((snapshot.fraction - 0.5).abs() < 1e-9);
    }
}
