//! Forge and NeoForge installers (§5.4).
//!
//! Modern installers ship an `install_profile.json` with:
//!
//! - a `version.json` child profile,
//! - embedded Maven artifacts under `maven/`,
//! - a `data` variable table, and
//! - client/server processors that patch the vanilla jar.
//!
//! Only client processors run. `DOWNLOAD_MOJMAPS` is replaced by a direct
//! download of the vanilla `client_mappings` artifact, because running the
//! installer tool would hit Mojang from inside a Java process we do not
//! control.

use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Deserialize;
use serde_json::Value;

use crate::archive::InstallerArchive;
use crate::kind::LoaderKind;
use crate::maven::Coordinate;
use crate::plan::{InstallStep, LoaderFilePurpose, LoaderPlan, OutputCheck, ZipCopy};
use crate::planner::{LoaderPlanner, LoaderRequest, checked_version_id, file_entry, join_url};
use crate::profile::{VersionProfile, parse_install_libraries};

const FORGE_MAVEN: &str = "https://maven.minecraftforge.net";
const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases";
const LIBRARIES: &str = "https://libraries.minecraft.net";

pub(crate) async fn plan(
    planner: &LoaderPlanner<'_>,
    request: &LoaderRequest<'_>,
) -> Result<LoaderPlan> {
    let installer = installer_reference(request.kind, request.minecraft, request.loader_version)?;
    let relative = planner.installer_path(request.kind, request.minecraft, &installer.file_name);
    let absolute = planner.paths.absolute(&relative);
    download_installer(planner, &installer.url, &absolute).await?;

    let archive = InstallerArchive::open(&absolute)?;
    let profile_bytes = archive.read("install_profile.json")?;
    let profile: InstallProfile = serde_json::from_slice(&profile_bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.install_profile",
            format!("install_profile.json: {error}"),
        )
    })?;

    if profile
        .minecraft
        .as_deref()
        .is_some_and(|version| version != request.minecraft)
    {
        return Err(Error::with_detail(
            ErrorKind::Conflict,
            "error.loader.minecraft_mismatch",
            format!(
                "installer targets {}, not {}",
                profile.minecraft.unwrap_or_default(),
                request.minecraft
            ),
        ));
    }

    let version_value = profile_value(&profile, &archive)?;
    let version_profile = VersionProfile::parse(version_value)?;
    let fallback = request
        .kind
        .version_id(request.minecraft, request.loader_version);
    let version_id = checked_version_id(&version_profile.id, &fallback);
    let version_json = version_profile.with_identity(&version_id, request.minecraft)?;
    let mut plan = planner.plan_header(
        request,
        version_id.clone(),
        version_profile.main_class.clone(),
        version_json,
    );

    LoaderPlanner::push_file(
        &mut plan,
        file_entry(
            LoaderFilePurpose::Installer,
            relative.clone(),
            installer.url.clone(),
            None,
            None,
        ),
    );

    let mut embedded = BTreeSet::new();
    for library in &version_profile.libraries {
        plan_library(
            planner,
            &mut plan,
            &archive,
            &mut embedded,
            LibraryToPlan {
                path: library.path.clone(),
                url: library.url.clone(),
                sha1: library.sha1.clone(),
                size: library.size,
            },
            LIBRARIES,
        )?;
    }

    if let Some(install) = &profile.install {
        plan_legacy_install(
            planner,
            &mut plan,
            &archive,
            &mut embedded,
            install,
            request.minecraft,
        )?;
    }

    let install_libraries = parse_install_libraries(&profile.libraries)?;
    for library in &install_libraries {
        plan_library(
            planner,
            &mut plan,
            &archive,
            &mut embedded,
            LibraryToPlan {
                path: library.path.clone(),
                url: library.url.clone(),
                sha1: library.sha1.clone(),
                size: library.size,
            },
            request.kind.maven_base(),
        )?;
    }

    let mut vars = BTreeMap::new();
    vars.insert("SIDE".to_string(), "client".to_string());
    vars.insert(
        "MINECRAFT_JAR".to_string(),
        request.client_jar.display().to_string(),
    );
    vars.insert(
        "MINECRAFT_VERSION".to_string(),
        request.minecraft.to_string(),
    );
    vars.insert(
        "ROOT".to_string(),
        planner
            .paths
            .libraries
            .parent()
            .unwrap_or(&planner.paths.root)
            .display()
            .to_string(),
    );
    vars.insert("INSTALLER".to_string(), absolute.display().to_string());
    vars.insert(
        "LIBRARY_DIR".to_string(),
        planner.paths.libraries.display().to_string(),
    );

    let data_resolver = DataResolver {
        planner,
        archive: &archive,
        kind: request.kind,
        minecraft: request.minecraft,
    };
    for (key, value) in &profile.data {
        let Some(literal) = value.client() else {
            continue;
        };
        let resolved = data_resolver.resolve(&mut plan, &mut embedded, &vars, literal)?;
        vars.insert(key.clone(), resolved);
    }

    plan_processors(
        planner,
        &mut plan,
        &archive,
        &mut embedded,
        &profile.processors,
        &vars,
        request,
    )?;

    Ok(plan)
}

