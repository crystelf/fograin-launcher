//! Managed Java runtime catalog (§5.5, M6).
//!
//! Mojang publishes the exact JRE builds the official launcher uses at
//! `launchermeta.mojang.com/v1/products/java-runtime/.../all.json`. The index
//! maps a platform id (`mac-os-arm64`, `windows-x64`, `linux`, …) to runtime
//! components (`jre-legacy`, `java-runtime-gamma`, …), and each component lists
//! one or more versioned manifests.
//!
//! Using the official catalog keeps the major version in lockstep with each
//! Minecraft version's `javaVersion.component` and gives us the x86_64 runtime
//! on Apple Silicon for 1.8–1.16, which is what the old LWJGL natives need.

use std::collections::BTreeMap;

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

use crate::runtime::{JavaArch, JavaVersion};

/// Official Mojang runtime index used by the vanilla launcher.
pub const MOJANG_RUNTIME_INDEX_URL: &str = "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Vendor id written into the runtime marker and shown by the UI.
pub const MOJANG_VENDOR: &str = "mojang";

/// One downloadable artifact referenced by the runtime index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeArtifact {
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

/// A platform the catalog can provide a native (or translated) runtime for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RuntimePlatform {
    /// Mojang platform key, e.g. `mac-os-arm64`.
    pub id: &'static str,
    /// Architecture of the binaries in that platform directory.
    pub arch: JavaArch,
}

/// One runtime the launcher can download.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaRuntimeCandidate {
    pub vendor: String,
    pub platform: String,
    pub component: String,
    pub version: String,
    pub major: u32,
    pub arch: JavaArch,
    /// True when the platform is a compatibility fallback (x86_64 on Apple
    /// Silicon, x64 on Windows on ARM). The UI labels this as Rosetta or
    /// emulation instead of hiding the architecture difference.
    #[serde(default)]
    pub fallback: bool,
    pub manifest: RuntimeArtifact,
}

impl JavaRuntimeCandidate {
    /// Stable runtime directory name, e.g.
    /// `mojang-java-runtime-delta-21.0.7-aarch64`.
    #[must_use]
    pub fn runtime_id(&self) -> String {
        format!(
            "{}-{}-{}-{}",
            self.vendor, self.component, self.version, self.arch
        )
    }

    /// Human label used by CLI and error details.
    #[must_use]
    pub fn label(&self) -> String {
        format!(
            "Java {} ({} {}, {})",
            self.major, self.component, self.version, self.arch
        )
    }
}

/// Platform order to try for a host.
///
/// macOS on Apple Silicon and Windows on ARM fall back to the x86_64 build
/// because Mojang does not publish old native runtimes; macOS runs it under
/// Rosetta, Windows under emulation. Linux arm64 has no official managed
/// runtime, so the list is empty and the UI asks for a system JDK.
#[must_use]
pub fn platforms_for(os: &str, arch: JavaArch) -> Vec<RuntimePlatform> {
    let mac_arm = RuntimePlatform {
        id: "mac-os-arm64",
        arch: JavaArch::Arm64,
    };
    let mac_x64 = RuntimePlatform {
        id: "mac-os",
        arch: JavaArch::X86_64,
    };
    let windows_arm = RuntimePlatform {
        id: "windows-arm64",
        arch: JavaArch::Arm64,
    };
    let windows_x64 = RuntimePlatform {
        id: "windows-x64",
        arch: JavaArch::X86_64,
    };
    match (os, arch) {
        ("macos", JavaArch::Arm64) => vec![mac_arm, mac_x64],
        ("macos", JavaArch::X86_64 | JavaArch::Unknown) => vec![mac_x64],
        ("windows", JavaArch::Arm64) => vec![windows_arm, windows_x64],
        ("windows", JavaArch::X86_64 | JavaArch::Unknown) => vec![windows_x64],
        ("windows", JavaArch::X86) => vec![RuntimePlatform {
            id: "windows-x86",
            arch: JavaArch::X86,
        }],
        ("linux", JavaArch::X86_64 | JavaArch::Unknown) => vec![RuntimePlatform {
            id: "linux",
            arch: JavaArch::X86_64,
        }],
        ("linux", JavaArch::X86) => vec![RuntimePlatform {
            id: "linux-i386",
            arch: JavaArch::X86,
        }],
        _ => Vec::new(),
    }
}

