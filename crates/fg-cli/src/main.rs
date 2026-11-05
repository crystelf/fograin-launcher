//! Headless debug and CI entry point for the Fograin core.
//!
//! M2 shipped the download tooling; M3 adds the install/launch path from §14:
//!
//! ```text
//! fg-cli versions                 # cached version manifest
//! fg-cli install <version>        # install vanilla + create an instance
//! fg-cli launch <instance>        # offline launch, streaming the game log
//! fg-cli java list                # discover JDKs
//! fg-cli java match <version>     # show the selected JDK for a version
//! fg-cli instance list            # installed instances
//! fg-cli download <url>           # download one file through the engine
//! fg-cli mirror <url>             # show the candidate list for a URL
//! fg-cli assets <version>         # download every asset object of a version
//! fg-cli probe <url>...           # HEAD-probe sources and update health data
//! ```

use std::{
    collections::{BTreeMap, HashSet},
    io::{IsTerminal as _, Write as _},
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use clap::{Args, Parser, Subcommand, ValueEnum};
use fg_auth::OfflineAccount;
use fg_core::{DownloadSource, Error, ErrorKind, Result, ThemePreference, config, paths};
use fg_game::install::{FileKind, InstallPlan, Installer, category_counts};
use fg_game::instance::Instance;
use fg_game::launch::{
    GameEvent, GameProcess, LaunchOptions, LaunchPlan, LogStream, cleanup_launch_artifacts,
    needs_x86_64_java,
};
use fg_game::layout::{Layout, validate_id};
use fg_game::resolver::VersionResolver;
use fg_game::rules::RuleContext;
use fg_java::{
    JavaArch, JavaRuntime, JavaScanner, JavaSource, JavaWarning, normalize_home, select,
};
use fg_net::{
    Candidate, Client, ContentCache, DownloadCtx, DownloadOptions, DownloadRequest, Downloader,
    MirrorTable, Priority, ProgressSink, Region,
};
use fg_task::{Control, GraphReport, NodeState, RunOptions, TaskGraph};
use serde::Deserialize;

const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const ASSET_ROOT: &str = "https://resources.download.minecraft.net";

#[derive(Debug, Parser)]
#[command(
    name = "fg-cli",
    version,
    about = "Fograin Launcher debug CLI (install, launch, downloads)"
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
    /// List the cached version manifest.
    Versions(VersionsArgs),
    /// Install a vanilla version and create an instance.
    Install(InstallArgs),
    /// Launch an installed instance with an offline account.
    Launch(LaunchArgs),
    /// Discover installed Java runtimes.
    Java(JavaArgs),
    /// Inspect installed instances.
    Instance(InstanceArgs),
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
struct VersionsArgs {
    /// Number of versions to print (newest first).
    #[arg(long, default_value_t = 20)]
    limit: usize,

    /// Emit JSON instead of a table.
    #[arg(long)]
    json: bool,

    /// Bypass the manifest cache.
    #[arg(long)]
    refresh: bool,

    /// Filter by version type.
    #[arg(long, value_enum)]
    kind: Option<VersionKindArg>,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum VersionKindArg {
    Release,
    Snapshot,
    OldBeta,
    OldAlpha,
}

impl VersionKindArg {
    fn matches(self, value: &str) -> bool {
        match self {
            Self::Release => value == "release",
            Self::Snapshot => value == "snapshot",
            Self::OldBeta => value == "old_beta",
            Self::OldAlpha => value == "old_alpha",
        }
    }
}

#[derive(Debug, Args)]
struct InstallArgs {
    /// Version id, e.g. 1.21.4.
    version: String,

    /// Instance id; defaults to the version id.
    #[arg(long)]
    id: Option<String>,

    /// Bypass cached version metadata and asset indexes.
    #[arg(long)]
    refresh: bool,

    /// Print the plan without downloading.
    #[arg(long)]
    dry_run: bool,

    /// Verify every installed file's SHA-1 after the download.
    #[arg(long)]
    strict: bool,

    /// Install shared files only; do not create an instance.
    #[arg(long)]
    no_instance: bool,
}

#[derive(Debug, Args)]
struct LaunchArgs {
    /// Instance id from `fg-cli instance list`.
    instance: String,

    /// Offline player name.
    #[arg(long, default_value = "Steve")]
    username: String,

    /// Explicit Java home (overrides discovery and instance overrides).
    #[arg(long)]
    java: Option<PathBuf>,

    /// Print the command and exit without spawning the game.
    #[arg(long)]
    dry_run: bool,

    /// Emit the launch plan as JSON.
    #[arg(long)]
    json: bool,

    /// Extra JVM argument; may be repeated.
    #[arg(long = "jvm-arg")]
    jvm_args: Vec<String>,
}

#[derive(Debug, Args)]
struct JavaArgs {
    #[command(subcommand)]
    command: JavaCommand,
}

#[derive(Debug, Subcommand)]
enum JavaCommand {
    /// List discovered runtimes.
    List(JavaListArgs),
    /// Show the runtime selected for a version.
    Match(JavaMatchArgs),
}

#[derive(Debug, Args)]
struct JavaListArgs {
    /// Probe JDKs that have no `release` file with `java -XshowSettings`.
    #[arg(long)]
    no_probe: bool,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct JavaMatchArgs {
    /// Installed version id.
    version: String,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct InstanceArgs {
    #[command(subcommand)]
    command: InstanceCommand,
}

#[derive(Debug, Subcommand)]
enum InstanceCommand {
    /// List instances.
    List(InstanceListArgs),
    /// Show one instance.
    Show(InstanceShowArgs),
}

#[derive(Debug, Args)]
struct InstanceListArgs {
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct InstanceShowArgs {
    id: String,

    #[arg(long)]
    json: bool,
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
        Command::Versions(args) => {
            command_versions(
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
        Command::Install(args) => {
            command_install(
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
        Command::Launch(args) => {
            command_launch(
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
        Command::Java(args) => {
            command_java(
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
        Command::Instance(args) => command_instance(&root, args),
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

// ---------------------------------------------------------------------------
// M3: install and launch
// ---------------------------------------------------------------------------

fn data_layout(root: &std::path::Path) -> Layout {
    Layout::new(root)
}

fn build_shared(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
) -> Result<(Layout, Arc<Downloader>, VersionResolver)> {
    let layout = data_layout(root);
    layout.ensure_base_dirs()?;
    let downloader = Arc::new(build_downloader(
        root,
        concurrency,
        rate_limit_kib,
        false,
        None,
        None,
        16,
    )?);
    let resolver = VersionResolver::new(
        downloader.clone(),
        layout.clone(),
        table.clone(),
        source,
        region,
    );
    Ok((layout, downloader, resolver))
}

async fn command_versions(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: VersionsArgs,
) -> Result<()> {
    let (_layout, _downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    let manifest = resolver.manifest(args.refresh).await?;

    let versions: Vec<&fg_game::version::VersionSummary> = manifest
        .versions
        .iter()
        .filter(|version| {
            args.kind
                .is_none_or(|kind| kind.matches(&version.version_type))
        })
        .take(args.limit)
        .collect();

    if args.json {
        let latest = serde_json::json!({
            "release": manifest.latest.release,
            "snapshot": manifest.latest.snapshot,
        });
        let payload = serde_json::json!({
            "latest": latest,
            "versions": versions,
        });
        println!(
            "{}",
            serde_json::to_string_pretty(&payload).map_err(json_error)?
        );
        return Ok(());
    }

    println!(
        "latest release {} · snapshot {}",
        manifest.latest.release, manifest.latest.snapshot
    );
    println!("{:<20} {:<10} {:<10} url", "id", "type", "released");
    for version in versions {
        println!(
            "{:<20} {:<10} {:<10} {}",
            version.id,
            version.version_type,
            version
                .release_time
                .as_deref()
                .map(|value| value.get(..10).unwrap_or(value))
                .unwrap_or("-"),
            version.url
        );
    }
    Ok(())
}

async fn command_install(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: InstallArgs,
) -> Result<()> {
    let (layout, downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    let instance_id = args.id.clone().unwrap_or_else(|| args.version.clone());
    validate_id(&instance_id)?;

    let mut instance = if args.dry_run || args.no_instance {
        None
    } else {
        let instance = Instance::load_or_create(&layout, &instance_id, &args.version)?;
        if instance.minecraft != args.version {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.instance.version_mismatch",
                format!(
                    "instance {} is for {}, not {}",
                    instance.id, instance.minecraft, args.version
                ),
            ));
        }
        instance.create_layout(&layout)?;
        Some(instance)
    };

    let installer = Installer::new(
        downloader,
        resolver,
        layout.clone(),
        table.clone(),
        source,
        region,
        RuleContext::current(),
    );
    println!(
        "planning {}{}",
        args.version,
        instance
            .as_ref()
            .map(|instance| format!(" for instance {}", instance.id))
            .unwrap_or_default()
    );
    let plan = installer
        .plan(
            &args.version,
            args.refresh,
            instance.as_ref().map(|instance| instance.id.as_str()),
        )
        .await?;
    println!("  java {} · main {}", plan.java_major, plan.main_class);

    for (kind, count, bytes) in category_counts(&plan) {
        println!(
            "  {:<10} {:>5} files  {:>10}",
            file_kind_label(kind),
            count,
            human_bytes(bytes)
        );
    }
    println!(
        "  {:<10} {:>5} files  {:>10}",
        "total",
        plan.files.len(),
        human_bytes(plan.total_bytes)
    );
    let missing = plan.missing(layout.root(), args.strict);
    println!("  {} already present", plan.files.len() - missing.len());

    if args.dry_run {
        for file in missing.iter().take(20) {
            println!("  missing {} ({:?})", file.path.display(), file.kind);
        }
        if missing.len() > 20 {
            println!("  … {} more", missing.len() - 20);
        }
        return Ok(());
    }

    plan.save(&layout)?;
    let graph = Arc::new(installer.graph(&plan));
    let reporter = spawn_graph_reporter(graph.clone());
    let report = run_graph_interruptible(graph).await?;
    reporter.abort();
    eprintln!();

    if !report.is_success() {
        if report.interrupted.is_some() {
            println!("install interrupted; run the same command again to resume");
            return Ok(());
        }
        let detail = report
            .failed
            .first()
            .map(|(node, error)| format!("{node}: {error}"))
            .unwrap_or_else(|| "install did not finish".to_string());
        return Err(Error::with_detail(
            ErrorKind::Internal,
            "error.install.failed",
            detail,
        ));
    }

    if args.strict {
        let missing = plan.missing(layout.root(), true);
        if !missing.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Io,
                "error.install.verify",
                format!("{} files failed verification", missing.len()),
            ));
        }
    }
    InstallPlan::remove(&layout, &args.version);
    println!(
        "installed {} in {:.1}s",
        args.version,
        report.elapsed.as_secs_f64()
    );
    println!("  client   {}", layout.version_jar(&args.version).display());
    println!("  libraries {}", layout.libraries().display());
    println!("  assets    {}", layout.assets().display());
    if let Some(instance) = &mut instance {
        instance.save(&layout)?;
        println!(
            "  instance  {}",
            layout.instance_file(&instance.id).display()
        );
        println!("launch it with: fg-cli launch {}", instance.id);
    }
    Ok(())
}

async fn command_launch(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: LaunchArgs,
) -> Result<()> {
    let (layout, _downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    let mut instance = Instance::load(&layout, &args.instance)?;
    if !instance.loader.is_vanilla() {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.launch.loader_unsupported",
            format!("loader {:?} is an M5 feature", instance.loader.kind),
        ));
    }

    let resolved = resolver
        .resolve_local(&instance.minecraft)
        .map_err(|error| {
            if error.kind == ErrorKind::NotFound {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.launch.not_installed",
                    format!(
                        "{} is not installed; run `fg-cli install {}`",
                        instance.minecraft, instance.minecraft
                    ),
                )
            } else {
                error
            }
        })?;

    let runtimes = scan_java(&layout, true).await;
    let java = pick_java(&instance, &resolved, &args, &runtimes)?;
    let account = OfflineAccount::new(&args.username)?;

    let plan = LaunchPlan::build(
        &layout,
        &instance,
        &resolved,
        &java,
        &account,
        &LaunchOptions {
            extra_jvm_args: args.jvm_args.clone(),
            ..LaunchOptions::default()
        },
    )
    .map_err(|error| {
        if error.kind == ErrorKind::NotFound {
            Error::with_detail(
                error.kind,
                "error.launch.incomplete",
                format!(
                    "{}; run `fg-cli install {}` first",
                    error.detail.unwrap_or_default(),
                    instance.minecraft
                ),
            )
        } else {
            error
        }
    })?;

    if args.json {
        let payload = serde_json::json!({
            "program": plan.program,
            "args": plan.args,
            "cwd": plan.cwd,
            "mainClass": plan.main_class,
            "classpath": plan.classpath,
            "natives": plan.natives_dir,
            "assetIndex": plan.asset_index,
            "logConfig": plan.log_config,
            "features": plan.features,
        });
        println!(
            "{}",
            serde_json::to_string_pretty(&payload).map_err(json_error)?
        );
        return Ok(());
    }

    println!("instance  {} ({})", instance.id, instance.minecraft);
    println!(
        "java      {} ({} {})",
        java.executable.display(),
        java.version
            .as_ref()
            .map_or_else(|| "?".to_string(), |version| version.full.clone()),
        java.arch
    );
    println!("account   {} ({})", account.name(), account.uuid());
    println!("natives   {}", plan.natives_dir.display());
    if let Some(log_config) = &plan.log_config {
        println!("log4j     {}", log_config.display());
    }

    if args.dry_run {
        println!("command   {}", plan.display());
        return Ok(());
    }

    let process = GameProcess::spawn(&plan)?;
    println!(
        "started   pid {} · log {}",
        process
            .pid()
            .map_or_else(|| "?".to_string(), |pid| pid.to_string()),
        process.log_file().display()
    );
    let mut events = process.subscribe();
    let wait = process.wait();
    tokio::pin!(wait);
    let mut events_open = true;
    let exit = loop {
        tokio::select! {
            event = events.recv(), if events_open => {
                match event {
                    Ok(GameEvent::Line { stream, text, .. }) => {
                        if stream == LogStream::Stdout {
                            println!("{text}");
                        } else {
                            eprintln!("{text}");
                        }
                    }
                    Ok(GameEvent::WindowReady) => {
                        eprintln!("[fograin] game window is ready");
                    }
                    Ok(GameEvent::Started { .. } | GameEvent::Exited { .. }) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(skipped)) => {
                        eprintln!("[fograin] skipped {skipped} log lines");
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => {
                        events_open = false;
                    }
                }
            }
            result = &mut wait => break result?,
        }
    };

    // Keep argfiles/pathing jars until the JVM has finished reading them.
    cleanup_launch_artifacts(&plan);
    let seconds = exit.duration.as_secs();
    instance.record_session(seconds);
    instance.save(&layout)?;
    println!(
        "exited    code {} after {}s · log {}",
        exit.code
            .map_or_else(|| "signal".to_string(), |code| code.to_string()),
        seconds,
        exit.log_file.display()
    );
    if !exit.success {
        return Err(Error::with_detail(
            ErrorKind::Internal,
            "error.launch.exit_code",
            format!("game exited with {:?}", exit.code),
        ));
    }
    Ok(())
}

fn pick_java(
    instance: &Instance,
    resolved: &fg_game::version::ResolvedVersion,
    args: &LaunchArgs,
    runtimes: &[JavaRuntime],
) -> Result<JavaRuntime> {
    let explicit = args
        .java
        .clone()
        .or_else(|| instance.java().map(std::path::Path::to_path_buf));
    if let Some(path) = explicit {
        let home = if path.is_dir() {
            normalize_home(&path)
        } else {
            path.parent()
                .and_then(|bin| bin.parent())
                .map(|home| normalize_home(home).unwrap_or_else(|| home.to_path_buf()))
        }
        .ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.java.not_found",
                format!("{} does not contain bin/java", path.display()),
            )
        })?;
        let runtime =
            JavaRuntime::from_home(home.clone(), JavaSource::ExtraRoot).ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.java.not_found",
                    format!("{} does not contain bin/java", home.display()),
                )
            })?;
        if runtime
            .major()
            .is_some_and(|major| major < resolved.java_major)
        {
            eprintln!(
                "warning: Java {} is older than the recommended Java {} for {}",
                runtime.major().unwrap_or(0),
                resolved.java_major,
                resolved.base_id
            );
        }
        return Ok(runtime);
    }

    let legacy_natives = cfg!(target_os = "macos")
        && JavaArch::host() == JavaArch::Arm64
        && needs_x86_64_java(&resolved.base_id);
    let (candidates, host_arch) = if legacy_natives {
        let x64: Vec<JavaRuntime> = runtimes
            .iter()
            .filter(|runtime| runtime.arch == JavaArch::X86_64)
            .cloned()
            .collect();
        if x64.is_empty() {
            eprintln!(
                "warning: {} has only x86_64 LWJGL natives on Apple Silicon; install an x86_64 Java 8 under Rosetta",
                resolved.base_id
            );
            (runtimes.to_vec(), JavaArch::Arm64)
        } else {
            eprintln!(
                "warning: {} has only x86_64 LWJGL natives; running under Rosetta",
                resolved.base_id
            );
            (x64, JavaArch::X86_64)
        }
    } else {
        (runtimes.to_vec(), JavaArch::host())
    };

    let selected = select(&candidates, resolved.java_major, host_arch)?;
    for warning in &selected.warnings {
        eprintln!("warning: {}", java_warning(warning));
    }
    Ok(selected.runtime)
}

fn java_warning(warning: &JavaWarning) -> String {
    match warning {
        JavaWarning::TooNew { found, required } => {
            format!(
                "Java {found} selected, version {} recommends Java {required}",
                "game"
            )
        }
        JavaWarning::ArchMismatch { runtime, host } => {
            format!("runtime arch {runtime} differs from host arch {host}")
        }
        JavaWarning::UnknownVersion => "runtime version is unknown".to_string(),
    }
}

async fn scan_java(layout: &Layout, probe: bool) -> Vec<JavaRuntime> {
    let scanner = JavaScanner::new().with_extra_root(layout.runtimes());
    if probe {
        scanner.scan_probing(Duration::from_secs(5)).await
    } else {
        scanner.scan()
    }
}

async fn command_java(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: JavaArgs,
) -> Result<()> {
    let (layout, _downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    match args.command {
        JavaCommand::List(args) => {
            let runtimes = scan_java(&layout, !args.no_probe).await;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&runtimes).map_err(json_error)?
                );
                return Ok(());
            }
            if runtimes.is_empty() {
                println!("no Java runtime found");
                return Ok(());
            }
            println!(
                "{:<8} {:<8} {:<10} {:<20} home",
                "version", "arch", "source", "vendor"
            );
            for runtime in runtimes {
                println!(
                    "{:<8} {:<8} {:<10} {:<20} {}",
                    runtime
                        .version
                        .as_ref()
                        .map_or_else(|| "?".to_string(), |version| version.full.clone()),
                    runtime.arch,
                    runtime.source.label(),
                    runtime.vendor.as_deref().unwrap_or("-"),
                    runtime.home.display()
                );
            }
        }
        JavaCommand::Match(args) => {
            let resolved = resolver.resolve_local(&args.version)?;
            let runtimes = scan_java(&layout, true).await;
            let selected = select(&runtimes, resolved.java_major, JavaArch::host())?;
            if args.json {
                let payload = serde_json::json!({
                    "required": resolved.java_major,
                    "exact": selected.exact,
                    "runtime": selected.runtime,
                    "warnings": selected.warnings.iter().map(java_warning).collect::<Vec<_>>(),
                });
                println!(
                    "{}",
                    serde_json::to_string_pretty(&payload).map_err(json_error)?
                );
                return Ok(());
            }
            println!(
                "{} requires Java {}; selected {} ({} {})",
                args.version,
                resolved.java_major,
                selected.runtime.home.display(),
                selected
                    .runtime
                    .version
                    .as_ref()
                    .map_or_else(|| "?".to_string(), |version| version.full.clone()),
                selected.runtime.arch
            );
            for warning in &selected.warnings {
                println!("warning: {}", java_warning(warning));
            }
        }
    }
    Ok(())
}

fn command_instance(root: &std::path::Path, args: InstanceArgs) -> Result<()> {
    let layout = data_layout(root);
    match args.command {
        InstanceCommand::List(args) => {
            let mut instances = Vec::new();
            if let Ok(entries) = std::fs::read_dir(layout.instances()) {
                for entry in entries.flatten() {
                    let id = entry.file_name().to_string_lossy().to_string();
                    match Instance::load(&layout, &id) {
                        Ok(instance) => instances.push(instance),
                        Err(error) => eprintln!("skip {id}: {error}"),
                    }
                }
            }
            instances.sort_by(|left, right| left.id.cmp(&right.id));
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&instances).map_err(json_error)?
                );
                return Ok(());
            }
            if instances.is_empty() {
                println!("no instances; run `fg-cli install <version>`");
                return Ok(());
            }
            println!(
                "{:<18} {:<12} {:<10} last played",
                "id", "minecraft", "played"
            );
            for instance in instances {
                println!(
                    "{:<18} {:<12} {:<10} {}",
                    instance.id,
                    instance.minecraft,
                    format_duration(instance.meta.play_seconds),
                    instance.meta.last_played.as_deref().unwrap_or("-")
                );
            }
        }
        InstanceCommand::Show(args) => {
            let instance = Instance::load(&layout, &args.id)?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&instance).map_err(json_error)?
                );
                return Ok(());
            }
            println!("id          {}", instance.id);
            println!("name        {}", instance.name);
            println!("minecraft   {}", instance.minecraft);
            println!("loader      {}", instance.loader.kind);
            println!("game dir    {}", instance.game_dir(&layout).display());
            println!(
                "java        {}",
                instance
                    .java()
                    .map_or_else(|| "auto".to_string(), |path| path.display().to_string())
            );
            println!("memory      {} MiB", instance.memory_mb());
            println!(
                "resolution  {}",
                instance.resolution().map_or_else(
                    || "default".to_string(),
                    |(width, height)| format!("{width}x{height}")
                )
            );
            println!(
                "playtime    {}",
                format_duration(instance.meta.play_seconds)
            );
            println!(
                "last played {}",
                instance.meta.last_played.as_deref().unwrap_or("-")
            );
        }
    }
    Ok(())
}

