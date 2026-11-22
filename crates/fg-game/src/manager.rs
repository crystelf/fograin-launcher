//! Instance management: create, import, rename, favourite, settings writes
//!
//! This is the single read/write surface the FFI layer exposes for instances:
//! listing with computed presentation fields, wizard creation, rename /
//! favorite, settings writes that keep the inherit semantics of §5.2, external
//! `.minecraft` imports and deletion. `fg-cli` reuses it instead of duplicating
//! directory walking.

use std::path::{Path, PathBuf};

use fg_core::{Error, ErrorKind, GameConfig, Result};
use time::format_description::well_known::Rfc3339;

use crate::{
    instance::{Instance, InstanceOverrides},
    layout::{Layout, validate_id},
};

/// One entry of [`InstanceManager::list`].
#[derive(Debug, Clone, PartialEq)]
pub struct InstanceSummary {
    instance: Instance,
    size_bytes: u64,
    last_played_epoch_ms: Option<i64>,
    installed: bool,
}

impl InstanceSummary {
    #[must_use]
    pub fn instance(&self) -> &Instance {
        &self.instance
    }

    #[must_use]
    pub fn id(&self) -> &str {
        &self.instance.id
    }

    #[must_use]
    pub fn name(&self) -> &str {
        &self.instance.name
    }

    #[must_use]
    pub fn minecraft(&self) -> &str {
        &self.instance.minecraft
    }

    #[must_use]
    pub fn loader_kind(&self) -> &str {
        self.instance.loader.kind.as_str()
    }

    #[must_use]
    pub fn loader_version(&self) -> &str {
        &self.instance.loader.version
    }

    /// Concrete profile id installed for this instance.
    #[must_use]
    pub fn version_id(&self) -> &str {
        self.instance.version_id()
    }

    /// Whether the launcher finished installing this version's files.
    ///
    /// A version JSON is written before downloads start, so it cannot be used
    /// as the readiness signal: a failed or interrupted install would still
    /// look playable. Shared installs require the finalize marker
    /// (`fograin-install.json`); external imports check their own
    /// `versions/<id>` directory instead.
    #[must_use]
    pub fn installed(&self) -> bool {
        self.installed
    }

    #[must_use]
    pub fn size_bytes(&self) -> u64 {
        self.size_bytes
    }

    #[must_use]
    pub fn play_seconds(&self) -> u64 {
        self.instance.meta.play_seconds
    }

    #[must_use]
    pub fn favorite(&self) -> bool {
        self.instance.meta.favorite
    }

    /// Epoch milliseconds of `meta.last_played`.
    #[must_use]
    pub fn last_played_epoch_ms(&self) -> Option<i64> {
        self.last_played_epoch_ms
    }

    /// True while the instance override inherits `launcher.toml [game]`.
    #[must_use]
    pub fn inherits(&self, setting: InheritableSetting) -> bool {
        let overrides = &self.instance.overrides;
        match setting {
            InheritableSetting::Java => overrides.java.is_none(),
            InheritableSetting::Memory => overrides.memory_mb.is_none(),
            InheritableSetting::JvmArgs => overrides.jvm_args.is_none(),
            InheritableSetting::Resolution => overrides.resolution.is_none(),
            InheritableSetting::Fullscreen => overrides.fullscreen.is_none(),
        }
    }

    /// Effective scalar value when it is not inherited.
    #[must_use]
    pub fn effective(&self, setting: InheritableSetting) -> Option<SettingValue> {
        if self.inherits(setting) {
            return None;
        }
        let overrides = &self.instance.overrides;
        let value = match setting {
            InheritableSetting::Java => SettingValue::Text(
                overrides
                    .java
                    .as_deref()
                    .map(|path| path.display().to_string())
                    .unwrap_or_default(),
            ),
            InheritableSetting::Memory => {
                SettingValue::Memory(overrides.memory_mb.unwrap_or_default())
            }
            InheritableSetting::JvmArgs => {
                SettingValue::Text(overrides.jvm_args.clone().unwrap_or_default())
            }
            InheritableSetting::Resolution => {
                SettingValue::Resolution(overrides.resolution.unwrap_or_default())
            }
            InheritableSetting::Fullscreen => {
                SettingValue::Flag(overrides.fullscreen.unwrap_or_default())
            }
        };
        Some(value)
    }
}

