use std::fmt;

use serde::{Deserialize, Serialize};

/// Host platform selected at compile time.
///
/// The identifier is used by configuration, FFI callers and diagnostics. It no
/// longer selects a CSS skin; each platform ships its own native client.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Platform {
    Macos,
    Win11,
    Gnome,
    Android,
}

impl Platform {
    /// Detects the host platform this binary was compiled for.
    #[must_use]
    pub const fn detect() -> Self {
        #[cfg(target_os = "macos")]
        {
            Self::Macos
        }
        #[cfg(target_os = "windows")]
        {
            Self::Win11
        }
        #[cfg(target_os = "linux")]
        {
            Self::Gnome
        }
        #[cfg(target_os = "android")]
        {
            Self::Android
        }
        #[cfg(not(any(
            target_os = "macos",
            target_os = "windows",
            target_os = "linux",
            target_os = "android"
        )))]
        {
            Self::Gnome
        }
    }

    /// Stable identifier used on the FFI wire and in configuration.
    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
            Self::Macos => "macos",
            Self::Win11 => "win11",
            Self::Gnome => "gnome",
            Self::Android => "android",
        }
    }

    /// Human-readable platform name.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Macos => "macOS",
            Self::Win11 => "Windows 11",
            Self::Gnome => "GNOME",
            Self::Android => "Material 3",
        }
    }
}

impl fmt::Display for Platform {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_values_match_ids() {
        for platform in [
            Platform::Macos,
            Platform::Win11,
            Platform::Gnome,
            Platform::Android,
        ] {
            let json = serde_json::to_string(&platform).expect("platform should serialize");
            assert_eq!(json, format!("\"{}\"", platform.id()));
        }
    }

    #[test]
    fn detects_the_host_platform() {
        let expected = if cfg!(target_os = "macos") {
            Platform::Macos
        } else if cfg!(target_os = "windows") {
            Platform::Win11
        } else if cfg!(target_os = "android") {
            Platform::Android
        } else {
            Platform::Gnome
        };

        assert_eq!(Platform::detect(), expected);
    }
}
