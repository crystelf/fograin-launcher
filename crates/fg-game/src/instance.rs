//! Minimal instance metadata used by M3 install/launch.
//!
//! M4 adds the full wizard, settings inheritance and import flow. The file
//! already follows the shape in §5.2 (`[loader]`, `[override]`, `[meta]`) so M4
//! can extend it without a migration.

use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};
use time::{OffsetDateTime, format_description::well_known::Rfc3339};

use fg_core::{Error, ErrorKind, Result};

use crate::layout::{Layout, validate_id, write_atomic};
use crate::split_command_line;

/// `instance.toml` schema version.
pub const INSTANCE_SCHEMA_VERSION: u32 = 1;
/// Default heap for a new instance; the M4 wizard replaces this with the
/// automatic policy from §8.3.
pub const DEFAULT_MEMORY_MB: u32 = 4096;

/// `instances/<id>/instance.toml`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Instance {
    pub schema_version: u32,
    pub id: String,
    pub name: String,
    pub icon: Option<String>,
    pub minecraft: String,
    pub loader: Loader,
    #[serde(rename = "override")]
    pub overrides: InstanceOverrides,
    pub meta: InstanceMeta,
    /// Unknown keys are kept for M4/M5 loaders and future fields.
    #[serde(flatten)]
    pub extra: BTreeMap<String, toml::Value>,
}

impl Default for Instance {
    fn default() -> Self {
        Self {
            schema_version: INSTANCE_SCHEMA_VERSION,
            id: String::new(),
            name: String::new(),
            icon: None,
            minecraft: String::new(),
            loader: Loader::default(),
            overrides: InstanceOverrides::default(),
            meta: InstanceMeta::default(),
            extra: BTreeMap::new(),
        }
    }
}

/// Loader section. Only `vanilla` is installed in M3.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Loader {
    pub kind: String,
    pub version: String,
    pub optifine: String,
}

impl Default for Loader {
    fn default() -> Self {
        Self {
            kind: "vanilla".to_string(),
            version: String::new(),
            optifine: String::new(),
        }
    }
}

impl Loader {
    #[must_use]
    pub fn is_vanilla(&self) -> bool {
        self.kind.is_empty() || self.kind == "vanilla"
    }
}

/// Per-instance overrides; empty means "inherit the global setting" in M4.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct InstanceOverrides {
    pub java: Option<PathBuf>,
    pub memory_mb: Option<u32>,
    pub jvm_args: Option<String>,
    pub resolution: Option<[u32; 2]>,
    pub fullscreen: Option<bool>,
    /// Relative to the instance directory unless absolute; defaults to
    /// `.minecraft`.
    pub game_dir: Option<PathBuf>,
}

/// `[meta]`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct InstanceMeta {
    pub created: String,
    pub last_played: Option<String>,
    pub play_seconds: u64,
    pub favorite: bool,
}

impl Default for InstanceMeta {
    fn default() -> Self {
        Self {
            created: now_rfc3339(),
            last_played: None,
            play_seconds: 0,
            favorite: false,
        }
    }
}

impl InstanceMeta {
    /// Records a finished session.
    pub fn record_session(&mut self, seconds: u64) {
        self.play_seconds = self.play_seconds.saturating_add(seconds);
        self.last_played = Some(now_rfc3339());
    }
}

