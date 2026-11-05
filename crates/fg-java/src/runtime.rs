//! Java runtime model, binaries and `release` parsing (§8.1).

use std::{
    fs,
    path::{Path, PathBuf},
};

use serde::{Deserialize, Serialize};

/// CPU architecture of a Java runtime.
///
/// The values are normalized because every vendor spells them differently:
/// `aarch64`/`arm64`, `amd64`/`x86_64`, `i386`/`x86`.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum JavaArch {
    X86,
    X86_64,
    Arm,
    Arm64,
    #[default]
    Unknown,
}

impl JavaArch {
    /// Parses the many spellings found in `release` files and `java.version`.
    #[must_use]
    pub fn parse(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "x86" | "i386" | "i486" | "i586" | "i686" | "x86_32" | "32" => Self::X86,
            "x86_64" | "amd64" | "x64" | "x86-64" | "64" => Self::X86_64,
            "arm" | "arm32" | "armv7" | "armv7l" | "aarch32" => Self::Arm,
            "arm64" | "aarch64" | "armv8" | "armv8l" => Self::Arm64,
            _ => Self::Unknown,
        }
    }

    /// Architecture of the host the launcher is running on.
    #[must_use]
    pub const fn host() -> Self {
        #[cfg(target_arch = "x86")]
        {
            Self::X86
        }
        #[cfg(target_arch = "x86_64")]
        {
            Self::X86_64
        }
        #[cfg(target_arch = "arm")]
        {
            Self::Arm
        }
        #[cfg(target_arch = "aarch64")]
        {
            Self::Arm64
        }
        #[cfg(not(any(
            target_arch = "x86",
            target_arch = "x86_64",
            target_arch = "arm",
            target_arch = "aarch64"
        )))]
        {
            Self::Unknown
        }
    }

    /// Value used in `rules[].os.arch` (`x86`, `x86_64`, `arm64`).
    #[must_use]
    pub const fn as_rule_str(self) -> &'static str {
        match self {
            Self::X86 => "x86",
            Self::X86_64 => "x86_64",
            Self::Arm => "arm",
            Self::Arm64 => "arm64",
            Self::Unknown => "unknown",
        }
    }

    /// Whether the two architectures can execute the same binaries.
    #[must_use]
    pub fn is_compatible(self, other: Self) -> bool {
        self == other || matches!(self, Self::Unknown) || matches!(other, Self::Unknown)
    }
}

impl std::fmt::Display for JavaArch {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_rule_str())
    }
}

/// A parsed Java version, kept as major plus the original string.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct JavaVersion {
    pub major: u32,
    pub full: String,
}

impl JavaVersion {
    /// Parses the version formats vendors use:
    ///
    /// - `1.8.0_402` → 8
    /// - `21.0.9` → 21
    /// - `17.0.12+7` → 17
    /// - `21-ea` → 21
    #[must_use]
    pub fn parse(value: &str) -> Option<Self> {
        let full = value.trim().to_string();
        let cleaned = full
            .split(['+', '-'])
            .next()
            .unwrap_or(&full)
            .trim()
            .to_string();
        let mut parts = cleaned.split('.');
        let first: u32 = parts.next()?.parse().ok()?;
        let major = if first == 1 {
            // 1.8.0_402 has an underscore after the patch component.
            let second = parts.next()?;
            second
                .split(['_', 'u'])
                .next()
                .unwrap_or(second)
                .parse()
                .ok()?
        } else {
            first
        };
        Some(Self { major, full })
    }
}

/// Where a runtime was discovered; useful for diagnostics and deduplication.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum JavaSource {
    JavaHomeEnv,
    PathEnv,
    MacJavaHome,
    MacJavaVirtualMachines,
    Homebrew,
    LinuxJvm,
    Sdkman,
    WindowsRegistry,
    WindowsProgramFiles,
    MojangLauncher,
    LauncherManaged,
    ExtraRoot,
}

