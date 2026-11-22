//! Loader families and their naming rules.
//!
//! `LoaderKind` is the wire value used by `instance.toml` and the FFI layer, so
//! it deliberately stays independent of any UI enum.

use std::fmt;

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

/// Maven host used for loader artifacts that do not carry an explicit URL.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoaderKind {
    Fabric,
    Quilt,
    Forge,
    NeoForge,
    OptiFine,
}

impl LoaderKind {
    /// Parses a wire value: `fabric`, `quilt`, `forge`, `neoforge`, `optifine`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for unknown or vanilla values.
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "fabric" => Ok(Self::Fabric),
            "quilt" => Ok(Self::Quilt),
            "forge" => Ok(Self::Forge),
            "neoforge" | "neo_forge" => Ok(Self::NeoForge),
            "optifine" | "opti_fine" => Ok(Self::OptiFine),
            "vanilla" | "" => Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.loader.unknown_kind",
                "vanilla is not an installable loader",
            )),
            other => Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.loader.unknown_kind",
                other.to_string(),
            )),
        }
    }

    /// Stable lower-case wire value.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Fabric => "fabric",
            Self::Quilt => "quilt",
            Self::Forge => "forge",
            Self::NeoForge => "neoforge",
            Self::OptiFine => "optifine",
        }
    }

    /// Human-readable title used in CLI output.
    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Fabric => "Fabric",
            Self::Quilt => "Quilt",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
            Self::OptiFine => "OptiFine",
        }
    }

    /// Whether the installer runs Java processors or patches the client jar.
    #[must_use]
    pub const fn requires_java(self) -> bool {
        matches!(self, Self::Forge | Self::NeoForge | Self::OptiFine)
    }

    /// Default Maven base for artifacts without an explicit `url`.
    #[must_use]
    pub const fn maven_base(self) -> &'static str {
        match self {
            Self::Fabric => "https://maven.fabricmc.net/",
            Self::Quilt => "https://maven.quiltmc.org/repository/release/",
            Self::Forge => "https://maven.minecraftforge.net/",
            Self::NeoForge => "https://maven.neoforged.net/releases/",
            Self::OptiFine => "https://maven.minecraftforge.net/",
        }
    }

    /// Concrete version id written under `shared/versions/<id>`.
    ///
    /// Fabric/Quilt profiles already carry their canonical id; Forge and
    /// NeoForge use `<minecraft>-<kind>-<loader>`, OptiFine follows the
    /// `1.20.1-OptiFine_HD_U_I6` convention used by HMCL/PCL.
    #[must_use]
    pub fn version_id(self, minecraft: &str, loader_version: &str) -> String {
        match self {
            Self::Fabric => format!("fabric-loader-{loader_version}-{minecraft}"),
            Self::Quilt => format!("quilt-loader-{loader_version}-{minecraft}"),
            Self::Forge => format!("{minecraft}-forge-{loader_version}"),
            Self::NeoForge => format!("{minecraft}-neoforge-{loader_version}"),
            Self::OptiFine => format!("{minecraft}-OptiFine_{}", loader_version.replace(' ', "_")),
        }
    }
}

impl fmt::Display for LoaderKind {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.title())
    }
}

impl Serialize for LoaderKind {
    fn serialize<S: serde::Serializer>(
        &self,
        serializer: S,
    ) -> std::result::Result<S::Ok, S::Error> {
        serializer.serialize_str(self.as_str())
    }
}

impl<'de> Deserialize<'de> for LoaderKind {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        let raw = String::deserialize(deserializer)?;
        Self::parse(&raw).map_err(serde::de::Error::custom)
    }
}

/// Version ids end up in paths; reject separators and control characters.
#[must_use]
pub fn is_safe_version_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 160
        && !value.contains('/')
        && !value.contains('\\')
        && !value.contains('\0')
        && value.chars().all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-' | '+')
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_wire_values() {
        assert_eq!(
            LoaderKind::parse("Fabric").expect("fabric"),
            LoaderKind::Fabric
        );
        assert_eq!(
            LoaderKind::parse("neo_forge").expect("neoforge"),
            LoaderKind::NeoForge
        );
        assert_eq!(
            LoaderKind::parse("optifine").expect("optifine"),
            LoaderKind::OptiFine
        );
        assert_eq!(
            LoaderKind::parse("vanilla")
                .expect_err("vanilla")
                .message_key,
            "error.loader.unknown_kind"
        );
    }

    #[test]
    fn builds_stable_version_ids() {
        assert_eq!(
            LoaderKind::Fabric.version_id("1.21.4", "0.16.9"),
            "fabric-loader-0.16.9-1.21.4"
        );
        assert_eq!(
            LoaderKind::Forge.version_id("1.20.1", "47.4.29"),
            "1.20.1-forge-47.4.29"
        );
        assert_eq!(
            LoaderKind::OptiFine.version_id("1.20.1", "HD_U_I6"),
            "1.20.1-OptiFine_HD_U_I6"
        );
        assert!(is_safe_version_id(
            &LoaderKind::Forge.version_id("1.20.1", "47.4.29")
        ));
        assert!(!is_safe_version_id("../escape"));
    }
}
