//! Data-driven mirror rules (§6.1).
//!
//! One official URL expands into an ordered candidate list: BMCLAPI (or another
//! mirror), an optional secondary mirror, and the official URL itself. The order
//! depends on [`DownloadSource`] and the detected [`Region`].

use std::{path::Path, sync::OnceLock};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use serde::Deserialize;

use crate::region::Region;

/// Default BMCLAPI root. Override with `FOGRAIN_BMCLAPI` or a cached table.
pub const DEFAULT_BMCLAPI: &str = "https://bmclapi2.bangbang93.com";

/// Placeholder replaced with the resolved BMCLAPI root while parsing.
const BMCLAPI_PLACEHOLDER: &str = "{bmcl}";

/// Table layout version understood by this build.
const SUPPORTED_VERSION: u32 = 1;

const EMBEDDED: &str = include_str!("mirrors.toml");

/// A single prefix rule.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MirrorRule {
    /// Official URL prefix this rule matches.
    pub source: String,
    /// Primary mirror prefix, `{bmcl}` already expanded.
    pub mirror: Option<String>,
    /// Secondary mirror tried after [`MirrorRule::mirror`].
    pub fallback: Option<String>,
    /// Whether the official URL may be used at all.
    pub official: bool,
}

impl MirrorRule {
    /// Returns the mirror URLs for `url`, in table order.
    fn mirror_urls(&self, url: &str) -> Vec<String> {
        let suffix = &url[self.source.len()..];
        let mut urls = Vec::with_capacity(2);
        if let Some(mirror) = &self.mirror {
            urls.push(format!("{mirror}{suffix}"));
        }
        if let Some(fallback) = &self.fallback {
            urls.push(format!("{fallback}{suffix}"));
        }
        urls
    }
}

#[derive(Debug, Deserialize)]
struct RawTable {
    #[serde(default = "default_version")]
    version: u32,
    #[serde(default)]
    bmclapi: Option<String>,
    #[serde(default, rename = "rule")]
    rules: Vec<RawRule>,
}

#[derive(Debug, Deserialize)]
struct RawRule {
    source: String,
    #[serde(default)]
    mirror: Option<String>,
    #[serde(default)]
    fallback: Option<String>,
    #[serde(default = "default_true")]
    official: bool,
}

fn default_version() -> u32 {
    SUPPORTED_VERSION
}

const fn default_true() -> bool {
    true
}

/// Parsed mirror rules with the BMCLAPI root resolved.
#[derive(Debug, Clone)]
pub struct MirrorTable {
    version: u32,
    bmclapi: String,
    rules: Vec<MirrorRule>,
}

