//! Headless debug and CI entry point for the Fograin core.
//!
//! Commands cover downloads, install/launch, accounts, content and diagnostics:
//!
//! ```text
//! fg-cli versions                 # cached version manifest
//! fg-cli install <version>        # install vanilla + create an instance
//! fg-cli launch <instance>        # selected account launch, streaming the game log
//! fg-cli java list                # discover JDKs
//! fg-cli java match <version>     # show the selected JDK for a version
//! fg-cli instance list            # installed instances
//! fg-cli download <url>           # download one file through the engine
//! fg-cli mirror <url>             # show the candidate list for a URL
//! fg-cli assets <version>         # download every asset object of a version
//! fg-cli probe <url>...           # HEAD-probe sources and update health data
//! fg-cli account list             # offline / third-party accounts
//! fg-cli content list <instance>  # local mods / resource packs / shaders
//! fg-cli content install ...      # Modrinth / CurseForge content
//! fg-cli world list <instance>    # worlds and backups
//! fg-cli diag analyze <instance>  # crash analysis and the support bundle
//! fg-cli datapack list ...        # per-world data packs
//! fg-cli modpack inspect/import   # modpack formats and import
//! ```

use std::{
    collections::{BTreeMap, HashSet},
    io::{IsTerminal as _, Write as _},
    path::{Path, PathBuf},
    sync::{
        Arc,
        atomic::{AtomicU64, AtomicUsize, Ordering},
    },
    time::{Duration, Instant},
};

use clap::{Args, Parser, Subcommand, ValueEnum};
use fg_auth::{
    Account, AccountKind, AccountStore, AuthResponse, AuthSession, AuthlibInjector, InjectorSource,
    OfflineAccount, YggdrasilClient, YggdrasilProfile, YggdrasilServer, random_token,
};
use fg_content::{
    ContentClient, ContentIndex, ContentKind, ContentSource, InstallRequest, SearchQuery,
    check_updates, delete_local, plan_install, scan_local, set_enabled,
};
use fg_core::{DownloadSource, Error, ErrorKind, Result, ThemePreference, config, paths};
use fg_game::install::{FileKind, InstallPlan, Installer, category_counts};
use fg_game::instance::Instance;
use fg_game::launch::{
    GameEvent, GameProcess, LaunchOptions, LaunchPlan, LogStream, cleanup_launch_artifacts,
    needs_x86_64_java,
};
use fg_game::layout::{Layout, validate_id};
use fg_game::manager::InstanceManager;
use fg_game::resolver::VersionResolver;
use fg_game::rules::RuleContext;
use fg_java::{
    JavaArch, JavaManager, JavaRequirement, JavaRuntime, JavaScanner, JavaSource, JavaWarning,
    normalize_home, select, select_available, verify_downloaded,
};
use fg_loader::{LoaderKind, LoaderPaths, LoaderPlanner, VersionListQuery};
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

    /// Never download a missing Java runtime for this command.
    #[arg(long, global = true)]
    no_auto_java: bool,

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
    /// List loader versions for a Minecraft version.
    Loaders(LoadersArgs),
    /// Install a vanilla version or a loader and create an instance.
    Install(InstallArgs),
    /// Launch an installed instance with an offline account.
    Launch(LaunchArgs),
    /// Discover, download and remove Java runtimes.
    Java(JavaArgs),
    /// Manage offline and third-party accounts.
    Account(AccountArgs),
    /// Inspect installed instances.
    Instance(InstanceArgs),
    /// Manage mods, resource packs, shader packs and content downloads.
    Content(ContentArgs),
    /// List, back up, restore and delete worlds.
    World(WorldArgs),
    /// Crash analysis and the diagnostic bundle.
    Diag(DiagArgs),
    /// Per-world data packs.
    Datapack(DatapackArgs),
    /// Modpack inspection, import and export.
    Modpack(ModpackArgs),
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

    /// Loader kind: fabric, quilt, forge, neoforge or optifine.
    #[arg(long)]
    loader: Option<String>,

    /// Loader version; defaults to the newest stable build.
    #[arg(long)]
    loader_version: Option<String>,

    /// Filled from the global `--no-auto-java` switch; not a CLI argument.
    #[arg(skip)]
    auto_java: bool,
}

#[derive(Debug, Args)]
struct LoadersArgs {
    /// Minecraft version, e.g. 1.21.4.
    minecraft: String,

    /// Loader kind: fabric, quilt, forge, neoforge or optifine.
    #[arg(long, default_value = "fabric")]
    kind: String,

    /// Number of versions to print (newest first).
    #[arg(long, default_value_t = 30)]
    limit: usize,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct LaunchArgs {
    /// Instance id from `fg-cli instance list`.
    instance: String,

    /// Account id to use instead of the selected account.
    #[arg(long)]
    account: Option<String>,

    /// One-shot offline player name; overrides the selected account for this
    /// launch without changing the account list.
    #[arg(long)]
    username: Option<String>,

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

    /// Filled from the global `--no-auto-java` switch; not a CLI argument.
    #[arg(skip)]
    auto_java: bool,
}

#[derive(Debug, Args)]
struct JavaArgs {
    #[command(subcommand)]
    command: JavaCommand,
}

#[derive(Debug, Args)]
struct AccountArgs {
    #[command(subcommand)]
    command: AccountCommand,
}

#[derive(Debug, Subcommand)]
enum AccountCommand {
    /// List saved accounts; `*` marks the selected one.
    List(AccountListArgs),
    /// Create and select an offline account.
    AddOffline(AccountAddOfflineArgs),
    /// Sign in to a third-party Yggdrasil / authlib-injector server.
    Login(AccountLoginArgs),
    /// Inspect an ALI API root and print its metadata.
    Discover(AccountDiscoverArgs),
    /// Select a saved account.
    Select(AccountSelectArgs),
    /// Remove a saved account and revoke its token when possible.
    Remove(AccountRemoveArgs),
    /// Validate (and refresh) a saved account token.
    Refresh(AccountRefreshArgs),
    /// Download or locate the cached authlib-injector jar.
    Injector,
}

#[derive(Debug, Args)]
struct AccountListArgs {
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct AccountAddOfflineArgs {
    /// 3–16 characters of `[A-Za-z0-9_]`.
    name: String,
}

#[derive(Debug, Args)]
struct AccountLoginArgs {
    /// Yggdrasil API root, e.g. https://littleskin.cn/api/yggdrasil.
    server: String,

    /// Account name or email.
    #[arg(long)]
    username: String,

    /// Password. Prefer FOGRAIN_AUTH_PASSWORD or --password-stdin to keep it
    /// out of the shell history.
    #[arg(long)]
    password: Option<String>,

    /// Read the password from one stdin line.
    #[arg(long)]
    password_stdin: bool,

