//! Loader installer entry points.
//!
//! [`LoaderPlanner`] turns a loader selection into a serializable
//! [`LoaderPlan`]. Network access is limited to metadata, profiles and the
//! installer archive; the actual install phase only consumes local files.

use std::path::{Path, PathBuf};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{Downloader, MirrorTable, Region};

use crate::kind::LoaderKind;
use crate::meta::LoaderVersion;
use crate::plan::{LOADER_PLAN_SCHEMA_VERSION, LoaderFile, LoaderFilePurpose, LoaderPlan};

/// Paths the loader planner needs from the launcher data root.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoaderPaths {
    pub root: PathBuf,
    pub libraries: PathBuf,
    pub cache: PathBuf,
}

impl LoaderPaths {
    #[must_use]
    pub fn new(
        root: impl Into<PathBuf>,
        libraries: impl Into<PathBuf>,
        cache: impl Into<PathBuf>,
    ) -> Self {
        Self {
            root: root.into(),
            libraries: libraries.into(),
            cache: cache.into(),
        }
    }

    /// Path relative to the data root.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Internal`] when `path` is outside the root.
    pub fn relative(&self, path: &Path) -> Result<PathBuf> {
        path.strip_prefix(&self.root)
            .map(Path::to_path_buf)
            .map_err(|_| {
                Error::with_detail(
                    ErrorKind::Internal,
                    "error.loader.path_outside_root",
                    path.display().to_string(),
                )
            })
    }

    /// Absolute path for a plan-relative path.
    #[must_use]
    pub fn absolute(&self, path: &Path) -> PathBuf {
        self.root.join(path)
    }

    /// `shared/libraries/<maven path>` relative to the data root.
    #[must_use]
    pub fn library_path(&self, maven_path: &str) -> PathBuf {
        let absolute = self.libraries.join(maven_path);
        self.relative(&absolute)
            .unwrap_or_else(|_| PathBuf::from("shared/libraries").join(maven_path))
    }
}

/// A mapping download from the vanilla version json.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteArtifact {
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

/// One loader install request.
#[derive(Debug, Clone)]
pub struct LoaderRequest<'a> {
    pub kind: LoaderKind,
    pub loader_version: &'a str,
    pub minecraft: &'a str,
    pub java_major: u32,
    /// Absolute path to the vanilla client jar used by Forge/OptiFine.
    pub client_jar: PathBuf,
    pub client_mappings: Option<RemoteArtifact>,
}

/// Fetches metadata and builds loader install plans.
pub struct LoaderPlanner<'a> {
    pub downloader: &'a Downloader,
    pub table: &'a MirrorTable,
    pub source: DownloadSource,
    pub region: Region,
    pub paths: LoaderPaths,
}

impl<'a> LoaderPlanner<'a> {
    #[must_use]
    pub fn new(
        downloader: &'a Downloader,
        table: &'a MirrorTable,
        source: DownloadSource,
        region: Region,
        paths: LoaderPaths,
    ) -> Self {
        Self {
            downloader,
            table,
            source,
            region,
            paths,
        }
    }

