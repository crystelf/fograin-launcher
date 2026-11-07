//! Java discovery (§8.1).
//!
//! The scanner is intentionally conservative: it only records directories that
//! really contain `bin/java`, deduplicates by canonical path and never runs a
//! JVM unless the metadata is missing. That keeps "refresh Java list" fast on a
//! machine with many JDKs.

use std::{
    collections::HashSet,
    env,
    path::{Path, PathBuf},
    time::Duration,
};

use crate::runtime::{JavaRuntime, JavaSource, executable_in};

/// Options for [`JavaScanner`].
#[derive(Debug, Clone)]
pub struct JavaScanner {
    /// Extra roots to scan, e.g. `<data root>/runtimes` (§5.1).
    pub extra_roots: Vec<PathBuf>,
    /// Read `JAVA_HOME` and `PATH`.
    pub include_environment: bool,
    /// Use platform-specific well-known locations (registry, `/usr/lib/jvm`…).
    pub include_platform: bool,
    /// Maximum depth below an extra root / vendor directory.
    pub max_depth: usize,
}

impl Default for JavaScanner {
    fn default() -> Self {
        Self {
            extra_roots: Vec::new(),
            include_environment: true,
            include_platform: true,
            max_depth: 3,
        }
    }
}

impl JavaScanner {
    /// A scanner with default options.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a directory that may contain runtimes (a launcher-managed root).
    #[must_use]
    pub fn with_extra_root(mut self, root: impl Into<PathBuf>) -> Self {
        self.extra_roots.push(root.into());
        self
    }

    /// Synchronous discovery: parses `release` files and binary headers.
    #[must_use]
    pub fn scan(&self) -> Vec<JavaRuntime> {
        let mut seen = HashSet::new();
        let mut runtimes = Vec::new();

        for (candidate, source) in self.candidates() {
            let Some(home) = normalize_home(&candidate) else {
                continue;
            };
            let key = std::fs::canonicalize(&home).unwrap_or_else(|_| home.clone());
            if !seen.insert(key) {
                continue;
            }
            if let Some(runtime) = JavaRuntime::from_home(home, source) {
                runtimes.push(runtime);
            }
        }

        runtimes.sort_by(|left, right| {
            right
                .major()
                .cmp(&left.major())
                .then_with(|| left.home.cmp(&right.home))
        });
        runtimes
    }

    /// Discovery plus the slow `java -XshowSettings` fallback for JDKs without
    /// a `release` file.
    pub async fn scan_probing(&self, timeout: Duration) -> Vec<JavaRuntime> {
        let mut runtimes = self.scan();
        for runtime in &mut runtimes {
            if runtime.version.is_none() {
                let _ = runtime.probe(timeout).await;
            }
        }
        runtimes
    }

    fn candidates(&self) -> Vec<(PathBuf, JavaSource)> {
        let mut candidates = Vec::new();

        if self.include_environment {
            if let Some(home) = env::var_os("JAVA_HOME").filter(|value| !value.is_empty()) {
                candidates.push((PathBuf::from(home), JavaSource::JavaHomeEnv));
            }
            if let Some(path) = env::var_os("PATH") {
                for entry in env::split_paths(&path) {
                    let executable = entry.join(if cfg!(target_os = "windows") {
                        "java.exe"
                    } else {
                        "java"
                    });
                    if executable.is_file()
                        && let Some(home) = entry.parent()
                    {
                        candidates.push((home.to_path_buf(), JavaSource::PathEnv));
                    }
                }
            }
        }

        if self.include_platform {
            #[cfg(target_os = "macos")]
            candidates.extend(macos_candidates());
            #[cfg(target_os = "linux")]
            candidates.extend(linux_candidates());
            #[cfg(target_os = "windows")]
            candidates.extend(windows_candidates());
        }

        for root in &self.extra_roots {
            let source = if root.file_name().is_some_and(|name| name == "runtimes") {
                JavaSource::LauncherManaged
            } else {
                JavaSource::ExtraRoot
            };
            for home in walk_for_homes(root, self.max_depth) {
                candidates.push((home, source));
            }
        }

        candidates
    }
}

/// Convenience wrapper used by the CLI and later by the FFI.
#[must_use]
pub fn discover() -> Vec<JavaRuntime> {
    JavaScanner::new().scan()
}

