//! Version manifest and `version.json` model, merge and library resolution
//! (§7.1).
//!
//! The parser is intentionally permissive: Mojang adds fields over time and
//! loader profiles reuse the same shape. Unknown fields are preserved in
//! [`VersionJson::extra`] so a merged loader profile does not silently lose
//! data that a later milestone needs.

use std::collections::{BTreeMap, HashSet};

use serde::{Deserialize, Serialize};

use fg_core::{Error, ErrorKind, Result};
use fg_java::JavaArch;

use crate::rules::{Rule, RuleContext, evaluate as rules_allow};
use crate::split_command_line;

/// `version_manifest_v2.json` (§7.1 step 1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionManifest {
    pub latest: LatestVersions,
    #[serde(default)]
    pub versions: Vec<VersionSummary>,
}

impl VersionManifest {
    /// Parses a manifest document.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed JSON.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.manifest.parse",
                format!("version_manifest_v2.json: {error}"),
            )
        })
    }

    /// Finds a version by id.
    #[must_use]
    pub fn find(&self, id: &str) -> Option<&VersionSummary> {
        self.versions.iter().find(|version| version.id == id)
    }

    /// Latest stable release id from `latest.release`.
    #[must_use]
    pub fn latest_release(&self) -> Option<&VersionSummary> {
        self.find(&self.latest.release)
    }

    /// Latest snapshot id from `latest.snapshot`.
    #[must_use]
    pub fn latest_snapshot(&self) -> Option<&VersionSummary> {
        self.find(&self.latest.snapshot)
    }
}

/// `latest` block.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LatestVersions {
    pub release: String,
    pub snapshot: String,
}

/// One entry of `versions`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionSummary {
    pub id: String,
    #[serde(rename = "type")]
    pub version_type: String,
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub time: Option<String>,
    #[serde(default)]
    pub release_time: Option<String>,
    #[serde(default)]
    pub compliance_level: Option<u32>,
}

/// Top-level `version.json`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "type", default)]
    pub version_type: Option<String>,
    #[serde(default)]
    pub time: Option<String>,
    #[serde(default)]
    pub release_time: Option<String>,
    #[serde(default)]
    pub main_class: Option<String>,
    #[serde(default)]
    pub minimum_launcher_version: Option<u32>,
    #[serde(default)]
    pub compliance_level: Option<u32>,
    #[serde(default)]
    pub inherits_from: Option<String>,
    #[serde(default)]
    pub asset_index: Option<AssetIndexRef>,
    #[serde(default)]
    pub assets: Option<String>,
    #[serde(default)]
    pub downloads: Option<VersionDownloads>,
    #[serde(default)]
    pub java_version: Option<JavaVersion>,
    #[serde(default)]
    pub logging: Option<LoggingConfig>,
    #[serde(default)]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    #[serde(default)]
    pub libraries: Vec<Library>,
    /// Fields this launcher does not model yet, preserved across merge.
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

impl VersionJson {
    /// Parses a `version.json` document.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed JSON.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.version.parse",
                format!("version json: {error}"),
            )
        })
    }
}

/// `assetIndex`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AssetIndexRef {
    pub id: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub total_size: Option<u64>,
    pub url: String,
}

/// Asset index document, kept small because only install and legacy layouts
/// need the object table.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssetIndex {
    #[serde(default)]
    pub objects: BTreeMap<String, AssetObject>,
    /// 1.6/1.7 layout: materialize by relative name instead of hashes.
    #[serde(default, rename = "virtual")]
    pub virtual_assets: Option<bool>,
    /// pre-1.6 layout: copy into `<game dir>/resources/<name>`.
    #[serde(default)]
    pub map_to_resources: Option<bool>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// One asset object.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

impl AssetIndex {
    /// Parses an asset index document.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed JSON.
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.asset_index.parse",
                format!("asset index: {error}"),
            )
        })
    }

    /// Whether this index needs a legacy file-name layout.
    #[must_use]
    pub fn is_legacy(&self) -> bool {
        self.virtual_assets.unwrap_or(false) || self.map_to_resources.unwrap_or(false)
    }
}

/// `downloads`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VersionDownloads {
    #[serde(default)]
    pub client: Option<Artifact>,
    #[serde(default)]
    pub server: Option<Artifact>,
    #[serde(default)]
    pub client_mappings: Option<Artifact>,
    #[serde(default)]
    pub server_mappings: Option<Artifact>,
    #[serde(default)]
    pub windows_server: Option<Artifact>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// A downloadable artifact in `downloads`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub url: Option<String>,
}

/// `javaVersion`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JavaVersion {
    #[serde(default)]
    pub component: Option<String>,
    #[serde(default)]
    pub major_version: Option<u32>,
}

