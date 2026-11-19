//! Finding and reading the diagnostic sources of one instance.

use std::{
    fs::{self, File},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::Serialize;

/// Largest tail read from one log file. Logs above this size are truncated to
/// the last 8 MiB; the analysis only needs the end of a session anyway.
const MAX_TAIL_BYTES: u64 = 8 * 1024 * 1024;

/// Which family a collected text belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum LogSourceKind {
    Stdout,
    Log,
    Crash,
    HsErr,
}

impl LogSourceKind {
    /// Stable wire value.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Stdout => "stdout",
            Self::Log => "log",
            Self::Crash => "crash",
            Self::HsErr => "hsErr",
        }
    }
}

/// One source reported to the UI, existing or not.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceInfo {
    pub kind: LogSourceKind,
    pub name: String,
    pub path: String,
    pub exists: bool,
    pub size_bytes: u64,
    pub modified_ms: Option<i64>,
    /// Lines collected for analysis from this source.
    pub lines: usize,
}

/// Text collected from one source.
#[derive(Debug, Clone)]
pub struct LogInput {
    pub kind: LogSourceKind,
    pub name: String,
    pub lines: Vec<String>,
}

/// Header fields parsed from the newest crash report.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CrashSummary {
    pub description: String,
    pub minecraft: String,
    pub java: String,
    pub file: String,
    pub modified_ms: Option<i64>,
}

/// Everything the analysis and the export bundle need.
#[derive(Debug, Clone, Default)]
pub struct LogBundle {
    pub sources: Vec<SourceInfo>,
    pub inputs: Vec<LogInput>,
    pub crash: Option<CrashSummary>,
}

impl LogBundle {
    /// Input for one source kind, if it was collected.
    #[must_use]
    pub fn input(&self, kind: LogSourceKind) -> Option<&LogInput> {
        self.inputs.iter().find(|input| input.kind == kind)
    }
}

/// Collects stdout, `logs/latest.log`, the newest crash report and hs_err file.
///
/// `stdout` is the launcher's ring buffer for the current session; the other
/// sources are read from `game_dir`. Missing files are reported with
/// `exists = false` instead of failing: a freshly created instance has none.
#[must_use]
pub fn collect(game_dir: &Path, stdout: Vec<String>, max_lines: usize) -> LogBundle {
    let max_lines = max_lines.clamp(100, 20_000);
    let mut bundle = LogBundle::default();

    if !stdout.is_empty() {
        let lines: Vec<String> = stdout
            .into_iter()
            .rev()
            .take(max_lines)
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect();
        bundle.sources.push(SourceInfo {
            kind: LogSourceKind::Stdout,
            name: "本次运行输出".to_string(),
            path: String::new(),
            exists: true,
            size_bytes: lines.iter().map(|line| line.len() as u64 + 1).sum(),
            modified_ms: None,
            lines: lines.len(),
        });
        bundle.inputs.push(LogInput {
            kind: LogSourceKind::Stdout,
            name: "本次运行输出".to_string(),
            lines,
        });
    }

    let latest = game_dir.join("logs").join("latest.log");
    collect_file(
        &mut bundle,
        LogSourceKind::Log,
        "最新日志",
        &latest,
        max_lines,
    );

    let crash_dir = game_dir.join("crash-reports");
    let crash = newest_file(&crash_dir, |name| name.ends_with(".txt"));
    match crash {
        Some(path) => {
            collect_file(
                &mut bundle,
                LogSourceKind::Crash,
                "崩溃报告",
                &path,
                max_lines,
            );
            if let Some(input) = bundle.input(LogSourceKind::Crash) {
                bundle.crash = Some(parse_crash(&input.lines, &path));
            }
        }
        None => bundle.sources.push(SourceInfo {
            kind: LogSourceKind::Crash,
            name: "崩溃报告".to_string(),
            path: crash_dir.display().to_string(),
            exists: false,
            size_bytes: 0,
            modified_ms: None,
            lines: 0,
        }),
    }

    let hs_err = newest_file(game_dir, |name| {
        name.starts_with("hs_err_pid") && name.ends_with(".log")
    });
    match hs_err {
        Some(path) => collect_file(
            &mut bundle,
            LogSourceKind::HsErr,
            "JVM 崩溃日志",
            &path,
            max_lines,
        ),
        None => bundle.sources.push(SourceInfo {
            kind: LogSourceKind::HsErr,
            name: "JVM 崩溃日志".to_string(),
            path: game_dir.display().to_string(),
            exists: false,
            size_bytes: 0,
            modified_ms: None,
            lines: 0,
        }),
    }

    bundle
}

