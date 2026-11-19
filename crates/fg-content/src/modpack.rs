//! Modpack inspection, import planning and `.mrpack` export (M9).
//!
//! Supported inputs:
//!
//! - Modrinth `.mrpack` (`modrinth.index.json` with direct download URLs)
//! - CurseForge `.zip` (`manifest.json`; download URLs are resolved through the
//!   MCIM client while the import graph runs)
//! - HMCL `.zip` (`modpack.json` plus an overrides folder)
//! - MultiMC/Prism instance folders (`mmc-pack.json` + `minecraft/`); these are
//!   registered as external instances instead of copied
//!
//! The import graph downloads every file, extracts `overrides/` and records
//! `instances/<id>/modpack.json` for a later update flow.

use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use fg_net::{DownloadRequest, Downloader, Integrity, Priority, ProgressSink};
use fg_task::{TaskCx, TaskGraph};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use time::{OffsetDateTime, macros::format_description};
use zip::ZipArchive;

use crate::ContentClient;

/// Record written into the instance directory after a successful import.
pub const MODPACK_FILE: &str = "modpack.json";
/// Index file at the root of a Modrinth pack.
const MRPACK_INDEX: &str = "modrinth.index.json";
/// Largest manifest accepted from a pack.
const MAX_MANIFEST_BYTES: u64 = 8 * 1024 * 1024;
/// Per-file cap when extracting overrides.
const MAX_OVERRIDE_FILE: u64 = 512 * 1024 * 1024;
/// Total cap when extracting overrides.
const MAX_OVERRIDE_TOTAL: u64 = 4 * 1024 * 1024 * 1024;
/// Entry cap when extracting overrides.
const MAX_OVERRIDE_ENTRIES: usize = 100_000;
/// Export caps.
const MAX_EXPORT_FILE: u64 = 32 * 1024 * 1024;
const MAX_EXPORT_TOTAL: u64 = 256 * 1024 * 1024;
const MAX_EXPORT_ENTRIES: usize = 5000;

/// Which format a pack came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ModpackKind {
    Modrinth,
    CurseForge,
    Hmcl,
    MultiMc,
}

impl ModpackKind {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Modrinth => "modrinth",
            Self::CurseForge => "curseforge",
            Self::Hmcl => "hmcl",
            Self::MultiMc => "multimc",
        }
    }
}

/// One file the pack wants in the instance.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PackFile {
    /// Instance-relative path, e.g. `mods/sodium.jar`.
    pub path: String,
    pub size: u64,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub sha512: Option<String>,
    /// Direct download URL (Modrinth); `None` when the source needs resolution.
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub file_id: Option<u64>,
}

/// Everything `modpack.inspect` reports to the UI.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Modpack {
    pub kind: ModpackKind,
    pub name: String,
    #[serde(default)]
    pub version: String,
    pub minecraft: String,
    /// `vanilla`, `fabric`, `quilt`, `forge` or `neoforge`.
    pub loader_kind: String,
    #[serde(default)]
    pub loader_version: String,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub author: Option<String>,
    pub files: Vec<PackFile>,
    /// Overrides directory inside the archive (`overrides`, `.minecraft`, …).
    #[serde(default)]
    pub overrides: Option<String>,
    pub source_path: String,
    /// Game directory of an external MultiMC/Prism instance.
    #[serde(default)]
    pub game_dir: Option<String>,
    /// True when the instance is registered in place instead of copied.
    #[serde(default)]
    pub external: bool,
    #[serde(default)]
    pub skipped: usize,
}

impl Modpack {
    /// Total download size of the managed files.
    #[must_use]
    pub fn total_bytes(&self) -> u64 {
        self.files.iter().map(|file| file.size).sum()
    }
}

/// Inspects a pack path: zip archive or MultiMC/Prism instance folder.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] for a missing path and
/// [`ErrorKind::Unsupported`] for archives without a known manifest.
pub fn inspect(path: &Path) -> Result<Modpack> {
    if path.is_dir() {
        return inspect_instance_dir(path);
    }
    if !path.is_file() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.modpack.missing",
            path.display().to_string(),
        ));
    }
    let file = File::open(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.read",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut archive = ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Unsupported,
            "error.modpack.format",
            format!("{}: {error}", path.display()),
        )
    })?;

    if let Some(text) = read_entry(&mut archive, MRPACK_INDEX)? {
        return parse_mrpack(&text, path);
    }
    if let Some(text) = read_entry(&mut archive, "manifest.json")? {
        return parse_curseforge_or_hmcl(&text, path);
    }
    if let Some(text) = read_entry(&mut archive, "modpack.json")? {
        return parse_hmcl(&text, path);
    }
    Err(Error::with_detail(
        ErrorKind::Unsupported,
        "error.modpack.format",
        format!("{} has no modpack manifest", path.display()),
    ))
}