impl MirrorTable {
    /// The table compiled into the binary, with environment overrides applied.
    ///
    /// # Panics
    ///
    /// Panics only when the embedded `mirrors.toml` is malformed, which the
    /// unit tests prevent from reaching a release build.
    pub fn embedded() -> &'static Self {
        static TABLE: OnceLock<MirrorTable> = OnceLock::new();
        TABLE.get_or_init(|| {
            Self::parse(EMBEDDED, bmclapi_override().as_deref())
                .expect("embedded mirrors.toml must be valid")
        })
    }

    /// Parses a table exactly as written, without environment overrides.
    pub fn from_toml(text: &str) -> Result<Self> {
        Self::parse(text, None)
    }

    /// Loads a hot-updated table from `<root>/cache/mirrors.toml`.
    ///
    /// A missing or invalid cached table falls back to [`MirrorTable::embedded`]
    /// so a bad download can never break mirroring.
    #[must_use]
    pub fn load(root: &Path) -> Self {
        let path = root.join("cache").join("mirrors.toml");
        let Ok(text) = std::fs::read_to_string(&path) else {
            return Self::embedded().clone();
        };

        match Self::parse(&text, bmclapi_override().as_deref()) {
            Ok(table) => {
                tracing::info!(
                    rules = table.rules.len(),
                    path = %path.display(),
                    "loaded cached mirror table"
                );
                table
            }
            Err(error) => {
                tracing::warn!(
                    path = %path.display(),
                    detail = %error,
                    "ignoring invalid cached mirror table"
                );
                Self::embedded().clone()
            }
        }
    }

    fn parse(text: &str, bmcl_override: Option<&str>) -> Result<Self> {
        let raw: RawTable = toml::from_str(text).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.net.mirror_table",
                error.to_string(),
            )
        })?;

        if raw.version != SUPPORTED_VERSION {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.net.mirror_table",
                format!(
                    "unsupported mirror table version {} (expected {SUPPORTED_VERSION})",
                    raw.version
                ),
            ));
        }

        let bmclapi = bmcl_override
            .filter(|value| !value.is_empty())
            .or(raw.bmclapi.as_deref())
            .unwrap_or(DEFAULT_BMCLAPI)
            .trim_end_matches('/')
            .to_string();

        let rules = raw
            .rules
            .into_iter()
            .map(|rule| {
                let source = rule.source;
                if !source.starts_with("http://") && !source.starts_with("https://") {
                    return Err(Error::with_detail(
                        ErrorKind::Parse,
                        "error.net.mirror_table",
                        format!("rule source must be an http(s) URL: {source}"),
                    ));
                }

                let mirror = rule.mirror.map(|value| expand(&value, &bmclapi));
                let fallback = rule.fallback.map(|value| expand(&value, &bmclapi));

                if !rule.official && mirror.is_none() && fallback.is_none() {
                    return Err(Error::with_detail(
                        ErrorKind::Parse,
                        "error.net.mirror_table",
                        format!("rule {source} disables official access without a mirror"),
                    ));
                }

                Ok(MirrorRule {
                    source,
                    mirror,
                    fallback,
                    official: rule.official,
                })
            })
            .collect::<Result<Vec<_>>>()?;

        if rules.is_empty() {
            return Err(Error::new(ErrorKind::Parse, "error.net.mirror_table_empty"));
        }

        Ok(Self {
            version: raw.version,
            bmclapi,
            rules,
        })
    }

    /// Resolved BMCLAPI root.
    #[must_use]
    pub fn bmclapi_root(&self) -> &str {
        &self.bmclapi
    }

    /// Table layout version.
    #[must_use]
    pub const fn version(&self) -> u32 {
        self.version
    }

    /// All rules in match order.
    #[must_use]
    pub fn rules(&self) -> &[MirrorRule] {
        &self.rules
    }

    /// First rule whose `source` prefixes `url`.
    #[must_use]
    pub fn rule_for(&self, url: &str) -> Option<&MirrorRule> {
        self.rules.iter().find(|rule| url.starts_with(&rule.source))
    }

    /// Expands one URL into ordered candidates.
    ///
    /// Unknown prefixes yield the official URL unchanged: there is nothing to
    /// mirror, and inventing a mapping would corrupt the request path.
    #[must_use]
    pub fn candidates(&self, url: &str, source: DownloadSource, region: Region) -> Vec<String> {
        let Some(rule) = self.rule_for(url) else {
            return vec![url.to_string()];
        };

        let mirror_urls = rule.mirror_urls(url);
        let official = rule.official.then(|| url.to_string());

        let mirror_first = match source {
            DownloadSource::Mirror => true,
            DownloadSource::Official => false,
            DownloadSource::Auto => region.is_china(),
        };

        let mut candidates = Vec::with_capacity(mirror_urls.len() + 1);
        if mirror_first {
            for candidate in mirror_urls {
                push_unique(&mut candidates, candidate);
            }
            if let Some(official) = official {
                push_unique(&mut candidates, official);
            }
        } else {
            if let Some(official) = official {
                push_unique(&mut candidates, official);
            }
            for candidate in mirror_urls {
                push_unique(&mut candidates, candidate);
            }
        }

        debug_assert!(!candidates.is_empty(), "a rule must yield a candidate");
        candidates
    }
}

fn expand(value: &str, bmclapi: &str) -> String {
    value.replace(BMCLAPI_PLACEHOLDER, bmclapi)
}

fn push_unique(target: &mut Vec<String>, url: String) {
    if !target.contains(&url) {
        target.push(url);
    }
}