impl JavaSource {
    /// Human-readable source label for `fg-cli java`.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::JavaHomeEnv => "JAVA_HOME",
            Self::PathEnv => "PATH",
            Self::MacJavaHome => "java_home",
            Self::MacJavaVirtualMachines => "/Library/Java",
            Self::Homebrew => "Homebrew",
            Self::LinuxJvm => "/usr/lib/jvm",
            Self::Sdkman => "SDKMAN",
            Self::WindowsRegistry => "registry",
            Self::WindowsProgramFiles => "Program Files",
            Self::MojangLauncher => "Mojang runtime",
            Self::LauncherManaged => "Fograin runtimes",
            Self::ExtraRoot => "extra root",
        }
    }
}

/// One installed Java runtime.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct JavaRuntime {
    /// Runtime home (the directory that contains `bin/`).
    pub home: PathBuf,
    /// Path to `bin/java` or `bin/java.exe`.
    pub executable: PathBuf,
    /// Version from `release` or a probed `java -version`.
    pub version: Option<JavaVersion>,
    /// Architecture from `release` or a binary header.
    pub arch: JavaArch,
    /// Vendor from `release`, for the settings page.
    pub vendor: Option<String>,
    /// How it was discovered.
    pub source: JavaSource,
}

impl JavaRuntime {
    /// Builds a runtime for `home` if it contains a Java executable.
    #[must_use]
    pub fn from_home(home: impl Into<PathBuf>, source: JavaSource) -> Option<Self> {
        let home = home.into();
        let executable = executable_in(&home)?;
        let release = Release::read(&home);
        let version = release
            .as_ref()
            .and_then(|release| release.java_version.as_deref())
            .and_then(JavaVersion::parse);
        let arch = release
            .as_ref()
            .and_then(|release| release.os_arch.as_deref())
            .map_or_else(
                || detect_binary_arch(&executable).unwrap_or(JavaArch::Unknown),
                JavaArch::parse,
            );
        let vendor = release.and_then(|release| release.implementor);
        Some(Self {
            home,
            executable,
            version,
            arch,
            vendor,
            source,
        })
    }

    /// Major version, when a `release` file or probe provided one.
    #[must_use]
    pub fn major(&self) -> Option<u32> {
        self.version.as_ref().map(|version| version.major)
    }

    /// Fills the missing version/metadata by running `java -XshowSettings`.
    ///
    /// # Errors
    ///
    /// Returns the probe error when the process cannot be started; callers that
    /// only need best-effort discovery should ignore it.
    pub async fn probe(&mut self, timeout: std::time::Duration) -> fg_core::Result<()> {
        let info = crate::probe::probe(&self.executable, timeout).await?;
        if self.version.is_none() {
            self.version = info.version;
        }
        if self.arch == JavaArch::Unknown {
            self.arch = info.arch;
        }
        if self.vendor.is_none() {
            self.vendor = info.vendor;
        }
        Ok(())
    }
}

/// Returns `<home>/bin/java[.exe]` when it exists.
#[must_use]
pub fn executable_in(home: &Path) -> Option<PathBuf> {
    let name = if cfg!(target_os = "windows") {
        "java.exe"
    } else {
        "java"
    };
    let executable = home.join("bin").join(name);
    executable.is_file().then_some(executable)
}

/// Parsed `release` file (§8.1).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Release {
    pub java_version: Option<String>,
    pub implementor: Option<String>,
    pub os_arch: Option<String>,
    pub os_name: Option<String>,
    pub java_runtime_version: Option<String>,
}

impl Release {
    /// Reads `<home>/release`, returning `None` when the file is absent.
    #[must_use]
    pub fn read(home: &Path) -> Option<Self> {
        let text = fs::read_to_string(home.join("release")).ok()?;
        Some(Self::parse(&text))
    }

    /// Parses the `KEY="value"` format. Unknown keys are ignored.
    #[must_use]
    pub fn parse(text: &str) -> Self {
        let mut release = Self::default();
        for line in text.lines() {
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            let value = value.trim().trim_matches('"').to_string();
            match key.trim() {
                "JAVA_VERSION" => release.java_version = Some(value),
                "IMPLEMENTOR" => release.implementor = Some(value),
                "OS_ARCH" => release.os_arch = Some(value),
                "OS_NAME" => release.os_name = Some(value),
                "JAVA_RUNTIME_VERSION" => release.java_runtime_version = Some(value),
                _ => {}
            }
        }
        release
    }
}