fn plan_legacy_install(
    planner: &LoaderPlanner<'_>,
    plan: &mut LoaderPlan,
    archive: &InstallerArchive,
    embedded: &mut BTreeSet<String>,
    install: &LegacyInstall,
    minecraft: &str,
) -> Result<()> {
    let Some(file_path) = install.file_path.as_deref() else {
        return Ok(());
    };
    let Some(coordinate_raw) = install.path.as_deref() else {
        return Ok(());
    };
    let coordinate = Coordinate::parse(coordinate_raw)?;
    let target = planner.paths.library_path(&coordinate.path());
    plan.remove_file(&target);
    let archive_relative = planner.paths.relative(archive.path())?;
    add_extract(
        plan,
        archive,
        &archive_relative,
        file_path,
        &target,
        embedded,
        "legacy universal",
    );
    let _ = minecraft;
    Ok(())
}

fn profile_value(profile: &InstallProfile, archive: &InstallerArchive) -> Result<Value> {
    if let Some(json) = &profile.json {
        let bytes = archive.read(json)?;
        return parse_json(&bytes, json);
    }
    if let Some(info) = &profile.version_info {
        return Ok(info.clone());
    }
    if archive.has("version.json") {
        let bytes = archive.read("version.json")?;
        return parse_json(&bytes, "version.json");
    }
    Err(Error::new(
        ErrorKind::NotFound,
        "error.loader.version_json_missing",
    ))
}

fn parse_json(bytes: &[u8], name: &str) -> Result<Value> {
    serde_json::from_slice(bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.version_json",
            format!("{name}: {error}"),
        )
    })
}

fn installer_reference(
    kind: LoaderKind,
    minecraft: &str,
    loader_version: &str,
) -> Result<InstallerReference> {
    let (url, file_name) = match kind {
        LoaderKind::Forge => {
            let full = format!("{minecraft}-{loader_version}");
            (
                format!("{FORGE_MAVEN}/net/minecraftforge/forge/{full}/forge-{full}-installer.jar"),
                format!("forge-{full}-installer.jar"),
            )
        }
        LoaderKind::NeoForge if minecraft == "1.20.1" => {
            let full = format!("{minecraft}-{loader_version}");
            (
                format!("{NEOFORGE_MAVEN}/net/neoforged/forge/{full}/forge-{full}-installer.jar"),
                format!("neoforge-{full}-installer.jar"),
            )
        }
        LoaderKind::NeoForge => (
            format!(
                "{NEOFORGE_MAVEN}/net/neoforged/neoforge/{loader_version}/neoforge-{loader_version}-installer.jar"
            ),
            format!("neoforge-{loader_version}-installer.jar"),
        ),
        _ => {
            return Err(Error::with_detail(
                ErrorKind::Internal,
                "error.loader.internal",
                format!("{kind} is not a Forge-family loader"),
            ));
        }
    };
    Ok(InstallerReference { url, file_name })
}

struct InstallerReference {
    url: String,
    file_name: String,
}

async fn download_installer(
    planner: &LoaderPlanner<'_>,
    url: &str,
    destination: &Path,
) -> Result<()> {
    let candidates = planner
        .table
        .candidates(url, planner.source, planner.region);
    let parsed = candidates
        .into_iter()
        .map(|candidate| fg_net::Candidate::parse(&candidate))
        .collect::<Result<Vec<_>>>()?;
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let request =
        fg_net::DownloadRequest::new(destination, parsed).with_priority(fg_net::Priority::Manifest);
    planner
        .downloader
        .download(request)
        .await
        .map_err(|error| {
            Error::with_detail(
                error.kind,
                "error.loader.installer_download",
                format!("{url}: {}", error.detail.unwrap_or_default()),
            )
        })?;
    Ok(())
}

