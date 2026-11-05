//! Choosing a Java runtime for a version (§8.2).

use fg_core::{Error, ErrorKind, Result};

use crate::runtime::{JavaArch, JavaRuntime};

/// Non-fatal problems with a selected runtime, surfaced in the launch dialog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JavaWarning {
    /// No runtime with the exact major version was found.
    TooNew { found: u32, required: u32 },
    /// The runtime's architecture differs from the host.
    ArchMismatch { runtime: JavaArch, host: JavaArch },
    /// The runtime could not report its version.
    UnknownVersion,
}

impl JavaWarning {
    /// Stable message key for the UI.
    #[must_use]
    pub const fn message_key(&self) -> &'static str {
        match self {
            Self::TooNew { .. } => "error.java.too_new",
            Self::ArchMismatch { .. } => "error.java.arch_mismatch",
            Self::UnknownVersion => "error.java.unknown_version",
        }
    }
}

/// A runtime plus why it was chosen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaSelection {
    pub runtime: JavaRuntime,
    /// True when `runtime.major == required`.
    pub exact: bool,
    pub warnings: Vec<JavaWarning>,
}

/// Required Java major for a Minecraft version (§8.2).
///
/// `declared` is `version_json.javaVersion.majorVersion`; when present it always
/// wins. Snapshots are mapped by year because their file names carry no
/// semantic version.
#[must_use]
pub fn required_major(version_id: &str, declared: Option<u32>) -> u32 {
    if let Some(major) = declared {
        return major;
    }

    if let Some(rest) = version_id.strip_prefix("1.") {
        let mut parts = rest.split(['.', '-', '_']);
        let Some(minor) = parts.next().and_then(|value| value.parse::<u32>().ok()) else {
            return 21;
        };
        let patch = parts
            .next()
            .and_then(|value| value.parse::<u32>().ok())
            .unwrap_or(0);
        return match minor {
            0..=16 => 8,
            17 => 16,
            18..=19 => 17,
            20 if patch <= 4 => 17,
            _ => 21,
        };
    }

    if let Some(year) = snapshot_year(version_id) {
        return match year {
            0..=20 => 8,
            21 => 16,
            22..=23 => 17,
            _ => 21,
        };
    }

    if version_id.starts_with("rd-")
        || version_id.starts_with("in-")
        || version_id.starts_with("c0.")
        || version_id.starts_with("inf-")
    {
        return 8;
    }

    // Future version schemes (e.g. `26.3`). `javaVersion` is present for those
    // in the official manifest, so this is only a last resort.
    21
}

fn snapshot_year(version_id: &str) -> Option<u32> {
    let (year, rest) = version_id.split_once('w')?;
    if rest.is_empty() {
        return None;
    }
    year.parse().ok()
}

/// Selects the best runtime for `required` major.
///
/// Preference order: exact major, same architecture, then discovery order.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] when no runtime is installed at all or when
/// no runtime is new enough for the version.
pub fn select(
    runtimes: &[JavaRuntime],
    required: u32,
    host_arch: JavaArch,
) -> Result<JavaSelection> {
    if runtimes.is_empty() {
        return Err(Error::new(ErrorKind::NotFound, "error.java.none_installed"));
    }

    let mut warnings = Vec::new();
    let (candidate, exact) = if let Some(candidate) = best_exact(runtimes, required, host_arch) {
        (candidate, true)
    } else if let Some(candidate) = best_newer(runtimes, required, host_arch) {
        warnings.push(JavaWarning::TooNew {
            found: candidate.major().unwrap_or(0),
            required,
        });
        (candidate, false)
    } else {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.java.required_missing",
            format!("need Java {required} or newer; none installed"),
        ));
    };

    if candidate.arch != JavaArch::Unknown
        && host_arch != JavaArch::Unknown
        && candidate.arch != host_arch
    {
        warnings.push(JavaWarning::ArchMismatch {
            runtime: candidate.arch,
            host: host_arch,
        });
    }
    if candidate.version.is_none() {
        warnings.push(JavaWarning::UnknownVersion);
    }

    Ok(JavaSelection {
        runtime: candidate.clone(),
        exact,
        warnings,
    })
}