fn read_entry(archive: &mut ZipArchive<File>, name: &str) -> Result<Option<String>> {
    let mut entry = match archive.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(error) => {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.modpack.read",
                format!("{name}: {error}"),
            ));
        }
    };
    if entry.size() > MAX_MANIFEST_BYTES {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.modpack.read",
            format!("{name} is larger than {MAX_MANIFEST_BYTES} bytes"),
        ));
    }
    let mut text = String::new();
    entry.read_to_string(&mut text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.read",
            format!("{name}: {error}"),
        )
    })?;
    Ok(Some(text))
}

#[derive(Debug, Deserialize)]
struct MrpackIndex {
    #[serde(default)]
    name: String,
    #[serde(default, rename = "versionId")]
    version_id: String,
    #[serde(default)]
    summary: Option<String>,
    #[serde(default)]
    files: Vec<MrpackFile>,
    #[serde(default)]
    dependencies: BTreeMap<String, Value>,
}

#[derive(Debug, Deserialize)]
struct MrpackFile {
    #[serde(default)]
    path: String,
    #[serde(default)]
    hashes: BTreeMap<String, String>,
    #[serde(default)]
    downloads: Vec<String>,
    #[serde(default, rename = "fileSize")]
    file_size: u64,
    #[serde(default)]
    env: Option<MrpackEnv>,
}

#[derive(Debug, Deserialize)]
struct MrpackEnv {
    #[serde(default)]
    client: Option<String>,
}

fn parse_mrpack(text: &str, path: &Path) -> Result<Modpack> {
    let index: MrpackIndex = serde_json::from_str(text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.modpack.manifest",
            format!("{}: {error}", path.display()),
        )
    })?;
    let minecraft = dependency_string(&index.dependencies, "minecraft").ok_or_else(|| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.modpack.minecraft",
            path.display().to_string(),
        )
    })?;
    let (loader_kind, loader_version) = loader_from_dependencies(&index.dependencies);

    let mut files = Vec::new();
    let mut skipped = 0;
    for file in index.files {
        if file
            .env
            .as_ref()
            .and_then(|env| env.client.as_deref())
            .is_some_and(|client| client == "unsupported")
        {
            skipped += 1;
            continue;
        }
        let Some(path) = safe_relative(&file.path) else {
            skipped += 1;
            continue;
        };
        files.push(PackFile {
            path,
            size: file.file_size,
            sha1: file.hashes.get("sha1").cloned(),
            sha512: file.hashes.get("sha512").cloned(),
            url: file.downloads.first().cloned(),
            project_id: None,
            file_id: None,
        });
    }

    Ok(Modpack {
        kind: ModpackKind::Modrinth,
        name: if index.name.trim().is_empty() {
            pack_stem(path)
        } else {
            index.name
        },
        version: index.version_id,
        minecraft,
        loader_kind,
        loader_version,
        summary: index.summary,
        author: None,
        files,
        overrides: Some("overrides".to_string()),
        source_path: path.display().to_string(),
        game_dir: None,
        external: false,
        skipped,
    })
}

#[derive(Debug, Deserialize)]
struct CurseManifest {
    #[serde(default, rename = "manifestType")]
    manifest_type: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    author: Option<String>,
    #[serde(default)]
    minecraft: CurseMinecraft,
    #[serde(default)]
    files: Vec<CurseFile>,
    #[serde(default)]
    overrides: String,
}

#[derive(Debug, Default, Deserialize)]
struct CurseMinecraft {
    #[serde(default)]
    version: String,
    #[serde(default)]
    loaders: Vec<CurseLoader>,
}

#[derive(Debug, Deserialize)]
struct CurseLoader {
    #[serde(default)]
    id: String,
    #[serde(default)]
    primary: bool,
}

#[derive(Debug, Deserialize)]
struct CurseFile {
    #[serde(default, rename = "projectID")]
    project_id: u64,
    #[serde(default, rename = "fileID")]
    file_id: u64,
}

fn parse_curseforge_or_hmcl(text: &str, path: &Path) -> Result<Modpack> {
    if let Ok(manifest) = serde_json::from_str::<CurseManifest>(text)
        && manifest
            .manifest_type
            .eq_ignore_ascii_case("minecraftModpack")
    {
        return Ok(curseforge_pack(manifest, path));
    }
    parse_hmcl(text, path)
}