impl Instance {
    /// Creates a new vanilla instance directory and file.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unsafe id and
    /// [`ErrorKind::Io`] when directories or the file cannot be written.
    pub fn create(layout: &Layout, id: &str, minecraft: &str) -> Result<Self> {
        validate_id(id)?;
        if minecraft.trim().is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.instance.empty_version",
            ));
        }
        let instance = Self {
            id: id.to_string(),
            name: id.to_string(),
            minecraft: minecraft.to_string(),
            ..Self::default()
        };
        instance.create_layout(layout)?;
        instance.save(layout)?;
        Ok(instance)
    }

    /// Loads and validates an instance.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when `instance.toml` is missing and
    /// [`ErrorKind::Parse`] for malformed TOML.
    pub fn load(layout: &Layout, id: &str) -> Result<Self> {
        validate_id(id)?;
        let path = layout.instance_file(id);
        let text = std::fs::read_to_string(&path).map_err(|error| {
            if error.kind() == std::io::ErrorKind::NotFound {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.instance.not_found",
                    path.display().to_string(),
                )
            } else {
                Error::with_detail(ErrorKind::Io, "error.io.read", error.to_string())
            }
        })?;
        let instance: Self = toml::from_str(&text).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.instance.parse",
                format!("{}: {error}", path.display()),
            )
        })?;
        if instance.id != id {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.instance.id_mismatch",
                format!("file says {:?}, directory is {id:?}", instance.id),
            ));
        }
        Ok(instance)
    }

    /// Loads an existing instance or creates it from `minecraft`.
    ///
    /// # Errors
    ///
    /// Propagates [`Self::load`] and [`Self::create`] failures.
    pub fn load_or_create(layout: &Layout, id: &str, minecraft: &str) -> Result<Self> {
        if layout.instance_file(id).is_file() {
            Self::load(layout, id)
        } else {
            Self::create(layout, id, minecraft)
        }
    }

    /// Writes `instance.toml` atomically.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] on write failure and [`ErrorKind::Internal`]
    /// when TOML serialization fails.
    pub fn save(&self, layout: &Layout) -> Result<()> {
        let text = toml::to_string_pretty(self).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.instance.serialize",
                error.to_string(),
            )
        })?;
        write_atomic(&layout.instance_file(&self.id), text.as_bytes())
    }

    /// Creates the instance directory and the standard game subdirectories.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when a directory cannot be created.
    pub fn create_layout(&self, layout: &Layout) -> Result<()> {
        let directories = [
            layout.instance_dir(&self.id),
            self.game_dir(layout),
            self.game_dir(layout).join("mods"),
            self.game_dir(layout).join("resourcepacks"),
            self.game_dir(layout).join("shaderpacks"),
            self.game_dir(layout).join("saves"),
            self.game_dir(layout).join("schematics"),
            self.game_dir(layout).join("config"),
            self.game_dir(layout).join("logs"),
            self.game_dir(layout).join("crash-reports"),
        ];
        for directory in directories {
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

    /// Game working directory (`instances/<id>/.minecraft` by default).
    #[must_use]
    pub fn game_dir(&self, layout: &Layout) -> PathBuf {
        match self.overrides.game_dir.as_deref() {
            Some(path) if path.is_absolute() => path.to_path_buf(),
            Some(path) => layout.instance_dir(&self.id).join(path),
            None => layout.game_dir(&self.id),
        }
    }

    /// Heap size in MiB.
    #[must_use]
    pub fn memory_mb(&self) -> u32 {
        self.overrides
            .memory_mb
            .unwrap_or(DEFAULT_MEMORY_MB)
            .max(512)
    }

    /// Extra user JVM arguments, already split.
    #[must_use]
    pub fn jvm_args(&self) -> Vec<String> {
        self.overrides
            .jvm_args
            .as_deref()
            .map(split_command_line)
            .unwrap_or_default()
    }

    /// Preferred window size.
    #[must_use]
    pub fn resolution(&self) -> Option<(u32, u32)> {
        self.overrides
            .resolution
            .map(|[width, height]| (width.max(320), height.max(240)))
    }

    /// Explicit Java path chosen for this instance.
    #[must_use]
    pub fn java(&self) -> Option<&Path> {
        self.overrides.java.as_deref()
    }

    /// Updates play statistics after a session.
    pub fn record_session(&mut self, seconds: u64) {
        self.meta.record_session(seconds);
    }
}

fn now_rfc3339() -> String {
    OffsetDateTime::now_utc()
        .format(&Rfc3339)
        .unwrap_or_else(|_| "1970-01-01T00:00:00Z".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn layout() -> (tempfile::TempDir, Layout) {
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        (directory, layout)
    }

    #[test]
    fn creates_and_round_trips_an_instance() {
        let (_directory, layout) = layout();
        let mut instance = Instance::create(&layout, "1.21.4", "1.21.4").expect("create");
        assert!(layout.game_dir("1.21.4").is_dir());
        assert!(layout.game_dir("1.21.4").join("mods").is_dir());

        instance.overrides.memory_mb = Some(6144);
        instance.overrides.resolution = Some([1920, 1080]);
        instance.overrides.jvm_args = Some("-XX:+UseG1GC -XX:+AlwaysPreTouch".to_string());
        instance.record_session(90);
        instance.save(&layout).expect("save");

        let loaded = Instance::load(&layout, "1.21.4").expect("load");
        assert_eq!(loaded.memory_mb(), 6144);
        assert_eq!(loaded.resolution(), Some((1920, 1080)));
        assert_eq!(loaded.jvm_args(), ["-XX:+UseG1GC", "-XX:+AlwaysPreTouch"]);
        assert_eq!(loaded.meta.play_seconds, 90);
        assert!(loaded.meta.last_played.is_some());
        assert_eq!(loaded.loader.kind, "vanilla");
    }

    #[test]
    fn game_dir_can_be_overridden() {
        let (_directory, layout) = layout();
        let mut instance = Instance::create(&layout, "inst", "1.21.4").expect("create");
        instance.overrides.game_dir = Some(PathBuf::from("custom"));
        assert_eq!(
            instance.game_dir(&layout),
            layout.instance_dir("inst").join("custom")
        );
        instance.overrides.game_dir = Some(layout.root().join("absolute"));
        assert_eq!(instance.game_dir(&layout), layout.root().join("absolute"));
    }

    #[test]
    fn rejects_unsafe_ids() {
        let (_directory, layout) = layout();
        assert!(Instance::create(&layout, "../escape", "1.21.4").is_err());
        assert!(Instance::create(&layout, "", "1.21.4").is_err());
    }

    #[test]
    fn missing_instance_is_not_found() {
        let (_directory, layout) = layout();
        let error = Instance::load(&layout, "nope").expect_err("missing");
        assert_eq!(error.kind, ErrorKind::NotFound);
    }

    #[test]
    fn memory_has_a_floor() {
        let (_directory, layout) = layout();
        let mut instance = Instance::create(&layout, "inst", "1.21.4").expect("create");
        instance.overrides.memory_mb = Some(1);
        assert_eq!(instance.memory_mb(), 512);
    }
}
