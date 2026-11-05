//! Install planning and the install task graph (§7.1).
//!
//! Planning is separated from execution: [`Installer::plan`] resolves the
//! version, fetches the asset index and produces a serializable list of files.
//! The list is then turned into a [`TaskGraph`] with one node per category, so
//! an interrupted install can be resumed by rebuilding the same graph and
//! letting `fg-net` verify the files that already exist.

use std::{
    collections::HashSet,
    path::{Path, PathBuf},
    sync::Arc,
};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{
    Candidate, DownloadCtx, DownloadRequest, Downloader, MirrorTable, Priority, ProgressSink,
    Region,
};
use fg_task::{Control, GraphReport, RunOptions, TaskCx, TaskGraph};
use serde::{Deserialize, Serialize};
use sha1::{Digest, Sha1};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

use crate::layout::{Layout, write_atomic};
use crate::resolver::VersionResolver;
use crate::rules::RuleContext;
use crate::version::{AssetIndex, AssetObject, ResolvedVersion};

/// Metadata schema written next to the installed version.
pub const INSTALL_META_SCHEMA_VERSION: u32 = 1;
/// Asset objects host from §6.1; mirror rules replace this prefix.
pub const ASSET_OBJECTS_URL: &str = "https://resources.download.minecraft.net";

/// Category of a planned file; also the task-graph node it belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FileKind {
    Client,
    Library,
    Asset,
    AssetIndex,
    LogConfig,
}

impl FileKind {
    /// Graph node name (an i18n key in the UI layer).
    #[must_use]
    pub const fn node_name(self) -> &'static str {
        match self {
            Self::Client => "install.client",
            Self::Library => "install.libraries",
            Self::Asset => "install.assets",
            Self::AssetIndex => "install.asset-index",
            Self::LogConfig => "install.logging",
        }
    }
}

/// One file the installer must materialize.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlannedFile {
    pub kind: FileKind,
    /// Path relative to the launcher data root.
    pub path: PathBuf,
    /// Official URL; mirror rules are applied when the request is built.
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

impl PlannedFile {
    /// Whether the file exists and matches the expected size.
    #[must_use]
    pub fn is_present(&self, root: &Path, strict: bool) -> bool {
        let path = root.join(&self.path);
        let Ok(metadata) = std::fs::metadata(&path) else {
            return false;
        };
        if !metadata.is_file() {
            return false;
        }
        if let Some(size) = self.size
            && metadata.len() != size
        {
            return false;
        }
        if strict && let Some(expected) = &self.sha1 {
            return file_sha1(&path).is_some_and(|actual| actual.eq_ignore_ascii_case(expected));
        }
        true
    }
}

/// Asset index state needed by launch.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexPlan {
    pub id: String,
    /// Path relative to the launcher data root.
    pub path: PathBuf,
    #[serde(default)]
    pub virtual_assets: bool,
    #[serde(default)]
    pub map_to_resources: bool,
}

/// Serializable install plan, also stored in `cache/install/` for resume.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallPlan {
    pub schema_version: u32,
    pub version_id: String,
    pub base_id: String,
    pub java_major: u32,
    pub main_class: String,
    #[serde(default)]
    pub instance_id: Option<String>,
    #[serde(default)]
    pub asset_index: Option<AssetIndexPlan>,
    /// Whether 1.7–1.18.1 needs the patched log4j config.
    #[serde(default)]
    pub log4j_fix: bool,
    pub files: Vec<PlannedFile>,
    pub total_bytes: u64,
}

impl InstallPlan {
    /// In-memory plan path under `<root>/cache/install/`.
    #[must_use]
    pub fn path(layout: &Layout, version_id: &str) -> PathBuf {
        layout.install_plans().join(format!("{version_id}.json"))
    }

