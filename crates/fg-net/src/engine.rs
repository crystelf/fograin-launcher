//! The download engine (§6.2).
//!
//! One request carries an ordered candidate list. The engine:
//!
//! 1. short-circuits when the destination already matches its checksum,
//! 2. reuses the content-addressed cache,
//! 3. tries candidates in health-ranked order with per-candidate retries and
//!    `200ms × 2ⁿ` backoff (capped at 5s),
//! 4. resumes `dest.part` through `Range`/`If-Range` when the validator matches,
//! 5. splits files above the segment threshold into parallel range requests,
//! 6. verifies the digest while streaming and only then renames onto `dest`.

use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use fg_core::{DownloadConfig, Error, ErrorKind, Result};
use futures_util::StreamExt;
use reqwest::{
    Method, StatusCode, Url,
    header::{self, HeaderMap, HeaderValue},
};
use serde::{Deserialize, Serialize};
use tokio::sync::OwnedSemaphorePermit;
use tokio::{
    fs::{File, OpenOptions},
    io::{AsyncWriteExt, BufWriter},
    sync::Semaphore,
    time::sleep,
};

use crate::{
    cache::{ContentCache, HttpCache},
    client::Client,
    error::{AttemptError, cancelled, from_reqwest, no_candidates},
    fsutil::{remove_if_exists, replace_file, temp_path},
    hash::{HashAlgorithm, Hasher, feed_file, hash_file},
    health::HealthRegistry,
    limiter::{HostLimits, RateLimiter},
    progress::DownloadCtx,
};

/// Read buffer used for every file write (§6.2: 64 KiB).
const WRITE_BUFFER: usize = 64 * 1024;

/// Integrity claim attached to a download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Integrity {
    Sha1(String),
    Sha256(String),
    Sha512(String),
    Size(u64),
}

impl Integrity {
    /// Creates a SHA-1 claim.
    #[must_use]
    pub fn sha1(hex: impl Into<String>) -> Self {
        Self::Sha1(hex.into())
    }

    /// Creates a SHA-256 claim.
    #[must_use]
    pub fn sha256(hex: impl Into<String>) -> Self {
        Self::Sha256(hex.into())
    }

    /// Creates a SHA-512 claim.
    #[must_use]
    pub fn sha512(hex: impl Into<String>) -> Self {
        Self::Sha512(hex.into())
    }

    /// The hash algorithm, if this is a hash claim.
    #[must_use]
    pub fn algorithm(&self) -> Option<HashAlgorithm> {
        match self {
            Self::Sha1(_) => Some(HashAlgorithm::Sha1),
            Self::Sha256(_) => Some(HashAlgorithm::Sha256),
            Self::Sha512(_) => Some(HashAlgorithm::Sha512),
            Self::Size(_) => None,
        }
    }

    /// Expected digest or size.
    #[must_use]
    pub fn expected(&self) -> String {
        match self {
            Self::Sha1(hex) | Self::Sha256(hex) | Self::Sha512(hex) => hex.to_ascii_lowercase(),
            Self::Size(size) => size.to_string(),
        }
    }

    /// The digest when this is a SHA-1 claim, which is what the cache keys on.
    #[must_use]
    pub fn sha1_hex(&self) -> Option<&str> {
        match self {
            Self::Sha1(hex) => Some(hex.as_str()),
            _ => None,
        }
    }

    /// Verifies a file against the claim.
    ///
    /// # Errors
    ///
    /// Propagates I/O failures while hashing.
    pub async fn verify(&self, path: &Path) -> Result<bool> {
        match self {
            Self::Size(size) => Ok(tokio::fs::metadata(path)
                .await
                .is_ok_and(|metadata| metadata.len() == *size)),
            Self::Sha1(hex) => Ok(hash_file(path, HashAlgorithm::Sha1)
                .await?
                .eq_ignore_ascii_case(hex)),
            Self::Sha256(hex) => Ok(hash_file(path, HashAlgorithm::Sha256)
                .await?
                .eq_ignore_ascii_case(hex)),
            Self::Sha512(hex) => Ok(hash_file(path, HashAlgorithm::Sha512)
                .await?
                .eq_ignore_ascii_case(hex)),
        }
    }
}

/// How the engine may reuse or populate caches.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum CachePolicy {
    /// Never consult the cache; still writes `dest`.
    #[default]
    None,
    /// Look up and store by SHA-1 in `cache/sha1` (§5.1).
    ContentAddressed,
    /// Conditional HTTP caching for manifests and API responses.
    HttpConditional,
}

/// Scheduling priority. Higher values are downloaded first (§6.2).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord)]
pub enum Priority {
    /// Regular content (mods, resource packs).
    #[default]
    Mod,
    /// Asset objects.
    Asset,
    /// Libraries and loader artifacts.
    Library,
    /// Version manifests and other small metadata.
    Manifest,
}

/// One source URL for a download.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    pub url: Url,
    /// Overrides the engine default when set.
    pub retries: Option<u32>,
    pub connect_timeout: Option<Duration>,
    pub read_timeout: Option<Duration>,
}

impl Candidate {
    #[must_use]
    pub fn new(url: Url) -> Self {
        Self {
            url,
            retries: None,
            connect_timeout: None,
            read_timeout: None,
        }
    }

    /// Parses a candidate URL.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for malformed URLs.
    pub fn parse(url: &str) -> Result<Self> {
        let url = Url::parse(url).map_err(|error| {
            Error::with_detail(ErrorKind::InvalidInput, "error.net.url", error.to_string())
        })?;
        Ok(Self::new(url))
    }

    #[must_use]
    pub fn with_retries(mut self, retries: u32) -> Self {
        self.retries = Some(retries);
        self
    }

    #[must_use]
    pub fn with_timeouts(mut self, connect: Duration, read: Duration) -> Self {
        self.connect_timeout = Some(connect);
        self.read_timeout = Some(read);
        self
    }
}

impl From<Url> for Candidate {
    fn from(url: Url) -> Self {
        Self::new(url)
    }
}

/// Everything needed to download one file.
#[derive(Debug, Clone)]
pub struct DownloadRequest {
    pub candidates: Vec<Candidate>,
    pub dest: PathBuf,
    pub integrity: Option<Integrity>,
    pub size_hint: Option<u64>,
    pub cache: CachePolicy,
    pub priority: Priority,
}

