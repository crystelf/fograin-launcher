//! OptiFine installer (§5.4).
//!
//! OptiFine ships a self-contained installer jar. Two layouts exist:
//!
//! - modern builds contain `optifine/Patcher.class`; running it against the
//!   vanilla jar produces the patched library;
//! - older builds are themselves the patched library, so the installer jar is
//!   copied into `libraries/optifine/OptiFine/<mc>_<version>/`.
//!
//! In both cases the launcher adds `--tweakClass optifine.OptiFineTweaker`, the
//! launchwrapper jar from the installer, and an `inheritsFrom` profile.

use std::path::{Path, PathBuf};

use fg_core::{Error, ErrorKind, Result};

use crate::archive::InstallerArchive;
use crate::maven::Coordinate;
use crate::plan::{InstallStep, LoaderFilePurpose, LoaderPlan};
use crate::planner::{LoaderPlanner, LoaderRequest, file_entry, join_url};
use crate::profile::{append_game_arguments, library_value};

const LIBRARIES: &str = "https://libraries.minecraft.net";

pub(crate) async fn plan(
    planner: &LoaderPlanner<'_>,
    request: &LoaderRequest<'_>,
) -> Result<LoaderPlan> {
    let (kind_name, patch) = request.loader_version.rsplit_once('_').ok_or_else(|| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.loader.version_invalid",
            format!(
                "{} is not an OptiFine version (expected e.g. HD_U_I6)",
                request.loader_version
            ),
        )
    })?;
    if kind_name.is_empty() || patch.is_empty() {
        return Err(Error::new(
            ErrorKind::InvalidInput,
            "error.loader.version_invalid",
        ));
    }

    let lookup = crate::meta::optifine_lookup_version(request.minecraft);
    let url = format!(
        "{}/optifine/{lookup}/{kind_name}/{patch}",
        planner.table.bmclapi_root()
    );
    let installer_relative = planner
        .paths
        .cache
        .join("loaders")
        .join("optifine")
        .join(request.minecraft)
        .join(format!("OptiFine-{lookup}-{kind_name}-{patch}.jar"));
    let installer_relative = planner.paths.relative(&installer_relative)?;
    let installer_absolute = planner.paths.absolute(&installer_relative);
    download(planner, &url, &installer_absolute).await?;
    let archive = InstallerArchive::open(&installer_absolute)?;

    let version_id = request
        .kind
        .version_id(request.minecraft, request.loader_version);
    let maven_version = format!("{}_{}", request.minecraft, request.loader_version);
    let optifine_path = format!("optifine/OptiFine/{maven_version}/OptiFine-{maven_version}.jar");
    let optifine_library = planner.paths.library_path(&optifine_path);

    let mut plan = planner.plan_header(
        request,
        version_id.clone(),
        "net.minecraft.launchwrapper.Launch".to_string(),
        String::new(),
    );
    LoaderPlanner::push_file(
        &mut plan,
        file_entry(
            LoaderFilePurpose::Installer,
            installer_relative.clone(),
            url.clone(),
            None,
            None,
        ),
    );

    let patcher = ["optifine/Patcher.class", "notch/net/optifine/Patcher.class"]
        .into_iter()
        .find(|entry| archive.has(entry));
    if let Some(_patcher) = patcher {
        plan.steps.push(InstallStep::PatchOptiFine {
            input: planner.paths.relative(&request.client_jar)?,
            installer: installer_relative.clone(),
            patcher_class: "optifine.Patcher".to_string(),
            output: optifine_library.clone(),
        });
    } else {
        plan.steps.push(InstallStep::CopyFile {
            source: installer_relative.clone(),
            target: optifine_library.clone(),
        });
    }

    let (launcher_coordinate, launcher_artifact) = launchwrapper(planner, &archive, &mut plan)?;

    let mut raw = serde_json::json!({
        "id": version_id,
        "inheritsFrom": request.minecraft,
        "type": "release",
        "mainClass": "net.minecraft.launchwrapper.Launch",
        "libraries": [
            library_value(
                &format!("optifine:OptiFine:{maven_version}"),
                &optifine_path,
            ),
            library_value(
                &launcher_coordinate,
                &launcher_artifact,
            ),
        ],
    });
    append_game_arguments(&mut raw, &["--tweakClass", "optifine.OptiFineTweaker"]);
    plan.version_json = serde_json::to_string_pretty(&raw).map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.loader.profile_serialize",
            error.to_string(),
        )
    })?;
    Ok(plan)
}