    /// Saves the plan for resumption.
    ///
    /// # Errors
    ///
    /// Returns serialization and I/O errors.
    pub fn save(&self, layout: &Layout) -> Result<()> {
        let bytes = serde_json::to_vec_pretty(self).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.install.plan_serialize",
                error.to_string(),
            )
        })?;
        write_atomic(&Self::path(layout, &self.version_id), &bytes)
    }

    /// Loads a saved plan when it still describes `version_id`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when no plan exists and
    /// [`ErrorKind::Parse`] for damaged JSON.
    pub fn load(layout: &Layout, version_id: &str) -> Result<Self> {
        let path = Self::path(layout, version_id);
        let bytes = std::fs::read(&path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.install.no_plan",
                    path.display().to_string(),
                )
            } else {
                Error::with_detail(ErrorKind::Io, "error.io.read", error.to_string())
            }
        })?;
        let plan: Self = serde_json::from_slice(&bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.install.plan_parse",
                format!("{}: {error}", path.display()),
            )
        })?;
        if plan.version_id != version_id {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.install.plan_version",
                format!("plan is for {}, requested {version_id}", plan.version_id),
            ));
        }
        Ok(plan)
    }

    /// Removes the resume plan after a successful install.
    pub fn remove(layout: &Layout, version_id: &str) {
        let _ = std::fs::remove_file(Self::path(layout, version_id));
    }

    /// Files belonging to one graph node.
    #[must_use]
    pub fn files_of(&self, kind: FileKind) -> Vec<&PlannedFile> {
        self.files.iter().filter(|file| file.kind == kind).collect()
    }

    /// Sum of known file sizes for a node.
    #[must_use]
    pub fn bytes_of(&self, kind: FileKind) -> u64 {
        self.files_of(kind)
            .iter()
            .filter_map(|file| file.size)
            .sum()
    }

    /// Files that are missing or do not match their size.
    #[must_use]
    pub fn missing(&self, root: &Path, strict: bool) -> Vec<&PlannedFile> {
        self.files
            .iter()
            .filter(|file| !file.is_present(root, strict))
            .collect()
    }
}

/// Result of a completed install.
#[derive(Debug, Clone)]
pub struct InstallReport {
    pub plan: InstallPlan,
    pub graph: GraphReport,
}

/// Installer with network, layout and rule context.
#[derive(Debug, Clone)]
pub struct Installer {
    downloader: Arc<Downloader>,
    resolver: VersionResolver,
    layout: Layout,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
    context: RuleContext,
    asset_objects_url: String,
}

impl Installer {
    #[must_use]
    pub fn new(
        downloader: Arc<Downloader>,
        resolver: VersionResolver,
        layout: Layout,
        table: MirrorTable,
        source: DownloadSource,
        region: Region,
        context: RuleContext,
    ) -> Self {
        Self {
            downloader,
            resolver,
            layout,
            table,
            source,
            region,
            context,
            asset_objects_url: ASSET_OBJECTS_URL.to_string(),
        }
    }

    /// Overrides the asset object host, for mirrors and local test servers.
    #[must_use]
    pub fn with_asset_objects_url(mut self, url: impl Into<String>) -> Self {
        self.asset_objects_url = url.into();
        self
    }

    #[must_use]
    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    #[must_use]
    pub fn downloader(&self) -> &Arc<Downloader> {
        &self.downloader
    }

    /// Builds a plan, preferring an installed version and falling back to the
    /// network when `refresh` is set or the install is incomplete.
    ///
    /// # Errors
    ///
    /// Propagates resolution, asset-index and filesystem errors.
    pub async fn plan(
        &self,
        version_id: &str,
        refresh: bool,
        instance_id: Option<&str>,
    ) -> Result<InstallPlan> {
        let resolved = if refresh {
            self.resolver.resolve_remote_fresh(version_id).await?
        } else {
            match self.resolver.resolve_local(version_id) {
                Ok(resolved) => resolved,
                Err(_) => self.resolver.resolve_remote(version_id).await?,
            }
        };
        self.plan_resolved(&resolved, instance_id).await
    }

