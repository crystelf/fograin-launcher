//! Content-addressed and HTTP conditional caches (§5.1, §6.2).

use std::{
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

use crate::{
    fsutil,
    hash::{HashAlgorithm, hash_bytes, hash_file},
};

/// `cache/sha1/<aa>/<hash>` storage shared by every downloaded file.
#[derive(Debug, Clone)]
pub struct ContentCache {
    root: PathBuf,
}

impl ContentCache {
    #[must_use]
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    /// Cache path for a SHA-1 digest.
    ///
    /// # Panics
    ///
    /// Panics when `sha1` is not a 40-character hexadecimal digest; callers
    /// validate through [`is_sha1`] first.
    #[must_use]
    pub fn path(&self, sha1: &str) -> PathBuf {
        let digest = sha1.to_ascii_lowercase();
        self.root.join("sha1").join(&digest[..2]).join(digest)
    }

    /// Returns a verified cache entry, deleting corrupt files.
    pub async fn lookup(&self, sha1: &str) -> Option<PathBuf> {
        if !is_sha1(sha1) {
            return None;
        }

        let path = self.path(sha1);
        if !tokio::fs::try_exists(&path).await.unwrap_or(false) {
            return None;
        }

        match hash_file(&path, HashAlgorithm::Sha1).await {
            Ok(actual) if actual.eq_ignore_ascii_case(sha1) => Some(path),
            Ok(actual) => {
                tracing::warn!(
                    path = %path.display(),
                    expected = sha1,
                    actual,
                    "discarding corrupt cache entry"
                );
                let _ = tokio::fs::remove_file(&path).await;
                None
            }
            Err(error) => {
                tracing::warn!(path = %path.display(), detail = %error, "cannot verify cache entry");
                None
            }
        }
    }

    /// Adds `source` to the cache. Returns a path that stays valid after the
    /// caller renames `source`.
    pub async fn insert(&self, sha1: &str, source: &Path) -> Result<PathBuf> {
        if !is_sha1(sha1) {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.net.cache_key",
                format!("not a SHA-1 digest: {sha1}"),
            ));
        }

        let target = self.path(sha1);
        if tokio::fs::try_exists(&target).await.unwrap_or(false) {
            return Ok(target);
        }

        fsutil::copy_or_link(source, &target)
            .await
            .map_err(|error| io_error(&target, &error))?;
        Ok(target)
    }

    /// Copies (or hard-links) a verified cache entry to `dest`.
    ///
    /// Returns `true` on a cache hit, `false` when the entry is absent.
    pub async fn materialize(&self, sha1: &str, dest: &Path) -> Result<bool> {
        let Some(cached) = self.lookup(sha1).await else {
            return Ok(false);
        };

        fsutil::copy_or_link(&cached, dest)
            .await
            .map_err(|error| io_error(dest, &error))?;
        Ok(true)
    }
}

/// Metadata stored next to an HTTP conditional cache body.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CachedMeta {
    /// Canonical cache key (usually the official, unmirrored URL).
    pub key: String,
    /// URL that produced this entry.
    pub url: String,
    #[serde(default)]
    pub etag: Option<String>,
    #[serde(default)]
    pub last_modified: Option<String>,
    #[serde(default)]
    pub content_type: Option<String>,
    /// Unix timestamp in seconds.
    pub fetched_at: u64,
    pub len: u64,
}

/// `cache/http/<aa>/<hash>` conditional cache for manifests and API responses.
#[derive(Debug, Clone)]
pub struct HttpCache {
    root: PathBuf,
}

impl HttpCache {
    #[must_use]
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// Stable file name for a cache key (SHA-1 of the canonical URL).
    #[must_use]
    pub fn key_for(key: &str) -> String {
        hash_bytes(key.as_bytes(), HashAlgorithm::Sha1)
    }

    #[must_use]
    pub fn body_path(&self, key: &str) -> PathBuf {
        let digest = Self::key_for(key);
        self.root
            .join("http")
            .join(&digest[..2])
            .join(format!("{digest}.body"))
    }

    #[must_use]
    pub fn meta_path(&self, key: &str) -> PathBuf {
        let digest = Self::key_for(key);
        self.root
            .join("http")
            .join(&digest[..2])
            .join(format!("{digest}.json"))
    }

    /// Loads a cached entry; both metadata and body must be readable.
    pub async fn get(&self, key: &str) -> Option<(CachedMeta, PathBuf)> {
        let meta_path = self.meta_path(key);
        let body_path = self.body_path(key);

        let text = tokio::fs::read_to_string(&meta_path).await.ok()?;
        let meta: CachedMeta = serde_json::from_str(&text).ok()?;
        if !tokio::fs::try_exists(&body_path).await.unwrap_or(false) {
            return None;
        }
        Some((meta, body_path))
    }

