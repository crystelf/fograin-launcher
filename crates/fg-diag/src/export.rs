//! Redacted diagnostic bundle (`diagnostics/<instance>-<timestamp>.zip`).

use std::{
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
};

use fg_core::util::sanitize_component;
use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;
use time::{OffsetDateTime, macros::format_description};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

use crate::analyze::Analysis;

/// Inputs for one bundle. Everything here is plain text or already redacted;
/// account tokens and credentials are never passed in.
pub struct BundleRequest<'a> {
    pub data_root: &'a Path,
    pub instance_id: &'a str,
    pub instance_name: &'a str,
    pub minecraft: &'a str,
    pub loader: &'a str,
    pub java: &'a str,
    pub memory_mb: u32,
    pub analysis: &'a Analysis,
    /// `(archive path, text)` pairs, e.g. `logs/latest.log`.
    pub log_texts: Vec<(String, String)>,
    /// Instance settings and content index as JSON (never account data).
    pub instance_json: String,
    pub app_version: &'a str,
    pub platform: &'a str,
}

/// Written bundle.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Bundle {
    pub path: String,
    pub size_bytes: u64,
    pub files: Vec<String>,
}

/// Writes the bundle and returns its path.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the output directory or archive cannot be
/// written.
pub fn export_bundle(request: &BundleRequest<'_>) -> Result<Bundle> {
    let directory = request.data_root.join("diagnostics");
    fs::create_dir_all(&directory).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.export",
            format!("{}: {error}", directory.display()),
        )
    })?;
    let path = unique_path(&directory, request.instance_name);
    let result = write_bundle(&path, request);
    if let Err(error) = result {
        let _ = fs::remove_file(&path);
        return Err(error);
    }
    let size_bytes = fs::metadata(&path).map_or(0, |metadata| metadata.len());
    Ok(Bundle {
        path: path.display().to_string(),
        size_bytes,
        files: bundle_files(request),
    })
}

fn bundle_files(request: &BundleRequest<'_>) -> Vec<String> {
    let mut files = vec![
        "report.md".to_string(),
        "manifest.json".to_string(),
        "instance.json".to_string(),
    ];
    files.extend(
        request
            .log_texts
            .iter()
            .map(|(name, _)| name.clone())
            .filter(|name| safe_entry(name)),
    );
    files
}

fn unique_path(directory: &Path, instance_name: &str) -> PathBuf {
    let safe = sanitize_component(instance_name, "instance");
    let now = OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc());
    let stamp = now
        .format(&format_description!(
            "[year][month][day]-[hour][minute][second]"
        ))
        .unwrap_or_else(|_| "bundle".to_string());
    let mut candidate = directory.join(format!("{safe}-{stamp}.zip"));
    let mut counter = 2;
    while candidate.exists() {
        candidate = directory.join(format!("{safe}-{stamp}-{counter}.zip"));
        counter += 1;
    }
    candidate
}

fn write_bundle(path: &Path, request: &BundleRequest<'_>) -> Result<()> {
    let file = File::create(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.export",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut writer = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    write_entry(&mut writer, "report.md", &report(request), options)?;
    write_entry(
        &mut writer,
        "manifest.json",
        &manifest_json(request),
        options,
    )?;
    write_entry(
        &mut writer,
        "instance.json",
        &request.instance_json,
        options,
    )?;
    for (name, text) in &request.log_texts {
        if !safe_entry(name) {
            continue;
        }
        write_entry(&mut writer, name, text, options)?;
    }

    writer.finish().map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.export",
            format!("{}: {error}", path.display()),
        )
    })?;
    Ok(())
}

fn write_entry(
    writer: &mut ZipWriter<File>,
    name: &str,
    text: &str,
    options: SimpleFileOptions,
) -> Result<()> {
    writer.start_file(name, options).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.export",
            format!("{name}: {error}"),
        )
    })?;
    writer.write_all(text.as_bytes()).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.export",
            format!("{name}: {error}"),
        )
    })
}

fn safe_entry(name: &str) -> bool {
    !name.is_empty()
        && !name.starts_with('/')
        && !name.contains('\\')
        && !name.split('/').any(|part| part == ".." || part == ".")
}

