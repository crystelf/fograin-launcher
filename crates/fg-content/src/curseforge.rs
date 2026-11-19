//! CurseForge API through the MCIM mirror.
//!
//! CurseForge's official API requires an API key, which the launcher does not
//! ship. `fg-net` therefore maps `https://api.curseforge.com` to MCIM and marks
//! the official host as unavailable, matching the policy in `docs/design.md`
//! §5.8. File downloads go through the same mirror rules.

use fg_core::{Error, ErrorKind, Result};
use fg_net::Url;
use serde::Deserialize;

use crate::{
    ContentClient, ContentDependency, ContentFile, ContentSearchPage, ContentSource,
    ContentVersion, Project, SearchQuery, VersionType,
    api::CURSEFORGE_API,
    model::{
        curseforge_loader_type, dependency_kind_from_curseforge, validate_project_id,
        version_type_from_curseforge,
    },
};

const GAME_ID: u32 = 432;

impl ContentClient {
    /// Searches CurseForge projects through MCIM.
    ///
    /// # Errors
    ///
    /// Propagates network and response-shape failures.
    pub async fn curseforge_search(&self, query: &SearchQuery) -> Result<ContentSearchPage> {
        let limit = query.limit.clamp(1, 50);
        let mut url = endpoint("/mods/search")?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("gameId", &GAME_ID.to_string());
            pairs.append_pair("classId", &query.kind.curseforge_class_id().to_string());
            pairs.append_pair("searchFilter", query.query.trim());
            pairs.append_pair("sortField", "2");
            pairs.append_pair("sortOrder", "desc");
            pairs.append_pair("index", &query.offset.to_string());
            pairs.append_pair("pageSize", &limit.to_string());
            if let Some(game_version) = query.game_version.as_deref().filter(|it| !it.is_empty()) {
                pairs.append_pair("gameVersion", game_version);
            }
            if query.kind.loader_specific()
                && let Some(loader_type) = curseforge_loader_type(query.loader.as_deref())
            {
                pairs.append_pair("modLoaderType", &loader_type.to_string());
            }
        }

        let raw: RawSearch = self.fetch_json(url.as_str()).await?;
        Ok(ContentSearchPage {
            source: ContentSource::Curseforge,
            projects: raw.data.into_iter().map(RawProject::into_project).collect(),
            total: raw.pagination.total_count,
            offset: raw.pagination.index,
            limit: raw.pagination.page_size,
        })
    }

    /// Reads one CurseForge project.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and propagates
    /// network/parse failures.
    pub async fn curseforge_project(&self, project_id: &str) -> Result<Project> {
        let id = curseforge_id(project_id)?;
        let url = endpoint(&format!("/mods/{id}"))?;
        let raw: RawProjectEnvelope = self.fetch_json(url.as_str()).await?;
        Ok(raw.data.into_project())
    }

    /// Lists files (versions) of a project, newest first.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and propagates
    /// network/parse failures.
    pub async fn curseforge_versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<Vec<ContentVersion>> {
        let id = curseforge_id(project_id)?;
        let mut url = endpoint(&format!("/mods/{id}/files"))?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("pageSize", "50");
            pairs.append_pair("index", "0");
            if let Some(game_version) = game_version.filter(|value| !value.is_empty()) {
                pairs.append_pair("gameVersion", game_version);
            }
            if let Some(loader_type) = curseforge_loader_type(loader) {
                pairs.append_pair("modLoaderType", &loader_type.to_string());
            }
        }

        let raw: RawFilesEnvelope = self.fetch_json(url.as_str()).await?;
        let mut versions: Vec<ContentVersion> = raw
            .data
            .into_iter()
            .filter(|file| file.matches(game_version, loader))
            .map(|file| file.into_version(id))
            .collect();
        versions.sort_by(|left, right| right.published.cmp(&left.published));
        Ok(versions)
    }

    /// Reads one file by id.
    ///
    /// # Errors
    ///
    /// Propagates network/parse failures and id validation.
    pub async fn curseforge_file(&self, project_id: &str, file_id: u64) -> Result<ContentVersion> {
        let id = curseforge_id(project_id)?;
        let url = endpoint(&format!("/mods/{id}/files/{file_id}"))?;
        let raw: RawFileEnvelope = self.fetch_json(url.as_str()).await?;
        Ok(raw.data.into_version(id))
    }

    /// Resolves the newest compatible version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when no file matches.
    pub async fn curseforge_latest_version(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<ContentVersion> {
        self.curseforge_versions(project_id, game_version, loader)
            .await?
            .into_iter()
            .next()
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.content.version_missing",
                    format!("{project_id} has no compatible file"),
                )
            })
    }

    /// Resolves an explicit version id, falling back to the latest compatible
    /// file when `version_id` is empty.
    ///
    /// # Errors
    ///
    /// Propagates network/parse failures and compatibility errors.
    pub async fn curseforge_resolve_version(
        &self,
        project_id: &str,
        version_id: Option<&str>,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<ContentVersion> {
        match version_id.map(str::trim).filter(|value| !value.is_empty()) {
            Some(version_id) => {
                let file_id = version_id.parse::<u64>().map_err(|_| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.content.version_id",
                        format!("CurseForge file id must be numeric: {version_id}"),
                    )
                })?;
                let version = self.curseforge_file(project_id, file_id).await?;
                if let Some(game_version) = game_version
                    && !version.game_versions.iter().any(|it| it == game_version)
                {
                    return Err(Error::with_detail(
                        ErrorKind::Conflict,
                        "error.content.version_incompatible",
                        format!("{version_id} does not support Minecraft {game_version}"),
                    ));
                }
                Ok(version)
            }
            None => {
                self.curseforge_latest_version(project_id, game_version, loader)
                    .await
            }
        }
    }

    /// Resolves a missing `downloadUrl` through the CurseForge download-url
    /// endpoint. Files with distribution disabled return an error.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Unsupported`] when the author disabled third-party
    /// downloads, and propagates network failures otherwise.
    pub async fn curseforge_download_url(&self, project_id: &str, file_id: u64) -> Result<String> {
        let id = curseforge_id(project_id)?;
        let url = endpoint(&format!("/mods/{id}/files/{file_id}/download-url"))?;
        let raw: RawStringEnvelope = self.fetch_json(url.as_str()).await?;
        if raw.data.trim().is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.content.download_disabled",
                format!("CurseForge file {file_id} has no download URL"),
            ));
        }
        Ok(raw.data)
    }
}