/// Settings an instance may keep on its own or inherit from the global config.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InheritableSetting {
    Java,
    Memory,
    JvmArgs,
    Resolution,
    Fullscreen,
}

/// Effective scalar value when it is not inherited.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SettingValue {
    Text(String),
    Memory(u32),
    Resolution([u32; 2]),
    Flag(bool),
}

/// All instances under the data root plus the global defaults they inherit.
#[derive(Debug, Clone)]
pub struct InstanceManager {
    layout: Layout,
    globals: GameConfig,
}

impl InstanceManager {
    #[must_use]
    pub fn new(layout: Layout, globals: GameConfig) -> Self {
        Self { layout, globals }
    }

    #[must_use]
    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    #[must_use]
    pub fn globals(&self) -> &GameConfig {
        &self.globals
    }

    /// Loads every instance directory, sorted by `last_played` (newest first)
    /// with never-played instances last by name.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the instances directory cannot be read.
    pub fn list(&self) -> Result<Vec<InstanceSummary>> {
        let dir = self.layout.instances();
        std::fs::create_dir_all(&dir).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", dir.display()),
            )
        })?;

        let mut summaries = Vec::new();
        let entries = std::fs::read_dir(&dir).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.read",
                format!("{}: {error}", dir.display()),
            )
        })?;
        for entry in entries {
            let entry = entry.map_err(|error| {
                Error::with_detail(ErrorKind::Io, "error.io.read", error.to_string())
            })?;
            if !entry.file_type().map(|kind| kind.is_dir()).unwrap_or(false) {
                continue;
            }
            let id = entry.file_name().to_string_lossy().to_string();
            let Ok(instance) = Instance::load(&self.layout, &id) else {
                // Damaged or foreign directories show up as warnings, never as
                // hard failures: the UI must stay usable around them.
                continue;
            };
            let version_json_path = instance.version_json(&self.layout).filter(|path| {
                path.is_file() || self.layout.version_json(&instance.minecraft).is_file()
            });
            let version_id = instance.version_id();
            let installed = if instance.overrides.game_dir.is_some() {
                let version_dir = instance
                    .game_dir(&self.layout)
                    .join("versions")
                    .join(version_id);
                version_dir.join(format!("{version_id}.json")).is_file()
                    || version_dir.join(format!("{version_id}.jar")).is_file()
            } else {
                self.layout.install_meta(version_id).is_file()
            };
            let mut summary = InstanceSummary {
                instance: instance.clone(),
                size_bytes: directory_size(&instance.game_dir(&self.layout)),
                last_played_epoch_ms: parse_rfc3339_ms(&instance.meta.last_played),
                installed,
            };
            summary.instance.version_json_path = version_json_path;
            summaries.push(summary);
        }

        summaries.sort_by(|left, right| {
            let left_ms = left.last_played_epoch_ms.unwrap_or(i64::MIN);
            let right_ms = right.last_played_epoch_ms.unwrap_or(i64::MIN);
            right_ms
                .cmp(&left_ms)
                .then_with(|| left.id().cmp(right.id()))
        });
        Ok(summaries)
    }

    #[must_use]
    pub fn exists(&self, id: &str) -> bool {
        if !valid_instance_id(id) {
            return false;
        }
        self.layout.instance_file(id).is_file()
    }

    /// Loads one instance.
    ///
    /// # Errors
    ///
    /// Propagates [`Instance::load`].
    pub fn load(&self, id: &str) -> Result<Instance> {
        Instance::load(&self.layout, id)
    }

    /// Wizard step "版本与加载器": derives the game version from a version id.
    #[must_use]
    pub fn minecraft_from_version(&self, version_id: &str) -> Option<String> {
        let rest = version_id.strip_prefix("1.")?;
        let minor = rest.split('.').next()?.to_string();
        rest.starts_with(&minor).then(|| format!("1.{minor}"))
    }

    #[must_use]
    pub fn valid_instance_id(id: &str) -> bool {
        validate_id(id).is_ok() && path_len_ok(id)
    }

    /// Creates a new instance directory from wizard values.
    ///
    /// # Errors
    ///
    /// Reports [`ErrorKind::InvalidInput`] for empty ids or versions and
    /// [`ErrorKind::Conflict`] when the id is taken.
    pub fn create(
        &self,
        id: &str,
        name: &str,
        minecraft: &str,
        loader_kind: Option<&str>,
        loader_version: Option<&str>,
    ) -> Result<Instance> {
        validate_id(id)?;
        if !Self::valid_instance_id(id) {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.instance.invalid_id",
                format!("unsafe id {id:?}"),
            ));
        }
        if minecraft.trim().is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.instance.empty_version",
            ));
        }
        if self.layout.instance_file(id).is_file() {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.instance.duplicate",
                format!("instance {id:?} already exists"),
            ));
        }

        let name = if name.trim().is_empty() {
            id.to_string()
        } else {
            name.trim().to_string()
        };
        let mut instance = Instance {
            id: id.to_string(),
            name,
            minecraft: minecraft.trim().to_string(),
            ..Instance::default()
        };
        if let Some(kind) = loader_kind.map(str::trim).filter(|kind| !kind.is_empty()) {
            instance.loader.kind = kind.to_string();
        }
        if let Some(version) = loader_version
            .map(str::trim)
            .filter(|version| !version.is_empty())
        {
            instance.loader.version = version.to_string();
        }
        instance.create_layout(&self.layout)?;
        instance.save(&self.layout)?;
        Ok(instance)
    }

    /// Renames an instance in `instance.toml`.
    ///
    /// # Errors
    ///
    /// Propagates [`Instance::load`] and save errors.
    pub fn rename(&self, id: &str, name: &str) -> Result<Instance> {
        let name = name.trim();
        if name.is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.instance.rename_empty",
            ));
        }
        let mut instance = Instance::load(&self.layout, id)?;
        instance.name = name.to_string();
        instance.save(&self.layout)?;
        Ok(instance)
    }

    /// Records the exact loader profile after an install plan was built.
    ///
    /// # Errors
    ///
    /// Propagates [`Instance::load`] and save errors.
    pub fn set_loader(
        &self,
        id: &str,
        kind: &str,
        version: &str,
        version_id: &str,
    ) -> Result<Instance> {
        let mut instance = Instance::load(&self.layout, id)?;
        instance.set_loader(kind, version, version_id);
        instance.save(&self.layout)?;
        Ok(instance)
    }

    /// Toggles the favorite flag and returns the new state.
    ///
    /// # Errors
    ///
    /// Propagates [`Instance::load`] and save errors.
    pub fn set_favorite(&self, id: &str, favorite: bool) -> Result<bool> {
        let mut instance = Instance::load(&self.layout, id)?;
        instance.meta.favorite = favorite;
        instance.save(&self.layout)?;
        Ok(favorite)
    }

    /// Effective settings: instance overrides fall back to `[game]` defaults.
    #[must_use]
    pub fn effective_settings(&self, instance: &Instance) -> EffectiveSettings {
        EffectiveSettings {
            java: instance
                .overrides
                .java
                .clone()
                .or_else(|| self.globals.java.clone()),
            memory_mb: instance
                .overrides
                .memory_mb
                .unwrap_or(self.globals.memory_mb),
            jvm_args: instance
                .overrides
                .jvm_args
                .clone()
                .unwrap_or_else(|| self.globals.jvm_args.clone()),
            resolution: instance.overrides.resolution.or(self.globals.resolution),
            fullscreen: instance
                .overrides
                .fullscreen
                .unwrap_or(self.globals.fullscreen),
            show_log: self.globals.show_log,
            username: self.globals.username.clone(),
        }
    }

    #[must_use]
    pub fn effective_json(&self, instance: &Instance) -> serde_json::Value {
        let settings = self.effective_settings(instance);
        serde_json::json!({
            "java": settings.java.as_ref().map(|path| path.display().to_string()),
            "memoryMb": settings.memory_mb,
            "jvmArgs": settings.jvm_args,
            "resolution": settings.resolution,
            "fullscreen": settings.fullscreen,
            "showLog": settings.show_log,
            "username": self.globals.username,
        })
    }

    /// Writes one overridable setting; `None` clears the override so the value
    /// inherits the global default again (§5.2 `[override]`).
    ///
    /// # Errors
    ///
    /// Propagates [`Instance::load`] and save errors.
    pub fn write_setting(
        &self,
        id: &str,
        setting: InheritableSetting,
        value: SettingValue,
    ) -> Result<Instance> {
        let mut instance = Instance::load(&self.layout, id)?;
        let overrides = &mut instance.overrides;
        match (setting, value) {
            (InheritableSetting::Java, SettingValue::Text(path)) => {
                overrides.java = to_path(path);
            }
            (InheritableSetting::Memory, SettingValue::Memory(mb)) => {
                overrides.memory_mb = if mb == 0 { None } else { Some(mb.max(512)) };
            }
            (InheritableSetting::JvmArgs, SettingValue::Text(args)) => {
                overrides.jvm_args = if args.trim().is_empty() {
                    None
                } else {
                    Some(args)
                };
            }
            (InheritableSetting::Resolution, SettingValue::Resolution(size)) => {
                overrides.resolution = if size[0] == 0 || size[1] == 0 {
                    None
                } else {
                    Some([size[0].max(320), size[1].max(240)])
                };
            }
            (InheritableSetting::Fullscreen, SettingValue::Flag(flag)) => {
                overrides.fullscreen = if flag { Some(true) } else { None };
            }
            (setting, value) => {
                return Err(Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.instance.setting_mismatch",
                    format!("{setting:?} rejects {value:?}"),
                ));
            }
        }
        instance.save(&self.layout)?;
        Ok(instance)
    }

    /// Registers an external `.minecraft` directory without moving files (§5.1).
    ///
    /// The id is derived from the directory name; the directory itself stays
    /// read-write so existing worlds, mods and options keep working.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] for missing directories and
    /// [`ErrorKind::Conflict`] when the derived id is taken.
    pub fn import_external(
        &self,
        directory: &Path,
        name: Option<&str>,
        minecraft_hint: Option<&str>,
    ) -> Result<Instance> {
        if !directory.is_dir() {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.instance.import_missing",
                format!("{} is not a directory", directory.display()),
            ));
        }
        let directory = directory.canonicalize().map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.instance.import_unavailable",
                format!("{}: {error}", directory.display()),
            )
        })?;

        let version = detect_minecraft_version(&directory)
            .or_else(|| minecraft_hint.map(str::to_string))
            .unwrap_or_default();
        if version.is_empty() {
            return Err(Error::new(
                ErrorKind::NotFound,
                "error.instance.import_no_version",
            ));
        }

        let base = directory
            .file_name()
            .map(|name| name.to_string_lossy().to_string())
            .unwrap_or_else(|| "imported-instance".to_string());
        let id = unique_import_id(&base, |candidate| {
            self.layout.instance_file(candidate).exists()
        });
        if !Self::valid_instance_id(&id) {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.instance.invalid_id",
                format!("cannot derive a safe id from {base:?}"),
            ));
        }
        if self.layout.instance_file(&id).is_file() {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.instance.duplicate",
                format!("instance {id:?} already exists"),
            ));
        }

        let mut instance = Instance {
            id: id.clone(),
            name: name
                .map_or_else(|| base.clone(), |value| value.trim().to_string())
                .trim()
                .to_string(),
            minecraft: version,
            ..Instance::default()
        };
        instance.overrides.game_dir = Some(directory.clone());
        std::fs::create_dir_all(self.layout.instance_dir(&id)).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", self.layout.instance_dir(&id).display()),
            )
        })?;
        instance.save(&self.layout)?;
        Ok(instance)
    }

    /// Deletes the instance directory after removing its shared pointers.
    ///
    /// `shared/` stays intact by design: instance pointers and shared game
    /// installs are separate, so deleting an instance never uninstalls a
    /// version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when a directory cannot be removed.
    pub fn delete(&self, id: &str) -> Result<()> {
        Instance::load(&self.layout, id)?;
        let _ = InstancePlan::remove_pointer(&self.layout, id);
        let instance_dir = self.layout.instance_dir(id);
        std::fs::remove_dir_all(&instance_dir).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.instance.delete_failed",
                format!("{}: {error}", instance_dir.display()),
            )
        })
    }
}

