//! authlib-injector artifact resolution.
//!
//! The agent is a small jar that must be passed with `-javaagent:<jar>=<apiRoot>`.
//! The launcher downloads the official `latest.json` (through the normal mirror
//! table), verifies the published SHA-256 and caches one jar per version under
//! `<root>/cache/authlib-injector/`. Later launches reuse the newest cached jar
//! without a network round-trip.

use std::{
    fs,
    path::{Path, PathBuf},
    time::SystemTime,
};

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{Client, DownloadRequest, Downloader, MirrorTable, Region, Url};
use serde::Deserialize;

/// Official metadata endpoint; the mirror table maps this host to BMCLAPI.
pub const LATEST_URL: &str = "https://authlib-injector.yushi.moe/artifact/latest.json";

/// Directory inside the launcher data root.
pub const CACHE_DIR: &str = "authlib-injector";
const FILE_PREFIX: &str = "authlib-injector-";

/// One published authlib-injector build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InjectorRelease {
    pub build_number: u64,
    pub version: String,
    pub download_url: String,
    pub sha256: Option<String>,
}

impl InjectorRelease {
    fn from_bytes(bytes: &[u8]) -> Result<Self> {
        let raw: RawLatest = serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.auth.injector_metadata",
                format!("latest.json: {error}"),
            )
        })?;
        if raw.download_url.trim().is_empty() {
            return Err(Error::new(ErrorKind::Parse, "error.auth.injector_metadata"));
        }
        let version = if raw.version.trim().is_empty() {
            raw.build_number.to_string()
        } else {
            raw.version
        };
        Ok(Self {
            build_number: raw.build_number,
            version,
            download_url: raw.download_url,
            sha256: raw
                .checksums
                .and_then(|checksums| checksums.sha256)
                .filter(|value| !value.trim().is_empty()),
        })
    }

    /// A filesystem-safe jar name.
    #[must_use]
    pub fn file_name(&self) -> String {
        let safe: String = self
            .version
            .chars()
            .map(|character| {
                if character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_') {
                    character
                } else {
                    '_'
                }
            })
            .collect();
        let safe = safe.trim_matches('_');
        let safe = if safe.is_empty() { "latest" } else { safe };
        format!("{FILE_PREFIX}{safe}.jar")
    }
}

/// Resolves and downloads authlib-injector using the launcher's source policy.
#[derive(Debug, Clone)]
pub struct InjectorSource {
    client: Client,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
    latest_url: String,
}

impl InjectorSource {
    #[must_use]
    pub fn new(client: Client, table: MirrorTable, source: DownloadSource, region: Region) -> Self {
        Self {
            client,
            table,
            source,
            region,
            latest_url: LATEST_URL.to_string(),
        }
    }

    /// Overrides the metadata URL, for integration tests and self-hosted
    /// mirrors.
    #[must_use]
    pub fn with_latest_url(mut self, url: impl Into<String>) -> Self {
        self.latest_url = url.into();
        self
    }

    /// Fetches `latest.json`, trying every mirror candidate.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] or [`ErrorKind::Parse`] with
    /// `error.auth.injector_metadata` when no candidate yields a usable build.
    pub async fn latest(&self) -> Result<InjectorRelease> {
        let mut failures = Vec::new();
        for candidate in self
            .table
            .candidates(&self.latest_url, self.source, self.region)
        {
            let Ok(url) = Url::parse(&candidate) else {
                failures.push(format!("{candidate}: invalid URL"));
                continue;
            };
            match self.fetch_latest(&url).await {
                Ok(release) => return Ok(release),
                Err(error) => {
                    tracing::warn!(
                        target: "fg_auth",
                        url = %candidate,
                        detail = %error,
                        "authlib-injector metadata candidate failed"
                    );
                    failures.push(format!("{candidate}: {error}"));
                }
            }
        }
        Err(Error::with_detail(
            ErrorKind::Network,
            "error.auth.injector_metadata",
            failures.join("; "),
        ))
    }

    /// Returns a cached jar, downloading and verifying one when absent.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] with `error.auth.injector_download` when
    /// metadata or the artifact cannot be fetched, or [`ErrorKind::Io`] when
    /// the cache directory cannot be created.
    pub async fn ensure(&self, root: &Path, downloader: &Downloader) -> Result<PathBuf> {
        if let Some(jar) = cached_jar(root) {
            return Ok(jar);
        }

        let release = self.latest().await?;
        let directory = cache_dir(root);
        fs::create_dir_all(&directory).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.auth.injector_download",
                format!("{}: {error}", directory.display()),
            )
        })?;
        let dest = directory.join(release.file_name());
        if dest.is_file() && fs::metadata(&dest).is_ok_and(|metadata| metadata.len() > 0) {
            return Ok(dest);
        }

        let candidates: Vec<fg_net::Candidate> = self
            .table
            .candidates(&release.download_url, self.source, self.region)
            .into_iter()
            .filter_map(|url| fg_net::Candidate::parse(&url).ok())
            .collect();
        if candidates.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.auth.injector_download",
                format!("{}: no usable download URL", release.download_url),
            ));
        }

        let mut request = DownloadRequest::new(&dest, candidates);
        if let Some(sha256) = &release.sha256 {
            request = request.with_sha256(sha256);
        }
        downloader.download(request).await.map_err(|error| {
            Error::with_detail(
                ErrorKind::Network,
                "error.auth.injector_download",
                format!("authlib-injector {}: {error}", release.version),
            )
        })?;

        if !dest.is_file() || fs::metadata(&dest).is_ok_and(|metadata| metadata.len() == 0) {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.auth.injector_download",
                format!("{} was not written", dest.display()),
            ));
        }
        tracing::info!(
            target: "fg_auth",
            version = %release.version,
            path = %dest.display(),
            "downloaded authlib-injector"
        );
        Ok(dest)
    }

    async fn fetch_latest(&self, url: &Url) -> Result<InjectorRelease> {
        let response = self.client.get(url).await?;
        if !response.status().is_success() {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.auth.injector_metadata",
                format!("HTTP {} for {url}", response.status()),
            ));
        }
        let bytes = response.bytes().await.map_err(|error| {
            Error::with_detail(
                ErrorKind::Network,
                "error.auth.injector_metadata",
                format!("{url}: {error}"),
            )
        })?;
        InjectorRelease::from_bytes(&bytes)
    }
}

