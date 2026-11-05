//! On-disk layout from §5.1.
//!
//! All shared game files live under `<root>/shared`; instances only own their
//! `.minecraft` directory and metadata. Keeping paths in one place makes it
//! possible to switch to the "非共享" layout in M4 without touching installers.

use std::path::{Path, PathBuf};

use fg_core::{Error, ErrorKind, Result};
use sha1::{Digest, Sha1};

/// Absolute paths for one launcher data root.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    root: PathBuf,
}

impl Layout {
    #[must_use]
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    #[must_use]
    pub fn shared(&self) -> PathBuf {
        self.root.join("shared")
    }

    #[must_use]
    pub fn versions(&self) -> PathBuf {
        self.shared().join("versions")
    }

    #[must_use]
    pub fn version_dir(&self, id: &str) -> PathBuf {
        self.versions().join(id)
    }

    #[must_use]
    pub fn version_json(&self, id: &str) -> PathBuf {
        self.version_dir(id).join(format!("{id}.json"))
    }

    #[must_use]
    pub fn version_jar(&self, id: &str) -> PathBuf {
        self.version_dir(id).join(format!("{id}.jar"))
    }

    /// Small install metadata used by launch to avoid re-parsing asset indexes.
    #[must_use]
    pub fn install_meta(&self, id: &str) -> PathBuf {
        self.version_dir(id).join("fograin-install.json")
    }

    #[must_use]
    pub fn libraries(&self) -> PathBuf {
        self.shared().join("libraries")
    }

    #[must_use]
    pub fn assets(&self) -> PathBuf {
        self.shared().join("assets")
    }

    #[must_use]
    pub fn asset_indexes(&self) -> PathBuf {
        self.assets().join("indexes")
    }

    #[must_use]
    pub fn asset_objects(&self) -> PathBuf {
        self.assets().join("objects")
    }

    #[must_use]
    pub fn log_configs(&self) -> PathBuf {
        self.assets().join("log_configs")
    }

    /// Legacy `virtual` layout for an asset index.
    #[must_use]
    pub fn virtual_assets(&self, index_id: &str) -> PathBuf {
        self.assets().join("virtual").join(index_id)
    }

    #[must_use]
    pub fn natives(&self, instance_id: &str) -> PathBuf {
        self.shared().join("natives").join(natives_tag(instance_id))
    }

    #[must_use]
    pub fn instances(&self) -> PathBuf {
        self.root.join("instances")
    }

    #[must_use]
    pub fn instance_dir(&self, instance_id: &str) -> PathBuf {
        self.instances().join(instance_id)
    }

    #[must_use]
    pub fn instance_file(&self, instance_id: &str) -> PathBuf {
        self.instance_dir(instance_id).join("instance.toml")
    }

    #[must_use]
    pub fn game_dir(&self, instance_id: &str) -> PathBuf {
        self.instance_dir(instance_id).join(".minecraft")
    }

    #[must_use]
    pub fn cache(&self) -> PathBuf {
        self.root.join("cache")
    }

    #[must_use]
    pub fn tasks(&self) -> PathBuf {
        self.cache().join("tasks")
    }

    #[must_use]
    pub fn install_plans(&self) -> PathBuf {
        self.cache().join("install")
    }

    #[must_use]
    pub fn runtimes(&self) -> PathBuf {
        self.root.join("runtimes")
    }

    /// Creates the directories the launcher owns.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when a directory cannot be created.
    pub fn ensure_base_dirs(&self) -> Result<()> {
        for directory in [
            self.shared(),
            self.versions(),
            self.libraries(),
            self.asset_indexes(),
            self.asset_objects(),
            self.log_configs(),
            self.instances(),
            self.cache(),
            self.tasks(),
        ] {
            std::fs::create_dir_all(&directory).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io.create_dir",
                    format!("{}: {error}", directory.display()),
                )
            })?;
        }
        Ok(())
    }
}

/// Short, filesystem-safe directory name for an instance's natives.
///
/// Using a hash keeps long instance ids and Unicode names out of the path while
/// remaining stable across launches.
#[must_use]
pub fn natives_tag(instance_id: &str) -> String {
    let mut hasher = Sha1::new();
    hasher.update(instance_id.as_bytes());
    let digest = hasher.finalize();
    digest[..8]
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

/// Validates an id before it is used as a path component.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for empty ids, separators and `..`.
pub fn validate_id(id: &str) -> Result<()> {
    if id.is_empty()
        || id == "."
        || id == ".."
        || id.contains('/')
        || id.contains('\\')
        || id.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.instance.invalid_id",
            format!("unsafe id {id:?}"),
        ));
    }
    Ok(())
}

/// Writes `contents` next to `path` and renames it into place (§5.2).
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when writing or renaming fails.
pub fn write_atomic(path: &Path, contents: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", parent.display()),
            )
        })?;
    }
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, contents).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.write",
            format!("{}: {error}", temporary.display()),
        )
    })?;
    std::fs::rename(&temporary, path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.rename",
            format!("{}: {error}", path.display()),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_layout_matches_the_design() {
        let layout = Layout::new("/data/fograin");
        assert_eq!(
            layout.version_json("1.21.4"),
            PathBuf::from("/data/fograin/shared/versions/1.21.4/1.21.4.json")
        );
        assert_eq!(
            layout.asset_objects(),
            PathBuf::from("/data/fograin/shared/assets/objects")
        );
        assert_eq!(
            layout.instance_file("inst-1"),
            PathBuf::from("/data/fograin/instances/inst-1/instance.toml")
        );
    }

    #[test]
    fn natives_tags_are_stable_and_short() {
        let tag = natives_tag("01JBX-instance");
        assert_eq!(tag.len(), 16);
        assert_eq!(tag, natives_tag("01JBX-instance"));
        assert_ne!(tag, natives_tag("another"));
    }

    #[test]
    fn ids_cannot_escape_the_instance_directory() {
        for id in ["", ".", "..", "../x", "a/b", "a\\b"] {
            assert!(validate_id(id).is_err(), "{id:?}");
        }
        assert!(validate_id("1.21.4").is_ok());
    }

    #[test]
    fn atomic_write_replaces_the_target() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("nested/instance.toml");
        write_atomic(&path, b"one").expect("first");
        write_atomic(&path, b"two").expect("second");
        assert_eq!(std::fs::read_to_string(&path).expect("read"), "two");
        assert!(!path.with_extension("tmp").exists());
    }
}
