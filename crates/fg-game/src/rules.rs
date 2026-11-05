//! `rules` evaluation for libraries and launch arguments (§7.1).
//!
//! The rules format is small but subtle: a rule list where *no* rule matches
//! means "disallowed", and the last matching rule wins. Rules with both `os`
//! and `features` require every condition to match.

use std::{
    collections::{BTreeMap, HashMap},
    sync::{Mutex, OnceLock},
};

use regex::Regex;
use serde::{Deserialize, Serialize};

use fg_java::JavaArch;

/// `action` of a rule.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleAction {
    Allow,
    Disallow,
}

/// One entry of `libraries[].rules` / `arguments.*[].rules`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Rule {
    pub action: RuleAction,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub os: Option<OsRule>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub features: Option<BTreeMap<String, bool>>,
}

/// `os` condition of a rule.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OsRule {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub arch: Option<String>,
    /// Legacy regex form, e.g. `^10\\.5\\.\\d$`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// Modern numeric range form, e.g. `{"min":"10.0.17134"}`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version_range: Option<VersionRange>,
}

/// `os.versionRange` from newer snapshots.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct VersionRange {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub min: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max: Option<String>,
}

/// Host OS identity used by rule evaluation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OsInfo {
    /// Canonical Mojang name: `windows`, `osx` or `linux`.
    pub name: String,
    pub arch: JavaArch,
    pub version: Option<String>,
}

impl OsInfo {
    /// Detects the host. The OS version is best-effort because the rules only
    /// use it for a handful of graphics/heap flags.
    #[must_use]
    pub fn current() -> Self {
        Self {
            name: current_os_name().to_string(),
            arch: JavaArch::host(),
            version: current_os_version(),
        }
    }

    fn matches_name(&self, name: &str) -> bool {
        let name = name.to_ascii_lowercase();
        let host = self.name.to_ascii_lowercase();
        match name.as_str() {
            "osx" | "macos" => host == "osx" || host == "macos",
            other => other == host,
        }
    }

    fn matches_arch(&self, arch: &str) -> bool {
        let expected = JavaArch::parse(arch);
        if expected == JavaArch::Unknown {
            return false;
        }
        // `x86` must not match `x86_64`: 32-bit natives are a real, distinct
        // build, and the launcher must not mix them.
        expected == self.arch
    }
}

/// Feature flags from the launch dialog (§9 item 5).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Features(pub(crate) BTreeMap<String, bool>);

impl Features {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    #[must_use]
    pub fn with(mut self, feature: impl Into<String>, enabled: bool) -> Self {
        self.0.insert(feature.into(), enabled);
        self
    }

    #[must_use]
    pub fn is_enabled(&self, feature: &str) -> bool {
        self.0.get(feature).copied().unwrap_or(false)
    }
}

/// Everything a rule can depend on.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuleContext {
    pub os: OsInfo,
    pub features: Features,
}

impl RuleContext {
    /// Context for the current host with all features disabled.
    #[must_use]
    pub fn current() -> Self {
        Self {
            os: OsInfo::current(),
            features: Features::new(),
        }
    }

    /// Context for an explicitly described host (tests and FFI previews).
    #[must_use]
    pub fn new(os: OsInfo, features: Features) -> Self {
        Self { os, features }
    }

    #[must_use]
    pub fn with_features(mut self, features: Features) -> Self {
        self.features = features;
        self
    }
}

/// Evaluates a rule list.
///
/// An empty list allows the element. Otherwise the element starts disallowed
/// and every matching rule overwrites the decision, so the last match wins.
#[must_use]
pub fn evaluate(rules: &[Rule], context: &RuleContext) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if rule_matches(rule, context) {
            allowed = rule.action == RuleAction::Allow;
        }
    }
    allowed
}

/// Whether one rule's conditions all match.
#[must_use]
pub fn rule_matches(rule: &Rule, context: &RuleContext) -> bool {
    if let Some(os) = &rule.os
        && !os_matches(os, context)
    {
        return false;
    }
    if let Some(features) = &rule.features {
        for (feature, expected) in features {
            if context.features.is_enabled(feature) != *expected {
                return false;
            }
        }
    }
    true
}

fn os_matches(os: &OsRule, context: &RuleContext) -> bool {
    if let Some(name) = &os.name
        && !context.os.matches_name(name)
    {
        return false;
    }
    if let Some(arch) = &os.arch
        && !context.os.matches_arch(arch)
    {
        return false;
    }
    if let Some(pattern) = &os.version {
        let Some(host) = context.os.version.as_deref() else {
            return false;
        };
        if !regex_matches(pattern, host) {
            return false;
        }
    }
    if let Some(range) = &os.version_range {
        let Some(host) = context.os.version.as_deref() else {
            return false;
        };
        if !range.contains(host) {
            return false;
        }
    }
    true
}

