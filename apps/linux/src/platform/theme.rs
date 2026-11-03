//! Appearance: reads `appearance.theme` from `launcher.toml` through `fg-core`
//! and applies it to `Adw.StyleManager` before the first frame.

use std::cell::Cell;
use std::path::PathBuf;

use fg_core::{ThemePreference, config, paths};

/// Combo-row labels, in [`ThemePreference`] order.
pub const LABELS: [&str; 3] = ["跟随系统", "浅色", "深色"];

pub const fn label(preference: ThemePreference) -> &'static str {
    match preference {
        ThemePreference::System => LABELS[0],
        ThemePreference::Light => LABELS[1],
        ThemePreference::Dark => LABELS[2],
    }
}

pub const fn index(preference: ThemePreference) -> u32 {
    match preference {
        ThemePreference::System => 0,
        ThemePreference::Light => 1,
        ThemePreference::Dark => 2,
    }
}

pub const fn from_index(index: u32) -> ThemePreference {
    match index {
        1 => ThemePreference::Light,
        2 => ThemePreference::Dark,
        _ => ThemePreference::System,
    }
}

pub fn from_id(id: &str) -> Option<ThemePreference> {
    match id {
        "system" => Some(ThemePreference::System),
        "light" => Some(ThemePreference::Light),
        "dark" => Some(ThemePreference::Dark),
        _ => None,
    }
}

/// Applies the preference to libadwaita. `Default` follows the desktop.
pub fn apply_color_scheme(preference: ThemePreference) {
    adw::StyleManager::default().set_color_scheme(match preference {
        ThemePreference::System => adw::ColorScheme::Default,
        ThemePreference::Light => adw::ColorScheme::ForceLight,
        ThemePreference::Dark => adw::ColorScheme::ForceDark,
    });
}

/// Reads the saved preference without touching GTK; used during startup.
pub fn saved_preference() -> ThemePreference {
    config::read_config(&paths::data_root())
        .map_or(ThemePreference::System, |saved| saved.appearance.theme)
}

/// Applies the persisted theme. Called from `startup`, before any window exists,
/// so the first frame already has the right appearance.
pub fn apply_saved() {
    apply_color_scheme(saved_preference());
}

/// Owns the current preference and writes changes back through `fg-core`.
pub struct ThemeController {
    root: PathBuf,
    preference: Cell<ThemePreference>,
}

impl ThemeController {
    pub fn load() -> Self {
        Self {
            root: paths::data_root(),
            preference: Cell::new(saved_preference()),
        }
    }

    pub fn preference(&self) -> ThemePreference {
        self.preference.get()
    }

    /// Applies the theme immediately, then persists it atomically.
    pub fn apply(&self, preference: ThemePreference) -> fg_core::Result<()> {
        apply_color_scheme(preference);
        self.preference.set(preference);
        let mut saved = config::read_config(&self.root)?;
        saved.appearance.theme = preference;
        config::write_config(&self.root, &saved)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn index_round_trips_every_preference() {
        for preference in [
            ThemePreference::System,
            ThemePreference::Light,
            ThemePreference::Dark,
        ] {
            assert_eq!(from_index(index(preference)), preference);
            assert_eq!(from_id(preference.as_str()), Some(preference));
        }
    }

    #[test]
    fn unknown_indexes_fall_back_to_system() {
        assert_eq!(from_index(99), ThemePreference::System);
        assert_eq!(from_id("sepia"), None);
    }
}
