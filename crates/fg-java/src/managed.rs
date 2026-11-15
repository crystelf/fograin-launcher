//! Managed Java runtimes under `<root>/runtimes` (§4.1, §5.5, M6).
//!
//! The launcher downloads Mojang's official per-file runtime manifests, writes
//! them into `runtimes/<vendor>-<component>-<version>-<arch>/`, creates the
//! manifest's directories and links, marks executables and writes a
//! `fograin-runtime.json` marker once the runtime is complete. The existing
//! [`crate::JavaScanner`] then discovers the directory like any other JDK, so
//! launch and loader code need no special case.
//!
//! Plans are pure data and are saved under `cache/java/` for resume and for the
//! CLI's `--dry-run`. Downloads use `fg-net`, so mirrors, retries, SHA-1
//! validation and the content-addressed cache all apply.

use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{
    Candidate, DownloadCtx, DownloadRequest, Downloader, MirrorTable, Priority, ProgressSink,
    Region,
};
use fg_task::{TaskCx, TaskGraph};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};

use crate::catalog::{
    JavaRuntimeCandidate, MOJANG_RUNTIME_INDEX_URL, RuntimeIndex, host_platforms, platforms_for,
};
use crate::runtime::{JavaArch, JavaRuntime, JavaSource, executable_in};

/// Bump when the plan or marker shape changes incompatibly.
pub const RUNTIME_PLAN_SCHEMA_VERSION: u32 = 1;
/// Marker written into a completed managed runtime.
pub const RUNTIME_MARKER: &str = "fograin-runtime.json";

/// The macOS runtime manifest nests the actual JDK home under this prefix.
/// Bundle metadata (`Contents/Info.plist`, `Contents/MacOS/libjli.dylib`) stays
/// in place, so the runtime is installed exactly as Mojang ships it.
const MAC_HOME: &str = "jre.bundle/Contents/Home";

/// One file the runtime install must materialize.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedRuntimeFile {
    /// Relative to `runtimes/<id>/`.
    pub path: String,
    /// Official URL; mirror rules are applied when the request is built.
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub executable: bool,
}

/// One symbolic link declared by the runtime manifest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeLink {
    pub path: String,
    /// Relative target, exactly as the manifest spells it.
    pub target: String,
}

/// Serializable runtime install plan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimePlan {
    pub schema_version: u32,
    /// Safe directory name under `<root>/runtimes`.
    pub id: String,
    pub vendor: String,
    pub component: String,
    pub version: String,
    pub major: u32,
    pub arch: JavaArch,
    pub platform: String,
    pub manifest_url: String,
    #[serde(default)]
    pub manifest_sha1: Option<String>,
    /// Relative path of the JDK home inside the runtime directory. Empty on
    /// Windows/Linux; `jre.bundle/Contents/Home` for Mojang's macOS builds.
    #[serde(default)]
    pub home: String,
    #[serde(default)]
    pub files: Vec<PlannedRuntimeFile>,
    #[serde(default)]
    pub directories: Vec<String>,
    #[serde(default)]
    pub links: Vec<RuntimeLink>,
    pub total_bytes: u64,
}

impl RuntimePlan {
    /// Path of the resume plan under `<root>/cache/java/`.
    #[must_use]
    pub fn cache_path(root: &Path, id: &str) -> PathBuf {
        root.join("cache").join("java").join(format!("{id}.json"))
    }

    /// Directory that receives the runtime.
    #[must_use]
    pub fn runtime_dir(&self, root: &Path) -> PathBuf {
        root.join("runtimes").join(&self.id)
    }

    /// Directory that contains `bin/java`.
    #[must_use]
    pub fn home_dir(&self, root: &Path) -> PathBuf {
        let directory = self.runtime_dir(root);
        if self.home.is_empty() {
            directory
        } else {
            directory.join(&self.home)
        }
    }

    /// Saves the plan for resume and the CLI's dry-run output.
    ///
    /// # Errors
    ///
    /// Returns serialization and I/O errors.
    pub fn save(&self, root: &Path) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.java.runtime_plan_serialize",
                error.to_string(),
            )
        })?;
        write_atomic(&Self::cache_path(root, &self.id), &bytes)
    }

    /// Total download size in the same style as `InstallPlan::bytes_of`.
    #[must_use]
    pub fn file_count(&self) -> usize {
        self.files.len()
    }
}