    /// Builds a plan for an already resolved version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for missing client/index metadata, network
    /// errors while fetching the asset index and I/O errors.
    pub async fn plan_resolved(
        &self,
        resolved: &ResolvedVersion,
        instance_id: Option<&str>,
    ) -> Result<InstallPlan> {
        let mut files = Vec::new();
        let mut total_bytes = 0_u64;

        let client = resolved.client_download()?;
        let client_url = client.url.clone().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.install.client_url",
                format!("version {} has no downloads.client.url", resolved.id),
            )
        })?;
        total_bytes += client.size.unwrap_or(0);
        files.push(PlannedFile {
            kind: FileKind::Client,
            path: relative(&self.layout, &self.layout.version_jar(&resolved.id))?,
            url: client_url,
            sha1: client.sha1.clone(),
            size: client.size,
        });

        let mut library_paths = HashSet::new();
        for library in resolved.libraries(&self.context)? {
            if !library_paths.insert(library.artifact.path.clone()) {
                continue;
            }
            total_bytes += library.artifact.size.unwrap_or(0);
            files.push(PlannedFile {
                kind: FileKind::Library,
                path: relative(
                    &self.layout,
                    &self.layout.libraries().join(&library.artifact.path),
                )?,
                url: library.artifact.url,
                sha1: library.artifact.sha1,
                size: library.artifact.size,
            });
        }

        let asset_index = match resolved.asset_index() {
            Some(index) => {
                let bytes = self.fetch_asset_index(index).await?;
                let parsed = AssetIndex::parse(&bytes)?;
                let path = self
                    .layout
                    .asset_indexes()
                    .join(format!("{}.json", index.id));
                total_bytes += index.size.unwrap_or(0);
                files.push(PlannedFile {
                    kind: FileKind::AssetIndex,
                    path: relative(&self.layout, &path)?,
                    url: index.url.clone(),
                    sha1: index.sha1.clone(),
                    size: index.size,
                });

                let mut seen = HashSet::new();
                for object in parsed.objects.values() {
                    if object.hash.len() < 2 || !seen.insert(object.hash.clone()) {
                        continue;
                    }
                    total_bytes += object.size;
                    files.push(asset_file(&self.layout, object, &self.asset_objects_url)?);
                }

                Some(AssetIndexPlan {
                    id: index.id.clone(),
                    path: relative(&self.layout, &path)?,
                    virtual_assets: parsed.virtual_assets.unwrap_or(false),
                    map_to_resources: parsed.map_to_resources.unwrap_or(false),
                })
            }
            None => None,
        };

        let log4j_fix = resolved.asset_index().is_some() && needs_log4j_fix(&resolved.base_id);
        if let Some(logging) = resolved
            .raw
            .logging
            .as_ref()
            .and_then(|logging| logging.client.as_ref())
        {
            total_bytes += logging.file.size.unwrap_or(0);
            files.push(PlannedFile {
                kind: FileKind::LogConfig,
                path: relative(
                    &self.layout,
                    &self.layout.log_configs().join(&logging.file.id),
                )?,
                url: logging.file.url.clone(),
                sha1: logging.file.sha1.clone(),
                size: logging.file.size,
            });
        }

        Ok(InstallPlan {
            schema_version: INSTALL_META_SCHEMA_VERSION,
            version_id: resolved.id.clone(),
            base_id: resolved.base_id.clone(),
            java_major: resolved.java_major,
            main_class: resolved.main_class()?.to_string(),
            instance_id: instance_id.map(ToString::to_string),
            asset_index,
            log4j_fix,
            files,
            total_bytes,
        })
    }

    async fn fetch_asset_index(&self, index: &crate::version::AssetIndexRef) -> Result<Vec<u8>> {
        let path = self
            .layout
            .asset_indexes()
            .join(format!("{}.json", index.id));
        if let Ok(bytes) = std::fs::read(&path) {
            let valid = index
                .sha1
                .as_deref()
                .is_none_or(|expected| sha1_hex(&bytes).eq_ignore_ascii_case(expected));
            if valid && AssetIndex::parse(&bytes).is_ok() {
                return Ok(bytes);
            }
        }

        let candidates = self.table.candidates(&index.url, self.source, self.region);
        let fetched = self.downloader.fetch_bytes(candidates, &index.url).await?;
        if let Some(expected) = &index.sha1
            && !sha1_hex(&fetched.bytes).eq_ignore_ascii_case(expected)
        {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.install.asset_index_hash",
                format!("{}: sha1 mismatch", index.url),
            ));
        }
        write_atomic(&path, &fetched.bytes)?;
        Ok(fetched.bytes.to_vec())
    }

    /// Builds the install task graph for `plan`.
    #[must_use]
    pub fn graph(&self, plan: &InstallPlan) -> TaskGraph {
        let mut builder = TaskGraph::builder(format!("install:{}", plan.version_id));
        let mut deps = Vec::new();

        for kind in [
            FileKind::Client,
            FileKind::Library,
            FileKind::Asset,
            FileKind::AssetIndex,
            FileKind::LogConfig,
        ] {
            let files: Vec<PlannedFile> = plan.files_of(kind).into_iter().cloned().collect();
            if files.is_empty() {
                continue;
            }
            let weight = weight_for(plan.bytes_of(kind));
            let downloader = self.downloader.clone();
            let table = self.table.clone();
            let source = self.source;
            let region = self.region;
            let root = self.layout.root().to_path_buf();
            let node = builder.task(kind.node_name(), weight, move |cx| async move {
                download_files(cx, files, &root, downloader, table, source, region).await
            });
            deps.push(node);
        }

        let layout = self.layout.clone();
        let plan = plan.clone();
        let finalize = builder.task("install.finalize", 1, move |cx| async move {
            cx.check()?;
            finalize_install(&cx, &layout, &plan)
        });
        builder.after(finalize, &deps);
        builder.build()
    }

    /// Plans and runs the install graph.
    ///
    /// # Errors
    ///
    /// Returns planning errors and the first graph failure.
    pub async fn install(
        &self,
        version_id: &str,
        instance_id: Option<&str>,
        control: &Control,
        options: &RunOptions,
    ) -> Result<InstallReport> {
        let plan = self.plan(version_id, false, instance_id).await?;
        plan.save(&self.layout)?;
        let graph = self.graph(&plan);
        let report = graph.run(control, options).await;
        if !report.is_success() {
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
        InstallPlan::remove(&self.layout, version_id);
        Ok(InstallReport {
            plan,
            graph: report,
        })
    }
}

