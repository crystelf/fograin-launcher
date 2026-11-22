use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    pub(super) async fn java_list(&self) -> Result<Value> {
        let config = self.config()?;
        let supported = !fg_java::host_platforms().is_empty();
        Ok(json!({
            "runtimes": self.java_rows()?,
            "autoDownload": config.java.auto_download,
            "supported": supported,
            "platform": fg_core::Platform::detect().id(),
            "hostArch": JavaArch::host().to_string(),
        }))
    }

    pub(super) async fn java_catalog(&self) -> Result<Value> {
        let config = self.config()?;
        let manager = self.java_manager()?;
        let installed = manager.installed();
        let candidates = manager.catalog().await?;
        let rows: Vec<JavaCatalogRow> = candidates
            .into_iter()
            .map(|candidate| {
                let hit = installed.iter().find(|runtime| {
                    runtime.marker.major == candidate.major && runtime.marker.arch == candidate.arch
                });
                JavaCatalogRow {
                    runtime_id: fg_java::sanitize_id(&candidate.runtime_id()),
                    major: candidate.major,
                    component: candidate.component,
                    version: candidate.version,
                    arch: candidate.arch.to_string(),
                    platform: candidate.platform,
                    fallback: candidate.fallback,
                    installed: hit.is_some(),
                    installed_version: hit.map(|runtime| runtime.marker.version.clone()),
                }
            })
            .collect();
        Ok(json!({
            "versions": rows,
            "autoDownload": config.java.auto_download,
            "supported": !fg_java::host_platforms().is_empty(),
            "platform": fg_core::Platform::detect().id(),
            "hostArch": JavaArch::host().to_string(),
        }))
    }

    pub(super) async fn java_install(self: &Arc<Self>, params: &Value) -> Result<Value> {
        let request: JavaInstallRequest = from_value(params)?;
        if request.major == 0 {
            return Err(Error::with_detail(
                ErrorKind::InvalidInput,
                "error.java.runtime_major",
                "major must be positive",
            ));
        }
        let name = format!("java:{}", request.major);
        if let Some((task_id, runtime_id)) = self.running_java_task(&name) {
            return Ok(json!({
                "taskId": task_id,
                "runtimeId": runtime_id,
                "existing": true,
            }));
        }

        let manager = Arc::new(self.java_manager()?);
        let component = non_empty(request.component.as_deref());
        let candidate = manager
            .candidate(request.major, component.as_deref())
            .await?;
        let plan = manager.plan(&candidate).await?;
        plan.save(&self.root)?;
        let runtime_id = plan.id.clone();
        let summary = json!({
            "runtimeId": runtime_id,
            "major": plan.major,
            "component": plan.component,
            "version": plan.version,
            "files": plan.files.len(),
            "totalBytes": plan.total_bytes,
        });
        let graph = manager.graph(plan);
        let control = Control::new();
        let java_replay = TaskReplay {
            method: "java.install",
            params: json!({
                "major": request.major,
                "component": request.component,
            }),
        };
        let task_id = self.queue_task(
            &name,
            control.clone(),
            graph,
            Some(runtime_id.clone()),
            Some(java_replay),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, name));
        Ok(json!({
            "taskId": task_id,
            "runtimeId": runtime_id,
            "existing": false,
            "major": summary["major"],
            "component": summary["component"],
            "version": summary["version"],
            "files": summary["files"],
            "totalBytes": summary["totalBytes"],
        }))
    }

    pub(super) async fn java_remove(&self, params: &Value) -> Result<Value> {
        let request: JavaRemoveRequest = from_value(params)?;
        let id = request.id.trim();
        if id.is_empty() {
            return Err(Error::new(ErrorKind::InvalidInput, "error.java.runtime_id"));
        }
        let manager = self.java_manager()?;
        if let Some(existing) = self.session.lock().expect("session poisoned").as_ref()
            && existing
                .finished
                .lock()
                .expect("exit slot poisoned")
                .is_none()
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.java.runtime_in_use",
                format!("game {} is running", existing.instance_id),
            ));
        }
        if self
            .tasks
            .lock()
            .expect("task queue poisoned")
            .iter()
            .any(|task| {
                task.runtime_id.as_deref() == Some(id)
                    && !task.graph.progress().is_complete()
                    && !task.control.is_stopped()
            })
        {
            return Err(Error::with_detail(
                ErrorKind::Conflict,
                "error.java.runtime_in_use",
                format!("{id} is still being installed"),
            ));
        }
        let marker = manager.remove(id)?;
        Ok(json!({ "ok": true, "id": marker.id }))
    }

    pub(super) async fn java_policy_set(&self, params: &Value) -> Result<Value> {
        let Some(auto_download) = params.get("autoDownload").and_then(Value::as_bool) else {
            return Err(Error::new(ErrorKind::InvalidInput, "error.rpc.params"));
        };
        let mut config = self.config()?;
        config.java.auto_download = auto_download;
        config::write_config(&self.root, &config)?;
        Ok(json!({ "autoDownload": auto_download }))
    }

    /// Returns a matching runtime, downloading the official one first when
    /// `[java] auto_download` is on and nothing suitable is installed.
    ///
    /// `require_arch` is set for Apple Silicon legacy versions: those need an
    /// x86_64 Java 8 under Rosetta, so an arm64 runtime must not satisfy the
    /// check even when its major matches.
    pub(super) async fn ensure_java(
        self: &Arc<Self>,
        required: u32,
        component: Option<&str>,
        arch: JavaArch,
        require_arch: bool,
    ) -> Result<fg_java::JavaSelection> {
        let requirement = fg_java::JavaRequirement::new(required, arch, require_arch);
        let runtimes = discover_java(&self.layout).await;
        let selection = fg_java::select_available(&runtimes, requirement);
        if let Ok(selection) = &selection {
            return Ok(selection.clone());
        }

        if !self.config()?.java.auto_download {
            return selection;
        }

        let manager = Arc::new(self.java_manager()?);
        let name = format!("java:{required}");
        if let Some((task_id, _)) = self.running_java_task(&name) {
            self.wait_task(&task_id).await?;
            // The running task may have installed a different architecture
            // than a legacy launch needs (for example arm64 Java 17 from the
            // management page while 1.18 needs x86_64). Re-check before
            // trusting it.
            let runtimes = discover_java(&self.layout).await;
            if let Ok(selection) = fg_java::select_available(&runtimes, requirement) {
                return Ok(selection);
            }
        }
        let candidate = manager.candidate_for(required, component, arch).await?;
        let plan = manager.plan(&candidate).await?;
        plan.save(&self.root)?;
        let runtime_id = plan.id.clone();
        let graph = manager.graph(plan);
        let control = Control::new();
        let java_replay = TaskReplay {
            method: "java.install",
            params: json!({
                "major": required,
                "component": component,
            }),
        };
        let task_id = self.queue_task(
            &name,
            control.clone(),
            graph,
            Some(runtime_id),
            Some(java_replay),
        );
        self.runtime
            .spawn(self.clone().watch_task(task_id.clone(), control, name));
        self.wait_task(&task_id).await?;
        let runtimes = discover_java(&self.layout).await;
        let selection = fg_java::select(&runtimes, required, arch)?;
        fg_java::verify_downloaded(&selection, requirement)?;
        Ok(selection)
    }
}
