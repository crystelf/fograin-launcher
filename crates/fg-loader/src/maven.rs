//! Minimal Maven coordinate parser shared by loader installers.
//!
//! `fg-game` owns the launch-time copy so both crates stay independent; the
//! grammar is the same as `libraries[].name` in `version.json`.

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

/// Default library host used when a loader library has no URL.
pub const DEFAULT_LIBRARY_URL: &str = "https://libraries.minecraft.net";

/// `group:artifact:version[:classifier][@extension]`.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Coordinate {
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

impl Coordinate {
    /// Parses a Maven coordinate.
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
                "error.loader.library_name",
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
                "error.loader.library_name",
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
                    "error.loader.library_name",
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

    /// URL under `base` (trailing slash optional).
    #[must_use]
    pub fn url(&self, base: &str) -> String {
        format!("{}/{}", base.trim_end_matches('/'), self.path())
    }

    /// Human-readable `group:artifact:version`.
    #[must_use]
    pub fn label(&self) -> String {
        format!("{}:{}:{}", self.group, self.artifact, self.version)
    }
}

fn is_safe_component(value: &str) -> bool {
    !value.is_empty()
        && value != "."
        && value != ".."
        && value
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || "._+-".contains(character))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_classifiers_and_extensions() {
        let coordinate =
            Coordinate::parse("net.minecraftforge:installertools:1.4.1").expect("plain");
        assert_eq!(
            coordinate.path(),
            "net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar"
        );
        let classifier = Coordinate::parse("net.minecraftforge:ForgeAutoRenamingTool:0.1.22:all")
            .expect("classifier");
        assert_eq!(
            classifier.path(),
            "net/minecraftforge/ForgeAutoRenamingTool/0.1.22/ForgeAutoRenamingTool-0.1.22-all.jar"
        );
        let zip = Coordinate::parse("de.oceanlabs.mcp:mcp_config:1.20.1@zip").expect("extension");
        assert!(zip.path().ends_with(".zip"));
    }

    #[test]
    fn rejects_traversal() {
        assert!(Coordinate::parse("a:b").is_err());
        assert!(Coordinate::parse("a:b:c:../../etc").is_err());
    }
}
