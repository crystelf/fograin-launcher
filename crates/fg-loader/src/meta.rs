//! Loader version discovery (§5.4).
//!
//! Fabric/Quilt expose JSON metadata, Forge/NeoForge publish Maven metadata and
//! OptiFine is listed by BMCLAPI. Every endpoint goes through the same mirror
//! table as the rest of the launcher, so `--source mirror` also works here.

use std::cmp::Ordering;

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{Downloader, MirrorTable, Region};
use serde::{Deserialize, Serialize};

use crate::kind::LoaderKind;

/// One selectable loader version.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoaderVersion {
    /// Value stored in `instance.toml` and passed back to the planner.
    pub version: String,
    pub stable: bool,
    /// Optional display label (OptiFine filename, Forge note).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub label: Option<String>,
    /// Optional compatibility note (required Forge version for OptiFine).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
}

impl LoaderVersion {
    fn new(version: impl Into<String>, stable: bool) -> Self {
        Self {
            version: version.into(),
            stable,
            label: None,
            note: None,
        }
    }
}

/// Fetch context; mirrors the knobs the rest of the core uses.
#[derive(Debug, Clone, Copy)]
pub struct VersionListQuery<'a> {
    pub kind: LoaderKind,
    pub minecraft: &'a str,
    pub limit: usize,
}

/// Loads the version list for `query`, newest first.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when the loader does not support the
/// Minecraft version and network/parse errors from the metadata endpoint.
pub async fn versions(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    query: VersionListQuery<'_>,
) -> Result<Vec<LoaderVersion>> {
    let mut versions = match query.kind {
        LoaderKind::Fabric => {
            fabric_like(downloader, table, source, region, query.minecraft, true).await?
        }
        LoaderKind::Quilt => {
            fabric_like(downloader, table, source, region, query.minecraft, false).await?
        }
        LoaderKind::Forge => forge_like(downloader, table, source, region, query.minecraft).await?,
        LoaderKind::NeoForge => {
            neoforge(downloader, table, source, region, query.minecraft).await?
        }
        LoaderKind::OptiFine => {
            optifine(downloader, table, source, region, query.minecraft).await?
        }
    };
    versions.truncate(query.limit.clamp(1, 500));
    Ok(versions)
}

fn fabric_url(minecraft: &str, fabric: bool) -> String {
    if fabric {
        format!("https://meta.fabricmc.net/v2/versions/loader/{minecraft}")
    } else {
        format!("https://meta.quiltmc.org/v3/versions/loader/{minecraft}")
    }
}

async fn fabric_like(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    minecraft: &str,
    fabric: bool,
) -> Result<Vec<LoaderVersion>> {
    #[derive(Deserialize)]
    struct Entry {
        loader: Loader,
    }
    #[derive(Deserialize)]
    struct Loader {
        version: String,
        #[serde(default = "default_true")]
        stable: bool,
    }

    let url = fabric_url(minecraft, fabric);
    let loader = if fabric { "fabric" } else { "quilt" };
    let fetched = fetch(
        downloader,
        table,
        source,
        region,
        &url,
        &format!("loader-versions/{loader}/{minecraft}"),
    )
    .await
    .map_err(|error| {
        missing_minecraft(error, if fabric { "Fabric" } else { "Quilt" }, minecraft)
    })?;
    let entries: Vec<Entry> = serde_json::from_slice(&fetched.bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.metadata",
            format!("{url}: {error}"),
        )
    })?;
    if entries.is_empty() {
        return Err(missing_minecraft_error(
            if fabric { "Fabric" } else { "Quilt" },
            minecraft,
        ));
    }
    let mut versions: Vec<LoaderVersion> = entries
        .into_iter()
        .map(|entry| LoaderVersion::new(entry.loader.version, entry.loader.stable))
        .collect();
    sort_versions(&mut versions);
    Ok(versions)
}

async fn forge_like(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    minecraft: &str,
) -> Result<Vec<LoaderVersion>> {
    let url = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    let text = fetch_text(
        downloader,
        table,
        source,
        region,
        url,
        "loader-versions/forge/maven-metadata.xml",
    )
    .await?;
    let prefix = format!("{minecraft}-");
    let mut versions: Vec<LoaderVersion> = metadata_versions(&text)
        .into_iter()
        .filter_map(|full| {
            full.strip_prefix(&prefix)
                .map(|loader| LoaderVersion::new(loader.to_string(), true))
        })
        .collect();
    sort_versions(&mut versions);
    Ok(versions)
}

