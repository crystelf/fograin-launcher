//! Modrinth v2 search, project and version endpoints.
//!
//! The launcher never talks to Modrinth with an API key. Requests go through
//! the mirror table, so mainland China users reach the MCIM cache first while
//! the official API stays a candidate elsewhere.

use fg_core::{Error, ErrorKind, Result};
use fg_net::Url;
use serde::Deserialize;

use crate::{
    ContentClient, ContentDependency, ContentFile, ContentSearchPage, ContentSource,
    ContentVersion, Project, SearchQuery, VersionType,
    api::MODRINTH_API,
    model::{modrinth_loaders, validate_project_id},
};

impl ContentClient {
    /// Searches projects of one kind.
    ///
    /// # Errors
    ///
    /// Propagates network and response-shape failures.
    pub async fn modrinth_search(&self, query: &SearchQuery) -> Result<ContentSearchPage> {
        let limit = query.limit.clamp(1, 50);
        let facets = modrinth_facets(query);
        let mut url = endpoint("/search")?;
        {
            let mut pairs = url.query_pairs_mut();
            pairs.append_pair("query", query.query.trim());
            pairs.append_pair("limit", &limit.to_string());
            pairs.append_pair("offset", &query.offset.to_string());
            if !facets.is_empty() {
                pairs.append_pair("facets", &facets);
            }
            pairs.append_pair(
                "index",
                if query.query.trim().is_empty() {
                    "downloads"
                } else {
                    "relevance"
                },
            );
        }

        let raw: RawSearch = self.fetch_json(url.as_str()).await?;
        Ok(ContentSearchPage {
            source: ContentSource::Modrinth,
            projects: raw.hits.into_iter().map(|hit| hit.into_project()).collect(),
            total: raw.total_hits,
            offset: raw.offset,
            limit: raw.limit,
        })
    }

    /// Reads one project.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and propagates
    /// network/parse failures.
    pub async fn modrinth_project(&self, project_id: &str) -> Result<Project> {
        validate_project_id(project_id)?;
        let url = endpoint(&format!("/project/{}", project_id.trim()))?;
        let raw: RawProject = self.fetch_json(url.as_str()).await?;
        Ok(raw.into_project())
    }

    /// Lists versions of a project, newest first.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and propagates
    /// network/parse failures.
    pub async fn modrinth_versions(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<Vec<ContentVersion>> {
        validate_project_id(project_id)?;
        let mut url = endpoint(&format!("/project/{}/version", project_id.trim()))?;
        {
            let mut pairs = url.query_pairs_mut();
            if let Some(game_version) = game_version.filter(|value| !value.is_empty()) {
                pairs.append_pair(
                    "game_versions",
                    &serde_json::to_string(&[game_version]).unwrap_or_default(),
                );
            }
            if let Some(loader) = loader.filter(|value| !value.is_empty()) {
                let loaders = modrinth_loaders(Some(loader));
                if !loaders.is_empty() {
                    pairs.append_pair(
                        "loaders",
                        &serde_json::to_string(&loaders).unwrap_or_default(),
                    );
                }
            }
        }

        let raw: Vec<RawVersion> = self.fetch_json(url.as_str()).await?;
        let mut versions: Vec<ContentVersion> = raw
            .into_iter()
            .filter(|version| version.matches(game_version, loader))
            .map(|version| version.into_version(project_id.trim()))
            .collect();
        // The mirror may reorder or serve a cached body, so newest-first is
        // enforced locally instead of trusting the response order.
        versions.sort_by(|left, right| right.published.cmp(&left.published));
        Ok(versions)
    }

    /// Reads one version by id.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and propagates
    /// network/parse failures.
    pub async fn modrinth_version(&self, version_id: &str) -> Result<ContentVersion> {
        validate_project_id(version_id)?;
        let url = endpoint(&format!("/version/{}", version_id.trim()))?;
        let raw: RawVersion = self.fetch_json(url.as_str()).await?;
        let project_id = raw.project_id.clone();
        Ok(raw.into_version(&project_id))
    }

    /// Looks up the newest version for each SHA-1 through
    /// `/version_files/update`. The response is keyed by the input hash.
    ///
    /// # Errors
    ///
    /// Propagates network and response-shape failures.
    pub async fn modrinth_versions_by_hashes(
        &self,
        hashes: &[String],
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<std::collections::HashMap<String, ContentVersion>> {
        let mut loaders = loader
            .and_then(|loader| {
                let values = modrinth_loaders(Some(loader));
                (!values.is_empty()).then_some(values)
            })
            .unwrap_or_default();
        loaders.sort();
        let body = serde_json::json!({
            "hashes": hashes,
            "algorithm": "sha1",
            "loaders": loaders,
            "game_versions": game_version
                .filter(|value| !value.is_empty())
                .map(|value| vec![value])
                .unwrap_or_default(),
        });
        let raw: std::collections::HashMap<String, RawVersion> = self
            .post_json("https://api.modrinth.com/v2/version_files/update", &body)
            .await?;
        Ok(raw
            .into_iter()
            .map(|(hash, version)| {
                let project_id = version.project_id.clone();
                (hash, version.into_version(&project_id))
            })
            .collect())
    }

    /// Resolves the newest compatible version of `project_id`.
    ///
    /// # Errors
    ///
    /// Propagates network/parse failures and returns
    /// [`ErrorKind::NotFound`] when no version matches the filters.
    pub async fn modrinth_latest_version(
        &self,
        project_id: &str,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<ContentVersion> {
        self.modrinth_versions(project_id, game_version, loader)
            .await?
            .into_iter()
            .next()
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.content.version_missing",
                    format!("{project_id} has no compatible version"),
                )
            })
    }

