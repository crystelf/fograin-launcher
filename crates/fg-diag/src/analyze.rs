//! Rule matching and ranking.

use fg_core::now_ms_i64;
use serde::Serialize;

use crate::{
    log::{CrashSummary, LogBundle, LogInput, LogSourceKind, SourceInfo},
    rule::{FixKind, Rule, RuleSet, RuleSource, Severity},
};

/// Hard cap on findings so a noisy log cannot flood the page.
const MAX_FINDINGS: usize = 20;

/// One matched rule with the line that triggered it.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Finding {
    pub id: String,
    pub severity: Severity,
    /// 0.0–1.0; crash-report matches outrank plain log matches.
    pub confidence: f32,
    pub title: String,
    pub body: String,
    pub excerpt: String,
    /// 1-based line inside the collected tail.
    pub line: usize,
    /// Display name of the source ("最新日志", "崩溃报告", …).
    pub source: String,
    pub source_kind: LogSourceKind,
    pub fix: FixKind,
}

/// Result of one analysis pass.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Analysis {
    pub findings: Vec<Finding>,
    pub severity: Option<Severity>,
    pub summary: String,
    pub scanned_lines: usize,
    pub rules: usize,
    pub sources: Vec<SourceInfo>,
    pub crash: Option<CrashSummary>,
    pub analyzed_at_ms: i64,
}

impl Analysis {}

