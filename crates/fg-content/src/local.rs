//! Local content scanning, enable/disable and deletion.
//!
//! Minecraft itself ignores anything that does not end in `.jar` (mods) or
//! `.zip` (resource/shader packs), so a disabled item is simply renamed with a
//! `.disabled` suffix. This is the convention used by HMCL, PCL and Prism, so
//! instances stay portable between launchers.

use std::{
    fs::{self, File},
    io::Read,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;
use zip::ZipArchive;

use crate::{
    ContentKind,
    index::{ContentIndex, IndexEntry},
    kind::strip_disabled,
};

/// Metadata limit for a single archive entry (`pack.mcmeta`, `fabric.mod.json`).
const MAX_METADATA_BYTES: u64 = 2 * 1024 * 1024;

/// One local mod, resource pack or shader pack.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalContent {
    pub kind: ContentKind,
    /// On-disk name, including `.disabled` when the item is disabled.
    pub file_name: String,
    /// Name Minecraft would see; `.disabled` removed.
    pub display_name: String,
    pub enabled: bool,
    pub path: String,
    pub size_bytes: u64,
    pub modified_ms: Option<i64>,
    /// Human-readable name from the embedded metadata, or the file stem.
    pub title: String,
    pub version: Option<String>,
    /// `fabric`, `quilt`, `forge`, `neoforge`, or `None` when unknown.
    pub loader: Option<String>,
    pub description: Option<String>,
    /// `pack_format` from `pack.mcmeta` for resource packs.
    pub pack_format: Option<i64>,
    /// Values filled from the instance content index.
    pub source: Option<String>,
    pub project_id: Option<String>,
    pub project_name: Option<String>,
    pub version_id: Option<String>,
}

impl LocalContent {
    fn new(kind: ContentKind, path: &Path, raw_name: &str, metadata: &fs::Metadata) -> Self {
        let (disabled, display_name) = strip_disabled(raw_name);
        let title = fallback_title(display_name);
        Self {
            kind,
            file_name: raw_name.to_string(),
            display_name: display_name.to_string(),
            enabled: !disabled,
            path: path.display().to_string(),
            size_bytes: metadata.len(),
            modified_ms: metadata.modified().ok().and_then(to_epoch_ms),
            title,
            version: None,
            loader: None,
            description: None,
            pack_format: None,
            source: None,
            project_id: None,
            project_name: None,
            version_id: None,
        }
    }

    /// Copies source/project/version metadata from the instance index.
    pub fn apply_index(&mut self, entry: &IndexEntry) {
        self.source = Some(entry.source.clone());
        self.project_id = Some(entry.project_id.clone());
        self.project_name = Some(entry.project_name.clone());
        self.version_id = Some(entry.version_id.clone());
        if self.version.is_none() {
            self.version = Some(entry.version_number.clone());
        }
        if self.title == fallback_title(&self.display_name) && !entry.project_name.is_empty() {
            self.title.clone_from(&entry.project_name);
        }
    }
}

/// Scans one content directory and merges the instance index.
///
/// A missing directory is not an error: freshly created or imported instances
/// often have no `shaderpacks/` yet.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the directory exists but cannot be read.
pub fn scan_local(
    game_dir: &Path,
    kind: ContentKind,
    index: Option<&ContentIndex>,
) -> Result<Vec<LocalContent>> {
    let directory = kind.directory(game_dir);
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

    let mut items = Vec::new();
    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                tracing::warn!(directory = %directory.display(), %error, "skipping unreadable content entry");
                continue;
            }
        };
        let raw_name = entry.file_name().to_string_lossy().to_string();
        if raw_name.starts_with('.') {
            continue;
        }
        let path = entry.path();
        let metadata = match fs::metadata(&path) {
            Ok(metadata) => metadata,
            Err(error) => {
                tracing::warn!(path = %path.display(), %error, "skipping content entry without metadata");
                continue;
            }
        };
        if metadata.is_dir() && !kind.accepts_directory() {
            continue;
        }
        if !metadata.is_dir() && !kind.accepts_name(&raw_name) {
            continue;
        }

        let mut item = LocalContent::new(kind, &path, &raw_name, &metadata);
        if metadata.is_dir() {
            item.size_bytes = directory_size(&path);
            if kind == ContentKind::ResourcePack {
                apply_pack_meta(&mut item, &read_dir_pack_meta(&path));
            } else if kind == ContentKind::ShaderPack && !path.join("shaders").is_dir() {
                tracing::debug!(path = %path.display(), "shader pack directory has no shaders/");
            }
        } else if kind == ContentKind::Mod {
            read_mod_metadata(&path, &mut item);
        } else if kind == ContentKind::ResourcePack {
            apply_pack_meta(&mut item, &read_zip_pack_meta(&path));
        } else {
            detect_shader_pack(&path);
        }

        if let Some(entry) = index.and_then(|index| index.find(&raw_name)) {
            item.apply_index(entry);
        }
        items.push(item);
    }

    items.sort_by(|left, right| {
        left.title
            .to_lowercase()
            .cmp(&right.title.to_lowercase())
            .then_with(|| left.file_name.cmp(&right.file_name))
    });
    Ok(items)
}