/// Platform order for the host running this process.
#[must_use]
pub fn host_platforms() -> Vec<RuntimePlatform> {
    platforms_for(std::env::consts::OS, JavaArch::host())
}

/// Parsed `all.json`.
#[derive(Debug, Clone)]
pub struct RuntimeIndex {
    platforms: BTreeMap<String, BTreeMap<String, Vec<IndexEntry>>>,
}

#[derive(Debug, Clone, Deserialize)]
struct IndexEntry {
    #[serde(default)]
    manifest: Option<RuntimeArtifact>,
    #[serde(default)]
    version: Option<IndexVersion>,
}

#[derive(Debug, Clone, Deserialize)]
struct IndexVersion {
    #[serde(default)]
    name: String,
}

impl RuntimeIndex {
    /// Parses the Mojang runtime index.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] when the payload is not the expected
    /// platform → component → entries object.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        let platforms: BTreeMap<String, BTreeMap<String, Vec<IndexEntry>>> =
            serde_json::from_slice(bytes).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Parse,
                    "error.java.runtime_index",
                    error.to_string(),
                )
            })?;
        if platforms.is_empty() {
            return Err(Error::new(
                ErrorKind::Parse,
                "error.java.runtime_index_empty",
            ));
        }
        Ok(Self { platforms })
    }

    /// Components published for one platform key.
    #[must_use]
    fn components(&self, platform: &str) -> Option<&BTreeMap<String, Vec<IndexEntry>>> {
        self.platforms.get(platform)
    }

    /// Chooses the newest usable runtime for `required` major.
    ///
    /// `component` is the version JSON's `javaVersion.component` when known; it
    /// wins as long as that component still ships a runtime the requested
    /// major can use. Otherwise the first component whose version parses to the
    /// exact major is selected.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Unsupported`] when none of `platforms` publishes a
    /// runtime for `required`.
    pub fn select(
        &self,
        platforms: &[RuntimePlatform],
        required: u32,
        component: Option<&str>,
    ) -> Result<JavaRuntimeCandidate> {
        for (index, platform) in platforms.iter().enumerate() {
            let Some(components) = self.components(platform.id) else {
                continue;
            };

            if let Some(component) = component
                && let Some(entries) = components.get(component)
                && let Some(candidate) = best_entry(entries, platform, None, component)
            {
                return Ok(candidate.with_fallback(index > 0));
            }

            let mut best: Option<(u8, VersionKey, JavaRuntimeCandidate)> = None;
            for (name, entries) in components {
                if name.ends_with("-snapshot") {
                    continue;
                }
                let Some(candidate) = best_entry(entries, platform, Some(required), name) else {
                    continue;
                };
                let version = version_key(&candidate.version);
                let rank = component_rank(name);
                let better = match &best {
                    None => true,
                    Some((best_rank, best_version, _)) => {
                        (rank, version) > (*best_rank, *best_version)
                    }
                };
                if better {
                    best = Some((rank, version, candidate));
                }
            }
            if let Some((_, _, candidate)) = best {
                return Ok(candidate.with_fallback(index > 0));
            }
        }

        Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.java.runtime_unsupported",
            format!(
                "no Mojang runtime for Java {required} on {} ({})",
                std::env::consts::OS,
                JavaArch::host()
            ),
        ))
    }

    /// One candidate per major, newest first-platform candidate.
    ///
    /// Duplicated majors (Mojang publishes both `beta` and `gamma` for 17) are
    /// collapsed so the management page offers one row per major.
    #[must_use]
    pub fn catalog(&self, platforms: &[RuntimePlatform]) -> Vec<JavaRuntimeCandidate> {
        let mut majors = Vec::new();
        for platform in platforms {
            let Some(components) = self.components(platform.id) else {
                continue;
            };
            for (name, entries) in components {
                if name.ends_with("-snapshot") {
                    continue;
                }
                for entry in entries {
                    let Some(major) = entry
                        .version
                        .as_ref()
                        .and_then(|version| runtime_version_major(&version.name))
                    else {
                        continue;
                    };
                    if !majors.contains(&major) {
                        majors.push(major);
                    }
                }
            }
        }
        majors.sort_unstable();
        majors
            .into_iter()
            .filter_map(|major| self.select(platforms, major, None).ok())
            .collect()
    }
}

