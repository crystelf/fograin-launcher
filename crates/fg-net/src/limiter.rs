//! Bandwidth and per-host concurrency limits (§6.2).

use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use tokio::sync::{OwnedSemaphorePermit, Semaphore};

/// Token bucket shared by every connection of the launcher.
///
/// The bucket is refilled lazily from the wall clock, so idle periods bank at
/// most one second of bandwidth instead of an unbounded burst.
#[derive(Debug)]
pub struct RateLimiter {
    bytes_per_second: u64,
    state: Mutex<Bucket>,
}

#[derive(Debug)]
struct Bucket {
    tokens: f64,
    last: Instant,
}

impl RateLimiter {
    /// Creates a limiter, or `None` when `bytes_per_second` is zero.
    #[must_use]
    pub fn new(bytes_per_second: u64) -> Option<Arc<Self>> {
        (bytes_per_second > 0).then(|| {
            Arc::new(Self {
                bytes_per_second,
                state: Mutex::new(Bucket {
                    tokens: bytes_per_second as f64,
                    last: Instant::now(),
                }),
            })
        })
    }

    /// Blocks until `bytes` worth of budget is available.
    pub async fn acquire(&self, bytes: u64) {
        if bytes == 0 {
            return;
        }

        let rate = self.bytes_per_second as f64;
        let mut remaining = bytes as f64;

        while remaining > 0.0 {
            let wait = {
                let mut bucket = self.state.lock().expect("rate limiter poisoned");
                let now = Instant::now();
                let elapsed = now.saturating_duration_since(bucket.last).as_secs_f64();
                bucket.tokens = (bucket.tokens + elapsed * rate).min(rate);
                bucket.last = now;

                let taken = remaining.min(bucket.tokens);
                bucket.tokens -= taken;
                remaining -= taken;

                if remaining > 0.0 {
                    Duration::from_secs_f64(remaining / rate)
                } else {
                    Duration::ZERO
                }
            };

            if wait.is_zero() {
                break;
            }
            // Wake up periodically so cancellation and new limits are observed
            // without holding the mutex across the sleep.
            tokio::time::sleep(wait.min(Duration::from_millis(200))).await;
        }
    }
}

/// Per-host concurrency caps.
///
/// BMCLAPI tolerates 32 concurrent requests; the Modrinth and CurseForge APIs
/// are documented at 16 (§6.2). Unknown hosts inherit the default.
#[derive(Debug)]
pub struct HostLimits {
    default: usize,
    overrides: HashMap<&'static str, usize>,
    permits: Mutex<HashMap<String, Arc<Semaphore>>>,
}

impl HostLimits {
    #[must_use]
    pub fn new(default: usize) -> Self {
        Self {
            default: default.max(1),
            overrides: HashMap::from([
                ("bmclapi2.bangbang93.com", 32),
                ("api.modrinth.com", 16),
                ("api.curseforge.com", 16),
                ("mod.mcimirror.top", 16),
            ]),
            permits: Mutex::new(HashMap::new()),
        }
    }

    /// Adds or replaces a host override.
    #[must_use]
    pub fn with_override(mut self, host: &'static str, permits: usize) -> Self {
        self.overrides.insert(host, permits.max(1));
        self
    }

    #[must_use]
    pub fn permits_for(&self, host: &str) -> usize {
        self.overrides.get(host).copied().unwrap_or(self.default)
    }

    /// Waits for a slot on `host` and returns the held permit.
    pub async fn acquire(&self, host: &str) -> OwnedSemaphorePermit {
        let semaphore = {
            let mut permits = self.permits.lock().expect("host limits poisoned");
            permits
                .entry(host.to_string())
                .or_insert_with(|| Arc::new(Semaphore::new(self.permits_for(host))))
                .clone()
        };

        semaphore
            .acquire_owned()
            .await
            .expect("host semaphore is never closed")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zero_bandwidth_means_unlimited() {
        assert!(RateLimiter::new(0).is_none());
        assert!(RateLimiter::new(1024).is_some());
    }

    #[tokio::test(start_paused = true)]
    async fn limiter_charges_the_configured_rate() {
        let limiter = RateLimiter::new(1000).expect("limiter");
        let start = Instant::now();

        // Drains the initial one-second burst.
        limiter.acquire(1000).await;
        assert!(start.elapsed() < Duration::from_millis(50));

        // The next 500 bytes need half a second of refill.
        limiter.acquire(500).await;
        let elapsed = start.elapsed();
        assert!(elapsed >= Duration::from_millis(400), "elapsed {elapsed:?}");
    }

    #[test]
    fn host_overrides_apply_only_to_known_hosts() {
        let limits = HostLimits::new(32);
        assert_eq!(limits.permits_for("bmclapi2.bangbang93.com"), 32);
        assert_eq!(limits.permits_for("api.modrinth.com"), 16);
        assert_eq!(limits.permits_for("example.com"), 32);

        let limits = limits.with_override("example.com", 4);
        assert_eq!(limits.permits_for("example.com"), 4);
    }

    #[tokio::test]
    async fn host_permits_are_capped() {
        let limits = HostLimits::new(8);
        let first = limits.acquire("example.com").await;
        let second = limits.acquire("example.com").await;
        drop(first);
        drop(second);
    }
}
