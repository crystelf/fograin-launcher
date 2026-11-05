//! Headless debug and CI entry point for the Fograin core.
//!
//! M2 ships the download tooling from §14:
//!
//! ```text
//! fg-cli download <url>      # download one file through the engine
//! fg-cli mirror <url>        # show the candidate list for a URL
//! fg-cli assets <version>    # download every asset object of a version
//! fg-cli probe <url>...      # HEAD-probe sources and update health data
//! ```

use std::{
    collections::{BTreeMap, HashSet},
    io::Write as _,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use clap::{Args, Parser, Subcommand, ValueEnum};
use fg_core::{DownloadSource, Result, ThemePreference, config, paths};
use fg_net::{
    Candidate, Client, ContentCache, DownloadCtx, DownloadOptions, DownloadRequest, Downloader,
    MirrorTable, Priority, ProgressSink, Region,
};
use serde::Deserialize;

const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ASSET_ROOT: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Parser)]
#[command(
    name = "fg-cli",
    version,
    about = "Fograin Launcher debug CLI (download engine, M2)"
)]
struct Cli {
    /// Launcher data root. Defaults to the platform data directory or $FOGRAIN_HOME.
    #[arg(long, global = true, env = "FOGRAIN_HOME")]
    root: Option<PathBuf>,

    /// Source preference for file downloads.
    #[arg(long, global = true, value_enum, default_value_t = SourceArg::Auto)]
    source: SourceArg,

    /// Override region detection, which drives "auto" ordering.
    #[arg(long, global = true, value_enum)]
    region: Option<RegionArg>,

    /// Concurrent connections for batch commands.
    #[arg(long, global = true)]
    concurrency: Option<usize>,