/// Marker persisted inside a completed runtime.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeMarker {
    pub schema_version: u32,
    pub id: String,
    pub vendor: String,
    pub component: String,
    pub version: String,
    pub major: u32,
    pub arch: JavaArch,
    pub platform: String,
    #[serde(default)]
    pub manifest_sha1: Option<String>,
    /// Relative JDK home inside the runtime directory.
    #[serde(default)]
    pub home: String,
    pub file_count: usize,
    pub total_bytes: u64,
    pub installed_at_ms: u64,
}

/// A managed runtime discovered on disk.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstalledRuntime {
    pub marker: RuntimeMarker,
    pub runtime: JavaRuntime,
    pub directory: PathBuf,
}

impl InstalledRuntime {
    /// Human label used by the FFI and CLI.
    #[must_use]
    pub fn label(&self) -> String {
        format!(
            "Java {} ({}, {})",
            self.marker.major, self.marker.version, self.marker.arch
        )
    }
}

/// Downloads, installs, lists and removes launcher-managed runtimes.
#[derive(Clone)]
pub struct JavaManager {
    root: PathBuf,
    downloader: Arc<Downloader>,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
}

impl JavaManager {
    #[must_use]
    pub fn new(
        root: impl Into<PathBuf>,
        downloader: Arc<Downloader>,
        table: MirrorTable,
        source: DownloadSource,
        region: Region,
    ) -> Self {
        Self {
            root: root.into(),
            downloader,
            table,
            source,
            region,
        }
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    #[must_use]
    pub fn runtimes_dir(&self) -> PathBuf {
        self.root.join("runtimes")
    }

    /// Available runtimes for this host, one per major.
    ///
    /// # Errors
    ///
    /// Returns network, cache or parse errors from the Mojang index.
    pub async fn catalog(&self) -> Result<Vec<JavaRuntimeCandidate>> {
        let platforms = host_platforms();
        if platforms.is_empty() {
            return Ok(Vec::new());
        }
        let index = self.runtime_index().await?;
        Ok(index.catalog(&platforms))
    }

    /// Picks the runtime matching `required` (and optionally the version's
    /// `javaVersion.component`).
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Unsupported`] when the host has no official runtime
    /// for that major, or the index fetch error.
    pub async fn candidate(
        &self,
        required: u32,
        component: Option<&str>,
    ) -> Result<JavaRuntimeCandidate> {
        self.candidate_for(required, component, JavaArch::host())
            .await
    }

    /// Chooses a runtime for a specific architecture.
    ///
    /// Apple Silicon launches of 1.7–1.18 need an x86_64 Java 8 (the old LWJGL
    /// natives only ship that arch), so callers ask for `X86_64` even though the
    /// host is arm64. On macOS that maps to Mojang's `mac-os` platform.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Unsupported`] when no platform matches `arch`, or
    /// the index fetch/parse error.
    pub async fn candidate_for(
        &self,
        required: u32,
        component: Option<&str>,
        arch: JavaArch,
    ) -> Result<JavaRuntimeCandidate> {
        let platforms = if arch == JavaArch::host() {
            host_platforms()
        } else {
            platforms_for(std::env::consts::OS, arch)
        };
        if platforms.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.java.runtime_unsupported",
                format!("no managed runtime for {} ({arch})", std::env::consts::OS),
            ));
        }
        let index = self.runtime_index().await?;
        let candidate = index.select(&platforms, required, component)?;
        if candidate.arch != arch && candidate.arch != JavaArch::Unknown {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.java.runtime_unsupported",
                format!(
                    "no {arch} runtime for Java {required}; official catalog has {}",
                    candidate.arch
                ),
            ));
        }
        Ok(candidate)
    }

    /// Resolves a candidate into a complete, serializable install plan.
    ///
    /// # Errors
    ///
    /// Returns network/parse errors and [`ErrorKind::Parse`] for unsafe paths
    /// in the vendor manifest.
    pub async fn plan(&self, candidate: &JavaRuntimeCandidate) -> Result<RuntimePlan> {
        let cache_key = candidate.manifest.sha1.as_deref().map_or_else(
            || candidate.manifest.url.clone(),
            |sha1| format!("java-manifest-{sha1}"),
        );
        let urls = self
            .table
            .candidates(&candidate.manifest.url, self.source, self.region);
        let fetched = self.downloader.fetch_bytes(urls, &cache_key).await?;
        // BMCLAPI re-serializes JSON manifests, so their SHA-1 differs from
        // Mojang's canonical byte stream. Verify only when the official host
        // served the bytes; the manifest content itself is parsed and every
        // file download is still SHA-1 checked.
        if let Some(expected) = candidate.manifest.sha1.as_deref()
            && is_official_host(&fetched.url)
        {
            let actual = sha1_hex(&fetched.bytes);
            if !actual.eq_ignore_ascii_case(expected) {
                return Err(Error::with_detail(
                    ErrorKind::Network,
                    "error.java.runtime_manifest_hash",
                    format!("{}: expected sha1 {expected}, got {actual}", fetched.url),
                ));
            }
        }
        let manifest: RawManifest = serde_json::from_slice(&fetched.bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.java.runtime_manifest",
                format!("{}: {error}", fetched.url),
            )
        })?;
        manifest.into_plan(candidate, fetched.url.as_str())
    }

    /// Resolves and plans a runtime by major, fetching the catalog first.
    ///
    /// # Errors
    ///
    /// Propagates catalog selection and plan errors.
    pub async fn plan_for(&self, required: u32, component: Option<&str>) -> Result<RuntimePlan> {
        let candidate = self.candidate(required, component).await?;
        self.plan(&candidate).await
    }

    /// Builds the task graph that downloads and installs `plan`.
    #[must_use]
    pub fn graph(self: &Arc<Self>, plan: RuntimePlan) -> TaskGraph {
        let name = format!("java:{}", plan.major);
        let weight = (plan.total_bytes / (1024 * 1024)).clamp(1, 10_000) as u32;
        let manager = Arc::clone(self);
        let mut builder = TaskGraph::builder(name);
        builder.task("java.runtime", weight, move |cx| {
            let manager = Arc::clone(&manager);
            let plan = plan.clone();
            async move { manager.install(&plan, &cx).await.map(|_| ()) }
        });
        builder.build()
    }

    /// Downloads every planned file, then creates directories, links, executable
    /// bits and the completion marker.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] when any download fails, and
    /// [`ErrorKind::Io`] / [`ErrorKind::Internal`] for local failures. A partial
    /// directory is left in place so a retry resumes from the content cache.
    pub async fn install(&self, plan: &RuntimePlan, cx: &TaskCx) -> Result<RuntimeMarker> {
        if plan.schema_version != RUNTIME_PLAN_SCHEMA_VERSION {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.java.runtime_plan_version",
                format!(
                    "plan schema {}, expected {RUNTIME_PLAN_SCHEMA_VERSION}",
                    plan.schema_version
                ),
            ));
        }
        cx.check()?;
        let directory = plan.runtime_dir(&self.root);
        fs::create_dir_all(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", directory.display()),
            )
        })?;
        let _ = fs::remove_file(directory.join(RUNTIME_MARKER));

        cx.set_total(plan.total_bytes.max(1));
        let sink = Arc::new(TaskSink { cx: cx.clone() });
        let mut items = Vec::with_capacity(plan.files.len());
        for file in &plan.files {
            cx.check()?;
            let urls = self.table.candidates(&file.url, self.source, self.region);
            let candidates = urls
                .into_iter()
                .map(|url| Candidate::parse(&url))
                .collect::<Result<Vec<_>>>()?;
            let mut request = DownloadRequest::new(directory.join(&file.path), candidates)
                .with_priority(Priority::Library);
            if let Some(size) = file.size {
                request = request.with_size_hint(size);
            }
            if let Some(sha1) = &file.sha1 {
                request = request.with_sha1(sha1);
            } else if let Some(size) = file.size {
                request = request.with_size(size);
            }
            items.push((
                request,
                DownloadCtx {
                    cancel: cx.cancel_handle(),
                    progress: sink.clone(),
                },
            ));
        }

        let report = self.downloader.download_many_with(items).await;
        if !report.failed.is_empty() {
            let (path, error) = &report.failed[0];
            return Err(Error::with_detail(
                error.kind,
                "error.java.runtime_install",
                format!(
                    "{}: {} ({} of {} files failed)",
                    path.display(),
                    error,
                    report.failed.len(),
                    plan.files.len()
                ),
            ));
        }

        for relative in &plan.directories {
            let path = directory.join(relative);
            fs::create_dir_all(&path).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io.create_dir",
                    format!("{}: {error}", path.display()),
                )
            })?;
        }
        for link in &plan.links {
            create_link(&directory, link)?;
        }
        set_permissions(&directory, &plan.files)?;

        if executable_in(&directory.join(&plan.home)).is_none() {
            return Err(Error::with_detail(
                ErrorKind::Internal,
                "error.java.runtime_incomplete",
                format!("{} has no bin/java after installation", directory.display()),
            ));
        }

        let marker = RuntimeMarker {
            schema_version: RUNTIME_PLAN_SCHEMA_VERSION,
            id: plan.id.clone(),
            vendor: plan.vendor.clone(),
            component: plan.component.clone(),
            version: plan.version.clone(),
            major: plan.major,
            arch: plan.arch,
            platform: plan.platform.clone(),
            manifest_sha1: plan.manifest_sha1.clone(),
            home: plan.home.clone(),
            file_count: plan.files.len(),
            total_bytes: plan.total_bytes,
            installed_at_ms: now_ms(),
        };
        let bytes = serde_json::to_vec_pretty(&marker).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.java.runtime_marker",
                error.to_string(),
            )
        })?;
        write_atomic(&directory.join(RUNTIME_MARKER), &bytes)?;
        Ok(marker)
    }

    /// Every completed managed runtime, newest install first.
    #[must_use]
    pub fn installed(&self) -> Vec<InstalledRuntime> {
        let Ok(entries) = fs::read_dir(self.runtimes_dir()) else {
            return Vec::new();
        };
        let mut runtimes = Vec::new();
        for entry in entries.flatten() {
            let directory = entry.path();
            if !directory.is_dir() {
                continue;
            }
            let Ok(marker) = fs::read(directory.join(RUNTIME_MARKER)) else {
                continue;
            };
            let Ok(marker) = serde_json::from_slice::<RuntimeMarker>(&marker) else {
                continue;
            };
            if directory
                .file_name()
                .is_none_or(|name| name != marker.id.as_str())
            {
                continue;
            }
            let home = if marker.home.is_empty() {
                directory.clone()
            } else {
                directory.join(&marker.home)
            };
            if let Some(runtime) = JavaRuntime::from_home(&home, JavaSource::LauncherManaged) {
                runtimes.push(InstalledRuntime {
                    marker,
                    runtime,
                    directory,
                });
            }
        }
        runtimes.sort_by(|left, right| {
            right
                .marker
                .installed_at_ms
                .cmp(&left.marker.installed_at_ms)
                .then_with(|| left.marker.id.cmp(&right.marker.id))
        });
        runtimes
    }

    /// Removes one managed runtime and its saved plan.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for unsafe ids and
    /// [`ErrorKind::NotFound`] when the directory does not exist.
    pub fn remove(&self, id: &str) -> Result<RuntimeMarker> {
        validate_runtime_id(id)?;
        let runtimes = self.runtimes_dir();
        let directory = runtimes.join(id);
        fs::create_dir_all(&runtimes)?;
        let canonical_root = fs::canonicalize(&runtimes).unwrap_or_else(|_| runtimes.clone());
        let canonical_dir = fs::canonicalize(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.java.runtime_not_found",
                format!("{}: {error}", directory.display()),
            )
        })?;
        if !canonical_dir.starts_with(&canonical_root) {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.java.runtime_id",
                id.to_string(),
            ));
        }
        let marker = fs::read(directory.join(RUNTIME_MARKER))
            .ok()
            .and_then(|bytes| serde_json::from_slice::<RuntimeMarker>(&bytes).ok())
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.java.runtime_marker_missing",
                    directory.display().to_string(),
                )
            })?;
        fs::remove_dir_all(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.java.runtime_remove",
                format!("{}: {error}", directory.display()),
            )
        })?;
        let _ = fs::remove_file(RuntimePlan::cache_path(&self.root, id));
        Ok(marker)
    }

    async fn runtime_index(&self) -> Result<RuntimeIndex> {
        let urls = self
            .table
            .candidates(MOJANG_RUNTIME_INDEX_URL, self.source, self.region);
        let fetched = self
            .downloader
            .fetch_bytes(urls, "java-runtime-all")
            .await?;
        RuntimeIndex::parse(&fetched.bytes)
    }
}