fn curseforge_pack(manifest: CurseManifest, path: &Path) -> Modpack {
    let loader = manifest
        .minecraft
        .loaders
        .iter()
        .find(|loader| loader.primary)
        .or_else(|| manifest.minecraft.loaders.first());
    let (loader_kind, loader_version) = loader.map_or_else(
        || ("vanilla".to_string(), String::new()),
        |loader| parse_loader_id(&loader.id),
    );
    let files = manifest
        .files
        .into_iter()
        .filter(|file| file.project_id > 0 && file.file_id > 0)
        .map(|file| PackFile {
            path: format!("mods/curseforge-{}-{}.jar", file.project_id, file.file_id),
            size: 0,
            sha1: None,
            sha512: None,
            url: None,
            project_id: Some(file.project_id.to_string()),
            file_id: Some(file.file_id),
        })
        .collect();
    Modpack {
        kind: ModpackKind::CurseForge,
        name: if manifest.name.trim().is_empty() {
            pack_stem(path)
        } else {
            manifest.name
        },
        version: manifest.version,
        minecraft: manifest.minecraft.version,
        loader_kind,
        loader_version,
        summary: None,
        author: manifest.author,
        files,
        overrides: Some(if manifest.overrides.trim().is_empty() {
            "overrides".to_string()
        } else {
            manifest.overrides
        }),
        source_path: path.display().to_string(),
        game_dir: None,
        external: false,
        skipped: 0,
    }
}

#[derive(Debug, Deserialize)]
struct HmclPack {
    #[serde(default)]
    name: String,
    #[serde(default)]
    version: String,
    #[serde(default)]
    author: Option<String>,
    #[serde(default)]
    description: Option<String>,
    #[serde(default, rename = "gameVersion")]
    game_version: String,
    #[serde(default, rename = "modLoader")]
    mod_loader: String,
    #[serde(default, rename = "modLoaderVersion")]
    loader_version: String,
    #[serde(default)]
    overrides: Option<String>,
}

fn parse_hmcl(text: &str, path: &Path) -> Result<Modpack> {
    let pack: HmclPack = serde_json::from_str(text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.modpack.manifest",
            format!("{}: {error}", path.display()),
        )
    })?;
    if pack.game_version.trim().is_empty() {
        return Err(Error::with_detail(
            ErrorKind::Parse,
            "error.modpack.minecraft",
            path.display().to_string(),
        ));
    }
    let loader_kind = normalize_loader_kind(&pack.mod_loader);
    Ok(Modpack {
        kind: ModpackKind::Hmcl,
        name: if pack.name.trim().is_empty() {
            pack_stem(path)
        } else {
            pack.name
        },
        version: pack.version,
        minecraft: pack.game_version,
        loader_kind,
        loader_version: pack.loader_version,
        summary: pack.description,
        author: pack.author,
        files: Vec::new(),
        overrides: Some(pack.overrides.unwrap_or_else(|| "overrides".to_string())),
        source_path: path.display().to_string(),
        game_dir: None,
        external: false,
        skipped: 0,
    })
}

fn inspect_instance_dir(directory: &Path) -> Result<Modpack> {
    let mmc = directory.join("mmc-pack.json");
    if !mmc.is_file() {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.modpack.directory",
            format!("{} has no mmc-pack.json", directory.display()),
        ));
    }
    let text = fs::read_to_string(&mmc).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.read",
            format!("{}: {error}", mmc.display()),
        )
    })?;
    let pack: MmcPack = serde_json::from_str(&text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.modpack.manifest",
            format!("{}: {error}", mmc.display()),
        )
    })?;
    let minecraft = pack
        .components
        .iter()
        .find(|component| component.uid == "net.minecraft")
        .map(|component| component.version.clone())
        .filter(|version| !version.trim().is_empty())
        .ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.modpack.minecraft",
                mmc.display().to_string(),
            )
        })?;
    let loader = pack.components.iter().find_map(|component| {
        let kind = match component.uid.as_str() {
            "net.fabricmc.fabric-loader" => "fabric",
            "org.quiltmc.quilt-loader" => "quilt",
            "net.minecraftforge" => "forge",
            "net.neoforged" | "net.neoforged.fancymodloader" => "neoforge",
            _ => return None,
        };
        Some((kind.to_string(), component.version.clone()))
    });
    let (loader_kind, loader_version) =
        loader.unwrap_or_else(|| ("vanilla".to_string(), String::new()));
    let game_dir = if directory.join("minecraft").is_dir() {
        directory.join("minecraft")
    } else if directory.join(".minecraft").is_dir() {
        directory.join(".minecraft")
    } else {
        directory.to_path_buf()
    };
    let name = instance_cfg_name(directory).unwrap_or_else(|| {
        directory
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .unwrap_or_else(|| "MultiMC".to_string())
    });
    Ok(Modpack {
        kind: ModpackKind::MultiMc,
        name,
        version: String::new(),
        minecraft,
        loader_kind,
        loader_version,
        summary: None,
        author: None,
        files: Vec::new(),
        overrides: None,
        source_path: directory.display().to_string(),
        game_dir: Some(game_dir.display().to_string()),
        external: true,
        skipped: 0,
    })
}

#[derive(Debug, Deserialize)]
struct MmcPack {
    #[serde(default)]
    components: Vec<MmcComponent>,
}

#[derive(Debug, Deserialize)]
struct MmcComponent {
    #[serde(default)]
    uid: String,
    #[serde(default)]
    version: String,
}

