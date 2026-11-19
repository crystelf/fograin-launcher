//! Turn a project version and its required dependencies into a download plan
//! and a `fg-task` graph.

use std::{
    collections::{HashMap, HashSet, VecDeque},
    path::{Path, PathBuf},
    sync::Arc,
};

use fg_core::{Error, ErrorKind, Result};
use fg_net::ProgressSink;
use fg_net::{DownloadRequest, Downloader, Integrity, Priority};
use fg_task::{TaskCx, TaskGraph};
use serde::Serialize;

use crate::{
    ContentClient, ContentKind, ContentSource, ContentVersion, IndexEntry,
    index::{ContentIndex, now_ms},
    model::validate_project_id,
};

/// Maximum dependency depth. Mods normally need one level; the limit stops a
/// malformed dependency graph from turning into an unbounded crawl.
const MAX_DEPENDENCY_DEPTH: usize = 4;

/// How an item entered the plan.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PlannedFileKind {
    Primary,
    RequiredDependency,
}

/// One file that will be downloaded.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannedFile {
    pub file_name: String,
    pub project_id: String,
    pub project_name: String,
    pub version_id: String,
    pub version_number: String,
    pub dependency: PlannedFileKind,
    pub size: u64,
    pub url: String,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
}

/// Request used by the FFI layer and the CLI.
#[derive(Debug, Clone)]
pub struct InstallRequest {
    pub source: ContentSource,
    pub project_id: String,
    pub version_id: Option<String>,
    pub kind: ContentKind,
    pub instance_id: String,
    pub instance_dir: PathBuf,
    pub game_dir: PathBuf,
    pub game_version: String,
    pub loader: Option<String>,
    /// Target world for data packs; ignored by the other kinds.
    pub world: Option<String>,
    /// Old file names removed after the new files are in place (content update).
    pub replace_files: Vec<String>,
    /// Project ids already installed in the instance; required dependencies
    /// that are already present are not downloaded again.
    pub installed_projects: Vec<String>,
    /// Whether required dependencies are resolved and downloaded too. The UI
    /// asks before pulling prerequisites, so it can pass `false` to install
    /// only the project itself.
    pub install_dependencies: bool,
}

/// Resolved installation, ready to become a [`TaskGraph`].
#[derive(Debug, Clone)]
pub struct ContentPlan {
    pub source: ContentSource,
    pub kind: ContentKind,
    pub instance_id: String,
    pub instance_dir: PathBuf,
    pub game_dir: PathBuf,
    /// Target world for data packs.
    pub world: Option<String>,
    pub project_id: String,
    pub project_name: String,
    pub version_id: String,
    pub version_number: String,
    pub items: Vec<PlannedFile>,
    pub total_bytes: u64,
    pub replace_files: Vec<String>,
    pub warnings: Vec<String>,
}

impl ContentPlan {
    /// Directory the planned files are written to.
    #[must_use]
    pub fn target_dir(&self) -> PathBuf {
        target_directory(self.kind, &self.game_dir, self.world.as_deref())
    }

