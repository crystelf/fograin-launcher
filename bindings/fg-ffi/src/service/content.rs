use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) fn content_client(&self) -> Result<ContentClient> {
        let config = self.config()?;
        Ok(ContentClient::new(
            self.http_client()?,
            self.layout.cache(),
            self.table.clone(),
            config.download.source,
            self.region,
        ))
    }

    pub(super) async fn content_local(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let index = ContentIndex::load(&instance_dir)?;

        let mut items: Vec<LocalContent> = Vec::new();
        let mut mods = 0_usize;
        let mut resourcepacks = 0_usize;
        let mut shaderpacks = 0_usize;
        let mut enabled = 0_usize;
        for kind in ContentKind::ALL {
            let scanned = scan_local(&game_dir, kind, Some(&index))?;
            match kind {
                ContentKind::Mod => mods = scanned.len(),
                ContentKind::ResourcePack => resourcepacks = scanned.len(),
                ContentKind::ShaderPack => shaderpacks = scanned.len(),
                ContentKind::Modpack | ContentKind::Datapack => {}
            }
            enabled += scanned.iter().filter(|item| item.enabled).count();
            items.extend(scanned);
        }

        Ok(json!({
            "items": items,
            "counts": {
                "mod": mods,
                "resourcepack": resourcepacks,
                "shaderpack": shaderpacks,
                "enabled": enabled,
            },
            "indexPath": instance_dir.join(fg_content::CONTENT_INDEX_FILE).display().to_string(),
        }))
    }

    pub(super) async fn content_toggle(&self, params: &Value) -> Result<Value> {
        let request: ContentToggleRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let new_name = set_enabled(&game_dir, kind, &request.file_name, request.enabled)?;

        let mut index = ContentIndex::load(&instance_dir)?;
        if let Some(mut entry) = index.find(&request.file_name).cloned() {
            index.remove(&request.file_name);
            entry.file_name.clone_from(&new_name);
            index.upsert(entry);
            index.save(&instance_dir)?;
        }
        Ok(json!({ "ok": true, "fileName": new_name }))
    }

    pub(super) async fn content_delete(&self, params: &Value) -> Result<Value> {
        let request: ContentDeleteRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        delete_local(&game_dir, kind, &request.file_name)?;

        let mut index = ContentIndex::load(&instance_dir)?;
        if index.find(&request.file_name).is_some() {
            index.remove(&request.file_name);
            index.save(&instance_dir)?;
        }
        Ok(json!({ "ok": true }))
    }

    pub(super) async fn content_search(&self, params: &Value) -> Result<Value> {
        let request: ContentSearchRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.optional_instance(request.instance_id.as_deref())?;
        let (game_version, loader) = content_context(
            instance.as_ref(),
            kind,
            request.game_version.as_deref(),
            request.loader.as_deref(),
        );

        let query = SearchQuery::new(kind)
            .with_query(request.query.trim())
            .with_game_version(game_version)
            .with_loader(loader)
            .with_page(request.offset, request.limit.clamp(1, 50));
        let client = self.content_client()?;
        let page = match source {
            ContentSource::Modrinth => client.modrinth_search(&query).await?,
            ContentSource::Curseforge => client.curseforge_search(&query).await?,
        };
        to_value(page)
    }

    pub(super) async fn content_versions(&self, params: &Value) -> Result<Value> {
        let request: ContentVersionsRequest = from_value(params)?;
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.optional_instance(request.instance_id.as_deref())?;
        let (game_version, loader) = content_context(
            instance.as_ref(),
            kind,
            request.game_version.as_deref(),
            request.loader.as_deref(),
        );
        let client = self.content_client()?;

        let project = match source {
            ContentSource::Modrinth => client.modrinth_project(&request.project_id).await?,
            ContentSource::Curseforge => client.curseforge_project(&request.project_id).await?,
        };
        let versions = match source {
            ContentSource::Modrinth => {
                client
                    .modrinth_versions(
                        &request.project_id,
                        game_version.as_deref(),
                        loader.as_deref(),
                    )
                    .await?
            }
            ContentSource::Curseforge => {
                client
                    .curseforge_versions(
                        &request.project_id,
                        game_version.as_deref(),
                        loader.as_deref(),
                    )
                    .await?
            }
        };
        Ok(json!({
            "source": source.as_str(),
            "project": project,
            "versions": versions,
        }))
    }

    /// Resolves an install without queueing anything. The content sheet uses
    /// this to list missing prerequisites before the user starts a download.
    pub(super) async fn content_plan(&self, params: &Value) -> Result<Value> {
        let request: ContentInstallRequest = from_value(params)?;
        let plan = self
            .build_content_plan(&request, request.install_dependencies)
            .await?;
        Ok(content_plan_summary(&plan))
    }

    pub(super) async fn build_content_plan(
        &self,
        request: &ContentInstallRequest,
        install_dependencies: bool,
    ) -> Result<fg_content::ContentPlan> {
        let kind = ContentKind::parse(&request.kind)?;
        let source = parse_content_source(&request.source)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let index = ContentIndex::load(&instance_dir)?;
        let installed_projects = index
            .items
            .iter()
            .map(|entry| entry.project_id.clone())
            .collect::<Vec<_>>();
        let loader = if kind.loader_specific() {
            fg_content::model::normalize_loader(&instance.loader.kind)
        } else {
            None
        };

        let client = self.content_client()?;
        plan_install(
            &client,
            InstallRequest {
                source,
                project_id: request.project_id.clone(),
                version_id: request.version_id.clone(),
                kind,
                instance_id: instance.id.clone(),
                instance_dir,
                game_dir,
                game_version: instance.minecraft.clone(),
                loader,
                world: request.world.clone(),
                replace_files: request.replace_files.clone(),
                installed_projects,
                install_dependencies,
            },
        )
        .await
    }

    pub(super) async fn content_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ContentInstallRequest = from_value(params)?;
        let plan = self
            .build_content_plan(&request, request.install_dependencies)
            .await?;
        let summary = content_plan_summary(&plan);

        let plan = Arc::new(plan);
        let task_name = format!("content:{}", plan.project_name);
        let client = self.content_client()?;
        let graph = plan.graph(self.downloader.clone(), client);
        let control = Control::new();
        let task_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "content.install",
                params: params.clone(),
            }),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, task_name));

        let mut response = summary;
        if let Value::Object(object) = &mut response {
            object.insert("taskId".to_string(), Value::String(task_id));
        }
        Ok(response)
    }

    pub(super) async fn content_updates(&self, params: &Value) -> Result<Value> {
        let request: ContentUpdatesRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let kind = request
            .kind
            .as_deref()
            .filter(|value| !value.trim().is_empty())
            .map(ContentKind::parse)
            .transpose()?;
        let game_dir = instance.game_dir(&self.layout);
        let instance_dir = self.layout.instance_dir(&instance.id);
        let loader = fg_content::model::normalize_loader(&instance.loader.kind);
        let client = self.content_client()?;
        let check = check_updates(
            &client,
            &instance_dir,
            &game_dir,
            kind,
            &instance.minecraft,
            loader.as_deref(),
        )
        .await?;
        to_value(check)
    }
}