fn asset_file(
    layout: &Layout,
    object: &AssetObject,
    asset_objects_url: &str,
) -> Result<PlannedFile> {
    let prefix = &object.hash[..2];
    Ok(PlannedFile {
        kind: FileKind::Asset,
        path: relative(
            layout,
            &layout.asset_objects().join(prefix).join(&object.hash),
        )?,
        url: format!(
            "{}/{prefix}/{}",
            asset_objects_url.trim_end_matches('/'),
            object.hash
        ),
        sha1: Some(object.hash.clone()),
        size: Some(object.size),
    })
}

/// Relative-to-root path used by plans so a moved data root keeps working.
fn relative(layout: &Layout, path: &Path) -> Result<PathBuf> {
    path.strip_prefix(layout.root())
        .map(Path::to_path_buf)
        .map_err(|_| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.install.path_outside_root",
                path.display().to_string(),
            )
        })
}

fn weight_for(bytes: u64) -> u32 {
    // One progress-weight unit per MiB, clamped so tiny nodes stay visible and
    // one 400 MiB node does not hide the rest of the graph.
    (bytes / (1024 * 1024)).clamp(1, 10_000) as u32
}

async fn download_files(
    cx: TaskCx,
    files: Vec<PlannedFile>,
    root: &Path,
    downloader: Arc<Downloader>,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
) -> Result<()> {
    cx.check()?;
    let total: u64 = files.iter().filter_map(|file| file.size).sum();
    cx.set_total(total.max(1));

    let sink = Arc::new(TaskSink { cx: cx.clone() });
    let mut items = Vec::with_capacity(files.len());
    for file in files {
        cx.check()?;
        let urls = table.candidates(&file.url, source, region);
        let candidates = urls
            .into_iter()
            .map(|url| Candidate::parse(&url))
            .collect::<Result<Vec<_>>>()?;
        let mut request = DownloadRequest::new(root.join(&file.path), candidates)
            .with_priority(priority_for(file.kind));
        if let Some(size) = file.size {
            request = request.with_size_hint(size);
        }
        if let Some(sha1) = file.sha1 {
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

    let report = downloader.download_many_with(items).await;
    if !report.failed.is_empty() {
        let (path, error) = &report.failed[0];
        return Err(Error::with_detail(
            error.kind,
            "error.install.download_failed",
            format!("{}: {error}", path.display()),
        ));
    }
    cx.set_fraction(1.0);
    Ok(())
}

fn priority_for(kind: FileKind) -> Priority {
    match kind {
        FileKind::AssetIndex | FileKind::LogConfig | FileKind::Client => Priority::Manifest,
        FileKind::Library => Priority::Library,
        FileKind::Asset => Priority::Asset,
    }
}

/// Reports aggregate bytes to one graph node.
#[derive(Debug)]
struct TaskSink {
    cx: TaskCx,
}

impl ProgressSink for TaskSink {
    fn on_bytes(&self, bytes: u64) {
        self.cx.advance(bytes);
    }
}

fn finalize_install(cx: &TaskCx, layout: &Layout, plan: &InstallPlan) -> Result<()> {
    layout.ensure_base_dirs()?;

    if plan.log4j_fix {
        let path = layout.log_configs().join(LOG4J_FIX_FILE);
        write_atomic(&path, LOG4J_FIX_XML.as_bytes())?;
    }

    if let Some(index) = &plan.asset_index {
        let index_path = layout.root().join(&index.path);
        let bytes = std::fs::read(&index_path)?;
        let assets = AssetIndex::parse(&bytes)?;

        if index.virtual_assets {
            let virtual_root = layout.virtual_assets(&index.id);
            for (name, object) in &assets.objects {
                if object.hash.len() < 2 {
                    continue;
                }
                let source = layout
                    .asset_objects()
                    .join(&object.hash[..2])
                    .join(&object.hash);
                let destination = virtual_root.join(name);
                link_or_copy(cx, &source, &destination)?;
            }
        }

        if index.map_to_resources
            && let Some(instance_id) = &plan.instance_id
        {
            let resources = layout.game_dir(instance_id).join("resources");
            for (name, object) in &assets.objects {
                if object.hash.len() < 2 {
                    continue;
                }
                let source = layout
                    .asset_objects()
                    .join(&object.hash[..2])
                    .join(&object.hash);
                let destination = resources.join(name);
                link_or_copy(cx, &source, &destination)?;
            }
        }
    }

    let meta = InstallMeta {
        schema_version: INSTALL_META_SCHEMA_VERSION,
        version_id: plan.version_id.clone(),
        base_id: plan.base_id.clone(),
        java_major: plan.java_major,
        main_class: plan.main_class.clone(),
        asset_index: plan.asset_index.clone(),
        log4j_fix: plan.log4j_fix,
        installed_at: OffsetDateTime::now_utc()
            .format(&Rfc3339)
            .unwrap_or_else(|_| String::new()),
    };
    let bytes = serde_json::to_vec_pretty(&meta).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.install.meta_serialize",
            error.to_string(),
        )
    })?;
    write_atomic(&layout.install_meta(&plan.version_id), &bytes)?;
    Ok(())
}