    /// Builds the task graph. Every file is one node; a final zero-byte node
    /// updates the instance index only when every download succeeded.
    #[must_use]
    pub fn graph(
        self: &Arc<Self>,
        downloader: Arc<Downloader>,
        client: ContentClient,
    ) -> TaskGraph {
        let name = format!("content:{}", self.project_name);
        let mut builder = TaskGraph::builder(name);
        let mut nodes = Vec::with_capacity(self.items.len());

        let target_dir = self.target_dir();
        for item in &self.items {
            let weight = (item.size / (64 * 1024)).clamp(1, 200_000) as u32;
            let downloader = Arc::clone(&downloader);
            let client = client.clone();
            let item = item.clone();
            let dest = target_dir.join(&item.file_name);
            let node = builder.task(
                format!("content.file:{}", item.file_name),
                weight,
                move |cx| {
                    let downloader = Arc::clone(&downloader);
                    let client = client.clone();
                    let item = item.clone();
                    let dest = dest.clone();
                    async move {
                        cx.check()?;
                        let candidates = client.candidates(&item.url)?;
                        if candidates.is_empty() {
                            return Err(Error::with_detail(
                                ErrorKind::Network,
                                "error.content.no_candidates",
                                item.url.clone(),
                            ));
                        }
                        let mut request = DownloadRequest::new(dest, candidates)
                            .with_priority(Priority::Mod)
                            .with_size_hint(item.size);
                        if let Some(sha1) = &item.sha1 {
                            request = request.with_sha1(sha1);
                        } else if let Some(sha512) = &item.sha512 {
                            request = request.with_integrity(Integrity::sha512(sha512));
                        } else if item.size > 0 {
                            request = request.with_size(item.size);
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
                },
            );
            nodes.push(node);
        }

        let plan = Arc::clone(self);
        let finalize = builder.task("content.index", 1, move |cx| {
            let plan = Arc::clone(&plan);
            async move {
                cx.check()?;
                if !plan.kind.locally_scanned() {
                    // Data packs are world-scoped and live outside the index;
                    // modpacks do not go through this plan at all.
                    return Ok(());
                }
                let mut index = ContentIndex::load(&plan.instance_dir)?;
                let planned: HashSet<&str> = plan
                    .items
                    .iter()
                    .map(|item| item.file_name.as_str())
                    .collect();
                for file_name in &plan.replace_files {
                    // A reinstall can legitimately reuse the same file name;
                    // never delete a file this plan just wrote.
                    if planned.contains(file_name.as_str()) {
                        continue;
                    }
                    index.remove(file_name);
                    let path = plan.kind.directory(&plan.game_dir).join(file_name);
                    remove_replaced(&path);
                }
                for item in &plan.items {
                    index.upsert(IndexEntry {
                        file_name: item.file_name.clone(),
                        kind: plan.kind,
                        source: plan.source.as_str().to_string(),
                        project_id: item.project_id.clone(),
                        project_name: item.project_name.clone(),
                        version_id: item.version_id.clone(),
                        version_number: item.version_number.clone(),
                        sha1: item.sha1.clone(),
                        installed_at_ms: now_ms(),
                    });
                }
                index.save(&plan.instance_dir)
            }
        });
        for node in nodes {
            builder.depends_on(finalize, node);
        }
        builder.build()
    }
}

/// Resolves the root project plus every required dependency.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the requested version has no file and
/// propagates API failures for the root project. Missing or incompatible
/// dependencies are reported in [`ContentPlan::warnings`] instead of failing
/// the whole install.
pub async fn plan_install(client: &ContentClient, request: InstallRequest) -> Result<ContentPlan> {
    validate_project_id(&request.project_id)?;
    if request.kind == ContentKind::Modpack {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.content.kind",
            "modpacks are installed through modpack.import".to_string(),
        ));
    }
    let world = if request.kind.world_scoped() {
        let world = request
            .world
            .as_deref()
            .map(str::trim)
            .filter(|world| !world.is_empty())
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.world.name",
                    "data packs need a target world",
                )
            })?
            .to_string();
        validate_world_name(&world)?;
        Some(world)
    } else {
        None
    };
    if request.kind == ContentKind::Mod && request.loader.is_none() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.content.loader_required",
            "mods need a Fabric, Quilt, Forge or NeoForge instance".to_string(),
        ));
    }
    for file_name in &request.replace_files {
        validate_replace_name(file_name)?;
    }
    let root_project_id = request.project_id.trim().to_string();
    let root_project = match request.source {
        ContentSource::Modrinth => client.modrinth_project(&root_project_id).await?,
        ContentSource::Curseforge => client.curseforge_project(&root_project_id).await?,
    };

    let installed: HashSet<String> = request.installed_projects.into_iter().collect();
    let mut project_names: HashMap<String, String> =
        HashMap::from([(root_project_id.clone(), root_project.title)]);
    let mut seen: HashSet<String> = HashSet::new();
    let mut queue: VecDeque<(String, Option<String>, PlannedFileKind, usize)> = VecDeque::new();
    queue.push_back((
        root_project_id.clone(),
        request.version_id.clone(),
        PlannedFileKind::Primary,
        0,
    ));

    let mut items: Vec<PlannedFile> = Vec::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut total_bytes = 0_u64;
    let mut names: HashSet<String> = HashSet::new();

    while let Some((project_id, version_id, role, depth)) = queue.pop_front() {
        if depth > MAX_DEPENDENCY_DEPTH {
            warnings.push(format!("依赖层级过深，已跳过 {project_id}"));
            continue;
        }
        if !seen.insert(project_id.clone()) {
            continue;
        }

        let version = match resolve_version(
            client,
            request.source,
            &project_id,
            version_id.as_deref(),
            &request.game_version,
            request.loader.as_deref(),
            request.kind,
        )
        .await
        {
            Ok(version) => version,
            Err(error) => {
                if role == PlannedFileKind::Primary {
                    return Err(error);
                }
                warnings.push(format!("前置 {project_id} 无法安装：{error}"));
                continue;
            }
        };

        if role == PlannedFileKind::RequiredDependency && installed.contains(&project_id) {
            continue;
        }

        if !project_names.contains_key(&project_id) {
            let title = match fetch_project_title(client, request.source, &project_id).await {
                Ok(title) => title,
                Err(error) => {
                    warnings.push(format!("无法读取前置 {project_id} 的信息：{error}"));
                    continue;
                }
            };
            project_names.insert(project_id.clone(), title);
        }
        let project_name = project_names
            .get(&project_id)
            .cloned()
            .unwrap_or_else(|| project_id.clone());

        let mut file = match version.file.clone() {
            Some(file) => file,
            None => {
                if role == PlannedFileKind::Primary {
                    return Err(Error::with_detail(
                        ErrorKind::NotFound,
                        "error.content.no_files",
                        project_id.clone(),
                    ));
                }
                warnings.push(format!("前置 {project_name} 没有可下载文件"));
                continue;
            }
        };
        if file.url.trim().is_empty() {
            if request.source == ContentSource::Curseforge {
                let file_id = version.version_id.parse::<u64>().map_err(|_| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.content.version_id",
                        version.version_id.clone(),
                    )
                })?;
                match client.curseforge_download_url(&project_id, file_id).await {
                    Ok(url) => file.url = url,
                    Err(error) => {
                        if role == PlannedFileKind::Primary {
                            return Err(error);
                        }
                        warnings.push(format!("前置 {project_name} 下载地址不可用：{error}"));
                        continue;
                    }
                }
            } else {
                return Err(Error::with_detail(
                    ErrorKind::NotFound,
                    "error.content.no_files",
                    project_id.clone(),
                ));
            }
        }

        let base_name = sanitize_file_name(
            &file.file_name,
            &project_id,
            &version.version_id,
            request.kind,
        );
        let file_name = unique_name(&mut names, &base_name, &project_id);
        total_bytes = total_bytes.saturating_add(file.size);
        items.push(PlannedFile {
            file_name,
            project_id: project_id.clone(),
            project_name: project_name.clone(),
            version_id: version.version_id.clone(),
            version_number: version.version_number.clone(),
            dependency: role,
            size: file.size,
            url: file.url,
            sha1: file.sha1,
            sha512: file.sha512,
        });

        if request.install_dependencies {
            for dependency in version.dependencies.iter().filter(|dep| dep.is_required()) {
                let Some(dep_project_id) = dependency.project_id.clone() else {
                    continue;
                };
                if seen.contains(&dep_project_id) || installed.contains(&dep_project_id) {
                    continue;
                }
                queue.push_back((
                    dep_project_id,
                    dependency.version_id.clone(),
                    PlannedFileKind::RequiredDependency,
                    depth + 1,
                ));
            }
        }
    }

    let (project_name, version_id, version_number) = items
        .iter()
        .find(|item| item.dependency == PlannedFileKind::Primary)
        .map(|item| {
            (
                item.project_name.clone(),
                item.version_id.clone(),
                item.version_number.clone(),
            )
        })
        .ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.content.no_files",
                root_project_id.clone(),
            )
        })?;

    Ok(ContentPlan {
        source: request.source,
        kind: request.kind,
        instance_id: request.instance_id,
        instance_dir: request.instance_dir,
        game_dir: request.game_dir,
        world,
        project_id: root_project_id,
        project_name,
        version_id,
        version_number,
        items,
        total_bytes,
        replace_files: request.replace_files,
        warnings,
    })
}

