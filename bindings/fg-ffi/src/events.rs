//! In-process event queue pushed by progress ticks and game sessions.
//!
//! The native clients drain it with [`fg_events_next`]; each entry is one
//! camelCase JSON object:
//!
//! - `task.progress` — `{ type, taskId, name, fraction, speed }`
//! - `task.finished` — `{ type, taskId, name, state }`
//! - `game.line` — `{ type, instanceId, text }`
//! - `game.started` — `{ type, instanceId, pid }`
//! - `game.exited` — `{ type, instanceId, exitCode, playSeconds }`

use std::collections::VecDeque;

const CAPACITY: usize = 256;

/// Ring buffer of pending events, shared by every RPC call site.
pub struct EventQueue {
    queue: std::sync::Mutex<VecDeque<String>>,
    game_lines: std::sync::Mutex<VecDeque<(String, String)>>,
}

impl Default for EventQueue {
    fn default() -> Self {
        Self::new()
    }
}

impl EventQueue {
    #[must_use]
    pub fn new() -> Self {
        Self {
            queue: std::sync::Mutex::new(VecDeque::new()),
            game_lines: std::sync::Mutex::new(VecDeque::new()),
        }
    }

    /// Enqueues one serialized event, dropping the oldest when full.
    pub fn push(&self, payload: String) {
        let mut queue = self.queue.lock().expect("event queue poisoned");
        if queue.len() >= CAPACITY {
            queue.pop_front();
        }
        queue.push_back(payload);
    }

    /// Pops the next event, or `None` when the queue is empty.
    #[must_use]
    pub fn next(&self) -> Option<String> {
        self.queue.lock().expect("event queue poisoned").pop_front()
    }

    #[must_use]
    pub fn len(&self) -> usize {
        self.queue.lock().expect("event queue poisoned").len()
    }

    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Removes up to `limit` events; the C ABI uses size hints to make this
    /// safe for the `fg_events_next` contract.
    #[must_use]
    pub fn next_with_limit(&self, limit: usize) -> Vec<String> {
        let mut queue = self.queue.lock().expect("event queue poisoned");
        (0..limit.max(1)).map_while(|_| queue.pop_front()).collect()
    }

    pub fn push_task_progress(&self, task_id: &str, name: &str, fraction: f64, speed: f64) {
        let payload = serde_json::json!({
            "type": "task.progress",
            "taskId": task_id,
            "name": name,
            "fraction": fraction.clamp(0.0, 1.0),
            "speed": speed.max(0.0),
        });
        self.push(payload.to_string());
    }

    /// Terminal event with node-level detail from the graph report.
    pub fn push_task_finished_ex(
        &self,
        task_id: &str,
        name: &str,
        state: &str,
        report: &fg_task::GraphReport,
    ) {
        let detail: Vec<String> = report
            .failed
            .iter()
            .map(|(node, error)| format!("{node}: {error}"))
            .take(5)
            .collect();
        let payload = serde_json::json!({
            "type": "task.finished",
            "taskId": task_id,
            "name": name,
            "state": state,
            "detail": detail,
        });
        self.push(payload.to_string());
    }

    pub fn push_game_window_ready(&self, instance_id: &str) {
        let payload = serde_json::json!({
            "type": "game.windowReady",
            "instanceId": instance_id,
        });
        self.push(payload.to_string());
    }

    pub fn push_game_started(&self, instance_id: &str, pid: Option<u32>) {
        let payload = serde_json::json!({
            "type": "game.started",
            "instanceId": instance_id,
            "pid": pid,
        });
        self.push(payload.to_string());
    }

    /// Appends one log line for a running session (also keeps a small recent
    /// buffer the UI reads through `game.log.recent`).
    pub fn push_game_line(&self, instance_id: &str, line: &str) {
        {
            let mut lines = self.game_lines.lock().expect("game lines poisoned");
            if lines.len() >= 400 {
                lines.pop_front();
            }
            lines.push_back((instance_id.to_string(), line.to_string()));
        }
        let payload = serde_json::json!({
            "type": "game.line",
            "instanceId": instance_id,
            "text": line,
        });
        self.push(payload.to_string());
    }

    pub fn push_game_exited(&self, instance_id: &str, exit_code: Option<i32>, play_seconds: u64) {
        let payload = serde_json::json!({
            "type": "game.exited",
            "instanceId": instance_id,
            "exitCode": exit_code,
            "playSeconds": play_seconds,
        });
        self.push(payload.to_string());
    }

    /// Latest buffered lines for one instance, newest last.
    #[must_use]
    pub fn recent_game_lines(&self, instance_id: &str, limit: usize) -> Vec<String> {
        let lines = self.game_lines.lock().expect("game lines poisoned");
        lines
            .iter()
            .filter(|(id, _)| id == instance_id)
            .map(|(_, text)| text.clone())
            .rev()
            .take(limit)
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect()
    }

    pub fn clear_game_lines(&self, instance_id: &str) {
        self.game_lines
            .lock()
            .expect("game lines poisoned")
            .retain(|(id, _)| id != instance_id);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queue_is_fifo_and_cap_bounded() {
        let queue = EventQueue::new();
        for index in 0..(CAPACITY + 10) {
            queue.push_task_progress("t", "install", index as f64 / 1000.0, 0.0);
        }
        assert_eq!(queue.len(), CAPACITY);

        let first = queue.next().expect("first event");
        assert!(first.contains("\"taskId\":\"t\""), "{first}");
        assert!(queue.next().is_some());
    }

    #[test]
    fn game_lines_are_kept_per_instance() {
        let queue = EventQueue::new();
        queue.push_game_line("a", "[stdout] hello");
        queue.push_game_line("b", "[stdout] other");
        queue.push_game_line("a", "[stdout] world");

        let lines = queue.recent_game_lines("a", 10);
        assert_eq!(
            lines,
            vec!["[stdout] hello".to_string(), "[stdout] world".to_string()]
        );

        queue.clear_game_lines("a");
        assert!(queue.recent_game_lines("a", 10).is_empty());
        assert_eq!(queue.recent_game_lines("b", 10).len(), 1);
    }

    #[test]
    fn exited_events_carry_seconds() {
        let queue = EventQueue::new();
        queue.push_game_exited("a", Some(0), 42);
        let event = queue.next().expect("event");
        assert!(event.contains("\"playSeconds\":42"), "{event}");
        assert!(event.contains("\"exitCode\":0"), "{event}");
    }
}