/// Resolves a candidate directory to a runtime home.
#[must_use]
pub fn normalize_home(candidate: &Path) -> Option<PathBuf> {
    let direct = [
        candidate.to_path_buf(),
        candidate.join("Contents").join("Home"),
        candidate
            .join("libexec")
            .join("openjdk.jdk")
            .join("Contents")
            .join("Home"),
        candidate.join("Home"),
    ];
    for home in direct {
        if executable_in(&home).is_some() {
            return Some(home);
        }
    }

    // A path to the executable itself: `<home>/bin/java`.
    let name = candidate.file_name()?.to_string_lossy();
    if name == "java" || name == "java.exe" {
        let bin = candidate.parent()?;
        if bin.file_name().is_some_and(|name| name == "bin") {
            return bin.parent().map(Path::to_path_buf);
        }
    }
    None
}

/// Walks at most `depth` levels under `root` looking for Java homes.
fn walk_for_homes(root: &Path, depth: usize) -> Vec<PathBuf> {
    let mut found = Vec::new();
    if depth == 0 || !root.is_dir() {
        return found;
    }
    if normalize_home(root).is_some() {
        found.push(root.to_path_buf());
    }
    let Ok(entries) = std::fs::read_dir(root) else {
        return found;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() && !path.is_symlink() {
            found.extend(walk_for_homes(&path, depth - 1));
        }
    }
    found
}

#[cfg(target_os = "macos")]
fn macos_candidates() -> Vec<(PathBuf, JavaSource)> {
    let mut candidates = Vec::new();

    if let Ok(output) = std::process::Command::new("/usr/libexec/java_home")
        .arg("-V")
        .output()
    {
        let text = String::from_utf8_lossy(&output.stderr);
        for line in text.lines() {
            if let Some(path) = line.split_whitespace().last()
                && path.starts_with('/')
            {
                candidates.push((PathBuf::from(path), JavaSource::MacJavaHome));
            }
        }
    }

    let mut roots = vec![
        PathBuf::from("/Library/Java/JavaVirtualMachines"),
        PathBuf::from("/System/Library/Java/JavaVirtualMachines"),
    ];
    if let Some(home) = dirs::home_dir() {
        roots.push(home.join("Library/Java/JavaVirtualMachines"));
    }
    for root in roots {
        candidates.extend(jvm_bundles(&root));
    }

    for pattern in [
        "/opt/homebrew/opt/openjdk*",
        "/usr/local/opt/openjdk*",
        "/opt/homebrew/Cellar/openjdk*/*/libexec/openjdk.jdk/Contents/Home",
        "/usr/local/Cellar/openjdk*/*/libexec/openjdk.jdk/Contents/Home",
    ] {
        if let Ok(paths) = glob::glob(pattern) {
            for path in paths.flatten() {
                candidates.push((path, JavaSource::Homebrew));
            }
        }
    }
    candidates
}

#[cfg(target_os = "macos")]
fn jvm_bundles(root: &Path) -> Vec<(PathBuf, JavaSource)> {
    let mut candidates = Vec::new();
    if let Ok(entries) = std::fs::read_dir(root) {
        for entry in entries.flatten() {
            let home = entry.path().join("Contents").join("Home");
            if executable_in(&home).is_some() {
                candidates.push((home, JavaSource::MacJavaVirtualMachines));
            }
        }
    }
    candidates
}

#[cfg(target_os = "linux")]
fn linux_candidates() -> Vec<(PathBuf, JavaSource)> {
    let mut candidates = Vec::new();
    for root in ["/usr/lib/jvm", "/usr/lib64/jvm", "/opt"] {
        let root = Path::new(root);
        if let Ok(entries) = std::fs::read_dir(root) {
            for entry in entries.flatten() {
                let path = entry.path();
                let name = path
                    .file_name()
                    .map_or_else(String::new, |name| name.to_string_lossy().into_owned());
                if root == Path::new("/opt")
                    && !name.to_ascii_lowercase().contains("jdk")
                    && !name.to_ascii_lowercase().contains("java")
                {
                    continue;
                }
                candidates.push((path, JavaSource::LinuxJvm));
            }
        }
    }
    if let Some(home) = dirs::home_dir()
        && let Ok(entries) = std::fs::read_dir(home.join(".sdkman/candidates/java"))
    {
        for entry in entries.flatten() {
            candidates.push((entry.path(), JavaSource::Sdkman));
        }
    }
    candidates
}