/// Reads the architecture from a PE, Mach-O or ELF header (§8.1).
///
/// Returns `None` for scripts and unrecognized files. This never runs the
/// binary, which matters for Rosetta: a Mach-O header shows the real target
/// architecture.
#[must_use]
pub fn detect_binary_arch(path: &Path) -> Option<JavaArch> {
    use std::io::Read as _;
    let mut file = fs::File::open(path).ok()?;
    let mut bytes = vec![0_u8; 8192];
    let read = file.read(&mut bytes).ok()?;
    bytes.truncate(read);
    detect_arch_bytes(&bytes)
}

/// Header parser split out so unit tests can feed synthetic binaries.
#[must_use]
pub fn detect_arch_bytes(bytes: &[u8]) -> Option<JavaArch> {
    if bytes.starts_with(b"MZ") {
        return pe_arch(bytes);
    }
    if bytes.starts_with(&[0x7f, b'E', b'L', b'F']) {
        return elf_arch(bytes);
    }
    let magic = array4(bytes, 0)?;
    match magic {
        [0xfe, 0xed, 0xfa, 0xcf] => macho_arch(bytes, false),
        [0xcf, 0xfa, 0xed, 0xfe] => macho_arch(bytes, true),
        [0xfe, 0xed, 0xfa, 0xce] => macho_arch(bytes, false),
        [0xce, 0xfa, 0xed, 0xfe] => macho_arch(bytes, true),
        [0xca, 0xfe, 0xba, 0xbe] => fat_arch(bytes, false),
        [0xbe, 0xba, 0xfe, 0xca] => fat_arch(bytes, true),
        _ => None,
    }
}

fn array4(bytes: &[u8], offset: usize) -> Option<[u8; 4]> {
    bytes.get(offset..offset + 4)?.try_into().ok()
}

fn u32_at(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(array4(bytes, offset)?))
}

fn pe_arch(bytes: &[u8]) -> Option<JavaArch> {
    let pe_offset = u32_at(bytes, 0x3c)? as usize;
    if bytes.get(pe_offset..pe_offset + 4)? != b"PE\0\0" {
        return None;
    }
    match u32::from_le_bytes(array4(bytes, pe_offset + 4)?) {
        0x014c => Some(JavaArch::X86),
        0x8664 => Some(JavaArch::X86_64),
        0xaa64 => Some(JavaArch::Arm64),
        0x01c4 => Some(JavaArch::Arm),
        _ => None,
    }
}

fn elf_arch(bytes: &[u8]) -> Option<JavaArch> {
    if bytes.len() < 20 {
        return None;
    }
    let little_endian = bytes[5] == 1;
    let machine = if little_endian {
        u16::from_le_bytes(bytes[18..20].try_into().ok()?)
    } else {
        u16::from_be_bytes(bytes[18..20].try_into().ok()?)
    };
    match machine {
        3 => Some(JavaArch::X86),
        62 => Some(JavaArch::X86_64),
        40 => Some(JavaArch::Arm),
        183 => Some(JavaArch::Arm64),
        _ => None,
    }
}

fn macho_arch(bytes: &[u8], little_endian: bool) -> Option<JavaArch> {
    let raw = bytes.get(4..8)?;
    let cpu = if little_endian {
        u32::from_le_bytes(raw.try_into().ok()?)
    } else {
        u32::from_be_bytes(raw.try_into().ok()?)
    };
    match cpu {
        7 => Some(JavaArch::X86),
        0x0100_0007 => Some(JavaArch::X86_64),
        12 => Some(JavaArch::Arm),
        0x0100_000c => Some(JavaArch::Arm64),
        _ => None,
    }
}

