//! HTTP client, mirror rules, download engine and caches for Fograin Launcher (§6).
//!
//! The crate is deliberately independent of the UI clients and of `fg-task`: it
//! exposes a small async surface ([`Downloader`], [`Client`]) and a
//! [`ProgressSink`] trait, so the task graph can wrap downloads without the
//! engine knowing about task scheduling.
//!
//! Layers, from the bottom up:
//!
//! - [`client`] — one pooled `reqwest` client (rustls, HTTP/2, UA, proxy).
//! - [`mirrors`] — data-driven prefix rules that expand one URL into an ordered
//!   candidate list, honouring the user's source preference and region.
//! - [`engine`] — retries, per-host concurrency, rate limiting, integrity
//!   checks, resumable and segmented downloads.
//! - [`cache`] — content-addressed SHA-1 storage and HTTP conditional cache.
//! - [`health`] — source RTT probing and the 3-failure / 5-minute breaker.

pub mod cache;
pub mod client;
pub mod engine;
pub mod error;
pub mod hash;
pub mod health;
pub mod limiter;
pub mod mirrors;
pub mod progress;
pub mod region;

mod fsutil;

pub use cache::{CachedMeta, ContentCache, HttpCache};
pub use client::{Client, ClientBuilder, ProxyMode};
pub use engine::{
    BatchReport, CachePolicy, Candidate, DownloadOptions, DownloadOutcome, DownloadRequest,
    Downloader, DownloaderBuilder, Integrity, Priority,
};
/// Re-exported so callers and integration tests share the exact error type.
pub use fg_core;
pub use fg_core::DownloadSource;
pub use hash::HashAlgorithm;
pub use health::{HealthEntry, HealthRegistry, Probe};
pub use mirrors::{MirrorRule, MirrorTable};
pub use progress::{AtomicProgress, DownloadCtx, NoopProgress, ProgressSink};
pub use region::Region;
/// Re-exported URL type used by candidates, outcomes and health probes.
pub use reqwest::Url;