#[cfg(target_os = "windows")]
fn windows_candidates() -> Vec<(PathBuf, JavaSource)> {
    let mut candidates = Vec::new();

    // Registry: JavaSoft stores the home of each installed JRE/JDK.
    for key in [
        r"HKLM\SOFTWARE\JavaSoft\Java Runtime Environment",
        r"HKLM\SOFTWARE\JavaSoft\JDK",
        r"HKLM\SOFTWARE\JavaSoft\Java Development Kit",
    ] {
        if let Ok(output) = std::process::Command::new("reg")
            .args(["query", key, "/s"])
            .output()
        {
            let text = String::from_utf8_lossy(&output.stdout);
            for line in text.lines() {
                if let Some((name, value)) = line.split_once("REG_SZ")
                    && name.trim().eq_ignore_ascii_case("JavaHome")
                {
                    candidates.push((PathBuf::from(value.trim()), JavaSource::WindowsRegistry));
                }
            }
        }
    }

    let mut roots = Vec::new();
    for variable in ["ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"] {
        if let Some(path) = env::var_os(variable) {
            roots.push(PathBuf::from(path));
        }
    }
    for root in &roots {
        for vendor in [
            "Java",
            "Eclipse Adoptium",
            "Zulu",
            "Microsoft",
            "BellSoft",
            "Amazon Corretto",
        ] {
            let directory = root.join(vendor);
            if let Ok(entries) = std::fs::read_dir(&directory) {
                for entry in entries.flatten() {
                    candidates.push((entry.path(), JavaSource::WindowsProgramFiles));
                }
            }
        }
    }

    if let Some(local) = env::var_os("LOCALAPPDATA") {
        let pattern = PathBuf::from(local)
            .join("Packages")
            .join("Microsoft.4297127D64EC6_*")
            .join("LocalCache/Local/runtime/*/*/*");
        if let Some(pattern) = pattern.to_str()
            && let Ok(paths) = glob::glob(pattern)
        {
            for path in paths.flatten() {
                candidates.push((path, JavaSource::MojangLauncher));
            }
        }
    }
    candidates
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fake_runtime(root: &Path, name: &str, release: Option<&str>) -> PathBuf {
        let home = root.join(name);
        std::fs::create_dir_all(home.join("bin")).expect("bin");
        let executable = if cfg!(target_os = "windows") {
            "java.exe"
        } else {
            "java"
        };
        std::fs::write(home.join("bin").join(executable), b"binary").expect("java");
        if let Some(release) = release {
            std::fs::write(home.join("release"), release).expect("release");
        }
        home
    }

    #[test]
    fn extra_roots_are_scanned_and_deduplicated() {
        let directory = tempfile::tempdir().expect("tempdir");
        let home = fake_runtime(
            directory.path(),
            "jdk-21.0.9",
            Some("JAVA_VERSION=\"21.0.9\"\nOS_ARCH=\"aarch64\"\nIMPLEMENTOR=\"Test\"\n"),
        );

        let scanner = JavaScanner {
            include_environment: false,
            include_platform: false,
            extra_roots: vec![
                directory.path().to_path_buf(),
                directory.path().to_path_buf(),
            ],
            max_depth: 2,
        };
        let runtimes = scanner.scan();
        assert_eq!(runtimes.len(), 1);
        assert_eq!(runtimes[0].home, home);
        assert_eq!(runtimes[0].major(), Some(21));
        assert_eq!(runtimes[0].vendor.as_deref(), Some("Test"));
    }

    #[test]
    fn mac_bundle_layout_resolves_to_contents_home() {
        let directory = tempfile::tempdir().expect("tempdir");
        let home = directory.path().join("jdk-21.jdk/Contents/Home");
        std::fs::create_dir_all(home.join("bin")).expect("bin");
        let executable = if cfg!(target_os = "windows") {
            "java.exe"
        } else {
            "java"
        };
        std::fs::write(home.join("bin").join(executable), b"binary").expect("java");
        assert_eq!(
            normalize_home(&directory.path().join("jdk-21.jdk")),
            Some(home)
        );
    }

    #[test]
    fn executable_path_resolves_to_home() {
        let directory = tempfile::tempdir().expect("tempdir");
        let home = fake_runtime(directory.path(), "jdk", None);
        let executable = if cfg!(target_os = "windows") {
            "java.exe"
        } else {
            "java"
        };
        assert_eq!(
            normalize_home(&home.join("bin").join(executable)),
            Some(home)
        );
    }

    #[test]
    fn a_file_that_is_not_java_is_not_a_runtime() {
        let directory = tempfile::tempdir().expect("tempdir");
        assert!(normalize_home(directory.path()).is_none());
    }
}
