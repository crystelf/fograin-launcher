//! Progress reporting for downloads.
//!
//! The engine only needs a byte counter and an optional total; batching and
//! UI-facing throttling belong to `fg-task` (§3.1, §6.2).

use std::sync::{
    Arc,
    atomic::{AtomicU64, Ordering},
};

use tokio_util::sync::CancellationToken;

/// Receives download progress. Implementations must be cheap: the engine calls
/// them once per network chunk.
pub trait ProgressSink: Send + Sync {
    /// Called once when the total size becomes known.
    fn on_total(&self, _total: Option<u64>) {}

    /// Called after `bytes` more payload bytes were written.
    fn on_bytes(&self, _bytes: u64) {}

    /// Called before the next retry of the current candidate.
    fn on_retry(&self, _attempt: u32, _url: &str) {}
}

/// Ignores all progress events.
#[derive(Debug, Default, Clone, Copy)]
pub struct NoopProgress;

impl ProgressSink for NoopProgress {}

/// Atomic byte counter, useful for tests and the CLI.
#[derive(Debug)]
pub struct AtomicProgress {
    total: AtomicU64,
    done: AtomicU64,
}

impl Default for AtomicProgress {
    fn default() -> Self {
        Self {
            total: AtomicU64::new(u64::MAX),
            done: AtomicU64::new(0),
        }
    }
}

impl AtomicProgress {
    #[must_use]
    pub fn total(&self) -> Option<u64> {
        match self.total.load(Ordering::Relaxed) {
            u64::MAX => None,
            total => Some(total),
        }
    }

    #[must_use]
    pub fn done(&self) -> u64 {
        self.done.load(Ordering::Relaxed)
    }
}

impl ProgressSink for AtomicProgress {
    fn on_total(&self, total: Option<u64>) {
        self.total
            .store(total.unwrap_or(u64::MAX), Ordering::Relaxed);
    }

    fn on_bytes(&self, bytes: u64) {
        self.done.fetch_add(bytes, Ordering::Relaxed);
    }
}

/// Per-download cancellation and progress handle.
#[derive(Clone)]
pub struct DownloadCtx {
    pub cancel: CancellationToken,
    pub progress: Arc<dyn ProgressSink>,
}

impl Default for DownloadCtx {
    fn default() -> Self {
        Self {
            cancel: CancellationToken::new(),
            progress: Arc::new(NoopProgress),
        }
    }
}

impl DownloadCtx {
    /// A context that reports into `progress`.
    #[must_use]
    pub fn with_progress(progress: Arc<dyn ProgressSink>) -> Self {
        Self {
            cancel: CancellationToken::new(),
            progress,
        }
    }

    #[must_use]
    pub fn is_cancelled(&self) -> bool {
        self.cancel.is_cancelled()
    }
}

impl std::fmt::Debug for DownloadCtx {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("DownloadCtx")
            .field("cancelled", &self.cancel.is_cancelled())
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn atomic_progress_tracks_totals() {
        let progress = AtomicProgress::default();
        assert_eq!(progress.total(), None);
        assert_eq!(progress.done(), 0);

        progress.on_total(Some(1024));
        progress.on_bytes(512);
        progress.on_bytes(512);

        assert_eq!(progress.total(), Some(1024));
        assert_eq!(progress.done(), 1024);
    }

    #[test]
    fn download_context_defaults_to_a_live_token() {
        let ctx = DownloadCtx::default();
        assert!(!ctx.is_cancelled());
        ctx.cancel.cancel();
        assert!(ctx.is_cancelled());
    }
}