    /// Resolves an explicit version id, falling back to the latest compatible
    /// version when `version_id` is empty.
    ///
    /// # Errors
    ///
    /// Propagates network/parse failures and compatibility errors.
    pub async fn modrinth_resolve_version(
        &self,
        project_id: &str,
        version_id: Option<&str>,
        game_version: Option<&str>,
        loader: Option<&str>,
    ) -> Result<ContentVersion> {
        match version_id.map(str::trim).filter(|value| !value.is_empty()) {
            Some(version_id) => {
                let version = self.modrinth_version(version_id).await?;
                if version.project_id != project_id.trim() {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.content.version_mismatch",
                        format!(
                            "version {version_id} belongs to {}, not {project_id}",
                            version.project_id
                        ),
                    ));
                }
                if let Some(game_version) = game_version
                    && !version.game_versions.iter().any(|it| it == game_version)
                {
                    return Err(Error::with_detail(
                        ErrorKind::Conflict,
                        "error.content.version_incompatible",
                        format!("{version_id} does not support Minecraft {game_version}"),
                    ));
                }
                if let (Some(loader), false) = (loader, version.loaders.is_empty()) {
                    let allowed = modrinth_loaders(Some(loader));
                    if !allowed.is_empty()
                        && !version
                            .loaders
                            .iter()
                            .any(|item| allowed.iter().any(|allowed| allowed == item))
                    {
                        return Err(Error::with_detail(
                            ErrorKind::Conflict,
                            "error.content.version_incompatible",
                            format!("{version_id} does not support {loader}"),
                        ));
                    }
                }
                Ok(version)
            }
            None => {
                self.modrinth_latest_version(project_id, game_version, loader)
                    .await
            }
        }
    }
}

fn endpoint(path: &str) -> Result<Url> {
    Url::parse(&format!("{MODRINTH_API}{path}")).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.content.request",
            format!("{MODRINTH_API}{path}: {error}"),
        )
    })
}

fn modrinth_facets(query: &SearchQuery) -> String {
    let mut facets: Vec<Vec<String>> = vec![vec![format!(
        "project_type:{}",
        query.kind.modrinth_project_type()
    )]];

    if let Some(game_version) = query.game_version.as_deref().filter(|it| !it.is_empty()) {
        facets.push(vec![format!("versions:{game_version}")]);
    }

    if query.kind.loader_specific()
        && let Some(loader) = query.loader.as_deref()
    {
        let categories = modrinth_loaders(Some(loader))
            .into_iter()
            .map(|loader| format!("categories:{loader}"))
            .collect::<Vec<_>>();
        if !categories.is_empty() {
            facets.push(categories);
        }
    }

    serde_json::to_string(&facets).unwrap_or_default()
}

#[derive(Debug, Deserialize)]
struct RawSearch {
    hits: Vec<RawHit>,
    #[serde(default)]
    total_hits: u64,
    #[serde(default)]
    offset: u32,
    #[serde(default)]
    limit: u32,
}

#[derive(Debug, Deserialize)]
struct RawHit {
    project_id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    follows: u64,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    date_modified: Option<String>,
}

impl RawHit {
    fn into_project(self) -> Project {
        Project {
            source: ContentSource::Modrinth,
            project_id: self.project_id,
            slug: self.slug,
            title: if self.title.trim().is_empty() {
                "Unknown project".to_string()
            } else {
                self.title
            },
            description: self.description.trim().to_string(),
            downloads: self.downloads,
            follows: self.follows,
            icon_url: self.icon_url,
            categories: self.categories,
            updated: self.date_modified,
        }
    }
}

#[derive(Debug, Deserialize)]
struct RawProject {
    id: String,
    #[serde(default)]
    slug: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    downloads: u64,
    #[serde(default)]
    followers: u64,
    #[serde(default)]
    icon_url: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    updated: Option<String>,
}

impl RawProject {
    fn into_project(self) -> Project {
        Project {
            source: ContentSource::Modrinth,
            project_id: self.id,
            slug: self.slug,
            title: if self.title.trim().is_empty() {
                "Unknown project".to_string()
            } else {
                self.title
            },
            description: self.description.trim().to_string(),
            downloads: self.downloads,
            follows: self.followers,
            icon_url: self.icon_url,
            categories: self.categories,
            updated: self.updated,
        }
    }
}