/// `logging`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LoggingConfig {
    #[serde(default)]
    pub client: Option<LoggingClient>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// `logging.client`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoggingClient {
    pub argument: String,
    pub file: LoggingFile,
    #[serde(rename = "type", default)]
    pub config_type: Option<String>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// `logging.client.file`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LoggingFile {
    pub id: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    pub url: String,
}

/// `arguments`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
    /// New in 2026 snapshots; default JVM flags for the game.
    #[serde(rename = "default-user-jvm", default)]
    pub default_user_jvm: Vec<Argument>,
}

impl Arguments {
    /// Game arguments for `context`.
    #[must_use]
    pub fn game_values(&self, context: &RuleContext) -> Vec<String> {
        values(&self.game, context)
    }

    /// JVM arguments for `context`.
    #[must_use]
    pub fn jvm_values(&self, context: &RuleContext) -> Vec<String> {
        values(&self.jvm, context)
    }

    /// Default user JVM arguments for `context`.
    #[must_use]
    pub fn default_user_jvm_values(&self, context: &RuleContext) -> Vec<String> {
        values(&self.default_user_jvm, context)
    }
}

fn values(arguments: &[Argument], context: &RuleContext) -> Vec<String> {
    arguments
        .iter()
        .flat_map(|argument| argument.values(context))
        .collect()
}

/// A string or a conditional argument.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional(ConditionalArgument),
}

impl Argument {
    /// Expands this argument, returning nothing when its rules do not match.
    #[must_use]
    pub fn values(&self, context: &RuleContext) -> Vec<String> {
        match self {
            Self::Plain(value) => vec![value.clone()],
            Self::Conditional(argument) => {
                if rules_allow(&argument.rules, context) {
                    argument.value.values()
                } else {
                    Vec::new()
                }
            }
        }
    }
}

/// `{ "rules": [...], "value": ... }`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConditionalArgument {
    #[serde(default)]
    pub rules: Vec<Rule>,
    pub value: ArgumentValue,
}

/// A single argument or a group of arguments.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum ArgumentValue {
    One(String),
    Many(Vec<String>),
}

impl ArgumentValue {
    #[must_use]
    pub fn values(&self) -> Vec<String> {
        match self {
            Self::One(value) => vec![value.clone()],
            Self::Many(values) => values.clone(),
        }
    }
}

/// `libraries[]`.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    #[serde(default)]
    pub natives: Option<BTreeMap<String, String>>,
    #[serde(default)]
    pub extract: Option<ExtractRule>,
    #[serde(default)]
    pub rules: Vec<Rule>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub checksums: Option<Vec<String>>,
    #[serde(default)]
    pub clientreq: Option<bool>,
    #[serde(default)]
    pub serverreq: Option<bool>,
    #[serde(rename = "_comment", default)]
    pub comment: Option<String>,
    #[serde(flatten)]
    pub extra: BTreeMap<String, serde_json::Value>,
}

/// `libraries[].downloads`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LibraryDownloads {
    #[serde(default)]
    pub artifact: Option<Artifact>,
    #[serde(default)]
    pub classifiers: Option<BTreeMap<String, Artifact>>,
}

/// `libraries[].extract`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ExtractRule {
    #[serde(default)]
    pub exclude: Vec<String>,
}

impl Default for ExtractRule {
    fn default() -> Self {
        Self {
            exclude: vec!["META-INF/".to_string()],
        }
    }
}

/// Maven coordinate parsed from `libraries[].name`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct MavenCoordinate {
    pub group: String,
    pub artifact: String,
    pub version: String,
    #[serde(default)]
    pub classifier: Option<String>,
    #[serde(default = "default_extension")]
    pub extension: String,
}

fn default_extension() -> String {
    "jar".to_string()
}