struct LibraryToPlan {
    path: String,
    url: Option<String>,
    sha1: Option<String>,
    size: Option<u64>,
}

fn plan_library(
    planner: &LoaderPlanner<'_>,
    plan: &mut LoaderPlan,
    archive: &InstallerArchive,
    embedded: &mut BTreeSet<String>,
    library: LibraryToPlan,
    fallback: &str,
) -> Result<()> {
    let LibraryToPlan {
        path,
        url,
        sha1,
        size,
    } = library;
    if archive.has_maven(&path) {
        let target = planner.paths.library_path(&path);
        plan.remove_file(&target);
        let archive_relative = planner.paths.relative(archive.path())?;
        add_extract(
            plan,
            archive,
            &archive_relative,
            &format!("maven/{path}"),
            &target,
            embedded,
            &path,
        );
        return Ok(());
    }
    let url = url
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| join_url(fallback, &path));
    let file = planner.library_file(&path, url, sha1, size);
    LoaderPlanner::push_file(plan, file);
    Ok(())
}

fn add_extract(
    plan: &mut LoaderPlan,
    archive: &InstallerArchive,
    archive_relative: &Path,
    entry: &str,
    target: &Path,
    embedded: &mut BTreeSet<String>,
    label: &str,
) {
    let key = format!("{}|{}", archive_relative.display(), target.display());
    if !embedded.insert(key) {
        return;
    }
    if !archive.has(entry) {
        tracing::warn!(entry, label, "installer entry missing");
        return;
    }
    let copy = ZipCopy {
        entry: entry.trim_start_matches('/').to_string(),
        target: target.to_path_buf(),
    };
    if let Some(InstallStep::Extract { archive: _, entries }) = plan
        .steps
        .iter_mut()
        .find(|step| matches!(step, InstallStep::Extract { archive: existing, .. } if existing == archive_relative))
    {
        entries.push(copy);
        return;
    }
    plan.steps.push(InstallStep::Extract {
        archive: archive_relative.to_path_buf(),
        entries: vec![copy],
    });
}

struct DataResolver<'a, 'p> {
    planner: &'a LoaderPlanner<'p>,
    archive: &'a InstallerArchive,
    kind: LoaderKind,
    minecraft: &'a str,
}

impl DataResolver<'_, '_> {
    fn resolve(
        &self,
        plan: &mut LoaderPlan,
        embedded: &mut BTreeSet<String>,
        vars: &BTreeMap<String, String>,
        literal: &str,
    ) -> Result<String> {
        let planner = self.planner;
        let archive = self.archive;
        let kind = self.kind;
        let minecraft = self.minecraft;
        let literal = literal.trim();
        if let Some(coordinate) = literal
            .strip_prefix('[')
            .and_then(|value| value.strip_suffix(']'))
        {
            let coordinate = Coordinate::parse(coordinate)?;
            return Ok(planner
                .paths
                .libraries
                .join(coordinate.path())
                .display()
                .to_string());
        }
        if let Some(entry) = literal.strip_prefix('/') {
            let file_name = Path::new(entry)
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_else(|| "data".to_string());
            let target = planner.installer_path(kind, minecraft, &format!("data/{file_name}"));
            let archive_relative = planner.paths.relative(archive.path())?;
            add_extract(
                plan,
                archive,
                &archive_relative,
                entry,
                &target,
                embedded,
                "installer data",
            );
            return Ok(planner.paths.absolute(&target).display().to_string());
        }
        if let Some(value) = literal
            .strip_prefix('\'')
            .and_then(|value| value.strip_suffix('\''))
        {
            return Ok(value.to_string());
        }
        replace_tokens(literal, vars)
    }
}

fn resolve_argument(
    planner: &LoaderPlanner<'_>,
    vars: &BTreeMap<String, String>,
    literal: &str,
) -> Result<String> {
    let literal = literal.trim();
    if let Some(coordinate) = literal
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
    {
        let coordinate = Coordinate::parse(coordinate)?;
        return Ok(planner
            .paths
            .libraries
            .join(coordinate.path())
            .display()
            .to_string());
    }
    if let Some(key) = literal
        .strip_prefix('{')
        .and_then(|value| value.strip_suffix('}'))
    {
        return vars.get(key).cloned().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.install_profile",
                format!("unknown install variable {{{key}}}"),
            )
        });
    }
    if let Some(value) = literal
        .strip_prefix('\'')
        .and_then(|value| value.strip_suffix('\''))
    {
        return Ok(value.to_string());
    }
    replace_tokens(literal, vars)
}

