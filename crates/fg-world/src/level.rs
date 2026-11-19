//! `level.dat` summary for the world list.
//!
//! Only the handful of fields the UI shows are read; the file is a gzipped NBT
//! compound whose `Data` child carries the level metadata.

use std::path::Path;

use serde::Serialize;

use crate::nbt::{self, Nbt};

/// Friendly world metadata.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LevelInfo {
    pub name: Option<String>,
    pub version: Option<String>,
    /// `survival`, `creative`, `adventure`, `spectator` or `unknown`.
    pub game_mode: Option<String>,
    pub hardcore: bool,
    pub seed: Option<i64>,
    /// Epoch milliseconds (`LastPlayed` in `level.dat`).
    pub last_played_ms: Option<i64>,
}

/// Reads `saves/<world>/level.dat`, returning `None` when it is absent.
#[must_use]
pub fn read_level_info(world_dir: &Path) -> Option<LevelInfo> {
    let root = nbt::read_gzip(&world_dir.join("level.dat")).ok()?;
    let data = root.get("Data")?;
    let mut info = LevelInfo {
        name: data.string("LevelName").map(str::to_string),
        version: data
            .get("Version")
            .and_then(|version| version.string("Name"))
            .map(str::to_string),
        game_mode: data.get("GameType").and_then(|value| match value {
            Nbt::Int(mode) => Some(game_mode_name(*mode)),
            Nbt::Byte(mode) => Some(game_mode_name(i32::from(*mode))),
            _ => None,
        }),
        hardcore: matches!(data.get("hardcore"), Some(Nbt::Byte(1))),
        seed: match data.get("RandomSeed") {
            Some(Nbt::Long(seed)) => Some(*seed),
            _ => None,
        },
        last_played_ms: match data.get("LastPlayed") {
            Some(Nbt::Long(value)) => Some(*value),
            _ => None,
        },
    };
    if info.name.as_deref().is_some_and(str::is_empty) {
        info.name = None;
    }
    Some(info)
}

fn game_mode_name(mode: i32) -> String {
    match mode {
        0 => "survival",
        1 => "creative",
        2 => "adventure",
        3 => "spectator",
        _ => "unknown",
    }
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nbt::write_gzip;
    use std::collections::BTreeMap;

    #[test]
    fn reads_the_level_summary() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut data = BTreeMap::new();
        data.insert("LevelName".to_string(), Nbt::String("雾林世界".to_string()));
        data.insert("GameType".to_string(), Nbt::Int(1));
        data.insert("hardcore".to_string(), Nbt::Byte(0));
        data.insert("RandomSeed".to_string(), Nbt::Long(-42));
        data.insert("LastPlayed".to_string(), Nbt::Long(1_793_000_000_000));
        let mut version = BTreeMap::new();
        version.insert("Name".to_string(), Nbt::String("1.21.4".to_string()));
        data.insert("Version".to_string(), Nbt::Compound(version));
        let mut root = BTreeMap::new();
        root.insert("Data".to_string(), Nbt::Compound(data));
        write_gzip(&temp.path().join("level.dat"), &Nbt::Compound(root)).expect("write");

        let info = read_level_info(temp.path()).expect("level info");
        assert_eq!(info.name.as_deref(), Some("雾林世界"));
        assert_eq!(info.game_mode.as_deref(), Some("creative"));
        assert_eq!(info.version.as_deref(), Some("1.21.4"));
        assert_eq!(info.seed, Some(-42));
        assert!(!info.hardcore);
        assert_eq!(info.last_played_ms, Some(1_793_000_000_000));
    }

    #[test]
    fn a_missing_level_dat_is_none() {
        let temp = tempfile::tempdir().expect("tempdir");
        assert!(read_level_info(temp.path()).is_none());
    }
}