impl MavenCoordinate {
    /// Parses `group:artifact:version[:classifier][@extension]`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed or path-traversing names.
    pub fn parse(name: &str) -> Result<Self> {
        let (name, extension) = match name.split_once('@') {
            Some((name, extension)) => (name, extension.to_string()),
            None => (name, default_extension()),
        };
        let mut parts = name.split(':');
        let group = parts.next().unwrap_or_default();
        let artifact = parts.next().unwrap_or_default();
        let version = parts.next().unwrap_or_default();
        if group.is_empty() || artifact.is_empty() || version.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.version.library_name",
                format!("invalid Maven coordinate: {name}"),
            ));
        }
        let classifier = parts
            .next()
            .map(ToString::to_string)
            .filter(|value| !value.is_empty());
        if parts.next().is_some() {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.version.library_name",
                format!("too many segments: {name}"),
            ));
        }
        for component in [group, artifact, version]
            .into_iter()
            .chain(classifier.as_deref())
            .chain(Some(extension.as_str()))
        {
            if !is_safe_component(component) {
                return Err(Error::with_detail(
                    ErrorKind::Parse,
                    "error.version.library_name",
                    format!("unsafe path component {component:?} in {name}"),
                ));
            }
        }
        Ok(Self {
            group: group.to_string(),
            artifact: artifact.to_string(),
            version: version.to_string(),
            classifier,
            extension,
        })
    }

    /// Maven-layout path relative to the libraries root.
    #[must_use]
    pub fn path(&self) -> String {
        let mut file = format!("{}-{}", self.artifact, self.version);
        if let Some(classifier) = &self.classifier {
            file.push('-');
            file.push_str(classifier);
        }
        file.push('.');
        file.push_str(&self.extension);
        format!(
            "{}/{}/{}/{}",
            self.group.replace('.', "/"),
            self.artifact,
            self.version,
            file
        )
    }

    /// Key used for classpath de-duplication (ignores the classifier).
    #[must_use]
    pub fn key(&self) -> String {
        format!("{}:{}", self.group, self.artifact)
    }

    /// Key that also includes the classifier, so a modern natives jar is not
    /// collapsed into its base library.
    #[must_use]
    pub fn classpath_key(&self) -> String {
        match &self.classifier {
            Some(classifier) => format!("{}:{}:{}", self.group, self.artifact, classifier),
            None => self.key(),
        }
    }

    /// `group:artifact`, used in diagnostics and tests.
    #[must_use]
    pub fn name(&self) -> String {
        format!("{}:{}", self.group, self.artifact)
    }

    /// Coordinate with `classifier`, used for legacy natives.
    #[must_use]
    pub fn with_classifier(&self, classifier: impl Into<String>) -> Self {
        Self {
            classifier: Some(classifier.into()),
            ..self.clone()
        }
    }

    /// Default download URL under the libraries host.
    #[must_use]
    pub fn url(&self, base: Option<&str>) -> String {
        let base = base
            .filter(|value| !value.is_empty())
            .unwrap_or(DEFAULT_LIBRARIES_URL);
        format!("{}/{}", base.trim_end_matches('/'), self.path())
    }
}

/// Mojang's libraries host; mirrors replace this prefix (§6.1).
pub const DEFAULT_LIBRARIES_URL: &str = "https://libraries.minecraft.net";

fn is_safe_component(value: &str) -> bool {
    !value.is_empty()
        && value != "."
        && value != ".."
        && value
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || "._+-".contains(character))
}

/// A library artifact with a concrete download URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedArtifact {
    /// Maven path relative to the libraries root.
    pub path: String,
    pub url: String,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

/// What a resolved library contributes at launch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LibraryKind {
    /// Goes on the classpath.
    Classpath,
    /// Legacy nested natives: extracted into the natives directory.
    Native {
        classifier: String,
        extract: ExtractRule,
    },
}

/// A library after OS/feature rules and natives classification.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedLibrary {
    pub coordinate: MavenCoordinate,
    pub artifact: ResolvedArtifact,
    pub kind: LibraryKind,
}

impl ResolvedLibrary {
    /// Whether this library is a modern natives jar that lives on the classpath.
    #[must_use]
    pub fn is_native_jar(&self) -> bool {
        self.coordinate
            .classifier
            .as_deref()
            .is_some_and(|classifier| classifier.starts_with("natives-"))
    }
}

/// Merged version plus helpers; no paths or filesystem access.
#[derive(Debug, Clone, PartialEq)]
pub struct ResolvedVersion {
    pub id: String,
    /// Root version id (`1.21.4` even when a loader profile is used).
    pub base_id: String,
    pub parent_id: Option<String>,
    pub java_major: u32,
    pub raw: VersionJson,
}