fn collect_file(
    bundle: &mut LogBundle,
    kind: LogSourceKind,
    label: &str,
    path: &Path,
    max_lines: usize,
) {
    let metadata = fs::metadata(path);
    match read_tail(path, max_lines) {
        Ok(lines) => {
            let name = path
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_else(|| label.to_string());
            bundle.sources.push(SourceInfo {
                kind,
                name,
                path: path.display().to_string(),
                exists: true,
                size_bytes: metadata.as_ref().map(|meta| meta.len()).unwrap_or(0),
                modified_ms: metadata
                    .ok()
                    .and_then(|meta| meta.modified().ok())
                    .and_then(to_epoch_ms),
                lines: lines.len(),
            });
            bundle.inputs.push(LogInput {
                kind,
                name: label.to_string(),
                lines,
            });
        }
        Err(error) => {
            // A directory named `latest.log` or a file we cannot read still
            // appears in the source list so the UI can explain the gap.
            tracing::warn!(path = %path.display(), %error, "cannot read diagnostic source");
            bundle.sources.push(SourceInfo {
                kind,
                name: label.to_string(),
                path: path.display().to_string(),
                exists: metadata.is_ok(),
                size_bytes: metadata.as_ref().map(|meta| meta.len()).unwrap_or(0),
                modified_ms: metadata
                    .ok()
                    .and_then(|meta| meta.modified().ok())
                    .and_then(to_epoch_ms),
                lines: 0,
            });
        }
    }
}

fn newest_file(directory: &Path, accept: impl Fn(&str) -> bool) -> Option<PathBuf> {
    let entries = fs::read_dir(directory).ok()?;
    let mut best: Option<(SystemTime, PathBuf)> = None;
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !accept(&name) {
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        if !metadata.is_file() {
            continue;
        }
        let modified = metadata.modified().unwrap_or(UNIX_EPOCH);
        if best.as_ref().is_none_or(|(current, _)| modified > *current) {
            best = Some((modified, entry.path()));
        }
    }
    best.map(|(_, path)| path)
}

/// Reads the last `max_lines` lines of a UTF-8 (or mostly UTF-8) text file.
///
/// The file is read from the end when it is larger than [`MAX_TAIL_BYTES`], so a
/// multi-gigabyte log cannot exhaust memory.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] for a missing file and [`ErrorKind::Io`] for
/// read failures.
pub fn read_tail(path: &Path, max_lines: usize) -> Result<Vec<String>> {
    let mut file = File::open(path).map_err(|error| {
        if error.kind() == std::io::ErrorKind::NotFound {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.diag.missing",
                path.display().to_string(),
            )
        } else {
            Error::with_detail(
                ErrorKind::Io,
                "error.diag.read",
                format!("{}: {error}", path.display()),
            )
        }
    })?;
    let length = file.metadata().map(|meta| meta.len()).unwrap_or(0);
    let skip = length.saturating_sub(MAX_TAIL_BYTES);
    if skip > 0 {
        file.seek(SeekFrom::Start(skip)).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.diag.read",
                format!("{}: {error}", path.display()),
            )
        })?;
    }
    let mut buffer = Vec::new();
    file.read_to_end(&mut buffer).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.diag.read",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut text = String::from_utf8_lossy(&buffer).into_owned();
    if skip > 0 {
        // Drop the partial first line after seeking into the middle of a file.
        if let Some(index) = text.find('\n') {
            text.drain(..=index);
        }
    }
    let mut lines: Vec<String> = text
        .lines()
        .map(|line| line.trim_end_matches('\r').to_string())
        .collect();
    if lines.len() > max_lines {
        lines.drain(..lines.len() - max_lines);
    }
    Ok(lines)
}