fn link_or_copy(cx: &TaskCx, source: &Path, destination: &Path) -> Result<()> {
    cx.check()?;
    let destination_len = std::fs::metadata(destination)
        .ok()
        .filter(|metadata| metadata.is_file())
        .map(|metadata| metadata.len());
    let source_len = std::fs::metadata(source)
        .ok()
        .map(|metadata| metadata.len());
    if destination_len.is_some() && destination_len == source_len {
        return Ok(());
    }
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent)?;
    }
    match std::fs::hard_link(source, destination) {
        Ok(()) => Ok(()),
        Err(_) => std::fs::copy(source, destination)
            .map(|_| ())
            .map_err(Error::from),
    }
}

/// `fograin-install.json` marker.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InstallMeta {
    pub schema_version: u32,
    pub version_id: String,
    pub base_id: String,
    pub java_major: u32,
    pub main_class: String,
    #[serde(default)]
    pub asset_index: Option<AssetIndexPlan>,
    #[serde(default)]
    pub log4j_fix: bool,
    pub installed_at: String,
}

impl InstallMeta {
    /// Loads the marker for an installed version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the version is not installed.
    pub fn load(layout: &Layout, version_id: &str) -> Result<Self> {
        let path = layout.install_meta(version_id);
        let bytes = std::fs::read(&path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.install.not_installed",
                    path.display().to_string(),
                )
            } else {
                Error::with_detail(ErrorKind::Io, "error.io.read", error.to_string())
            }
        })?;
        serde_json::from_slice(&bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.install.meta_parse",
                format!("{}: {error}", path.display()),
            )
        })
    }
}