    /// Profile id when the account has more than one character.
    #[arg(long)]
    profile: Option<String>,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct AccountDiscoverArgs {
    server: String,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct AccountSelectArgs {
    id: String,
}

#[derive(Debug, Args)]
struct AccountRemoveArgs {
    id: String,
}

#[derive(Debug, Args)]
struct AccountRefreshArgs {
    /// Account id; defaults to the selected account.
    #[arg(long)]
    id: Option<String>,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Subcommand)]
enum JavaCommand {
    /// List discovered runtimes.
    List(JavaListArgs),
    /// Show the runtime selected for a version.
    Match(JavaMatchArgs),
    /// List downloadable official runtimes for this platform.
    Catalog(JavaCatalogArgs),
    /// Download and install a runtime by Java major.
    Install(JavaInstallArgs),
    /// Remove a launcher-managed runtime.
    Remove(JavaRemoveArgs),
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
struct JavaCatalogArgs {
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct JavaInstallArgs {
    /// Java feature release, e.g. 8, 17 or 21.
    major: u32,

    /// Mojang runtime component; defaults to the one for this major.
    #[arg(long)]
    component: Option<String>,

    /// Print the download plan without installing.
    #[arg(long)]
    dry_run: bool,

    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct JavaRemoveArgs {
    /// Runtime directory id from `fg-cli java list`.
    id: String,
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
    /// Create an empty instance without installing files.
    Create(InstanceCreateArgs),
    /// Register an external `.minecraft` directory (files stay in place).
    Import(InstanceImportArgs),
    /// Delete an instance directory (shared game files stay installed).
    Delete(InstanceDeleteArgs),
}

#[derive(Debug, Args)]
struct InstanceCreateArgs {
    id: String,
    /// Minecraft version the instance targets.
    version: String,
    /// Display name; defaults to the id.
    #[arg(long)]
    name: Option<String>,
}

#[derive(Debug, Args)]
struct InstanceImportArgs {
    directory: std::path::PathBuf,
    #[arg(long)]
    name: Option<String>,
}

#[derive(Debug, Args)]
struct InstanceDeleteArgs {
    id: String,
}

#[derive(Debug, Args)]
struct ContentArgs {
    #[command(subcommand)]
    command: ContentCommand,
}

#[derive(Debug, Subcommand)]
enum ContentCommand {
    /// List installed mods, resource packs and shader packs.
    List(ContentListArgs),
    /// Enable or disable one installed file.
    Toggle(ContentToggleArgs),
    /// Delete one installed file and its index entry.
    Delete(ContentDeleteArgs),
    /// Search Modrinth or CurseForge.
    Search(ContentSearchArgs),
    /// List versions of a project.
    Versions(ContentVersionsArgs),
    /// Install a project version (and required dependencies) into an instance.
    Install(ContentInstallArgs),
    /// Check installed content for updates.
    Update(ContentUpdateArgs),
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum ContentKindArg {
    Mod,
    Modpack,
    Resourcepack,
    Shaderpack,
    Datapack,
}

impl From<ContentKindArg> for ContentKind {
    fn from(value: ContentKindArg) -> Self {
        match value {
            ContentKindArg::Mod => Self::Mod,
            ContentKindArg::Modpack => Self::Modpack,
            ContentKindArg::Resourcepack => Self::ResourcePack,
            ContentKindArg::Shaderpack => Self::ShaderPack,
            ContentKindArg::Datapack => Self::Datapack,
        }
    }
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum ContentSourceArg {
    Modrinth,
    Curseforge,
}

impl From<ContentSourceArg> for ContentSource {
    fn from(value: ContentSourceArg) -> Self {
        match value {
            ContentSourceArg::Modrinth => Self::Modrinth,
            ContentSourceArg::Curseforge => Self::Curseforge,
        }
    }
}

#[derive(Debug, Args)]
struct ContentListArgs {
    instance: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct ContentToggleArgs {
    instance: String,
    kind: ContentKindArg,
    file: String,
    #[arg(long, conflicts_with = "enable")]
    disable: bool,
    #[arg(long, conflicts_with = "disable")]
    enable: bool,
}

#[derive(Debug, Args)]
struct ContentDeleteArgs {
    instance: String,
    kind: ContentKindArg,
    file: String,
    #[arg(long)]
    yes: bool,
}

#[derive(Debug, Args)]
struct ContentSearchArgs {
    kind: ContentKindArg,
    query: Option<String>,
    /// Content provider: Modrinth or CurseForge (through MCIM).
    #[arg(long = "provider", value_enum, default_value_t = ContentSourceArg::Modrinth)]
    content_source: ContentSourceArg,
    #[arg(long)]
    instance: Option<String>,
    #[arg(long, default_value_t = 20)]
    limit: u32,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct ContentVersionsArgs {
    project: String,
    kind: ContentKindArg,
    /// Content provider: Modrinth or CurseForge (through MCIM).
    #[arg(long = "provider", value_enum, default_value_t = ContentSourceArg::Modrinth)]
    content_source: ContentSourceArg,
    #[arg(long)]
    instance: Option<String>,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct ContentInstallArgs {
    project: String,
    kind: ContentKindArg,
    #[arg(long)]
    instance: String,
    /// Content provider: Modrinth or CurseForge (through MCIM).
    #[arg(long = "provider", value_enum, default_value_t = ContentSourceArg::Modrinth)]
    content_source: ContentSourceArg,
    #[arg(long)]
    version: Option<String>,
    /// Target world for data packs.
    #[arg(long)]
    world: Option<String>,
    /// Old file name to remove after the new files are installed.
    #[arg(long = "replace")]
    replace: Vec<String>,
    #[arg(long)]
    dry_run: bool,
}

#[derive(Debug, Args)]
struct ContentUpdateArgs {
    instance: String,
    #[arg(long)]
    kind: Option<ContentKindArg>,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct WorldArgs {
    #[command(subcommand)]
    command: WorldCommand,
}

#[derive(Debug, Subcommand)]
enum WorldCommand {
    /// List worlds and backups.
    List(WorldListArgs),
    /// Back up one world into <root>/backups/<instance>/.
    Backup(WorldBackupArgs),
    /// List backups of an instance.
    Backups(WorldBackupsArgs),
    /// Restore a backup into a new world folder.
    Restore(WorldRestoreArgs),
    /// Delete a world folder.
    Delete(WorldDeleteArgs),
    /// Delete one backup file.
    BackupDelete(WorldBackupDeleteArgs),
}

#[derive(Debug, Args)]
struct WorldListArgs {
    instance: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct WorldBackupArgs {
    instance: String,
    world: String,
}

#[derive(Debug, Args)]
struct WorldBackupsArgs {
    instance: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct WorldRestoreArgs {
    instance: String,
    backup: String,
    /// New world folder name; defaults to the backup file stem plus "-restored".
    #[arg(long)]
    name: Option<String>,
}

#[derive(Debug, Args)]
struct WorldDeleteArgs {
    instance: String,
    world: String,
    #[arg(long)]
    yes: bool,
}

#[derive(Debug, Args)]
struct WorldBackupDeleteArgs {
    instance: String,
    backup: String,
    #[arg(long)]
    yes: bool,
}

#[derive(Debug, Args)]
struct DiagArgs {
    #[command(subcommand)]
    command: DiagCommand,
}

#[derive(Debug, Subcommand)]
enum DiagCommand {
    /// List the diagnostic sources of an instance.
    Logs(DiagInstanceArgs),
    /// Analyze logs and crash reports.
    Analyze(DiagInstanceArgs),
    /// Write a redacted diagnostic bundle.
    Export(DiagInstanceArgs),
}

#[derive(Debug, Args)]
struct DiagInstanceArgs {
    instance: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct DatapackArgs {
    #[command(subcommand)]
    command: DatapackCommand,
}

#[derive(Debug, Subcommand)]
enum DatapackCommand {
    /// List the data packs of one world.
    List(DatapackListArgs),
    /// Enable or disable one data pack.
    Toggle(DatapackToggleArgs),
    /// Delete one data pack.
    Delete(DatapackDeleteArgs),
    /// Copy a `.zip` data pack into the world.
    Import(DatapackImportArgs),
}

#[derive(Debug, Args)]
struct DatapackListArgs {
    instance: String,
    world: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct DatapackToggleArgs {
    instance: String,
    world: String,
    file: String,
    #[arg(long, conflicts_with = "enable")]
    disable: bool,
    #[arg(long, conflicts_with = "disable")]
    enable: bool,
}

#[derive(Debug, Args)]
struct DatapackDeleteArgs {
    instance: String,
    world: String,
    file: String,
    #[arg(long)]
    yes: bool,
}

#[derive(Debug, Args)]
struct DatapackImportArgs {
    instance: String,
    world: String,
    path: String,
}

#[derive(Debug, Args)]
struct ModpackArgs {
    #[command(subcommand)]
    command: ModpackCommand,
}

#[derive(Debug, Subcommand)]
enum ModpackCommand {
    /// Detect the pack format and print its metadata.
    Inspect(ModpackInspectArgs),
    /// Import a pack into a new or an existing instance.
    Import(ModpackImportArgs),
    /// Export an instance as a `.mrpack` under <root>/exports.
    Export(ModpackExportArgs),
}

#[derive(Debug, Args)]
struct ModpackInspectArgs {
    path: String,
    #[arg(long)]
    json: bool,
}

#[derive(Debug, Args)]
struct ModpackImportArgs {
    path: String,
    /// Apply the pack to an existing instance instead of creating one.
    #[arg(long)]
    instance: Option<String>,
    #[arg(long)]
    dry_run: bool,
}

#[derive(Debug, Args)]
struct ModpackExportArgs {
    instance: String,
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
    let auto_java = !cli.no_auto_java
        && config::read_config(&root).map_or(true, |config| config.java.auto_download);

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
        Command::Loaders(args) => {
            command_loaders(
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
        Command::Install(mut args) => {
            args.auto_java = auto_java;
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
        Command::Launch(mut args) => {
            args.auto_java = auto_java;
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
        Command::Account(args) => {
            command_account(
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
        Command::Content(args) => {
            command_content(
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
        Command::World(args) => command_world(&root, args),
        Command::Diag(args) => command_diag(&root, args),
        Command::Datapack(args) => command_datapack(&root, args),
        Command::Modpack(args) => {
            command_modpack(
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
// Install and launch
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

async fn command_loaders(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: LoadersArgs,
) -> Result<()> {
    let (layout, downloader, _resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    let kind = LoaderKind::parse(&args.kind)?;
    let planner = LoaderPlanner::new(
        &downloader,
        table,
        source,
        region,
        LoaderPaths::new(layout.root(), layout.libraries(), layout.cache()),
    );
    let versions = fg_loader::versions(
        planner.downloader,
        planner.table,
        planner.source,
        planner.region,
        VersionListQuery {
            kind,
            minecraft: &args.minecraft,
            limit: args.limit,
        },
    )
    .await?;
    if args.json {
        println!(
            "{}",
            serde_json::to_string_pretty(&versions).map_err(json_error)?
        );
        return Ok(());
    }
    println!("{} for Minecraft {}", kind.title(), args.minecraft);
    for version in versions {
        println!(
            "  {:<20} {}{}",
            version.version,
            if version.stable { "" } else { "preview" },
            version
                .note
                .as_deref()
                .map(|note| format!("  (Forge {note})"))
                .unwrap_or_default()
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

    let loader_kind = args.loader.as_deref().map(LoaderKind::parse).transpose()?;
    let loader_version = if let Some(kind) = loader_kind {
        let version = if let Some(version) = args
            .loader_version
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
        {
            version.to_string()
        } else {
            LoaderPlanner::new(
                &downloader,
                table,
                source,
                region,
                LoaderPaths::new(layout.root(), layout.libraries(), layout.cache()),
            )
            .latest_version(kind, &args.version)
            .await?
            .version
        };
        Some(version)
    } else {
        None
    };
    let base = resolver.resolve_remote(&args.version).await?;
    let java = if loader_kind.is_some_and(LoaderKind::requires_java) && !args.dry_run {
        if let Some(path) = config::read_config(root)?.game.java {
            Some(explicit_runtime(&path)?.executable)
        } else {
            let manager = java_manager(&layout, &downloader, table, source, region);
            Some(
                ensure_java_for_cli(
                    &manager,
                    &layout,
                    base.java_major,
                    base.java_component(),
                    JavaArch::host(),
                    false,
                    args.auto_java,
                )
                .await?
                .runtime
                .executable,
            )
        }
    } else {
        None
    };
    let installer = Installer::new(
        downloader,
        resolver,
        layout.clone(),
        table.clone(),
        source,
        region,
        RuleContext::current(),
    )
    .with_java(java);
    println!(
        "planning {}{}",
        args.version,
        instance
            .as_ref()
            .map(|instance| format!(" for instance {}", instance.id))
            .unwrap_or_default()
    );
    let plan = match (loader_kind, loader_version.as_deref()) {
        (Some(kind), Some(version)) => {
            installer
                .plan_loader(
                    &args.version,
                    kind,
                    version,
                    instance.as_ref().map(|instance| instance.id.as_str()),
                )
                .await?
        }
        _ => {
            installer
                .plan(
                    &args.version,
                    args.refresh,
                    instance.as_ref().map(|instance| instance.id.as_str()),
                )
                .await?
        }
    };
    if let (Some(kind), Some(version)) = (loader_kind, loader_version.as_deref())
        && let Some(instance) = &mut instance
    {
        instance.set_loader(kind.as_str(), version, &plan.version_id);
    }
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
    plan.save(&layout)?;

    if args.dry_run {
        for file in missing.iter().take(20) {
            println!("  missing {} ({:?})", file.path.display(), file.kind);
        }
        if missing.len() > 20 {
            println!("  … {} more", missing.len() - 20);
        }
        return Ok(());
    }

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
    InstallPlan::remove(&layout, &plan.version_id);
    println!(
        "installed {} in {:.1}s",
        plan.version_id,
        report.elapsed.as_secs_f64()
    );
    println!("  client   {}", layout.version_jar(&plan.base_id).display());
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
    let (layout, downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    let mut instance = Instance::load(&layout, &args.instance)?;
    {
        // `[override]` inherits `launcher.toml [game]` defaults when the
        // instance does not set its own value.
        let globals = config::read_config(root)?.game;
        instance.overrides = fg_game::manager::overrides_for_launch(&instance, &globals);
    }
    let version_id = instance.version_id().to_string();
    let resolved = resolver.resolve_local(&version_id).map_err(|error| {
        if error.kind == ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.launch.not_installed",
                format!(
                    "{version_id} is not installed; run `fg-cli install {}`",
                    instance.minecraft
                ),
            )
        } else {
            error
        }
    })?;

    let mut runtimes = scan_java(&layout, true).await;
    let explicit_java = args.java.is_some() || instance.java().is_some();
    let legacy_x64 = cfg!(target_os = "macos")
        && JavaArch::host() == JavaArch::Arm64
        && needs_x86_64_java(&resolved.base_id);
    if !explicit_java
        && (legacy_x64 || select(&runtimes, resolved.java_major, JavaArch::host()).is_err())
    {
        let manager = java_manager(&layout, &downloader, table, source, region);
        ensure_java_for_cli(
            &manager,
            &layout,
            resolved.java_major,
            resolved.java_component(),
            if legacy_x64 {
                JavaArch::X86_64
            } else {
                JavaArch::host()
            },
            legacy_x64,
            args.auto_java,
        )
        .await?;
        runtimes = scan_java(&layout, true).await;
    }
    let java = pick_java(&instance, &resolved, &args, &runtimes)?;
    let session = resolve_launch_session(
        root,
        table,
        source,
        region,
        &downloader,
        args.account.as_deref(),
        args.username.as_deref(),
    )
    .await?;

    let plan = LaunchPlan::build(
        &layout,
        &instance,
        &resolved,
        &java,
        &session,
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

    println!(
        "instance  {} ({}{})",
        instance.id,
        instance.minecraft,
        if instance.loader.is_vanilla() {
            String::new()
        } else {
            format!(" · {} {}", instance.loader.kind, instance.loader.version)
        }
    );
    println!(
        "java      {} ({} {})",
        java.executable.display(),
        java.version
            .as_ref()
            .map_or_else(|| "?".to_string(), |version| version.full.clone()),
        java.arch
    );
    println!(
        "account   {} ({}){}",
        session.name(),
        session.uuid(),
        if session.user_type() == fg_auth::UserType::Mojang {
            " · 第三方登录"
        } else {
            " · 离线"
        }
    );
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

fn explicit_runtime(path: &std::path::Path) -> Result<JavaRuntime> {
    let home = if path.is_dir() {
        normalize_home(path)
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
    JavaRuntime::from_home(home.clone(), JavaSource::ExtraRoot).ok_or_else(|| {
        Error::with_detail(
            ErrorKind::NotFound,
            "error.java.not_found",
            format!("{} does not contain bin/java", home.display()),
        )
    })
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
        let runtime = explicit_runtime(&path)?;
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
        JavaWarning::TooOld { found, required } => {
            format!("Java {found} selected; version recommends Java {required}")
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

fn java_manager(
    layout: &Layout,
    downloader: &Arc<Downloader>,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
) -> JavaManager {
    JavaManager::new(
        layout.root().to_path_buf(),
        downloader.clone(),
        table.clone(),
        source,
        region,
    )
}

/// Ensures a runtime exists for `required`, downloading the official Mojang
/// build when `allow_download` is on and nothing matches.
async fn ensure_java_for_cli(
    manager: &JavaManager,
    layout: &Layout,
    required: u32,
    component: Option<&str>,
    arch: JavaArch,
    require_arch: bool,
    allow_download: bool,
) -> Result<fg_java::JavaSelection> {
    let runtimes = scan_java(layout, true).await;
    let requirement = JavaRequirement::new(required, arch, require_arch);
    let selection = select_available(&runtimes, requirement);
    if let Ok(selection) = &selection {
        return Ok(selection.clone());
    }
    if !allow_download {
        return selection;
    }

    let candidate = manager.candidate_for(required, component, arch).await?;
    let plan = manager.plan(&candidate).await?;
    plan.save(layout.root())?;
    eprintln!(
        "downloading Java {} ({}, {}) — {} files, {}",
        plan.major,
        plan.component,
        plan.arch,
        plan.files.len(),
        human_bytes(plan.total_bytes)
    );
    let manager = Arc::new(manager.clone());
    let graph = Arc::new(manager.graph(plan.clone()));
    let reporter = spawn_graph_reporter(graph.clone());
    let report = run_graph_interruptible(graph).await?;
    reporter.abort();
    eprintln!();
    if !report.is_success() {
        let detail = report
            .failed
            .first()
            .map(|(node, error)| format!("{node}: {error}"))
            .unwrap_or_else(|| "Java download did not finish".to_string());
        return Err(Error::with_detail(
            ErrorKind::Internal,
            "error.java.runtime_install",
            detail,
        ));
    }
    println!(
        "installed Java {} at {}",
        plan.major,
        plan.runtime_dir(layout.root()).display()
    );
    let runtimes = scan_java(layout, true).await;
    let selection = select(&runtimes, required, arch)?;
    verify_downloaded(&selection, requirement)?;
    Ok(selection)
}

/// `fg-cli account ...`: account repository, offline creation and third-party
/// login. Skin management is intentionally absent.
async fn command_account(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: AccountArgs,
) -> Result<()> {
    let config = config::read_config(root)?;
    let mut store = AccountStore::load_or_default(root, &config.game.username)?;

    match args.command {
        AccountCommand::Injector => {
            let downloader =
                build_downloader(root, concurrency, rate_limit_kib, false, None, None, 16)?;
            let jar = InjectorSource::new(build_http_client(root)?, table.clone(), source, region)
                .ensure(root, &downloader)
                .await?;
            println!("{}", jar.display());
        }
        AccountCommand::List(args) => {
            let selected = store.selected_id().unwrap_or_default().to_string();
            if args.json {
                let accounts: Vec<serde_json::Value> = store
                    .accounts()
                    .iter()
                    .map(|account| account_json(account, &selected))
                    .collect();
                println!(
                    "{}",
                    serde_json::to_string_pretty(&serde_json::json!({
                        "selectedId": selected,
                        "accounts": accounts,
                    }))
                    .map_err(json_error)?
                );
            } else {
                for account in store.accounts() {
                    print_account_line(account, account.id == selected);
                }
            }
        }
        AccountCommand::AddOffline(args) => {
            let account = store.add_offline(args.name.trim())?.clone();
            store.save()?;
            print_account_line(&account, true);
        }
        AccountCommand::Select(args) => {
            let account = store.select(&args.id)?.clone();
            store.save()?;
            print_account_line(&account, true);
        }
        AccountCommand::Remove(args) => {
            if let Some(account) = store.account(&args.id).cloned() {
                best_effort_invalidate(root, &account).await;
            }
            let removed = store.remove(&args.id);
            if store.accounts().is_empty() {
                let name = if fg_auth::validate_name(&config.game.username).is_ok() {
                    config.game.username.clone()
                } else {
                    "Steve".to_string()
                };
                store.add_offline(&name)?;
            }
            store.save()?;
            match removed {
                Some(account) => println!("removed  {} ({})", account.name, account.id),
                None => println!("no account with id {}", args.id),
            }
        }
        AccountCommand::Refresh(args) => {
            let account = match args.id.as_deref() {
                Some(id) => store.account(id).cloned(),
                None => store.selected().cloned(),
            }
            .ok_or_else(|| {
                Error::new(
                    ErrorKind::NotFound,
                    if args.id.is_some() {
                        "error.auth.account_not_found"
                    } else {
                        "error.auth.no_account"
                    },
                )
            })?;
            let refreshed = account
                .ensure_valid(&YggdrasilClient::new(build_http_client(root)?))
                .await?;
            store.update_account(refreshed.clone())?;
            store.save()?;
            if args.json {
                let selected = store.selected_id().unwrap_or_default().to_string();
                println!(
                    "{}",
                    serde_json::to_string_pretty(&account_json(&refreshed, &selected))
                        .map_err(json_error)?
                );
            } else {
                println!("refreshed {}", refreshed.name);
                print_account_line(&refreshed, true);
            }
        }
        AccountCommand::Discover(args) => {
            let server = YggdrasilClient::new(build_http_client(root)?)
                .discover(&args.server)
                .await?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&serde_json::json!({
                        "server": server.api_root(),
                        "serverName": server.server_name(),
                        "implementationName": server.implementation_name(),
                        "implementationVersion": server.implementation_version(),
                    }))
                    .map_err(json_error)?
                );
            } else {
                println!("api root   {}", server.api_root());
                println!("server     {}", server.server_name());
                if let Some(name) = server.implementation_name() {
                    println!(
                        "software   {} {}",
                        name,
                        server.implementation_version().unwrap_or("")
                    );
                }
            }
        }
        AccountCommand::Login(args) => {
            let client = YggdrasilClient::new(build_http_client(root)?);
            let server = client.discover(&args.server).await?;
            let password = resolve_login_password(&args)?;
            let client_token = random_token();
            let response = client
                .authenticate(&server, args.username.trim(), &password, &client_token)
                .await?;
            let profile = choose_profile(&response, args.profile.as_deref())?;
            let bound = if response
                .selected_profile
                .as_ref()
                .is_some_and(|selected| selected.id == profile.id)
            {
                response
            } else {
                client
                    .refresh(
                        &server,
                        &response.access_token,
                        &response.client_token,
                        Some(&profile),
                    )
                    .await?
            };
            let account = store
                .upsert_yggdrasil(&server, &bound, &profile, Some(args.username.trim()))?
                .clone();
            store.save()?;
            if args.json {
                let selected = store.selected_id().unwrap_or_default().to_string();
                println!(
                    "{}",
                    serde_json::to_string_pretty(&account_json(&account, &selected))
                        .map_err(json_error)?
                );
            } else {
                println!("signed in  {}", server.server_name());
                print_account_line(&account, true);
            }
        }
    }

    Ok(())
}

fn resolve_login_password(args: &AccountLoginArgs) -> Result<String> {
    if let Some(password) = &args.password
        && !password.is_empty()
    {
        return Ok(password.clone());
    }
    if let Ok(password) = std::env::var("FOGRAIN_AUTH_PASSWORD")
        && !password.is_empty()
    {
        return Ok(password);
    }
    if args.password_stdin {
        let mut line = String::new();
        std::io::stdin().read_line(&mut line)?;
        let password = line.trim_end_matches(['\r', '\n']).to_string();
        if !password.is_empty() {
            return Ok(password);
        }
    }
    Err(Error::new(
        ErrorKind::InvalidInput,
        "error.auth.credentials_required",
    ))
}

fn choose_profile(response: &AuthResponse, requested: Option<&str>) -> Result<YggdrasilProfile> {
    let find = |value: &str| -> Option<YggdrasilProfile> {
        let hex = value.replace('-', "");
        response
            .available_profiles
            .iter()
            .find(|profile| profile.id == value || profile.id_hex() == hex)
            .cloned()
    };
    if let Some(id) = requested {
        return find(id).ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.auth.profile_not_found",
                id.to_string(),
            )
        });
    }
    if let Some(selected) = &response.selected_profile {
        return Ok(selected.clone());
    }
    if response.available_profiles.len() == 1 {
        return Ok(response.available_profiles[0].clone());
    }
    eprintln!("this account has multiple profiles; choose one with --profile <id>:");
    for profile in &response.available_profiles {
        eprintln!("  {}  {}", profile.id, profile.name);
    }
    Err(Error::new(ErrorKind::Auth, "error.auth.profile_required"))
}

fn account_json(account: &Account, selected_id: &str) -> serde_json::Value {
    serde_json::json!({
        "id": account.id,
        "kind": account.kind.as_str(),
        "name": account.name,
        "uuid": account.uuid,
        "server": account.server,
        "serverName": account.server_name,
        "selected": account.id == selected_id,
    })
}

fn print_account_line(account: &Account, selected: bool) {
    let marker = if selected { "*" } else { " " };
    let source = account
        .server_name
        .as_deref()
        .or(account.server.as_deref())
        .unwrap_or("-");
    println!(
        "{marker} {}  {}  {}  {}",
        account.name,
        account.uuid,
        account.kind.as_str(),
        source
    );
}

fn build_http_client(root: &std::path::Path) -> Result<Client> {
    let config = config::read_config(root)?;
    Client::builder().from_config(&config.network)?.build()
}

/// Resolves the account used by `fg-cli launch`, refreshing Yggdrasil tokens
/// and caching authlib-injector when necessary.
async fn resolve_launch_session(
    root: &std::path::Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    downloader: &Downloader,
    account_id: Option<&str>,
    username: Option<&str>,
) -> Result<AuthSession> {
    if let Some(username) = username.map(str::trim).filter(|name| !name.is_empty()) {
        if account_id.is_some() {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.auth.conflicting_account",
                "use either --account or --username, not both",
            ));
        }
        return Ok(AuthSession::offline(&OfflineAccount::new(username)?));
    }

    let config = config::read_config(root)?;
    let mut store = AccountStore::load_or_default(root, &config.game.username)?;
    let account = match account_id {
        Some(id) => store.account(id).cloned(),
        None => store.selected().cloned(),
    }
    .ok_or_else(|| {
        Error::new(
            ErrorKind::NotFound,
            if account_id.is_some() {
                "error.auth.account_not_found"
            } else {
                "error.auth.no_account"
            },
        )
    })?;

    match account.kind {
        AccountKind::Offline => account.session(None),
        AccountKind::Yggdrasil => {
            let client = YggdrasilClient::new(build_http_client(root)?);
            let refreshed = account.ensure_valid(&client).await?;
            if refreshed != account {
                store.update_account(refreshed.clone())?;
                store.save()?;
            }
            let server = refreshed
                .server
                .clone()
                .ok_or_else(|| Error::new(ErrorKind::Auth, "error.auth.login_required"))?;
            let injector =
                InjectorSource::new(build_http_client(root)?, table.clone(), source, region)
                    .ensure(root, downloader)
                    .await?;
            refreshed.session(Some(AuthlibInjector::new(injector, server)))
        }
        AccountKind::Microsoft => Err(Error::new(
            ErrorKind::Unsupported,
            "error.auth.microsoft_pending",
        )),
    }
}

async fn best_effort_invalidate(root: &std::path::Path, account: &Account) {
    if account.kind != AccountKind::Yggdrasil {
        return;
    }
    let (Some(server), Some(access_token), Some(client_token)) = (
        account.server.as_deref(),
        account.access_token(),
        account.client_token(),
    ) else {
        return;
    };
    let server = YggdrasilServer::new(
        server,
        account.server_name.clone().unwrap_or_default(),
        None,
        None,
    );
    match build_http_client(root) {
        Ok(client) => {
            if let Err(error) = YggdrasilClient::new(client)
                .invalidate(&server, access_token, client_token)
                .await
            {
                tracing::warn!(
                    server = server.api_root(),
                    detail = %error,
                    "could not invalidate the removed account token"
                );
            }
        }
        Err(error) => {
            tracing::warn!(detail = %error, "could not build a client for token revocation");
        }
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
    let (layout, downloader, resolver) =
        build_shared(root, table, source, region, concurrency, rate_limit_kib)?;
    match args.command {
        JavaCommand::List(args) => {
            let runtimes = scan_java(&layout, !args.no_probe).await;
            let managed = java_manager(&layout, &downloader, table, source, region).installed();
            if args.json {
                let payload = serde_json::json!({
                    "runtimes": runtimes,
                    "managed": managed.iter().map(|runtime| &runtime.marker).collect::<Vec<_>>(),
                });
                println!(
                    "{}",
                    serde_json::to_string_pretty(&payload).map_err(json_error)?
                );
                return Ok(());
            }
            if runtimes.is_empty() {
                println!("no Java runtime found");
                return Ok(());
            }
            println!(
                "{:<8} {:<8} {:<10} {:<20} {:<7} home",
                "version", "arch", "source", "vendor", "managed"
            );
            for runtime in runtimes {
                let installed = managed
                    .iter()
                    .find(|installed| installed.runtime.home == runtime.home)
                    .map(|installed| installed.marker.id.as_str())
                    .unwrap_or("-");
                println!(
                    "{:<8} {:<8} {:<10} {:<20} {:<7} {}",
                    runtime
                        .version
                        .as_ref()
                        .map_or_else(|| "?".to_string(), |version| version.full.clone()),
                    runtime.arch,
                    runtime.source.label(),
                    runtime.vendor.as_deref().unwrap_or("-"),
                    installed,
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
                    "component": resolved.java_component(),
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
                "{} requires Java {} ({}); selected {} ({} {})",
                args.version,
                resolved.java_major,
                resolved.java_component().unwrap_or("no component"),
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
        JavaCommand::Catalog(args) => {
            let manager = java_manager(&layout, &downloader, table, source, region);
            let installed = manager.installed();
            let catalog = manager.catalog().await?;
            if args.json {
                let rows: Vec<serde_json::Value> = catalog
                    .iter()
                    .map(|candidate| {
                        let hit = installed.iter().find(|runtime| {
                            runtime.marker.major == candidate.major
                                && runtime.marker.arch == candidate.arch
                        });
                        serde_json::json!({
                            "runtimeId": fg_java::sanitize_id(&candidate.runtime_id()),
                            "major": candidate.major,
                            "component": candidate.component,
                            "version": candidate.version,
                            "arch": candidate.arch,
                            "platform": candidate.platform,
                            "fallback": candidate.fallback,
                            "installed": hit.is_some(),
                            "installedVersion": hit.map(|runtime| runtime.marker.version.clone()),
                        })
                    })
                    .collect();
                println!(
                    "{}",
                    serde_json::to_string_pretty(&rows).map_err(json_error)?
                );
                return Ok(());
            }
            if catalog.is_empty() {
                println!("no official Java runtime is available for this platform");
                return Ok(());
            }
            println!(
                "{:<6} {:<24} {:<12} {:<16} installed",
                "java", "component", "version", "arch"
            );
            for candidate in catalog {
                let hit = installed.iter().find(|runtime| {
                    runtime.marker.major == candidate.major && runtime.marker.arch == candidate.arch
                });
                println!(
                    "{:<6} {:<24} {:<12} {:<16} {}",
                    candidate.major,
                    candidate.component,
                    candidate.version,
                    candidate.arch,
                    hit.map_or_else(
                        || if candidate.fallback {
                            "no (compat)"
                        } else {
                            "no"
                        }
                        .to_string(),
                        |runtime| format!("yes ({})", runtime.marker.version)
                    )
                );
            }
        }
        JavaCommand::Install(args) => {
            let manager = java_manager(&layout, &downloader, table, source, region);
            let candidate = manager
                .candidate(args.major, args.component.as_deref())
                .await?;
            let plan = manager.plan(&candidate).await?;
            plan.save(layout.root())?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&plan).map_err(json_error)?
                );
            } else {
                println!(
                    "Java {} · {} {} · {} · {} files · {}",
                    plan.major,
                    plan.component,
                    plan.version,
                    plan.arch,
                    plan.files.len(),
                    human_bytes(plan.total_bytes)
                );
                println!("  runtime {}", plan.runtime_dir(layout.root()).display());
            }
            if args.dry_run {
                for file in plan.files.iter().take(20) {
                    println!("  {}{}", file.path, if file.executable { " *" } else { "" });
                }
                if plan.files.len() > 20 {
                    println!("  … {} more", plan.files.len() - 20);
                }
                return Ok(());
            }
            let manager = Arc::new(manager);
            let graph = Arc::new(manager.graph(plan.clone()));
            let reporter = spawn_graph_reporter(graph.clone());
            let report = run_graph_interruptible(graph).await?;
            reporter.abort();
            eprintln!();
            if !report.is_success() {
                let detail = report
                    .failed
                    .first()
                    .map(|(node, error)| format!("{node}: {error}"))
                    .unwrap_or_else(|| "Java download did not finish".to_string());
                return Err(Error::with_detail(
                    ErrorKind::Internal,
                    "error.java.runtime_install",
                    detail,
                ));
            }
            println!(
                "installed Java {} at {}",
                plan.major,
                plan.runtime_dir(layout.root()).display()
            );
        }
        JavaCommand::Remove(args) => {
            let manager = java_manager(&layout, &downloader, table, source, region);
            let marker = manager.remove(&args.id)?;
            println!(
                "removed Java {} ({}) from {}",
                marker.major,
                marker.version,
                layout.runtimes().join(&args.id).display()
            );
        }
    }
    Ok(())
}

async fn command_content(
    root: &Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: ContentArgs,
) -> Result<()> {
    let layout = Layout::new(root);
    let loaded = config::read_config(root)?;
    let http = Client::builder().from_config(&loaded.network)?.build()?;
    let client = ContentClient::new(http, layout.cache(), table.clone(), source, region);

    match args.command {
        ContentCommand::List(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let index = ContentIndex::load(&layout.instance_dir(&instance.id))?;
            let mut items = Vec::new();
            for kind in ContentKind::ALL {
                items.extend(scan_local(&instance.game_dir(&layout), kind, Some(&index))?);
            }
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&items).map_err(json_error)?
                );
                return Ok(());
            }
            if items.is_empty() {
                println!("no content installed in {}", instance.id);
                return Ok(());
            }
            println!(
                "{:<12} {:<8} {:<28} {:<12} file",
                "kind", "enabled", "title", "version"
            );
            for item in items {
                println!(
                    "{:<12} {:<8} {:<28} {:<12} {}",
                    item.kind.as_str(),
                    if item.enabled { "yes" } else { "no" },
                    truncate_text(&item.title, 28),
                    truncate_text(item.version.as_deref().unwrap_or("-"), 12),
                    item.file_name
                );
            }
        }
        ContentCommand::Toggle(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let kind: ContentKind = args.kind.into();
            let enabled = if args.enable {
                true
            } else if args.disable {
                false
            } else {
                args.file.ends_with(".disabled")
            };
            let new_name = set_enabled(&instance.game_dir(&layout), kind, &args.file, enabled)?;
            let instance_dir = layout.instance_dir(&instance.id);
            let mut index = ContentIndex::load(&instance_dir)?;
            if let Some(mut entry) = index.find(&args.file).cloned() {
                index.remove(&args.file);
                entry.file_name.clone_from(&new_name);
                index.upsert(entry);
                index.save(&instance_dir)?;
            }
            println!(
                "{} {} -> {}",
                if enabled { "enabled" } else { "disabled" },
                args.file,
                new_name
            );
        }
        ContentCommand::Delete(args) => {
            if !args.yes {
                eprintln!("refusing to delete {} without --yes", args.file);
                return Ok(());
            }
            let instance = Instance::load(&layout, &args.instance)?;
            let kind: ContentKind = args.kind.into();
            delete_local(&instance.game_dir(&layout), kind, &args.file)?;
            let instance_dir = layout.instance_dir(&instance.id);
            let mut index = ContentIndex::load(&instance_dir)?;
            if index.find(&args.file).is_some() {
                index.remove(&args.file);
                index.save(&instance_dir)?;
            }
            println!("deleted {}", args.file);
        }
        ContentCommand::Search(args) => {
            let kind: ContentKind = args.kind.into();
            let source: ContentSource = args.content_source.into();
            let instance = match args.instance.as_deref() {
                Some(id) => Some(Instance::load(&layout, id)?),
                None => None,
            };
            let mut query = SearchQuery::new(kind)
                .with_query(args.query.unwrap_or_default())
                .with_page(0, args.limit.clamp(1, 50));
            if let Some(instance) = &instance {
                query = query.with_game_version(Some(instance.minecraft.clone()));
                if kind.loader_specific() {
                    query = query
                        .with_loader(fg_content::model::normalize_loader(&instance.loader.kind));
                }
            }
            let page = match source {
                ContentSource::Modrinth => client.modrinth_search(&query).await?,
                ContentSource::Curseforge => client.curseforge_search(&query).await?,
            };
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&page).map_err(json_error)?
                );
                return Ok(());
            }
            println!(
                "{} results from {} (showing {}):",
                page.total,
                source.label(),
                page.projects.len()
            );
            for project in page.projects {
                println!(
                    "  [{}] {} ({}) — {}",
                    project.project_id,
                    project.title,
                    human_count(project.downloads),
                    truncate_text(&project.description, 80)
                );
            }
        }
        ContentCommand::Versions(args) => {
            let kind: ContentKind = args.kind.into();
            let source: ContentSource = args.content_source.into();
            let instance = match args.instance.as_deref() {
                Some(id) => Some(Instance::load(&layout, id)?),
                None => None,
            };
            let game_version = instance.as_ref().map(|instance| instance.minecraft.clone());
            let loader = instance
                .as_ref()
                .filter(|_| kind.loader_specific())
                .and_then(|instance| fg_content::model::normalize_loader(&instance.loader.kind));
            let project = match source {
                ContentSource::Modrinth => client.modrinth_project(&args.project).await?,
                ContentSource::Curseforge => client.curseforge_project(&args.project).await?,
            };
            let versions = match source {
                ContentSource::Modrinth => {
                    client
                        .modrinth_versions(
                            &args.project,
                            game_version.as_deref(),
                            loader.as_deref(),
                        )
                        .await?
                }
                ContentSource::Curseforge => {
                    client
                        .curseforge_versions(
                            &args.project,
                            game_version.as_deref(),
                            loader.as_deref(),
                        )
                        .await?
                }
            };
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&serde_json::json!({
                        "project": project,
                        "versions": versions,
                    }))
                    .map_err(json_error)?
                );
                return Ok(());
            }
            println!("{} ({} versions)", project.title, versions.len());
            for version in versions {
                println!(
                    "  [{}] {} · {} · {}",
                    version.version_id,
                    version.version_number,
                    version.version_type.as_str(),
                    version
                        .file
                        .as_ref()
                        .map_or("-".to_string(), |file| file.file_name.clone())
                );
            }
        }
        ContentCommand::Install(args) => {
            let kind: ContentKind = args.kind.into();
            let source: ContentSource = args.content_source.into();
            let instance = Instance::load(&layout, &args.instance)?;
            let instance_dir = layout.instance_dir(&instance.id);
            let index = ContentIndex::load(&instance_dir)?;
            let plan = plan_install(
                &client,
                InstallRequest {
                    source,
                    project_id: args.project,
                    version_id: args.version,
                    kind,
                    instance_id: instance.id.clone(),
                    instance_dir,
                    game_dir: instance.game_dir(&layout),
                    game_version: instance.minecraft.clone(),
                    loader: kind
                        .loader_specific()
                        .then(|| fg_content::model::normalize_loader(&instance.loader.kind))
                        .flatten(),
                    world: args.world,
                    replace_files: args.replace,
                    installed_projects: index
                        .items
                        .iter()
                        .map(|entry| entry.project_id.clone())
                        .collect(),
                    install_dependencies: true,
                },
            )
            .await?;
            println!(
                "{} {} → {} file(s), {}",
                plan.project_name,
                plan.version_number,
                plan.items.len(),
                human_bytes(plan.total_bytes)
            );
            for item in &plan.items {
                println!(
                    "  {:<8} {:<32} {} ({} )",
                    if item.dependency == fg_content::PlannedFileKind::RequiredDependency {
                        "dep"
                    } else {
                        "primary"
                    },
                    truncate_text(&item.project_name, 32),
                    item.file_name,
                    human_bytes(item.size)
                );
            }
            for warning in &plan.warnings {
                eprintln!("warning: {warning}");
            }
            if args.dry_run {
                return Ok(());
            }
            let downloader =
                build_downloader(root, concurrency, rate_limit_kib, false, None, None, 16)?;
            let file_count = plan.items.len();
            let plan = Arc::new(plan);
            let graph = plan.graph(Arc::new(downloader), client);
            let report = run_graph_interruptible(Arc::new(graph)).await?;
            if report.is_success() {
                println!("installed {file_count} file(s)");
            } else {
                return Err(Error::with_detail(
                    ErrorKind::Internal,
                    "error.content.install",
                    report
                        .failed
                        .iter()
                        .map(|(node, error)| format!("{node}: {error}"))
                        .collect::<Vec<_>>()
                        .join(" | "),
                ));
            }
        }
        ContentCommand::Update(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let kind = args.kind.map(ContentKind::from);
            let loader = fg_content::model::normalize_loader(&instance.loader.kind);
            let check = check_updates(
                &client,
                &layout.instance_dir(&instance.id),
                &instance.game_dir(&layout),
                kind,
                &instance.minecraft,
                loader.as_deref(),
            )
            .await?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&check).map_err(json_error)?
                );
                return Ok(());
            }
            println!(
                "checked {}, {} update(s), {} unknown",
                check.checked,
                check.updates.len(),
                check.unknown
            );
            for update in check.updates {
                println!(
                    "  {:<12} {:<28} {} → {} [{}]",
                    update.kind.as_str(),
                    truncate_text(&update.project_name, 28),
                    update.current_version,
                    update.latest_version,
                    update.latest_version_id
                );
            }
            for warning in check.warnings {
                eprintln!("warning: {warning}");
            }
        }
    }
    Ok(())
}

fn command_diag(root: &Path, args: DiagArgs) -> Result<()> {
    let layout = Layout::new(root);
    match args.command {
        DiagCommand::Logs(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let bundle = fg_diag::collect(&instance.game_dir(&layout), Vec::new(), 2000);
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&bundle.sources).map_err(json_error)?
                );
                return Ok(());
            }
            println!("{:<6} {:<32} {:<10} lines", "kind", "name", "exists");
            for source in bundle.sources {
                println!(
                    "{:<6} {:<32} {:<10} {}",
                    source.kind.as_str(),
                    truncate_text(&source.name, 32),
                    if source.exists { "yes" } else { "no" },
                    source.lines
                );
            }
        }
        DiagCommand::Analyze(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let bundle = fg_diag::collect(&instance.game_dir(&layout), Vec::new(), 2000);
            let rules = fg_diag::RuleSet::load(Some(&root.join("diag").join("rules")))?;
            let analysis = fg_diag::analyze(&bundle, &rules);
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&analysis).map_err(json_error)?
                );
                return Ok(());
            }
            println!("{}", analysis.summary);
            for finding in &analysis.findings {
                println!(
                    "  [{:<7}] {:<28} {:.0}%  {}:{}",
                    finding.severity.as_str(),
                    truncate_text(&finding.title, 28),
                    finding.confidence * 100.0,
                    finding.source,
                    finding.line
                );
                if !finding.excerpt.is_empty() {
                    println!("           {}", truncate_text(&finding.excerpt, 100));
                }
            }
        }
        DiagCommand::Export(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let game_dir = instance.game_dir(&layout);
            let bundle = fg_diag::collect(&game_dir, Vec::new(), 2000);
            let rules = fg_diag::RuleSet::load(Some(&root.join("diag").join("rules")))?;
            let analysis = fg_diag::analyze(&bundle, &rules);
            let mut log_texts = Vec::new();
            for input in &bundle.inputs {
                let name = match input.kind {
                    fg_diag::LogSourceKind::Stdout => "logs/session.log".to_string(),
                    fg_diag::LogSourceKind::Log => "logs/latest.log".to_string(),
                    fg_diag::LogSourceKind::Crash => "crash-reports/latest.txt".to_string(),
                    fg_diag::LogSourceKind::HsErr => "hs_err.log".to_string(),
                };
                log_texts.push((name, input.lines.join("\n")));
            }
            let loader = if instance.loader.is_vanilla() {
                String::new()
            } else {
                format!("{} {}", instance.loader.kind, instance.loader.version)
            };
            let request = fg_diag::BundleRequest {
                data_root: root,
                instance_id: &instance.id,
                instance_name: &instance.name,
                minecraft: &instance.minecraft,
                loader: &loader,
                java: "unknown",
                memory_mb: 0,
                analysis: &analysis,
                log_texts,
                instance_json: serde_json::json!({
                    "instance": instance.id,
                    "minecraft": instance.minecraft,
                    "loaderKind": instance.loader.kind,
                    "loaderVersion": instance.loader.version,
                })
                .to_string(),
                app_version: env!("CARGO_PKG_VERSION"),
                platform: fg_core::Platform::detect().id(),
            };
            let bundle = fg_diag::export_bundle(&request)?;
            println!("wrote {} ({})", bundle.path, human_bytes(bundle.size_bytes));
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&bundle).map_err(json_error)?
                );
            }
        }
    }
    Ok(())
}

fn command_datapack(root: &Path, args: DatapackArgs) -> Result<()> {
    let layout = Layout::new(root);
    match args.command {
        DatapackCommand::List(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let items = fg_world::datapack::list(&instance.game_dir(&layout), &args.world)?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&items).map_err(json_error)?
                );
                return Ok(());
            }
            if items.is_empty() {
                println!("no data packs in {}/{}", instance.id, args.world);
                return Ok(());
            }
            for item in items {
                println!(
                    "{:<8} {:<32} {:<8} {:<12} {}",
                    if item.enabled { "enabled" } else { "off" },
                    truncate_text(&item.title, 32),
                    item.pack_format
                        .map_or("-".to_string(), |format| format.to_string()),
                    human_bytes(item.size_bytes),
                    item.file_name
                );
            }
        }
        DatapackCommand::Toggle(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let enabled = if args.enable {
                true
            } else if args.disable {
                false
            } else {
                args.file.ends_with(".disabled")
            };
            let name = fg_world::datapack::set_enabled(
                &instance.game_dir(&layout),
                &args.world,
                &args.file,
                enabled,
            )?;
            println!(
                "{} {} -> {}",
                if enabled { "enabled" } else { "disabled" },
                args.file,
                name
            );
        }
        DatapackCommand::Delete(args) => {
            if !args.yes {
                eprintln!("refusing to delete {} without --yes", args.file);
                return Ok(());
            }
            let instance = Instance::load(&layout, &args.instance)?;
            fg_world::datapack::delete(&instance.game_dir(&layout), &args.world, &args.file)?;
            println!("deleted {}", args.file);
        }
        DatapackCommand::Import(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let name = fg_world::datapack::import(
                &instance.game_dir(&layout),
                &args.world,
                Path::new(&args.path),
            )?;
            println!("imported {name}");
        }
    }
    Ok(())
}

async fn command_modpack(
    root: &Path,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    concurrency: Option<usize>,
    rate_limit_kib: u64,
    args: ModpackArgs,
) -> Result<()> {
    let layout = Layout::new(root);
    match args.command {
        ModpackCommand::Inspect(args) => {
            let pack = fg_content::modpack::inspect(Path::new(&args.path))?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&pack).map_err(json_error)?
                );
                return Ok(());
            }
            println!(
                "{} · {} {} · {} file(s) · {}{}",
                pack.name,
                pack.minecraft,
                if pack.loader_kind == "vanilla" {
                    "原版".to_string()
                } else {
                    format!("{} {}", pack.loader_kind, pack.loader_version)
                },
                pack.files.len(),
                human_bytes(pack.total_bytes()),
                if pack.external {
                    " · 外部目录"
                } else {
                    ""
                }
            );
            if let Some(summary) = &pack.summary {
                println!("{summary}");
            }
            if pack.skipped > 0 {
                println!("{} entr(ies) skipped", pack.skipped);
            }
        }
        ModpackCommand::Import(args) => {
            let pack = fg_content::modpack::inspect(Path::new(&args.path))?;
            let loaded = config::read_config(root)?;
            let manager = InstanceManager::new(layout.clone(), loaded.game.clone());
            if pack.external {
                let game_dir = pack.game_dir.clone().ok_or_else(|| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.modpack.directory",
                        pack.source_path.clone(),
                    )
                })?;
                let instance = manager.import_external(
                    Path::new(&game_dir),
                    Some(&pack.name),
                    Some(&pack.minecraft),
                )?;
                if pack.loader_kind != "vanilla" {
                    manager.set_loader(
                        &instance.id,
                        &pack.loader_kind,
                        &pack.loader_version,
                        "",
                    )?;
                }
                println!("imported external instance {}", instance.id);
                return Ok(());
            }
            let instance = match args.instance.as_deref() {
                Some(id) => {
                    let instance = manager.load(id)?;
                    if instance.minecraft != pack.minecraft {
                        return Err(Error::with_detail(
                            ErrorKind::Conflict,
                            "error.instance.version_mismatch",
                            format!(
                                "instance {} is for {}, pack is for {}",
                                instance.id, instance.minecraft, pack.minecraft
                            ),
                        ));
                    }
                    instance
                }
                None => {
                    let id = unique_modpack_id(&manager, &pack.name);
                    manager.create(
                        &id,
                        &pack.name,
                        &pack.minecraft,
                        (!pack.loader_kind.eq_ignore_ascii_case("vanilla"))
                            .then_some(pack.loader_kind.as_str()),
                        Some(&pack.loader_version),
                    )?
                }
            };
            println!(
                "{} → instance {} ({} file(s), {})",
                pack.name,
                instance.id,
                pack.files.len(),
                human_bytes(pack.total_bytes())
            );
            if args.dry_run {
                return Ok(());
            }
            let http = Client::builder().from_config(&loaded.network)?.build()?;
            let client = ContentClient::new(http, layout.cache(), table.clone(), source, region);
            let downloader =
                build_downloader(root, concurrency, rate_limit_kib, false, None, None, 16)?;
            let plan = fg_content::ModpackPlan::new(
                pack,
                PathBuf::from(&args.path),
                layout.instance_dir(&instance.id),
                instance.game_dir(&layout),
            );
            let graph = plan.graph(Arc::new(downloader), client);
            let report = run_graph_interruptible(Arc::new(graph)).await?;
            if report.is_success() {
                println!("imported into {}", instance.id);
            } else {
                return Err(Error::with_detail(
                    ErrorKind::Internal,
                    "error.modpack.import",
                    report
                        .failed
                        .iter()
                        .map(|(node, error)| format!("{node}: {error}"))
                        .collect::<Vec<_>>()
                        .join(" | "),
                ));
            }
        }
        ModpackCommand::Export(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let game_dir = instance.game_dir(&layout);
            let plan = fg_content::modpack::export_plan(&game_dir);
            let sections: Vec<String> = plan
                .sections
                .iter()
                .filter(|section| section.default)
                .map(|section| section.id.clone())
                .collect();
            let exports = root.join("exports");
            let output = fg_content::modpack::default_export_path(&exports, &instance.name, "1.0");
            let config = fg_content::modpack::ExportConfig {
                name: &instance.name,
                author: "",
                version: "1.0",
                description: "",
                minecraft: &instance.minecraft,
                loader_kind: &instance.loader.kind,
                loader_version: &instance.loader.version,
                game_dir: &game_dir,
                sections: &sections,
                output: &output,
            };
            let export = fg_content::modpack::export_mrpack(&config)?;
            println!(
                "wrote {} ({} file(s), {})",
                export.path,
                export.files,
                human_bytes(export.size_bytes)
            );
        }
    }
    Ok(())
}

