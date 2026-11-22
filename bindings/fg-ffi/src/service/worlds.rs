use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn worlds_list(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let worlds = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "worlds": worlds, "backups": backups }))
    }

    pub(super) async fn worlds_backups(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "backups": backups }))
    }

    pub(super) async fn worlds_backup(&self, params: &Value) -> Result<Value> {
        let request: WorldNameRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let root = self.root.clone();
        let instance_id = instance.id.clone();
        let game_dir = instance.game_dir(&self.layout);
        let name = request.name;
        let backup: Backup = tokio::task::spawn_blocking(move || {
            fg_world::backup_world(&root, &instance_id, &game_dir, &name)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.world.backup",
                format!("backup worker failed: {error}"),
            )
        })??;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "ok": true, "backup": backup, "backups": backups }))
    }

    pub(super) async fn worlds_restore(&self, params: &Value) -> Result<Value> {
        let request: WorldRestoreRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let root = self.root.clone();
        let instance_id = instance.id.clone();
        let game_dir = instance.game_dir(&self.layout);
        let backup = request.backup;
        let name = request.name;
        let path = tokio::task::spawn_blocking(move || {
            fg_world::restore_backup(&root, &instance_id, &game_dir, &backup, &name)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.world.restore",
                format!("restore worker failed: {error}"),
            )
        })??;
        let worlds: Vec<World> = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        let backups: Vec<Backup> = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({
            "ok": true,
            "path": path.display().to_string(),
            "worlds": worlds,
            "backups": backups,
        }))
    }

    pub(super) async fn worlds_delete(&self, params: &Value) -> Result<Value> {
        let request: WorldNameRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        fg_world::delete_world(&instance.game_dir(&self.layout), &request.name)?;
        let worlds = fg_world::list_worlds(&instance.game_dir(&self.layout))?;
        Ok(json!({ "ok": true, "worlds": worlds }))
    }

    pub(super) async fn worlds_backup_delete(&self, params: &Value) -> Result<Value> {
        let request: WorldBackupRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        fg_world::delete_backup(&self.root, &instance.id, &request.backup)?;
        let backups = fg_world::list_backups(&self.root, &instance.id)?;
        Ok(json!({ "ok": true, "backups": backups }))
    }

    // ------------------------------------------------------------------
    // Servers, diagnostics, data packs and modpacks
    // ------------------------------------------------------------------
}