fn replace_tokens(value: &str, vars: &BTreeMap<String, String>) -> Result<String> {
    let mut output = String::with_capacity(value.len());
    let mut rest = value;
    while let Some(start) = rest.find('{') {
        output.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        let Some(end) = after.find('}') else {
            output.push_str(&rest[start..]);
            return Ok(output);
        };
        let key = &after[..end];
        let replacement = vars.get(key).ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.install_profile",
                format!("unknown install variable {{{key}}} in {value}"),
            )
        })?;
        output.push_str(replacement);
        rest = &after[end + 1..];
    }
    output.push_str(rest);
    Ok(output)
}

fn plan_processors(
    planner: &LoaderPlanner<'_>,
    plan: &mut LoaderPlan,
    archive: &InstallerArchive,
    embedded: &mut BTreeSet<String>,
    processors: &[Processor],
    vars: &BTreeMap<String, String>,
    request: &LoaderRequest<'_>,
) -> Result<()> {
    for (index, processor) in processors.iter().enumerate() {
        if !client_side(&processor.sides) {
            continue;
        }
        let args = processor
            .args
            .iter()
            .map(|arg| resolve_argument(planner, vars, arg))
            .collect::<Result<Vec<_>>>()?;

        if is_download_mojmaps(&args) {
            plan_mojmaps(planner, plan, &args, request)?;
            continue;
        }

        let jar = Coordinate::parse(&processor.jar)?;
        let jar_path =
            ensure_processor_artifact(planner, plan, archive, embedded, &jar, request.kind)?;
        let mut classpath = Vec::with_capacity(processor.classpath.len());
        for entry in &processor.classpath {
            let coordinate = Coordinate::parse(entry)?;
            classpath.push(ensure_processor_artifact(
                planner,
                plan,
                archive,
                embedded,
                &coordinate,
                request.kind,
            )?);
        }

        let mut outputs = Vec::new();
        for (output, expected) in &processor.outputs {
            let output = resolve_argument(planner, vars, output)?;
            let expected = resolve_argument(planner, vars, expected)?;
            outputs.push(OutputCheck {
                path: relative_to_root(planner, &output)?,
                sha1: expected,
            });
        }

        let label = format!("processor {} {}", index + 1, jar.label());
        plan.steps.push(InstallStep::RunProcessor {
            label,
            jar: relative_to_root(planner, &jar_path.display().to_string())?,
            classpath: classpath
                .into_iter()
                .map(|path| relative_to_root(planner, &path.display().to_string()))
                .collect::<Result<Vec<_>>>()?,
            args,
            outputs,
        });
    }
    Ok(())
}

fn ensure_processor_artifact(
    planner: &LoaderPlanner<'_>,
    plan: &mut LoaderPlan,
    archive: &InstallerArchive,
    embedded: &mut BTreeSet<String>,
    coordinate: &Coordinate,
    _kind: LoaderKind,
) -> Result<PathBuf> {
    let path = coordinate.path();
    let absolute = planner.paths.libraries.join(&path);
    if archive.has_maven(&path) {
        let archive_relative = planner.paths.relative(archive.path())?;
        add_extract(
            plan,
            archive,
            &archive_relative,
            &format!("maven/{path}"),
            &absolute,
            embedded,
            &coordinate.label(),
        );
    } else {
        let file = planner.library_file(&path, coordinate.url(_kind.maven_base()), None, None);
        LoaderPlanner::push_file(plan, file);
    }
    Ok(absolute)
}

fn plan_mojmaps(
    planner: &LoaderPlanner<'_>,
    plan: &mut LoaderPlan,
    args: &[String],
    request: &LoaderRequest<'_>,
) -> Result<()> {
    let output = flag_value(args, "--output")
        .ok_or_else(|| Error::new(ErrorKind::Parse, "error.loader.mappings_output"))?;
    let Some(mappings) = &request.client_mappings else {
        return Err(Error::new(
            ErrorKind::NotFound,
            "error.loader.mappings_missing",
        ));
    };
    let relative = relative_to_root(planner, &output)?;
    plan.remove_file(&relative);
    let file = file_entry(
        LoaderFilePurpose::Mappings,
        relative,
        mappings.url.clone(),
        mappings.sha1.clone(),
        mappings.size,
    );
    LoaderPlanner::push_file(plan, file);
    Ok(())
}