/// Enables or disables one item by renaming it.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the item is missing,
/// [`ErrorKind::InvalidInput`] for unsafe names and [`ErrorKind::Conflict`]
/// when the target name already exists.
pub fn set_enabled(
    game_dir: &Path,
    kind: ContentKind,
    file_name: &str,
    enabled: bool,
) -> Result<String> {
    let directory = kind.directory(game_dir);
    let source = child_path(&directory, file_name)?;
    if !source.exists() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.content.file_missing",
            source.display().to_string(),
        ));
    }

    let (disabled, base_name) = strip_disabled(file_name);
    if enabled && !disabled {
        return Ok(file_name.to_string());
    }
    if !enabled && disabled {
        return Ok(file_name.to_string());
    }

    let target_name = if enabled {
        base_name.to_string()
    } else {
        format!("{base_name}.disabled")
    };
    let target = child_path(&directory, &target_name)?;
    if target.exists() {
        return Err(Error::with_detail(
            ErrorKind::Conflict,
            "error.content.file_conflict",
            target.display().to_string(),
        ));
    }

    fs::rename(&source, &target).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.rename",
            format!("{} → {}: {error}", source.display(), target.display()),
        )
    })?;
    Ok(target_name)
}

/// Deletes one item permanently.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the item is missing and
/// [`ErrorKind::InvalidInput`] for unsafe names.
pub fn delete_local(game_dir: &Path, kind: ContentKind, file_name: &str) -> Result<()> {
    let directory = kind.directory(game_dir);
    let path = child_path(&directory, file_name)?;
    let metadata = fs::symlink_metadata(&path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.content.file_missing",
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

    if metadata.is_dir() && !metadata.file_type().is_symlink() {
        fs::remove_dir_all(&path)
    } else {
        fs::remove_file(&path)
    }
    .map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.content.delete",
            format!("{}: {error}", path.display()),
        )
    })
}

fn child_path(directory: &Path, name: &str) -> Result<PathBuf> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
    {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.content.file",
            format!("unsafe content file name {name:?}"),
        ));
    }
    Ok(directory.join(name))
}

fn apply_pack_meta(item: &mut LocalContent, meta: &Option<PackMeta>) {
    let Some(meta) = meta else {
        return;
    };
    item.pack_format = meta.pack_format;
    if let Some(description) = &meta.description
        && !description.is_empty()
    {
        item.description = Some(description.clone());
    }
}

#[derive(Debug, Default)]
struct PackMeta {
    pack_format: Option<i64>,
    description: Option<String>,
}

fn read_zip_pack_meta(path: &Path) -> Option<PackMeta> {
    let mut archive = open_archive(path)?;
    let bytes = read_entry(&mut archive, "pack.mcmeta", MAX_METADATA_BYTES)?;
    parse_pack_meta(&bytes)
}

fn read_dir_pack_meta(directory: &Path) -> Option<PackMeta> {
    let bytes = read_small_file(&directory.join("pack.mcmeta"))?;
    parse_pack_meta(&bytes)
}

fn parse_pack_meta(bytes: &[u8]) -> Option<PackMeta> {
    let value: serde_json::Value = serde_json::from_slice(bytes).ok()?;
    let pack = value.get("pack")?;
    let pack_format = pack.get("pack_format").and_then(serde_json::Value::as_i64);
    let description = pack.get("description").map(json_text);
    Some(PackMeta {
        pack_format,
        description: description.map(|text| truncate(&text, 400)),
    })
}

