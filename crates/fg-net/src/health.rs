//! Source health tracking and circuit breaking (§6.1).
//!
//! Three consecutive failures open a five-minute breaker for that origin;
//! while open, the source is ranked behind healthy candidates. RTT samples feed
//! the "auto" source preference and are persisted to `cache/source-health.json`
//! so a restart keeps what it learned.

use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use fg_core::Result;
use reqwest::Url;
use serde::{Deserialize, Serialize};

use crate::client::Client;

/// Failures in a row that open the breaker.
pub const FAILURE_THRESHOLD: u32 = 3;
/// Minimum interval between opportunistic health-file writes.
const PERSIST_INTERVAL_MS: u64 = 5_000;
/// How long a tripped breaker stays open.
pub const OPEN_DURATION: Duration = Duration::from_secs(5 * 60);

/// Per-origin health record.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct HealthEntry {
    /// Consecutive failures since the last success.
    pub failures: u32,
    /// Successful transfers since the file was created.
    pub successes: u64,
    /// Unix milliseconds until which the breaker is open.
    pub open_until_ms: Option<u64>,
    /// Last measured round-trip time in milliseconds.
    pub last_rtt_ms: Option<u64>,
    /// Unix milliseconds of the last update.
    pub updated_ms: u64,
}

impl HealthEntry {
    #[must_use]
    pub fn is_open(&self, now_ms: u64) -> bool {
        self.open_until_ms.is_some_and(|until| until > now_ms)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct HealthFile {
    #[serde(default = "default_schema")]
    schema_version: u32,
    #[serde(default)]
    sources: BTreeMap<String, HealthEntry>,
}

fn default_schema() -> u32 {
    1
}

impl Default for HealthFile {
    fn default() -> Self {
        Self {
            schema_version: default_schema(),
            sources: BTreeMap::new(),
        }
    }
}

/// Result of a single speed probe.
#[derive(Debug, Clone)]
pub struct Probe {
    pub url: Url,
    pub ok: bool,
    pub status: Option<u16>,
    pub rtt_ms: Option<u64>,
    pub detail: Option<String>,
}

/// Origin-scoped health registry. Cheap to share across tasks.
#[derive(Debug)]
pub struct HealthRegistry {
    path: Option<PathBuf>,
    state: Mutex<BTreeMap<String, HealthEntry>>,
    last_persist_ms: AtomicU64,
}

impl HealthRegistry {
    /// Loads persisted health data when `path` exists.
    #[must_use]
    pub fn new(path: Option<PathBuf>) -> Self {
        let sources = path
            .as_deref()
            .and_then(|path| std::fs::read_to_string(path).ok())
            .and_then(|text| serde_json::from_str::<HealthFile>(&text).ok())
            .map_or_else(BTreeMap::new, |file| file.sources);

        Self {
            path,
            state: Mutex::new(sources),
            last_persist_ms: AtomicU64::new(0),
        }
    }

    /// A registry that never touches disk.
    #[must_use]
    pub fn in_memory() -> Self {
        Self::new(None)
    }

    /// Records a successful transfer. Transfer duration is not a round-trip
    /// time, so it leaves [`HealthEntry::last_rtt_ms`] untouched.
    pub fn record_success(&self, url: &Url) {
        self.record_success_with_rtt(url, None);
    }

    /// Records a successful HEAD probe and its round-trip time.
    pub fn record_rtt(&self, url: &Url, rtt: Duration) {
        self.record_success_with_rtt(url, Some(rtt));
    }

    fn record_success_with_rtt(&self, url: &Url, rtt: Option<Duration>) {
        let origin = origin_of(url);
        let now = now_ms();
        let snapshot = {
            let mut state = self.state.lock().expect("health registry poisoned");
            let entry = state.entry(origin).or_default();
            entry.failures = 0;
            entry.successes += 1;
            entry.open_until_ms = None;
            if let Some(rtt) = rtt {
                entry.last_rtt_ms = Some(rtt.as_millis().min(u128::from(u64::MAX)) as u64);
            }
            entry.updated_ms = now;
            state.clone()
        };
        // Successes are frequent during a 4000-file install; persist at most
        // every few seconds instead of writing the file thousands of times.
        self.persist(&snapshot, false);
    }

    /// Records a failed attempt, opening the breaker after three in a row.
    pub fn record_failure(&self, url: &Url) {
        self.record_failure_at(url, now_ms());
    }

    fn record_failure_at(&self, url: &Url, now: u64) {
        let origin = origin_of(url);
        let snapshot = {
            let mut state = self.state.lock().expect("health registry poisoned");
            let entry = state.entry(origin).or_default();
            entry.failures += 1;
            entry.updated_ms = now;
            if entry.failures >= FAILURE_THRESHOLD {
                entry.open_until_ms =
                    Some(now + OPEN_DURATION.as_millis().min(u128::from(u64::MAX)) as u64);
                // Start a fresh streak after the breaker expires.
                entry.failures = 0;
                tracing::warn!(
                    url = %url,
                    "download source circuit opened for five minutes"
                );
            }
            state.clone()
        };
        self.persist(&snapshot, true);
    }

    /// Whether the origin of `url` is currently circuit-broken.
    #[must_use]
    pub fn is_open(&self, url: &Url) -> bool {
        let origin = origin_of(url);
        let now = now_ms();
        self.state
            .lock()
            .expect("health registry poisoned")
            .get(&origin)
            .is_some_and(|entry| entry.is_open(now))
    }

    /// Stable-ranks candidates so open breakers sink to the end.
    pub fn rank(&self, candidates: &mut [String]) {
        let now = now_ms();
        let state = self.state.lock().expect("health registry poisoned");

        candidates.sort_by_key(|candidate| {
            let Some(origin) = Url::parse(candidate).ok().map(|url| origin_of(&url)) else {
                return 0;
            };
            match state.get(&origin) {
                Some(entry) if entry.is_open(now) => 2,
                Some(entry) if entry.failures > 0 => 1,
                _ => 0,
            }
        });
    }

    /// Snapshot of every known origin, sorted by key.
    #[must_use]
    pub fn entries(&self) -> Vec<(String, HealthEntry)> {
        self.state
            .lock()
            .expect("health registry poisoned")
            .iter()
            .map(|(origin, entry)| (origin.clone(), entry.clone()))
            .collect()
    }

    /// Writes the registry to disk, if a path was configured.
    pub fn save(&self) -> Result<()> {
        let snapshot = self.state.lock().expect("health registry poisoned").clone();
        self.persist(&snapshot, true);
        Ok(())
    }

    /// Probes each URL with a HEAD request and records the result.
    pub async fn probe(&self, client: &Client, urls: &[Url]) -> Vec<Probe> {
        let probes = urls.iter().cloned().map(|url| {
            let client = client.clone();
            async move {
                let started = Instant::now();
                let response =
                    tokio::time::timeout(Duration::from_secs(15), client.head(&url)).await;

                let probe = match response {
                    Ok(Ok(response)) => {
                        let status = response.status();
                        let ok = status.is_success() || status.is_redirection();
                        Probe {
                            url: url.clone(),
                            ok,
                            status: Some(status.as_u16()),
                            rtt_ms: Some(started.elapsed().as_millis() as u64),
                            detail: (!ok).then(|| format!("HTTP {status}")),
                        }
                    }
                    Ok(Err(error)) => Probe {
                        url: url.clone(),
                        ok: false,
                        status: None,
                        rtt_ms: Some(started.elapsed().as_millis() as u64),
                        detail: Some(error.to_string()),
                    },
                    Err(_) => Probe {
                        url: url.clone(),
                        ok: false,
                        status: None,
                        rtt_ms: None,
                        detail: Some("probe timed out".to_string()),
                    },
                };

                if probe.ok {
                    self.record_rtt(&probe.url, Duration::from_millis(probe.rtt_ms.unwrap_or(0)));
                } else {
                    self.record_failure(&probe.url);
                }
                probe
            }
        });

        futures_util::future::join_all(probes).await
    }

    fn persist(&self, sources: &BTreeMap<String, HealthEntry>, force: bool) {
        let Some(path) = &self.path else {
            return;
        };

        let now = now_ms();
        if !force {
            let last = self.last_persist_ms.load(Ordering::Relaxed);
            if now.saturating_sub(last) < PERSIST_INTERVAL_MS {
                return;
            }
        }
        self.last_persist_ms.store(now, Ordering::Relaxed);

        let file = HealthFile {
            schema_version: default_schema(),
            sources: sources.clone(),
        };
        let Ok(encoded) = serde_json::to_vec_pretty(&file) else {
            tracing::warn!("cannot encode source health");
            return;
        };

        if let Err(error) = write_sync(path, &encoded) {
            tracing::warn!(
                path = %path.display(),
                detail = %error,
                "cannot persist source health"
            );
        }
    }
}

fn write_sync(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let temporary = path.with_extension("json.tmp");
    std::fs::write(&temporary, bytes)?;

    match std::fs::rename(&temporary, path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists || cfg!(windows) => {
            let _ = std::fs::remove_file(path);
            std::fs::rename(&temporary, path)
        }
        Err(error) => Err(error),
    }
}

/// Scheme, host and port identity of a URL.
#[must_use]
pub fn origin_of(url: &Url) -> String {
    match (url.host_str(), url.port()) {
        (Some(host), Some(port)) => format!("{}://{host}:{port}", url.scheme()),
        (Some(host), None) => format!("{}://{host}", url.scheme()),
        (None, _) => format!("{}://unknown", url.scheme()),
    }
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn url(value: &str) -> Url {
        Url::parse(value).expect("valid url")
    }

    #[test]
    fn origins_include_ports_only_when_present() {
        assert_eq!(
            origin_of(&url("https://example.com/a")),
            "https://example.com"
        );
        assert_eq!(
            origin_of(&url("http://127.0.0.1:8080/a")),
            "http://127.0.0.1:8080"
        );
    }

    #[test]
    fn three_failures_open_the_breaker_for_five_minutes() {
        let registry = HealthRegistry::in_memory();
        let target = url("https://cdn.example.com/file.bin");
        let now = now_ms();

        registry.record_failure_at(&target, now);
        registry.record_failure_at(&target, now);
        assert!(!registry.is_open(&target));

        registry.record_failure_at(&target, now);
        assert!(registry.is_open(&target));

        let entry = registry.entries().pop().expect("entry").1;
        assert_eq!(
            entry.open_until_ms,
            Some(now + OPEN_DURATION.as_millis() as u64)
        );
        assert_eq!(entry.failures, 0);
    }

    #[test]
    fn a_success_resets_the_streak_and_closes_the_breaker() {
        let registry = HealthRegistry::in_memory();
        let target = url("https://cdn.example.com/file.bin");

        let now = now_ms();
        registry.record_failure_at(&target, now);
        registry.record_failure_at(&target, now);
        registry.record_failure_at(&target, now);
        assert!(registry.is_open(&target));

        registry.record_rtt(&target, Duration::from_millis(120));
        assert!(!registry.is_open(&target));

        let entry = registry.entries().pop().expect("entry").1;
        assert_eq!(entry.failures, 0);
        assert_eq!(entry.successes, 1);
        assert_eq!(entry.last_rtt_ms, Some(120));

        // A plain download success must not overwrite the measured RTT.
        registry.record_success(&target);
        let entry = registry.entries().pop().expect("entry").1;
        assert_eq!(entry.successes, 2);
        assert_eq!(entry.last_rtt_ms, Some(120));
    }

    #[test]
    fn ranking_moves_open_breakers_last_and_keeps_stable_order() {
        let registry = HealthRegistry::in_memory();
        let now = now_ms();
        let broken = url("https://broken.example.com/a");
        let flaky = url("https://flaky.example.com/a");
        let healthy = url("https://healthy.example.com/a");

        registry.record_failure_at(&broken, now);
        registry.record_failure_at(&broken, now);
        registry.record_failure_at(&broken, now);
        registry.record_failure_at(&flaky, now);

        let mut candidates = vec![broken.to_string(), healthy.to_string(), flaky.to_string()];
        registry.rank(&mut candidates);

        assert_eq!(candidates[0], healthy.to_string());
        assert_eq!(candidates[1], flaky.to_string());
        assert_eq!(candidates[2], broken.to_string());
    }

    #[test]
    fn health_survives_a_restart() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("cache").join("source-health.json");
        let target = url("https://example.com/file");

        {
            let registry = HealthRegistry::new(Some(path.clone()));
            registry.record_rtt(&target, Duration::from_millis(88));
        }

        let reloaded = HealthRegistry::new(Some(path));
        let entry = reloaded.entries().pop().expect("entry").1;
        assert_eq!(entry.successes, 1);
        assert_eq!(entry.last_rtt_ms, Some(88));
    }
}
