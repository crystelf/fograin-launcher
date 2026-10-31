use std::{
    fs,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};

use crate::error::{Error, ErrorKind, Result};

/// Current `launcher.toml` schema version. Bump it when the layout changes.
pub const SCHEMA_VERSION: u32 = 1;

/// How the launcher should choose its color theme (§4.1.1).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ThemePreference {
    #[default]
    System,
    Light,
    Dark,
}

impl ThemePreference {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }
}

/// The concrete theme after resolving [`ThemePreference::System`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ResolvedTheme {
    Light,
    Dark,
}

impl ResolvedTheme {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }

    #[must_use]
    pub const fn is_dark(self) -> bool {
        matches!(self, Self::Dark)
    }
}

/// Global launcher settings (`launcher.toml`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct LauncherConfig {
    pub schema_version: u32,
    pub appearance: AppearanceConfig,
}

impl Default for LauncherConfig {
    fn default() -> Self {
        Self::new()
    }
}

impl LauncherConfig {
    #[must_use]
    pub fn new() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            appearance: AppearanceConfig::default(),
        }
    }

    fn normalize(mut self) -> Self {
        // Schema v1 is current. Unknown future versions are left untouched for
        // the migration chain in §5.2, but missing/old versions are upgraded.
        if self.schema_version == 0 {
            self.schema_version = SCHEMA_VERSION;
        }
        self
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppearanceConfig {
    pub theme: ThemePreference,
}

/// Path of the global settings file inside the launcher data root.
#[must_use]
pub fn config_path(root: &Path) -> PathBuf {
    root.join("launcher.toml")
}

/// Reads `launcher.toml`; a missing file yields defaults.
pub fn read_config(root: &Path) -> Result<LauncherConfig> {
    let path = config_path(root);

    match fs::read_to_string(&path) {
        Ok(contents) => toml::from_str::<LauncherConfig>(&contents)
            .map(LauncherConfig::normalize)
            .map_err(|error| {
                Error::with_detail(
                    ErrorKind::Parse,
                    "error.config.parse",
                    format!("{}: {error}", path.display()),
                )
            }),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(LauncherConfig::new()),
        Err(error) => Err(Error::with_detail(
            ErrorKind::Io,
            "error.config.read",
            format!("{}: {error}", path.display()),
        )),
    }
}

/// Atomically writes `launcher.toml` through a temporary file and rename.
pub fn write_config(root: &Path, config: &LauncherConfig) -> Result<()> {
    fs::create_dir_all(root)?;

    let path = config_path(root);
    let temporary = root.join("launcher.toml.tmp");
    let contents = toml::to_string_pretty(config).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.config.serialize",
            error.to_string(),
        )
    })?;

    fs::write(&temporary, contents)?;
    fs::rename(&temporary, &path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.config.write",
            format!("{}: {error}", path.display()),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_root() -> PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};

        static NEXT: AtomicU64 = AtomicU64::new(0);
        let unique = NEXT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!(
            "fograin-config-test-{}-{}-{unique}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock after epoch")
                .as_nanos()
        ))
    }

    #[test]
    fn missing_file_uses_defaults() {
        let root = temp_root();
        let config = read_config(&root).expect("defaults");
        assert_eq!(config.schema_version, SCHEMA_VERSION);
        assert_eq!(config.appearance.theme, ThemePreference::System);
    }

    #[test]
    fn round_trips_theme_through_toml() {
        let root = temp_root();
        let mut config = LauncherConfig::new();
        config.appearance.theme = ThemePreference::Light;

        write_config(&root, &config).expect("write config");
        let loaded = read_config(&root).expect("read config");
        assert_eq!(loaded, config);

        let text = fs::read_to_string(config_path(&root)).expect("read toml");
        assert!(text.contains("theme = \"light\""));

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn resets_a_zero_schema_version() {
        let root = temp_root();
        fs::create_dir_all(&root).expect("create root");
        fs::write(
            config_path(&root),
            "schema_version = 0\n[appearance]\ntheme = \"dark\"\n",
        )
        .expect("write config");

        let loaded = read_config(&root).expect("read config");
        assert_eq!(loaded.schema_version, SCHEMA_VERSION);
        assert_eq!(loaded.appearance.theme, ThemePreference::Dark);

        fs::remove_dir_all(root).expect("cleanup");
    }
}