impl VersionRange {
    /// Numeric comparison: `min` inclusive, `max` exclusive.
    #[must_use]
    pub fn contains(&self, version: &str) -> bool {
        let Some(value) = VersionParts::parse(version) else {
            return false;
        };
        if let Some(min) = self.min.as_deref().and_then(VersionParts::parse)
            && value < min
        {
            return false;
        }
        if let Some(max) = self.max.as_deref().and_then(VersionParts::parse)
            && value >= max
        {
            return false;
        }
        true
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct VersionParts(Vec<u64>);

impl VersionParts {
    fn parse(value: &str) -> Option<Self> {
        let parts: Vec<u64> = value
            .split(|character: char| !character.is_ascii_digit())
            .filter(|part| !part.is_empty())
            .map(str::parse)
            .collect::<Result<_, _>>()
            .ok()?;
        (!parts.is_empty()).then_some(Self(parts))
    }
}

fn regex_matches(pattern: &str, value: &str) -> bool {
    static CACHE: OnceLock<Mutex<HashMap<String, Option<Regex>>>> = OnceLock::new();
    let cache = CACHE.get_or_init(|| Mutex::new(HashMap::new()));
    if let Ok(mut cache) = cache.lock() {
        let regex = cache
            .entry(pattern.to_string())
            .or_insert_with(|| Regex::new(pattern).ok());
        return regex.as_ref().is_some_and(|regex| regex.is_match(value));
    }
    // A poisoned cache must not break launching: fall back to a direct compile.
    Regex::new(pattern).is_ok_and(|regex| regex.is_match(value))
}

fn current_os_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    }
}

fn current_os_version() -> Option<String> {
    #[cfg(target_os = "macos")]
    {
        macos_version()
    }
    #[cfg(target_os = "windows")]
    {
        windows_version()
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        None
    }
}

#[cfg(target_os = "macos")]
fn macos_version() -> Option<String> {
    let output = std::process::Command::new("sw_vers")
        .arg("-productVersion")
        .output()
        .ok()?;
    let version = String::from_utf8_lossy(&output.stdout).trim().to_string();
    (!version.is_empty()).then_some(version)
}

#[cfg(target_os = "windows")]
fn windows_version() -> Option<String> {
    // `ver` prints "[Version 10.0.22631.3737]".
    let output = std::process::Command::new("cmd")
        .args(["/C", "ver"])
        .output()
        .ok()?;
    let text = String::from_utf8_lossy(&output.stdout);
    let start = text.find("Version ")? + "Version ".len();
    let version: String = text[start..]
        .chars()
        .take_while(|character| character.is_ascii_digit() || *character == '.')
        .collect();
    (!version.is_empty()).then_some(version)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn context(name: &str, arch: JavaArch, version: &str) -> RuleContext {
        RuleContext::new(
            OsInfo {
                name: name.to_string(),
                arch,
                version: Some(version.to_string()),
            },
            Features::new(),
        )
    }

    fn rule(action: RuleAction) -> Rule {
        Rule {
            action,
            os: None,
            features: None,
        }
    }

    #[test]
    fn empty_rules_allow() {
        assert!(evaluate(&[], &context("osx", JavaArch::Arm64, "15.1")));
    }

    #[test]
    fn no_matching_rule_disallows() {
        let rules = vec![Rule {
            action: RuleAction::Allow,
            os: Some(OsRule {
                name: Some("linux".into()),
                ..OsRule::default()
            }),
            features: None,
        }];
        assert!(!evaluate(&rules, &context("osx", JavaArch::Arm64, "15.1")));
    }

    #[test]
    fn last_matching_rule_wins() {
        let rules = vec![
            rule(RuleAction::Allow),
            Rule {
                action: RuleAction::Disallow,
                os: Some(OsRule {
                    name: Some("osx".into()),
                    version: Some(r"^10\.5\.\d$".into()),
                    ..OsRule::default()
                }),
                features: None,
            },
        ];
        assert!(evaluate(&rules, &context("osx", JavaArch::Arm64, "15.1")));
        assert!(!evaluate(
            &rules,
            &context("osx", JavaArch::X86_64, "10.5.8")
        ));
    }

    #[test]
    fn features_must_match_exactly() {
        let rules = vec![Rule {
            action: RuleAction::Allow,
            os: None,
            features: Some(BTreeMap::from([("has_custom_resolution".into(), true)])),
        }];
        let mut context = context("osx", JavaArch::Arm64, "15.1");
        assert!(!evaluate(&rules, &context));
        context.features = Features::new().with("has_custom_resolution", true);
        assert!(evaluate(&rules, &context));
    }

    #[test]
    fn arm64_and_x86_do_not_match_each_other() {
        let rule = Rule {
            action: RuleAction::Allow,
            os: Some(OsRule {
                arch: Some("x86".into()),
                ..OsRule::default()
            }),
            features: None,
        };
        assert!(!evaluate(
            std::slice::from_ref(&rule),
            &context("windows", JavaArch::X86_64, "10.0.22631")
        ));
        assert!(evaluate(
            std::slice::from_ref(&rule),
            &context("windows", JavaArch::X86, "10.0.22631")
        ));
    }

    #[test]
    fn version_range_is_min_inclusive_max_exclusive() {
        let range = VersionRange {
            min: Some("10.0.17134".into()),
            max: Some("10.0.22000".into()),
        };
        assert!(range.contains("10.0.17134"));
        assert!(range.contains("10.0.19045.1"));
        assert!(!range.contains("10.0.22000"));
        assert!(!range.contains("10.0.10000"));
        assert!(!range.contains("not-a-version"));
    }

    #[test]
    fn parses_real_snapshot_version_range_rule() {
        let json = r#"{
            "action": "allow",
            "os": {"name": "windows", "versionRange": {"min": "10.0.17134"}}
        }"#;
        let rule: Rule = serde_json::from_str(json).expect("rule");
        assert_eq!(
            rule.os.expect("os").version_range,
            Some(VersionRange {
                min: Some("10.0.17134".into()),
                max: None
            })
        );
    }
}