impl DownloadRequest {
    #[must_use]
    pub fn new(dest: impl Into<PathBuf>, candidates: Vec<Candidate>) -> Self {
        Self {
            candidates,
            dest: dest.into(),
            integrity: None,
            size_hint: None,
            cache: CachePolicy::None,
            priority: Priority::default(),
        }
    }

    /// Builds a request from a single URL.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for malformed URLs.
    pub fn from_url(dest: impl Into<PathBuf>, url: &str) -> Result<Self> {
        Ok(Self::new(dest, vec![Candidate::parse(url)?]))
    }

    #[must_use]
    pub fn with_integrity(mut self, integrity: Integrity) -> Self {
        self.integrity = Some(integrity);
        self
    }

    /// Sets a SHA-1 claim and enables the content-addressed cache.
    #[must_use]
    pub fn with_sha1(mut self, hex: impl Into<String>) -> Self {
        self.integrity = Some(Integrity::sha1(hex));
        self.cache = CachePolicy::ContentAddressed;
        self
    }

    #[must_use]
    pub fn with_sha256(mut self, hex: impl Into<String>) -> Self {
        self.integrity = Some(Integrity::sha256(hex));
        self
    }

    #[must_use]
    pub fn with_size(mut self, size: u64) -> Self {
        self.integrity = Some(Integrity::Size(size));
        self
    }

    #[must_use]
    pub fn with_size_hint(mut self, size: u64) -> Self {
        self.size_hint = Some(size);
        self
    }

    #[must_use]
    pub fn with_cache(mut self, cache: CachePolicy) -> Self {
        self.cache = cache;
        self
    }

    #[must_use]
    pub fn with_priority(mut self, priority: Priority) -> Self {
        self.priority = priority;
        self
    }
}

/// Engine tuning, usually derived from `[download]` in `launcher.toml`.
#[derive(Debug, Clone)]
pub struct DownloadOptions {
    /// Attempts per candidate URL.
    pub retries: u32,
    /// First retry delay.
    pub backoff_base: Duration,
    /// Upper bound for retry delays.
    pub backoff_cap: Duration,
    /// Connect timeout when a candidate does not override it.
    pub connect_timeout: Duration,
    /// Read (stall) timeout when a candidate does not override it.
    pub read_timeout: Duration,
    /// Parallel range requests per file.
    pub segments: usize,
    /// Files at least this large are segmented.
    pub segment_threshold: u64,
    /// Set to `false` to disable segmentation entirely.
    pub enable_segments: bool,
}

impl Default for DownloadOptions {
    fn default() -> Self {
        Self {
            retries: 5,
            backoff_base: Duration::from_millis(200),
            backoff_cap: Duration::from_secs(5),
            connect_timeout: Duration::from_secs(10),
            read_timeout: Duration::from_secs(10),
            segments: 4,
            segment_threshold: 16 * 1024 * 1024,
            enable_segments: true,
        }
    }
}

impl DownloadOptions {
    /// Applies `[download]` and `[network]` from `launcher.toml`.
    #[must_use]
    pub fn from_config(download: &DownloadConfig, network: &fg_core::NetworkConfig) -> Self {
        let threshold = download.segment_threshold();
        let segments = download.effective_segments();
        Self {
            retries: download.retries,
            connect_timeout: network.timeout(),
            read_timeout: network.timeout(),
            segments,
            segment_threshold: threshold,
            enable_segments: threshold > 0 && segments > 1,
            ..Self::default()
        }
    }
}

/// Result of one completed download.
#[derive(Debug, Clone)]
pub struct DownloadOutcome {
    pub dest: PathBuf,
    pub bytes: u64,
    /// `true` when the content-addressed cache satisfied the request.
    pub from_cache: bool,
    /// Candidate that served the bytes; `None` for cache hits and skipped files.
    pub source: Option<Url>,
}

/// Aggregate result for a batch of downloads.
#[derive(Debug, Default)]
pub struct BatchReport {
    pub succeeded: usize,
    pub failed: Vec<(PathBuf, Error)>,
    pub bytes: u64,
}

impl BatchReport {
    #[must_use]
    pub fn is_success(&self) -> bool {
        self.failed.is_empty()
    }
}

/// Builds a [`Downloader`].
#[derive(Debug)]
pub struct DownloaderBuilder {
    root: PathBuf,
    client: Option<Client>,
    options: DownloadOptions,
    concurrency: usize,
    per_host: usize,
    rate_limit: u64,
}

impl DownloaderBuilder {
    #[must_use]
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self {
            root: root.into(),
            client: None,
            options: DownloadOptions::default(),
            concurrency: DownloadConfig::default().effective_concurrency(),
            per_host: 32,
            rate_limit: 0,
        }
    }

    #[must_use]
    pub fn client(mut self, client: Client) -> Self {
        self.client = Some(client);
        self
    }

    #[must_use]
    pub fn options(mut self, options: DownloadOptions) -> Self {
        self.options = options;
        self
    }

    #[must_use]
    pub fn concurrency(mut self, concurrency: usize) -> Self {
        self.concurrency = concurrency.max(1);
        self
    }

    #[must_use]
    pub fn per_host(mut self, per_host: usize) -> Self {
        self.per_host = per_host.max(1);
        self
    }

    #[must_use]
    pub fn rate_limit_bytes_per_sec(mut self, bytes_per_second: u64) -> Self {
        self.rate_limit = bytes_per_second;
        self
    }

    /// Builds the engine.
    ///
    /// # Errors
    ///
    /// Propagates client construction failures.
    pub fn build(self) -> Result<Downloader> {
        let client = match self.client {
            Some(client) => client,
            None => Client::default_client()?,
        };

        let cache_root = self.root.join("cache");
        Ok(Downloader {
            client,
            cache: ContentCache::new(&cache_root),
            http_cache: HttpCache::new(&cache_root),
            health: HealthRegistry::new(Some(cache_root.join("source-health.json"))),
            options: self.options,
            global: Arc::new(Semaphore::new(self.concurrency)),
            hosts: Arc::new(HostLimits::new(self.per_host)),
            rate: RateLimiter::new(self.rate_limit),
            concurrency: self.concurrency,
        })
    }
}