/// Wizard-facing view of settings with inherit semantics.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EffectiveSettings {
    pub java: Option<PathBuf>,
    pub memory_mb: u32,
    pub jvm_args: String,
    pub resolution: Option<[u32; 2]>,
    pub fullscreen: bool,
    pub show_log: bool,
    pub username: String,
}

/// Launch-time conversion of overrides with `[game]` defaults applied.
#[must_use]
pub fn overrides_for_launch(instance: &Instance, globals: &GameConfig) -> InstanceOverrides {
    InstanceOverrides {
        java: instance
            .overrides
            .java
            .clone()
            .or_else(|| globals.java.clone()),
        memory_mb: if instance.overrides.memory_mb.is_none() && globals.memory_mb > 0 {
            Some(globals.memory_mb)
        } else {
            instance.overrides.memory_mb
        },
        jvm_args: instance.overrides.jvm_args.clone().or_else(|| {
            if globals.jvm_args.trim().is_empty() {
                None
            } else {
                Some(globals.jvm_args.clone())
            }
        }),
        resolution: instance.overrides.resolution.or(globals.resolution),
        fullscreen: Some(instance.overrides.fullscreen.unwrap_or(globals.fullscreen)),
        game_dir: instance.overrides.game_dir.clone(),
    }
}

/// Install-plan cache pointer written by the installer; removed on delete.
pub struct InstancePlan;