fn instance_cfg_name(directory: &Path) -> Option<String> {
    let text = fs::read_to_string(directory.join("instance.cfg")).ok()?;
    text.lines().find_map(|line| {
        line.strip_prefix("name=")
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string)
    })
}

fn dependency_string(dependencies: &BTreeMap<String, Value>, key: &str) -> Option<String> {
    dependencies
        .get(key)
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn loader_from_dependencies(dependencies: &BTreeMap<String, Value>) -> (String, String) {
    for (key, kind) in [
        ("fabric-loader", "fabric"),
        ("quilt-loader", "quilt"),
        ("forge", "forge"),
        ("neoforge", "neoforge"),
    ] {
        if let Some(version) = dependency_string(dependencies, key) {
            return (kind.to_string(), version);
        }
    }
    ("vanilla".to_string(), String::new())
}

fn parse_loader_id(id: &str) -> (String, String) {
    let (kind, version) = id.split_once('-').unwrap_or((id, ""));
    (normalize_loader_kind(kind), version.to_string())
}

fn normalize_loader_kind(value: &str) -> String {
    match value.trim().to_ascii_lowercase().as_str() {
        "fabric" | "fabric-loader" => "fabric".to_string(),
        "quilt" | "quilt-loader" => "quilt".to_string(),
        "forge" | "minecraftforge" => "forge".to_string(),
        "neoforge" | "neo-forge" => "neoforge".to_string(),
        _ => "vanilla".to_string(),
    }
}

fn pack_stem(path: &Path) -> String {
    path.file_stem()
        .map(|stem| stem.to_string_lossy().to_string())
        .filter(|stem| !stem.is_empty())
        .unwrap_or_else(|| "整合包".to_string())
}

/// Rejects absolute paths, traversal and Windows drive prefixes.
#[must_use]
pub fn safe_relative(path: &str) -> Option<String> {
    let normalized = path.replace('\\', "/");
    if normalized.starts_with('/') || normalized.contains('\0') {
        return None;
    }
    if normalized.len() > 2 && normalized.as_bytes().get(1) == Some(&b':') {
        return None;
    }
    let mut parts = Vec::new();
    for part in normalized.split('/') {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." {
            return None;
        }
        parts.push(part);
    }
    if parts.is_empty() {
        None
    } else {
        Some(parts.join("/"))
    }
}

/// Import plan: the instance's directories plus the pack.
#[derive(Debug, Clone)]
pub struct ModpackPlan {
    pub pack: Modpack,
    pub archive: PathBuf,
    pub instance_dir: PathBuf,
    pub game_dir: PathBuf,
}

impl ModpackPlan {
    #[must_use]
    pub fn new(pack: Modpack, archive: PathBuf, instance_dir: PathBuf, game_dir: PathBuf) -> Self {
        Self {
            pack,
            archive,
            instance_dir,
            game_dir,
        }
    }

    /// Builds the download graph: files → overrides → `modpack.json`.
    ///
    /// CurseForge entries carry only `projectID`/`fileID`; their download URL is
    /// resolved inside the node so the HTTP requests parallelise with the rest
    /// of the graph.
    #[must_use]
    pub fn graph(&self, downloader: Arc<Downloader>, client: ContentClient) -> TaskGraph {
        let mut builder = TaskGraph::builder(format!("modpack:{}", self.pack.name));
        let mut nodes = Vec::with_capacity(self.pack.files.len());

        for file in &self.pack.files {
            let weight = (file.size / (64 * 1024)).clamp(1, 200_000) as u32;
            let file = file.clone();
            let downloader = Arc::clone(&downloader);
            let client = client.clone();
            let dest = self.game_dir.join(&file.path);
            if let Some(parent) = dest.parent() {
                let _ = fs::create_dir_all(parent);
            }
            let node = builder.task(format!("modpack.file:{}", file.path), weight, move |cx| {
                let downloader = Arc::clone(&downloader);
                let client = client.clone();
                let file = file.clone();
                let dest = dest.clone();
                async move {
                    cx.check()?;
                    let url = match file.url.clone() {
                        Some(url) => url,
                        None => {
                            let project = file.project_id.clone().ok_or_else(|| {
                                Error::with_detail(
                                    ErrorKind::Unsupported,
                                    "error.modpack.file",
                                    file.path.clone(),
                                )
                            })?;
                            let file_id = file.file_id.ok_or_else(|| {
                                Error::with_detail(
                                    ErrorKind::Unsupported,
                                    "error.modpack.file",
                                    file.path.clone(),
                                )
                            })?;
                            client.curseforge_download_url(&project, file_id).await?
                        }
                    };
                    let candidates = client.candidates(&url)?;
                    if candidates.is_empty() {
                        return Err(Error::with_detail(
                            ErrorKind::Network,
                            "error.content.no_candidates",
                            url,
                        ));
                    }
                    let mut request = DownloadRequest::new(dest, candidates)
                        .with_priority(Priority::Mod)
                        .with_size_hint(file.size);
                    if let Some(sha1) = &file.sha1 {
                        request = request.with_sha1(sha1);
                    } else if let Some(sha512) = &file.sha512 {
                        request = request.with_integrity(Integrity::sha512(sha512));
                    } else if file.size > 0 {
                        request = request.with_size(file.size);
                    }
                    downloader
                        .download_with(
                            request,
                            fg_net::DownloadCtx {
                                cancel: cx.cancel_handle(),
                                progress: Arc::new(TaskSink { cx: cx.clone() }),
                            },
                        )
                        .await
                        .map(|_| ())
                }
            });
            nodes.push(node);
        }

        let archive = self.archive.clone();
        let game_dir = self.game_dir.clone();
        let overrides = self.pack.overrides.clone();
        let override_node = builder.task("modpack.overrides", 8, move |cx| {
            let archive = archive.clone();
            let game_dir = game_dir.clone();
            let overrides = overrides.clone();
            async move {
                cx.check()?;
                let extracted = tokio::task::spawn_blocking(move || {
                    extract_overrides(&archive, &game_dir, overrides.as_deref())
                })
                .await
                .map_err(|error| {
                    Error::with_detail(
                        ErrorKind::Internal,
                        "error.modpack.overrides",
                        error.to_string(),
                    )
                })??;
                cx.set_message(format!("已解压 {extracted} 个覆盖文件"));
                Ok(())
            }
        });
        for node in nodes {
            builder.depends_on(override_node, node);
        }

        let pack = self.pack.clone();
        let instance_dir = self.instance_dir.clone();
        let source = self.archive.display().to_string();
        let finalize = builder.task("modpack.record", 1, move |cx| {
            let pack = pack.clone();
            let instance_dir = instance_dir.clone();
            let source = source.clone();
            async move {
                cx.check()?;
                write_record(&instance_dir, &pack, &source)
            }
        });
        builder.depends_on(finalize, override_node);
        builder.build()
    }
}