async fn resolve_version(
    client: &ContentClient,
    source: ContentSource,
    project_id: &str,
    version_id: Option<&str>,
    game_version: &str,
    loader: Option<&str>,
    kind: ContentKind,
) -> Result<ContentVersion> {
    let game = Some(game_version).filter(|value| !value.is_empty());
    let loader = if kind.loader_specific() { loader } else { None };
    match source {
        ContentSource::Modrinth => {
            client
                .modrinth_resolve_version(project_id, version_id, game, loader)
                .await
        }
        ContentSource::Curseforge => {
            client
                .curseforge_resolve_version(project_id, version_id, game, loader)
                .await
        }
    }
}

async fn fetch_project_title(
    client: &ContentClient,
    source: ContentSource,
    project_id: &str,
) -> Result<String> {
    match source {
        ContentSource::Modrinth => client
            .modrinth_project(project_id)
            .await
            .map(|project| project.title),
        ContentSource::Curseforge => client
            .curseforge_project(project_id)
            .await
            .map(|project| project.title),
    }
}

fn sanitize_file_name(raw: &str, project_id: &str, version_id: &str, kind: ContentKind) -> String {
    let name = raw.rsplit(['/', '\\']).next().unwrap_or_default().trim();
    let mut sanitized = String::with_capacity(name.len());
    for character in name.chars() {
        if character.is_alphanumeric()
            || matches!(
                character,
                '.' | '-' | '_' | '+' | ' ' | '(' | ')' | '[' | ']' | '#' | '@'
            )
        {
            sanitized.push(character);
        } else {
            sanitized.push('_');
        }
    }
    let sanitized = sanitized.trim().trim_matches('.').to_string();
    if sanitized.is_empty() || sanitized == "." || sanitized == ".." {
        let extension = match kind {
            ContentKind::Mod => "jar",
            ContentKind::Modpack => "mrpack",
            ContentKind::ResourcePack | ContentKind::ShaderPack | ContentKind::Datapack => "zip",
        };
        return format!("{project_id}-{version_id}.{extension}");
    }
    sanitized
}