impl InstancePlan {
    pub fn pointer_path(layout: &Layout, instance_id: &str) -> PathBuf {
        layout
            .install_plans()
            .join(format!("instance-{instance_id}.pointer"))
    }

    pub fn remove_pointer(layout: &Layout, instance_id: &str) -> bool {
        std::fs::remove_file(Self::pointer_path(layout, instance_id)).is_ok()
    }
}

fn to_path(value: String) -> Option<PathBuf> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(PathBuf::from(trimmed))
    }
}

fn path_len_ok(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128
}

fn valid_instance_id(id: &str) -> bool {
    validate_id(id).is_ok() && path_len_ok(id)
}

fn directory_size(root: &Path) -> u64 {
    walk_size(root, 0)
}

fn walk_size(directory: &Path, depth: u8) -> u64 {
    if depth > 6 {
        return 0;
    }
    let Ok(entries) = std::fs::read_dir(directory) else {
        return 0;
    };
    let mut total = 0;
    for entry in entries.flatten() {
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if metadata.is_dir() {
            total += walk_size(&entry.path(), depth + 1);
        } else {
            total += metadata.len();
        }
    }
    total
}

fn parse_rfc3339_ms(value: &Option<String>) -> Option<i64> {
    let raw = value.as_deref()?;
    let datetime = time::OffsetDateTime::parse(raw, &Rfc3339).ok()?;
    const MILLIS_PER_NANOS: i128 = 1_000_000;
    let millis = datetime.unix_timestamp_nanos() / MILLIS_PER_NANOS;
    i64::try_from(millis).ok()
}