fn extract_overrides(archive: &Path, game_dir: &Path, overrides: Option<&str>) -> Result<usize> {
    let file = File::open(archive).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.read",
            format!("{}: {error}", archive.display()),
        )
    })?;
    let mut archive = ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Unsupported,
            "error.modpack.format",
            format!("{}: {error}", path_display(archive)),
        )
    })?;
    let primary = format!(
        "{}/",
        overrides.unwrap_or("overrides").trim_end_matches('/')
    );
    let secondary = "client-overrides/";
    let mut written = 0_usize;
    let mut total = 0_u64;
    for index in 0..archive.len() {
        let mut entry = match archive.by_index(index) {
            Ok(entry) => entry,
            Err(error) => {
                tracing::warn!(%error, "skipping unreadable modpack entry");
                continue;
            }
        };
        let Ok(name) = entry.name().map(std::borrow::Cow::into_owned) else {
            continue;
        };
        let relative = name
            .strip_prefix(&primary)
            .or_else(|| name.strip_prefix(secondary));
        let Some(relative) = relative else {
            continue;
        };
        if entry.is_dir() {
            continue;
        }
        let Some(relative) = safe_relative(relative) else {
            tracing::warn!(name = %name, "skipping unsafe modpack override path");
            continue;
        };
        if written >= MAX_OVERRIDE_ENTRIES {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.modpack.overrides",
                "too many override entries",
            ));
        }
        let size = entry.size();
        if size > MAX_OVERRIDE_FILE || total.saturating_add(size) > MAX_OVERRIDE_TOTAL {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.modpack.overrides",
                format!("{name} is too large"),
            ));
        }
        let target = game_dir.join(&relative);
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.modpack.overrides",
                    format!("{}: {error}", parent.display()),
                )
            })?;
        }
        let mut output = File::create(&target).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.modpack.overrides",
                format!("{}: {error}", target.display()),
            )
        })?;
        let copied = io::copy(&mut entry.by_ref().take(size), &mut output).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.modpack.overrides",
                format!("{}: {error}", target.display()),
            )
        })?;
        total = total.saturating_add(copied);
        written += 1;
    }
    Ok(written)
}

fn path_display(path: &Path) -> String {
    path.display().to_string()
}

/// Persisted metadata of an imported pack.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackRecord {
    #[serde(default = "schema_version")]
    pub schema_version: u32,
    pub kind: ModpackKind,
    pub name: String,
    #[serde(default)]
    pub version: String,
    pub minecraft: String,
    pub loader_kind: String,
    #[serde(default)]
    pub loader_version: String,
    #[serde(default)]
    pub source_path: String,
    #[serde(default)]
    pub installed_at_ms: i64,
    #[serde(default)]
    pub files: Vec<String>,
    #[serde(default)]
    pub overrides: Option<String>,
}

const fn schema_version() -> u32 {
    1
}