impl JavaRuntimeCandidate {
    fn with_fallback(mut self, fallback: bool) -> Self {
        self.fallback = fallback;
        self
    }
}

fn best_entry(
    entries: &[IndexEntry],
    platform: &RuntimePlatform,
    required: Option<u32>,
    component: &str,
) -> Option<JavaRuntimeCandidate> {
    let mut best: Option<(VersionKey, JavaRuntimeCandidate)> = None;
    for entry in entries {
        let Some(manifest) = entry.manifest.clone() else {
            continue;
        };
        let Some(version) = entry.version.as_ref() else {
            continue;
        };
        let Some(major) = runtime_version_major(&version.name) else {
            continue;
        };
        if required.is_some_and(|required| major != required) {
            continue;
        }
        let parsed = version_key(&version.name);
        let candidate = JavaRuntimeCandidate {
            vendor: MOJANG_VENDOR.to_string(),
            platform: platform.id.to_string(),
            component: component.to_string(),
            version: version.name.clone(),
            major,
            arch: platform.arch,
            fallback: false,
            manifest,
        };
        match &best {
            Some((best_version, _)) if parsed <= *best_version => {}
            _ => best = Some((parsed, candidate)),
        }
    }
    best.map(|(_, candidate)| candidate)
}

fn component_rank(name: &str) -> u8 {
    match name {
        "jre-legacy" => 0,
        "java-runtime-alpha" => 1,
        "java-runtime-beta" => 2,
        "java-runtime-gamma" => 3,
        "java-runtime-delta" => 4,
        "java-runtime-epsilon" => 5,
        _ => 6,
    }
}

type VersionKey = (u32, u32, u32);

fn version_key(value: &str) -> VersionKey {
    let value = value.trim();
    if let Some((major, rest)) = value.split_once('u')
        && let Ok(major) = major.parse::<u32>()
    {
        let patch = rest
            .split(['.', '-', '_'])
            .next()
            .and_then(|value| value.parse::<u32>().ok())
            .unwrap_or(0);
        return (major, patch, 0);
    }
    let mut parts = value
        .split(['.', '-', '_', '+'])
        .filter_map(|part| part.parse::<u32>().ok())
        .take(3);
    (
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
    )
}

/// Parses `21.0.7`, `17.0.15`, `8u202`, `8u74-cacert462b08` and `16.0.1.9.1_3`.
#[must_use]
pub fn runtime_version_major(value: &str) -> Option<u32> {
    let value = value.trim();
    if let Some(rest) = value.strip_prefix("1.")
        && let Some((minor, _)) = rest.split_once(['.', '-', '_'])
        && let Ok(major) = minor.parse::<u32>()
    {
        return Some(major);
    }
    if let Some((major, _)) = value.split_once('u')
        && !major.is_empty()
        && major.chars().all(|character| character.is_ascii_digit())
    {
        return major.parse().ok();
    }
    JavaVersion::parse(value).map(|version| version.major)
}

#[cfg(test)]
mod tests {
    use super::*;

