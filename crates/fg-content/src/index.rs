//! Per-instance content index.
//!
//! The index is the launcher's memory of where a local file came from. It is
//! stored next to `instance.toml` (`instances/<id>/content.json`) rather than
//! inside `.minecraft`, so sharing or backing up the game directory does not
//! leak launcher bookkeeping and external imports stay untouched.
//!
//! The index is advisory: a file that disappears is simply no longer listed,
//! and a manually copied file has no entry (it still shows up in the content
//! manager, just without an update source).

use std::{
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

use crate::ContentKind;

/// File name inside the instance directory.
pub const CONTENT_INDEX_FILE: &str = "content.json";

/// Index schema version.
pub const CONTENT_INDEX_SCHEMA_VERSION: u32 = 1;

/// One installed project/version.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexEntry {
    pub file_name: String,
    pub kind: ContentKind,
    pub source: String,
    pub project_id: String,
    pub project_name: String,
    pub version_id: String,
    pub version_number: String,
    pub sha1: Option<String>,
    pub installed_at_ms: u64,
}

/// `instances/<id>/content.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ContentIndex {
    pub schema_version: u32,
    pub items: Vec<IndexEntry>,
}

impl Default for ContentIndex {
    fn default() -> Self {
        Self {
            schema_version: CONTENT_INDEX_SCHEMA_VERSION,
            items: Vec::new(),
        }
    }
}

impl ContentIndex {
    /// Loads an index from an instance directory; a missing file yields an
    /// empty index.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed JSON and
    /// [`ErrorKind::Unsupported`] for a future schema version.
    pub fn load(instance_dir: &Path) -> Result<Self> {
        let path = index_path(instance_dir);
        let text = match fs::read_to_string(&path) {
            Ok(text) => text,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Self::default());
            }
            Err(error) => {
                return Err(Error::with_detail(
                    ErrorKind::Io,
                    "error.content.index_read",
                    format!("{}: {error}", path.display()),
                ));
            }
        };
        let mut index: Self = serde_json::from_str(&text).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.content.index_parse",
                format!("{}: {error}", path.display()),
            )
        })?;
        if index.schema_version == 0 {
            index.schema_version = CONTENT_INDEX_SCHEMA_VERSION;
        }
        if index.schema_version > CONTENT_INDEX_SCHEMA_VERSION {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.content.index_schema",
                format!(
                    "{} uses schema {}, expected at most {}",
                    path.display(),
                    index.schema_version,
                    CONTENT_INDEX_SCHEMA_VERSION
                ),
            ));
        }
        Ok(index)
    }

    /// Writes the index atomically.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the file cannot be written.
    pub fn save(&self, instance_dir: &Path) -> Result<()> {
        let path = index_path(instance_dir);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io.create_dir",
                    format!("{}: {error}", parent.display()),
                )
            })?;
        }
        let text = serde_json::to_string_pretty(self).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.content.index_serialize",
                error.to_string(),
            )
        })?;
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, text.as_bytes()).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.content.index_write",
                format!("{}: {error}", temporary.display()),
            )
        })?;
        fs::rename(&temporary, &path).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.content.index_write",
                format!("{}: {error}", path.display()),
            )
        })?;
        Ok(())
    }

    /// Finds the entry for an on-disk file name.
    #[must_use]
    pub fn find(&self, file_name: &str) -> Option<&IndexEntry> {
        self.items.iter().find(|entry| entry.file_name == file_name)
    }

    /// Inserts or replaces the entry for `entry.file_name`.
    pub fn upsert(&mut self, entry: IndexEntry) {
        if let Some(existing) = self
            .items
            .iter_mut()
            .find(|existing| existing.file_name == entry.file_name)
        {
            *existing = entry;
        } else {
            self.items.push(entry);
        }
    }

    /// Removes the entry for `file_name`.
    pub fn remove(&mut self, file_name: &str) {
        self.items.retain(|entry| entry.file_name != file_name);
    }

    /// Entries of one kind.
    #[must_use]
    pub fn of_kind(&self, kind: ContentKind) -> Vec<&IndexEntry> {
        self.items
            .iter()
            .filter(|entry| entry.kind == kind)
            .collect()
    }
}

/// Absolute path of an instance's content index.
#[must_use]
pub fn index_path(instance_dir: &Path) -> PathBuf {
    instance_dir.join(CONTENT_INDEX_FILE)
}

/// Current epoch milliseconds; `0` when the clock is before the epoch.
#[must_use]
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| {
            u64::try_from(duration.as_millis()).unwrap_or(u64::MAX)
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str) -> IndexEntry {
        IndexEntry {
            file_name: name.into(),
            kind: ContentKind::Mod,
            source: "modrinth".into(),
            project_id: "AANobbMI".into(),
            project_name: "Sodium".into(),
            version_id: "c3YkZvne".into(),
            version_number: "0.6.13".into(),
            sha1: Some("aa".into()),
            installed_at_ms: 1,
        }
    }

    #[test]
    fn round_trips_and_replaces_entries() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut index = ContentIndex::default();
        index.upsert(entry("sodium.jar"));
        index.upsert(entry("sodium.jar"));
        assert_eq!(index.items.len(), 1);
        index.save(temp.path()).expect("save");

        let loaded = ContentIndex::load(temp.path()).expect("load");
        assert_eq!(loaded.items.len(), 1);
        assert_eq!(
            loaded.find("sodium.jar").expect("entry").project_name,
            "Sodium"
        );

        index.remove("sodium.jar");
        index.save(temp.path()).expect("save empty");
        assert!(
            ContentIndex::load(temp.path())
                .expect("load")
                .items
                .is_empty()
        );
    }

    #[test]
    fn a_missing_index_is_empty_but_a_future_one_is_rejected() {
        let temp = tempfile::tempdir().expect("tempdir");
        assert!(
            ContentIndex::load(temp.path())
                .expect("missing")
                .items
                .is_empty()
        );

        fs::write(
            index_path(temp.path()),
            r#"{"schemaVersion":99,"items":[]}"#,
        )
        .expect("write");
        assert_eq!(
            ContentIndex::load(temp.path()).expect_err("future").kind,
            ErrorKind::Unsupported
        );
    }

    #[test]
    fn malformed_json_is_a_parse_error() {
        let temp = tempfile::tempdir().expect("tempdir");
        fs::write(index_path(temp.path()), "{").expect("write");
        assert_eq!(
            ContentIndex::load(temp.path()).expect_err("parse").kind,
            ErrorKind::Parse
        );
    }
}