fn fat_arch(bytes: &[u8], little_endian: bool) -> Option<JavaArch> {
    let count = if little_endian {
        u32::from_le_bytes(array4(bytes, 4)?)
    } else {
        u32::from_be_bytes(array4(bytes, 4)?)
    };
    let mut fallback = None;
    for index in 0..count.min(32) {
        let offset = 8 + index as usize * 20;
        let raw = array4(bytes, offset)?;
        let cpu = if little_endian {
            u32::from_le_bytes(raw)
        } else {
            u32::from_be_bytes(raw)
        };
        let arch = match cpu {
            7 => JavaArch::X86,
            0x0100_0007 => JavaArch::X86_64,
            12 => JavaArch::Arm,
            0x0100_000c => JavaArch::Arm64,
            _ => continue,
        };
        if arch == JavaArch::Arm64 {
            return Some(arch);
        }
        fallback = Some(arch);
    }
    fallback
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_release_versions() {
        assert_eq!(JavaVersion::parse("1.8.0_402").expect("v8").major, 8);
        assert_eq!(JavaVersion::parse("21.0.9").expect("v21").major, 21);
        assert_eq!(JavaVersion::parse("17.0.12+7").expect("v17").major, 17);
        assert_eq!(JavaVersion::parse("21-ea").expect("ea").major, 21);
        assert!(JavaVersion::parse("not a version").is_none());
    }

    #[test]
    fn parses_release_file() {
        let release = Release::parse(
            "IMPLEMENTOR=\"Oracle Corporation\"\nJAVA_VERSION=\"21.0.9\"\nOS_ARCH=\"aarch64\"\n",
        );
        assert_eq!(release.implementor.as_deref(), Some("Oracle Corporation"));
        assert_eq!(release.java_version.as_deref(), Some("21.0.9"));
        assert_eq!(
            JavaArch::parse(release.os_arch.as_deref().unwrap()),
            JavaArch::Arm64
        );
    }

    #[test]
    fn detects_pe_architectures() {
        let mut bytes = vec![0_u8; 0x100];
        bytes[0..2].copy_from_slice(b"MZ");
        bytes[0x3c..0x40].copy_from_slice(&0x80_u32.to_le_bytes());
        bytes[0x80..0x84].copy_from_slice(b"PE\0\0");
        bytes[0x84..0x88].copy_from_slice(&0x8664_u32.to_le_bytes());
        assert_eq!(detect_arch_bytes(&bytes), Some(JavaArch::X86_64));
    }

    #[test]
    fn detects_macho_and_fat_architectures() {
        let mut thin = Vec::new();
        thin.extend_from_slice(&[0xcf, 0xfa, 0xed, 0xfe]);
        thin.extend_from_slice(&0x0100_000c_u32.to_le_bytes());
        thin.extend_from_slice(&[0; 16]);
        assert_eq!(detect_arch_bytes(&thin), Some(JavaArch::Arm64));

        let mut fat = Vec::new();
        fat.extend_from_slice(&0xcafe_babe_u32.to_be_bytes());
        fat.extend_from_slice(&1_u32.to_be_bytes());
        fat.extend_from_slice(&0x0100_0007_u32.to_be_bytes());
        fat.extend_from_slice(&[0; 16]);
        assert_eq!(detect_arch_bytes(&fat), Some(JavaArch::X86_64));
    }

    #[test]
    fn detects_elf_architectures() {
        let mut bytes = vec![0_u8; 20];
        bytes[0..4].copy_from_slice(&[0x7f, b'E', b'L', b'F']);
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[18..20].copy_from_slice(&183_u16.to_le_bytes());
        assert_eq!(detect_arch_bytes(&bytes), Some(JavaArch::Arm64));
    }

    #[test]
    fn finds_java_in_a_home() {
        let directory = tempfile::tempdir().expect("tempdir");
        let home = directory.path().join("jdk");
        std::fs::create_dir_all(home.join("bin")).expect("bin");
        let name = if cfg!(target_os = "windows") {
            "java.exe"
        } else {
            "java"
        };
        std::fs::write(home.join("bin").join(name), b"binary").expect("java");
        assert!(executable_in(&home).is_some());
        assert!(executable_in(directory.path()).is_none());
    }
}
