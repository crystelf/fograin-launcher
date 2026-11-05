//! Fetching and caching version metadata (§7.1 steps 1–3).
//!
//! Install uses the network; launch must work offline. Both go through the
//! same [`VersionResolver`]: `resolve_remote` saves every raw `version.json`
//! it fetches, and `resolve_local` only reads those files.

use std::{path::PathBuf, sync::Arc};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{Downloader, MirrorTable, Region};

use crate::layout::{Layout, write_atomic};
use crate::version::{ResolvedVersion, VersionJson, VersionManifest, resolve_chain};

/// Official version manifest (§7.1 step 1).
pub const VERSION_MANIFEST_URL: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

const MAX_INHERITANCE_DEPTH: usize = 8;

/// Fetches manifests and version chains, writing raw JSON under
/// `<root>/shared/versions/<id>/<id>.json`.
#[derive(Debug, Clone)]
pub struct VersionResolver {
    downloader: Arc<Downloader>,
    layout: Layout,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
    manifest_url: String,
}

impl VersionResolver {
    #[must_use]
    pub fn new(
        downloader: Arc<Downloader>,
        layout: Layout,
        table: MirrorTable,
        source: DownloadSource,
        region: Region,
    ) -> Self {
        Self {
            downloader,
            layout,
            table,
            source,
            region,
            manifest_url: VERSION_MANIFEST_URL.to_string(),
        }
    }

    /// Overrides the manifest URL for tests and mirrors.
    #[must_use]
    pub fn with_manifest_url(mut self, url: impl Into<String>) -> Self {
        self.manifest_url = url.into();
        self
    }

    /// Loads (and caches to `<cache>/version_manifest_v2.json`) the manifest.
    ///
    /// # Errors
    ///
    /// Returns the network or parse error.
    pub async fn manifest(&self, refresh: bool) -> Result<VersionManifest> {
        let cache_path = self.layout.cache().join("version_manifest_v2.json");
        if !refresh
            && let Ok(bytes) = std::fs::read(&cache_path)
            && let Ok(manifest) = VersionManifest::parse(&bytes)
        {
            return Ok(manifest);
        }

        let candidates = self
            .table
            .candidates(&self.manifest_url, self.source, self.region);
        let fetched = self
            .downloader
            .fetch_bytes(candidates, &self.manifest_url)
            .await?;
        let manifest = VersionManifest::parse(&fetched.bytes)?;
        write_atomic(&cache_path, &fetched.bytes)?;
        Ok(manifest)
    }

    /// Resolves a version id from the network, saving each raw JSON.
    ///
    /// An installed version is preferred, so `install` on an already-installed
    /// version does not touch the network.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the id is absent from the manifest,
    /// network errors from [`Downloader::fetch_bytes`] and parse errors for
    /// malformed version JSON.
    pub async fn resolve_remote(&self, id: &str) -> Result<ResolvedVersion> {
        self.resolve_remote_inner(id, true, false).await
    }

    /// Like [`Self::resolve_remote`], but ignores local files and refreshes the
    /// manifest cache. Used by `fg-cli install --refresh`.
    ///
    /// # Errors
    ///
    /// Propagates manifest/version fetch and parse errors.
    pub async fn resolve_remote_fresh(&self, id: &str) -> Result<ResolvedVersion> {
        self.resolve_remote_inner(id, false, true).await
    }