#[derive(Debug, Deserialize)]
struct RawManifest {
    #[serde(default)]
    files: BTreeMap<String, RawEntry>,
}

#[derive(Debug, Deserialize)]
struct RawEntry {
    #[serde(rename = "type", default)]
    kind: String,
    #[serde(default)]
    executable: bool,
    #[serde(default)]
    target: Option<String>,
    #[serde(default)]
    downloads: Option<RawDownloads>,
}

#[derive(Debug, Deserialize)]
struct RawDownloads {
    #[serde(default)]
    raw: Option<RawDownload>,
}

#[derive(Debug, Deserialize)]
struct RawDownload {
    url: String,
    #[serde(default)]
    sha1: Option<String>,
    #[serde(default)]
    size: Option<u64>,
}

impl RawManifest {
    fn into_plan(
        self,
        candidate: &JavaRuntimeCandidate,
        manifest_url: &str,
    ) -> Result<RuntimePlan> {
        let home = if self
            .files
            .keys()
            .any(|path| path == &format!("{MAC_HOME}/release"))
        {
            MAC_HOME.to_string()
        } else {
            String::new()
        };
        let mut plan = RuntimePlan {
            schema_version: RUNTIME_PLAN_SCHEMA_VERSION,
            id: sanitize_id(&candidate.runtime_id()),
            vendor: candidate.vendor.clone(),
            component: candidate.component.clone(),
            version: candidate.version.clone(),
            major: candidate.major,
            arch: candidate.arch,
            platform: candidate.platform.clone(),
            manifest_url: manifest_url.to_string(),
            manifest_sha1: candidate.manifest.sha1.clone(),
            home,
            files: Vec::new(),
            directories: Vec::new(),
            links: Vec::new(),
            total_bytes: 0,
        };

        for (raw_path, entry) in self.files {
            if raw_path == MAC_HOME {
                continue;
            }
            let path = safe_relative(&raw_path)?;
            match entry.kind.as_str() {
                "directory" => plan.directories.push(path),
                "file" => {
                    let download = entry
                        .downloads
                        .and_then(|downloads| downloads.raw)
                        .ok_or_else(|| {
                            Error::with_detail(
                                ErrorKind::Parse,
                                "error.java.runtime_manifest",
                                format!("{raw_path}: file without downloads.raw"),
                            )
                        })?;
                    plan.total_bytes = plan.total_bytes.saturating_add(download.size.unwrap_or(0));
                    plan.files.push(PlannedRuntimeFile {
                        path,
                        url: download.url,
                        sha1: download.sha1,
                        size: download.size,
                        executable: entry.executable,
                    });
                }
                "link" => {
                    let target = entry.target.ok_or_else(|| {
                        Error::with_detail(
                            ErrorKind::Parse,
                            "error.java.runtime_manifest",
                            format!("{raw_path}: link without target"),
                        )
                    })?;
                    plan.links.push(RuntimeLink { path, target });
                }
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::Parse,
                        "error.java.runtime_manifest",
                        format!("{raw_path}: unknown entry type {other:?}"),
                    ));
                }
            }
        }

        if plan.files.is_empty() {
            return Err(Error::new(
                ErrorKind::Parse,
                "error.java.runtime_manifest_empty",
            ));
        }
        plan.directories.sort();
        plan.directories.dedup();
        plan.files.sort_by(|left, right| left.path.cmp(&right.path));
        plan.links.sort_by(|left, right| left.path.cmp(&right.path));
        Ok(plan)
    }
}