/// Download engine shared by installation tasks.
#[derive(Debug)]
pub struct Downloader {
    client: Client,
    cache: ContentCache,
    http_cache: HttpCache,
    health: HealthRegistry,
    options: DownloadOptions,
    global: Arc<Semaphore>,
    hosts: Arc<HostLimits>,
    rate: Option<Arc<RateLimiter>>,
    concurrency: usize,
}

impl Downloader {
    /// Creates an engine with defaults under `root`.
    ///
    /// # Errors
    ///
    /// Propagates client construction failures.
    pub fn new(root: impl Into<PathBuf>) -> Result<Self> {
        DownloaderBuilder::new(root).build()
    }

    /// Creates a builder rooted at `root`.
    #[must_use]
    pub fn builder(root: impl Into<PathBuf>) -> DownloaderBuilder {
        DownloaderBuilder::new(root)
    }

    #[must_use]
    pub fn client(&self) -> &Client {
        &self.client
    }

    #[must_use]
    pub fn health(&self) -> &HealthRegistry {
        &self.health
    }

    #[must_use]
    pub fn content_cache(&self) -> &ContentCache {
        &self.cache
    }

    #[must_use]
    pub fn http_cache(&self) -> &HttpCache {
        &self.http_cache
    }

    #[must_use]
    pub fn options(&self) -> &DownloadOptions {
        &self.options
    }

    /// Downloads one file with no progress reporting.
    ///
    /// # Errors
    ///
    /// Returns the aggregated failure when every candidate fails.
    pub async fn download(&self, request: DownloadRequest) -> Result<DownloadOutcome> {
        self.download_with(request, DownloadCtx::default()).await
    }

    /// Downloads one file, honouring cancellation and progress sinks.
    ///
    /// # Errors
    ///
    /// - [`ErrorKind::Cancelled`] when the context is cancelled.
    /// - [`ErrorKind::Network`] when every candidate fails.
    pub async fn download_with(
        &self,
        request: DownloadRequest,
        ctx: DownloadCtx,
    ) -> Result<DownloadOutcome> {
        if request.candidates.is_empty() {
            return Err(no_candidates(&request.dest));
        }
        if ctx.is_cancelled() {
            return Err(cancelled());
        }

        if let Some(outcome) = self.already_complete(&request).await? {
            return Ok(outcome);
        }

        if request.cache == CachePolicy::ContentAddressed
            && let Some(sha1) = request.integrity.as_ref().and_then(Integrity::sha1_hex)
            && self.cache.materialize(sha1, &request.dest).await?
        {
            tracing::debug!(dest = %request.dest.display(), "served from content cache");
            let bytes = tokio::fs::metadata(&request.dest)
                .await
                .map_or(0, |metadata| metadata.len());
            return Ok(DownloadOutcome {
                dest: request.dest.clone(),
                bytes,
                from_cache: true,
                source: None,
            });
        }

        let mut candidates = request.candidates.clone();
        self.rank_candidates(&mut candidates);

        let mut failures: Vec<String> = Vec::with_capacity(candidates.len());
        for candidate in &candidates {
            if ctx.is_cancelled() {
                return Err(cancelled());
            }

            match self.download_from(candidate, &request, &ctx).await {
                Ok(outcome) => {
                    self.health.record_success(&candidate.url);
                    return Ok(outcome);
                }
                Err(AttemptError::Cancelled) => return Err(cancelled()),
                Err(error) => {
                    self.health.record_failure(&candidate.url);
                    failures.push(format!("{}: {}", candidate.url, error.detail()));
                }
            }
        }

        Err(Error::with_detail(
            ErrorKind::Network,
            "error.net.all_candidates",
            format!("{} — {}", request.dest.display(), failures.join(" | ")),
        ))
    }

    /// Downloads many files, highest priority first, with bounded concurrency.
    ///
    /// # Errors
    ///
    /// Individual failures are collected in the report rather than returned.
    pub async fn download_many(&self, requests: Vec<DownloadRequest>) -> BatchReport {
        let items = requests
            .into_iter()
            .map(|request| (request, DownloadCtx::default()))
            .collect();
        self.download_many_with(items).await
    }

    /// Downloads many files with per-file contexts.
    pub async fn download_many_with(
        &self,
        items: Vec<(DownloadRequest, DownloadCtx)>,
    ) -> BatchReport {
        let mut items = items;
        items.sort_by_key(|(request, _)| std::cmp::Reverse(request.priority));

        let concurrency = self.concurrency.max(1);
        let mut report = BatchReport::default();

        let stream = futures_util::stream::iter(items)
            .map(|(request, ctx)| async move {
                let dest = request.dest.clone();
                (dest, self.download_with(request, ctx).await)
            })
            .buffer_unordered(concurrency);

        futures_util::pin_mut!(stream);
        while let Some((dest, result)) = stream.next().await {
            match result {
                Ok(outcome) => {
                    report.succeeded += 1;
                    report.bytes += outcome.bytes;
                }
                Err(error) => report.failed.push((dest, error)),
            }
        }

        if let Err(error) = self.health.save() {
            tracing::warn!(detail = %error, "cannot persist source health");
        }

        report
    }

