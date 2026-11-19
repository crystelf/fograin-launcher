//! Diagnostic rules: built-in TOML plus user overrides.
//!
//! Rules are plain regexes over log lines. A rule carries its display copy so
//! the analysis result can be rendered without a second lookup table; the M10
//! i18n pass will move `title` / `body` to `composeResources` keyed by `id`.

use std::{
    fs,
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};
use regex::{Regex, RegexBuilder};
use serde::{Deserialize, Serialize};

/// Built-in rules shipped with the launcher.
const BUILTIN_RULES: &str = include_str!("../rules/default.toml");

/// How serious a finding is. Declaration order doubles as the sort order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Info,
    Warning,
    Error,
}

impl Severity {
    /// Lowercase wire value.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Info => "info",
            Self::Warning => "warning",
            Self::Error => "error",
        }
    }
}

/// Which collected source a rule is allowed to match.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RuleSource {
    Log,
    Crash,
    #[serde(rename = "hsErr", alias = "hs_err")]
    HsErr,
    Any,
}

/// Suggested follow-up shown as a button on the finding card.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum FixKind {
    #[default]
    #[serde(alias = "none")]
    None,
    #[serde(alias = "install_java")]
    InstallJava,
    #[serde(alias = "open_java_settings")]
    OpenJavaSettings,
    #[serde(alias = "open_content")]
    OpenContent,
    #[serde(alias = "disable_mod")]
    DisableMod,
    #[serde(alias = "open_graphics")]
    OpenGraphics,
    #[serde(alias = "open_logs")]
    OpenLogs,
}

impl FixKind {
    /// True when the UI should render a repair button.
    #[must_use]
    pub const fn is_actionable(self) -> bool {
        !matches!(self, Self::None)
    }
}

/// One compiled analysis rule.
#[derive(Debug, Clone)]
pub struct Rule {
    pub id: String,
    pub severity: Severity,
    pub source: RuleSource,
    pub pattern: String,
    pub title: String,
    pub body: String,
    pub fix: FixKind,
    regex: Regex,
}

impl Rule {
    /// Compiled matcher, case-insensitive so mod ids and exception names match.
    #[must_use]
    pub fn regex(&self) -> &Regex {
        &self.regex
    }
}

/// Raw TOML shape (the regex is compiled after parsing).
#[derive(Debug, Deserialize)]
struct RuleFile {
    #[serde(default, rename = "rule")]
    rules: Vec<RuleDef>,
}

#[derive(Debug, Deserialize)]
struct RuleDef {
    id: String,
    #[serde(default = "default_severity")]
    severity: Severity,
    #[serde(default = "default_source")]
    source: RuleSource,
    pattern: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    fix: FixKind,
    /// Lets a user turn a built-in rule off from their override directory.
    #[serde(default)]
    disabled: bool,
}

fn default_severity() -> Severity {
    Severity::Warning
}

fn default_source() -> RuleSource {
    RuleSource::Any
}

fn compile(definition: &RuleDef) -> Result<Rule> {
    if definition.id.trim().is_empty() {
        return Err(Error::new(ErrorKind::InvalidInput, "error.diag.rule_id"));
    }
    if definition.pattern.trim().is_empty() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.diag.rule_pattern",
            definition.id.clone(),
        ));
    }
    let regex = RegexBuilder::new(&definition.pattern)
        .case_insensitive(true)
        .build()
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::InvalidInput,
                "error.diag.rule_pattern",
                format!("{}: {error}", definition.id),
            )
        })?;
    Ok(Rule {
        id: definition.id.trim().to_string(),
        severity: definition.severity,
        source: definition.source,
        pattern: definition.pattern.clone(),
        title: definition.title.trim().to_string(),
        body: definition.body.trim().to_string(),
        fix: definition.fix,
        regex,
    })
}

/// The active rule set after merging built-ins and user overrides.
#[derive(Debug, Clone, Default)]
pub struct RuleSet {
    rules: Vec<Rule>,
}

