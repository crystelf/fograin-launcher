//! World backups under `<data root>/backups/<instance id>/<timestamp>.zip`.

use std::{
    fs::{self, File},
    io,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;
use time::{OffsetDateTime, macros::format_description};
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

use crate::world::validate_name;

/// Largest restored backup. A world above this size is almost certainly a
/// corrupt or malicious archive; refusing it keeps the extraction bounded.
const MAX_RESTORE_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const MAX_RESTORE_ENTRIES: usize = 200_000;

/// One backup file.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Backup {
    /// File name inside the instance's backup directory.
    pub name: String,
    pub path: String,
    pub size_bytes: u64,
    pub modified_ms: Option<i64>,
}

/// Backs `saves/<world>` up as a zip.
///
/// `session.lock` is skipped: it is a runtime lock, not world data.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the world is missing and
/// [`ErrorKind::Io`] when the backup cannot be written.
pub fn backup_world(
    data_root: &Path,
    instance_id: &str,
    game_dir: &Path,
    world_name: &str,
) -> Result<Backup> {
    validate_name(world_name)?;
    let world = game_dir.join("saves").join(world_name);
    let metadata = fs::symlink_metadata(&world).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.world.missing",
                world.display().to_string(),
            )
        } else {
            Error::with_detail(
                ErrorKind::Io,
                "error.io",
                format!("{}: {error}", world.display()),
            )
        }
    })?;
    if !metadata.is_dir() || metadata.file_type().is_symlink() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.not_a_world",
            world.display().to_string(),
        ));
    }

    let directory = backup_dir(data_root, instance_id);
    fs::create_dir_all(&directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.create_dir",
            format!("{}: {error}", directory.display()),
        )
    })?;
    let path = unique_backup_path(&directory);
    let result = write_backup(&world, &path);
    if let Err(error) = result {
        let _ = fs::remove_file(&path);
        return Err(error);
    }
    let size_bytes = fs::metadata(&path).map_or(0, |metadata| metadata.len());
    Ok(Backup {
        name: path
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .unwrap_or_default(),
        path: path.display().to_string(),
        size_bytes,
        modified_ms: fs::metadata(&path)
            .ok()
            .and_then(|metadata| metadata.modified().ok())
            .and_then(to_epoch_ms),
    })
}

/// Lists backup files, newest first.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the backup directory exists but cannot be
/// read.
pub fn list_backups(data_root: &Path, instance_id: &str) -> Result<Vec<Backup>> {
    let directory = backup_dir(data_root, instance_id);
    if !directory.is_dir() {
        return Ok(Vec::new());
    }
    let entries = fs::read_dir(&directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.read_dir",
            format!("{}: {error}", directory.display()),
        )
    })?;
    let mut backups = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') || !name.to_ascii_lowercase().ends_with(".zip") {
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if !metadata.is_file() {
            continue;
        }
        backups.push(Backup {
            name,
            path: path.display().to_string(),
            size_bytes: metadata.len(),
            modified_ms: metadata.modified().ok().and_then(to_epoch_ms),
        });
    }
    backups.sort_by(|left, right| {
        right
            .modified_ms
            .unwrap_or(i64::MIN)
            .cmp(&left.modified_ms.unwrap_or(i64::MIN))
            .then_with(|| left.name.cmp(&right.name))
    });
    Ok(backups)
}

/// Deletes one backup file.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for unsafe names and
/// [`ErrorKind::NotFound`] when the file is missing.
pub fn delete_backup(data_root: &Path, instance_id: &str, backup_name: &str) -> Result<()> {
    let path = backup_path(data_root, instance_id, backup_name)?;
    fs::remove_file(&path).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.world.backup_missing",
                path.display().to_string(),
            )
        } else {
            Error::with_detail(
                ErrorKind::Io,
                "error.world.backup_delete",
                format!("{}: {error}", path.display()),
            )
        }
    })
}

