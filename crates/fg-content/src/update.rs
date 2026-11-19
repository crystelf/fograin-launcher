//! Update checks for installed content.
//!
//! Two strategies are used:
//!
//! - files recorded in the instance index ask the originating project for its
//!   newest compatible version and compare version ids;
//! - files copied in by hand are looked up on Modrinth by SHA-1 through
//!   `/version_files/update`, which is the same signal Modrinth's own launcher
//!   uses.
//!
//! CurseForge files without an index entry cannot be identified reliably (the
//! public fingerprint endpoint needs the full Murmur2 implementation), so they
//! are reported as unknown instead of producing a false update.

use std::{
    collections::HashMap,
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;
use sha1::{Digest, Sha1};

use crate::{
    ContentClient, ContentKind, ContentSource, LocalContent, index::ContentIndex, local::scan_local,
};

/// One available update.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateRow {
    pub kind: ContentKind,
    pub file_name: String,
    pub display_name: String,
    pub source: ContentSource,
    pub project_id: String,
    pub project_name: String,
    pub current_version: String,
    pub latest_version: String,
    pub latest_version_id: String,
    pub latest_file_name: Option<String>,
    pub latest_size: u64,
}

/// Result of one update check.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCheck {
    /// Files examined.
    pub checked: usize,
    pub updates: Vec<UpdateRow>,
    /// Files whose source could not be determined or refreshed.
    pub unknown: usize,
    pub warnings: Vec<String>,
}

/// Checks all installed content of `kind` (or every kind when `None`).
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the instance directories cannot be read.
/// Individual API failures become `unknown` rows so one broken project cannot
/// hide the other updates.
pub async fn check_updates(
    client: &ContentClient,
    instance_dir: &Path,
    game_dir: &Path,
    kind: Option<ContentKind>,
    game_version: &str,
    loader: Option<&str>,
) -> Result<UpdateCheck> {
    let index = ContentIndex::load(instance_dir)?;
    let kinds: Vec<ContentKind> = kind.map_or_else(|| ContentKind::ALL.to_vec(), |kind| vec![kind]);

    let mut check = UpdateCheck::default();
    let mut unindexed: Vec<(LocalContent, PathBuf)> = Vec::new();

    for kind in kinds {
        let items = scan_local(game_dir, kind, Some(&index))?;
        for item in items {
            check.checked += 1;
            if let Some(entry) = index.find(&item.file_name) {
                match check_indexed(
                    client,
                    &item,
                    entry.source.as_str(),
                    &entry.project_id,
                    &entry.version_id,
                    &entry.version_number,
                    game_version,
                    loader,
                )
                .await
                {
                    Ok(Some(row)) => check.updates.push(row),
                    Ok(None) => {}
                    Err(error) => {
                        check.unknown += 1;
                        check.warnings.push(format!(
                            "{}: {}",
                            item.display_name,
                            error.detail.as_deref().unwrap_or(&error.message_key)
                        ));
                    }
                }
            } else {
                unindexed.push((item.clone(), PathBuf::from(&item.path)));
            }
        }
    }

    let (checkable, skipped): (Vec<_>, Vec<_>) = unindexed
        .into_iter()
        .partition(|(item, _)| !item.kind.loader_specific() || loader.is_some());
    check.unknown += skipped.len();
    check_unindexed(client, &mut check, checkable, game_version).await?;
    check
        .updates
        .sort_by_key(|row| row.project_name.to_lowercase());
    Ok(check)
}