    /// Fetches a metadata body through the conditional cache, trying each URL.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] when every URL fails.
    pub async fn fetch_bytes(&self, urls: Vec<String>, cache_key: &str) -> Result<FetchedBytes> {
        if urls.is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.net.no_candidates",
            ));
        }

        let mut failures: Vec<String> = Vec::with_capacity(urls.len());
        for raw in urls {
            let url = Url::parse(&raw).map_err(|error| {
                Error::with_detail(ErrorKind::InvalidInput, "error.net.url", error.to_string())
            })?;
            match self
                .client
                .fetch_cached(&self.http_cache, cache_key, &url)
                .await
            {
                Ok(response) => {
                    self.health.record_success(&url);
                    return Ok(FetchedBytes {
                        bytes: response.bytes,
                        url,
                        from_cache: response.from_cache,
                    });
                }
                Err(error) => {
                    self.health.record_failure(&url);
                    failures.push(format!("{url}: {error}"));
                }
            }
        }

        Err(Error::with_detail(
            ErrorKind::Network,
            "error.net.all_candidates",
            failures.join(" | "),
        ))
    }

    /// Fetches and parses JSON from the first reachable URL.
    ///
    /// # Errors
    ///
    /// Returns the network error, or [`ErrorKind::Parse`] for invalid JSON.
    pub async fn fetch_json<T: serde::de::DeserializeOwned>(
        &self,
        urls: Vec<String>,
        cache_key: &str,
    ) -> Result<T> {
        let fetched = self.fetch_bytes(urls, cache_key).await?;
        serde_json::from_slice(&fetched.bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.net.response_json",
                format!("{}: {error}", fetched.url),
            )
        })
    }

    /// Measures RTT and success for each URL, updating the breaker state.
    pub async fn probe_sources(&self, urls: &[Url]) -> Vec<crate::health::Probe> {
        self.health.probe(&self.client, urls).await
    }

    async fn already_complete(&self, request: &DownloadRequest) -> Result<Option<DownloadOutcome>> {
        let Ok(metadata) = tokio::fs::metadata(&request.dest).await else {
            return Ok(None);
        };
        if !metadata.is_file() {
            return Ok(None);
        }

        let complete = match &request.integrity {
            Some(integrity) => {
                if integrity.verify(&request.dest).await? {
                    true
                } else {
                    // A corrupt destination must not survive; otherwise the
                    // cache would keep serving it back.
                    let _ = remove_if_exists(&request.dest).await;
                    false
                }
            }
            None => request.size_hint.is_some_and(|size| size == metadata.len()),
        };

        Ok(complete.then(|| DownloadOutcome {
            dest: request.dest.clone(),
            bytes: metadata.len(),
            from_cache: false,
            source: None,
        }))
    }

    fn rank_candidates(&self, candidates: &mut [Candidate]) {
        if candidates.len() < 2 {
            return;
        }

        let mut urls: Vec<String> = candidates
            .iter()
            .map(|candidate| candidate.url.to_string())
            .collect();
        self.health.rank(&mut urls);

        let order: HashMap<&str, usize> = urls
            .iter()
            .enumerate()
            .map(|(index, url)| (url.as_str(), index))
            .collect();

        candidates.sort_by_key(|candidate| {
            order
                .get(candidate.url.as_str())
                .copied()
                .unwrap_or(usize::MAX)
        });
    }

    async fn download_from(
        &self,
        candidate: &Candidate,
        request: &DownloadRequest,
        ctx: &DownloadCtx,
    ) -> std::result::Result<DownloadOutcome, AttemptError> {
        let retries = candidate.retries.unwrap_or(self.options.retries).min(20);
        let segmentable = self.is_segmentable(request);
        let mut last = AttemptError::Protocol("no attempt was made".to_string());

        for attempt in 0..=retries {
            if ctx.is_cancelled() {
                return Err(AttemptError::Cancelled);
            }

            let segmented = segmentable && !part_exists(&request.dest).await;
            let result = if segmented {
                self.segment_attempt(candidate, request, ctx).await
            } else {
                self.stream_attempt(candidate, request, ctx).await
            };

            match result {
                Ok(outcome) => return Ok(outcome),
                Err(AttemptError::Cancelled) => return Err(AttemptError::Cancelled),
                Err(AttemptError::RangeUnsupported) if segmented => {
                    // The server ignored `Range`; fall back without spending a retry.
                    match self.stream_attempt(candidate, request, ctx).await {
                        Ok(outcome) => return Ok(outcome),
                        Err(AttemptError::Cancelled) => return Err(AttemptError::Cancelled),
                        Err(error) => last = error,
                    }
                }
                Err(error) => last = error,
            }

            if !last.retryable() || attempt == retries {
                break;
            }

            ctx.progress.on_retry(attempt + 1, candidate.url.as_str());
            let delay = backoff(attempt, self.options.backoff_base, self.options.backoff_cap);
            tracing::debug!(
                url = %candidate.url,
                attempt = attempt + 1,
                delay_ms = delay.as_millis(),
                cause = %last.detail(),
                "retrying download"
            );

            tokio::select! {
                () = sleep(delay) => {}
                () = ctx.cancel.cancelled() => return Err(AttemptError::Cancelled),
            }
        }

        Err(last)
    }

    fn is_segmentable(&self, request: &DownloadRequest) -> bool {
        self.options.enable_segments
            && self.options.segments > 1
            && request
                .size_hint
                .is_some_and(|size| size >= self.options.segment_threshold && size > 0)
    }

    async fn acquire_slot(
        &self,
        ctx: &DownloadCtx,
        host: &str,
    ) -> std::result::Result<(OwnedSemaphorePermit, OwnedSemaphorePermit), AttemptError> {
        let global = tokio::select! {
            permit = self.global.clone().acquire_owned() => {
                permit.map_err(|_| AttemptError::Cancelled)?
            }
            () = ctx.cancel.cancelled() => return Err(AttemptError::Cancelled),
        };
        let host = tokio::select! {
            permit = self.hosts.acquire(host) => permit,
            () = ctx.cancel.cancelled() => return Err(AttemptError::Cancelled),
        };
        Ok((global, host))
    }

    /// Single-stream (optionally resumed) download.
    async fn stream_attempt(
        &self,
        candidate: &Candidate,
        request: &DownloadRequest,
        ctx: &DownloadCtx,
    ) -> std::result::Result<DownloadOutcome, AttemptError> {
        let part = part_path(&request.dest);
        let meta_path = meta_path(&request.dest);
        let mut resume = load_resume(&part, &meta_path).await;

        if let Some(state) = &resume
            && state.url != candidate.url.as_str()
        {
            // Partial bytes belong to another mirror; restart rather than mix.
            remove_if_exists(&part)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?;
            remove_if_exists(&meta_path)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", meta_path.display())))?;
            resume = None;
        }

        if let Some(state) = &resume
            && state.total.is_some_and(|total| state.downloaded >= total)
        {
            // The file body is already on disk; only verification is missing.
            return self.finalize(candidate, request, ctx).await;
        }

        let resume_len = resume.as_ref().map_or(0, |state| state.downloaded);
        if resume_len > 0 {
            tracing::debug!(
                dest = %request.dest.display(),
                url = %candidate.url,
                downloaded = resume_len,
                "resuming partial download"
            );
        }
        let host = candidate.url.host_str().unwrap_or("unknown").to_string();
        let _slots = self.acquire_slot(ctx, &host).await?;

        let mut builder = self.client.request(Method::GET, &candidate.url).header(
            header::ACCEPT_ENCODING,
            HeaderValue::from_static("identity"),
        );

        if resume_len > 0 {
            builder = builder.header(header::RANGE, format!("bytes={resume_len}-"));
            if let Some(validator) = resume.as_ref().and_then(ResumeState::if_range) {
                builder = builder.header(header::IF_RANGE, validator);
            }
        }

        let connect_timeout = candidate
            .connect_timeout
            .unwrap_or(self.options.connect_timeout);
        let response = tokio::time::timeout(connect_timeout, builder.send())
            .await
            .map_err(|_| {
                AttemptError::Timeout(format!("connect timed out after {connect_timeout:?}"))
            })?
            .map_err(from_reqwest)?;

        let status = response.status();
        let headers = response.headers();

        if has_content_encoding(headers) {
            return Err(AttemptError::Protocol(
                "server compressed the body; byte-range resume is unsafe".to_string(),
            ));
        }

        let (total, resume_from) = match status {
            StatusCode::PARTIAL_CONTENT => {
                let (start, _end, total) = parse_content_range(headers)?;
                if start != resume_len {
                    discard_partial(&part, &meta_path).await;
                    return Err(AttemptError::Restart(format!(
                        "resume start {start} did not match {resume_len}"
                    )));
                }
                if let Some(state) = &resume
                    && state.total.is_some_and(|known| known != total)
                {
                    discard_partial(&part, &meta_path).await;
                    return Err(AttemptError::Restart(
                        "remote length changed while resuming".to_string(),
                    ));
                }
                if let (Some(state), Some(etag)) = (&resume, header_str(headers, header::ETAG))
                    && state.etag.as_deref() != Some(etag)
                {
                    discard_partial(&part, &meta_path).await;
                    return Err(AttemptError::Restart(
                        "ETag changed while resuming".to_string(),
                    ));
                }
                (Some(total), resume_len)
            }
            StatusCode::OK => {
                if resume_len > 0 {
                    // The server ignored `Range`; start over.
                    discard_partial(&part, &meta_path).await;
                }
                (response.content_length().or(request.size_hint), 0)
            }
            StatusCode::RANGE_NOT_SATISFIABLE => {
                discard_partial(&part, &meta_path).await;
                return Err(AttemptError::Restart(
                    "range not satisfiable; restarting".to_string(),
                ));
            }
            _ if status.is_success() => (response.content_length().or(request.size_hint), 0),
            _ => {
                return Err(AttemptError::Status {
                    status,
                    detail: candidate.url.to_string(),
                });
            }
        };

        let state = ResumeState {
            url: candidate.url.to_string(),
            etag: header_str(headers, header::ETAG).map(str::to_owned),
            last_modified: header_str(headers, header::LAST_MODIFIED).map(str::to_owned),
            total,
            downloaded: resume_from,
        };
        write_resume(&meta_path, &state).await?;

        let file = if resume_from > 0 {
            OpenOptions::new()
                .append(true)
                .open(&part)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?
        } else {
            File::create(&part)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?
        };

        let mut hasher = request
            .integrity
            .as_ref()
            .and_then(Integrity::algorithm)
            .map(Hasher::new);
        if resume_from > 0
            && let Some(hasher) = &mut hasher
        {
            feed_file(hasher, &part)
                .await
                .map_err(|error| AttemptError::Io(error.to_string()))?;
        }

        ctx.progress.on_total(total);

        let read_timeout = candidate.read_timeout.unwrap_or(self.options.read_timeout);
        let mut stream = response.bytes_stream();
        let mut writer = BufWriter::with_capacity(WRITE_BUFFER, file);
        let mut written = resume_from;
        let mut interrupted = None;

        loop {
            let next = match tokio::time::timeout(read_timeout, stream.next()).await {
                Ok(next) => next,
                Err(_) => {
                    interrupted = Some(AttemptError::Timeout(format!(
                        "read timed out after {read_timeout:?}"
                    )));
                    break;
                }
            };

            let Some(chunk) = next else {
                break;
            };
            let chunk = match chunk {
                Ok(chunk) => chunk,
                Err(error) => {
                    interrupted = Some(from_reqwest(error));
                    break;
                }
            };

            if ctx.is_cancelled() {
                interrupted = Some(AttemptError::Cancelled);
                break;
            }
            if let Some(rate) = &self.rate {
                rate.acquire(chunk.len() as u64).await;
            }

            if let Some(hasher) = &mut hasher {
                hasher.update(&chunk);
            }
            if let Err(error) = writer.write_all(&chunk).await {
                return Err(AttemptError::Io(format!("{}: {error}", part.display())));
            }

            written += chunk.len() as u64;
            ctx.progress.on_bytes(chunk.len() as u64);
        }

        // Flush even when the stream broke: without this the buffered tail is
        // lost and the next attempt cannot resume from where this one stopped.
        writer
            .flush()
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?;
        writer
            .get_ref()
            .sync_all()
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?;
        drop(writer);

        if let Some(error) = interrupted {
            return Err(error);
        }

        if let Some(total) = total
            && written != total
        {
            return Err(AttemptError::Timeout(format!(
                "incomplete body: {written} of {total} bytes"
            )));
        }

        let mut verified = false;
        if let Some(hasher) = hasher
            && let Some(integrity) = &request.integrity
        {
            if hasher.finish().eq_ignore_ascii_case(&integrity.expected()) {
                verified = true;
            } else {
                discard_partial(&part, &meta_path).await;
                return Err(AttemptError::Integrity(format!(
                    "checksum mismatch for {}",
                    request.dest.display()
                )));
            }
        }

        self.finalize_with_bytes(candidate, request, written, verified)
            .await
    }

    /// Segmented download for large files (§6.2).
    async fn segment_attempt(
        &self,
        candidate: &Candidate,
        request: &DownloadRequest,
        ctx: &DownloadCtx,
    ) -> std::result::Result<DownloadOutcome, AttemptError> {
        let total = request
            .size_hint
            .filter(|size| *size > 0)
            .ok_or(AttemptError::RangeUnsupported)?;

        let segments = self.options.segments.max(1);
        let chunk = total.div_ceil(segments as u64);

        tracing::debug!(
            dest = %request.dest.display(),
            url = %candidate.url,
            total,
            segments,
            "downloading in segments"
        );
        ctx.progress.on_total(Some(total));

        let mut ranges = Vec::with_capacity(segments);
        for index in 0..segments {
            let start = index as u64 * chunk;
            if start >= total {
                break;
            }
            let end = (start + chunk - 1).min(total - 1);
            ranges.push((index, start, end));
        }

        let part = part_path(&request.dest);
        let downloads = ranges.iter().map(|(index, start, end)| {
            let candidate = candidate.clone();
            let ctx = ctx.clone();
            let segment_path = segment_path(&request.dest, *index);
            let start = *start;
            let end = *end;
            async move {
                self.download_segment(&candidate, start, end, &segment_path, total, &ctx)
                    .await
            }
        });

        let results = futures_util::future::join_all(downloads).await;
        let mut written = 0_u64;
        for result in results {
            match result {
                Ok(bytes) => written += bytes,
                Err(error) => {
                    cleanup_segments(&request.dest, ranges.len()).await;
                    return Err(error);
                }
            }
        }

        if written != total {
            cleanup_segments(&request.dest, ranges.len()).await;
            return Err(AttemptError::Protocol(format!(
                "segmented download produced {written} of {total} bytes"
            )));
        }

        // Merge the segments into the canonical `.part` file.
        let merge = async {
            let mut output = BufWriter::with_capacity(
                WRITE_BUFFER,
                File::create(&part)
                    .await
                    .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?,
            );

            for (index, _, _) in &ranges {
                let segment = segment_path(&request.dest, *index);
                let mut input = tokio::fs::File::open(&segment)
                    .await
                    .map_err(|error| AttemptError::Io(format!("{}: {error}", segment.display())))?;
                tokio::io::copy(&mut input, &mut output)
                    .await
                    .map_err(|error| AttemptError::Io(format!("{}: {error}", segment.display())))?;
                let _ = tokio::fs::remove_file(&segment).await;
            }

            output
                .flush()
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?;
            output
                .get_ref()
                .sync_all()
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", part.display())))?;
            Ok::<(), AttemptError>(())
        };

        if let Err(error) = merge.await {
            cleanup_segments(&request.dest, ranges.len()).await;
            let _ = remove_if_exists(&part).await;
            return Err(error);
        }

        ctx.progress.on_bytes(0);
        self.finalize_with_bytes(candidate, request, written, false)
            .await
    }

    async fn download_segment(
        &self,
        candidate: &Candidate,
        start: u64,
        end: u64,
        path: &Path,
        total: u64,
        ctx: &DownloadCtx,
    ) -> std::result::Result<u64, AttemptError> {
        let expected = end - start + 1;
        let host = candidate.url.host_str().unwrap_or("unknown").to_string();
        let _slots = self.acquire_slot(ctx, &host).await?;

        let request = self
            .client
            .request(Method::GET, &candidate.url)
            .header(header::RANGE, format!("bytes={start}-{end}"))
            .header(
                header::ACCEPT_ENCODING,
                HeaderValue::from_static("identity"),
            );

        let connect_timeout = candidate
            .connect_timeout
            .unwrap_or(self.options.connect_timeout);
        let response = tokio::time::timeout(connect_timeout, request.send())
            .await
            .map_err(|_| AttemptError::Timeout("segment connect timed out".to_string()))?
            .map_err(from_reqwest)?;

        match response.status() {
            StatusCode::PARTIAL_CONTENT => {}
            status if status.is_success() => return Err(AttemptError::RangeUnsupported),
            status => {
                return Err(AttemptError::Status {
                    status,
                    detail: format!("segment {start}-{end} of {}", candidate.url),
                });
            }
        }

        let (actual_start, actual_end, actual_total) = parse_content_range(response.headers())?;
        if actual_start != start || actual_end != end || actual_total != total {
            return Err(AttemptError::Protocol(format!(
                "segment range mismatch: expected {start}-{end}/{total}, got {actual_start}-{actual_end}/{actual_total}"
            )));
        }

        let file = File::create(path)
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", path.display())))?;
        let mut writer = BufWriter::with_capacity(WRITE_BUFFER, file);

        let read_timeout = candidate.read_timeout.unwrap_or(self.options.read_timeout);
        let mut stream = response.bytes_stream();
        let mut written = 0_u64;

        loop {
            let next = tokio::time::timeout(read_timeout, stream.next())
                .await
                .map_err(|_| {
                    AttemptError::Timeout(format!("segment read timed out after {read_timeout:?}"))
                })?;

            let Some(chunk) = next else {
                break;
            };
            let chunk = chunk.map_err(from_reqwest)?;

            if ctx.is_cancelled() {
                let _ = writer.flush().await;
                return Err(AttemptError::Cancelled);
            }
            if let Some(rate) = &self.rate {
                rate.acquire(chunk.len() as u64).await;
            }

            writer
                .write_all(&chunk)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", path.display())))?;
            written += chunk.len() as u64;
            ctx.progress.on_bytes(chunk.len() as u64);
        }

        writer
            .flush()
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", path.display())))?;
        writer
            .get_ref()
            .sync_all()
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", path.display())))?;
        drop(writer);

        if written != expected {
            return Err(AttemptError::Protocol(format!(
                "segment {start}-{end} wrote {written} of {expected} bytes"
            )));
        }

        Ok(written)
    }

    async fn finalize(
        &self,
        candidate: &Candidate,
        request: &DownloadRequest,
        _ctx: &DownloadCtx,
    ) -> std::result::Result<DownloadOutcome, AttemptError> {
        let bytes = tokio::fs::metadata(part_path(&request.dest))
            .await
            .map_or(0, |metadata| metadata.len());
        self.finalize_with_bytes(candidate, request, bytes, false)
            .await
    }

    async fn finalize_with_bytes(
        &self,
        candidate: &Candidate,
        request: &DownloadRequest,
        bytes: u64,
        verified: bool,
    ) -> std::result::Result<DownloadOutcome, AttemptError> {
        let part = part_path(&request.dest);

        if !verified && let Some(integrity) = &request.integrity {
            let valid = integrity
                .verify(&part)
                .await
                .map_err(|error| AttemptError::Io(error.to_string()))?;
            if !valid {
                discard_partial(&part, &meta_path(&request.dest)).await;
                return Err(AttemptError::Integrity(format!(
                    "checksum mismatch for {}",
                    request.dest.display()
                )));
            }
        }

        if request.cache == CachePolicy::ContentAddressed {
            let sha1 = match request.integrity.as_ref().and_then(Integrity::sha1_hex) {
                Some(hash) => Some(hash.to_string()),
                None => Some(
                    hash_file(&part, HashAlgorithm::Sha1)
                        .await
                        .map_err(|error| AttemptError::Io(error.to_string()))?,
                ),
            };

            if let Some(hash) = sha1
                && let Err(error) = self.cache.insert(&hash, &part).await
            {
                tracing::warn!(
                    dest = %request.dest.display(),
                    detail = %error,
                    "cannot store file in content cache"
                );
            }
        }

        if let Some(parent) = request.dest.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|error| AttemptError::Io(format!("{}: {error}", parent.display())))?;
        }

        replace_file(&part, &request.dest)
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", request.dest.display())))?;
        let _ = remove_if_exists(&meta_path(&request.dest)).await;

        Ok(DownloadOutcome {
            dest: request.dest.clone(),
            bytes,
            from_cache: false,
            source: Some(candidate.url.clone()),
        })
    }
}