fn report(request: &BundleRequest<'_>) -> String {
    let analysis = request.analysis;
    let mut text = String::with_capacity(2048);
    text.push_str("# 雾雨启动器诊断报告\n\n");
    text.push_str(&format!(
        "- 实例：{}（{}）\n- Minecraft：{}\n- 加载器：{}\n- Java：{}\n- 最大内存：{} MB\n- 平台：{} · 启动器 {}\n- 生成时间：{}\n\n",
        request.instance_name,
        request.instance_id,
        request.minecraft,
        if request.loader.is_empty() {
            "原版"
        } else {
            request.loader
        },
        if request.java.is_empty() {
            "未记录"
        } else {
            request.java
        },
        request.memory_mb,
        request.platform,
        request.app_version,
        OffsetDateTime::now_utc()
            .format(&format_description!(
                "[year]-[month]-[day] [hour]:[minute]:[second] UTC"
            ))
            .unwrap_or_default(),
    ));
    if let Some(crash) = &analysis.crash {
        text.push_str("## 崩溃报告\n\n");
        text.push_str(&format!("- 描述：{}\n", crash.description));
        text.push_str(&format!("- 游戏版本：{}\n", crash.minecraft));
        text.push_str(&format!("- Java：{}\n", crash.java));
        text.push_str(&format!("- 文件：{}\n\n", crash.file));
    }
    text.push_str("## 结论\n\n");
    text.push_str(&analysis.summary);
    text.push_str("\n\n");
    if analysis.findings.is_empty() {
        text.push_str("没有命中已知规则。请查看 `logs/latest.log` 的末尾与 `crash-reports/`。\n");
    } else {
        text.push_str(&format!(
            "共命中 {} 条规则（分析了 {} 行）：\n\n",
            analysis.findings.len(),
            analysis.scanned_lines
        ));
        for finding in &analysis.findings {
            let percent = (finding.confidence * 100.0).round() as u32;
            text.push_str(&format!(
                "### [{}] {}（{}%）\n\n- 来源：{} 第 {} 行\n- 修复建议：{}\n\n```\n{}\n```\n\n",
                finding.severity.as_str(),
                finding.title,
                percent,
                finding.source,
                finding.line,
                finding.body,
                finding.excerpt,
            ));
        }
    }
    text.push_str("## 附件\n\n");
    for (name, _) in &request.log_texts {
        text.push_str(&format!("- `{name}`\n"));
    }
    text
}

fn manifest_json(request: &BundleRequest<'_>) -> String {
    serde_json::json!({
        "appVersion": request.app_version,
        "platform": request.platform,
        "instanceId": request.instance_id,
        "instanceName": request.instance_name,
        "minecraft": request.minecraft,
        "loader": request.loader,
        "java": request.java,
        "memoryMb": request.memory_mb,
        "analyzedAtMs": request.analysis.analyzed_at_ms,
        "findings": request.analysis.findings.len(),
        "files": bundle_files(request),
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use std::io::Read;

    use super::*;
    use crate::{
        analyze::{Analysis, Finding},
        log::LogSourceKind,
        rule::{FixKind, Severity},
    };

    fn analysis() -> Analysis {
        Analysis {
            findings: vec![Finding {
                id: "java.memory".to_string(),
                severity: Severity::Error,
                confidence: 0.92,
                title: "内存不足".to_string(),
                body: "提高内存".to_string(),
                excerpt: "java.lang.OutOfMemoryError".to_string(),
                line: 12,
                source: "崩溃报告".to_string(),
                source_kind: LogSourceKind::Crash,
                fix: FixKind::OpenJavaSettings,
            }],
            severity: Some(Severity::Error),
            summary: "找到 1 个可能原因。".to_string(),
            scanned_lines: 40,
            rules: 14,
            sources: Vec::new(),
            crash: None,
            analyzed_at_ms: 1,
        }
    }

    #[test]
    fn writes_a_bundle_with_report_and_logs() {
        let temp = tempfile::tempdir().expect("tempdir");
        let analysis = analysis();
        let bundle = export_bundle(&BundleRequest {
            data_root: temp.path(),
            instance_id: "fog",
            instance_name: "雾林 / 生存",
            minecraft: "1.21.4",
            loader: "fabric 0.16.9",
            java: "Java 21",
            memory_mb: 6144,
            analysis: &analysis,
            log_texts: vec![
                ("logs/latest.log".to_string(), "line\n".to_string()),
                ("../escape.txt".to_string(), "bad".to_string()),
            ],
            instance_json: "{\"id\":\"fog\"}".to_string(),
            app_version: "0.1.0",
            platform: "macos",
        })
        .expect("export");
        assert!(bundle.path.ends_with(".zip"));
        assert!(bundle.files.contains(&"logs/latest.log".to_string()));
        assert!(!bundle.files.contains(&"../escape.txt".to_string()));

        let file = File::open(&bundle.path).expect("open");
        let mut archive = zip::ZipArchive::new(file).expect("archive");
        let mut report = String::new();
        archive
            .by_name("report.md")
            .expect("report")
            .read_to_string(&mut report)
            .expect("read");
        assert!(report.contains("内存不足"));
        assert!(report.contains("1.21.4"));
        assert!(archive.by_name("../escape.txt").is_err());
    }

    #[test]
    fn sanitizes_the_instance_name_in_the_file_name() {
        assert_eq!(
            sanitize_component("New World / 1.21", "instance"),
            "New-World-1.21"
        );
        assert_eq!(sanitize_component("///", "instance"), "instance");
    }
}