/// Builds a safe runtime id from a candidate id.
#[must_use]
pub fn sanitize_id(value: &str) -> String {
    let mut output = String::with_capacity(value.len());
    for character in value.chars() {
        if character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_') {
            output.push(character);
        } else {
            output.push('_');
        }
    }
    output.truncate(120);
    output
}

/// Rejects ids that could escape `runtimes/`.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for empty ids, separators and `..`.
pub fn validate_runtime_id(id: &str) -> Result<()> {
    if id.is_empty()
        || id == "."
        || id == ".."
        || id.contains('/')
        || id.contains('\\')
        || id.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.java.runtime_id",
            id.to_string(),
        ));
    }
    Ok(())
}

fn safe_relative(path: &str) -> Result<String> {
    let normalized = path.replace('\\', "/");
    if normalized.is_empty()
        || normalized.starts_with('/')
        || normalized.starts_with("../")
        || normalized.contains("/../")
        || normalized.ends_with("/..")
        || normalized.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::Parse,
            "error.java.runtime_path",
            path.to_string(),
        ));
    }
    Ok(normalized)
}

fn create_link(directory: &Path, link: &RuntimeLink) -> Result<()> {
    let path = directory.join(&link.path);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if path.symlink_metadata().is_ok() {
        fs::remove_file(&path).or_else(|_| fs::remove_dir_all(&path))?;
    }
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(&link.target, &path).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.java.runtime_link",
                format!("{} -> {}: {error}", path.display(), link.target),
            )
        })?;
    }
    #[cfg(not(unix))]
    {
        let resolved = path.parent().unwrap_or(directory).join(&link.target);
        fs::copy(&resolved, &path).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.java.runtime_link",
                format!("{} <- {}: {error}", path.display(), resolved.display()),
            )
        })?;
    }
    Ok(())
}