/// `<root>/cache/authlib-injector`.
#[must_use]
pub fn cache_dir(root: &Path) -> PathBuf {
    root.join("cache").join(CACHE_DIR)
}

/// Newest cached `authlib-injector-*.jar`, if any.
#[must_use]
pub fn cached_jar(root: &Path) -> Option<PathBuf> {
    let entries = fs::read_dir(cache_dir(root)).ok()?;
    entries
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with(FILE_PREFIX) && name.ends_with(".jar"))
                && fs::metadata(path).is_ok_and(|metadata| metadata.len() > 0)
        })
        .max_by_key(|path| {
            fs::metadata(path)
                .and_then(|metadata| metadata.modified())
                .unwrap_or(SystemTime::UNIX_EPOCH)
        })
}

#[derive(Debug, Deserialize)]
struct RawLatest {
    #[serde(default, rename = "build_number", alias = "buildNumber")]
    build_number: u64,
    #[serde(default)]
    version: String,
    #[serde(rename = "download_url", alias = "downloadUrl", alias = "url")]
    download_url: String,
    #[serde(default)]
    checksums: Option<RawChecksums>,
}

#[derive(Debug, Deserialize)]
struct RawChecksums {
    #[serde(default)]
    sha256: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        Mock, MockServer, ResponseTemplate,
        matchers::{method, path},
    };

    #[test]
    fn release_names_are_safe_and_stable() {
        let release = InjectorRelease {
            build_number: 11,
            version: "1.2.8".to_string(),
            download_url: "https://example.com/authlib-injector.jar".to_string(),
            sha256: None,
        };
        assert_eq!(release.file_name(), "authlib-injector-1.2.8.jar");

        let odd = InjectorRelease {
            version: "1.2/8 beta".to_string(),
            ..release
        };
        assert_eq!(odd.file_name(), "authlib-injector-1.2_8_beta.jar");
    }

    #[test]
    fn cached_jar_picks_the_newest_artifact() {
        let temp = tempfile::tempdir().expect("tempdir");
        let directory = cache_dir(temp.path());
        fs::create_dir_all(&directory).expect("cache dir");
        fs::write(directory.join("authlib-injector-1.2.7.jar"), b"old").expect("old");
        fs::write(directory.join("authlib-injector-1.2.8.jar"), b"new").expect("new");
        fs::write(directory.join("notes.txt"), b"ignore").expect("notes");

        let jar = cached_jar(temp.path()).expect("jar");
        assert!(
            jar.ends_with("authlib-injector-1.2.7.jar")
                || jar.ends_with("authlib-injector-1.2.8.jar")
        );
    }

    #[tokio::test]
    async fn downloads_latest_and_verifies_the_hash() {
        let server = MockServer::start().await;
        let jar = b"jar";
        Mock::given(method("GET"))
            .and(path("/latest.json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "build_number": 42,
                "version": "1.2.8",
                "download_url": format!("{}/authlib-injector.jar", server.uri()),
                "checksums": {
                    "sha256": "0163f1eea7894350060624d315234d40c508ab251ba121714e234503045faadd"
                }
            })))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/authlib-injector.jar"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(jar.to_vec()))
            .mount(&server)
            .await;

        let temp = tempfile::tempdir().expect("tempdir");
        let downloader = Downloader::builder(temp.path())
            .build()
            .expect("downloader");
        let source = InjectorSource::new(
            Client::default_client().expect("client"),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .with_latest_url(format!("{}/latest.json", server.uri()));

        let path = source
            .ensure(temp.path(), &downloader)
            .await
            .expect("ensure");
        assert_eq!(fs::read(&path).expect("jar"), jar);
        assert!(path.ends_with("authlib-injector-1.2.8.jar"));

        // The second call short-circuits on the cache and performs no request.
        let again = source
            .ensure(temp.path(), &downloader)
            .await
            .expect("cached");
        assert_eq!(again, path);
    }

    #[test]
    fn missing_versions_fall_back_to_the_build_number() {
        let release = InjectorRelease::from_bytes(
            br#"{"build_number": 7, "download_url": "https://example.com/a.jar"}"#,
        )
        .expect("parse");
        assert_eq!(release.version, "7");
        assert_eq!(release.file_name(), "authlib-injector-7.jar");
    }
}