/// `1.<minor>` (or the snapshot id itself) based on the jar the directory last
/// ran, so imported `.minecraft` folders name the version they were played with.
fn detect_minecraft_version(directory: &Path) -> Option<String> {
    let launcher_file = directory.join("launcher_profiles.json");
    if launcher_file.is_file()
        && let Ok(text) = std::fs::read_to_string(&launcher_file)
        && let Ok(value) = serde_json::from_str::<serde_json::Value>(&text)
        && let Some(version) = value
            .pointer("/selectedProfile/lastVersionId")
            .and_then(serde_json::Value::as_str)
            .map(str::to_string)
    {
        return Some(version);
    }

    let options = directory.join("options.txt");
    if options.is_file() {
        // No version recorded in options.txt; leave the detection to the caller.
        return None;
    }
    None
}

fn unique_import_id(base: &str, taken: impl Fn(&str) -> bool) -> String {
    let sanitized: String = base
        .chars()
        .map(|character| match character {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' | '\0' | ' ' => '-',
            other => other,
        })
        .collect();
    let mut id = sanitized.clone();
    let mut suffix = 2_u32;
    while taken(&id) {
        id = format!("{sanitized}-{suffix}");
        suffix += 1;
    }
    id
}

impl Instance {
    fn version_json(&self, layout: &Layout) -> Option<PathBuf> {
        let path = layout.version_json(self.version_id());
        path.is_file().then_some(path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::instance::{DEFAULT_MEMORY_MB, INSTANCE_SCHEMA_VERSION};

    fn fixture() -> (tempfile::TempDir, InstanceManager) {
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        layout.ensure_base_dirs().expect("dirs");
        let globals = GameConfig {
            memory_mb: 2048,
            username: "Tester".to_string(),
            jvm_args: "-XX:+UseG1GC".to_string(),
            ..GameConfig::default()
        };
        (directory, InstanceManager::new(layout, globals))
    }

    #[test]
    fn create_list_round_trip() {
        let (_dir, manager) = fixture();
        let instance = manager
            .create(
                "wizard-1",
                "  雾林生存  ",
                "1.21.4",
                Some("vanilla"),
                Some(""),
            )
            .expect("create");
        assert_eq!(instance.loader.kind, "vanilla");
        assert_eq!(instance.loader.version, "");
        assert_eq!(instance.name, "雾林生存");

        let list = manager.list().expect("list");
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].id(), "wizard-1");
        assert!(list[0].inherits(InheritableSetting::Memory));
        assert!(!list[0].installed());

        assert!(!manager.exists("wizard-2"));
        assert!(manager.exists("wizard-1"));
    }