/// Restores a backup into a new world folder.
///
/// Restoring never overwrites: the caller chooses a new world name, usually
/// the original plus a suffix.
///
/// # Errors
///
/// Returns [`ErrorKind::Conflict`] when the target world already exists,
/// [`ErrorKind::Parse`] for an unreadable archive and [`ErrorKind::Io`] when
/// extraction fails.
pub fn restore_backup(
    data_root: &Path,
    instance_id: &str,
    game_dir: &Path,
    backup_name: &str,
    world_name: &str,
) -> Result<PathBuf> {
    validate_name(world_name)?;
    let path = backup_path(data_root, instance_id, backup_name)?;
    let saves = game_dir.join("saves");
    fs::create_dir_all(&saves).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.create_dir",
            format!("{}: {error}", saves.display()),
        )
    })?;
    let target = saves.join(world_name);
    if target.exists() {
        return Err(Error::with_detail(
            ErrorKind::Conflict,
            "error.world.exists",
            target.display().to_string(),
        ));
    }

    let file = File::open(&path).map_err(|error| {
        if error.kind() == io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.world.backup_missing",
                path.display().to_string(),
            )
        } else {
            Error::with_detail(
                ErrorKind::Io,
                "error.world.backup_read",
                format!("{}: {error}", path.display()),
            )
        }
    })?;
    let mut archive = ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.world.backup_parse",
            format!("{}: {error}", path.display()),
        )
    })?;

    let temporary = saves.join(format!(
        ".restore-{world_name}-{pid}",
        pid = std::process::id()
    ));
    if temporary.exists() {
        let _ = fs::remove_dir_all(&temporary);
    }
    fs::create_dir_all(&temporary).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.create_dir",
            format!("{}: {error}", temporary.display()),
        )
    })?;

    let result = extract(&mut archive, &temporary);
    if let Err(error) = result {
        let _ = fs::remove_dir_all(&temporary);
        return Err(error);
    }
    if let Err(error) = fs::rename(&temporary, &target) {
        let _ = fs::remove_dir_all(&temporary);
        return Err(Error::with_detail(
            ErrorKind::Io,
            "error.world.restore",
            format!("{}: {error}", target.display()),
        ));
    }
    Ok(target)
}

fn extract(archive: &mut ZipArchive<File>, target: &Path) -> Result<()> {
    if archive.len() > MAX_RESTORE_ENTRIES {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.backup_entries",
            archive.len().to_string(),
        ));
    }
    let mut total = 0_u64;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.world.backup_parse",
                error.to_string(),
            )
        })?;
        let Some(relative) = entry.enclosed_name() else {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.world.backup_entry",
                entry
                    .name()
                    .map(|name| name.to_string())
                    .unwrap_or_default(),
            ));
        };
        if relative.as_os_str().is_empty() {
            continue;
        }
        total = total.saturating_add(entry.size());
        if total > MAX_RESTORE_BYTES {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.world.backup_too_large",
                total.to_string(),
            ));
        }
        let destination = target.join(&relative);
        if entry.is_dir() {
            fs::create_dir_all(&destination).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io.create_dir",
                    format!("{}: {error}", destination.display()),
                )
            })?;
            continue;
        }
        if let Some(parent) = destination.parent() {
            fs::create_dir_all(parent).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io.create_dir",
                    format!("{}: {error}", parent.display()),
                )
            })?;
        }
        let mut output = File::create(&destination).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.world.restore",
                format!("{}: {error}", destination.display()),
            )
        })?;
        io::copy(&mut entry, &mut output).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.world.restore",
                format!("{}: {error}", destination.display()),
            )
        })?;
    }
    Ok(())
}

fn write_backup(world: &Path, path: &Path) -> Result<()> {
    let file = File::create(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.world.backup_write",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut writer = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    add_directory(&mut writer, world, world, options)?;
    writer.finish().map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.world.backup_write",
            format!("{}: {error}", path.display()),
        )
    })?;
    Ok(())
}