fn parse_crash(lines: &[String], path: &Path) -> CrashSummary {
    let mut summary = CrashSummary {
        file: path.display().to_string(),
        modified_ms: fs::metadata(path)
            .ok()
            .and_then(|meta| meta.modified().ok())
            .and_then(to_epoch_ms),
        ..CrashSummary::default()
    };
    for line in lines.iter().take(40) {
        if let Some(value) = line.strip_prefix("Description:") {
            summary.description = value.trim().to_string();
        } else if let Some(value) = line.strip_prefix("Minecraft Version:") {
            summary.minecraft = value.trim().to_string();
        } else if let Some(value) = line.strip_prefix("Java Version:") {
            summary.java = value.trim().to_string();
        }
    }
    if summary.description.is_empty() {
        // Modded crash reports often put the exception in the description block;
        // fall back to the first exception line so the card is never blank.
        if let Some(line) = lines.iter().find(|line| {
            line.contains("Exception") || line.contains("Error") || line.contains("Caused by")
        }) {
            summary.description = line.trim().to_string();
        }
    }
    summary
}

pub(crate) fn to_epoch_ms(time: SystemTime) -> Option<i64> {
    time.duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collects_sources_and_parses_the_crash_header() {
        let temp = tempfile::tempdir().expect("tempdir");
        let game = temp.path();
        fs::create_dir_all(game.join("logs")).expect("logs");
        fs::create_dir_all(game.join("crash-reports")).expect("crash");
        fs::write(
            game.join("logs/latest.log"),
            "[12:00:00] [main/INFO]: hello\n[12:00:01] [main/ERROR]: boom\n",
        )
        .expect("log");
        fs::write(
            game.join("crash-reports/crash-2026-11-17_12.00.00-client.txt"),
            "---- Minecraft Crash Report ----\n// Oops\n\nTime: 2026-11-17\nDescription: Rendering screen\n\nMinecraft Version: 1.21.4\nJava Version: 21.0.7, Mojang\n\njava.lang.RuntimeException\n",
        )
        .expect("crash");

        let bundle = collect(game, vec!["stdout line".to_string()], 500);
        assert_eq!(
            bundle.input(LogSourceKind::Log).expect("log").lines.len(),
            2
        );
        assert_eq!(
            bundle
                .input(LogSourceKind::Stdout)
                .expect("stdout")
                .lines
                .len(),
            1
        );
        let crash = bundle.crash.expect("crash summary");
        assert_eq!(crash.description, "Rendering screen");
        assert_eq!(crash.minecraft, "1.21.4");
        assert!(crash.java.starts_with("21.0.7"));
        assert!(
            bundle
                .sources
                .iter()
                .any(|source| source.kind == LogSourceKind::HsErr)
        );
    }

    #[test]
    fn missing_sources_are_reported_not_errors() {
        let temp = tempfile::tempdir().expect("tempdir");
        let bundle = collect(temp.path(), Vec::new(), 500);
        assert!(bundle.inputs.is_empty());
        assert_eq!(bundle.sources.len(), 3);
        assert!(bundle.sources.iter().all(|source| !source.exists));
        assert!(bundle.crash.is_none());
    }

    #[test]
    fn a_large_tail_is_cut_to_the_last_lines() {
        let temp = tempfile::tempdir().expect("tempdir");
        let path = temp.path().join("latest.log");
        let text: String = (0..200).map(|index| format!("line {index}\n")).collect();
        fs::write(&path, text).expect("write");

        let lines = read_tail(&path, 10).expect("tail");
        assert_eq!(lines.len(), 10);
        assert_eq!(lines.last().map(String::as_str), Some("line 199"));
        assert_eq!(
            read_tail(&temp.path().join("missing.log"), 10)
                .expect_err("missing")
                .kind,
            ErrorKind::NotFound
        );
    }
}