    /// Bandwidth cap in KiB/s (0 = unlimited).
    #[arg(long, global = true, default_value_t = 0)]
    rate_limit_kib: u64,

    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum SourceArg {
    Auto,
    Official,
    Mirror,
}

impl From<SourceArg> for DownloadSource {
    fn from(value: SourceArg) -> Self {
        match value {
            SourceArg::Auto => Self::Auto,
            SourceArg::Official => Self::Official,
            SourceArg::Mirror => Self::Mirror,
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum RegionArg {
    Cn,
    Global,
}

impl From<RegionArg> for Region {
    fn from(value: RegionArg) -> Self {
        match value {
            RegionArg::Cn => Region::China,
            RegionArg::Global => Region::RestOfWorld,
        }
    }
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Print build and data-directory information.
    Info,
    /// Download one URL through the engine.
    Download(DownloadArgs),
    /// Print the ordered candidate URLs produced by the mirror table.
    Mirror(MirrorArgs),
    /// Download every asset object of a Minecraft version.
    Assets(AssetsArgs),
    /// HEAD-probe URLs and record their health.
    Probe(ProbeArgs),
}

#[derive(Debug, Args)]
struct DownloadArgs {
    /// URL to download (mirror rules are applied).
    url: String,

    /// Destination file. Defaults to the URL's last path segment.
    #[arg(long)]
    dest: Option<PathBuf>,

    /// Expected SHA-1; also enables the content-addressed cache.
    #[arg(long)]
    sha1: Option<String>,

    /// Expected SHA-256.
    #[arg(long)]
    sha256: Option<String>,

    /// Expected size in bytes.
    #[arg(long)]
    size: Option<u64>,

    /// Attempts per candidate.
    #[arg(long)]
    retries: Option<u32>,

    /// Parallel segments for large files.
    #[arg(long)]
    segments: Option<usize>,

    /// Files at least this large are segmented.
    #[arg(long, default_value_t = 16)]
    segment_threshold_mb: u64,

    /// Disable segmented downloads.
    #[arg(long)]
    no_segments: bool,
}

#[derive(Debug, Args)]
struct MirrorArgs {
    url: String,

    /// Emit a JSON array instead of one URL per line.
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct AssetsArgs {
    /// Minecraft version id, e.g. 1.21.4.
    version: String,

    /// Directory for the asset objects. Defaults to <root>/cache/fg-cli-assets/<version>.
    #[arg(long)]
    dest: Option<PathBuf>,

    /// Only download the first N objects (smoke tests).
    #[arg(long)]
    limit: Option<usize>,

    /// Resolve the manifest and print the plan without downloading.
    #[arg(long)]
    dry_run: bool,
}

#[derive(Debug, Args)]
struct ProbeArgs {
    urls: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct VersionManifest {
    versions: Vec<ManifestEntry>,
}

#[derive(Debug, Deserialize)]
struct ManifestEntry {
    id: String,
    url: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct VersionJson {
    #[serde(default)]
    asset_index: Option<AssetIndex>,
}

#[derive(Debug, Deserialize)]
struct AssetIndex {
    url: String,
    #[serde(default)]
    sha1: Option<String>,
}

#[derive(Debug, Deserialize)]
struct AssetIndexFile {
    #[serde(default)]
    objects: BTreeMap<String, AssetObject>,
}

#[derive(Debug, Deserialize)]
struct AssetObject {
    hash: String,
    size: u64,
}

#[tokio::main]
async fn main() -> Result<()> {
    init_tracing();

    let cli = Cli::parse();
    let root = cli.root.clone().unwrap_or_else(paths::data_root);
    let source: DownloadSource = cli.source.into();
    let region = cli.region.map_or_else(Region::detect, Region::from);
    let table = MirrorTable::load(&root);

    match cli.command {
        Command::Info => command_info(&root),
        Command::Download(args) => {
            command_download(
                &root,
                &table,
                source,
                region,
                cli.concurrency,
                cli.rate_limit_kib,
                args,
            )
            .await
        }
        Command::Mirror(args) => command_mirror(&table, source, region, args),
        Command::Assets(args) => {
            command_assets(
                &root,
                &table,
                source,
                region,
                cli.concurrency,
                cli.rate_limit_kib,
                args,
            )
            .await
        }
        Command::Probe(args) => command_probe(&root, cli.rate_limit_kib, args).await,
    }
}

fn init_tracing() {
    let filter = std::env::var("RUST_LOG").unwrap_or_else(|_| "warn".to_string());
    let _ = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .try_init();
}

fn command_info(root: &std::path::Path) -> Result<()> {
    let loaded = config::read_config(root).unwrap_or_default();
    println!("fograin-cli {}", env!("CARGO_PKG_VERSION"));
    println!("platform   {}", fg_core::Platform::detect().label());
    println!("data root  {}", root.display());
    println!("config     {}", config::config_path(root).display());
    println!(
        "theme      {}",
        match loaded.appearance.theme {
            ThemePreference::System => "system",
            ThemePreference::Light => "light",
            ThemePreference::Dark => "dark",
        }
    );
    println!("region     {}", Region::detect().as_str());
    println!(
        "mirror     {} rules, bmclapi {}",
        MirrorTable::embedded().rules().len(),
        MirrorTable::embedded().bmclapi_root()
    );
    Ok(())
}

async fn command_download(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: DownloadArgs,
) -> Result<()> {
    let dest = args.dest.unwrap_or_else(|| {
        let name = args
            .url
            .rsplit('/')
            .find(|segment| !segment.is_empty())
            .unwrap_or("download.bin");
        root.join("downloads").join(name)
    });

    let downloader = build_downloader(
        root,
        concurrency,
        rate_limit_kib,
        args.no_segments,
        args.retries,
        args.segments,
        args.segment_threshold_mb,
    )?;
    let candidates = candidates_for(table, &args.url, source, region)?;

    let mut request = DownloadRequest::new(&dest, candidates).with_priority(Priority::Manifest);
    if let Some(size) = args.size {
        request = request.with_size_hint(size);
    }
    if let Some(sha1) = args.sha1 {
        request = request.with_sha1(sha1);
    } else if let Some(sha256) = args.sha256 {
        request = request.with_sha256(sha256);
    } else if let Some(size) = args.size {
        request = request.with_size(size);
    }

    let started = Instant::now();
    let outcome = downloader.download(request).await?;
    let seconds = started.elapsed().as_secs_f64().max(0.001);

    println!(
        "downloaded {} -> {}",
        human_bytes(outcome.bytes),
        outcome.dest.display()
    );
    println!(
        "source     {}",
        outcome.source.map_or_else(
            || "content cache / already complete".to_string(),
            |url| url.to_string()
        )
    );
    println!(
        "speed      {}/s in {:.2}s",
        human_bytes((outcome.bytes as f64 / seconds) as u64),
        seconds
    );
    println!("cache hit  {}", outcome.from_cache);
    Ok(())
}

fn command_mirror(
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    args: MirrorArgs,
) -> Result<()> {
    let candidates = table.candidates(&args.url, source, region);
    if args.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&candidates).map_err(|error| {
                fg_core::Error::with_detail(
                    fg_core::ErrorKind::Internal,
                    "error.cli.json",
                    error.to_string(),
                )
            })?
        );
    } else {
        for (index, candidate) in candidates.iter().enumerate() {
            println!("{index}: {candidate}");
        }
    }
    Ok(())
}

async fn command_assets(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: AssetsArgs,
) -> Result<()> {
    let downloader = build_downloader(root, concurrency, rate_limit_kib, false, None, None, 16)?;
    let dest = args
        .dest
        .unwrap_or_else(|| root.join("cache").join("fg-cli-assets").join(&args.version));

    let manifest_urls = table.candidates(VERSION_MANIFEST_URL, source, region);
    let manifest: VersionManifest = downloader
        .fetch_json(manifest_urls, VERSION_MANIFEST_URL)
        .await?;

    let entry = manifest
        .versions
        .iter()
        .find(|version| version.id == args.version)
        .ok_or_else(|| {
            fg_core::Error::with_detail(
                fg_core::ErrorKind::NotFound,
                "error.cli.unknown_version",
                format!("version {} is not in the manifest", args.version),
            )
        })?;

    let version_json: VersionJson = downloader
        .fetch_json(table.candidates(&entry.url, source, region), &entry.url)
        .await?;

    let index = version_json.asset_index.ok_or_else(|| {
        fg_core::Error::with_detail(
            fg_core::ErrorKind::NotFound,
            "error.cli.no_assets",
            format!("version {} has no asset index", args.version),
        )
    })?;

    let index_file: AssetIndexFile = downloader
        .fetch_json(table.candidates(&index.url, source, region), &index.url)
        .await?;

    let mut objects: Vec<(String, u64)> = index_file
        .objects
        .into_values()
        .map(|object| (object.hash, object.size))
        .collect();
    objects.sort_by(|left, right| left.0.cmp(&right.0));
    objects.dedup_by(|left, right| left.0 == right.0);
    if let Some(limit) = args.limit {
        objects.truncate(limit);
    }

    let total_bytes: u64 = objects.iter().map(|(_, size)| size).sum();
    println!(
        "version {} — {} assets ({}) into {}",
        args.version,
        objects.len(),
        human_bytes(total_bytes),
        dest.display()
    );
    println!(
        "asset index {} (sha1 {})",
        index.url,
        index.sha1.as_deref().unwrap_or("unknown")
    );

    if args.dry_run {
        for (hash, size) in objects.iter().take(20) {
            println!("  {hash} {}", human_bytes(*size));
        }
        if objects.len() > 20 {
            println!("  … {} more", objects.len() - 20);
        }
        return Ok(());
    }

    let mut seen = HashSet::with_capacity(objects.len());
    let mut requests = Vec::with_capacity(objects.len());
    for (hash, size) in &objects {
        if hash.len() < 2 || !seen.insert(hash.clone()) {
            continue;
        }

        let official = format!("{ASSET_ROOT}/{}/{}", &hash[..2], hash);
        let candidates = candidates_for(table, &official, source, region)?;
        let path = dest.join(&hash[..2]).join(hash);

        requests.push(
            DownloadRequest::new(path, candidates)
                .with_sha1(hash.clone())
                .with_size_hint(*size)
                .with_priority(Priority::Asset),
        );
    }

    let progress = Arc::new(BatchProgress::default());
    let items: Vec<(DownloadRequest, DownloadCtx)> = requests
        .into_iter()
        .map(|request| (request, DownloadCtx::with_progress(progress.clone())))
        .collect();
    let total_files = items.len();
    let reporter = spawn_reporter(progress.clone(), total_files, total_bytes);
    let started = Instant::now();
    let report = downloader.download_many_with(items).await;
    reporter.abort();

    println!();
    let seconds = started.elapsed().as_secs_f64().max(0.001);
    println!(
        "done: {} ok, {} failed, {} in {:.1}s ({}/s)",
        report.succeeded,
        report.failed.len(),
        human_bytes(report.bytes),
        seconds,
        human_bytes((report.bytes as f64 / seconds) as u64)
    );

    if !report.failed.is_empty() {
        for (dest, error) in report.failed.iter().take(10) {
            eprintln!("failed {}: {error}", dest.display());
        }
        return Err(fg_core::Error::with_detail(
            fg_core::ErrorKind::Network,
            "error.cli.assets_failed",
            format!("{} of {total_files} assets failed", report.failed.len()),
        ));
    }

    // Verify the on-disk layout the installer will rely on.
    let cache_entries = ContentCache::new(root.join("cache").join("sha1"));
    println!("content cache at {}", cache_entries.root().display());
    Ok(())
}

async fn command_probe(root: &std::path::Path, rate_limit_kib: u64, args: ProbeArgs) -> Result<()> {
    if args.urls.is_empty() {
        return Err(fg_core::Error::new(
            fg_core::ErrorKind::InvalidInput,
            "error.cli.no_urls",
        ));
    }

    let downloader = build_downloader(root, None, rate_limit_kib, true, None, None, 16)?;
    let mut urls = Vec::with_capacity(args.urls.len());
    for raw in &args.urls {
        urls.push(fg_net::Url::parse(raw).map_err(|error| {
            fg_core::Error::with_detail(
                fg_core::ErrorKind::InvalidInput,
                "error.net.url",
                format!("{raw}: {error}"),
            )
        })?);
    }

    let probes = downloader.probe_sources(&urls).await;
    for probe in &probes {
        println!(
            "{}  {:>6}  {}",
            if probe.ok { "ok  " } else { "fail" },
            probe
                .rtt_ms
                .map_or_else(|| "-".to_string(), |rtt| format!("{rtt}ms")),
            probe.url
        );
        if let Some(detail) = &probe.detail {
            println!("        {detail}");
        }
    }
    Ok(())
}

fn build_downloader(
    root: &std::path::Path,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    no_segments: bool,
    retries: Option<u32>,
    segments: Option<usize>,
    segment_threshold_mb: u64,
) -> Result<Downloader> {
    let loaded = config::read_config(root).unwrap_or_default();

    let mut options = DownloadOptions::from_config(&loaded.download, &loaded.network);
    if let Some(retries) = retries {
        options.retries = retries;
    }
    if let Some(segments) = segments {
        options.segments = segments.max(1);
        options.enable_segments = segments > 1 && options.segment_threshold > 0;
    }
    if segment_threshold_mb == 0 || no_segments {
        options.enable_segments = false;
    } else {
        options.segment_threshold = segment_threshold_mb * 1024 * 1024;
    }

    let client = Client::builder().from_config(&loaded.network)?.build()?;
    let concurrency = concurrency
        .unwrap_or_else(|| loaded.download.effective_concurrency())
        .max(1);

    Downloader::builder(root)
        .client(client)
        .options(options)
        .concurrency(concurrency)
        .per_host(loaded.download.per_host as usize)
        .rate_limit_bytes_per_sec(rate_limit_kib * 1024)
        .build()
}

fn candidates_for(
    table: &MirrorTable,
    url: &str,
    source: DownloadSource,
    region: Region,
) -> Result<Vec<Candidate>> {
    table
        .candidates(url, source, region)
        .into_iter()
        .map(|candidate| Candidate::parse(&candidate))
        .collect()
}

/// Progress sink that keeps the running totals for the batch reporter.
#[derive(Debug, Default)]
struct BatchProgress {
    bytes: AtomicU64,
    files: AtomicUsize,
}

impl ProgressSink for BatchProgress {
    fn on_bytes(&self, bytes: u64) {
        self.bytes.fetch_add(bytes, Ordering::Relaxed);
    }

    fn on_total(&self, total: Option<u64>) {
        if total.is_some() {
            self.files.fetch_add(1, Ordering::Relaxed);
        }
    }
}

fn spawn_reporter(
    progress: Arc<BatchProgress>,
    files: usize,
    total_bytes: u64,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut last_bytes = 0_u64;
        let mut interval = tokio::time::interval(Duration::from_secs(1));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

        loop {
            interval.tick().await;
            // Retries can report the same bytes twice; keep the bar honest.
            let bytes = progress.bytes.load(Ordering::Relaxed).min(total_bytes);
            let seen = progress.files.load(Ordering::Relaxed).min(files);
            let delta = bytes.saturating_sub(last_bytes);
            last_bytes = bytes;

            let percent = if total_bytes > 0 {
                bytes as f64 / total_bytes as f64 * 100.0
            } else {
                0.0
            };

            let line = format!(
                "\r  {seen}/{files} files  {percent:5.1}%  {}/s  {} / {}      ",
                human_bytes(delta),
                human_bytes(bytes),
                human_bytes(total_bytes)
            );
            let _ = std::io::stderr().write_all(line.as_bytes());
            let _ = std::io::stderr().flush();
        }
    })
}

fn human_bytes(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}
