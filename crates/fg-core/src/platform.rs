use std::fmt;

use serde::{Deserialize, Serialize};

/// Visual skin selected by the host platform (§4.1 of `docs/design.md`).
///
/// The identifier is injected into the web view as
/// `<html data-platform="…">` before the first paint, and is also returned by
/// the `app_info` command.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum PlatformSkin {
    Macos,
    Win11,
    Gnome,
    Android,
}

impl PlatformSkin {
    /// Detects the skin for the platform this binary was compiled for.
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

    /// Stable identifier used in `data-platform` and on the IPC wire.
    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
            Self::Macos => "macos",
            Self::Win11 => "win11",
            Self::Gnome => "gnome",
            Self::Android => "android",
        }
    }

    /// Human-readable skin name shown on the M0 status screen.
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

impl fmt::Display for PlatformSkin {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.label())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_values_match_ids() {
        for skin in [
            PlatformSkin::Macos,
            PlatformSkin::Win11,
            PlatformSkin::Gnome,
            PlatformSkin::Android,
        ] {
            let json = serde_json::to_string(&skin).expect("skin should serialize");
            assert_eq!(json, format!("\"{}\"", skin.id()));
        }
    }

    #[test]
    fn detects_the_host_platform() {
        let expected = if cfg!(target_os = "macos") {
            PlatformSkin::Macos
        } else if cfg!(target_os = "windows") {
            PlatformSkin::Win11
        } else if cfg!(target_os = "android") {
            PlatformSkin::Android
        } else {
            PlatformSkin::Gnome
        };

        assert_eq!(PlatformSkin::detect(), expected);
    }
}