#[cfg(unix)]
fn set_permissions(directory: &Path, files: &[PlannedRuntimeFile]) -> Result<()> {
    use std::os::unix::fs::PermissionsExt as _;

    for file in files {
        let mode = if file.executable { 0o755 } else { 0o644 };
        let path = directory.join(&file.path);
        fs::set_permissions(&path, fs::Permissions::from_mode(mode)).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.java.runtime_permission",
                format!("{}: {error}", path.display()),
            )
        })?;
    }
    Ok(())
}

#[cfg(not(unix))]
fn set_permissions(_directory: &Path, _files: &[PlannedRuntimeFile]) -> Result<()> {
    Ok(())
}

fn write_atomic(path: &Path, contents: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, contents)?;
    fs::rename(&temporary, path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.rename",
            format!("{}: {error}", path.display()),
        )
    })
}

fn is_official_host(url: &fg_net::Url) -> bool {
    url.host_str().is_some_and(|host| {
        host == "piston-meta.mojang.com"
            || host == "launchermeta.mojang.com"
            || host == "piston-data.mojang.com"
            || host == "launcher.mojang.com"
    })
}

fn sha1_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

struct TaskSink {
    cx: TaskCx,
}

impl ProgressSink for TaskSink {
    fn on_bytes(&self, bytes: u64) {
        self.cx.advance(bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::catalog::RuntimeArtifact;

    fn candidate(manifest_url: &str) -> JavaRuntimeCandidate {
        JavaRuntimeCandidate {
            vendor: "mojang".to_string(),
            platform: "mac-os-arm64".to_string(),
            component: "java-runtime-delta".to_string(),
            version: "21.0.7".to_string(),
            major: 21,
            arch: JavaArch::Arm64,
            fallback: false,
            manifest: RuntimeArtifact {
                url: manifest_url.to_string(),
                sha1: None,
                size: None,
            },
        }
    }

    #[test]
    fn plan_preserves_the_macos_bundle_home_and_links() {
        let manifest: RawManifest = serde_json::from_value(serde_json::json!({
            "files": {
                "jre.bundle/Contents/Home": {"type": "directory"},
                "jre.bundle/Contents/Home/bin": {"type": "directory"},
                "jre.bundle/Contents/Home/bin/java": {
                    "type": "file",
                    "executable": true,
                    "downloads": {"raw": {
                        "url": "https://piston-data.mojang.com/v1/objects/aa/java",
                        "sha1": "aa",
                        "size": 10
                    }}
                },
                "jre.bundle/Contents/Home/release": {
                    "type": "file",
                    "downloads": {"raw": {
                        "url": "https://piston-data.mojang.com/v1/objects/bb/release",
                        "sha1": "bb",
                        "size": 20
                    }}
                },
                "jre.bundle/Contents/Home/bin/ControlPanel": {
                    "type": "link",
                    "target": "java"
                }
            }
        }))
        .expect("manifest");

        let plan = manifest
            .into_plan(
                &candidate("https://example.invalid/manifest.json"),
                "https://example.invalid/manifest.json",
            )
            .expect("plan");
        assert_eq!(plan.total_bytes, 30);
        assert_eq!(plan.home, "jre.bundle/Contents/Home");
        assert_eq!(
            plan.files
                .iter()
                .map(|file| file.path.as_str())
                .collect::<Vec<_>>(),
            vec![
                "jre.bundle/Contents/Home/bin/java",
                "jre.bundle/Contents/Home/release"
            ]
        );
        assert!(plan.files[0].executable);
        assert!(
            plan.directories
                .contains(&"jre.bundle/Contents/Home/bin".to_string())
        );
        assert_eq!(
            plan.links[0].path,
            "jre.bundle/Contents/Home/bin/ControlPanel"
        );
        assert_eq!(plan.id, "mojang-java-runtime-delta-21.0.7-arm64");
        assert_eq!(
            plan.home_dir(Path::new("/data")),
            Path::new(
                "/data/runtimes/mojang-java-runtime-delta-21.0.7-arm64/jre.bundle/Contents/Home"
            )
        );
    }

    #[test]
    fn plan_rejects_path_traversal() {
        let manifest: RawManifest = serde_json::from_value(serde_json::json!({
            "files": {
                "../evil": {"type": "directory"}
            }
        }))
        .expect("manifest");
        let error = manifest
            .into_plan(&candidate("https://example.invalid/manifest.json"), "x")
            .expect_err("traversal must fail");
        assert_eq!(error.message_key, "error.java.runtime_path");
    }

    #[test]
    fn wires_are_safe_relative_paths() {
        assert!(safe_relative("bin/java").is_ok());
        assert!(safe_relative("/etc/passwd").is_err());
        assert!(safe_relative("a/../../b").is_err());
        assert!(safe_relative("").is_err());
        assert!(validate_runtime_id("mojang-x-21.0.7-arm64").is_ok());
        assert!(validate_runtime_id("../x").is_err());
        assert!(validate_runtime_id("a/b").is_err());
    }

    #[test]
    fn sanitizes_unsafe_runtime_ids() {
        assert_eq!(sanitize_id("mojang-java 21/arm64"), "mojang-java_21_arm64");
        assert_eq!(sanitize_id("ok-1.2.3"), "ok-1.2.3");
    }

    #[test]
    fn discovers_a_nested_macos_bundle_home() {
        let root = tempfile::tempdir().expect("tempdir");
        let id = "mojang-java-runtime-delta-21.0.7-arm64";
        let directory = root.path().join("runtimes").join(id);
        let home = directory.join("jre.bundle").join("Contents").join("Home");
        fs::create_dir_all(home.join("bin")).expect("bin");
        fs::write(
            home.join("bin")
                .join(if cfg!(windows) { "java.exe" } else { "java" }),
            b"java",
        )
        .expect("java");
        fs::write(
            home.join("release"),
            "JAVA_VERSION=\"21.0.7\"\nOS_ARCH=\"aarch64\"\n",
        )
        .expect("release");
        fs::write(
            directory.join(RUNTIME_MARKER),
            serde_json::to_vec(&serde_json::json!({
                "schemaVersion": 1,
                "id": id,
                "vendor": "mojang",
                "component": "java-runtime-delta",
                "version": "21.0.7",
                "major": 21,
                "arch": "arm64",
                "platform": "mac-os-arm64",
                "home": "jre.bundle/Contents/Home",
                "fileCount": 1,
                "totalBytes": 4,
                "installedAtMs": 1,
            }))
            .expect("marker"),
        )
        .expect("write marker");

        let manager = JavaManager::new(
            root.path(),
            Arc::new(
                Downloader::builder(root.path())
                    .build()
                    .expect("downloader"),
            ),
            MirrorTable::embedded().clone(),
            DownloadSource::Auto,
            Region::RestOfWorld,
        );
        let installed = manager.installed();
        assert_eq!(installed.len(), 1);
        assert_eq!(installed[0].runtime.home, home);
        assert_eq!(installed[0].runtime.major(), Some(21));

        let scanner = crate::JavaScanner::new().with_extra_root(manager.runtimes_dir());
        let discovered = scanner.scan();
        assert!(
            discovered
                .iter()
                .any(|runtime| runtime.home == installed[0].runtime.home),
            "scanner must reach jre.bundle/Contents/Home: {discovered:?}"
        );
    }

    #[tokio::test]
    async fn catalog_uses_the_mojang_index_through_mirrors() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };

        let root = tempfile::tempdir().expect("tempdir");
        let server = MockServer::start().await;
        let manifest = serde_json::json!({
            "url": format!("{}/v1/packages/test/manifest.json", server.uri()),
            "sha1": "aa",
            "size": 1
        });
        let entry = serde_json::json!([{
            "manifest": manifest,
            "version": {"name": "21.0.7", "released": "2026-01-01T00:00:00+00:00"}
        }]);
        let index = serde_json::json!({
            "mac-os": {"java-runtime-delta": entry},
            "mac-os-arm64": {"java-runtime-delta": entry},
            "windows-x64": {"java-runtime-delta": entry},
            "linux": {"java-runtime-delta": entry}
        });
        Mock::given(method("GET"))
            .and(path(
                "/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_json(index))
            .mount(&server)
            .await;

        let table = MirrorTable::from_toml(&format!(
            "version = 1\n\
             [[rule]]\n\
             source = \"https://launchermeta.mojang.com\"\n\
             mirror = \"{}\"\n",
            server.uri()
        ))
        .expect("mirror table");
        let manager = JavaManager::new(
            root.path(),
            Arc::new(
                Downloader::builder(root.path())
                    .build()
                    .expect("downloader"),
            ),
            table,
            DownloadSource::Mirror,
            Region::RestOfWorld,
        );

        let catalog = manager.catalog().await.expect("catalog");
        assert_eq!(catalog.len(), 1);
        assert_eq!(catalog[0].major, 21);
        assert_eq!(catalog[0].component, "java-runtime-delta");

        let x64 = manager
            .candidate_for(21, None, JavaArch::X86_64)
            .await
            .expect("x64 candidate");
        assert_eq!(x64.arch, JavaArch::X86_64);
        let expected_platform = if cfg!(target_os = "macos") {
            "mac-os"
        } else if cfg!(target_os = "windows") {
            "windows-x64"
        } else {
            "linux"
        };
        assert_eq!(x64.platform, expected_platform);
    }

    #[tokio::test]
    async fn installs_downloads_links_marker_and_removes_the_runtime() {
        use fg_task::{Control, RunOptions};
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };

        let root = tempfile::tempdir().expect("tempdir");
        let server = MockServer::start().await;
        let java_bytes = b"#!/bin/sh\necho java\n";
        let release_bytes = b"JAVA_VERSION=\"21.0.7\"\nOS_ARCH=\"aarch64\"\n";
        let java_sha1 = sha1_hex(java_bytes);
        let release_sha1 = sha1_hex(release_bytes);

        Mock::given(method("GET"))
            .and(path("/v1/packages/test/manifest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "files": {
                    "bin": {"type": "directory"},
                    "legal": {"type": "directory"},
                    "bin/java": {
                        "type": "file",
                        "executable": true,
                        "downloads": {"raw": {
                            "url": format!("https://piston-data.mojang.com/v1/objects/{java_sha1}/java"),
                            "sha1": java_sha1,
                            "size": java_bytes.len(),
                        }}
                    },
                    "release": {
                        "type": "file",
                        "downloads": {"raw": {
                            "url": format!("https://piston-data.mojang.com/v1/objects/{release_sha1}/release"),
                            "sha1": release_sha1,
                            "size": release_bytes.len(),
                        }}
                    },
                    "bin/ControlPanel": {"type": "link", "target": "java"}
                }
            })))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path(format!("/v1/objects/{java_sha1}/java")))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(java_bytes))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path(format!("/v1/objects/{release_sha1}/release")))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(release_bytes))
            .mount(&server)
            .await;

        let table = MirrorTable::from_toml(&format!(
            "version = 1\n\
             [[rule]]\n\
             source = \"https://launchermeta.mojang.com\"\n\
             mirror = \"{}\"\n\
             [[rule]]\n\
             source = \"https://piston-data.mojang.com\"\n\
             mirror = \"{}\"\n",
            server.uri(),
            server.uri()
        ))
        .expect("mirror table");
        let downloader = Arc::new(
            Downloader::builder(root.path())
                .build()
                .expect("downloader"),
        );
        let manager = Arc::new(JavaManager::new(
            root.path(),
            downloader,
            table,
            DownloadSource::Mirror,
            Region::RestOfWorld,
        ));

        let candidate = candidate("https://launchermeta.mojang.com/v1/packages/test/manifest.json");
        let plan = manager.plan(&candidate).await.expect("plan");
        assert_eq!(plan.files.len(), 2);
        let graph = manager.graph(plan.clone());
        let report = graph.run(&Control::new(), &RunOptions::default()).await;
        assert!(report.is_success(), "install failed: {:?}", report.failed);

        let directory = plan.runtime_dir(root.path());
        assert_eq!(
            fs::read(directory.join("bin/java")).expect("java"),
            java_bytes
        );
        assert!(directory.join("legal").is_dir());
        let marker: RuntimeMarker =
            serde_json::from_slice(&fs::read(directory.join(RUNTIME_MARKER)).expect("marker"))
                .expect("marker json");
        assert_eq!(marker.id, plan.id);
        assert_eq!(marker.major, 21);
        assert_eq!(marker.file_count, 2);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            let mode = fs::metadata(directory.join("bin/java"))
                .expect("java metadata")
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o755);
            assert!(
                fs::symlink_metadata(directory.join("bin/ControlPanel"))
                    .expect("link")
                    .file_type()
                    .is_symlink()
            );
        }

        let installed = manager.installed();
        assert_eq!(installed.len(), 1);
        assert_eq!(installed[0].marker.id, plan.id);
        assert_eq!(installed[0].runtime.major(), Some(21));

        let removed = manager.remove(&plan.id).expect("remove");
        assert_eq!(removed.id, plan.id);
        assert!(!directory.exists());
        assert!(manager.installed().is_empty());
    }
}