impl ResolvedVersion {
    /// Main class, required by every launchable version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] when the merged JSON has no `mainClass`.
    pub fn main_class(&self) -> Result<&str> {
        self.raw.main_class.as_deref().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.version.no_main_class",
                format!("version {}", self.id),
            )
        })
    }

    /// Client jar download metadata.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] when the version has no `client` download.
    pub fn client_download(&self) -> Result<&Artifact> {
        self.raw
            .downloads
            .as_ref()
            .and_then(|downloads| downloads.client.as_ref())
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::Parse,
                    "error.version.no_client",
                    format!("version {} has no downloads.client", self.id),
                )
            })
    }

    /// Asset index metadata (all launchable versions have one).
    #[must_use]
    pub fn asset_index(&self) -> Option<&AssetIndexRef> {
        self.raw.asset_index.as_ref()
    }

    /// Old `minecraftArguments`, split on whitespace.
    #[must_use]
    pub fn legacy_game_arguments(&self) -> Option<Vec<String>> {
        self.raw
            .minecraft_arguments
            .as_deref()
            .map(split_command_line)
    }

    /// New `arguments.game` for `context`.
    #[must_use]
    pub fn game_arguments(&self, context: &RuleContext) -> Vec<String> {
        self.raw
            .arguments
            .as_ref()
            .map(|arguments| arguments.game_values(context))
            .unwrap_or_default()
    }

    /// New `arguments.jvm` for `context`.
    #[must_use]
    pub fn jvm_arguments(&self, context: &RuleContext) -> Vec<String> {
        self.raw
            .arguments
            .as_ref()
            .map(|arguments| arguments.jvm_values(context))
            .unwrap_or_default()
    }

    /// `arguments.default-user-jvm` for `context` (2026 snapshots).
    #[must_use]
    pub fn default_user_jvm_arguments(&self, context: &RuleContext) -> Vec<String> {
        self.raw
            .arguments
            .as_ref()
            .map(|arguments| arguments.default_user_jvm_values(context))
            .unwrap_or_default()
    }

    /// Resolves the library list for the host described by `context`.
    ///
    /// Handles both layouts:
    ///
    /// - pre-1.19: `natives` map plus `downloads.classifiers`;
    /// - 1.19+: separate `:natives-<os>[-<arch>]` coordinates.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for a malformed library coordinate.
    pub fn libraries(&self, context: &RuleContext) -> Result<Vec<ResolvedLibrary>> {
        let mut resolved = Vec::new();
        let mut modern: Vec<(ModernNative, ResolvedArtifact, MavenCoordinate)> = Vec::new();

        for library in &self.raw.libraries {
            if !rules_allow(&library.rules, context) {
                continue;
            }
            let coordinate = MavenCoordinate::parse(&library.name)?;

            if let Some(natives) = &library.natives {
                // The base artifact (if any) is always on the classpath.
                if let Some(artifact) = library.main_artifact(&coordinate)? {
                    resolved.push(ResolvedLibrary {
                        coordinate: coordinate.clone(),
                        artifact,
                        kind: LibraryKind::Classpath,
                    });
                }
                let Some(template) = natives.get(os_key(&context.os.name)) else {
                    continue;
                };
                let classifier = template.replace("${arch}", arch_bits(context.os.arch));
                if classifier.is_empty() {
                    continue;
                }
                let artifact = library.classifier_artifact(&coordinate, &classifier)?;
                resolved.push(ResolvedLibrary {
                    coordinate: coordinate.with_classifier(classifier.clone()),
                    artifact,
                    kind: LibraryKind::Native {
                        classifier,
                        extract: library.extract.clone().unwrap_or_default(),
                    },
                });
                continue;
            }

            if let Some(shape) = ModernNative::parse(&coordinate, &context.os.name) {
                if let Some(artifact) = library.main_artifact(&coordinate)? {
                    modern.push((shape, artifact, coordinate));
                }
                continue;
            }

            if let Some(artifact) = library.main_artifact(&coordinate)? {
                resolved.push(ResolvedLibrary {
                    coordinate,
                    artifact,
                    kind: LibraryKind::Classpath,
                });
            }
        }

        select_modern_natives(modern, context, &mut resolved);
        Ok(resolved)
    }

    /// Classpath entries in launch order, with duplicate `group:artifact`
    /// coordinates collapsed (loader/child libraries win, because merge keeps
    /// them in front).
    #[must_use]
    pub fn classpath_libraries<'a>(
        &self,
        resolved: &'a [ResolvedLibrary],
    ) -> Vec<&'a ResolvedLibrary> {
        let mut seen = HashSet::new();
        resolved
            .iter()
            .filter(|library| matches!(library.kind, LibraryKind::Classpath))
            .filter(|library| seen.insert(library.coordinate.classpath_key()))
            .collect()
    }
}

fn select_modern_natives(
    modern: Vec<(ModernNative, ResolvedArtifact, MavenCoordinate)>,
    context: &RuleContext,
    resolved: &mut Vec<ResolvedLibrary>,
) {
    if modern.is_empty() {
        return;
    }
    // Drop libraries for another OS and classifiers for another architecture.
    let mut applicable: Vec<(ModernNative, ResolvedArtifact, MavenCoordinate)> = modern
        .into_iter()
        .filter(|(shape, _, _)| shape.arch.is_none() || shape.arch == Some(context.os.arch))
        .collect();

    // A generic `natives-macos` must not be mixed with `natives-macos-arm64`
    // when the latter exists: both would put two copies of the same dylib on
    // the classpath.
    let specific_groups: HashSet<String> = applicable
        .iter()
        .filter(|(shape, _, coordinate)| {
            shape.arch.is_some()
                && shape.arch == Some(context.os.arch)
                && coordinate.classifier.is_some()
        })
        .map(|(_, _, coordinate)| coordinate.key())
        .collect();

    applicable.retain(|(shape, _, coordinate)| {
        if shape.arch.is_none()
            && shape.generic_exact
            && specific_groups.contains(&coordinate.key())
        {
            return false;
        }
        true
    });

    for (_, artifact, coordinate) in applicable {
        resolved.push(ResolvedLibrary {
            coordinate,
            artifact,
            kind: LibraryKind::Classpath,
        });
    }
}