    const INDEX: &str = r#"{
      "mac-os-arm64": {
        "java-runtime-delta": [
          {"manifest": {"url": "https://example.invalid/delta-arm.json", "sha1": "aa", "size": 1},
           "version": {"name": "21.0.7", "released": "2026-01-01T00:00:00+00:00"}}
        ],
        "java-runtime-gamma": [
          {"manifest": {"url": "https://example.invalid/gamma-arm.json", "sha1": "bb", "size": 1},
           "version": {"name": "17.0.15"}}
        ],
        "java-runtime-gamma-snapshot": [
          {"manifest": {"url": "https://example.invalid/gamma-snap.json", "sha1": "cc", "size": 1},
           "version": {"name": "17.0.15"}}
        ]
      },
      "mac-os": {
        "jre-legacy": [
          {"manifest": {"url": "https://example.invalid/legacy-x64.json", "sha1": "dd", "size": 1},
           "version": {"name": "8u74-cacert462b08"}}
        ],
        "java-runtime-alpha": [
          {"manifest": {"url": "https://example.invalid/alpha-x64.json", "sha1": "ee", "size": 1},
           "version": {"name": "16.0.1.9.1_3"}}
        ]
      },
      "linux": {
        "java-runtime-delta": [
          {"manifest": {"url": "https://example.invalid/delta-linux.json", "sha1": "ff", "size": 1},
           "version": {"name": "21.0.7"}}
        ]
      }
    }"#;

    fn platforms() -> Vec<RuntimePlatform> {
        platforms_for("macos", JavaArch::Arm64)
    }

    #[test]
    fn selects_native_component_first() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        let candidate = index
            .select(&platforms(), 17, Some("java-runtime-gamma"))
            .expect("java 17");
        assert_eq!(candidate.platform, "mac-os-arm64");
        assert_eq!(candidate.arch, JavaArch::Arm64);
        assert!(!candidate.fallback);
        assert_eq!(
            candidate.runtime_id(),
            "mojang-java-runtime-gamma-17.0.15-arm64"
        );
    }

    #[test]
    fn old_versions_fall_back_to_x64_on_apple_silicon() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        let java8 = index.select(&platforms(), 8, None).expect("java 8");
        assert_eq!(java8.platform, "mac-os");
        assert_eq!(java8.arch, JavaArch::X86_64);
        assert!(java8.fallback);

        let java16 = index.select(&platforms(), 16, None).expect("java 16");
        assert_eq!(java16.platform, "mac-os");
        assert!(java16.fallback);
    }

    #[test]
    fn component_hint_beats_a_different_major() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        // The Oracle manifest says gamma=17 even though the base version asked
        // for 21; the caller uses the component when present.
        let candidate = index
            .select(&platforms(), 21, Some("java-runtime-gamma"))
            .expect("component hint");
        assert_eq!(candidate.component, "java-runtime-gamma");
        assert_eq!(candidate.major, 17);
    }

    #[test]
    fn catalog_skips_snapshot_components_and_dedupes_majors() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        let catalog = index.catalog(&platforms());
        let majors: Vec<u32> = catalog.iter().map(|candidate| candidate.major).collect();
        assert_eq!(majors, vec![8, 16, 17, 21]);
        assert!(
            catalog
                .iter()
                .all(|candidate| !candidate.component.ends_with("-snapshot"))
        );
        let java17 = catalog
            .iter()
            .find(|candidate| candidate.major == 17)
            .expect("17");
        assert_eq!(java17.component, "java-runtime-gamma");
    }

    #[test]
    fn architecture_specific_catalog_only_offers_that_arch() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        let catalog = index.catalog(&platforms_for("macos", JavaArch::X86_64));
        let majors: Vec<u32> = catalog.iter().map(|candidate| candidate.major).collect();
        assert_eq!(majors, vec![8, 16]);
        assert!(
            catalog
                .iter()
                .all(|candidate| candidate.arch == JavaArch::X86_64 && !candidate.fallback)
        );
    }

    #[test]
    fn unsupported_platforms_report_a_key() {
        let index = RuntimeIndex::parse(INDEX.as_bytes()).expect("index");
        let error = index
            .select(&platforms_for("linux", JavaArch::Arm64), 21, None)
            .expect_err("no linux arm64 runtime");
        assert_eq!(error.message_key, "error.java.runtime_unsupported");
        assert!(platforms_for("linux", JavaArch::Arm64).is_empty());
        assert!(platforms_for("android", JavaArch::Arm64).is_empty());
    }

    #[test]
    fn parses_vendor_version_spellings() {
        assert_eq!(runtime_version_major("8u202"), Some(8));
        assert_eq!(runtime_version_major("8u74-cacert462b08"), Some(8));
        assert_eq!(runtime_version_major("16.0.1.9.1_3"), Some(16));
        assert_eq!(runtime_version_major("21.0.7"), Some(21));
        assert_eq!(runtime_version_major("1.8.0_402"), Some(8));
        assert_eq!(runtime_version_major("not a version"), None);
    }

    #[test]
    fn windows_arm_uses_arm64_then_x64() {
        let ids: Vec<&str> = platforms_for("windows", JavaArch::Arm64)
            .iter()
            .map(|platform| platform.id)
            .collect();
        assert_eq!(ids, vec!["windows-arm64", "windows-x64"]);
        let linux: Vec<&str> = platforms_for("linux", JavaArch::X86_64)
            .iter()
            .map(|platform| platform.id)
            .collect();
        assert_eq!(linux, vec!["linux"]);
    }
}