/// Result of [`Downloader::fetch_bytes`].
#[derive(Debug, Clone)]
pub struct FetchedBytes {
    pub bytes: bytes::Bytes,
    pub url: Url,
    pub from_cache: bool,
}

/// In-flight `.part` metadata (§6.2).
#[derive(Debug, Clone, Serialize, Deserialize)]
struct ResumeState {
    url: String,
    #[serde(default)]
    etag: Option<String>,
    #[serde(default)]
    last_modified: Option<String>,
    #[serde(default)]
    total: Option<u64>,
    downloaded: u64,
}

impl ResumeState {
    /// `If-Range` value: a strong ETag, else the last modification date.
    fn if_range(&self) -> Option<&str> {
        match self.etag.as_deref() {
            Some(etag) if !etag.starts_with("W/") => Some(etag),
            _ => self.last_modified.as_deref(),
        }
    }
}

async fn discard_partial(part: &Path, meta: &Path) {
    let _ = remove_if_exists(part).await;
    let _ = remove_if_exists(meta).await;
}

async fn cleanup_segments(dest: &Path, count: usize) {
    for index in 0..count {
        let segment = segment_path(dest, index);
        let _ = remove_if_exists(&segment).await;
    }
}

async fn load_resume(part: &Path, meta: &Path) -> Option<ResumeState> {
    let text = tokio::fs::read_to_string(meta).await.ok()?;
    let mut state: ResumeState = serde_json::from_str(&text).ok()?;
    let length = tokio::fs::metadata(part).await.ok()?.len();

    if length == 0 || state.total.is_some_and(|total| length > total) {
        let _ = remove_if_exists(part).await;
        let _ = remove_if_exists(meta).await;
        return None;
    }

    state.downloaded = length;
    if state.total.is_some_and(|total| length == total) {
        return Some(state);
    }
    Some(state)
}