fn launchwrapper(
    planner: &LoaderPlanner<'_>,
    archive: &InstallerArchive,
    plan: &mut LoaderPlan,
) -> Result<(String, String)> {
    if let Some(version) = archive
        .has("launchwrapper-of.txt")
        .then(|| archive.read("launchwrapper-of.txt"))
        .transpose()?
        .and_then(|bytes| String::from_utf8(bytes).ok())
        .map(|text| text.trim().to_string())
        .filter(|text| !text.is_empty())
    {
        let entry = format!("launchwrapper-of-{version}.jar");
        let coordinate = format!("optifine:launchwrapper-of:{version}");
        let path = Coordinate::parse(&coordinate)?.path();
        let target = planner.paths.library_path(&path);
        if archive.has(&entry) {
            plan.steps.push(InstallStep::Extract {
                archive: archive_path(planner, archive)?,
                entries: vec![crate::plan::ZipCopy { entry, target }],
            });
            return Ok((coordinate, path));
        }
    }

    if archive.has("launchwrapper-2.0.jar") {
        let coordinate = "optifine:launchwrapper:2.0".to_string();
        let path = Coordinate::parse(&coordinate)?.path();
        plan.steps.push(InstallStep::Extract {
            archive: archive_path(planner, archive)?,
            entries: vec![crate::plan::ZipCopy {
                entry: "launchwrapper-2.0.jar".to_string(),
                target: planner.paths.library_path(&path),
            }],
        });
        return Ok((coordinate, path));
    }

    let coordinate = "net.minecraft:launchwrapper:1.12".to_string();
    let path = Coordinate::parse(&coordinate)?.path();
    LoaderPlanner::push_file(
        plan,
        planner.library_file(&path, join_url(LIBRARIES, &path), None, None),
    );
    Ok((coordinate, path))
}

fn archive_path(planner: &LoaderPlanner<'_>, archive: &InstallerArchive) -> Result<PathBuf> {
    planner.paths.relative(archive.path())
}

async fn download(planner: &LoaderPlanner<'_>, url: &str, destination: &Path) -> Result<()> {
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

/// Exposed for tests: parse an OptiFine version string.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] when the string has no `_` separator.
pub fn split_version(version: &str) -> Result<(String, String)> {
    let (kind, patch) = version.rsplit_once('_').ok_or_else(|| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.loader.version_invalid",
            version.to_string(),
        )
    })?;
    Ok((kind.to_string(), patch.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_versions() {
        assert_eq!(
            split_version("HD_U_I6").expect("split"),
            ("HD_U".to_string(), "I6".to_string())
        );
        assert_eq!(
            split_version("HD_U_I5_pre4").expect("preview"),
            ("HD_U_I5".to_string(), "pre4".to_string())
        );
        assert!(split_version("I6").is_err());
    }

    #[test]
    fn library_value_has_the_expected_path() {
        let value = library_value(
            "optifine:OptiFine:1.20.1_HD_U_I6",
            "optifine/OptiFine/1.20.1_HD_U_I6/OptiFine-1.20.1_HD_U_I6.jar",
        );
        assert_eq!(
            value["downloads"]["artifact"]["path"],
            "optifine/OptiFine/1.20.1_HD_U_I6/OptiFine-1.20.1_HD_U_I6.jar"
        );
    }
}
