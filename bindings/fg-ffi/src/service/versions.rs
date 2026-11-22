use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn versions_list(&self, params: &Value) -> Result<Value> {
        let request: VersionsListRequest = from_value(params)?;
        let manifest = self.resolver()?.manifest(request.refresh).await?;
        let entries: Vec<Value> = manifest
            .versions
            .iter()
            .filter(|version| {
                kind_matches(
                    &request.kind,
                    &version.id,
                    &version.version_type,
                    version.release_time.as_deref(),
                )
            })
            .take(request.limit.clamp(1, 500))
            .map(|version| {
                json!({
                    "id": version.id,
                    "kind": version.version_type,
                    "released": version.release_time,
                })
            })
            .collect();
        Ok(json!({ "versions": entries }))
    }

    pub(super) async fn versions_news(&self, params: &Value) -> Result<Value> {
        let request: NewsRequest = from_value(params)?;
        let manifest = self.resolver()?.manifest(request.refresh).await?;
        let news: Vec<Value> = manifest
            .versions
            .iter()
            .filter(|version| version.version_type == "release")
            .take(request.limit.clamp(1, 20))
            .map(|version| {
                json!({
                    "id": version.id,
                    "kind": version.version_type,
                    "released": version.release_time,
                })
            })
            .collect();
        Ok(json!({ "news": news }))
    }

    pub(super) async fn loaders_versions(&self, params: &Value) -> Result<Value> {
        let request: LoaderVersionsRequest = from_value(params)?;
        let kind = LoaderKind::parse(&request.kind)?;
        let planner = self.loader_planner()?;
        let versions = fg_loader::versions(
            planner.downloader,
            planner.table,
            planner.source,
            planner.region,
            fg_loader::VersionListQuery {
                kind,
                minecraft: &request.minecraft,
                limit: request.limit,
            },
        )
        .await?;
        Ok(json!({ "versions": versions }))
    }

    pub(super) async fn version_plan(&self, params: &Value) -> Result<Value> {
        let request: VersionRequest = from_value(params)?;
        let loader = requested_loader(&request)?;
        let plan = if let Some((kind, version)) = loader {
            let version = if version.trim().is_empty() {
                self.loader_planner()?
                    .latest_version(kind, &request.version)
                    .await?
                    .version
            } else {
                version
            };
            self.installer()?
                .plan_loader(&request.version, kind, &version, None)
                .await?
        } else {
            self.installer()?
                .plan(&request.version, false, None)
                .await?
        };
        Ok(json!({
            "versionId": plan.version_id,
            "baseId": plan.base_id,
            "javaMajor": plan.java_major,
            "files": plan.files.len(),
            "totalBytes": plan.total_bytes,
            "loaderKind": plan.loader.as_ref().map(|loader| loader.kind.as_str()),
            "loaderVersion": plan.loader.as_ref().map(|loader| loader.loader_version.clone()),
        }))
    }

    pub(super) async fn install_start(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: InstallStartRequest = from_value(params)?;
        let target_instance = if let Some(id) = non_empty(request.instance_id.as_deref()) {
            let manager = self.manager();
            if manager.exists(&id) {
                let instance = manager.load(&id)?;
                if instance.minecraft != request.version {
                    return Err(Error::with_detail(
                        ErrorKind::Conflict,
                        "error.instance.version_mismatch",
                        format!(
                            "instance {id} is for {}, not {}",
                            instance.minecraft, request.version
                        ),
                    ));
                }
                Some(instance)
            } else {
                None
            }
        } else {
            None
        };

        // The completion flow sends the instance's existing loader so a repair
        // install does not silently fall back to vanilla.
        let loader_selection = resolve_loader_selection(&request, target_instance.as_ref())?;
        let (loader_kind, loader_version) = match loader_selection {
            Some((kind, version)) => {
                let version = if version.trim().is_empty() {
                    self.loader_planner()?
                        .latest_version(kind, &request.version)
                        .await?
                        .version
                } else {
                    version
                };
                (Some(kind), Some(version))
            }
            None => (None, None),
        };

        // Validate the version before queueing so the wizard can surface a
        // problem in place instead of through a task failure.
        let resolved = self.resolver()?.resolve_remote(&request.version).await?;
        let java = if loader_kind.is_some_and(LoaderKind::requires_java) {
            let configured = target_instance
                .as_ref()
                .and_then(|instance| instance.overrides.java.clone())
                .or_else(|| self.config().ok().and_then(|config| config.game.java));
            if let Some(path) = configured {
                Some(explicit_java(&path)?.executable)
            } else {
                let selection = self
                    .ensure_java(
                        resolved.java_major,
                        resolved.java_component(),
                        JavaArch::host(),
                        false,
                    )
                    .await?;
                Some(selection.runtime.executable)
            }
        } else {
            None
        };
        let installer = self.installer_with_java(java)?;
        let plan = match (loader_kind, loader_version.as_deref()) {
            (Some(kind), Some(version)) => {
                installer
                    .plan_loader(
                        &request.version,
                        kind,
                        version,
                        request.instance_id.as_deref(),
                    )
                    .await?
            }
            _ => {
                installer
                    .plan_resolved(&resolved, request.instance_id.as_deref())
                    .await?
            }
        };
        plan.save(&self.layout)?;
        let plan_version_id = plan.version_id.clone();
        let graph = installer.graph(&plan);
        drop(plan);

        // Create the instance row before queueing the install: the UI opens
        // its 游戏设置 page from the finished event, and an already-installed
        // version can complete the graph before any detached create task runs.
        // An existing instance with the same Minecraft version is reused, so
        // pressing 开始游戏 on an incomplete instance completes its resources
        // instead of failing with a duplicate-id error.
        if let Some(id) = non_empty(request.instance_id.as_deref()) {
            let manager = self.manager();
            if target_instance.is_none() {
                let name =
                    non_empty(request.instance_name.as_deref()).unwrap_or_else(|| id.clone());
                let kind = loader_kind.map_or("vanilla", LoaderKind::as_str);
                manager.create(
                    &id,
                    &name,
                    &request.version,
                    Some(kind),
                    loader_version.as_deref(),
                )?;
            }
            if let Some(kind) = loader_kind {
                manager.set_loader(
                    &id,
                    kind.as_str(),
                    loader_version.as_deref().unwrap_or_default(),
                    &plan_version_id,
                )?;
            }
        }

        let task_name = format!("install:{}", request.version);
        let control = Control::new();
        let queue_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "install.start",
                params: params.clone(),
            }),
        );
        self.runtime.spawn(
            self.clone()
                .watch_task(queue_id.clone(), control, task_name),
        );
        Ok(json!({
            "taskId": queue_id,
            "versionId": plan_version_id,
        }))
    }
}
