//! Fabric and Quilt installers (§5.4).
//!
//! Both loaders publish a complete launcher profile at
//! `meta.<loader>.net/v…/versions/loader/<mc>/<loader>/profile/json`. The
//! profile is already an `inheritsFrom` child of the vanilla version, so the
//! installer only has to persist it and download the loader libraries.

use fg_core::{Error, ErrorKind, Result};

use crate::kind::{LoaderKind, is_safe_version_id};
use crate::plan::LoaderPlan;
use crate::planner::{LoaderPlanner, LoaderRequest};
use crate::profile::VersionProfile;

pub(crate) async fn plan(
    planner: &LoaderPlanner<'_>,
    request: &LoaderRequest<'_>,
) -> Result<LoaderPlan> {
    let url = profile_url(request.kind, request.minecraft, request.loader_version);
    let cache_key = format!(
        "{}/{}/{minecraft}/profile.json",
        request.kind.as_str(),
        request.loader_version,
        minecraft = request.minecraft
    );
    let bytes = planner
        .fetch_cached(&url, &cache_key)
        .await
        .map_err(|error| {
            if error.kind == ErrorKind::Network {
                Error::with_detail(
                    ErrorKind::Network,
                    "error.loader.profile_unavailable",
                    format!(
                        "{url}: {}. Check that {} {} supports Minecraft {}",
                        error.detail.as_deref().unwrap_or("request failed"),
                        request.kind.title(),
                        request.loader_version,
                        request.minecraft
                    ),
                )
            } else {
                error
            }
        })?;
    let raw: serde_json::Value = serde_json::from_slice(&bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.profile_parse",
            format!("{url}: {error}"),
        )
    })?;
    let profile = VersionProfile::parse(raw)?;

    let derived = request
        .kind
        .version_id(request.minecraft, request.loader_version);
    let version_id = if is_safe_version_id(&profile.id) && !profile.id.is_empty() {
        profile.id.clone()
    } else {
        derived
    };
    let version_json = profile.with_identity(&version_id, request.minecraft)?;
    let mut plan = planner.plan_header(
        request,
        version_id,
        profile.main_class.clone(),
        version_json,
    );

    for library in &profile.libraries {
        let file = planner.library_file(
            &library.path,
            library.download_url(request.kind.maven_base()),
            library.sha1.clone(),
            library.size,
        );
        LoaderPlanner::push_file(&mut plan, file);
    }

    Ok(plan)
}

fn profile_url(kind: LoaderKind, minecraft: &str, loader_version: &str) -> String {
    match kind {
        LoaderKind::Fabric => format!(
            "https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader_version}/profile/json"
        ),
        LoaderKind::Quilt => format!(
            "https://meta.quiltmc.org/v3/versions/loader/{minecraft}/{loader_version}/profile/json"
        ),
        _ => unreachable!("fabric planner only handles Fabric/Quilt"),
    }
}

/// Exposed for tests: a profile can be planned without network by reusing the
/// same conversion.
///
/// # Errors
///
/// Returns parse errors for malformed profiles.
pub fn plan_from_profile(
    profile: serde_json::Value,
    kind: LoaderKind,
    minecraft: &str,
    loader_version: &str,
    java_major: u32,
    planner: &LoaderPlanner<'_>,
) -> Result<LoaderPlan> {
    let version_id = kind.version_id(minecraft, loader_version);
    let profile = VersionProfile::parse(profile)?;
    let version_id = if is_safe_version_id(&profile.id) {
        profile.id.clone()
    } else {
        version_id
    };
    let version_json = profile.with_identity(&version_id, minecraft)?;
    let mut plan = planner.plan_header_from_parts(
        kind,
        loader_version,
        minecraft,
        java_major,
        version_id,
        profile.main_class.clone(),
        version_json,
    );
    for library in &profile.libraries {
        let file = planner.library_file(
            &library.path,
            library.download_url(kind.maven_base()),
            library.sha1.clone(),
            library.size,
        );
        LoaderPlanner::push_file(&mut plan, file);
    }
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plan::LoaderFilePurpose;
    use crate::planner::LoaderPaths;

    #[test]
    fn profile_urls_match_the_meta_apis() {
        assert_eq!(
            profile_url(LoaderKind::Fabric, "1.21.4", "0.16.9"),
            "https://meta.fabricmc.net/v2/versions/loader/1.21.4/0.16.9/profile/json"
        );
        assert_eq!(
            profile_url(LoaderKind::Quilt, "1.21.4", "0.27.0"),
            "https://meta.quiltmc.org/v3/versions/loader/1.21.4/0.27.0/profile/json"
        );
    }

    #[test]
    fn plans_libraries_from_a_profile_fixture() {
        let directory = tempfile::tempdir().expect("tempdir");
        let downloader = fg_net::Downloader::new(directory.path()).expect("downloader");
        let table = fg_net::MirrorTable::embedded().clone();
        let paths = LoaderPaths::new(
            directory.path(),
            directory.path().join("shared/libraries"),
            directory.path().join("cache"),
        );
        let planner = LoaderPlanner::new(
            &downloader,
            &table,
            fg_core::DownloadSource::Official,
            fg_net::Region::RestOfWorld,
            paths,
        );
        let profile: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/fabric-profile.json"))
                .expect("profile");
        let plan = plan_from_profile(
            profile,
            LoaderKind::Fabric,
            "1.21.4",
            "0.16.9",
            21,
            &planner,
        )
        .expect("plan");
        assert_eq!(plan.version_id, "fabric-loader-0.16.9-1.21.4");
        assert_eq!(
            plan.main_class,
            "net.fabricmc.loader.impl.launch.knot.KnotClient"
        );
        assert!(!plan.files.is_empty());
        assert_eq!(plan.files[0].purpose, LoaderFilePurpose::Library);
        assert!(plan.files[0].path.starts_with("shared/libraries"));
        assert!(plan.files[0].url.starts_with("https://maven.fabricmc.net/"));
        assert!(plan.steps.is_empty());
    }
}
