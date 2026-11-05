//! Task identifiers used by the FFI surface and the persisted task queue.

use std::{
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize};

/// Time-ordered task identifier.
///
/// The first half is the creation time in milliseconds, the second half a
/// process-local counter, so identifiers sort by start order and stay unique
/// inside one launcher session.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct TaskId(String);

impl TaskId {
    /// Creates a fresh identifier.
    #[must_use]
    pub fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let millis = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |duration| duration.as_millis() as u64);
        let counter = NEXT.fetch_add(1, Ordering::Relaxed);

        Self(format!("{millis:012x}{counter:04x}"))
    }

    /// Wraps an identifier loaded from disk or received over FFI.
    #[must_use]
    pub fn from_string(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl Default for TaskId {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Display for TaskId {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identifiers_are_unique_and_ordered() {
        let first = TaskId::new();
        let second = TaskId::new();

        assert_ne!(first, second);
        assert!(first < second);
        assert_eq!(first.as_str().len(), 16);
        assert!(
            first
                .as_str()
                .chars()
                .all(|value| value.is_ascii_hexdigit())
        );
    }

    #[test]
    fn round_trips_through_json() {
        let id = TaskId::new();
        let json = serde_json::to_string(&id).expect("serialize");
        assert_eq!(json, format!("\"{id}\""));
        assert_eq!(
            serde_json::from_str::<TaskId>(&json).expect("deserialize"),
            id
        );
    }
}
