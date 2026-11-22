use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn datapacks_list(&self, params: &Value) -> Result<Value> {
        let request: DatapackRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let items = fg_world::datapack::list(&instance.game_dir(&self.layout), &request.world)?;
        Ok(json!({ "items": items, "count": items.len() }))
    }

    pub(super) async fn datapacks_toggle(&self, params: &Value) -> Result<Value> {
        let request: DatapackToggleRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let file_name = fg_world::datapack::set_enabled(
            &game_dir,
            &request.world,
            &request.file_name,
            request.enabled,
        )?;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items, "fileName": file_name }))
    }

    pub(super) async fn datapacks_delete(&self, params: &Value) -> Result<Value> {
        let request: DatapackDeleteRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        fg_world::datapack::delete(&game_dir, &request.world, &request.file_name)?;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items }))
    }

    pub(super) async fn datapacks_import(&self, params: &Value) -> Result<Value> {
        let request: DatapackImportRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let source = PathBuf::from(&request.path);
        let path = source.clone();
        let world = request.world.clone();
        let target_game = game_dir.clone();
        let file_name = tokio::task::spawn_blocking(move || {
            fg_world::datapack::import(&target_game, &world, &path)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.datapack.write",
                error.to_string(),
            )
        })??;
        let items = fg_world::datapack::list(&game_dir, &request.world)?;
        Ok(json!({ "items": items, "fileName": file_name }))
    }
}
