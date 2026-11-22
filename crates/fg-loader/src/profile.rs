//! Parsing of loader `version.json` profiles.
//!
//! Fabric/Quilt return a ready-made profile; Forge/NeoForge embed one in the
//! installer. The launcher only needs id, main class and library artifacts, so
//! this module intentionally mirrors the small subset of `fg-game::version`
//! that is relevant while planning.

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::maven::Coordinate;

/// Library list entry from a loader profile.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ProfileLibrary {
    pub name: String,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
    #[serde(default)]
    pub downloads: Option<ProfileDownloads>,
}

/// `libraries[].downloads`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ProfileDownloads {
    #[serde(default)]
    pub artifact: Option<ProfileArtifact>,
}

/// Concrete artifact metadata.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ProfileArtifact {
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

/// A resolved library: coordinate, concrete path and optional download URL.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VersionLibrary {
    pub coordinate: Coordinate,
    pub path: String,
    pub url: Option<String>,
    pub sha1: Option<String>,
    pub size: Option<u64>,
}

impl VersionLibrary {
    /// URL to use, falling back to `base` when the profile omitted one.
    #[must_use]
    pub fn download_url(&self, base: &str) -> String {
        self.url
            .clone()
            .unwrap_or_else(|| self.coordinate.url(base))
    }
}

/// Parsed loader profile.
#[derive(Debug, Clone)]
pub struct VersionProfile {
    pub raw: Value,
    pub id: String,
    pub main_class: String,
    pub libraries: Vec<VersionLibrary>,
}

impl VersionProfile {
    /// Parses a loader `version.json`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for malformed JSON, a missing `mainClass`
    /// or an invalid library coordinate.
    pub fn parse(raw: Value) -> Result<Self> {
        let id = raw
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let main_class = main_class(&raw).ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.no_main_class",
                format!("profile {id:?} has no mainClass"),
            )
        })?;
        let libraries = raw
            .get("libraries")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or_default();
        let mut parsed = Vec::with_capacity(libraries.len());
        for library in libraries {
            parsed.push(parse_library(library)?);
        }
        Ok(Self {
            raw,
            id,
            main_class,
            libraries: parsed,
        })
    }

    /// Sets the concrete version id and parent, then serializes the profile.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Internal`] if serialization fails.
    pub fn with_identity(&self, version_id: &str, base_id: &str) -> Result<String> {
        let mut raw = self.raw.clone();
        if let Some(object) = raw.as_object_mut() {
            object.insert("id".into(), Value::String(version_id.to_string()));
            object.insert("inheritsFrom".into(), Value::String(base_id.to_string()));
        }
        serde_json::to_string_pretty(&raw).map_err(|error| {
            Error::with_detail(
                ErrorKind::Internal,
                "error.loader.profile_serialize",
                error.to_string(),
            )
        })
    }
}

/// Extracts `mainClass`, accepting both the string and `{client}` shapes.
#[must_use]
pub fn main_class(raw: &Value) -> Option<String> {
    match raw.get("mainClass")? {
        Value::String(value) if !value.is_empty() => Some(value.clone()),
        Value::Object(object) => object
            .get("client")
            .and_then(Value::as_str)
            .map(ToString::to_string),
        _ => None,
    }
}

fn parse_library(value: &Value) -> Result<VersionLibrary> {
    let library: ProfileLibrary = serde_json::from_value(value.clone()).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.profile_library",
            error.to_string(),
        )
    })?;
    let coordinate = Coordinate::parse(&library.name)?;
    let artifact = library
        .downloads
        .as_ref()
        .and_then(|downloads| downloads.artifact.clone())
        .unwrap_or_default();
    let path = artifact.path.unwrap_or_else(|| coordinate.path());
    let url = artifact.url.filter(|value| !value.is_empty()).or_else(|| {
        library
            .url
            .filter(|value| !value.is_empty())
            .map(|base| coordinate.url(&base))
    });
    Ok(VersionLibrary {
        coordinate,
        path,
        url,
        sha1: artifact.sha1.or(library.sha1),
        size: artifact.size.or(library.size),
    })
}

/// Parses the `"libraries"` array of an `install_profile.json`.
///
/// # Errors
///
/// Returns [`ErrorKind::Parse`] for malformed coordinates.
pub fn parse_install_libraries(libraries: &[Value]) -> Result<Vec<VersionLibrary>> {
    libraries.iter().map(parse_library).collect()
}

/// Adds a plain library entry to a profile `Value`.
#[must_use]
pub fn library_value(name: &str, artifact_path: &str) -> Value {
    serde_json::json!({
        "name": name,
        "downloads": {
            "artifact": {
                "path": artifact_path,
            }
        }
    })
}

/// Appends game arguments to a profile, creating `arguments.game` if needed.
pub fn append_game_arguments(raw: &mut Value, arguments: &[&str]) {
    let Some(object) = raw.as_object_mut() else {
        return;
    };
    let entry = object
        .entry("arguments")
        .or_insert_with(|| Value::Object(serde_json::Map::new()));
    let Some(arguments_object) = entry.as_object_mut() else {
        return;
    };
    let game = arguments_object
        .entry("game")
        .or_insert_with(|| Value::Array(Vec::new()));
    let Some(game) = game.as_array_mut() else {
        return;
    };
    for argument in arguments {
        game.push(Value::String((*argument).to_string()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_fabric_profile() {
        let raw = serde_json::json!({
            "id": "fabric-loader-0.16.9-1.21.4",
            "inheritsFrom": "1.21.4",
            "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
            "libraries": [
                { "name": "org.ow2.asm:asm:9.7.1", "url": "https://maven.fabricmc.net/" }
            ]
        });
        let profile = VersionProfile::parse(raw).expect("profile");
        assert_eq!(profile.id, "fabric-loader-0.16.9-1.21.4");
        assert_eq!(profile.libraries.len(), 1);
        assert_eq!(
            profile.libraries[0].path,
            "org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
        );
        assert_eq!(
            profile.libraries[0].download_url("https://example.invalid"),
            "https://maven.fabricmc.net/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar"
        );
        let json = profile
            .with_identity("fabric-loader-0.16.9-1.21.4", "1.21.4")
            .expect("serialize");
        assert!(json.contains("\"inheritsFrom\": \"1.21.4\""));
    }

    #[test]
    fn accepts_the_client_main_class_shape() {
        let raw = serde_json::json!({ "mainClass": { "client": "example.Main" } });
        assert_eq!(main_class(&raw).as_deref(), Some("example.Main"));
    }

    #[test]
    fn appends_game_arguments() {
        let mut raw = serde_json::json!({ "arguments": { "game": ["--x"] } });
        append_game_arguments(&mut raw, &["--tweakClass", "optifine.OptiFineTweaker"]);
        assert_eq!(
            raw["arguments"]["game"],
            serde_json::json!(["--x", "--tweakClass", "optifine.OptiFineTweaker"])
        );
    }
}