/// Parsed `natives-<os>[-<arch>|-patch]` classifier of a 1.19+ library.
#[derive(Debug)]
struct ModernNative {
    arch: Option<JavaArch>,
    /// Classifier is exactly `natives-<os>` (not e.g. `natives-macos-patch`).
    generic_exact: bool,
}

impl ModernNative {
    fn parse(coordinate: &MavenCoordinate, os_name: &str) -> Option<Self> {
        let classifier = coordinate.classifier.as_deref()?;
        let rest = classifier.strip_prefix("natives-")?;
        let os = if rest.starts_with("macos") {
            "osx"
        } else if rest.starts_with("windows") {
            "windows"
        } else if rest.starts_with("linux") {
            "linux"
        } else {
            return None;
        };
        if os != os_key(os_name) {
            return None;
        }
        let suffix = rest.split_once('-').map(|(_, suffix)| suffix).unwrap_or("");
        let arch = match suffix {
            "arm64" | "aarch64" => Some(JavaArch::Arm64),
            "x86" => Some(JavaArch::X86),
            "x64" | "x86_64" | "amd64" => Some(JavaArch::X86_64),
            "patch" => None,
            _ => None,
        };
        Some(Self {
            arch,
            generic_exact: suffix.is_empty(),
        })
    }
}

impl Library {
    fn main_artifact(&self, coordinate: &MavenCoordinate) -> Result<Option<ResolvedArtifact>> {
        if let Some(downloads) = &self.downloads {
            if let Some(artifact) = &downloads.artifact {
                return Ok(Some(self.to_resolved(coordinate, artifact, None)));
            }
            // A `downloads` block with only classifiers means "natives only".
            if downloads.classifiers.is_some() {
                return Ok(None);
            }
        }
        Ok(Some(self.to_resolved(
            coordinate,
            &Artifact::default(),
            None,
        )))
    }

    fn classifier_artifact(
        &self,
        coordinate: &MavenCoordinate,
        classifier: &str,
    ) -> Result<ResolvedArtifact> {
        let artifact = self
            .downloads
            .as_ref()
            .and_then(|downloads| downloads.classifiers.as_ref())
            .and_then(|classifiers| classifiers.get(classifier))
            .cloned()
            .unwrap_or_default();
        Ok(self.to_resolved(coordinate, &artifact, Some(classifier)))
    }

    fn to_resolved(
        &self,
        coordinate: &MavenCoordinate,
        artifact: &Artifact,
        classifier: Option<&str>,
    ) -> ResolvedArtifact {
        let coordinate = match classifier {
            Some(classifier) => coordinate.with_classifier(classifier),
            None => coordinate.clone(),
        };
        let path = artifact.path.clone().unwrap_or_else(|| coordinate.path());
        let url = artifact
            .url
            .clone()
            .filter(|url| !url.is_empty())
            .unwrap_or_else(|| coordinate.url(self.url.as_deref()));
        ResolvedArtifact {
            path,
            url,
            sha1: artifact.sha1.clone(),
            size: artifact.size,
        }
    }
}

fn os_key(name: &str) -> &str {
    match name {
        "macos" => "osx",
        other => other,
    }
}

fn arch_bits(arch: JavaArch) -> &'static str {
    match arch {
        JavaArch::X86 | JavaArch::Arm | JavaArch::Unknown => "32",
        JavaArch::X86_64 | JavaArch::Arm64 => "64",
    }
}

/// Merges a parent version with a child (`inheritsFrom`).
///
/// Scalars: child wins. Libraries: child first. Arguments: parent then child.
#[must_use]
pub fn merge(parent: &VersionJson, child: VersionJson) -> VersionJson {
    let mut merged = VersionJson {
        id: child.id.clone(),
        version_type: child.version_type.or(parent.version_type.clone()),
        time: child.time.or(parent.time.clone()),
        release_time: child.release_time.or(parent.release_time.clone()),
        main_class: child.main_class.or(parent.main_class.clone()),
        minimum_launcher_version: child
            .minimum_launcher_version
            .or(parent.minimum_launcher_version),
        compliance_level: child.compliance_level.or(parent.compliance_level),
        inherits_from: None,
        asset_index: child.asset_index.or(parent.asset_index.clone()),
        assets: child.assets.or(parent.assets.clone()),
        downloads: merge_downloads(parent.downloads.as_ref(), child.downloads.as_ref()),
        java_version: child.java_version.or(parent.java_version.clone()),
        logging: child.logging.or(parent.logging.clone()),
        minecraft_arguments: child
            .minecraft_arguments
            .or(parent.minecraft_arguments.clone()),
        arguments: merge_arguments(parent.arguments.as_ref(), child.arguments.as_ref()),
        libraries: Vec::new(),
        extra: parent.extra.clone(),
    };

    merged.libraries.extend(child.libraries);
    merged.libraries.extend(parent.libraries.iter().cloned());
    for (key, value) in child.extra {
        merged.extra.insert(key, value);
    }
    merged
}