fn endpoint(path: &str) -> Result<Url> {
    Url::parse(&format!("{CURSEFORGE_API}{path}")).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.content.request",
            format!("{CURSEFORGE_API}{path}: {error}"),
        )
    })
}

fn curseforge_id(project_id: &str) -> Result<u64> {
    validate_project_id(project_id)?;
    project_id.trim().parse::<u64>().map_err(|_| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.content.project_id",
            format!("CurseForge project id must be numeric: {project_id}"),
        )
    })
}

#[derive(Debug, Deserialize)]
struct RawSearch {
    #[serde(default)]
    data: Vec<RawProject>,
    #[serde(default)]
    pagination: RawPagination,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawPagination {
    #[serde(default)]
    index: u32,
    #[serde(default)]
    page_size: u32,
    #[serde(default)]
    total_count: u64,
}

#[derive(Debug, Deserialize)]
struct RawProjectEnvelope {
    data: RawProject,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawProject {
    id: u64,
    #[serde(default)]
    name: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    summary: String,
    #[serde(default)]
    download_count: u64,
    #[serde(default)]
    logo: Option<RawLogo>,
    #[serde(default)]
    categories: Vec<RawCategory>,
    #[serde(default)]
    date_modified: Option<String>,
}

impl RawProject {
    fn into_project(self) -> Project {
        Project {
            source: ContentSource::Curseforge,
            project_id: self.id.to_string(),
            slug: self.slug,
            title: if self.name.trim().is_empty() {
                "Unknown project".to_string()
            } else {
                self.name
            },
            description: self.summary.trim().to_string(),
            downloads: self.download_count,
            follows: 0,
            icon_url: self.logo.and_then(|logo| {
                logo.thumbnail_url
                    .filter(|value| !value.is_empty())
                    .or(logo.url)
            }),
            categories: self
                .categories
                .into_iter()
                .map(|category| category.name)
                .collect(),
            updated: self.date_modified,
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawLogo {
    #[serde(default)]
    thumbnail_url: Option<String>,
    #[serde(default)]
    url: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawCategory {
    #[serde(default)]
    name: String,
}

#[derive(Debug, Deserialize)]
struct RawFilesEnvelope {
    #[serde(default)]
    data: Vec<RawFile>,
}

#[derive(Debug, Deserialize)]
struct RawFileEnvelope {
    data: RawFile,
}

#[derive(Debug, Deserialize)]
struct RawStringEnvelope {
    data: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawFile {
    id: u64,
    #[serde(default)]
    display_name: String,
    #[serde(default)]
    file_name: String,
    #[serde(default)]
    release_type: u8,
    #[serde(default)]
    file_date: Option<String>,
    #[serde(default)]
    file_length: u64,
    #[serde(default)]
    download_count: u64,
    #[serde(default)]
    download_url: Option<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    dependencies: Vec<RawDependency>,
    #[serde(default)]
    hashes: Vec<RawHash>,
}

impl RawFile {
    fn matches(&self, game_version: Option<&str>, loader: Option<&str>) -> bool {
        if let Some(game_version) = game_version.filter(|value| !value.is_empty())
            && !self.game_versions.is_empty()
            && !self
                .game_versions
                .iter()
                .any(|value| value.eq_ignore_ascii_case(game_version))
        {
            return false;
        }
        if let Some(loader) = loader.filter(|value| !value.is_empty()) {
            let wanted = match loader.to_ascii_lowercase().as_str() {
                "fabric" => "Fabric",
                "quilt" => "Quilt",
                "forge" => "Forge",
                "neoforge" => "NeoForge",
                _ => return true,
            };
            if !self.game_versions.is_empty()
                && !self
                    .game_versions
                    .iter()
                    .any(|value| value.eq_ignore_ascii_case(wanted))
            {
                return false;
            }
        }
        true
    }

    fn into_version(self, project_id: u64) -> ContentVersion {
        let loaders = self
            .game_versions
            .iter()
            .filter_map(|value| match value.to_ascii_lowercase().as_str() {
                "fabric" | "quilt" | "forge" | "neoforge" => Some(value.to_ascii_lowercase()),
                _ => None,
            })
            .collect::<Vec<_>>();
        let sha1 = self
            .hashes
            .iter()
            .find(|hash| hash.algo == 1)
            .map(|hash| hash.value.clone());
        let url = self.download_url.clone().unwrap_or_default();
        ContentVersion {
            source: ContentSource::Curseforge,
            project_id: project_id.to_string(),
            version_id: self.id.to_string(),
            name: if self.display_name.trim().is_empty() {
                self.file_name.clone()
            } else {
                self.display_name.clone()
            },
            version_number: if self.display_name.trim().is_empty() {
                self.file_name.clone()
            } else {
                self.display_name
            },
            game_versions: self.game_versions,
            loaders,
            downloads: self.download_count,
            version_type: version_type_from_curseforge(self.release_type),
            published: self.file_date,
            file: Some(ContentFile {
                file_name: self.file_name,
                url,
                size: self.file_length,
                sha1,
                sha512: None,
            }),
            dependencies: self
                .dependencies
                .into_iter()
                .map(|dependency| ContentDependency {
                    project_id: Some(dependency.mod_id.to_string()),
                    version_id: None,
                    kind: dependency_kind_from_curseforge(dependency.relation_type).to_string(),
                    name: None,
                })
                .collect(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct RawHash {
    #[serde(default)]
    value: String,
    #[serde(default)]
    algo: u8,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawDependency {
    #[serde(default)]
    mod_id: u64,
    #[serde(default)]
    relation_type: u8,
}

/// Parses CurseForge's `fileDate` for tests and sorting.
#[must_use]
pub fn normalize_release_type(value: u8) -> VersionType {
    version_type_from_curseforge(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn file(loader: &str, game: &str) -> RawFile {
        RawFile {
            id: 1,
            display_name: "Test 1.0".into(),
            file_name: "test-1.0.jar".into(),
            release_type: 1,
            file_date: Some("2026-01-01T00:00:00Z".into()),
            file_length: 10,
            download_count: 3,
            download_url: Some("https://edge.forgecdn.net/files/1/1/test.jar".into()),
            game_versions: vec![loader.to_string(), game.to_string(), "Client".into()],
            dependencies: vec![RawDependency {
                mod_id: 99,
                relation_type: 3,
            }],
            hashes: vec![RawHash {
                value: "abc".into(),
                algo: 1,
            }],
        }
    }

    #[test]
    fn files_filter_by_game_and_loader() {
        let target = file("Fabric", "1.21.4");
        assert!(target.matches(Some("1.21.4"), Some("fabric")));
        assert!(!target.matches(Some("1.20.1"), Some("fabric")));
        assert!(!target.matches(Some("1.21.4"), Some("forge")));
    }

    #[test]
    fn conversion_keeps_hashes_and_required_dependencies() {
        let version = file("Fabric", "1.21.4").into_version(77);
        assert_eq!(version.project_id, "77");
        assert_eq!(version.file.expect("file").sha1.as_deref(), Some("abc"));
        assert_eq!(version.dependencies[0].kind, "required");
        assert_eq!(version.dependencies[0].project_id.as_deref(), Some("99"));
    }

    #[test]
    fn ids_must_be_numeric() {
        assert_eq!(curseforge_id("123").expect("id"), 123);
        assert!(curseforge_id("sodium").is_err());
        assert!(curseforge_id("../1").is_err());
    }
}