    async fn resolve_remote_inner(
        &self,
        id: &str,
        prefer_local: bool,
        refresh_manifest: bool,
    ) -> Result<ResolvedVersion> {
        crate::layout::validate_id(id)?;

        if prefer_local && let Ok(local) = self.resolve_local(id) {
            return Ok(local);
        }

        let manifest = self.manifest(refresh_manifest).await?;
        let mut chain = Vec::new();
        let mut current_id = id.to_string();
        let mut current_url = manifest
            .find(&current_id)
            .map(|version| version.url.clone())
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.version.unknown",
                    format!("{current_id} is not in the manifest"),
                )
            })?;

        for _ in 0..MAX_INHERITANCE_DEPTH {
            let bytes = self.fetch_version_bytes(&current_url).await?;
            let version = VersionJson::parse(&bytes)?;
            let next = version.inherits_from.clone();
            self.save_version(&version.id, &bytes)?;
            chain.push(version);
            let Some(parent_id) = next else {
                return resolve_chain(&chain);
            };
            let parent = manifest.find(&parent_id).ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.version.unknown_parent",
                    format!("{current_id} inheritsFrom {parent_id}, which is not in the manifest"),
                )
            })?;
            current_url = parent.url.clone();
            current_id = parent_id;
        }

        Err(Error::with_detail(
            ErrorKind::Parse,
            "error.version.inherits_depth",
            format!("{id} exceeds {MAX_INHERITANCE_DEPTH} parents"),
        ))
    }

    /// Resolves an installed version from local JSON only.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the version or one of its parents
    /// is not installed.
    pub fn resolve_local(&self, id: &str) -> Result<ResolvedVersion> {
        crate::layout::validate_id(id)?;
        let mut chain = Vec::new();
        let mut current_id = id.to_string();

        for _ in 0..MAX_INHERITANCE_DEPTH {
            let path = self.layout.version_json(&current_id);
            let bytes = std::fs::read(&path).map_err(|error| {
                if error.kind() == std::io::ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.version.not_installed",
                        format!("{}", path.display()),
                    )
                } else {
                    Error::with_detail(ErrorKind::Io, "error.io.read", error.to_string())
                }
            })?;
            let version = VersionJson::parse(&bytes)?;
            let next = version.inherits_from.clone();
            chain.push(version);
            let Some(parent_id) = next else {
                return resolve_chain(&chain);
            };
            current_id = parent_id;
        }

        Err(Error::with_detail(
            ErrorKind::Parse,
            "error.version.inherits_depth",
            format!("{id} exceeds {MAX_INHERITANCE_DEPTH} parents"),
        ))
    }

    /// Path where a raw version JSON is stored.
    #[must_use]
    pub fn version_path(&self, id: &str) -> PathBuf {
        self.layout.version_json(id)
    }

    async fn fetch_version_bytes(&self, url: &str) -> Result<bytes::Bytes> {
        let candidates = self.table.candidates(url, self.source, self.region);
        let fetched = self.downloader.fetch_bytes(candidates, url).await?;
        Ok(fetched.bytes)
    }

    fn save_version(&self, id: &str, bytes: &[u8]) -> Result<()> {
        let path = self.layout.version_json(id);
        // A cached version json is immutable; avoid churning mtimes on every
        // install, but always write when the file is missing or damaged.
        if let Ok(existing) = std::fs::read(&path)
            && existing == bytes
        {
            return Ok(());
        }
        write_atomic(&path, bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::version::VersionJson;

    fn layout() -> (tempfile::TempDir, Layout) {
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        layout.ensure_base_dirs().expect("dirs");
        (directory, layout)
    }

    fn write_version(layout: &Layout, id: &str, json: &str) {
        let path = layout.version_json(id);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("dir");
        std::fs::write(path, json).expect("write");
    }

    #[test]
    fn resolves_an_installed_version_without_network() {
        let (_directory, layout) = layout();
        write_version(
            &layout,
            "child",
            r#"{"id":"child","inheritsFrom":"1.21.4","mainClass":"child.Main"}"#,
        );
        write_version(
            &layout,
            "1.21.4",
            include_str!("../tests/fixtures/versions/1.21.4.json"),
        );

        let downloader = Arc::new(Downloader::new(layout.root()).expect("downloader"));
        let resolver = VersionResolver::new(
            downloader,
            layout,
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        );
        let resolved = resolver.resolve_local("child").expect("resolve");
        assert_eq!(resolved.id, "child");
        assert_eq!(resolved.base_id, "1.21.4");
        assert_eq!(resolved.parent_id.as_deref(), Some("1.21.4"));
        assert_eq!(resolved.main_class().expect("main"), "child.Main");
    }

    #[test]
    fn local_resolution_reports_a_missing_parent() {
        let (_directory, layout) = layout();
        write_version(
            &layout,
            "child",
            r#"{"id":"child","inheritsFrom":"missing"}"#,
        );
        let downloader = Arc::new(Downloader::new(layout.root()).expect("downloader"));
        let resolver = VersionResolver::new(
            downloader,
            layout,
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        );
        let error = resolver.resolve_local("child").expect_err("missing");
        assert_eq!(error.kind, ErrorKind::NotFound);
    }

    #[test]
    fn raw_version_json_round_trips_through_the_parser() {
        let text = include_str!("../tests/fixtures/versions/1.20.1.json");
        let version = VersionJson::parse(text.as_bytes()).expect("parse");
        assert_eq!(version.id, "1.20.1");
    }
}