async fn neoforge(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    minecraft: &str,
) -> Result<Vec<LoaderVersion>> {
    // NeoForge 1.20.1 lives under `net.neoforged:forge` and uses the
    // `<minecraft>-<version>` layout; 1.20.2+ uses `net.neoforged:neoforge`
    // with `<minor>.<patch>.<build>` versions.
    if minecraft == "1.20.1" {
        let url = "https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml";
        let text = fetch_text(
            downloader,
            table,
            source,
            region,
            url,
            "loader-versions/neoforge/forge-metadata.xml",
        )
        .await?;
        let prefix = "1.20.1-";
        let mut versions: Vec<LoaderVersion> = metadata_versions(&text)
            .into_iter()
            .filter_map(|full| {
                full.strip_prefix(prefix)
                    .map(|loader| LoaderVersion::new(loader.to_string(), true))
            })
            .collect();
        sort_versions(&mut versions);
        return Ok(versions);
    }

    let Some(prefix) = neoforge_version_prefix(minecraft) else {
        return Err(missing_minecraft_error("NeoForge", minecraft));
    };
    let url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    let text = fetch_text(
        downloader,
        table,
        source,
        region,
        url,
        "loader-versions/neoforge/maven-metadata.xml",
    )
    .await?;
    let mut versions: Vec<LoaderVersion> = metadata_versions(&text)
        .into_iter()
        .filter(|version| version.starts_with(&prefix))
        .map(|version| LoaderVersion::new(version, true))
        .collect();
    sort_versions(&mut versions);
    Ok(versions)
}

async fn optifine(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    minecraft: &str,
) -> Result<Vec<LoaderVersion>> {
    #[derive(Deserialize)]
    struct Entry {
        mcversion: String,
        #[serde(rename = "type")]
        kind: String,
        patch: String,
        #[serde(default)]
        filename: String,
        #[serde(default)]
        forge: String,
    }

    let lookup = optifine_lookup_version(minecraft);
    let url = format!("{}/optifine/{lookup}", table.bmclapi_root());
    let fetched = fetch(
        downloader,
        table,
        source,
        region,
        &url,
        &format!("loader-versions/optifine/{lookup}"),
    )
    .await
    .map_err(|error| missing_minecraft(error, "OptiFine", minecraft))?;
    let entries: Vec<Entry> = serde_json::from_slice(&fetched.bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.metadata",
            format!("{url}: {error}"),
        )
    })?;
    let mut versions: Vec<LoaderVersion> = entries
        .into_iter()
        .filter(|entry| entry.mcversion == lookup || entry.mcversion == minecraft)
        .map(|entry| {
            let stable = !(entry.patch.starts_with("pre") || entry.patch.starts_with("alpha"));
            let note = entry
                .forge
                .strip_prefix("Forge ")
                .filter(|value| *value != "N/A")
                .map(ToString::to_string);
            LoaderVersion {
                version: format!("{}_{}", entry.kind, entry.patch),
                stable,
                label: (!entry.filename.is_empty()).then_some(entry.filename),
                note,
            }
        })
        .collect();
    sort_versions(&mut versions);
    Ok(versions)
}

/// `1.21.1` → `21.1.`, `1.21` → `21.0.`, snapshots → `None`.
#[must_use]
pub fn neoforge_version_prefix(minecraft: &str) -> Option<String> {
    let rest = minecraft.strip_prefix("1.")?;
    let mut parts = rest.split('.');
    let minor = parts.next()?;
    let patch = parts.next().unwrap_or("0");
    if parts.next().is_some()
        || minor.is_empty()
        || patch.is_empty()
        || !minor.chars().all(|character| character.is_ascii_digit())
        || !patch.chars().all(|character| character.is_ascii_digit())
    {
        return None;
    }
    Some(format!("{minor}.{patch}."))
}

/// BMCLAPI names 1.8/1.9 with a `.0` suffix.
#[must_use]
pub fn optifine_lookup_version(minecraft: &str) -> String {
    match minecraft {
        "1.8" | "1.9" => format!("{minecraft}.0"),
        other => other.to_string(),
    }
}

/// Forge/NeoForge Maven metadata `<version>` values in document order.
#[must_use]
pub fn metadata_versions(xml: &str) -> Vec<String> {
    xml.split("<version>")
        .skip(1)
        .filter_map(|chunk| {
            chunk
                .find("</version>")
                .map(|end| unescape_xml(chunk[..end].trim()))
        })
        .collect()
}