fn merge_downloads(
    parent: Option<&VersionDownloads>,
    child: Option<&VersionDownloads>,
) -> Option<VersionDownloads> {
    match (parent, child) {
        (None, None) => None,
        (Some(parent), None) => Some(parent.clone()),
        (None, Some(child)) => Some(child.clone()),
        (Some(parent), Some(child)) => Some(VersionDownloads {
            client: child.client.clone().or(parent.client.clone()),
            server: child.server.clone().or(parent.server.clone()),
            client_mappings: child
                .client_mappings
                .clone()
                .or(parent.client_mappings.clone()),
            server_mappings: child
                .server_mappings
                .clone()
                .or(parent.server_mappings.clone()),
            windows_server: child
                .windows_server
                .clone()
                .or(parent.windows_server.clone()),
            extra: parent.extra.clone(),
        }),
    }
}

fn merge_arguments(parent: Option<&Arguments>, child: Option<&Arguments>) -> Option<Arguments> {
    match (parent, child) {
        (None, None) => None,
        (Some(parent), None) => Some(parent.clone()),
        (None, Some(child)) => Some(child.clone()),
        (Some(parent), Some(child)) => {
            let mut game = parent.game.clone();
            game.extend(child.game.clone());
            let mut jvm = parent.jvm.clone();
            jvm.extend(child.jvm.clone());
            let mut default_user_jvm = parent.default_user_jvm.clone();
            default_user_jvm.extend(child.default_user_jvm.clone());
            Some(Arguments {
                game,
                jvm,
                default_user_jvm,
            })
        }
    }
}