    /// Stores a body and its validators. The body is written first so a crash
    /// can never leave metadata pointing at a missing file.
    pub async fn put(&self, key: &str, meta: &CachedMeta, body: &[u8]) -> Result<()> {
        let body_path = self.body_path(key);
        let meta_path = self.meta_path(key);

        fsutil::write_atomic(&body_path, body)
            .await
            .map_err(|error| io_error(&body_path, &error))?;

        let encoded = serde_json::to_vec_pretty(meta).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.net.cache_meta",
                error.to_string(),
            )
        })?;
        fsutil::write_atomic(&meta_path, &encoded)
            .await
            .map_err(|error| io_error(&meta_path, &error))
    }

    /// Removes both files of a cached entry.
    pub async fn remove(&self, key: &str) {
        let _ = fsutil::remove_if_exists(&self.meta_path(key)).await;
        let _ = fsutil::remove_if_exists(&self.body_path(key)).await;
    }
}

#[must_use]
pub(crate) fn is_sha1(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|byte| byte.is_ascii_hexdigit())
}

pub(crate) fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_secs())
}

pub(crate) fn io_error(path: &Path, error: &std::io::Error) -> Error {
    Error::with_detail(
        ErrorKind::Io,
        "error.io",
        format!("{}: {error}", path.display()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const ABC_SHA1: &str = "a9993e364706816aba3e25717850c26c9cd0d89d";

    #[tokio::test]
    async fn content_cache_round_trips_and_verifies() {
        let dir = tempfile::tempdir().expect("temp dir");
        let cache = ContentCache::new(dir.path().join("cache"));
        let source = dir.path().join("part");
        tokio::fs::write(&source, b"abc").await.expect("write");

        let stored = cache.insert(ABC_SHA1, &source).await.expect("insert");
        assert_eq!(stored, cache.path(ABC_SHA1));

        // The engine renames its `.part` file onto the final destination after
        // caching; the hard-linked cache entry must survive that rename.
        let moved = dir.path().join("final");
        tokio::fs::rename(&source, &moved).await.expect("move");
        assert_eq!(tokio::fs::read(&stored).await.expect("read"), b"abc");
        assert_eq!(tokio::fs::read(&moved).await.expect("read"), b"abc");

        let dest = dir.path().join("nested").join("abc.bin");
        assert!(
            cache
                .materialize(ABC_SHA1, &dest)
                .await
                .expect("materialize")
        );
        assert_eq!(tokio::fs::read(&dest).await.expect("read"), b"abc");
    }

    #[tokio::test]
    async fn corrupt_entries_are_discarded() {
        let dir = tempfile::tempdir().expect("temp dir");
        let cache = ContentCache::new(dir.path().join("cache"));
        let path = cache.path(ABC_SHA1);
        tokio::fs::create_dir_all(path.parent().expect("parent"))
            .await
            .expect("mkdir");
        tokio::fs::write(&path, b"not abc").await.expect("write");

        assert!(cache.lookup(ABC_SHA1).await.is_none());
        assert!(!tokio::fs::try_exists(&path).await.expect("exists"));
    }

    #[test]
    fn rejects_non_sha1_digests() {
        assert!(!is_sha1("abc"));
        assert!(is_sha1(ABC_SHA1));
        assert!(!is_sha1(&ABC_SHA1.to_uppercase().repeat(2)));
    }

    #[tokio::test]
    async fn http_cache_stores_body_and_validators() {
        let dir = tempfile::tempdir().expect("temp dir");
        let cache = HttpCache::new(dir.path());
        let meta = CachedMeta {
            key: "https://example.com/manifest.json".into(),
            url: "https://example.com/manifest.json".into(),
            etag: Some("\"v1\"".into()),
            last_modified: None,
            content_type: Some("application/json".into()),
            fetched_at: 42,
            len: 2,
        };

        cache
            .put(meta.key.as_str(), &meta, b"{}")
            .await
            .expect("put");
        let (stored, body_path) = cache.get(&meta.key).await.expect("hit");

        assert_eq!(stored.etag.as_deref(), Some("\"v1\""));
        assert_eq!(tokio::fs::read(body_path).await.expect("read"), b"{}");

        cache.remove(&meta.key).await;
        assert!(cache.get(&meta.key).await.is_none());
    }

    #[test]
    fn cache_keys_are_stable_and_paths_are_sharded() {
        let cache = HttpCache::new("/cache");
        let key = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        let digest = HttpCache::key_for(key);

        assert_eq!(digest, HttpCache::key_for(key));
        assert_eq!(digest.len(), 40);
        assert!(cache.body_path(key).ends_with(format!("{digest}.body")));
        assert_eq!(
            cache
                .body_path(key)
                .parent()
                .expect("parent")
                .file_name()
                .expect("dir"),
            &digest[..2]
        );
    }
}