#[derive(Debug, Deserialize)]
struct RawVersion {
    id: String,
    #[serde(default)]
    project_id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    version_number: String,
    #[serde(default)]
    game_versions: Vec<String>,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    downloads: u64,
    #[serde(default = "default_version_type")]
    version_type: String,
    #[serde(default)]
    date_published: Option<String>,
    #[serde(default)]
    files: Vec<RawFile>,
    #[serde(default)]
    dependencies: Vec<RawDependency>,
}

impl RawVersion {
    fn matches(&self, game_version: Option<&str>, loader: Option<&str>) -> bool {
        if let Some(game_version) = game_version.filter(|value| !value.is_empty())
            && !self.game_versions.is_empty()
            && !self.game_versions.iter().any(|item| item == game_version)
        {
            return false;
        }
        if let Some(loader) = loader.filter(|value| !value.is_empty()) {
            let allowed = modrinth_loaders(Some(loader));
            if !allowed.is_empty()
                && !self.loaders.is_empty()
                && !self
                    .loaders
                    .iter()
                    .any(|item| allowed.iter().any(|allowed| allowed == item))
            {
                return false;
            }
        }
        true
    }

    fn into_version(self, fallback_project_id: &str) -> ContentVersion {
        let project_id = if self.project_id.trim().is_empty() {
            fallback_project_id.to_string()
        } else {
            self.project_id
        };
        let file = self
            .files
            .iter()
            .find(|file| file.primary)
            .or_else(|| self.files.first())
            .map(RawFile::to_content_file);
        ContentVersion {
            source: ContentSource::Modrinth,
            project_id,
            version_id: self.id,
            name: self.name,
            version_number: self.version_number,
            game_versions: self.game_versions,
            loaders: self.loaders,
            downloads: self.downloads,
            version_type: version_type(&self.version_type),
            published: self.date_published,
            file,
            dependencies: self
                .dependencies
                .into_iter()
                .map(|dependency| ContentDependency {
                    project_id: dependency.project_id,
                    version_id: dependency.version_id,
                    kind: dependency.dependency_type,
                    name: None,
                })
                .collect(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct RawFile {
    #[serde(default)]
    filename: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    size: u64,
    #[serde(default)]
    hashes: RawHashes,
    #[serde(default)]
    primary: bool,
}

impl RawFile {
    fn to_content_file(&self) -> ContentFile {
        ContentFile {
            file_name: self.filename.clone(),
            url: self.url.clone(),
            size: self.size,
            sha1: self.hashes.sha1.clone(),
            sha512: self.hashes.sha512.clone(),
        }
    }
}

#[derive(Debug, Default, Deserialize)]
struct RawHashes {
    #[serde(default)]
    sha1: Option<String>,
    #[serde(default)]
    sha512: Option<String>,
}

#[derive(Debug, Deserialize)]
struct RawDependency {
    #[serde(default)]
    version_id: Option<String>,
    #[serde(default)]
    project_id: Option<String>,
    #[serde(default)]
    dependency_type: String,
}

const fn default_version_type() -> String {
    String::new()
}

fn version_type(value: &str) -> VersionType {
    match value.to_ascii_lowercase().as_str() {
        "beta" => VersionType::Beta,
        "alpha" => VersionType::Alpha,
        _ => VersionType::Release,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn facets_and_filters_match_the_documented_shape() {
        let query = SearchQuery::new(crate::ContentKind::Mod)
            .with_query("sodium")
            .with_game_version(Some("1.21.4".into()))
            .with_loader(Some("quilt".into()));
        let facets = modrinth_facets(&query);
        assert!(facets.contains("project_type:mod"));
        assert!(facets.contains("versions:1.21.4"));
        assert!(facets.contains("categories:quilt"));
        assert!(facets.contains("categories:fabric"));
    }

    #[test]
    fn versions_are_filtered_client_side_too() {
        let raw = RawVersion {
            id: "v1".into(),
            project_id: "p1".into(),
            name: "Sodium".into(),
            version_number: "1.0".into(),
            game_versions: vec!["1.20.1".into()],
            loaders: vec!["fabric".into()],
            downloads: 1,
            version_type: "release".into(),
            date_published: None,
            files: vec![RawFile {
                filename: "sodium.jar".into(),
                url: "https://cdn.modrinth.com/x.jar".into(),
                size: 1,
                hashes: RawHashes::default(),
                primary: true,
            }],
            dependencies: Vec::new(),
        };
        assert!(!raw.matches(Some("1.21.4"), Some("fabric")));
        assert!(raw.matches(Some("1.20.1"), Some("fabric")));
        assert!(raw.matches(None, Some("quilt")) || !raw.loaders.contains(&"fabric".to_string()));
    }
}