fn is_download_mojmaps(args: &[String]) -> bool {
    flag_value(args, "--task").as_deref() == Some("DOWNLOAD_MOJMAPS")
        && flag_value(args, "--side")
            .as_deref()
            .is_none_or(|side| side == "client")
}

fn flag_value(args: &[String], flag: &str) -> Option<String> {
    args.iter()
        .position(|arg| arg == flag)
        .and_then(|index| args.get(index + 1))
        .cloned()
}

fn relative_to_root(planner: &LoaderPlanner<'_>, path: &str) -> Result<PathBuf> {
    let path = PathBuf::from(path);
    if path.is_absolute() {
        planner.paths.relative(&path)
    } else {
        Ok(path)
    }
}

fn client_side(sides: &[String]) -> bool {
    sides.is_empty() || sides.iter().any(|side| side == "client")
}

// ---- install_profile.json ------------------------------------------------

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallProfile {
    #[serde(default)]
    _spec: u32,
    #[serde(default)]
    minecraft: Option<String>,
    #[serde(default)]
    _version: Option<String>,
    #[serde(default)]
    json: Option<String>,
    #[serde(default)]
    install: Option<LegacyInstall>,
    #[serde(default)]
    version_info: Option<Value>,
    #[serde(default)]
    data: BTreeMap<String, DataValue>,
    #[serde(default)]
    processors: Vec<Processor>,
    #[serde(default)]
    libraries: Vec<Value>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LegacyInstall {
    #[serde(default)]
    file_path: Option<String>,
    #[serde(default)]
    path: Option<String>,
    #[serde(default)]
    _target: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum DataValue {
    One(String),
    Sided {
        #[serde(default)]
        client: Option<String>,
        #[serde(default)]
        _server: Option<String>,
    },
}

impl DataValue {
    fn client(&self) -> Option<&str> {
        match self {
            Self::One(value) => Some(value),
            Self::Sided { client, .. } => client.as_deref(),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Processor {
    #[serde(default)]
    sides: Vec<String>,
    jar: String,
    #[serde(default)]
    classpath: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
    #[serde(default)]
    outputs: BTreeMap<String, String>,
}

// ---- test helpers ---------------------------------------------------------

#[cfg(test)]
impl InstallProfile {
    /// Parses an `install_profile.json` body; used by unit tests.
    fn parse(bytes: &[u8]) -> Result<Self> {
        serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.install_profile",
                error.to_string(),
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_processors_are_selected() {
        assert!(client_side(&[]));
        assert!(client_side(&["client".into()]));
        assert!(!client_side(&["server".into()]));
    }

    #[test]
    fn download_mojmaps_is_detected() {
        let args = vec![
            "--task".into(),
            "DOWNLOAD_MOJMAPS".into(),
            "--version".into(),
            "1.20.1".into(),
            "--side".into(),
            "client".into(),
            "--output".into(),
            "/tmp/client-mappings.txt".into(),
        ];
        assert!(is_download_mojmaps(&args));
        assert_eq!(
            flag_value(&args, "--output").as_deref(),
            Some("/tmp/client-mappings.txt")
        );
    }

    #[test]
    fn placeholder_replacement_is_strict() {
        let vars = BTreeMap::from([
            ("ROOT".to_string(), "/data".to_string()),
            ("SIDE".to_string(), "client".to_string()),
        ]);
        assert_eq!(
            replace_tokens("{ROOT}/libraries", &vars).expect("replace"),
            "/data/libraries"
        );
        assert!(replace_tokens("{MISSING}", &vars).is_err());
    }

    #[test]
    fn parses_a_modern_install_profile() {
        let profile = InstallProfile::parse(
            br#"{
              "spec": 1,
              "minecraft": "1.20.1",
              "json": "/version.json",
              "data": {
                "MAPPINGS": { "client": "[de.oceanlabs.mcp:mcp_config:1.20.1@zip]" },
                "BINPATCH": { "client": "/data/client.lzma" }
              },
              "processors": [
                { "sides": ["server"], "jar": "a:b:1" },
                { "jar": "c:d:2", "classpath": ["e:f:3"], "args": ["--x", "{ROOT}"] }
              ],
              "libraries": []
            }"#,
        )
        .expect("profile");
        assert_eq!(profile.minecraft.as_deref(), Some("1.20.1"));
        assert_eq!(profile.data["BINPATCH"].client(), Some("/data/client.lzma"));
        assert_eq!(profile.processors.len(), 2);
    }
}