/// Resolves an `inheritsFrom` chain into one merged version.
///
/// `chain[0]` is the leaf, followed by its parents. `base_id` is the id of the
/// deepest parent, which is the Minecraft version used for Java inference and
/// asset lookup.
///
/// # Errors
///
/// Returns [`ErrorKind::Parse`] when the chain is empty, inconsistent or the
/// leaf references a parent that was not supplied.
pub fn resolve_chain(chain: &[VersionJson]) -> Result<ResolvedVersion> {
    let Some(leaf) = chain.first() else {
        return Err(Error::new(ErrorKind::Parse, "error.version.empty_chain"));
    };

    for (index, version) in chain.iter().enumerate() {
        let expected = version.inherits_from.as_deref();
        let actual = chain.get(index + 1).map(|parent| parent.id.as_str());
        if expected != actual && !(expected.is_none() && index + 1 == chain.len()) {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.version.inherits_mismatch",
                format!(
                    "{} inheritsFrom {:?}, but next in chain is {:?}",
                    version.id, expected, actual
                ),
            ));
        }
    }

    let mut merged = chain.last().cloned().unwrap_or_default();
    for child in chain.iter().rev().skip(1) {
        merged = merge(&merged, child.clone());
    }
    // The leaf id must win even if `merge` was called in an unexpected order.
    merged.id.clone_from(&leaf.id);
    merged.inherits_from = None;

    let base_id = chain
        .last()
        .map_or_else(|| leaf.id.clone(), |version| version.id.clone());
    let declared = merged
        .java_version
        .as_ref()
        .and_then(|java| java.major_version);
    let java_major = fg_java::required_major(&base_id, declared);

    Ok(ResolvedVersion {
        id: leaf.id.clone(),
        base_id,
        parent_id: leaf.inherits_from.clone(),
        java_major,
        raw: merged,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rules::{Features, OsInfo};
    use fg_java::{JavaArch, JavaVersion as JavaRuntimeVersion};

    fn fixture(name: &str) -> VersionJson {
        let text = match name {
            "1.0" => include_str!("../tests/fixtures/versions/1.0.json"),
            "1.7.10" => include_str!("../tests/fixtures/versions/1.7.10.json"),
            "1.8.9" => include_str!("../tests/fixtures/versions/1.8.9.json"),
            "1.12.2" => include_str!("../tests/fixtures/versions/1.12.2.json"),
            "1.16.5" => include_str!("../tests/fixtures/versions/1.16.5.json"),
            "1.20.1" => include_str!("../tests/fixtures/versions/1.20.1.json"),
            "1.21.4" => include_str!("../tests/fixtures/versions/1.21.4.json"),
            "26.4-snapshot-7" => {
                include_str!("../tests/fixtures/versions/26.4-snapshot-7.json")
            }
            "rd-132211" => include_str!("../tests/fixtures/versions/rd-132211.json"),
            other => panic!("unknown fixture {other}"),
        };
        VersionJson::parse(text.as_bytes()).expect("fixture parses")
    }

    fn context(os: &str, arch: JavaArch) -> RuleContext {
        RuleContext::new(
            OsInfo {
                name: os.to_string(),
                arch,
                version: Some("15.1".to_string()),
            },
            Features::new(),
        )
    }

    #[test]
    fn all_acceptance_samples_parse() {
        for name in [
            "1.0",
            "1.7.10",
            "1.8.9",
            "1.12.2",
            "1.16.5",
            "1.20.1",
            "1.21.4",
            "26.4-snapshot-7",
            "rd-132211",
        ] {
            let version = fixture(name);
            assert!(
                !version.main_class.as_deref().unwrap_or("").is_empty(),
                "{name}"
            );
            assert!(!version.libraries.is_empty(), "{name}");
            assert!(version.asset_index.is_some(), "{name}");
            assert!(version.downloads.is_some(), "{name}");
        }
    }

    #[test]
    fn legacy_and_modern_arguments_are_both_present() {
        let legacy = fixture("1.12.2");
        assert!(legacy.minecraft_arguments.is_some());
        assert!(legacy.arguments.is_none());

        let modern = fixture("1.16.5");
        assert!(modern.minecraft_arguments.is_none());
        assert!(modern.arguments.is_some());
    }

    #[test]
    fn snapshot_has_default_user_jvm_and_version_range_rules() {
        let snapshot = fixture("26.4-snapshot-7");
        let arguments = snapshot.arguments.expect("arguments");
        assert!(!arguments.default_user_jvm.is_empty());
        let values = arguments.default_user_jvm_values(&context("osx", JavaArch::Arm64));
        assert!(values.iter().any(|value| value.contains("UseZGC")));
    }

    #[test]
    fn resolves_java_major_from_the_fixture_metadata() {
        for (name, expected) in [
            ("1.0", 8),
            ("1.7.10", 8),
            ("1.16.5", 8),
            ("1.20.1", 17),
            ("1.21.4", 21),
            ("26.4-snapshot-7", 25),
        ] {
            let chain = [fixture(name)];
            let resolved = resolve_chain(&chain).expect("resolve");
            assert_eq!(resolved.java_major, expected, "{name}");
            assert_eq!(resolved.base_id, name);
        }
    }

    #[test]
    fn resolves_modern_natives_for_apple_silicon() {
        let version = resolve_chain(&[fixture("1.21.4")]).expect("resolve");
        let libraries = version
            .libraries(&context("osx", JavaArch::Arm64))
            .expect("libraries");
        let native_names: Vec<&str> = libraries
            .iter()
            .filter(|library| library.is_native_jar())
            .map(|library| library.coordinate.classifier.as_deref().unwrap_or(""))
            .collect();
        assert!(
            native_names.contains(&"natives-macos-arm64"),
            "{native_names:?}"
        );
        let generic_lwjgl = libraries.iter().any(|library| {
            library.coordinate.key() == "org.lwjgl:lwjgl"
                && library.coordinate.classifier.as_deref() == Some("natives-macos")
        });
        assert!(
            !generic_lwjgl,
            "generic macOS natives must not be mixed with arm64: {native_names:?}"
        );
        assert!(libraries.iter().any(|library| {
            library.coordinate.name() == "org.lwjgl:lwjgl"
                && matches!(library.kind, LibraryKind::Classpath)
        }));
    }

    #[test]
    fn resolves_legacy_natives_and_extraction_rules() {
        let version = resolve_chain(&[fixture("1.7.10")]).expect("resolve");
        let libraries = version
            .libraries(&context("osx", JavaArch::X86_64))
            .expect("libraries");
        let native = libraries
            .iter()
            .find(|library| matches!(library.kind, LibraryKind::Native { .. }))
            .expect("legacy native");
        let LibraryKind::Native {
            classifier,
            extract,
        } = &native.kind
        else {
            unreachable!()
        };
        assert_eq!(classifier, "natives-osx");
        assert!(extract.exclude.iter().any(|entry| entry == "META-INF/"));
        assert!(native.artifact.path.contains("natives-osx"));
        // The base jar (not the natives jar) is on the classpath.
        assert!(libraries.iter().any(|library| {
            library.coordinate.artifact == "lwjgl" && matches!(library.kind, LibraryKind::Classpath)
        }));
    }

    #[test]
    fn conditional_arguments_follow_features() {
        let version = resolve_chain(&[fixture("1.16.5")]).expect("resolve");
        let plain = context("osx", JavaArch::Arm64);
        assert!(
            !version
                .game_arguments(&plain)
                .iter()
                .any(|arg| arg == "--width")
        );

        let with_resolution = RuleContext::new(
            plain.os.clone(),
            Features::new().with("has_custom_resolution", true),
        );
        let args = version.game_arguments(&with_resolution);
        assert!(args.iter().any(|arg| arg == "--width"));
        assert!(args.iter().any(|arg| arg == "--height"));
    }

    #[test]
    fn merges_inherits_from_with_child_first_libraries() {
        let parent = VersionJson {
            id: "1.21.4".into(),
            main_class: Some("parent.Main".into()),
            minecraft_arguments: Some("--parent".into()),
            libraries: vec![Library {
                name: "com.example:parent:1".into(),
                ..Library::default()
            }],
            arguments: Some(Arguments {
                game: vec![Argument::Plain("--parent".into())],
                ..Arguments::default()
            }),
            ..VersionJson::default()
        };
        let child = VersionJson {
            id: "fabric-loader".into(),
            inherits_from: Some("1.21.4".into()),
            main_class: Some("child.Main".into()),
            libraries: vec![Library {
                name: "com.example:child:1".into(),
                ..Library::default()
            }],
            arguments: Some(Arguments {
                jvm: vec![Argument::Plain("-Dchild=true".into())],
                ..Arguments::default()
            }),
            ..VersionJson::default()
        };

        let merged = merge(&parent, child);
        assert_eq!(merged.main_class.as_deref(), Some("child.Main"));
        assert_eq!(merged.libraries.len(), 2);
        assert_eq!(merged.libraries[0].name, "com.example:child:1");
        assert_eq!(merged.libraries[1].name, "com.example:parent:1");
        let arguments = merged.arguments.expect("arguments");
        assert_eq!(arguments.game.len(), 1);
        assert_eq!(arguments.jvm.len(), 1);
        assert_eq!(merged.minecraft_arguments.as_deref(), Some("--parent"));
    }

    #[test]
    fn rejects_an_inconsistent_inherits_chain() {
        let leaf = VersionJson {
            id: "child".into(),
            inherits_from: Some("expected".into()),
            ..VersionJson::default()
        };
        let parent = VersionJson {
            id: "actual".into(),
            ..VersionJson::default()
        };
        let error = resolve_chain(&[leaf, parent]).expect_err("mismatch");
        assert_eq!(error.kind, ErrorKind::Parse);
        assert_eq!(error.message_key, "error.version.inherits_mismatch");
    }

    #[test]
    fn parses_and_renders_maven_coordinates() {
        let coordinate =
            MavenCoordinate::parse("org.lwjgl:lwjgl:3.3.3:natives-macos-arm64").expect("parse");
        assert_eq!(
            coordinate.classifier.as_deref(),
            Some("natives-macos-arm64")
        );
        assert_eq!(
            coordinate.path(),
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-macos-arm64.jar"
        );
        let coordinate = MavenCoordinate::parse("group:artifact:1.0@zip").expect("parse");
        assert_eq!(coordinate.extension, "zip");
        assert!(coordinate.path().ends_with(".zip"));
        assert!(MavenCoordinate::parse("a:b").is_err());
        assert!(MavenCoordinate::parse("a:b:c:../../etc").is_err());
    }

    #[test]
    fn asset_index_flags_are_preserved() {
        let index: AssetIndex = serde_json::from_str(
            r#"{"objects":{"a":{"hash":"ab","size":1}},"map_to_resources":true}"#,
        )
        .expect("index");
        assert!(index.map_to_resources.unwrap());
        assert!(index.is_legacy());
        assert_eq!(index.objects["a"].hash, "ab");
    }

    #[test]
    fn classpath_deduplicates_by_group_and_artifact() {
        let version = resolve_chain(&[fixture("1.21.4")]).expect("resolve");
        let libraries = version
            .libraries(&context("osx", JavaArch::Arm64))
            .expect("libraries");
        let classpath = version.classpath_libraries(&libraries);
        let mut keys = HashSet::new();
        for library in &classpath {
            assert!(
                keys.insert(library.coordinate.classpath_key()),
                "duplicate {}",
                library.coordinate.classpath_key()
            );
        }
        assert!(
            classpath.iter().any(|library| library
                .coordinate
                .classifier
                .as_deref()
                .is_some_and(|classifier| classifier == "natives-macos-arm64")),
            "modern natives must stay on the classpath"
        );
    }

    #[test]
    fn java_version_helper_is_used_for_fallback() {
        assert_eq!(
            VersionJson {
                id: "x".into(),
                ..VersionJson::default()
            }
            .java_version,
            None
        );
        let parsed = JavaVersion {
            component: None,
            major_version: Some(8),
        };
        assert_eq!(parsed.major_version, Some(8));
        assert_eq!(
            JavaRuntimeVersion::parse("1.8.0_402")
                .expect("version")
                .major,
            8
        );
    }
}