fn detect_shader_pack(path: &Path) {
    let archive = match open_archive(path) {
        Some(archive) => archive,
        None => return,
    };
    let has_shaders = archive
        .file_names()
        .filter_map(|name| name.ok())
        .any(|name| {
            let lower = name.replace('\\', "/").to_ascii_lowercase();
            lower.starts_with("shaders/") || lower == "shaders"
        });
    if !has_shaders {
        tracing::debug!(path = %path.display(), "shader pack has no shaders/ entry");
    }
}

fn read_mod_metadata(path: &Path, item: &mut LocalContent) {
    let Some(mut archive) = open_archive(path) else {
        return;
    };

    if let Some(bytes) = read_entry(&mut archive, "fabric.mod.json", MAX_METADATA_BYTES)
        && let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes)
    {
        apply_mod_fields(
            item,
            "fabric",
            value.get("name").and_then(serde_json::Value::as_str),
            value.get("version").and_then(serde_json::Value::as_str),
            value.get("description").map(json_text).as_deref(),
        );
        if item.description.is_none() {
            item.description = value
                .get("description")
                .map(json_text)
                .map(|text| truncate(&text, 400));
        }
        if value
            .get("name")
            .and_then(serde_json::Value::as_str)
            .is_none()
            && let Some(id) = value.get("id").and_then(serde_json::Value::as_str)
        {
            item.title = id.to_string();
        }
        return;
    }

    if let Some(bytes) = read_entry(&mut archive, "quilt.mod.json", MAX_METADATA_BYTES)
        && let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes)
    {
        let loader = value.get("quilt_loader");
        let metadata = loader.and_then(|loader| loader.get("metadata"));
        apply_mod_fields(
            item,
            "quilt",
            metadata
                .and_then(|metadata| metadata.get("name"))
                .and_then(serde_json::Value::as_str)
                .or_else(|| value.get("name").and_then(serde_json::Value::as_str)),
            loader
                .and_then(|loader| loader.get("version"))
                .and_then(serde_json::Value::as_str),
            metadata
                .and_then(|metadata| metadata.get("description"))
                .map(json_text)
                .as_deref(),
        );
        return;
    }

    for (entry, loader) in [
        ("META-INF/neoforge.mods.toml", "neoforge"),
        ("META-INF/mods.toml", "forge"),
    ] {
        if let Some(bytes) = read_entry(&mut archive, entry, MAX_METADATA_BYTES)
            && let Some((title, version, description)) = parse_forge_mods(&bytes)
        {
            apply_mod_fields(
                item,
                loader,
                Some(&title),
                version.as_deref(),
                description.as_deref(),
            );
            return;
        }
    }

    if let Some(bytes) = read_entry(&mut archive, "mcmod.info", MAX_METADATA_BYTES)
        && let Some((title, version, description)) = parse_mcmod_info(&bytes)
    {
        apply_mod_fields(
            item,
            "forge",
            Some(&title),
            version.as_deref(),
            description.as_deref(),
        );
    }
}

fn apply_mod_fields(
    item: &mut LocalContent,
    loader: &str,
    title: Option<&str>,
    version: Option<&str>,
    description: Option<&str>,
) {
    item.loader = Some(loader.to_string());
    if let Some(title) = title.map(str::trim).filter(|value| !value.is_empty()) {
        item.title = title.to_string();
    }
    if let Some(version) = version.map(str::trim).filter(|value| !value.is_empty())
        && !version.contains("${")
    {
        item.version = Some(version.to_string());
    }
    if let Some(description) = description.map(str::trim).filter(|value| !value.is_empty()) {
        item.description = Some(truncate(description, 400));
    }
}

fn parse_forge_mods(bytes: &[u8]) -> Option<(String, Option<String>, Option<String>)> {
    let text = String::from_utf8_lossy(bytes);
    let value: toml::Value = toml::from_str(&text).ok()?;
    let mods = value.get("mods")?.as_array()?;
    let first = mods.first()?.as_table()?;
    let title = first
        .get("displayName")
        .and_then(toml::Value::as_str)
        .or_else(|| first.get("modId").and_then(toml::Value::as_str))?
        .to_string();
    let version = first
        .get("version")
        .and_then(toml::Value::as_str)
        .map(ToString::to_string);
    let description = first
        .get("description")
        .and_then(toml::Value::as_str)
        .map(ToString::to_string);
    Some((title, version, description))
}

