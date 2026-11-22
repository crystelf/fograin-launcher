use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn modpack_inspect(&self, params: &Value) -> Result<Value> {
        let request: ModpackInspectRequest = from_value(params)?;
        let path = PathBuf::from(&request.path);
        let pack = tokio::task::spawn_blocking(move || fg_content::modpack::inspect(&path))
            .await
            .map_err(|error| {
                Error::with_detail(ErrorKind::Internal, "error.modpack.read", error.to_string())
            })??;
        to_value(pack)
    }

    /// Downloads a Modrinth/CurseForge modpack and imports it.
    ///
    /// The archive download is a normal task so the queue shows progress; once
    /// it finishes the background orchestrator calls the same import path as a
    /// local `.mrpack`, which queues the base install and the pack graph.
    pub(super) async fn modpack_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ModpackInstallRequest = from_value(params)?;
        let source = parse_content_source(request.source.as_deref().unwrap_or_default())?;
        let client = self.content_client()?;
        let version = match source {
            ContentSource::Modrinth => {
                client
                    .modrinth_resolve_version(
                        &request.project_id,
                        Some(&request.version_id),
                        None,
                        None,
                    )
                    .await?
            }
            ContentSource::Curseforge => {
                client
                    .curseforge_resolve_version(
                        &request.project_id,
                        Some(&request.version_id),
                        None,
                        None,
                    )
                    .await?
            }
        };
        let mut file = version.file.clone().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.content.no_files",
                request.project_id.clone(),
            )
        })?;
        if file.url.trim().is_empty() {
            if source == ContentSource::Curseforge {
                let file_id = version.version_id.parse::<u64>().map_err(|_| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.content.version_id",
                        version.version_id.clone(),
                    )
                })?;
                file.url = client
                    .curseforge_download_url(&request.project_id, file_id)
                    .await?;
            } else {
                return Err(Error::with_detail(
                    ErrorKind::NotFound,
                    "error.content.no_files",
                    request.project_id.clone(),
                ));
            }
        }
        let name = non_empty(request.name.as_deref()).unwrap_or_else(|| version.project_id.clone());
        let directory = self.layout.cache().join("modpacks");
        std::fs::create_dir_all(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.create_dir",
                format!("{}: {error}", directory.display()),
            )
        })?;
        let file_name = sanitize_download_name(&file.file_name)
            .unwrap_or_else(|| format!("{}-{}.mrpack", version.project_id, version.version_id));
        let dest = directory.join(format!("{}-{}", version.version_id, file_name));
        let candidates = client.candidates(&file.url)?;
        if candidates.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.content.no_candidates",
                file.url.clone(),
            ));
        }
        let mut download = DownloadRequest::new(dest.clone(), candidates)
            .with_priority(Priority::Mod)
            .with_size_hint(file.size);
        if let Some(sha1) = &file.sha1 {
            download = download.with_sha1(sha1);
        } else if let Some(sha512) = &file.sha512 {
            download = download.with_integrity(fg_net::Integrity::sha512(sha512));
        } else if file.size > 0 {
            download = download.with_size(file.size);
        }
        let weight = (file.size / (64 * 1024)).clamp(1, 200_000) as u32;
        let downloader = Arc::clone(&self.downloader);
        let mut builder = TaskGraph::builder(format!("modpack-download:{}", name));
        builder.task("modpack.download", weight, move |cx| {
            let downloader = Arc::clone(&downloader);
            let download = download.clone();
            async move {
                cx.check()?;
                downloader
                    .download_with(
                        download,
                        fg_net::DownloadCtx {
                            cancel: cx.cancel_handle(),
                            progress: Arc::new(TaskSink { cx: cx.clone() }),
                        },
                    )
                    .await
                    .map(|_| ())
            }
        });
        let graph = builder.build();
        let control = Control::new();
        let task_name = format!("整合包下载:{}", name);
        let task_id = self.queue_task(
            &task_name,
            control.clone(),
            graph,
            None,
            Some(TaskReplay {
                method: "modpack.install",
                params: params.clone(),
            }),
        );
        let this = Arc::clone(self);
        let wait_id = task_id.clone();
        let import_name = name.clone();
        self.runtime.spawn(async move {
            if let Err(error) = this.wait_task(&wait_id).await {
                tracing::warn!(%error, "modpack download failed; import skipped");
                return;
            }
            let payload = json!({ "path": dest, "name": import_name });
            if let Err(error) = this.modpack_import(&payload).await {
                tracing::warn!(%error, "modpack import failed after download");
            }
        });
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, task_name));

        Ok(json!({
            "taskId": task_id,
            "name": name,
            "versionId": version.version_id,
            "versionNumber": version.version_number,
            "source": source.as_str(),
        }))
    }

    pub(super) async fn modpack_import(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: ModpackImportRequest = from_value(params)?;
        let path = PathBuf::from(&request.path);
        let pack = tokio::task::spawn_blocking(move || fg_content::modpack::inspect(&path))
            .await
            .map_err(|error| {
                Error::with_detail(ErrorKind::Internal, "error.modpack.read", error.to_string())
            })??;
        let name = non_empty(request.name.as_deref()).unwrap_or_else(|| pack.name.clone());
        if pack.minecraft.trim().is_empty() {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.modpack.minecraft",
                pack.source_path.clone(),
            ));
        }

        if pack.external {
            let game_dir = pack.game_dir.clone().ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.modpack.directory",
                    pack.source_path.clone(),
                )
            })?;
            let manager = self.manager();
            let instance = manager.import_external(
                Path::new(&game_dir),
                Some(&name),
                Some(&pack.minecraft),
            )?;
            if !pack.loader_kind.eq_ignore_ascii_case("vanilla") {
                let _ =
                    manager.set_loader(&instance.id, &pack.loader_kind, &pack.loader_version, "");
            }
            return Ok(json!({
                "instanceId": instance.id,
                "taskId": Value::Null,
                "external": true,
                "pack": pack,
            }));
        }

        let instance_id = modpack_instance_id(&self.manager(), &name);
        let loader_kind = if pack.loader_kind.eq_ignore_ascii_case("vanilla") {
            String::new()
        } else {
            pack.loader_kind.clone()
        };
        let install = self
            .install_start(&json!({
                "version": pack.minecraft,
                "instanceId": instance_id,
                "instanceName": name,
                "loaderKind": loader_kind,
                "loaderVersion": pack.loader_version,
            }))
            .await?;
        let base_task = install
            .get("taskId")
            .and_then(Value::as_str)
            .map(str::to_string);

        let archive = PathBuf::from(&pack.source_path);
        let replay_params = json!({
            "path": pack.source_path,
            "name": pack.name,
        });
        let this = Arc::clone(self);
        let task_name = format!("整合包:{}", pack.name);
        let pack_for_task = pack.clone();
        let instance_for_task = instance_id.clone();
        let task_name_for_task = task_name.clone();
        let base_task_for_task = base_task.clone();
        self.runtime.spawn(async move {
            if let Some(task) = base_task_for_task
                && let Err(error) = this.wait_task(&task).await
            {
                tracing::warn!(%error, "base install failed; modpack import stopped");
                return;
            }
            let instance = match this.manager().load(&instance_for_task) {
                Ok(instance) => instance,
                Err(error) => {
                    tracing::warn!(%error, "modpack instance disappeared");
                    return;
                }
            };
            let client = match this.content_client() {
                Ok(client) => client,
                Err(error) => {
                    tracing::warn!(%error, "modpack import has no content client");
                    return;
                }
            };
            let plan = fg_content::ModpackPlan::new(
                pack_for_task,
                archive,
                this.layout.instance_dir(&instance_for_task),
                instance.game_dir(&this.layout),
            );
            let graph = plan.graph(Arc::clone(&this.downloader), client);
            let control = Control::new();
            let task_id = this.queue_task(
                &task_name_for_task,
                control.clone(),
                graph,
                None,
                Some(TaskReplay {
                    method: "modpack.import",
                    params: replay_params,
                }),
            );
            this.clone()
                .watch_task(task_id, control, task_name_for_task)
                .await;
        });

        Ok(json!({
            "instanceId": instance_id,
            "taskId": base_task,
            "external": false,
            "pack": pack,
        }))
    }

    pub(super) async fn modpack_export_plan(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let game_dir = instance.game_dir(&self.layout);
        let plan = tokio::task::spawn_blocking(move || fg_content::modpack::export_plan(&game_dir))
            .await
            .map_err(|error| {
                Error::with_detail(
                    ErrorKind::Internal,
                    "error.modpack.export",
                    error.to_string(),
                )
            })?;
        to_value(plan)
    }

    pub(super) async fn modpack_export(&self, params: &Value) -> Result<Value> {
        let request: ModpackExportRequest = from_value(params)?;
        let instance = self.manager().load(&request.instance_id)?;
        let loader_kind = instance.loader.kind.clone();
        let loader_version = instance.loader.version.clone();
        let name = non_empty(request.name.as_deref()).unwrap_or_else(|| instance.name.clone());
        let minecraft = instance.minecraft.clone();
        let version = non_empty(request.version.as_deref()).unwrap_or_else(|| "1.0".to_string());
        let author = request.author.unwrap_or_default();
        let description = request.description.unwrap_or_default();
        let game_dir = instance.game_dir(&self.layout);
        let sections = request.sections.unwrap_or_else(|| {
            fg_content::modpack::export_plan(&game_dir)
                .sections
                .into_iter()
                .filter(|section| section.default)
                .map(|section| section.id)
                .collect()
        });
        let output = match request.output_path.filter(|path| !path.trim().is_empty()) {
            Some(path) => std::path::PathBuf::from(path),
            None => {
                let exports_dir = self.root.join("exports");
                fg_content::modpack::default_export_path(&exports_dir, &name, &version)
            }
        };
        let export = tokio::task::spawn_blocking(move || {
            let config = fg_content::modpack::ExportConfig {
                name: &name,
                author: &author,
                version: &version,
                description: &description,
                minecraft: &minecraft,
                loader_kind: &loader_kind,
                loader_version: &loader_version,
                game_dir: &game_dir,
                sections: &sections,
                output: &output,
            };
            fg_content::modpack::export_mrpack(&config)
        })
        .await
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.modpack.export",
                error.to_string(),
            )
        })??;
        to_value(export)
    }
}
