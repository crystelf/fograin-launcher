//! Read-only zip helpers for loader installers.

use std::{
    collections::BTreeSet,
    fs::File,
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};

/// An opened installer archive with its entry list cached.
pub struct InstallerArchive {
    path: PathBuf,
    names: BTreeSet<String>,
}

impl InstallerArchive {
    /// Opens `path` and reads its central directory.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] when the file is not a zip archive.
    pub fn open(path: &Path) -> Result<Self> {
        let file = File::open(path)?;
        let archive = zip::ZipArchive::new(file).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.installer_zip",
                format!("{}: {error}", path.display()),
            )
        })?;
        let names = archive
            .file_names()
            .filter_map(std::result::Result::ok)
            .map(|name| name.to_string())
            .collect();
        Ok(Self {
            path: path.to_path_buf(),
            names,
        })
    }

    /// Archive path as opened.
    #[must_use]
    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Whether the archive contains `name` (leading slashes are ignored).
    #[must_use]
    pub fn has(&self, name: &str) -> bool {
        self.names.contains(normalize(name).as_str())
    }

    /// Reads one entry.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] for a missing entry and
    /// [`ErrorKind::Parse`] when the archive cannot be read.
    pub fn read(&self, name: &str) -> Result<Vec<u8>> {
        let normalized = normalize(name);
        let file = File::open(&self.path)?;
        let mut archive = zip::ZipArchive::new(file).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.installer_zip",
                format!("{}: {error}", self.path.display()),
            )
        })?;
        let mut entry = archive.by_name(&normalized).map_err(|error| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.loader.installer_entry",
                format!("{normalized}: {error}"),
            )
        })?;
        let mut bytes = Vec::with_capacity(usize::try_from(entry.size()).unwrap_or(0));
        std::io::Read::read_to_end(&mut entry, &mut bytes)?;
        Ok(bytes)
    }

    /// Entries under `prefix`, without the prefix.
    #[must_use]
    pub fn entries_under(&self, prefix: &str) -> Vec<String> {
        let prefix = normalize(prefix);
        self.names
            .iter()
            .filter_map(|name| name.strip_prefix(&prefix).map(ToString::to_string))
            .filter(|name| !name.is_empty() && !name.ends_with('/'))
            .collect()
    }

    /// Whether a Maven artifact is embedded under `maven/`.
    #[must_use]
    pub fn has_maven(&self, maven_path: &str) -> bool {
        self.has(&format!("maven/{maven_path}"))
    }
}

fn normalize(name: &str) -> String {
    name.trim_start_matches('/').replace('\\', "/")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn fixture() -> (tempfile::TempDir, PathBuf) {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("installer.jar");
        let file = File::create(&path).expect("file");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("install_profile.json", options)
            .expect("entry");
        zip.write_all(b"{}").expect("write");
        zip.start_file("maven/net/example/lib/1.0/lib-1.0.jar", options)
            .expect("entry");
        zip.write_all(b"jar").expect("write");
        zip.finish().expect("finish");
        (directory, path)
    }

    #[test]
    fn reads_entries_and_maven_paths() {
        let (_directory, path) = fixture();
        let archive = InstallerArchive::open(&path).expect("open");
        assert!(archive.has("/install_profile.json"));
        assert_eq!(archive.read("/install_profile.json").expect("read"), b"{}");
        assert!(archive.has_maven("net/example/lib/1.0/lib-1.0.jar"));
        assert!(!archive.has_maven("net/example/lib/2.0/lib-2.0.jar"));
        assert_eq!(
            archive.entries_under("maven/"),
            ["net/example/lib/1.0/lib-1.0.jar"]
        );
    }
}