    #[test]
    fn installed_requires_the_finalize_marker() {
        let (_dir, manager) = fixture();
        manager
            .create("inst", "inst", "1.21.4", None, None)
            .expect("create");
        assert!(!manager.list().expect("list")[0].installed());

        std::fs::create_dir_all(manager.layout().version_dir("1.21.4")).expect("version dir");
        std::fs::write(manager.layout().install_meta("1.21.4"), b"{}").expect("meta");
        assert!(manager.list().expect("list")[0].installed());
    }

    #[test]
    fn installed_uses_the_loader_profile_id() {
        let (_dir, manager) = fixture();
        manager
            .create("inst", "inst", "1.21.4", Some("fabric"), Some("0.16.9"))
            .expect("create");
        manager
            .set_loader("inst", "fabric", "0.16.9", "fabric-loader-0.16.9-1.21.4")
            .expect("set loader");
        let summary = &manager.list().expect("list")[0];
        assert_eq!(summary.version_id(), "fabric-loader-0.16.9-1.21.4");
        assert!(!summary.installed());
        std::fs::create_dir_all(manager.layout().version_dir("fabric-loader-0.16.9-1.21.4"))
            .expect("version dir");
        std::fs::write(
            manager.layout().install_meta("fabric-loader-0.16.9-1.21.4"),
            b"{}",
        )
        .expect("meta");
        assert!(manager.list().expect("list")[0].installed());
    }

    #[test]
    fn duplicate_ids_are_rejected() {
        let (_dir, manager) = fixture();
        manager
            .create("dup", "one", "1.21.4", None, None)
            .expect("create");
        let error = manager
            .create("dup", "two", "1.21.4", None, None)
            .expect_err("conflict");
        assert_eq!(error.message_key, "error.instance.duplicate");
    }

    #[test]
    fn rename_and_favorite_persist() {
        let (_dir, manager) = fixture();
        manager
            .create("inst", "旧名字", "1.21.4", None, None)
            .expect("create");

        let renamed = manager.rename("inst", "  新名字  ").expect("rename");
        assert_eq!(renamed.name, "新名字");

        manager.set_favorite("inst", true).expect("favorite");
        let list = manager.list().expect("list");
        assert_eq!(list[0].name(), "新名字");
        assert!(list[0].favorite());
    }

