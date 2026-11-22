use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn app_snapshot(&self) -> Result<Value> {
        let instances = self.instance_rows().await?;
        Ok(json!({
            "platform": fg_core::Platform::detect().id(),
            "appVersion": env!("CARGO_PKG_VERSION"),
            "dataRoot": self.root.display().to_string(),
            "javaCount": java_count(&self.layout),
            "activeTasks": self.tasks_list_len(),
            "diskFreeBytes": disk_free_bytes(&self.root),
            "totalMemoryBytes": total_memory_bytes(),
            "instanceCount": instances.len(),
        }))
    }

    pub(super) async fn instance_rows(&self) -> Result<Vec<InstanceRow>> {
        let rows = self
            .manager()
            .list()?
            .into_iter()
            .map(|summary| {
                let instance = summary.instance().clone();
                InstanceRow {
                    id: summary.id().to_string(),
                    name: summary.name().to_string(),
                    minecraft: summary.minecraft().to_string(),
                    loader_kind: summary.loader_kind().to_string(),
                    loader_version: summary.loader_version().to_string(),
                    favorite: summary.favorite(),
                    installed: summary.installed(),
                    mod_count: count_mods(&instance.game_dir(&self.layout)),
                    size_bytes: summary.size_bytes(),
                    play_seconds: summary.play_seconds(),
                    last_played_ms: summary.last_played_epoch_ms(),
                }
            })
            .collect();
        Ok(rows)
    }

    /// Home aggregate: recent list, favorites and totals (§13).
    pub(super) async fn instances_summary(&self) -> Result<Value> {
        let mut rows = self.instance_rows().await?;
        rows.sort_by(|a, b| {
            b.last_played_ms
                .unwrap_or(i64::MIN)
                .cmp(&a.last_played_ms.unwrap_or(i64::MIN))
                .then_with(|| a.id.cmp(&b.id))
        });
        let recent: Vec<InstanceRow> = rows.iter().take(6).cloned().collect();
        let favorites: Vec<InstanceRow> = rows.iter().filter(|row| row.favorite).cloned().collect();
        Ok(json!({
            "recent": recent,
            "favorites": favorites,
            "all": rows,
            "sizeBytesTotal": rows.iter().map(|row| row.size_bytes).sum::<u64>(),
            "playSecondsTotal": rows.iter().map(|row| row.play_seconds).sum::<u64>(),
        }))
    }

    pub(super) fn instances_create(&self, params: &Value) -> Result<Value> {
        let request: InstanceCreateRequest = from_value(params)?;
        let manager = self.manager();
        let loader_kind = non_empty(Some(request.loader_kind.as_str()));
        let instance = manager.create(
            &request.id,
            &request.name,
            &request.minecraft,
            loader_kind.as_deref(),
            request
                .loader_version
                .as_deref()
                .filter(|v| !v.trim().is_empty()),
        )?;
        Ok(json!({
            "id": instance.id,
            "name": instance.name,
            "minecraft": instance.minecraft,
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    pub(super) async fn instances_import(&self, params: &Value) -> Result<Value> {
        let request: InstanceImportRequest = from_value(params)?;
        let directory = PathBuf::from(&request.directory);
        let instance = self.manager().import_external(
            &directory,
            request.name.as_deref(),
            request.minecraft.as_deref(),
        )?;
        Ok(json!({
            "id": instance.id,
            "name": instance.name,
            "minecraft": instance.minecraft,
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    pub(super) async fn instances_rename(&self, params: &Value) -> Result<Value> {
        let request: IdNameRequest = from_value(params)?;
        self.manager().rename(&request.id, &request.name)?;
        Ok(json!(null))
    }

    pub(super) async fn instances_favorite(&self, params: &Value) -> Result<Value> {
        let request: IdFlagRequest = from_value(params)?;
        let favorite = self.manager().set_favorite(&request.id, request.favorite)?;
        Ok(json!({ "favorite": favorite }))
    }

    pub(super) async fn instances_delete(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        if let Some(session) = self.session.lock().expect("session poisoned").as_ref()
            && session.instance_id == request.id
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.instance.delete_running",
                format!("instance {} is running", request.id),
            ));
        }
        self.manager().delete(&request.id)?;
        Ok(json!(null))
    }

    pub(super) async fn instance_settings(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let manager = self.manager();
        let instance = manager.load(&request.id)?;
        Ok(json!({
            "effective": manager.effective_json(&instance),
            "overrides": json!({
                "java": instance.overrides.java.as_ref().map(|path| path.display().to_string()),
                "memoryMb": instance.overrides.memory_mb,
                "jvmArgs": instance.overrides.jvm_args,
                "resolution": instance.overrides.resolution,
                "fullscreen": instance.overrides.fullscreen,
                "gameDir": instance.overrides.game_dir.as_ref().map(|path| path.display().to_string()),
            }),
            "globals": self.game_config_value()?,
        }))
    }

    pub(super) async fn instance_settings_set(&self, params: &Value) -> Result<Value> {
        let request: SettingsSetRequest = from_value(params)?;
        let manager = self.manager();
        for update in &request.updates {
            let setting = update.setting.as_str();
            match setting {
                "java" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Java,
                        inherit_text(&update.value),
                    )?;
                }
                "memory" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Memory,
                        SettingValue::Memory(update.value.as_u64().unwrap_or(0) as u32),
                    )?;
                }
                "jvmArgs" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::JvmArgs,
                        inherit_text(&update.value),
                    )?;
                }
                "resolution" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Resolution,
                        SettingValue::Resolution(resolution_of(&update.value)),
                    )?;
                }
                "fullscreen" => {
                    manager.write_setting(
                        &request.id,
                        InheritableSetting::Fullscreen,
                        SettingValue::Flag(update.value.as_bool().unwrap_or(false)),
                    )?;
                }
                other => {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.instance.setting_unknown",
                        other.to_string(),
                    ));
                }
            }
        }
        Ok(json!(null))
    }

    pub(super) async fn instance_verify(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let manager = self.manager();
        let instance = manager.load(&request.id)?;
        let version_id = instance.version_id().to_string();
        let resolved = self
            .resolver()?
            .resolve_local(&version_id)
            .map_err(|error| {
                if error.kind == ErrorKind::NotFound {
                    Error::with_detail(
                        ErrorKind::NotFound,
                        "error.launch.not_installed",
                        format!("{version_id} is not installed"),
                    )
                } else {
                    error
                }
            })?;
        match fg_game::launch::verify_installed(&self.layout, &resolved, &RuleContext::current()) {
            Ok(()) => Ok(json!({ "ok": true, "missing": 0 })),
            Err(error) => Ok(json!({
                "ok": false,
                "messageKey": error.message_key,
                "detail": error.detail.unwrap_or_default(),
            })),
        }
    }

    pub(super) async fn instance_dir(&self, params: &Value) -> Result<Value> {
        let request: IdRequest = from_value(params)?;
        let instance = self.manager().load(&request.id)?;
        Ok(json!({
            "gameDir": instance.game_dir(&self.layout).display().to_string(),
        }))
    }

    // ------------------------------------------------------------------
    // Content and worlds
    // ------------------------------------------------------------------
}