fn write_record(instance_dir: &Path, pack: &Modpack, source: &str) -> Result<()> {
    fs::create_dir_all(instance_dir).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.record",
            format!("{}: {error}", instance_dir.display()),
        )
    })?;
    let record = ModpackRecord {
        schema_version: schema_version(),
        kind: pack.kind,
        name: pack.name.clone(),
        version: pack.version.clone(),
        minecraft: pack.minecraft.clone(),
        loader_kind: pack.loader_kind.clone(),
        loader_version: pack.loader_version.clone(),
        source_path: source.to_string(),
        installed_at_ms: now_ms(),
        files: pack.files.iter().map(|file| file.path.clone()).collect(),
        overrides: pack.overrides.clone(),
    };
    let text = serde_json::to_string_pretty(&record).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.modpack.record",
            error.to_string(),
        )
    })?;
    let path = instance_dir.join(MODPACK_FILE);
    fs::write(&path, text).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.record",
            format!("{}: {error}", path.display()),
        )
    })
}

/// Reads `modpack.json`; `None` when the instance was not created from a pack.
#[must_use]
pub fn read_record(instance_dir: &Path) -> Option<ModpackRecord> {
    let text = fs::read_to_string(instance_dir.join(MODPACK_FILE)).ok()?;
    serde_json::from_str(&text).ok()
}

/// Result of `modpack.export`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Export {
    pub path: String,
    pub size_bytes: u64,
    pub files: usize,
}

/// Writes a `.mrpack` with every local override under `overrides/`.
///
/// Managed files go into `overrides/` too (not into `files[]`): the launcher
/// does not keep the original download URLs in its content index, and a pack
/// that cannot be re-downloaded is worse than a bigger one.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the export directory or archive cannot be
/// written.
pub fn export_mrpack(
    instance_name: &str,
    minecraft: &str,
    loader_kind: &str,
    loader_version: &str,
    game_dir: &Path,
    exports_dir: &Path,
) -> Result<Export> {
    fs::create_dir_all(exports_dir).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.export",
            format!("{}: {error}", exports_dir.display()),
        )
    })?;
    let now = OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc());
    let stamp = now
        .format(&format_description!(
            "[year][month][day]-[hour][minute][second]"
        ))
        .unwrap_or_else(|_| "pack".to_string());
    let mut path = exports_dir.join(format!("{}-{stamp}.mrpack", sanitize(instance_name)));
    let mut counter = 2;
    while path.exists() {
        path = exports_dir.join(format!(
            "{}-{stamp}-{counter}.mrpack",
            sanitize(instance_name)
        ));
        counter += 1;
    }

    let file = File::create(&path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.modpack.export",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    let index = serde_json::json!({
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": format!("fograin-{stamp}"),
        "name": instance_name,
        "summary": "由雾雨启动器导出",
        "files": [],
        "dependencies": dependencies(loader_kind, loader_version, minecraft),
    });
    writer
        .start_file(MRPACK_INDEX, options)
        .map_err(export_error)?;
    writer
        .write_all(index.to_string().as_bytes())
        .map_err(export_error)?;

    let mut files = 0_usize;
    let mut total = 0_u64;
    for directory in [
        "mods",
        "config",
        "defaultconfigs",
        "resourcepacks",
        "shaderpacks",
    ] {
        let root = game_dir.join(directory);
        if root.is_dir() {
            add_tree(&mut writer, &root, directory, &mut files, &mut total)?;
        }
    }
    let options_txt = game_dir.join("options.txt");
    if options_txt.is_file() {
        add_file(
            &mut writer,
            &options_txt,
            "overrides/options.txt",
            &mut total,
        )?;
        files += 1;
    }
    writer.finish().map_err(export_error)?;
    let size_bytes = fs::metadata(&path).map_or(0, |metadata| metadata.len());
    Ok(Export {
        path: path.display().to_string(),
        size_bytes,
        files,
    })
}

fn dependencies(loader_kind: &str, loader_version: &str, minecraft: &str) -> Value {
    let mut map = serde_json::Map::new();
    map.insert(
        "minecraft".to_string(),
        Value::String(minecraft.to_string()),
    );
    let key = match loader_kind {
        "fabric" => Some("fabric-loader"),
        "quilt" => Some("quilt-loader"),
        "forge" => Some("forge"),
        "neoforge" => Some("neoforge"),
        _ => None,
    };
    if let Some(key) = key {
        map.insert(key.to_string(), Value::String(loader_version.to_string()));
    }
    Value::Object(map)
}

fn add_tree(
    writer: &mut zip::ZipWriter<File>,
    directory: &Path,
    prefix: &str,
    files: &mut usize,
    total: &mut u64,
) -> Result<()> {
    let Ok(entries) = fs::read_dir(directory) else {
        return Ok(());
    };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        if *files >= MAX_EXPORT_ENTRIES || *total >= MAX_EXPORT_TOTAL {
            break;
        }
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            continue;
        };
        if metadata.file_type().is_symlink() {
            continue;
        }
        if metadata.is_dir() {
            add_tree(writer, &path, &format!("{prefix}/{name}"), files, total)?;
        } else if metadata.is_file() {
            let archive_name = format!("overrides/{prefix}/{name}");
            add_file(writer, &path, &archive_name, total)?;
            *files += 1;
        }
    }
    Ok(())
}