fn parse_mcmod_info(bytes: &[u8]) -> Option<(String, Option<String>, Option<String>)> {
    let value: serde_json::Value = serde_json::from_slice(bytes).ok()?;
    let first = value
        .get("modList")
        .and_then(serde_json::Value::as_array)
        .or_else(|| value.as_array())?
        .first()?;
    let title = first
        .get("name")
        .and_then(serde_json::Value::as_str)
        .or_else(|| first.get("modid").and_then(serde_json::Value::as_str))?
        .to_string();
    let version = first
        .get("version")
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string);
    let description = first
        .get("description")
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string);
    Some((title, version, description))
}

fn open_archive(path: &Path) -> Option<ZipArchive<File>> {
    let file = File::open(path).ok()?;
    ZipArchive::new(file).ok()
}

fn read_entry<R: Read + std::io::Seek>(
    archive: &mut ZipArchive<R>,
    name: &str,
    limit: u64,
) -> Option<Vec<u8>> {
    let entry = archive.by_name(name).ok()?;
    if entry.size() > limit {
        tracing::warn!(
            entry = name,
            size = entry.size(),
            "content metadata is too large"
        );
        return None;
    }
    let mut bytes = Vec::with_capacity(usize::try_from(entry.size()).unwrap_or(0));
    entry.take(limit).read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

fn read_small_file(path: &Path) -> Option<Vec<u8>> {
    let metadata = fs::metadata(path).ok()?;
    if metadata.len() > MAX_METADATA_BYTES {
        return None;
    }
    fs::read(path).ok()
}

fn fallback_title(display_name: &str) -> String {
    let path = Path::new(display_name);
    path.file_stem()
        .map(|stem| stem.to_string_lossy().trim().to_string())
        .filter(|stem| !stem.is_empty())
        .unwrap_or_else(|| display_name.to_string())
}

/// Converts a JSON text component to plain text.
fn json_text(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(text) => text.clone(),
        serde_json::Value::Array(parts) => parts.iter().map(json_text).collect::<Vec<_>>().join(""),
        serde_json::Value::Object(object) => {
            let mut text = object.get("text").map(json_text).unwrap_or_default();
            if let Some(extra) = object.get("extra") {
                text.push_str(&json_text(extra));
            }
            if text.is_empty()
                && let Some(translate) = object.get("translate").and_then(|value| value.as_str())
            {
                text.push_str(translate);
            }
            text
        }
        _ => String::new(),
    }
}

fn truncate(value: &str, max_chars: usize) -> String {
    if value.chars().count() <= max_chars {
        return value.to_string();
    }
    let mut truncated: String = value.chars().take(max_chars).collect();
    truncated.push('…');
    truncated
}