async fn write_resume(path: &Path, state: &ResumeState) -> std::result::Result<(), AttemptError> {
    let encoded = serde_json::to_vec(state)
        .map_err(|error| AttemptError::Protocol(format!("cannot encode resume state: {error}")))?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent)
            .await
            .map_err(|error| AttemptError::Io(format!("{}: {error}", parent.display())))?;
    }
    let temporary = temp_path(path);
    tokio::fs::write(&temporary, encoded)
        .await
        .map_err(|error| AttemptError::Io(format!("{}: {error}", temporary.display())))?;
    replace_file(&temporary, path)
        .await
        .map_err(|error| AttemptError::Io(format!("{}: {error}", path.display())))
}

async fn part_exists(dest: &Path) -> bool {
    tokio::fs::metadata(part_path(dest))
        .await
        .is_ok_and(|metadata| metadata.is_file() && metadata.len() > 0)
}

fn parse_content_range(headers: &HeaderMap) -> std::result::Result<(u64, u64, u64), AttemptError> {
    let value = header_str(headers, header::CONTENT_RANGE)
        .ok_or_else(|| AttemptError::Protocol("missing Content-Range".to_string()))?;
    let rest = value
        .strip_prefix("bytes ")
        .ok_or_else(|| AttemptError::Protocol(format!("invalid Content-Range: {value}")))?;
    let (range, total) = rest
        .split_once('/')
        .ok_or_else(|| AttemptError::Protocol(format!("invalid Content-Range: {value}")))?;
    let (start, end) = range
        .split_once('-')
        .ok_or_else(|| AttemptError::Protocol(format!("invalid Content-Range: {value}")))?;

    let parse = |part: &str| {
        part.parse::<u64>().map_err(|_| {
            AttemptError::Protocol(format!("invalid Content-Range component: {value}"))
        })
    };

    Ok((parse(start)?, parse(end)?, parse(total)?))
}