#[allow(clippy::too_many_arguments)]
async fn check_indexed(
    client: &ContentClient,
    item: &LocalContent,
    source: &str,
    project_id: &str,
    current_version_id: &str,
    current_version_number: &str,
    game_version: &str,
    loader: Option<&str>,
) -> Result<Option<UpdateRow>> {
    let source = ContentSource::parse(source)?;
    let loader = item.kind.loader_specific().then_some(loader).flatten();
    if item.kind == ContentKind::Mod && loader.is_none() {
        return Err(Error::new(
            ErrorKind::InvalidInput,
            "error.content.loader_required",
        ));
    }
    let latest = match source {
        ContentSource::Modrinth => {
            client
                .modrinth_latest_version(project_id, Some(game_version), loader)
                .await?
        }
        ContentSource::Curseforge => {
            client
                .curseforge_latest_version(project_id, Some(game_version), loader)
                .await?
        }
    };
    if latest.version_id == current_version_id {
        return Ok(None);
    }
    Ok(Some(UpdateRow {
        kind: item.kind,
        file_name: item.file_name.clone(),
        display_name: item.display_name.clone(),
        source,
        project_id: project_id.to_string(),
        project_name: item
            .project_name
            .clone()
            .unwrap_or_else(|| item.title.clone()),
        current_version: current_version_number.to_string(),
        latest_version: latest.version_number.clone(),
        latest_version_id: latest.version_id,
        latest_file_name: latest.file.clone().map(|file| file.file_name),
        latest_size: latest.file.map_or(0, |file| file.size),
    }))
}

async fn check_unindexed(
    client: &ContentClient,
    check: &mut UpdateCheck,
    items: Vec<(LocalContent, PathBuf)>,
    game_version: &str,
) -> Result<()> {
    if items.is_empty() {
        return Ok(());
    }

    let paths: Vec<PathBuf> = items.iter().map(|(_, path)| path.clone()).collect();
    let hashes = tokio::task::spawn_blocking(move || {
        paths
            .into_iter()
            .map(|path| file_sha1(&path).ok())
            .collect::<Vec<_>>()
    })
    .await
    .map_err(|error| {
        Error::with_detail(
            ErrorKind::Internal,
            "error.content.update",
            format!("hash worker failed: {error}"),
        )
    })?;

    let mut by_hash: HashMap<String, LocalContent> = HashMap::new();
    for ((item, _), hash) in items.into_iter().zip(hashes) {
        let Some(hash) = hash else {
            check.unknown += 1;
            continue;
        };
        by_hash.insert(hash, item);
    }
    if by_hash.is_empty() {
        return Ok(());
    }

    let hashes: Vec<String> = by_hash.keys().cloned().collect();
    for chunk in hashes.chunks(50) {
        let versions = match client
            .modrinth_versions_by_hashes(chunk, Some(game_version), None)
            .await
        {
            Ok(versions) => versions,
            Err(error) => {
                check.unknown += chunk.len();
                check.warnings.push(format!(
                    "Modrinth 更新查询失败：{}",
                    error.detail.as_deref().unwrap_or(&error.message_key)
                ));
                continue;
            }
        };

        for hash in chunk {
            let Some(item) = by_hash.get(hash) else {
                continue;
            };
            let Some(version) = versions.get(hash) else {
                check.unknown += 1;
                continue;
            };
            let same_file = version
                .file
                .as_ref()
                .and_then(|file| file.sha1.as_deref())
                .is_some_and(|sha1| sha1.eq_ignore_ascii_case(hash));
            if same_file {
                continue;
            }
            check.updates.push(UpdateRow {
                kind: item.kind,
                file_name: item.file_name.clone(),
                display_name: item.display_name.clone(),
                source: ContentSource::Modrinth,
                project_id: version.project_id.clone(),
                project_name: item.title.clone(),
                current_version: item
                    .version
                    .clone()
                    .unwrap_or_else(|| "手动安装".to_string()),
                latest_version: version.version_number.clone(),
                latest_version_id: version.version_id.clone(),
                latest_file_name: version.file.as_ref().map(|file| file.file_name.clone()),
                latest_size: version.file.as_ref().map_or(0, |file| file.size),
            });
        }
    }
    Ok(())
}

fn file_sha1(path: &Path) -> Result<String> {
    let mut file = File::open(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.io.read",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut hasher = Sha1::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.io.read",
                format!("{}: {error}", path.display()),
            )
        })?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hashes_match_the_documented_sha1() {
        let temp = tempfile::tempdir().expect("tempdir");
        let path = temp.path().join("file.bin");
        std::fs::write(&path, b"abc").expect("write");
        assert_eq!(
            file_sha1(&path).expect("hash"),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
    }
}
