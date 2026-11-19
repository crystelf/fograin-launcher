//! Data packs live inside one world: `saves/<world>/datapacks/*.zip`.
//!
//! Enable/disable uses the same `.disabled` suffix as mods so HMCL, Prism and
//! the launcher all agree on the state.

use std::{
    fs::{self, File},
    io::{Read, Write},
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;
use serde_json::Value;
use zip::ZipArchive;

use crate::{content::strip_disabled, world::validate_name};

/// One installed data pack.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Datapack {
    pub file_name: String,
    pub title: String,
    pub enabled: bool,
    pub size_bytes: u64,
    pub modified_ms: Option<i64>,
    pub pack_format: Option<u32>,
    pub description: Option<String>,
}

/// Lists the data packs of one world, enabled first then by title.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the world does not exist and
/// [`ErrorKind::Io`] when `datapacks/` cannot be read.
pub fn list(game_dir: &Path, world: &str) -> Result<Vec<Datapack>> {
    let directory = datapack_dir(game_dir, world)?;
    if !directory.is_dir() {
        return Ok(Vec::new());
    }
    let entries = fs::read_dir(&directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.datapack.read",
            format!("{}: {error}", directory.display()),
        )
    })?;
    let mut datapacks = Vec::new();
    for entry in entries.flatten() {
        let file_name = entry.file_name().to_string_lossy().to_string();
        if file_name.starts_with('.') || file_name == ".DS_Store" {
            continue;
        }
        let (disabled, base_name) = strip_disabled(&file_name);
        if !base_name.to_ascii_lowercase().ends_with(".zip") {
            continue;
        }
        let Ok(metadata) = fs::symlink_metadata(entry.path()) else {
            continue;
        };
        if !metadata.is_file() || metadata.file_type().is_symlink() {
            continue;
        }
        let (pack_format, description) = read_meta(&entry.path());
        let title = description
            .as_deref()
            .map(str::trim)
            .filter(|text| !text.is_empty())
            .map(|text| text.chars().take(80).collect::<String>())
            .unwrap_or_else(|| {
                base_name
                    .trim_end_matches(".zip")
                    .trim_end_matches(".ZIP")
                    .to_string()
            });
        datapacks.push(Datapack {
            file_name,
            title,
            enabled: !disabled,
            size_bytes: metadata.len(),
            modified_ms: metadata.modified().ok().and_then(to_epoch_ms),
            pack_format,
            description,
        });
    }
    datapacks.sort_by(|left, right| {
        right
            .enabled
            .cmp(&left.enabled)
            .then_with(|| left.title.to_lowercase().cmp(&right.title.to_lowercase()))
    });
    Ok(datapacks)
}

/// Renames one data pack to `<name>` or `<name>.disabled`.
///
/// Returns the new file name.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the file disappeared and
/// [`ErrorKind::Conflict`] when the target name already exists.
pub fn set_enabled(game_dir: &Path, world: &str, file_name: &str, enabled: bool) -> Result<String> {
    let directory = datapack_dir(game_dir, world)?;
    let current = safe_entry(&directory, file_name)?;
    let (was_disabled, base_name) = strip_disabled(file_name);
    if was_disabled != enabled {
        // State already matches: map "disabled" to "enabled" and vice versa.
        let target_name = if enabled {
            base_name.to_string()
        } else {
            format!("{base_name}.disabled")
        };
        return Ok(target_name);
    }
    let target_name = if enabled {
        base_name.to_string()
    } else {
        format!("{base_name}.disabled")
    };
    let target = directory.join(&target_name);
    if target.exists() {
        return Err(Error::with_detail(
            ErrorKind::Conflict,
            "error.datapack.exists",
            target.display().to_string(),
        ));
    }
    fs::rename(&current, &target).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.datapack.write",
            format!("{}: {error}", current.display()),
        )
    })?;
    Ok(target_name)
}

/// Deletes one data pack.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the file disappeared.
pub fn delete(game_dir: &Path, world: &str, file_name: &str) -> Result<()> {
    let directory = datapack_dir(game_dir, world)?;
    let path = safe_entry(&directory, file_name)?;
    fs::remove_file(&path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.datapack.delete",
            format!("{}: {error}", path.display()),
        )
    })
}