fn bmclapi_override() -> Option<String> {
    std::env::var("FOGRAIN_BMCLAPI")
        .ok()
        .filter(|value| !value.trim().is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_url(rule: &MirrorRule) -> String {
        format!("{}path/to/file.bin", rule.source)
    }

    #[test]
    fn embedded_table_parses_and_has_unique_sources() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("embedded table parses");
        assert_eq!(table.version(), SUPPORTED_VERSION);
        assert!(
            table.rules().len() >= 18,
            "table should cover the §6.1 rows"
        );

        let mut seen = std::collections::HashSet::new();
        for rule in table.rules() {
            assert!(
                seen.insert(rule.source.clone()),
                "duplicate rule for {}",
                rule.source
            );
            assert!(rule.source.starts_with("http"));
            assert!(rule.mirror.is_some() || rule.fallback.is_some());
        }
    }

    /// §6.1 requires every prefix rule to be covered by a test.
    #[test]
    fn every_rule_maps_to_a_mirror_that_keeps_the_suffix() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");

        for rule in table.rules() {
            let url = sample_url(rule);
            let candidates = table.candidates(&url, DownloadSource::Auto, Region::China);

            let expected_suffix = "path/to/file.bin";
            let mirror = rule.mirror.as_deref().expect("rule has a mirror");
            assert!(
                candidates.contains(&format!("{mirror}{expected_suffix}")),
                "rule {} did not yield its mirror URL: {candidates:?}",
                rule.source
            );

            if let Some(fallback) = rule.fallback.as_deref() {
                assert!(
                    candidates.contains(&format!("{fallback}{expected_suffix}")),
                    "rule {} did not yield its fallback URL: {candidates:?}",
                    rule.source
                );
            }

            if rule.official {
                assert!(
                    candidates.iter().any(|candidate| candidate == &url),
                    "rule {} dropped the official URL: {candidates:?}",
                    rule.source
                );
            } else {
                assert!(
                    candidates.iter().all(|candidate| candidate != &url),
                    "rule {} must not use the official URL: {candidates:?}",
                    rule.source
                );
            }
        }
    }

    #[test]
    fn mirror_first_in_china_and_official_first_elsewhere() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");
        let url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

        let cn = table.candidates(url, DownloadSource::Auto, Region::China);
        assert_eq!(
            cn[0],
            format!("{DEFAULT_BMCLAPI}/mc/game/version_manifest_v2.json")
        );
        assert_eq!(cn.last().map(String::as_str), Some(url));

        let world = table.candidates(url, DownloadSource::Auto, Region::RestOfWorld);
        let mirrored = format!("{DEFAULT_BMCLAPI}/mc/game/version_manifest_v2.json");
        assert_eq!(world[0], url);
        assert_eq!(world.last().map(String::as_str), Some(mirrored.as_str()));
    }

    #[test]
    fn explicit_source_preferences_override_the_region() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");
        let url = "https://libraries.minecraft.net/org/example/lib/1.0/lib-1.0.jar";

        let mirror = table.candidates(url, DownloadSource::Mirror, Region::RestOfWorld);
        assert!(mirror[0].contains("bmclapi2.bangbang93.com/libraries/"));

        let official = table.candidates(url, DownloadSource::Official, Region::China);
        assert_eq!(official[0], url);
        assert!(official[1].contains("/libraries/"));
    }

    #[test]
    fn curseforge_rules_never_offer_the_official_api() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");

        for url in [
            "https://api.curseforge.com/v1/mods/1",
            "https://edge.forgecdn.net/files/1/2/mod.jar",
        ] {
            for source in [
                DownloadSource::Auto,
                DownloadSource::Official,
                DownloadSource::Mirror,
            ] {
                for region in [Region::China, Region::RestOfWorld] {
                    let candidates = table.candidates(url, source, region);
                    assert!(
                        candidates.iter().all(|candidate| candidate != url),
                        "{url} must only use MCIM, got {candidates:?}"
                    );
                    assert!(
                        candidates
                            .iter()
                            .all(|candidate| candidate.contains("mcimirror"))
                    );
                }
            }
        }
    }

    #[test]
    fn neoforge_keeps_the_forge_cdn_after_the_mirror() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");
        let url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.80/neoforge-21.1.80.jar";

        let candidates = table.candidates(url, DownloadSource::Mirror, Region::RestOfWorld);
        assert_eq!(
            candidates[0],
            format!("{DEFAULT_BMCLAPI}/maven/net/neoforged/neoforge/21.1.80/neoforge-21.1.80.jar")
        );
        assert_eq!(
            candidates[1],
            "https://neoforged.forgecdn.net/net/neoforged/neoforge/21.1.80/neoforge-21.1.80.jar"
        );
        assert_eq!(candidates[2], url);
    }

    #[test]
    fn unknown_prefixes_are_returned_unchanged() {
        let table = MirrorTable::from_toml(EMBEDDED).expect("table parses");
        let url = "https://example.com/some/file.jar";
        assert_eq!(
            table.candidates(url, DownloadSource::Mirror, Region::China),
            vec![url.to_string()]
        );
    }

    #[test]
    fn duplicated_mirror_and_fallback_are_collapsed() {
        let table = MirrorTable::from_toml(
            r#"
            [[rule]]
            source = "https://example.org"
            mirror = "{bmcl}"
            fallback = "{bmcl}"
            "#,
        )
        .expect("table parses");

        let candidates =
            table.candidates("https://example.org/x", DownloadSource::Auto, Region::China);
        assert_eq!(
            candidates,
            vec![
                format!("{DEFAULT_BMCLAPI}/x"),
                "https://example.org/x".to_string()
            ]
        );
    }

    #[test]
    fn bmclapi_root_can_be_overridden_in_the_table() {
        let table = MirrorTable::from_toml(
            r#"
            bmclapi = "https://mirror.example.net/"

            [[rule]]
            source = "https://libraries.minecraft.net"
            mirror = "{bmcl}/libraries"
            "#,
        )
        .expect("table parses");

        assert_eq!(table.bmclapi_root(), "https://mirror.example.net");
        assert_eq!(
            table.candidates(
                "https://libraries.minecraft.net/a/b.jar",
                DownloadSource::Mirror,
                Region::RestOfWorld
            )[0],
            "https://mirror.example.net/libraries/a/b.jar"
        );
    }

    #[test]
    fn invalid_tables_are_rejected() {
        let missing_mirror = MirrorTable::from_toml(
            r#"
            [[rule]]
            source = "https://example.org"
            official = false
            "#,
        );
        assert!(missing_mirror.is_err());

        let bad_source = MirrorTable::from_toml(
            r#"
            [[rule]]
            source = "example.org"
            mirror = "{bmcl}"
            "#,
        );
        assert!(bad_source.is_err());

        let future = MirrorTable::from_toml(
            "version = 99\n[[rule]]\nsource = \"https://a\"\nmirror = \"https://b\"\n",
        );
        assert_eq!(
            future.expect_err("version rejected").kind,
            ErrorKind::Unsupported
        );
    }
}