fn unescape_xml(value: &str) -> String {
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

fn sort_versions(versions: &mut [LoaderVersion]) {
    versions.sort_by(|left, right| {
        right
            .stable
            .cmp(&left.stable)
            .then_with(|| natural_cmp(&right.version, &left.version))
    });
}

/// Compares version strings with numeric runs handled as numbers.
#[must_use]
pub fn natural_cmp(left: &str, right: &str) -> Ordering {
    #[derive(PartialEq, Eq, PartialOrd, Ord)]
    enum Segment<'a> {
        Text(&'a str),
        Number(u64),
    }

    fn segments(value: &str) -> Vec<Segment<'_>> {
        let bytes = value.as_bytes();
        let mut result = Vec::new();
        let mut index = 0;
        while index < bytes.len() {
            let start = index;
            let numeric = bytes[index].is_ascii_digit();
            while index < bytes.len() && bytes[index].is_ascii_digit() == numeric {
                index += 1;
            }
            let chunk = &value[start..index];
            if numeric {
                result.push(Segment::Number(chunk.parse().unwrap_or(u64::MAX)));
            } else {
                result.push(Segment::Text(chunk));
            }
        }
        result
    }

    segments(left).cmp(&segments(right))
}

async fn fetch(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    url: &str,
    cache_key: &str,
) -> Result<fg_net::engine::FetchedBytes> {
    let candidates = table.candidates(url, source, region);
    downloader.fetch_bytes(candidates, cache_key).await
}

async fn fetch_text(
    downloader: &Downloader,
    table: &MirrorTable,
    source: DownloadSource,
    region: Region,
    url: &str,
    cache_key: &str,
) -> Result<String> {
    let fetched = fetch(downloader, table, source, region, url, cache_key).await?;
    String::from_utf8(fetched.bytes.to_vec()).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.metadata",
            format!("{url}: {error}"),
        )
    })
}

fn missing_minecraft(error: Error, loader: &str, minecraft: &str) -> Error {
    let not_found = error
        .detail
        .as_deref()
        .is_some_and(|detail| detail.contains("404") || detail.contains("Not Found"));
    if error.kind == ErrorKind::NotFound || not_found {
        missing_minecraft_error(loader, minecraft)
    } else {
        error
    }
}

fn missing_minecraft_error(loader: &str, minecraft: &str) -> Error {
    Error::with_detail(
        ErrorKind::NotFound,
        "error.loader.unsupported_minecraft",
        format!("{loader} has no builds for Minecraft {minecraft}"),
    )
}

const fn default_true() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn metadata_versions_are_extracted_in_order() {
        let xml = r#"<?xml version="1.0"?>
            <metadata><versioning><versions>
              <version>1.20.1-47.2.0</version>
              <version>1.20.1-47.4.29</version>
            </versions></versioning></metadata>"#;
        assert_eq!(metadata_versions(xml), ["1.20.1-47.2.0", "1.20.1-47.4.29"]);
    }

    #[test]
    fn neoforge_prefix_follows_the_version_scheme() {
        assert_eq!(neoforge_version_prefix("1.21.1").as_deref(), Some("21.1."));
        assert_eq!(neoforge_version_prefix("1.21").as_deref(), Some("21.0."));
        assert_eq!(neoforge_version_prefix("1.20.4").as_deref(), Some("20.4."));
        assert_eq!(neoforge_version_prefix("1.20.5-pre1"), None);
        assert_eq!(neoforge_version_prefix("26.1"), None);
    }

    #[test]
    fn optifine_versions_sort_stable_and_numeric() {
        let mut versions = vec![
            LoaderVersion::new("HD_U_I10", true),
            LoaderVersion::new("HD_U_I6", true),
            LoaderVersion::new("HD_U_I7_pre1", false),
            LoaderVersion::new("HD_U_I9", true),
        ];
        sort_versions(&mut versions);
        assert_eq!(
            versions
                .iter()
                .map(|version| version.version.as_str())
                .collect::<Vec<_>>(),
            ["HD_U_I10", "HD_U_I9", "HD_U_I6", "HD_U_I7_pre1"]
        );
    }

    #[test]
    fn natural_compare_handles_numeric_runs() {
        assert_eq!(natural_cmp("1.20.10", "1.20.9"), Ordering::Greater);
        assert_eq!(natural_cmp("HD_U_I9", "HD_U_I10"), Ordering::Less);
        assert_eq!(natural_cmp("same", "same"), Ordering::Equal);
    }

    #[test]
    fn optifine_lookup_maps_legacy_names() {
        assert_eq!(optifine_lookup_version("1.8"), "1.8.0");
        assert_eq!(optifine_lookup_version("1.20.1"), "1.20.1");
    }
}