/// Matches every rule against every source and returns the ranked result.
///
/// A rule contributes at most one finding: the match with the highest source
/// confidence. Sorting is severity first, then confidence, then id so the UI
/// stays stable between identical runs.
#[must_use]
pub fn analyze(bundle: &LogBundle, rules: &RuleSet) -> Analysis {
    let mut findings: Vec<Finding> = Vec::new();
    for input in &bundle.inputs {
        for rule in rules.rules() {
            if !matches_source(rule, input.kind) {
                continue;
            }
            let Some((line_index, line)) = first_match(rule, input) else {
                continue;
            };
            let confidence = confidence_for(input.kind);
            let finding = Finding {
                id: rule.id.clone(),
                severity: rule.severity,
                confidence,
                title: display_title(rule),
                body: rule.body.clone(),
                excerpt: excerpt(line),
                line: line_index + 1,
                source: input.name.clone(),
                source_kind: input.kind,
                fix: rule.fix,
            };
            match findings
                .iter_mut()
                .find(|existing| existing.id == finding.id)
            {
                Some(existing) => {
                    if finding.confidence > existing.confidence {
                        *existing = finding;
                    }
                }
                None => findings.push(finding),
            }
        }
    }

    findings.sort_by(|left, right| {
        right
            .severity
            .cmp(&left.severity)
            .then_with(|| {
                right
                    .confidence
                    .partial_cmp(&left.confidence)
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
            .then_with(|| left.id.cmp(&right.id))
    });
    findings.truncate(MAX_FINDINGS);

    let severity = findings.first().map(|finding| finding.severity);
    let scanned_lines = bundle.inputs.iter().map(|input| input.lines.len()).sum();
    let summary = summarize(&findings, bundle.crash.as_ref());

    Analysis {
        findings,
        severity,
        summary,
        scanned_lines,
        rules: rules.len(),
        sources: bundle.sources.clone(),
        crash: bundle.crash.clone(),
        analyzed_at_ms: now_ms_i64(),
    }
}

fn matches_source(rule: &Rule, kind: LogSourceKind) -> bool {
    match rule.source {
        RuleSource::Any => true,
        RuleSource::Log => kind == LogSourceKind::Log || kind == LogSourceKind::Stdout,
        RuleSource::Crash => kind == LogSourceKind::Crash,
        RuleSource::HsErr => kind == LogSourceKind::HsErr,
    }
}

fn first_match<'a>(rule: &Rule, input: &'a LogInput) -> Option<(usize, &'a str)> {
    input
        .lines
        .iter()
        .enumerate()
        .find(|(_, line)| rule.regex().is_match(line))
        .map(|(index, line)| (index, line.as_str()))
}

const fn confidence_for(kind: LogSourceKind) -> f32 {
    match kind {
        LogSourceKind::Crash => 0.92,
        LogSourceKind::HsErr => 0.86,
        LogSourceKind::Log => 0.75,
        LogSourceKind::Stdout => 0.6,
    }
}

fn display_title(rule: &Rule) -> String {
    if rule.title.is_empty() {
        rule.id.clone()
    } else {
        rule.title.clone()
    }
}

fn excerpt(line: &str) -> String {
    const MAX_CHARS: usize = 320;
    let trimmed = line.trim();
    if trimmed.chars().count() <= MAX_CHARS {
        return trimmed.to_string();
    }
    let mut text: String = trimmed.chars().take(MAX_CHARS).collect();
    text.push('…');
    text
}

fn summarize(findings: &[Finding], crash: Option<&CrashSummary>) -> String {
    let crash_text = crash
        .map(|crash| crash.description.trim())
        .filter(|description| !description.is_empty());
    match (findings.is_empty(), crash_text) {
        (true, Some(description)) => {
            format!("崩溃报告：{description}。没有匹配到已知问题，请查看日志末尾。")
        }
        (true, None) => {
            "没有匹配到已知问题。如果游戏仍然无法启动，请查看日志末尾的异常堆栈。".to_string()
        }
        (false, Some(description)) => {
            format!(
                "崩溃报告：{description}。找到 {} 个可能原因，请先看第一条。",
                findings.len()
            )
        }
        (false, None) => format!("找到 {} 个可能原因，请先看第一条。", findings.len()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::log::{LogBundle, LogInput, LogSourceKind};

    fn bundle(log: &[&str], crash: &[&str]) -> LogBundle {
        let mut bundle = LogBundle::default();
        bundle.inputs.push(LogInput {
            kind: LogSourceKind::Log,
            name: "最新日志".to_string(),
            lines: log.iter().map(|line| (*line).to_string()).collect(),
        });
        if !crash.is_empty() {
            bundle.inputs.push(LogInput {
                kind: LogSourceKind::Crash,
                name: "崩溃报告".to_string(),
                lines: crash.iter().map(|line| (*line).to_string()).collect(),
            });
            bundle.crash = Some(CrashSummary {
                description: "Rendering screen".to_string(),
                ..CrashSummary::default()
            });
        }
        bundle
    }

    #[test]
    fn ranks_crash_findings_above_log_findings() {
        let rules = RuleSet::builtin().expect("rules");
        let report = bundle(
            &["[main/WARN]: something odd"],
            &[
                "Description: Rendering screen",
                "java.lang.OutOfMemoryError: Java heap space",
            ],
        );
        let analysis = analyze(&report, &rules);
        let memory = analysis
            .findings
            .iter()
            .find(|finding| finding.id == "java.memory")
            .expect("memory finding");
        assert_eq!(memory.severity, Severity::Error);
        assert!((memory.confidence - 0.92).abs() < f32::EPSILON);
        assert_eq!(memory.line, 2);
        assert_eq!(memory.fix, FixKind::OpenJavaSettings);
        assert!(analysis.summary.contains("Rendering screen"));
    }

    #[test]
    fn one_finding_per_rule_and_a_missing_crash_is_clean() {
        let rules = RuleSet::builtin().expect("rules");
        let report = bundle(
            &[
                "[main/ERROR]: UnsupportedClassVersionError",
                "[main/ERROR]: UnsupportedClassVersionError again",
            ],
            &[],
        );
        let analysis = analyze(&report, &rules);
        assert_eq!(
            analysis
                .findings
                .iter()
                .filter(|finding| finding.id == "java.version")
                .count(),
            1
        );
        assert_eq!(analysis.findings.len(), 1);
        assert!(!analysis.summary.contains("Rendering"));
        assert_eq!(analysis.scanned_lines, 2);
        assert_eq!(analysis.severity, Some(Severity::Error));
    }

    #[test]
    fn clean_logs_produce_an_explanatory_summary() {
        let rules = RuleSet::builtin().expect("rules");
        let analysis = analyze(&bundle(&["[main/INFO]: all good"], &[]), &rules);
        assert!(analysis.findings.is_empty());
        assert!(analysis.summary.contains("没有匹配到"));
        assert_eq!(analysis.severity, None);
    }
}