fn unique_modpack_id(manager: &InstanceManager, name: &str) -> String {
    let mut base: String = name
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect();
    base.truncate(24);
    let base = if base.is_empty() {
        "modpack".to_string()
    } else {
        base
    };
    if !manager.exists(&base) {
        return base;
    }
    let mut counter = 2_u32;
    loop {
        let candidate = format!("{base}-{counter}");
        if !manager.exists(&candidate) {
            return candidate;
        }
        counter += 1;
    }
}

fn command_world(root: &Path, args: WorldArgs) -> Result<()> {
    let layout = Layout::new(root);
    match args.command {
        WorldCommand::List(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let worlds = fg_world::list_worlds(&instance.game_dir(&layout))?;
            let backups = fg_world::list_backups(root, &instance.id)?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&serde_json::json!({
                        "worlds": worlds,
                        "backups": backups,
                    }))
                    .map_err(json_error)?
                );
                return Ok(());
            }
            if worlds.is_empty() {
                println!("no worlds in {}", instance.id);
            } else {
                println!("{:<32} {:>10}  modified", "world", "size");
                for world in worlds {
                    println!(
                        "{:<32} {:>10}  {}",
                        truncate_text(&world.name, 32),
                        human_bytes(world.size_bytes),
                        world
                            .modified_ms
                            .map_or("-".to_string(), |ms| ms.to_string())
                    );
                }
            }
            println!("{} backup(s) in {}", backups.len(), instance.id);
        }
        WorldCommand::Backup(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let backup = fg_world::backup_world(
                root,
                &instance.id,
                &instance.game_dir(&layout),
                &args.world,
            )?;
            println!(
                "backed up {} -> {} ({})",
                args.world,
                backup.path,
                human_bytes(backup.size_bytes)
            );
        }
        WorldCommand::Backups(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let backups = fg_world::list_backups(root, &instance.id)?;
            if args.json {
                println!(
                    "{}",
                    serde_json::to_string_pretty(&backups).map_err(json_error)?
                );
                return Ok(());
            }
            for backup in backups {
                println!("{} ({})", backup.name, human_bytes(backup.size_bytes));
            }
        }
        WorldCommand::Restore(args) => {
            let instance = Instance::load(&layout, &args.instance)?;
            let name = args.name.unwrap_or_else(|| {
                let stem = Path::new(&args.backup)
                    .file_stem()
                    .map_or("world", |stem| stem.to_str().unwrap_or("world"));
                format!("{stem}-restored")
            });
            let path = fg_world::restore_backup(
                root,
                &instance.id,
                &instance.game_dir(&layout),
                &args.backup,
                &name,
            )?;
            println!("restored into {}", path.display());
        }
        WorldCommand::Delete(args) => {
            if !args.yes {
                eprintln!("refusing to delete {} without --yes", args.world);
                return Ok(());
            }
            let instance = Instance::load(&layout, &args.instance)?;
            fg_world::delete_world(&instance.game_dir(&layout), &args.world)?;
            println!("deleted {}", args.world);
        }
        WorldCommand::BackupDelete(args) => {
            if !args.yes {
                eprintln!("refusing to delete {} without --yes", args.backup);
                return Ok(());
            }
            let instance = Instance::load(&layout, &args.instance)?;
            fg_world::delete_backup(root, &instance.id, &args.backup)?;
            println!("deleted backup {}", args.backup);
        }
    }
    Ok(())
}