fn spawn_graph_reporter(graph: Arc<TaskGraph>) -> tokio::task::JoinHandle<()> {
    let interactive = std::io::stderr().is_terminal();
    tokio::spawn(async move {
        let period = if interactive {
            Duration::from_millis(500)
        } else {
            Duration::from_secs(5)
        };
        let mut interval = tokio::time::interval(period);
        loop {
            interval.tick().await;
            let progress = graph.progress();
            let running: Vec<&str> = progress
                .nodes
                .iter()
                .filter(|node| node.state == NodeState::Running)
                .map(|node| node.name.as_str())
                .collect();
            let line = format!(
                "{:5.1}%  {:>10}/s  {:<40}",
                progress.fraction * 100.0,
                human_bytes(progress.speed.max(0.0) as u64),
                running.join(", ")
            );
            if interactive {
                eprint!("\r  {line}");
                let _ = std::io::stderr().flush();
            } else {
                eprintln!("  {line}");
            }
        }
    })
}

async fn run_graph_interruptible(graph: Arc<TaskGraph>) -> Result<GraphReport> {
    let control = Control::new();
    let options = RunOptions::default();
    let run = graph.run(&control, &options);
    tokio::pin!(run);
    loop {
        tokio::select! {
            report = &mut run => return Ok(report),
            signal = tokio::signal::ctrl_c() => {
                signal.map_err(|error| {
                    Error::with_detail(ErrorKind::Io, "error.cli.signal", error.to_string())
                })?;
                eprintln!("\n  cancelling; unfinished downloads keep their .part files");
                control.cancel();
            }
        }
    }
}

fn file_kind_label(kind: FileKind) -> &'static str {
    match kind {
        FileKind::Client => "client",
        FileKind::Library => "libraries",
        FileKind::Asset => "assets",
        FileKind::AssetIndex => "asset index",
        FileKind::LogConfig => "logging",
    }
}

fn format_duration(seconds: u64) -> String {
    if seconds == 0 {
        return "-".to_string();
    }
    if seconds < 60 {
        return format!("{seconds}s");
    }
    let hours = seconds / 3600;
    let minutes = (seconds % 3600) / 60;
    if hours > 0 {
        format!("{hours}h{minutes:02}m")
    } else {
        format!("{minutes}m")
    }
}

fn json_error(error: serde_json::Error) -> Error {
    Error::with_detail(ErrorKind::Internal, "error.cli.json", error.to_string())
}