fn has_content_encoding(headers: &HeaderMap) -> bool {
    header_str(headers, header::CONTENT_ENCODING)
        .is_some_and(|value| !value.eq_ignore_ascii_case("identity"))
}

fn header_str(headers: &HeaderMap, name: header::HeaderName) -> Option<&str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

/// Retry delay: `base × 2ⁿ`, capped.
#[must_use]
pub fn backoff(attempt: u32, base: Duration, cap: Duration) -> Duration {
    let factor = 1_u32.checked_shl(attempt.min(16)).unwrap_or(u32::MAX);
    base.saturating_mul(factor).min(cap)
}

fn append_suffix(path: &Path, suffix: &str) -> PathBuf {
    let mut value = path.as_os_str().to_os_string();
    value.push(suffix);
    PathBuf::from(value)
}

/// `dest.part`, the resumable download target.
#[must_use]
pub fn part_path(dest: &Path) -> PathBuf {
    append_suffix(dest, ".part")
}

/// `dest.part.meta`, the resume validator file.
#[must_use]
pub fn meta_path(dest: &Path) -> PathBuf {
    append_suffix(dest, ".part.meta")
}

fn segment_path(dest: &Path, index: usize) -> PathBuf {
    append_suffix(dest, &format!(".part.{index}"))
}

