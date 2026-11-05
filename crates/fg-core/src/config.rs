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
    pub download: DownloadConfig,
    pub network: NetworkConfig,
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
            download: DownloadConfig::default(),
            network: NetworkConfig::default(),
        }
    }

    fn normalize(mut self) -> Self {
        // Schema v1 is current. Unknown future versions are left untouched for
        // the migration chain in §5.2, but missing/old versions are upgraded.
        if self.schema_version == 0 {
            self.schema_version = SCHEMA_VERSION;
        }
        self.download = self.download.clamped();
        self
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct AppearanceConfig {
    pub theme: ThemePreference,
}

/// Preferred source ordering for downloads (§6.1).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DownloadSource {
    /// Region-aware: mirrors first in mainland China, official first elsewhere.
    #[default]
    Auto,
    /// Official sources first, mirrors as the fallback.
    Official,
    /// Mirrors first, official as the fallback.
    Mirror,
}

impl DownloadSource {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Official => "official",
            Self::Mirror => "mirror",
        }
    }
}

/// `[download]` — engine limits and source preferences (§6.2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct DownloadConfig {
    /// Source preference for file downloads.
    pub source: DownloadSource,
    /// Source preference for version lists and other metadata.
    pub version_source: DownloadSource,
    /// Global concurrent connection cap. `0` derives `min(cpu * 4, 64)`.
    pub concurrency: u32,
    /// Per-host concurrent request cap.
    pub per_host: u32,
    /// Global bandwidth cap in KiB/s. `0` means unlimited.
    pub rate_limit_kib: u32,
    /// Parallel segments for large files. `1` disables segmented downloads.
    pub segments: u32,
    /// Files at least this large are split into segments. `0` disables splitting.
    pub segment_threshold_mb: u32,
    /// Attempts per candidate URL before moving to the next one.
    pub retries: u32,
}

impl Default for DownloadConfig {
    fn default() -> Self {
        Self {
            source: DownloadSource::Auto,
            version_source: DownloadSource::Auto,
            concurrency: 0,
            per_host: 32,
            rate_limit_kib: 0,
            segments: 4,
            segment_threshold_mb: 16,
            retries: 5,
        }
    }
}

impl DownloadConfig {
    /// Global concurrency limit, resolving the `0` (automatic) sentinel.
    #[must_use]
    pub fn effective_concurrency(&self) -> usize {
        if self.concurrency == 0 {
            let cpus = std::thread::available_parallelism().map_or(4, |value| value.get());
            (cpus * 4).clamp(4, 64)
        } else {
            self.concurrency.clamp(1, 128) as usize
        }
    }

    /// Bandwidth limit in bytes per second; `0` means unlimited.
    #[must_use]
    pub fn rate_limit_bytes(&self) -> u64 {
        u64::from(self.rate_limit_kib) * 1024
    }

    /// Segment count, clamped to the supported range.
    #[must_use]
    pub fn effective_segments(&self) -> usize {
        self.segments.clamp(1, 16) as usize
    }

    /// Byte threshold above which a file is fetched in segments.
    #[must_use]
    pub fn segment_threshold(&self) -> u64 {
        u64::from(self.segment_threshold_mb) * 1024 * 1024
    }

    fn clamped(mut self) -> Self {
        self.concurrency = self.concurrency.min(128);
        self.per_host = self.per_host.clamp(1, 128);
        self.retries = self.retries.clamp(1, 20);
        self.segments = self.segments.clamp(1, 16);
        self
    }
}

/// `[network]` — proxy and timeout settings.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NetworkConfig {
    /// `auto` (system/environment), `none`, or a proxy URL such as
    /// `http://127.0.0.1:7890` or `socks5://127.0.0.1:1080`.
    pub proxy: String,
    /// Connect and read timeout in seconds.
    pub timeout_secs: u32,
}

impl Default for NetworkConfig {
    fn default() -> Self {
        Self {
            proxy: "auto".to_string(),
            timeout_secs: 10,
        }
    }
}

impl NetworkConfig {
    /// Timeout as a duration, clamped to a sane range.
    #[must_use]
    pub fn timeout(&self) -> std::time::Duration {
        std::time::Duration::from_secs(u64::from(self.timeout_secs.clamp(1, 300)))
    }
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

    #[test]
    fn download_and_network_sections_use_documented_defaults() {
        let config = LauncherConfig::new();

        assert_eq!(config.download.source, DownloadSource::Auto);
        assert_eq!(config.download.version_source, DownloadSource::Auto);
        assert_eq!(config.download.per_host, 32);
        assert_eq!(config.download.segments, 4);
        assert_eq!(config.download.segment_threshold_mb, 16);
        assert_eq!(config.download.retries, 5);
        assert_eq!(config.download.rate_limit_bytes(), 0);
        assert!(config.download.effective_concurrency() >= 4);
        assert_eq!(config.network.proxy, "auto");
        assert_eq!(config.network.timeout().as_secs(), 10);
    }

    #[test]
    fn parses_download_and_network_overrides() {
        let root = temp_root();
        fs::create_dir_all(&root).expect("create root");
        fs::write(
            config_path(&root),
            r#"
[download]
source = "mirror"
version_source = "official"
concurrency = 24
per_host = 8
rate_limit_kib = 4096
segments = 2
segment_threshold_mb = 0
retries = 3

[network]
proxy = "socks5://127.0.0.1:1080"
timeout_secs = 20
"#,
        )
        .expect("write config");

        let loaded = read_config(&root).expect("read config");
        assert_eq!(loaded.download.source, DownloadSource::Mirror);
        assert_eq!(loaded.download.version_source, DownloadSource::Official);
        assert_eq!(loaded.download.effective_concurrency(), 24);
        assert_eq!(loaded.download.effective_segments(), 2);
        assert_eq!(loaded.download.segment_threshold(), 0);
        assert_eq!(loaded.download.rate_limit_bytes(), 4 * 1024 * 1024);
        assert_eq!(loaded.network.proxy, "socks5://127.0.0.1:1080");
        assert_eq!(loaded.network.timeout().as_secs(), 20);

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn clamps_engine_limits_to_supported_ranges() {
        let root = temp_root();
        fs::create_dir_all(&root).expect("create root");
        fs::write(
            config_path(&root),
            "[download]\nconcurrency = 4096\nper_host = 0\nretries = 0\nsegments = 99\n",
        )
        .expect("write config");

        let loaded = read_config(&root).expect("read config");
        assert_eq!(loaded.download.concurrency, 128);
        assert_eq!(loaded.download.effective_concurrency(), 128);
        assert_eq!(loaded.download.per_host, 1);
        assert_eq!(loaded.download.retries, 1);
        assert_eq!(loaded.download.effective_segments(), 16);

        fs::remove_dir_all(root).expect("cleanup");
    }
}