/// Copies a `.zip` from anywhere on disk into the world.
///
/// Returns the file name written inside `datapacks/`.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] for a missing/not-a-zip source and
/// [`ErrorKind::Io`] when the copy fails.
pub fn import(game_dir: &Path, world: &str, source: &Path) -> Result<String> {
    let directory = datapack_dir(game_dir, world)?;
    let metadata = fs::metadata(source).map_err(|error| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.datapack.source",
            format!("{}: {error}", source.display()),
        )
    })?;
    if !metadata.is_file() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.datapack.source",
            source.display().to_string(),
        ));
    }
    let name = source
        .file_name()
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_default();
    if !name.to_ascii_lowercase().ends_with(".zip") {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.datapack.source",
            "data packs must be .zip files".to_string(),
        ));
    }
    let target_name = unique_name(&directory, &name);
    let target = directory.join(&target_name);
    fs::create_dir_all(&directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.datapack.write",
            format!("{}: {error}", directory.display()),
        )
    })?;
    fs::copy(source, &target).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.datapack.write",
            format!("{}: {error}", target.display()),
        )
    })?;
    Ok(target_name)
}

fn datapack_dir(game_dir: &Path, world: &str) -> Result<PathBuf> {
    validate_name(world)?;
    let world_dir = game_dir.join("saves").join(world);
    if !world_dir.is_dir() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.world.missing",
            world_dir.display().to_string(),
        ));
    }
    Ok(world_dir.join("datapacks"))
}

fn safe_entry(directory: &Path, file_name: &str) -> Result<PathBuf> {
    let (_, base_name) = strip_disabled(file_name);
    if file_name.is_empty()
        || file_name == "."
        || file_name == ".."
        || file_name.contains('/')
        || file_name.contains('\\')
        || file_name.contains('\0')
        || file_name.starts_with('.')
        || !base_name.to_ascii_lowercase().ends_with(".zip")
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.datapack.name",
            format!("unsafe data pack name {file_name:?}"),
        ));
    }
    let path = directory.join(file_name);
    if !path.is_file() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.datapack.missing",
            path.display().to_string(),
        ));
    }
    Ok(path)
}

fn unique_name(directory: &Path, name: &str) -> String {
    if !directory.join(name).exists() {
        return name.to_string();
    }
    let (stem, extension) = match name.rsplit_once('.') {
        Some((stem, extension)) if !stem.is_empty() => (stem.to_string(), format!(".{extension}")),
        _ => (name.to_string(), String::new()),
    };
    let mut counter = 2;
    loop {
        let candidate = format!("{stem}-{counter}{extension}");
        if !directory.join(&candidate).exists() {
            return candidate;
        }
        counter += 1;
    }
}

fn read_meta(path: &Path) -> (Option<u32>, Option<String>) {
    let Ok(file) = File::open(path) else {
        return (None, None);
    };
    let Ok(mut archive) = ZipArchive::new(file) else {
        return (None, None);
    };
    let Ok(mut entry) = archive.by_name("pack.mcmeta") else {
        return (None, None);
    };
    if entry.size() > 64 * 1024 {
        return (None, None);
    }
    let mut text = String::new();
    if entry.read_to_string(&mut text).is_err() {
        return (None, None);
    }
    parse_meta(&text)
}

fn parse_meta(text: &str) -> (Option<u32>, Option<String>) {
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        return (None, None);
    };
    let pack = value.get("pack");
    let format = pack
        .and_then(|pack| pack.get("pack_format"))
        .and_then(Value::as_u64)
        .and_then(|format| u32::try_from(format).ok());
    let description = pack
        .and_then(|pack| pack.get("description"))
        .map(|description| flatten_text(description).trim().to_string())
        .filter(|text| !text.is_empty());
    (format, description)
}

