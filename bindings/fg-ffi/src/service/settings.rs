use super::helpers::*;
use super::*;

impl Service {
    /// Manual camelCase mapping: `launcher.toml` keeps the snake_case keys of
    /// the other sections while the wire format stays camelCase.
    pub(super) fn game_config_value(&self) -> Result<Value> {
        let game = self.config()?.game;
        Ok(game_json(&game))
    }

    /// Launcher-wide settings owned by the shell (appearance, initial page,
    /// download engine and proxy). Game defaults stay on `gameConfig.*` and
    /// the Java policy on `java.policy.set`.
    pub(super) fn settings_value(&self) -> Result<Value> {
        Ok(settings_json(&self.config()?))
    }

    pub(super) async fn settings_set(&self, params: &Value) -> Result<Value> {
        let fields = params
            .as_object()
            .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "error.config.fields"))?;
        let mut config = self.config()?;
        for (key, value) in fields {
            match key.as_str() {
                "theme" => {
                    config.appearance.theme = parse_wire_theme(&as_string(value))?;
                }
                "language" => {
                    config.appearance.language =
                        fg_core::LanguagePreference::from_value(&as_string(value));
                }
                "accent" => config.appearance.accent = as_string(value),
                "initialPage" => {
                    config.general.initial_page = parse_initial_page(&as_string(value))?;
                }
                "download" => apply_download_settings(&mut config.download, value)?,
                "network" => apply_network_settings(&mut config.network, value)?,
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.config.unknown_field",
                        other.to_string(),
                    ));
                }
            }
        }
        config::write_config(&self.root, &config)?;
        // Re-read so the response carries the clamped/normalized values.
        let config = self.config()?;
        Ok(json!({
            "settings": settings_json(&config),
            "snapshot": crate::SettingsSnapshot::at(&self.root),
        }))
    }

    pub(super) async fn game_config_set(&self, params: &Value) -> Result<Value> {
        let fields = params
            .as_object()
            .ok_or_else(|| Error::new(ErrorKind::InvalidInput, "error.config.fields"))?;
        let mut config = self.config()?;
        for (key, value) in fields {
            match key.as_str() {
                "username" => config.game.username = as_string(value),
                "java" => {
                    config.game.java = if value.is_null() {
                        None
                    } else {
                        Some(PathBuf::from(as_string(value)))
                    };
                }
                "memoryMb" => config.game.memory_mb = as_u32(value),
                "showLog" => config.game.show_log = as_bool(value),
                "jvmArgs" => config.game.jvm_args = as_string(value),
                "fullscreen" => config.game.fullscreen = as_bool(value),
                "resolution" => {
                    config.game.resolution = if value.is_null() {
                        None
                    } else {
                        Some([as_u32(&value[0]), as_u32(&value[1])])
                    };
                }
                "autoCompleteResources" => config.game.auto_complete_resources = as_bool(value),
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.config.unknown_field",
                        other.to_string(),
                    ));
                }
            }
        }
        config::write_config(&self.root, &config)?;
        self.set_manager_globals(config.game.clone());
        self.game_config_value()
    }

    // ------------------------------------------------------------------
    // Accounts: offline + Yggdrasil / authlib-injector.
    // ------------------------------------------------------------------
}
