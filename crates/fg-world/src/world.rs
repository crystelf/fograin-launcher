//! World directories under `saves/`.

use std::{
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;

use crate::level::read_level_info;

/// One world folder.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct World {
    /// Folder name under `saves/`, which is also Minecraft's display name.
    pub name: String,
    pub path: String,
    pub size_bytes: u64,
    pub modified_ms: Option<i64>,
    /// Whether `icon.png` exists (`saves/<name>/icon.png`).
    pub has_icon: bool,
    /// `level.dat` display name; the folder name is the stable key.
    pub level_name: Option<String>,
    /// `survival` / `creative` / `adventure` / `spectator`.
    pub game_mode: Option<String>,
    /// Game version recorded by the last save.
    pub version: Option<String>,
    pub last_played_ms: Option<i64>,
    pub seed: Option<i64>,
}

/// Lists worlds, most recently modified first.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] only when `saves/` exists but cannot be read.
pub fn list_worlds(game_dir: &Path) -> Result<Vec<World>> {
    let saves = game_dir.join("saves");
    if !saves.is_dir() {
        return Ok(Vec::new());
    }
    let entries = fs::read_dir(&saves).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.read_dir",
            format!("{}: {error}", saves.display()),
        )
    })?;

    let mut worlds = Vec::new();
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                tracing::warn!(directory = %saves.display(), %error, "skipping unreadable world");
                continue;
            }
        };
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            continue;
        };
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            continue;
        }
        let level = read_level_info(&path);
        worlds.push(World {
            name: name.clone(),
            path: path.display().to_string(),
            size_bytes: directory_size(&path),
            modified_ms: metadata.modified().ok().and_then(to_epoch_ms),
            has_icon: path.join("icon.png").is_file(),
            level_name: level.as_ref().and_then(|level| level.name.clone()),
            game_mode: level.as_ref().and_then(|level| level.game_mode.clone()),
            version: level.as_ref().and_then(|level| level.version.clone()),
            last_played_ms: level.as_ref().and_then(|level| level.last_played_ms),
            seed: level.as_ref().and_then(|level| level.seed),
        });
    }

    worlds.sort_by(|left, right| {
        right
            .modified_ms
            .unwrap_or(i64::MIN)
            .cmp(&left.modified_ms.unwrap_or(i64::MIN))
            .then_with(|| left.name.cmp(&right.name))
    });
    Ok(worlds)
}

/// Deletes `saves/<name>`.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for unsafe names,
/// [`ErrorKind::NotFound`] for a missing world and [`ErrorKind::Io`] when the
/// directory cannot be removed.
pub fn delete_world(game_dir: &Path, name: &str) -> Result<()> {
    let saves = game_dir.join("saves");
    let path = world_path(&saves, name)?;
    let metadata = fs::symlink_metadata(&path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.world.missing",
                path.display().to_string(),
            )
        } else {
            Error::with_detail(
                ErrorKind::Io,
                "error.io",
                format!("{}: {error}", path.display()),
            )
        }
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.not_a_world",
            path.display().to_string(),
        ));
    }
    fs::remove_dir_all(&path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.world.delete",
            format!("{}: {error}", path.display()),
        )
    })
}

/// Absolute path of `saves/<name>`, rejecting traversal.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for empty names, separators and `..`.
pub fn world_path(saves: &Path, name: &str) -> Result<PathBuf> {
    validate_name(name)?;
    Ok(saves.join(name))
}

/// Validates a world folder name.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for names that are not a single path
/// component.
pub fn validate_name(name: &str) -> Result<()> {
    let trimmed = name.trim();
    if trimmed.is_empty()
        || trimmed == "."
        || trimmed == ".."
        || trimmed.contains('/')
        || trimmed.contains('\\')
        || trimmed.contains('\0')
        || name != trimmed
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.name",
            format!("unsafe world name {name:?}"),
        ));
    }
    Ok(())
}

pub(crate) fn directory_size(directory: &Path) -> u64 {
    let mut total = 0_u64;
    let Ok(entries) = fs::read_dir(directory) else {
        return 0;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            continue;
        };
        if metadata.file_type().is_symlink() {
            continue;
        }
        if metadata.is_dir() {
            total = total.saturating_add(directory_size(&path));
        } else {
            total = total.saturating_add(metadata.len());
        }
    }
    total
}

pub(crate) fn to_epoch_ms(time: SystemTime) -> Option<i64> {
    time.duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lists_worlds_and_rejects_traversal() {
        let temp = tempfile::tempdir().expect("tempdir");
        let saves = temp.path().join("saves");
        fs::create_dir_all(saves.join("New World")).expect("world");
        fs::write(saves.join("New World").join("level.dat"), b"nbt").expect("level");
        fs::write(saves.join("New World").join("icon.png"), b"png").expect("icon");
        fs::write(saves.join("notes.txt"), b"not a world").expect("file");

        let worlds = list_worlds(temp.path()).expect("list");
        assert_eq!(worlds.len(), 1);
        assert_eq!(worlds[0].name, "New World");
        assert!(worlds[0].has_icon);
        assert!(worlds[0].size_bytes >= 6);

        assert!(delete_world(temp.path(), "New World").is_ok());
        assert!(!saves.join("New World").exists());
        assert_eq!(
            delete_world(temp.path(), "../escape")
                .expect_err("traversal")
                .kind,
            ErrorKind::InvalidInput
        );
    }

    #[test]
    fn a_missing_saves_directory_is_empty() {
        let temp = tempfile::tempdir().expect("tempdir");
        assert!(list_worlds(temp.path()).expect("list").is_empty());
    }
}