fn to_epoch_ms(time: SystemTime) -> Option<i64> {
    time.duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

fn directory_size(directory: &Path) -> u64 {
    let mut total = 0_u64;
    let Ok(entries) = fs::read_dir(directory) else {
        return 0;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(metadata) = fs::metadata(&path) else {
            continue;
        };
        if metadata.is_dir() {
            total = total.saturating_add(directory_size(&path));
        } else {
            total = total.saturating_add(metadata.len());
        }
    }
    total
}

#[cfg(test)]
mod tests {
    use std::io::Write;

    use zip::write::SimpleFileOptions;

    use super::*;

    fn write_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let file = File::create(path).expect("zip file");
        let mut writer = zip::ZipWriter::new(file);
        for (name, bytes) in entries {
            writer
                .start_file(*name, SimpleFileOptions::default())
                .expect("start file");
            writer.write_all(bytes).expect("write entry");
        }
        writer.finish().expect("finish zip");
    }

    #[test]
    fn fabric_metadata_is_read_and_disabled_suffix_is_transparent() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mods = temp.path().join("mods");
        fs::create_dir_all(&mods).expect("mods");
        write_zip(
            &mods.join("sodium.jar.disabled"),
            &[(
                "fabric.mod.json",
                br#"{"id":"sodium","name":"Sodium","version":"0.6.13","description":"Faster rendering"}"#,
            )],
        );

        let items = scan_local(temp.path(), ContentKind::Mod, None).expect("scan");
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "Sodium");
        assert_eq!(items[0].version.as_deref(), Some("0.6.13"));
        assert_eq!(items[0].loader.as_deref(), Some("fabric"));
        assert!(!items[0].enabled);
        assert_eq!(items[0].display_name, "sodium.jar");

        let renamed = set_enabled(temp.path(), ContentKind::Mod, "sodium.jar.disabled", true)
            .expect("enable");
        assert_eq!(renamed, "sodium.jar");
        assert!(mods.join("sodium.jar").is_file());

        let renamed =
            set_enabled(temp.path(), ContentKind::Mod, "sodium.jar", false).expect("disable");
        assert_eq!(renamed, "sodium.jar.disabled");
        assert!(mods.join("sodium.jar.disabled").is_file());
    }

    #[test]
    fn resource_pack_meta_and_description_component_are_parsed() {
        let temp = tempfile::tempdir().expect("tempdir");
        let packs = temp.path().join("resourcepacks");
        fs::create_dir_all(&packs).expect("packs");
        write_zip(
            &packs.join("fograin.zip"),
            &[(
                "pack.mcmeta",
                br#"{"pack":{"pack_format":34,"description":{"text":"Fograin","extra":[{"text":" UI"}]}}}"#,
            )],
        );

        let items = scan_local(temp.path(), ContentKind::ResourcePack, None).expect("scan");
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].pack_format, Some(34));
        assert_eq!(items[0].description.as_deref(), Some("Fograin UI"));
        assert_eq!(items[0].title, "fograin");
    }

    #[test]
    fn forge_mods_toml_is_parsed() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mods = temp.path().join("mods");
        fs::create_dir_all(&mods).expect("mods");
        write_zip(
            &mods.join("jei.jar"),
            &[(
                "META-INF/mods.toml",
                br#"
modLoader="javafml"
[[mods]]
modId="jei"
version="15.0.0"
displayName="Just Enough Items"
description="View items and recipes"
"#,
            )],
        );

        let items = scan_local(temp.path(), ContentKind::Mod, None).expect("scan");
        assert_eq!(items[0].title, "Just Enough Items");
        assert_eq!(items[0].version.as_deref(), Some("15.0.0"));
        assert_eq!(items[0].loader.as_deref(), Some("forge"));
    }

    #[test]
    fn index_metadata_overrides_the_file_stem() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mods = temp.path().join("mods");
        fs::create_dir_all(&mods).expect("mods");
        write_zip(
            &mods.join("sodium-fabric-0.6.13.jar"),
            &[(
                "fabric.mod.json",
                br#"{"id":"sodium","name":"Sodium","version":"0.6.13"}"#,
            )],
        );
        let mut index = ContentIndex::default();
        index.upsert(IndexEntry {
            file_name: "sodium-fabric-0.6.13.jar".into(),
            kind: ContentKind::Mod,
            source: "modrinth".into(),
            project_id: "AANobbMI".into(),
            project_name: "Sodium".into(),
            version_id: "c3YkZvne".into(),
            version_number: "0.6.13".into(),
            sha1: None,
            installed_at_ms: 1,
        });
        let items = scan_local(temp.path(), ContentKind::Mod, Some(&index)).expect("scan");
        assert_eq!(items[0].source.as_deref(), Some("modrinth"));
        assert_eq!(items[0].project_id.as_deref(), Some("AANobbMI"));
    }

    #[test]
    fn deleting_a_content_file_removes_it() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mods = temp.path().join("mods");
        fs::create_dir_all(&mods).expect("mods");
        fs::write(mods.join("x.jar"), b"x").expect("file");
        delete_local(temp.path(), ContentKind::Mod, "x.jar").expect("delete");
        assert!(!mods.join("x.jar").exists());
        assert_eq!(
            delete_local(temp.path(), ContentKind::Mod, "missing.jar")
                .expect_err("missing")
                .kind,
            ErrorKind::NotFound
        );
    }

    #[test]
    fn traversal_names_are_rejected() {
        let temp = tempfile::tempdir().expect("tempdir");
        fs::create_dir_all(temp.path().join("mods")).expect("mods");
        assert_eq!(
            delete_local(temp.path(), ContentKind::Mod, "../instance.toml")
                .expect_err("traversal")
                .kind,
            ErrorKind::InvalidInput
        );
    }
}
