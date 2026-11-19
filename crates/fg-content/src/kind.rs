//! Content kinds the launcher can browse and install.
//!
//! `Mod`, `ResourcePack` and `ShaderPack` live in the instance game directory.
//! `Datapack` is installed into one world and `Modpack` is a whole instance, so
//! the two are browsable but are not part of the local content scan.

use std::path::Path;

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

/// One browsable/installable content family.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ContentKind {
    Mod,
    Modpack,
    ResourcePack,
    ShaderPack,
    Datapack,
}

impl ContentKind {
    /// Kinds scanned inside an instance's game directory.
    pub const ALL: [Self; 3] = [Self::Mod, Self::ResourcePack, Self::ShaderPack];

    /// Every kind the downloads page can browse, in display order.
    pub const BROWSABLE: [Self; 5] = [
        Self::Mod,
        Self::Modpack,
        Self::ResourcePack,
        Self::ShaderPack,
        Self::Datapack,
    ];

    /// Parses the wire value used by the CLI and the FFI contract.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unknown kind.
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim().to_ascii_lowercase().as_str() {
            "mod" | "mods" => Ok(Self::Mod),
            "modpack" | "modpacks" | "mod_pack" | "modpack_zip" => Ok(Self::Modpack),
            "datapack" | "datapacks" | "data_pack" | "data_packs" => Ok(Self::Datapack),
            "resourcepack" | "resource_pack" | "resourcepacks" => Ok(Self::ResourcePack),
            "shaderpack" | "shader_pack" | "shaderpacks" | "shader" | "shaders" => {
                Ok(Self::ShaderPack)
            }
            other => Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.content.kind",
                format!("unknown content kind {other:?}"),
            )),
        }
    }

    /// Stable wire/JSON value.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::Modpack => "modpack",
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shaderpack",
            Self::Datapack => "datapack",
        }
    }

    /// Minecraft directory under the game directory.
    #[must_use]
    pub const fn folder(self) -> &'static str {
        match self {
            Self::Mod => "mods",
            Self::Modpack => "modpacks",
            Self::ResourcePack => "resourcepacks",
            Self::ShaderPack => "shaderpacks",
            Self::Datapack => "datapacks",
        }
    }

    /// Modrinth `project_type` facet.
    #[must_use]
    pub const fn modrinth_project_type(self) -> &'static str {
        match self {
            Self::Mod => "mod",
            Self::Modpack => "modpack",
            Self::ResourcePack => "resourcepack",
            Self::ShaderPack => "shader",
            Self::Datapack => "datapack",
        }
    }

    /// CurseForge class id (`gameId = 432`, Minecraft).
    #[must_use]
    pub const fn curseforge_class_id(self) -> u32 {
        match self {
            Self::Mod => 6,
            Self::Modpack => 4471,
            Self::ResourcePack => 12,
            Self::ShaderPack => 6552,
            Self::Datapack => 6945,
        }
    }

    /// Whether a loader filter applies (mods and packs target one loader).
    #[must_use]
    pub const fn loader_specific(self) -> bool {
        matches!(self, Self::Mod | Self::Modpack)
    }

    /// Whether the kind is installed into one world instead of the game root.
    #[must_use]
    pub const fn world_scoped(self) -> bool {
        matches!(self, Self::Datapack)
    }

    /// Whether the local content scan can see this kind.
    #[must_use]
    pub const fn locally_scanned(self) -> bool {
        matches!(self, Self::Mod | Self::ResourcePack | Self::ShaderPack)
    }

    /// Whether a file name is a candidate for this kind.
    ///
    /// Disabled files keep a `.disabled` suffix, so callers should strip it
    /// with [`strip_disabled`] first; this helper does it for convenience.
    #[must_use]
    pub fn accepts_name(self, name: &str) -> bool {
        let (_, name) = strip_disabled(name);
        match self {
            Self::Mod => name.to_ascii_lowercase().ends_with(".jar"),
            Self::Modpack => {
                let lower = name.to_ascii_lowercase();
                lower.ends_with(".mrpack") || lower.ends_with(".zip")
            }
            Self::ResourcePack | Self::ShaderPack | Self::Datapack => {
                name.to_ascii_lowercase().ends_with(".zip")
            }
        }
    }

    /// Whether a directory can itself be a resource or shader pack.
    #[must_use]
    pub const fn accepts_directory(self) -> bool {
        matches!(self, Self::ResourcePack | Self::ShaderPack)
    }

    /// Game directory of this kind.
    #[must_use]
    pub fn directory(self, game_dir: &Path) -> std::path::PathBuf {
        game_dir.join(self.folder())
    }
}

/// Splits the optional `.disabled` suffix used by HMCL/PCL/Prism.
#[must_use]
pub fn strip_disabled(name: &str) -> (bool, &str) {
    match name.strip_suffix(".disabled") {
        Some(stripped) => (true, stripped),
        None => (false, name),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_every_supported_alias() {
        for (value, kind) in [
            ("mod", ContentKind::Mod),
            ("MODS", ContentKind::Mod),
            ("resource_pack", ContentKind::ResourcePack),
            ("resourcepack", ContentKind::ResourcePack),
            ("shader", ContentKind::ShaderPack),
            ("shaderpacks", ContentKind::ShaderPack),
            ("modpack", ContentKind::Modpack),
            ("data_packs", ContentKind::Datapack),
        ] {
            assert_eq!(ContentKind::parse(value).expect("kind"), kind);
        }
        assert_eq!(
            ContentKind::parse("schematic").expect_err("removed").kind,
            ErrorKind::InvalidInput
        );
    }

    #[test]
    fn disabled_suffix_is_transparent() {
        assert_eq!(strip_disabled("sodium.jar.disabled"), (true, "sodium.jar"));
        assert_eq!(strip_disabled("sodium.jar"), (false, "sodium.jar"));
        assert!(ContentKind::Mod.accepts_name("sodium.jar.disabled"));
        assert!(ContentKind::ResourcePack.accepts_name("pack.zip.disabled"));
        assert!(!ContentKind::Mod.accepts_name("pack.zip"));
        assert!(ContentKind::Datapack.accepts_name("terralith.zip"));
        assert!(ContentKind::Modpack.accepts_name("calm.mrpack"));
        assert!(ContentKind::ShaderPack.accepts_directory());
        assert!(!ContentKind::Mod.accepts_directory());
        assert!(!ContentKind::Datapack.accepts_directory());
    }

    #[test]
    fn modrinth_and_curseforge_ids_match_the_documented_apis() {
        assert_eq!(ContentKind::Mod.modrinth_project_type(), "mod");
        assert_eq!(ContentKind::Modpack.modrinth_project_type(), "modpack");
        assert_eq!(ContentKind::ResourcePack.curseforge_class_id(), 12);
        assert_eq!(ContentKind::ShaderPack.curseforge_class_id(), 6552);
        assert_eq!(ContentKind::Datapack.curseforge_class_id(), 6945);
    }
}
