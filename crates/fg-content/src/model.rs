//! Shared wire models for Modrinth and CurseForge searches.

use serde::{Deserialize, Serialize};

use fg_core::{Error, ErrorKind, Result};

/// Where a project/version comes from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentSource {
    Modrinth,
    Curseforge,
}

impl ContentSource {
    /// Parses the wire value.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unknown source.
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "modrinth" => Ok(Self::Modrinth),
            "curseforge" | "curse" => Ok(Self::Curseforge),
            other => Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.content.source",
                format!("unknown content source {other:?}"),
            )),
        }
    }

    /// Stable wire/JSON value.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Modrinth => "modrinth",
            Self::Curseforge => "curseforge",
        }
    }

    /// Human-readable name used by CLI output.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Modrinth => "Modrinth",
            Self::Curseforge => "CurseForge",
        }
    }
}

/// One project row returned by a search.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Project {
    pub source: ContentSource,
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub downloads: u64,
    pub follows: u64,
    pub icon_url: Option<String>,
    pub categories: Vec<String>,
    /// ISO-8601 timestamp as returned by the API.
    pub updated: Option<String>,
}

/// One project version.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentVersion {
    pub source: ContentSource,
    pub project_id: String,
    pub version_id: String,
    pub name: String,
    pub version_number: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub downloads: u64,
    pub version_type: VersionType,
    pub published: Option<String>,
    /// Primary file, or the first file when no primary flag exists.
    pub file: Option<ContentFile>,
    pub dependencies: Vec<ContentDependency>,
}

/// Release channel of a version.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum VersionType {
    Release,
    Beta,
    Alpha,
}

impl VersionType {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Release => "release",
            Self::Beta => "beta",
            Self::Alpha => "alpha",
        }
    }
}

/// Downloadable file of a version.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentFile {
    pub file_name: String,
    pub url: String,
    pub size: u64,
    pub sha1: Option<String>,
    pub sha512: Option<String>,
}

/// Required or optional dependency of a version.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentDependency {
    pub project_id: Option<String>,
    pub version_id: Option<String>,
    /// `required`, `optional`, `embedded` or `incompatible`.
    pub kind: String,
    /// Dependency title when the API already knows it.
    pub name: Option<String>,
}

impl ContentDependency {
    #[must_use]
    pub fn is_required(&self) -> bool {
        self.kind == "required"
    }
}

/// One page of search results.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContentSearchPage {
    pub source: ContentSource,
    pub projects: Vec<Project>,
    pub total: u64,
    pub offset: u32,
    pub limit: u32,
}

/// What a caller wants to search for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchQuery {
    pub query: String,
    pub kind: crate::ContentKind,
    pub game_version: Option<String>,
    pub loader: Option<String>,
    pub offset: u32,
    pub limit: u32,
}

impl SearchQuery {
    #[must_use]
    pub fn new(kind: crate::ContentKind) -> Self {
        Self {
            query: String::new(),
            kind,
            game_version: None,
            loader: None,
            offset: 0,
            limit: 20,
        }
    }

    #[must_use]
    pub fn with_query(mut self, query: impl Into<String>) -> Self {
        self.query = query.into();
        self
    }

    #[must_use]
    pub fn with_game_version(mut self, version: Option<String>) -> Self {
        self.game_version = version;
        self
    }

    #[must_use]
    pub fn with_loader(mut self, loader: Option<String>) -> Self {
        self.loader = loader;
        self
    }

    #[must_use]
    pub fn with_page(mut self, offset: u32, limit: u32) -> Self {
        self.offset = offset;
        self.limit = limit.clamp(1, 50);
        self
    }
}

/// Normalizes a loader name to the lowercase API spelling.
#[must_use]
pub fn normalize_loader(loader: &str) -> Option<String> {
    match loader.trim().to_ascii_lowercase().as_str() {
        "" | "vanilla" | "optifine" => None,
        "fabric" | "quilt" | "forge" | "neoforge" => Some(loader.trim().to_ascii_lowercase()),
        other => Some(other.to_string()),
    }
}

/// Modrinth loader names for the instance loader; Quilt can run Fabric mods, so
/// both names are offered as facets.
#[must_use]
pub fn modrinth_loaders(loader: Option<&str>) -> Vec<String> {
    match loader {
        Some("quilt") => vec!["quilt".to_string(), "fabric".to_string()],
        Some("fabric") => vec!["fabric".to_string()],
        Some("forge") => vec!["forge".to_string()],
        Some("neoforge") => vec!["neoforge".to_string()],
        _ => Vec::new(),
    }
}

/// CurseForge `modLoaderType` for the instance loader.
#[must_use]
pub fn curseforge_loader_type(loader: Option<&str>) -> Option<u32> {
    match loader {
        Some("forge") => Some(1),
        Some("fabric") => Some(4),
        Some("quilt") => Some(5),
        Some("neoforge") => Some(6),
        _ => None,
    }
}

/// CurseForge file `releaseType`: 1 = release, 2 = beta, 3 = alpha.
#[must_use]
pub const fn version_type_from_curseforge(value: u8) -> VersionType {
    match value {
        1 => VersionType::Release,
        2 => VersionType::Beta,
        _ => VersionType::Alpha,
    }
}

/// Parses a CurseForge dependency `relationType`.
#[must_use]
pub const fn dependency_kind_from_curseforge(value: u8) -> &'static str {
    match value {
        1 => "embedded",
        2 => "optional",
        3 => "required",
        4 => "tool",
        5 => "incompatible",
        _ => "unknown",
    }
}

/// Ensures a project id is safe to splice into an API path.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for empty ids or path separators.
pub fn validate_project_id(value: &str) -> Result<()> {
    let trimmed = value.trim();
    if trimmed.is_empty()
        || trimmed == "."
        || trimmed == ".."
        || trimmed.contains('/')
        || trimmed.contains('\\')
        || trimmed.contains('?')
        || trimmed.contains('#')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.content.project_id",
            format!("unsafe project id {value:?}"),
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_aliases_and_labels() {
        assert_eq!(
            ContentSource::parse("CurseForge").expect("source"),
            ContentSource::Curseforge
        );
        assert_eq!(ContentSource::Modrinth.label(), "Modrinth");
        assert!(ContentSource::parse("unknown").is_err());
    }

    #[test]
    fn loader_facets_follow_the_loader() {
        assert_eq!(modrinth_loaders(Some("quilt")), vec!["quilt", "fabric"]);
        assert_eq!(modrinth_loaders(Some("fabric")), vec!["fabric"]);
        assert!(modrinth_loaders(Some("vanilla")).is_empty());
        assert_eq!(curseforge_loader_type(Some("neoforge")), Some(6));
        assert_eq!(curseforge_loader_type(Some("vanilla")), None);
    }

    #[test]
    fn curseforge_mappings_are_explicit() {
        assert_eq!(version_type_from_curseforge(1), VersionType::Release);
        assert_eq!(version_type_from_curseforge(3), VersionType::Alpha);
        assert_eq!(dependency_kind_from_curseforge(3), "required");
    }

    #[test]
    fn project_ids_cannot_escape_the_api_path() {
        assert!(validate_project_id("AANobbMI").is_ok());
        assert!(validate_project_id("sodium").is_ok());
        assert!(validate_project_id("../x").is_err());
        assert!(validate_project_id("").is_err());
        assert!(validate_project_id("a/b").is_err());
    }
}