    #[test]
    fn settings_inherit_and_override() {
        let (_dir, manager) = fixture();
        manager
            .create("inst", "inst", "1.21.4", None, None)
            .expect("create");
        let instance = manager.load("inst").expect("load");

        let effective = manager.effective_settings(&instance);
        assert_eq!(effective.memory_mb, 2048);
        assert_eq!(effective.jvm_args, "-XX:+UseG1GC");
        assert_eq!(effective.username, "Tester");

        let updated = manager
            .write_setting(
                "inst",
                InheritableSetting::Memory,
                SettingValue::Memory(8192),
            )
            .expect("write memory");
        assert_eq!(updated.memory_mb(), 8192);
        let summary = &manager.list().expect("list")[0];
        assert!(!summary.inherits(InheritableSetting::Memory));
        assert!(summary.inherits(InheritableSetting::JvmArgs));

        manager
            .write_setting("inst", InheritableSetting::Memory, SettingValue::Memory(0))
            .expect("reset memory");
        let instance = manager.load("inst").expect("load");
        assert!(
            instance.overrides.memory_mb.is_none() && instance.memory_mb() == DEFAULT_MEMORY_MB
        );
    }

    #[test]
    fn fullscreen_override_only_compresses_to_true() {
        let (_dir, manager) = fixture();
        manager
            .create("inst", "inst", "1.21.4", None, None)
            .expect("create");

        manager
            .write_setting(
                "inst",
                InheritableSetting::Fullscreen,
                SettingValue::Flag(false),
            )
            .expect("clear flag");
        assert!(
            manager
                .load("inst")
                .expect("load")
                .overrides
                .fullscreen
                .is_none()
        );

        manager
            .write_setting(
                "inst",
                InheritableSetting::Fullscreen,
                SettingValue::Flag(true),
            )
            .expect("set flag");
        assert_eq!(
            manager.load("inst").expect("load").overrides.fullscreen,
            Some(true)
        );
    }

    #[test]
    fn launch_overrides_fall_back_to_globals() {
        let (_dir, manager) = fixture();
        globals_override_case(&manager);
    }

    fn globals_override_case(manager: &InstanceManager) {
        manager
            .create("inst", "inst", "1.21.4", None, None)
            .expect("create");
        let instance = manager.load("inst").expect("load");
        let overrides = overrides_for_launch(&instance, manager.globals());
        assert_eq!(overrides.memory_mb, Some(2048));
        assert_eq!(overrides.jvm_args, Some("-XX:+UseG1GC".to_string()));
        assert_eq!(overrides.java, None);
    }

    #[test]
    fn import_and_delete_instances() {
        let (_dir, manager) = fixture();
        let external = _dir.path().join("external");
        std::fs::create_dir_all(&external).expect("external dir");
        std::fs::write(
            external.join("launcher_profiles.json"),
            r#"{"selectedProfile":{"lastVersionId":"1.20.1"}}"#,
        )
        .expect("profile json");

        let imported = manager
            .import_external(&external, Some("外部存档"), None)
            .expect("import");
        assert_eq!(imported.id, "external");
        assert_eq!(imported.minecraft, "1.20.1");
        assert_eq!(
            imported.game_dir(manager.layout()),
            external.canonicalize().expect("canonical")
        );

        let second = manager
            .import_external(&external, None, None)
            .expect("second import");
        assert_eq!(second.id, "external-2");

        let list = manager.list().expect("list");
        assert_eq!(list.len(), 2);

        manager.delete("external").expect("delete");
        assert!(manager.list().expect("list").len() == 1);
        assert!(!manager.layout().instance_dir("external").exists());
    }

    #[test]
    fn import_rejects_missing_directory() {
        let (_dir, manager) = fixture();
        let error = manager
            .import_external(&_dir.path().join("missing"), None, None)
            .expect_err("missing dir");
        assert_eq!(error.message_key, "error.instance.import_missing");
    }

    #[test]
    fn wizard_version_matches_instance_schema() {
        let (_dir, manager) = fixture();
        manager
            .create("v", "v", "1.21.4", None, None)
            .expect("create");
        let instance = manager.load("v").expect("load");
        assert_eq!(instance.schema_version, INSTANCE_SCHEMA_VERSION);
        assert_eq!(
            manager.minecraft_from_version("1.21.4"),
            Some("1.21".to_string())
        );
    }
}