/// File name of the patched log4j config bundled with the launcher.
pub const LOG4J_FIX_FILE: &str = "fograin-log4j2.xml";

/// Patched config for 1.7–1.18.1. `%msg{nolookups}` prevents Log4Shell through
/// message lookups; the JVM also receives `-Dlog4j2.formatMsgNoLookups=true`.
pub const LOG4J_FIX_XML: &str = r#"<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
  <Appenders>
    <Console name="SysOut" target="SYSTEM_OUT">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n"/>
    </Console>
    <File name="File" fileName="logs/latest.log">
      <PatternLayout pattern="[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n"/>
    </File>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="SysOut"/>
      <AppenderRef ref="File"/>
    </Root>
  </Loggers>
</Configuration>
"#;

/// Whether a Minecraft version is in the Log4Shell window (§7.1 step 3).
#[must_use]
pub fn needs_log4j_fix(version_id: &str) -> bool {
    let Some(rest) = version_id.strip_prefix("1.") else {
        return false;
    };
    let mut parts = rest.split('.').filter_map(|part| part.parse::<u32>().ok());
    let Some(minor) = parts.next() else {
        return false;
    };
    let patch = parts.next().unwrap_or(0);
    if minor < 7 {
        return false;
    }
    if minor < 18 {
        return true;
    }
    if minor == 18 {
        return patch <= 1;
    }
    false
}

fn sha1_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    digest.iter().map(|byte| format!("{byte:02x}")).collect()
}

fn file_sha1(path: &Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    Some(sha1_hex(&bytes))
}

/// Runs a plan without re-planning; used by resume.
///
/// # Errors
///
/// Returns the first graph failure.
pub async fn run_plan(
    installer: &Installer,
    plan: &InstallPlan,
    control: &Control,
    options: &RunOptions,
) -> Result<GraphReport> {
    let graph = installer.graph(plan);
    let report = graph.run(control, options).await;
    if report.is_success() {
        InstallPlan::remove(&installer.layout, &plan.version_id);
        Ok(report)
    } else {
        let detail = report
            .failed
            .first()
            .map(|(node, error)| format!("{node}: {error}"))
            .unwrap_or_else(|| "install did not finish".to_string());
        Err(Error::with_detail(
            ErrorKind::Internal,
            "error.install.failed",
            detail,
        ))
    }
}