fn add_directory(
    writer: &mut ZipWriter<File>,
    root: &Path,
    directory: &Path,
    options: SimpleFileOptions,
) -> Result<()> {
    let entries = fs::read_dir(directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.read_dir",
            format!("{}: {error}", directory.display()),
        )
    })?;
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(metadata) = fs::symlink_metadata(&path) else {
            continue;
        };
        if metadata.file_type().is_symlink() {
            continue;
        }
        let Ok(relative) = path.strip_prefix(root) else {
            continue;
        };
        if relative == Path::new("session.lock") {
            continue;
        }
        let name = relative.to_string_lossy().replace('\\', "/");
        if metadata.is_dir() {
            add_directory(writer, root, &path, options)?;
        } else if metadata.is_file() {
            let mut input = File::open(&path).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.world.backup_read",
                    format!("{}: {error}", path.display()),
                )
            })?;
            writer.start_file(&name, options).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.world.backup_write",
                    format!("{name}: {error}"),
                )
            })?;
            io::copy(&mut input, writer).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.world.backup_write",
                    format!("{name}: {error}"),
                )
            })?;
        }
    }
    Ok(())
}

fn backup_dir(data_root: &Path, instance_id: &str) -> PathBuf {
    data_root
        .join("backups")
        .join(sanitize_instance(instance_id))
}

fn backup_path(data_root: &Path, instance_id: &str, backup_name: &str) -> Result<PathBuf> {
    let mut name = backup_name.trim().to_string();
    if !name.to_ascii_lowercase().ends_with(".zip") {
        name.push_str(".zip");
    }
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.world.backup_name",
            format!("unsafe backup name {backup_name:?}"),
        ));
    }
    Ok(backup_dir(data_root, instance_id).join(name))
}

fn unique_backup_path(directory: &Path) -> PathBuf {
    let stamp = OffsetDateTime::now_utc()
        .format(format_description!(
            "[year][month][day]-[hour][minute][second]"
        ))
        .unwrap_or_else(|_| "backup".to_string());
    let mut path = directory.join(format!("{stamp}.zip"));
    let mut counter = 2;
    while path.exists() {
        path = directory.join(format!("{stamp}-{counter}.zip"));
        counter += 1;
    }
    path
}

fn sanitize_instance(instance_id: &str) -> String {
    instance_id
        .chars()
        .map(|character| {
            if character.is_alphanumeric() || matches!(character, '-' | '_') {
                character
            } else {
                '_'
            }
        })
        .collect()
}

fn to_epoch_ms(time: SystemTime) -> Option<i64> {
    time.duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn world(root: &Path) -> PathBuf {
        let path = root.join("saves").join("New World");
        fs::create_dir_all(path.join("region")).expect("region");
        fs::write(path.join("level.dat"), b"level").expect("level");
        fs::write(path.join("region").join("r.0.0.mca"), b"region").expect("region file");
        fs::write(path.join("session.lock"), b"lock").expect("lock");
        path
    }

    #[test]
    fn backup_round_trips_and_skips_the_session_lock() {
        let temp = tempfile::tempdir().expect("tempdir");
        let game = temp.path().join("game");
        let world = world(&game);

        let backup = backup_world(temp.path(), "fog-forest", &game, "New World").expect("backup");
        assert!(Path::new(&backup.path).is_file());
        assert!(backup.size_bytes > 0);

        let listed = list_backups(temp.path(), "fog-forest").expect("list");
        assert_eq!(listed.len(), 1);

        let restore = restore_backup(
            temp.path(),
            "fog-forest",
            &game,
            &backup.name,
            "New World Restored",
        )
        .expect("restore");
        assert_eq!(
            fs::read(restore.join("level.dat")).expect("level"),
            b"level"
        );
        assert!(restore.join("region").join("r.0.0.mca").is_file());
        assert!(!restore.join("session.lock").exists());

        assert_eq!(
            restore_backup(
                temp.path(),
                "fog-forest",
                &game,
                &backup.name,
                "New World Restored"
            )
            .expect_err("exists")
            .kind,
            ErrorKind::Conflict
        );

        delete_backup(temp.path(), "fog-forest", &backup.name).expect("delete");
        assert!(
            list_backups(temp.path(), "fog-forest")
                .expect("list")
                .is_empty()
        );
        assert!(world.exists());
    }

    #[test]
    fn unsafe_backup_names_are_rejected() {
        let temp = tempfile::tempdir().expect("tempdir");
        assert_eq!(
            delete_backup(temp.path(), "i", "../x.zip")
                .expect_err("traversal")
                .kind,
            ErrorKind::InvalidInput
        );
    }
}