fn flatten_text(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Array(items) => items.iter().map(flatten_text).collect(),
        Value::Object(map) => {
            let mut text = String::new();
            if let Some(Value::String(value)) = map.get("text") {
                text.push_str(value);
            } else if let Some(Value::String(key)) = map.get("translate") {
                text.push_str(key);
            }
            if let Some(extra) = map.get("extra") {
                text.push_str(&flatten_text(extra));
            }
            text
        }
        _ => String::new(),
    }
}

fn to_epoch_ms(time: std::time::SystemTime) -> Option<i64> {
    time.duration_since(std::time::UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

/// Reads the `pack.mcmeta` of a `.zip` data pack; exposed for content scan tests.
pub fn read_pack_meta(path: &Path) -> (Option<u32>, Option<String>) {
    read_meta(path)
}

/// Writes a minimal `.zip` data pack. Tests and the CLI use it.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the archive cannot be written.
pub fn write_pack(path: &Path, pack_format: u32, description: &str) -> Result<()> {
    let file = File::create(path).map_err(|error| {
        Error::with_detail(ErrorKind::Io, "error.datapack.write", error.to_string())
    })?;
    let mut writer = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);
    writer.start_file("pack.mcmeta", options).map_err(|error| {
        Error::with_detail(ErrorKind::Io, "error.datapack.write", error.to_string())
    })?;
    let meta = format!(
        "{{\"pack\":{{\"pack_format\":{pack_format},\"description\":{}}}}}",
        serde_json::to_string(description).unwrap_or_else(|_| "\"\"".to_string())
    );
    writer.write_all(meta.as_bytes()).map_err(|error| {
        Error::with_detail(ErrorKind::Io, "error.datapack.write", error.to_string())
    })?;
    writer.finish().map_err(|error| {
        Error::with_detail(ErrorKind::Io, "error.datapack.write", error.to_string())
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn world_with_pack(temp: &Path) -> (PathBuf, String) {
        let game = temp.join("game");
        fs::create_dir_all(game.join("saves/World/datapacks")).expect("world");
        let pack = game.join("saves/World/datapacks/terralith.zip");
        write_pack(&pack, 48, "Terralith 地形").expect("pack");
        (game, "World".to_string())
    }

    #[test]
    fn lists_reads_meta_and_toggles() {
        let temp = tempfile::tempdir().expect("tempdir");
        let (game, world) = world_with_pack(temp.path());

        let packs = list(&game, &world).expect("list");
        assert_eq!(packs.len(), 1);
        assert_eq!(packs[0].title, "Terralith 地形");
        assert_eq!(packs[0].pack_format, Some(48));
        assert!(packs[0].enabled);

        let renamed = set_enabled(&game, &world, "terralith.zip", false).expect("disable");
        assert_eq!(renamed, "terralith.zip.disabled");
        let packs = list(&game, &world).expect("list");
        assert!(!packs[0].enabled);

        let renamed = set_enabled(&game, &world, "terralith.zip.disabled", true).expect("enable");
        assert_eq!(renamed, "terralith.zip");
        assert!(list(&game, &world).expect("list")[0].enabled);
    }

    #[test]
    fn imports_with_a_unique_name_and_deletes() {
        let temp = tempfile::tempdir().expect("tempdir");
        let (game, world) = world_with_pack(temp.path());
        let source = temp.path().join("terralith.zip");
        write_pack(&source, 48, "Terralith").expect("source");
        // The source now collides with the installed name.
        let name = import(&game, &world, &source).expect("import");
        assert_eq!(name, "terralith-2.zip");
        delete(&game, &world, &name).expect("delete");
        assert_eq!(list(&game, &world).expect("list").len(), 1);

        assert_eq!(
            delete(&game, &world, "../escape.zip")
                .expect_err("unsafe")
                .kind,
            ErrorKind::InvalidInput
        );
        assert_eq!(
            list(&game, "Missing").expect_err("missing world").kind,
            ErrorKind::NotFound
        );
    }

    #[test]
    fn parses_text_component_descriptions() {
        let (format, description) = parse_meta(
            r#"{"pack":{"pack_format":61,"description":{"text":"Fog ","extra":[{"text":"Packs"}]}}}"#,
        );
        assert_eq!(format, Some(61));
        assert_eq!(description.as_deref(), Some("Fog Packs"));
    }
}