    /// Builds a plan for `request`.
    ///
    /// # Errors
    ///
    /// Propagates metadata/installer network errors, unsupported Minecraft
    /// versions and malformed installer archives.
    pub async fn plan(&self, request: &LoaderRequest<'_>) -> Result<LoaderPlan> {
        if request.loader_version.trim().is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.loader.version_missing",
            ));
        }
        if !crate::kind::is_safe_version_id(request.loader_version.trim()) {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.loader.version_invalid",
                request.loader_version.to_string(),
            ));
        }
        let plan = match request.kind {
            LoaderKind::Fabric | LoaderKind::Quilt => crate::fabric::plan(self, request).await?,
            LoaderKind::Forge | LoaderKind::NeoForge => crate::forge::plan(self, request).await?,
            LoaderKind::OptiFine => crate::optifine::plan(self, request).await?,
        };
        if !crate::kind::is_safe_version_id(&plan.version_id) {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.loader.version_invalid",
                plan.version_id,
            ));
        }
        Ok(plan)
    }

    /// Picks the newest stable version, falling back to the first entry.
    ///
    /// # Errors
    ///
    /// Propagates [`crate::meta::versions`] errors.
    pub async fn latest_version(&self, kind: LoaderKind, minecraft: &str) -> Result<LoaderVersion> {
        let mut versions = crate::meta::versions(
            self.downloader,
            self.table,
            self.source,
            self.region,
            crate::meta::VersionListQuery {
                kind,
                minecraft,
                limit: 50,
            },
        )
        .await?;
        if versions.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.loader.unsupported_minecraft",
                format!("{kind} has no builds for Minecraft {minecraft}"),
            ));
        }
        let index = versions
            .iter()
            .position(|version| version.stable)
            .unwrap_or(0);
        Ok(versions.remove(index))
    }

    /// Downloads one URL into the loader cache and returns its relative path.
    ///
    /// # Errors
    ///
    /// Propagates network and I/O errors.
    pub async fn fetch_cached(&self, url: &str, cache_key: &str) -> Result<Vec<u8>> {
        let candidates = self.table.candidates(url, self.source, self.region);
        let fetched = self
            .downloader
            .fetch_bytes(candidates, &format!("loaders/{cache_key}"))
            .await?;
        Ok(fetched.bytes.to_vec())
    }

    /// Adds a loader file, deduplicating by target path.
    pub(crate) fn push_file(plan: &mut LoaderPlan, file: LoaderFile) {
        if let Some(existing) = plan.files.iter_mut().find(|item| item.path == file.path) {
            if existing.url.is_empty() && !file.url.is_empty() {
                *existing = file;
            }
            return;
        }
        plan.files.push(file);
    }

    /// Builds a library download entry from a concrete maven path.
    pub(crate) fn library_file(
        &self,
        maven_path: &str,
        url: String,
        sha1: Option<String>,
        size: Option<u64>,
    ) -> LoaderFile {
        LoaderFile {
            purpose: LoaderFilePurpose::Library,
            path: self.paths.library_path(maven_path),
            url,
            sha1,
            size,
        }
    }

    /// Relative path for the loader installer cache slot.
    #[must_use]
    pub(crate) fn installer_path(&self, kind: LoaderKind, minecraft: &str, file: &str) -> PathBuf {
        let safe = kind.as_str();
        self.paths
            .cache
            .join("loaders")
            .join(safe)
            .join(minecraft)
            .join(file)
            .strip_prefix(&self.paths.root)
            .map(Path::to_path_buf)
            .unwrap_or_else(|_| PathBuf::from(format!("cache/loaders/{safe}/{minecraft}/{file}")))
    }

    /// Base id for a plan from its request.
    pub(crate) fn plan_header(
        &self,
        request: &LoaderRequest<'_>,
        version_id: String,
        main_class: String,
        version_json: String,
    ) -> LoaderPlan {
        self.plan_header_from_parts(
            request.kind,
            request.loader_version,
            request.minecraft,
            request.java_major,
            version_id,
            main_class,
            version_json,
        )
    }

    /// Header builder used by planners that already hold the individual fields.
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn plan_header_from_parts(
        &self,
        kind: LoaderKind,
        loader_version: &str,
        minecraft: &str,
        java_major: u32,
        version_id: String,
        main_class: String,
        version_json: String,
    ) -> LoaderPlan {
        LoaderPlan {
            schema_version: LOADER_PLAN_SCHEMA_VERSION,
            kind,
            loader_version: loader_version.to_string(),
            version_id,
            base_id: minecraft.to_string(),
            main_class,
            java_major,
            version_json,
            files: Vec::new(),
            steps: Vec::new(),
        }
    }
}

/// Adds a string-keyed file plan entry.
pub(crate) fn file_entry(
    kind: LoaderFilePurpose,
    path: PathBuf,
    url: impl Into<String>,
    sha1: Option<String>,
    size: Option<u64>,
) -> LoaderFile {
    LoaderFile {
        purpose: kind,
        path,
        url: url.into(),
        sha1,
        size,
    }
}

/// Joins a base URL and a relative maven path without doubling slashes.
#[must_use]
pub(crate) fn join_url(base: &str, path: &str) -> String {
    format!("{}/{}", base.trim_end_matches('/'), path)
}

/// Validates that a derived version id can be used as a directory name.
pub(crate) fn checked_version_id(candidate: &str, fallback: &str) -> String {
    if crate::kind::is_safe_version_id(candidate) {
        candidate.to_string()
    } else {
        fallback.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relative_paths_round_trip() {
        let paths = LoaderPaths::new("/data", "/data/shared/libraries", "/data/cache");
        let relative = paths
            .relative(Path::new("/data/shared/libraries/a/b.jar"))
            .expect("relative");
        assert_eq!(relative, PathBuf::from("shared/libraries/a/b.jar"));
        assert_eq!(
            paths.library_path("a/b.jar"),
            PathBuf::from("shared/libraries/a/b.jar")
        );
        assert_eq!(
            paths.absolute(&relative),
            PathBuf::from("/data/shared/libraries/a/b.jar")
        );
    }

    #[test]
    fn unsafe_version_ids_fall_back() {
        assert_eq!(
            checked_version_id("fabric-loader-1", "fallback"),
            "fabric-loader-1"
        );
        assert_eq!(checked_version_id("../escape", "fallback"), "fallback");
    }
}
