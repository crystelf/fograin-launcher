//! Small filesystem helpers shared by the caches and the engine.

use std::{
    io,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

/// Writes `bytes` to `path` through a temporary file and an atomic rename.
pub(crate) async fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    let temporary = temp_path(path);
    tokio::fs::write(&temporary, bytes).await?;
    replace_file(&temporary, path).await
}

/// Path of the temporary sibling used for atomic writes.
///
/// The suffix is unique per call so two tasks can write the same cache key at
/// once without truncating each other's temporary file.
pub(crate) fn temp_path(path: &Path) -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(0);

    let mut name = path
        .file_name()
        .map(|value| value.to_os_string())
        .unwrap_or_default();
    name.push(format!(
        ".{}.{}.tmp",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));

    match path.parent() {
        Some(parent) => parent.join(name),
        None => PathBuf::from(name),
    }
}

/// Renames `from` onto `to`, replacing an existing file on every platform.
pub(crate) async fn replace_file(from: &Path, to: &Path) -> io::Result<()> {
    match tokio::fs::rename(from, to).await {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
            remove_if_exists(to).await?;
            tokio::fs::rename(from, to).await
        }
        Err(_error) if cfg!(windows) => {
            // Windows `rename` refuses to replace an existing target.
            remove_if_exists(to).await?;
            tokio::fs::rename(from, to).await
        }
        Err(error) => Err(error),
    }
}

/// Creates a hard link when possible, otherwise copies the file.
pub(crate) async fn copy_or_link(from: &Path, to: &Path) -> io::Result<()> {
    if let Some(parent) = to.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }

    // Build the new file next to the target and rename it into place: removing
    // the destination first would expose a window where another task sees a
    // missing cache entry (or a half-written one).
    let temporary = temp_path(to);
    match tokio::fs::hard_link(from, &temporary).await {
        Ok(()) => {}
        Err(_) => {
            tokio::fs::copy(from, &temporary).await?;
        }
    }

    replace_file(&temporary, to).await
}

pub(crate) async fn remove_if_exists(path: &Path) -> io::Result<()> {
    match tokio::fs::remove_file(path).await {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn atomic_write_replaces_existing_content() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("nested").join("value.json");

        write_atomic(&path, b"first").await.expect("write");
        write_atomic(&path, b"second").await.expect("overwrite");

        let contents = tokio::fs::read(&path).await.expect("read");
        assert_eq!(contents, b"second");
        assert!(!temp_path(&path).exists());
    }

    #[tokio::test]
    async fn copy_or_link_creates_parents_and_overwrites() {
        let dir = tempfile::tempdir().expect("temp dir");
        let source = dir.path().join("source.bin");
        let target = dir.path().join("a").join("b").join("target.bin");
        tokio::fs::write(&source, b"payload").await.expect("write");

        copy_or_link(&source, &target).await.expect("link or copy");
        assert_eq!(tokio::fs::read(&target).await.expect("read"), b"payload");

        tokio::fs::write(&source, b"changed").await.expect("write");
        copy_or_link(&source, &target).await.expect("replace");
        assert_eq!(tokio::fs::read(&target).await.expect("read"), b"changed");
    }
}