impl RuleSet {
    /// Parses the embedded rule table.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] when a built-in rule is malformed; that is
    /// a build problem, so it must fail loudly instead of hiding the rule.
    pub fn builtin() -> Result<Self> {
        let file: RuleFile = toml::from_str(BUILTIN_RULES).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.diag.rules_parse",
                format!("builtin rules: {error}"),
            )
        })?;
        let mut set = Self::default();
        for definition in &file.rules {
            set.rules.push(compile(definition)?);
        }
        Ok(set)
    }

    /// Loads the built-ins and then every `*.toml` in `directory`.
    ///
    /// A rule with an existing id replaces the earlier one; `disabled = true`
    /// removes it. Invalid files are logged and skipped: a broken user rule must
    /// never make the diagnostics page unusable.
    ///
    /// # Errors
    ///
    /// Only fails when the built-in table itself is invalid.
    pub fn load(directory: Option<&Path>) -> Result<Self> {
        let mut set = Self::builtin()?;
        let Some(directory) = directory else {
            return Ok(set);
        };
        if !directory.is_dir() {
            return Ok(set);
        }
        let mut paths: Vec<PathBuf> = fs::read_dir(directory)
            .map(|entries| {
                entries
                    .flatten()
                    .map(|entry| entry.path())
                    .filter(|path| {
                        path.extension()
                            .is_some_and(|extension| extension.eq_ignore_ascii_case("toml"))
                    })
                    .collect()
            })
            .unwrap_or_default();
        paths.sort();
        for path in paths {
            match fs::read_to_string(&path) {
                Ok(text) => match toml::from_str::<RuleFile>(&text) {
                    Ok(file) => {
                        for definition in &file.rules {
                            match compile(definition) {
                                Ok(rule) => set.upsert(rule, definition.disabled),
                                Err(error) => {
                                    tracing::warn!(path = %path.display(), %error, "skipping diagnostic rule");
                                }
                            }
                        }
                    }
                    Err(error) => {
                        tracing::warn!(path = %path.display(), %error, "skipping diagnostic rule file");
                    }
                },
                Err(error) => {
                    tracing::warn!(path = %path.display(), %error, "cannot read diagnostic rule file");
                }
            }
        }
        Ok(set)
    }

    fn upsert(&mut self, rule: Rule, disabled: bool) {
        let existing = self.rules.iter().position(|item| item.id == rule.id);
        if disabled {
            if let Some(index) = existing {
                self.rules.remove(index);
            }
            return;
        }
        match existing {
            Some(index) => self.rules[index] = rule,
            None => self.rules.push(rule),
        }
    }

    /// Active rules in load order.
    #[must_use]
    pub fn rules(&self) -> &[Rule] {
        &self.rules
    }

    /// Number of active rules; the diagnostics page shows it as context.
    #[must_use]
    pub fn len(&self) -> usize {
        self.rules.len()
    }

    /// True when every rule was removed by user overrides.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.rules.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builtins_compile_and_cover_the_first_batch() {
        let set = RuleSet::builtin().expect("builtin rules");
        assert!(set.len() >= 10, "expected the M9 rule batch");
        for id in [
            "java.version",
            "java.memory",
            "graphics.driver",
            "mod.dependency",
            "mod.duplicate",
            "mixin.failure",
            "optifine.conflict",
            "loader.duplicate",
            "tick.crash",
        ] {
            assert!(
                set.rules().iter().any(|rule| rule.id == id),
                "missing rule {id}"
            );
        }
    }

    #[test]
    fn user_rules_replace_and_disable_builtins() {
        let temp = tempfile::tempdir().expect("tempdir");
        fs::write(
            temp.path().join("custom.toml"),
            r#"
            [[rule]]
            id = "java.version"
            pattern = "CustomJavaMarker"
            title = "自定义"
            body = "覆盖后的规则"
            severity = "error"

            [[rule]]
            id = "loader.duplicate"
            pattern = "x"
            disabled = true

            [[rule]]
            id = "custom.new"
            pattern = "CustomNewMarker"
            title = "新规则"
            fix = "open_logs"
            "#,
        )
        .expect("write rules");

        let set = RuleSet::load(Some(temp.path())).expect("load");
        let java = set
            .rules()
            .iter()
            .find(|rule| rule.id == "java.version")
            .expect("replaced rule");
        assert_eq!(java.title, "自定义");
        assert!(java.regex().is_match("customjavamarker"));
        assert!(!set.rules().iter().any(|rule| rule.id == "loader.duplicate"));
        let added = set
            .rules()
            .iter()
            .find(|rule| rule.id == "custom.new")
            .expect("added rule");
        assert_eq!(added.fix, FixKind::OpenLogs);
    }

    #[test]
    fn broken_user_file_is_skipped() {
        let temp = tempfile::tempdir().expect("tempdir");
        fs::write(temp.path().join("bad.toml"), "not = [valid").expect("write");
        let set = RuleSet::load(Some(temp.path())).expect("load");
        assert_eq!(set.len(), RuleSet::builtin().expect("builtin").len());
    }
}