fn add_file(
    writer: &mut zip::ZipWriter<File>,
    source: &Path,
    archive_name: &str,
    total: &mut u64,
) -> Result<()> {
    let metadata = fs::metadata(source).map_err(export_error)?;
    if metadata.len() > MAX_EXPORT_FILE {
        return Ok(());
    }
    if total.saturating_add(metadata.len()) > MAX_EXPORT_TOTAL {
        return Ok(());
    }
    let mut input = File::open(source).map_err(export_error)?;
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    writer
        .start_file(archive_name, options)
        .map_err(export_error)?;
    let copied = io::copy(&mut input, writer).map_err(export_error)?;
    *total = total.saturating_add(copied);
    Ok(())
}

fn export_error(error: impl std::fmt::Display) -> Error {
    Error::with_detail(ErrorKind::Io, "error.modpack.export", error.to_string())
}

fn sanitize(name: &str) -> String {
    let mut text: String = name
        .chars()
        .map(|character| {
            if character.is_alphanumeric() || matches!(character, '-' | '_' | '.') {
                character
            } else {
                '-'
            }
        })
        .collect();
    while text.contains("--") {
        text = text.replace("--", "-");
    }
    let trimmed = text.trim_matches(['-', '.'].as_slice()).to_string();
    if trimmed.is_empty() {
        "modpack".to_string()
    } else {
        trimmed.chars().take(48).collect()
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
        .unwrap_or(0)
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

    fn pack_zip(path: &Path, index: &Value, overrides: &[(&str, &str)]) {
        let file = File::create(path).expect("create");
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        writer.start_file(MRPACK_INDEX, options).expect("index");
        writer
            .write_all(index.to_string().as_bytes())
            .expect("write index");
        for (name, text) in overrides {
            writer.start_file(*name, options).expect("override");
            writer.write_all(text.as_bytes()).expect("write override");
        }
        writer.finish().expect("finish");
    }

    #[test]
    fn inspects_a_modrinth_pack() {
        let temp = tempfile::tempdir().expect("tempdir");
        let path = temp.path().join("calm.mrpack");
        pack_zip(
            &path,
            &serde_json::json!({
                "formatVersion": 1,
                "name": "Calm Pack",
                "versionId": "1.2.0",
                "summary": "quiet",
                "files": [
                    {"path": "mods/sodium.jar", "fileSize": 100,
                     "hashes": {"sha1": "aa", "sha512": "bb"},
                     "downloads": ["https://cdn.example/sodium.jar"]},
                    {"path": "mods/server-only.jar", "fileSize": 5,
                     "env": {"client": "unsupported"},
                     "downloads": ["https://cdn.example/server.jar"]},
                    {"path": "../escape.jar", "fileSize": 5, "downloads": []}
                ],
                "dependencies": {"minecraft": "1.21.4", "fabric-loader": "0.16.9"}
            }),
            &[("overrides/config/fog.toml", "quiet = true\n")],
        );

        let pack = inspect(&path).expect("inspect");
        assert_eq!(pack.kind, ModpackKind::Modrinth);
        assert_eq!(pack.name, "Calm Pack");
        assert_eq!(pack.minecraft, "1.21.4");
        assert_eq!(pack.loader_kind, "fabric");
        assert_eq!(pack.loader_version, "0.16.9");
        assert_eq!(pack.files.len(), 1);
        assert_eq!(pack.skipped, 2);
        assert_eq!(pack.files[0].path, "mods/sodium.jar");
        assert_eq!(pack.files[0].sha1.as_deref(), Some("aa"));
    }

    #[test]
    fn inspects_curseforge_and_hmcl_manifests() {
        let temp = tempfile::tempdir().expect("tempdir");
        let curse = temp.path().join("curse.zip");
        let file = File::create(&curse).expect("create");
        let mut writer = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        writer
            .start_file("manifest.json", options)
            .expect("manifest");
        writer
            .write_all(
                serde_json::json!({
                    "manifestType": "minecraftModpack",
                    "name": "Curse Pack",
                    "version": "2.0",
                    "author": "someone",
                    "minecraft": {
                        "version": "1.20.1",
                        "loaders": [{"id": "forge-47.2.0", "primary": true}]
                    },
                    "files": [{"projectID": 238222, "fileID": 4556, "required": true}]
                })
                .to_string()
                .as_bytes(),
            )
            .expect("write");
        writer.finish().expect("finish");

        let pack = inspect(&curse).expect("inspect curse");
        assert_eq!(pack.kind, ModpackKind::CurseForge);
        assert_eq!(pack.loader_kind, "forge");
        assert_eq!(pack.loader_version, "47.2.0");
        assert_eq!(pack.files.len(), 1);
        assert_eq!(pack.files[0].project_id.as_deref(), Some("238222"));
        assert_eq!(pack.files[0].file_id, Some(4556));

        let hmcl = temp.path().join("hmcl.zip");
        let file = File::create(&hmcl).expect("create");
        let mut writer = zip::ZipWriter::new(file);
        writer
            .start_file("modpack.json", options)
            .expect("manifest");
        writer
            .write_all(
                serde_json::json!({
                    "name": "HMCL Pack",
                    "gameVersion": "1.19.2",
                    "modLoader": "fabric",
                    "modLoaderVersion": "0.15.0"
                })
                .to_string()
                .as_bytes(),
            )
            .expect("write");
        writer.finish().expect("finish");
        let pack = inspect(&hmcl).expect("inspect hmcl");
        assert_eq!(pack.kind, ModpackKind::Hmcl);
        assert_eq!(pack.loader_kind, "fabric");
        assert_eq!(pack.minecraft, "1.19.2");
    }

    #[test]
    fn inspects_a_multimc_instance_folder() {
        let temp = tempfile::tempdir().expect("tempdir");
        let instance = temp.path().join("Prism Instance");
        fs::create_dir_all(instance.join("minecraft/mods")).expect("dirs");
        fs::write(
            instance.join("mmc-pack.json"),
            serde_json::json!({
                "components": [
                    {"uid": "net.minecraft", "version": "1.21.4"},
                    {"uid": "net.fabricmc.fabric-loader", "version": "0.16.9"}
                ]
            })
            .to_string(),
        )
        .expect("mmc");
        fs::write(instance.join("instance.cfg"), "name=我的整合包\n").expect("cfg");

        let pack = inspect(&instance).expect("inspect");
        assert_eq!(pack.kind, ModpackKind::MultiMc);
        assert!(pack.external);
        assert_eq!(pack.name, "我的整合包");
        assert_eq!(pack.minecraft, "1.21.4");
        assert_eq!(pack.loader_kind, "fabric");
        assert_eq!(
            pack.game_dir.as_deref(),
            Some(instance.join("minecraft").to_str().unwrap())
        );
    }

    #[test]
    fn rejects_unknown_archives_and_unsafe_paths() {
        let temp = tempfile::tempdir().expect("tempdir");
        let path = temp.path().join("empty.zip");
        let file = File::create(&path).expect("create");
        let writer = zip::ZipWriter::new(file);
        writer.finish().expect("finish");
        assert_eq!(
            inspect(&path).expect_err("no manifest").kind,
            ErrorKind::Unsupported
        );
        assert_eq!(safe_relative("mods/a.jar").as_deref(), Some("mods/a.jar"));
        assert_eq!(safe_relative("mods/../a.jar"), None);
        assert_eq!(safe_relative("/etc/passwd"), None);
        assert_eq!(safe_relative("C:\\mods\\a.jar"), None);
        assert_eq!(
            safe_relative("./mods//a.jar").as_deref(),
            Some("mods/a.jar")
        );
    }

    #[test]
    fn exports_overrides_and_dependencies() {
        let temp = tempfile::tempdir().expect("tempdir");
        let game = temp.path().join("game");
        fs::create_dir_all(game.join("mods")).expect("mods");
        fs::create_dir_all(game.join("config")).expect("config");
        fs::write(game.join("mods/sodium.jar"), b"jar").expect("mod");
        fs::write(game.join("config/fog.toml"), b"a=1").expect("config");
        fs::write(game.join("options.txt"), b"fov:0.5").expect("options");
        let exports = temp.path().join("exports");
        let export =
            export_mrpack("雾林", "1.21.4", "fabric", "0.16.9", &game, &exports).expect("export");
        assert!(export.files >= 3);
        assert!(export.path.ends_with(".mrpack"));

        let file = File::open(&export.path).expect("open");
        let mut archive = ZipArchive::new(file).expect("archive");
        let mut index = String::new();
        archive
            .by_name(MRPACK_INDEX)
            .expect("index")
            .read_to_string(&mut index)
            .expect("read");
        let index: Value = serde_json::from_str(&index).expect("json");
        assert_eq!(index["dependencies"]["minecraft"], "1.21.4");
        assert_eq!(index["dependencies"]["fabric-loader"], "0.16.9");
        let names: Vec<String> = (0..archive.len())
            .map(|index| {
                archive
                    .by_index(index)
                    .expect("entry")
                    .name()
                    .expect("name")
                    .to_string()
            })
            .collect();
        assert!(names.contains(&"overrides/mods/sodium.jar".to_string()));
        assert!(names.contains(&"overrides/config/fog.toml".to_string()));
        assert!(names.contains(&"overrides/options.txt".to_string()));
    }
}