fn unique_name(names: &mut HashSet<String>, base: &str, project_id: &str) -> String {
    if names.insert(base.to_string()) {
        return base.to_string();
    }
    let (stem, extension) = match base.rsplit_once('.') {
        Some((stem, extension)) if !stem.is_empty() => (stem, format!(".{extension}")),
        _ => (base, String::new()),
    };
    let mut counter = 2;
    loop {
        let candidate = format!("{stem}-{project_id}-{counter}{extension}");
        if names.insert(candidate.clone()) {
            return candidate;
        }
        counter += 1;
    }
}

fn target_directory(kind: ContentKind, game_dir: &Path, world: Option<&str>) -> PathBuf {
    match kind {
        ContentKind::Datapack => game_dir
            .join("saves")
            .join(world.unwrap_or_default())
            .join("datapacks"),
        ContentKind::Modpack => game_dir.join("modpacks"),
        _ => kind.directory(game_dir),
    }
}

fn validate_world_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
        || name.starts_with('.')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.name",
            format!("unsafe world name {name:?}"),
        ));
    }
    Ok(())
}

fn validate_replace_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.content.file",
            format!("unsafe replace file name {name:?}"),
        ));
    }
    Ok(())
}

fn remove_replaced(path: &std::path::Path) {
    let Ok(metadata) = std::fs::symlink_metadata(path) else {
        return;
    };
    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        let _ = std::fs::remove_dir_all(path);
    } else {
        let _ = std::fs::remove_file(path);
    }
}

struct TaskSink {
    cx: TaskCx,
}

impl ProgressSink for TaskSink {
    fn on_bytes(&self, bytes: u64) {
        self.cx.advance(bytes);
    }
}

/// Converts the download source into the install priority used by the engine.
#[must_use]
pub const fn install_priority() -> Priority {
    Priority::Mod
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_names_are_sanitized_and_unique() {
        assert_eq!(
            sanitize_file_name("../sodium 0.6.13+mc1.21.4.jar", "p", "v", ContentKind::Mod),
            "sodium 0.6.13+mc1.21.4.jar"
        );
        assert_eq!(
            sanitize_file_name("", "p", "v", ContentKind::Mod),
            "p-v.jar"
        );
        assert_eq!(
            sanitize_file_name("bad:name?.zip", "p", "v", ContentKind::ResourcePack),
            "bad_name_.zip"
        );

        let mut names = HashSet::new();
        assert_eq!(unique_name(&mut names, "sodium.jar", "p"), "sodium.jar");
        assert_eq!(unique_name(&mut names, "sodium.jar", "p"), "sodium-p-2.jar");
        assert_eq!(unique_name(&mut names, "sodium.jar", "p"), "sodium-p-3.jar");
    }

    #[test]
    fn world_scoped_kinds_target_the_datapacks_folder() {
        assert_eq!(
            target_directory(ContentKind::Datapack, Path::new("/game"), Some("New World")),
            PathBuf::from("/game/saves/New World/datapacks")
        );
        assert_eq!(
            target_directory(ContentKind::Mod, Path::new("/game"), None),
            PathBuf::from("/game/mods")
        );
        assert!(validate_world_name("World").is_ok());
        assert!(validate_world_name("../escape").is_err());
        assert!(validate_world_name(".hidden").is_err());
    }

    #[test]
    fn priorities_stay_mod_level() {
        assert_eq!(install_priority(), Priority::Mod);
    }
}