/// Number of files per category, for CLI summaries.
#[must_use]
pub fn category_counts(plan: &InstallPlan) -> Vec<(FileKind, usize, u64)> {
    [
        FileKind::Client,
        FileKind::Library,
        FileKind::Asset,
        FileKind::AssetIndex,
        FileKind::LogConfig,
    ]
    .into_iter()
    .map(|kind| (kind, plan.files_of(kind).len(), plan.bytes_of(kind)))
    .filter(|(_, count, _)| *count > 0)
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::layout::Layout;
    use crate::resolver::VersionResolver;
    use crate::rules::{Features, OsInfo};
    use crate::version::VersionJson;
    use fg_java::JavaArch;

    fn layout() -> (tempfile::TempDir, Layout) {
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        layout.ensure_base_dirs().expect("dirs");
        (directory, layout)
    }

    fn installer(layout: &Layout) -> Installer {
        let downloader = Arc::new(Downloader::new(layout.root()).expect("downloader"));
        let resolver = VersionResolver::new(
            downloader.clone(),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        );
        Installer::new(
            downloader,
            resolver,
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
            RuleContext::new(
                OsInfo {
                    name: "osx".into(),
                    arch: JavaArch::Arm64,
                    version: Some("15.1".into()),
                },
                Features::new(),
            ),
        )
    }

    fn write_local_version(layout: &Layout, id: &str, text: &str) {
        let path = layout.version_json(id);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        std::fs::write(path, text).expect("write");
    }

    fn write_local_asset_index(layout: &Layout, id: &str) -> String {
        let text =
            r#"{"objects":{"a":{"hash":"aa00000000000000000000000000000000000000","size":3}}}"#;
        let sha1 = sha1_hex(text.as_bytes());
        let path = layout.asset_indexes().join(format!("{id}.json"));
        std::fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        std::fs::write(path, text).expect("write");
        sha1
    }

    #[tokio::test]
    async fn plans_by_category_and_skips_duplicate_assets() {
        let (_directory, layout) = layout();
        let mut version: VersionJson =
            VersionJson::parse(include_str!("../tests/fixtures/versions/1.21.4.json").as_bytes())
                .expect("parse");
        let sha1 = write_local_asset_index(&layout, "19");
        if let Some(index) = &mut version.asset_index {
            index.url = "https://example.invalid/19.json".into();
            index.sha1 = Some(sha1);
            index.size = Some(100);
        }
        write_local_version(
            &layout,
            "1.21.4",
            &serde_json::to_string(&version).expect("serialize"),
        );

        let installer = installer(&layout);
        let plan = installer.plan("1.21.4", false, None).await.expect("plan");
        assert_eq!(plan.version_id, "1.21.4");
        assert_eq!(plan.java_major, 21);
        assert!(plan.files_of(FileKind::Client).len() == 1);
        assert!(!plan.files_of(FileKind::Library).is_empty());
        assert!(
            plan.files_of(FileKind::Asset)
                .iter()
                .any(|file| file.path.to_string_lossy().contains("aa")),
            "asset object should be planned"
        );
        let assets: HashSet<_> = plan
            .files_of(FileKind::Asset)
            .iter()
            .map(|file| file.path.clone())
            .collect();
        assert_eq!(
            assets.len(),
            plan.files_of(FileKind::Asset).len(),
            "duplicate hashes are collapsed"
        );
        assert!(!plan.log4j_fix, "1.21.4 is outside the log4j window");
    }

    #[test]
    fn log4j_window_matches_the_design() {
        for version in ["1.7.10", "1.8.9", "1.12.2", "1.16.5", "1.18.1"] {
            assert!(needs_log4j_fix(version), "{version}");
        }
        for version in ["1.6.4", "1.18.2", "1.20.1", "1.21.4", "rd-132211"] {
            assert!(!needs_log4j_fix(version), "{version}");
        }
    }

    #[test]
    fn plan_round_trips_and_reports_missing_files() {
        let (_directory, layout) = layout();
        let plan = InstallPlan {
            schema_version: INSTALL_META_SCHEMA_VERSION,
            version_id: "1.21.4".into(),
            base_id: "1.21.4".into(),
            java_major: 21,
            main_class: "net.minecraft.client.main.Main".into(),
            instance_id: None,
            asset_index: None,
            log4j_fix: false,
            files: vec![PlannedFile {
                kind: FileKind::Client,
                path: PathBuf::from("shared/versions/1.21.4/1.21.4.jar"),
                url: "https://example.invalid/client.jar".into(),
                sha1: None,
                size: Some(4),
            }],
            total_bytes: 4,
        };
        plan.save(&layout).expect("save");
        let loaded = InstallPlan::load(&layout, "1.21.4").expect("load");
        assert_eq!(loaded, plan);
        assert_eq!(loaded.missing(layout.root(), false).len(), 1);
        std::fs::create_dir_all(layout.version_dir("1.21.4")).expect("version dir");
        std::fs::write(layout.version_jar("1.21.4"), b"1234").expect("write");
        assert!(loaded.missing(layout.root(), false).is_empty());
    }

    #[tokio::test]
    async fn install_graph_writes_fixed_log4j_and_marks_completion() {
        let (_directory, layout) = layout();
        // Keep the plan tiny: no client/libraries/assets downloads are needed
        // here; the finalize node is what this test covers.
        let plan = InstallPlan {
            schema_version: INSTALL_META_SCHEMA_VERSION,
            version_id: "1.21.4".into(),
            base_id: "1.21.4".into(),
            java_major: 21,
            main_class: "net.minecraft.client.main.Main".into(),
            instance_id: None,
            asset_index: None,
            log4j_fix: true,
            files: Vec::new(),
            total_bytes: 0,
        };
        let installer = installer(&layout);
        let report = run_plan(&installer, &plan, &Control::new(), &RunOptions::default())
            .await
            .expect("run");
        assert!(report.is_success());
        assert!(layout.log_configs().join(LOG4J_FIX_FILE).is_file());
        let meta = InstallMeta::load(&layout, "1.21.4").expect("meta");
        assert_eq!(meta.java_major, 21);
        assert!(meta.log4j_fix);
    }
}