fn best_exact(
    runtimes: &[JavaRuntime],
    required: u32,
    host_arch: JavaArch,
) -> Option<&JavaRuntime> {
    let mut candidates: Vec<&JavaRuntime> = runtimes
        .iter()
        .filter(|runtime| runtime.major() == Some(required))
        .collect();
    candidates.sort_by_key(|runtime| arch_rank(runtime.arch, host_arch));
    candidates.first().copied()
}

fn best_newer(
    runtimes: &[JavaRuntime],
    required: u32,
    host_arch: JavaArch,
) -> Option<&JavaRuntime> {
    let mut candidates: Vec<&JavaRuntime> = runtimes
        .iter()
        .filter(|runtime| runtime.major().is_some_and(|major| major > required))
        .collect();
    candidates.sort_by_key(|runtime| {
        (
            runtime.major().unwrap_or(u32::MAX),
            arch_rank(runtime.arch, host_arch),
        )
    });
    candidates.first().copied()
}

fn arch_rank(arch: JavaArch, host_arch: JavaArch) -> u8 {
    if arch == host_arch {
        0
    } else if arch == JavaArch::Unknown {
        1
    } else {
        2
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;

    use super::*;
    use crate::runtime::JavaVersion;

    fn runtime(home: &str, major: u32, arch: JavaArch) -> JavaRuntime {
        JavaRuntime {
            home: PathBuf::from(home),
            executable: PathBuf::from(home).join("bin/java"),
            version: Some(JavaVersion {
                major,
                full: format!("{major}.0.0"),
            }),
            arch,
            vendor: None,
            source: crate::runtime::JavaSource::ExtraRoot,
        }
    }

    #[test]
    fn infers_java_from_release_versions() {
        let cases = [
            ("1.0", 8),
            ("1.6.4", 8),
            ("1.7.10", 8),
            ("1.12.2", 8),
            ("1.16.5", 8),
            ("1.17", 16),
            ("1.17.1", 16),
            ("1.18", 17),
            ("1.19.4", 17),
            ("1.20.4", 17),
            ("1.20.5", 21),
            ("1.21.4", 21),
            ("rd-132211", 8),
            ("23w18a", 17),
            ("24w14a", 21),
            ("26.4-snapshot-7", 21),
        ];
        for (version, expected) in cases {
            assert_eq!(required_major(version, None), expected, "{version}");
        }
        assert_eq!(required_major("1.8.9", Some(21)), 21);
    }

    #[test]
    fn prefers_an_exact_major_and_host_arch() {
        let runtimes = vec![
            runtime("/jdk-17", 17, JavaArch::Arm64),
            runtime("/jdk-21-x64", 21, JavaArch::X86_64),
            runtime("/jdk-21-arm", 21, JavaArch::Arm64),
        ];
        let selected = select(&runtimes, 21, JavaArch::Arm64).expect("select");
        assert!(selected.exact);
        assert_eq!(selected.runtime.home, PathBuf::from("/jdk-21-arm"));
        assert!(selected.warnings.is_empty());
    }

    #[test]
    fn falls_back_to_the_closest_newer_runtime_with_a_warning() {
        let runtimes = vec![
            runtime("/jdk-17", 17, JavaArch::Arm64),
            runtime("/jdk-25", 25, JavaArch::Arm64),
        ];
        let selected = select(&runtimes, 21, JavaArch::Arm64).expect("select");
        assert!(!selected.exact);
        assert_eq!(selected.runtime.major(), Some(25));
        assert_eq!(
            selected.warnings[0],
            JavaWarning::TooNew {
                found: 25,
                required: 21
            }
        );
    }

    #[test]
    fn reports_an_architecture_mismatch() {
        let runtimes = vec![runtime("/jdk-21-x64", 21, JavaArch::X86_64)];
        let selected = select(&runtimes, 21, JavaArch::Arm64).expect("select");
        assert_eq!(
            selected.warnings[0],
            JavaWarning::ArchMismatch {
                runtime: JavaArch::X86_64,
                host: JavaArch::Arm64
            }
        );
    }

    #[test]
    fn errors_when_nothing_is_new_enough() {
        let runtimes = vec![runtime("/jdk-8", 8, JavaArch::Arm64)];
        let error = select(&runtimes, 17, JavaArch::Arm64).expect_err("too old");
        assert_eq!(error.kind, ErrorKind::NotFound);
        assert_eq!(error.message_key, "error.java.required_missing");
    }
}
