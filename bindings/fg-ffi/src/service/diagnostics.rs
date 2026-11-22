use super::helpers::*;
use super::params::*;
use super::*;

impl Service {
    /// Collects the diagnostic sources shared by `diag.logs` / `diag.analyze`.
    pub(super) fn diagnostic_bundle(&self, instance_id: &str) -> Result<(Instance, LogBundle)> {
        let instance = self.manager().load(instance_id)?;
        let stdout = self.events.recent_game_lines(&instance.id, 2000);
        let bundle = fg_diag::collect(&instance.game_dir(&self.layout), stdout, 2000);
        Ok((instance, bundle))
    }

    pub(super) fn diagnostic_rules(&self) -> RuleSet {
        RuleSet::load(Some(&self.root.join("diag").join("rules"))).unwrap_or_default()
    }

    pub(super) async fn diag_logs(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (_instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let mut texts = serde_json::Map::new();
        for input in &bundle.inputs {
            texts.insert(
                input.kind.as_str().to_string(),
                Value::String(input.lines.join("\n")),
            );
        }
        Ok(json!({
            "sources": bundle.sources,
            "crash": bundle.crash,
            "texts": Value::Object(texts),
            "rules": self.diagnostic_rules().len(),
        }))
    }

    pub(super) async fn diag_analyze(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (_instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let analysis = fg_diag::analyze(&bundle, &self.diagnostic_rules());
        to_value(analysis)
    }

    pub(super) async fn diag_export(&self, params: &Value) -> Result<Value> {
        let request: InstanceOnlyRequest = from_value(params)?;
        let (instance, bundle) = self.diagnostic_bundle(&request.instance_id)?;
        let analysis: Analysis = fg_diag::analyze(&bundle, &self.diagnostic_rules());

        let mut log_texts: Vec<(String, String)> = Vec::new();
        for input in &bundle.inputs {
            let name = match input.kind {
                LogSourceKind::Stdout => "logs/session.log".to_string(),
                LogSourceKind::Log => "logs/latest.log".to_string(),
                LogSourceKind::Crash => analysis
                    .crash
                    .as_ref()
                    .map(|crash| {
                        crash
                            .file
                            .rsplit(['/', '\\'])
                            .next()
                            .unwrap_or("crash.txt")
                            .to_string()
                    })
                    .map(|name| format!("crash-reports/{name}"))
                    .unwrap_or_else(|| "crash-reports/latest.txt".to_string()),
                LogSourceKind::HsErr => bundle
                    .sources
                    .iter()
                    .find(|source| source.kind == LogSourceKind::HsErr)
                    .and_then(|source| source.path.rsplit(['/', '\\']).next().map(str::to_string))
                    .unwrap_or_else(|| "hs_err.log".to_string()),
            };
            log_texts.push((name, input.lines.join("\n")));
        }

        let manager = self.manager();
        let effective = manager.effective_json(&instance);
        let java = effective
            .get("java")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
            .unwrap_or("自动");
        let memory_mb = effective
            .get("memoryMb")
            .and_then(Value::as_u64)
            .and_then(|value| u32::try_from(value).ok())
            .unwrap_or_default();
        let loader = if instance.loader.is_vanilla() {
            String::new()
        } else {
            format!("{} {}", instance.loader.kind, instance.loader.version)
        };
        let instance_json = json!({
            "instance": {
                "id": instance.id,
                "name": instance.name,
                "minecraft": instance.minecraft,
                "loaderKind": instance.loader.kind,
                "loaderVersion": instance.loader.version,
                "favorite": instance.meta.favorite,
            },
            "effective": effective,
            "contentIndex": fg_content::ContentIndex::load(&self.layout.instance_dir(&instance.id))
                .map(|index| index.items.len())
                .unwrap_or(0),
        })
        .to_string();

        let request = BundleRequest {
            data_root: &self.root,
            instance_id: &instance.id,
            instance_name: &instance.name,
            minecraft: &instance.minecraft,
            loader: &loader,
            java,
            memory_mb,
            analysis: &analysis,
            log_texts,
            instance_json,
            app_version: env!("CARGO_PKG_VERSION"),
            platform: fg_core::Platform::detect().id(),
        };
        let bundle = fg_diag::export_bundle(&request)?;
        Ok(json!({ "bundle": bundle, "analysis": analysis }))
    }
}
