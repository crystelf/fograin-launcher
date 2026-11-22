//! Small filesystem and clock helpers shared by the core crates.
//!
//! These live in `fg-core` so every crate reports timestamps the same way and
//! writes files through the same atomic-rename path instead of keeping a
//! private copy.

use std::{
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

use crate::{Error, ErrorKind, Result};

/// Milliseconds since the Unix epoch.
///
/// A clock that reports a time before the epoch (or an overflowing value)
/// saturates to `0` instead of panicking: timestamps are display metadata and
/// must never abort a download or an install.
#[must_use]
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| u64::try_from(duration.as_millis()).ok())
        .unwrap_or(0)
}

/// [`now_ms`] as `i64`, for files that persist a signed timestamp.
#[must_use]
pub fn now_ms_i64() -> i64 {
    i64::try_from(now_ms()).unwrap_or(i64::MAX)
}

/// Writes `contents` to `path` through a sibling temporary file and a rename.
///
/// The temporary name carries the process id and a counter, so two writers
/// targeting the same path never truncate each other's scratch file. The
/// rename replaces the destination on every platform.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the parent directory cannot be created, the
/// temporary file cannot be written, or the rename fails.
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

    let temporary = temp_path(path);
    std::fs::write(&temporary, contents).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.write",
            format!("{}: {error}", temporary.display()),
        )
    })?;
    replace_file(&temporary, path)
}

/// Path of the temporary sibling used by [`write_atomic`].
#[must_use]
pub fn temp_path(path: &Path) -> PathBuf {
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

fn replace_file(from: &Path, to: &Path) -> Result<()> {
    let result = std::fs::rename(from, to);
    // Windows refuses to replace an existing destination; retry once after
    // removing it. On Unix the first rename already succeeded.
    if result.is_err() && cfg!(windows) {
        let _ = std::fs::remove_file(to);
        if let Err(error) = std::fs::rename(from, to) {
            let _ = std::fs::remove_file(from);
            return Err(Error::with_detail(
                ErrorKind::Io,
                "error.io.rename",
                format!("{}: {error}", to.display()),
            ));
        }
        return Ok(());
    }
    result.map_err(|error| {
        let _ = std::fs::remove_file(from);
        Error::with_detail(
            ErrorKind::Io,
            "error.io.rename",
            format!("{}: {error}", to.display()),
        )
    })
}

/// Turns free-form text (an instance name, a pack name) into one path segment.
///
/// Alphanumerics, `-`, `_` and `.` survive; every other character becomes `-`.
/// Repeated dashes collapse, leading and trailing `-`/`.` are trimmed, and the
/// result is capped at 48 characters so it stays usable as a file name.
#[must_use]
pub fn sanitize_component(name: &str, fallback: &str) -> String {
    let mut text: String = name
        .chars()
        .map(|character| {
            if character.is_alphanumeric() || matches!(character, '-' | '_' | '.') {
                character
            } else {
                '-'
            }
        })
        .collect();
    while text.contains("--") {
        text = text.replace("--", "-");
    }
    let trimmed = text.trim_matches(['-', '.'].as_slice());
    if trimmed.is_empty() {
        fallback.to_string()
    } else {
        trimmed.chars().take(48).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn atomic_write_replaces_the_target_and_leaves_no_scratch_file() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("nested").join("value.toml");

        write_atomic(&path, b"one").expect("first");
        write_atomic(&path, b"two").expect("second");

        assert_eq!(std::fs::read(&path).expect("read"), b"two");
        let leftovers: Vec<_> = std::fs::read_dir(path.parent().expect("parent"))
            .expect("list")
            .filter_map(std::result::Result::ok)
            .filter(|entry| entry.file_name().to_string_lossy().ends_with(".tmp"))
            .collect();
        assert!(leftovers.is_empty(), "temporary file was left behind");
    }

    #[test]
    fn concurrent_writers_do_not_share_a_temporary_file() {
        let directory = tempfile::tempdir().expect("tempdir");
        let path = directory.path().join("value.toml");
        let first = temp_path(&path);
        let second = temp_path(&path);
        assert_ne!(first, second);
    }

    #[test]
    fn clock_values_are_consistent() {
        assert!(now_ms() > 0);
        assert_eq!(now_ms_i64() as u64, now_ms());
    }
}