/// Expands one URL into candidates through the mirror table.
#[must_use]
pub fn candidates_for(
    table: &crate::mirrors::MirrorTable,
    url: &str,
    source: fg_core::DownloadSource,
    region: crate::region::Region,
) -> Vec<Candidate> {
    table
        .candidates(url, source, region)
        .into_iter()
        .filter_map(|candidate| Candidate::parse(&candidate).ok())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builder_defaults_to_the_documented_concurrency() {
        let builder = DownloaderBuilder::new("/tmp/fograin-builder-test");

        // §6.2: min(cpu × 4, 64) when the setting is left at 0.
        assert_eq!(
            builder.concurrency,
            DownloadConfig::default().effective_concurrency()
        );
        assert!((4..=64).contains(&builder.concurrency));
        assert_eq!(builder.per_host, 32);
    }

    #[test]
    fn backoff_follows_the_documented_schedule() {
        let base = Duration::from_millis(200);
        let cap = Duration::from_secs(5);

        assert_eq!(backoff(0, base, cap), Duration::from_millis(200));
        assert_eq!(backoff(1, base, cap), Duration::from_millis(400));
        assert_eq!(backoff(2, base, cap), Duration::from_millis(800));
        assert_eq!(backoff(3, base, cap), Duration::from_millis(1600));
        assert_eq!(backoff(4, base, cap), Duration::from_millis(3200));
        assert_eq!(backoff(5, base, cap), Duration::from_secs(5));
        assert_eq!(backoff(60, base, cap), Duration::from_secs(5));
    }

    #[test]
    fn part_paths_are_siblings_of_the_destination() {
        let dest = Path::new("/tmp/mods/example.jar");
        assert_eq!(part_path(dest), Path::new("/tmp/mods/example.jar.part"));
        assert_eq!(
            meta_path(dest),
            Path::new("/tmp/mods/example.jar.part.meta")
        );
        assert_eq!(
            segment_path(dest, 3),
            Path::new("/tmp/mods/example.jar.part.3")
        );
    }

    #[test]
    fn parses_content_range_headers() {
        let mut headers = HeaderMap::new();
        headers.insert(
            header::CONTENT_RANGE,
            HeaderValue::from_static("bytes 100-199/1000"),
        );
        assert_eq!(
            parse_content_range(&headers).expect("parse"),
            (100, 199, 1000)
        );

        headers.insert(
            header::CONTENT_RANGE,
            HeaderValue::from_static("bytes */1000"),
        );
        assert!(parse_content_range(&headers).is_err());
    }

    #[test]
    fn content_encoding_is_detected() {
        let mut headers = HeaderMap::new();
        assert!(!has_content_encoding(&headers));

        headers.insert(
            header::CONTENT_ENCODING,
            HeaderValue::from_static("identity"),
        );
        assert!(!has_content_encoding(&headers));

        headers.insert(header::CONTENT_ENCODING, HeaderValue::from_static("gzip"));
        assert!(has_content_encoding(&headers));
    }

    #[tokio::test]
    async fn integrity_verifies_sizes_and_digests() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("abc.bin");
        tokio::fs::write(&path, b"abc").await.expect("write");

        assert!(
            Integrity::Sha1("a9993e364706816aba3e25717850c26c9cd0d89d".into())
                .verify(&path)
                .await
                .expect("verify")
        );
        assert!(
            !Integrity::sha1("deadbeef")
                .verify(&path)
                .await
                .expect("verify")
        );
        assert!(Integrity::Size(3).verify(&path).await.expect("verify"));
        assert!(!Integrity::Size(4).verify(&path).await.expect("verify"));
    }

    #[test]
    fn priority_orders_manifests_before_assets() {
        let mut priorities = vec![
            Priority::Mod,
            Priority::Asset,
            Priority::Manifest,
            Priority::Library,
        ];
        priorities.sort_by_key(|priority| std::cmp::Reverse(*priority));
        assert_eq!(
            priorities,
            vec![
                Priority::Manifest,
                Priority::Library,
                Priority::Asset,
                Priority::Mod
            ]
        );
    }

    #[test]
    fn resume_state_uses_strong_etags_for_if_range() {
        let state = ResumeState {
            url: "https://example.com/a".into(),
            etag: Some("\"abc\"".into()),
            last_modified: Some("Wed, 21 Oct 2015 07:28:00 GMT".into()),
            total: Some(100),
            downloaded: 10,
        };
        assert_eq!(state.if_range(), Some("\"abc\""));

        let weak = ResumeState {
            etag: Some("W/\"abc\"".into()),
            ..state
        };
        assert_eq!(weak.if_range(), Some("Wed, 21 Oct 2015 07:28:00 GMT"));
    }

    #[test]
    fn options_follow_the_configuration() {
        let download = DownloadConfig {
            retries: 3,
            segments: 8,
            segment_threshold_mb: 4,
            ..DownloadConfig::default()
        };
        let network = fg_core::NetworkConfig {
            proxy: "auto".into(),
            timeout_secs: 7,
        };
        let options = DownloadOptions::from_config(&download, &network);

        assert_eq!(options.retries, 3);
        assert_eq!(options.segments, 8);
        assert_eq!(options.segment_threshold, 4 * 1024 * 1024);
        assert_eq!(options.read_timeout, Duration::from_secs(7));
        assert!(options.enable_segments);

        let disabled = DownloadOptions::from_config(
            &DownloadConfig {
                segment_threshold_mb: 0,
                ..DownloadConfig::default()
            },
            &network,
        );
        assert!(!disabled.enable_segments);
    }
}