fn truncate_text(value: &str, max: usize) -> String {
    if value.chars().count() <= max {
        return value.to_string();
    }
    let mut truncated: String = value.chars().take(max.saturating_sub(1)).collect();
    truncated.push('…');
    truncated
}

fn human_count(value: u64) -> String {
    if value >= 1_000_000 {
        format!("{:.1}M", value as f64 / 1_000_000.0)
    } else if value >= 1_000 {
        format!("{:.1}k", value as f64 / 1_000.0)
    } else {
        value.to_string()
    }
}

fn command_instance(root: &std::path::Path, args: InstanceArgs) -> Result<()> {
    let layout = data_layout(root);
    let globals = config::read_config(root)?.game;
    let manager = fg_game::manager::InstanceManager::new(layout.clone(), globals);
    match args.command {
        InstanceCommand::Create(args) => {
            let instance = manager.create(
                &args.id,
                args.name.as_deref().unwrap_or(""),
                &args.version,
                None,
                None,
            )?;
            println!(
                "created {} ({})",
                instance.id,
                layout.instance_dir(&instance.id).display()
            );
            println!("  install files with: fg-cli install {}", args.version);
        }
        InstanceCommand::Import(args) => {
            let instance = manager.import_external(&args.directory, args.name.as_deref(), None)?;
            println!(
                "imported {} -> {} ({})",
                instance.name,
                instance.id,
                instance.game_dir(&layout).display()
            );
        }
        InstanceCommand::Delete(args) => {
            manager.delete(&args.id)?;
            println!("deleted {}", args.id);
        }
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
            println!(
                "loader      {}{}",
                instance.loader.kind,
                if instance.loader.version.is_empty() {
                    String::new()
                } else {
                    format!(" {}", instance.loader.version)
                }
            );
            if !instance.loader.version_id.is_empty() {
                println!("profile     {}", instance.loader.version_id);
            }
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
        FileKind::Loader => "loader",
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

#[cfg(test)]
mod tests {
    use super::*;

    fn profile(id: &str, name: &str) -> YggdrasilProfile {
        YggdrasilProfile {
            id: id.to_string(),
            name: name.to_string(),
        }
    }

    fn response(
        selected: Option<YggdrasilProfile>,
        available: Vec<YggdrasilProfile>,
    ) -> AuthResponse {
        AuthResponse {
            access_token: "access".to_string(),
            client_token: "client".to_string(),
            available_profiles: available,
            selected_profile: selected,
            user_id: None,
            user_properties: serde_json::json!({}),
        }
    }

    #[test]
    fn choose_profile_prefers_the_server_selection() {
        let selected = profile("a", "Alpha");
        let response = response(
            Some(selected.clone()),
            vec![selected.clone(), profile("b", "Beta")],
        );
        assert_eq!(choose_profile(&response, None).expect("selected"), selected);
    }

    #[test]
    fn choose_profile_accepts_a_dashless_profile_id() {
        let chosen = profile("8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5", "Fograin");
        let response = response(None, vec![chosen.clone(), profile("b", "Beta")]);
        assert_eq!(
            choose_profile(&response, Some("8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5")).expect("dashless"),
            chosen
        );
    }

    #[test]
    fn choose_profile_requires_a_choice_when_the_server_did_not_pick_one() {
        let response = response(None, vec![profile("a", "Alpha"), profile("b", "Beta")]);
        let error = choose_profile(&response, None).expect_err("ambiguous");
        assert_eq!(error.message_key, "error.auth.profile_required");
    }

    #[test]
    fn choose_profile_accepts_a_single_available_profile() {
        let only = profile("a", "Alpha");
        let response = response(None, vec![only.clone()]);
        assert_eq!(choose_profile(&response, None).expect("single"), only);
    }
}
