//! Fallback probing for runtimes without a `release` file (§8.1).
//!
//! Running `java` is slow and can hang on a broken runtime, so this path is
//! only used after the `release` file and binary headers were unavailable, with
//! a hard timeout and `kill_on_drop` so a timed-out probe cannot leak a child.

use std::{path::Path, process::Stdio, time::Duration};

use tokio::process::Command;

use fg_core::{Error, ErrorKind, Result};

use crate::runtime::{JavaArch, JavaVersion};

/// Values extracted from `java -XshowSettings:properties -version`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProbeInfo {
    pub version: Option<JavaVersion>,
    pub arch: JavaArch,
    pub vendor: Option<String>,
}

impl ProbeInfo {
    fn parse(text: &str) -> Self {
        let mut info = Self::default();
        for line in text.lines() {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            let key = key.trim();
            let value = value.trim();
            match key {
                "java.version" => info.version = JavaVersion::parse(value),
                "java.vendor" => info.vendor = Some(value.to_string()),
                "os.arch" => info.arch = JavaArch::parse(value),
                _ => {}
            }
        }
        info
    }
}

/// Runs `java -XshowSettings:properties -version` with a timeout.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the process cannot start, and
/// [`ErrorKind::Unsupported`] when it times out or exits non-zero.
pub async fn probe(executable: &Path, timeout: Duration) -> Result<ProbeInfo> {
    let mut command = Command::new(executable);
    command
        .arg("-XshowSettings:properties")
        .arg("-version")
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);

    let child = command.spawn().map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.java.spawn",
            format!("{}: {error}", executable.display()),
        )
    })?;

    let output = tokio::time::timeout(timeout, child.wait_with_output())
        .await
        .map_err(|_| {
            Error::with_detail(
                ErrorKind::Unsupported,
                "error.java.probe_timeout",
                format!("{} did not answer within {timeout:?}", executable.display()),
            )
        })?
        .map_err(|error| {
            Error::with_detail(ErrorKind::Io, "error.java.probe", error.to_string())
        })?;

    // `java -version` writes its banner to stderr; properties may be on either
    // stream depending on the vendor, so parse both.
    let mut text = String::from_utf8_lossy(&output.stderr).into_owned();
    text.push('\n');
    text.push_str(&String::from_utf8_lossy(&output.stdout));

    let info = ProbeInfo::parse(&text);
    if info.version.is_none() {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.java.probe_unparsed",
            format!("{}: no java.version in probe output", executable.display()),
        ));
    }
    Ok(info)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_property_output() {
        let info = ProbeInfo::parse(
            "Property settings:\n    java.version = 21.0.9\n    java.vendor = Oracle Corporation\n    os.arch = aarch64\n",
        );
        assert_eq!(info.version.expect("version").major, 21);
        assert_eq!(info.arch, JavaArch::Arm64);
        assert_eq!(info.vendor.as_deref(), Some("Oracle Corporation"));
    }

    #[test]
    fn parses_legacy_version_output() {
        let info = ProbeInfo::parse("    java.version = 1.8.0_402\n    os.arch = x86_64\n");
        assert_eq!(info.version.expect("version").major, 8);
        assert_eq!(info.arch, JavaArch::X86_64);
    }
}
