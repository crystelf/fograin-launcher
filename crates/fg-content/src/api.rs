//! Shared HTTP plumbing for the two content APIs.

use fg_core::{DownloadSource, Error, ErrorKind, Result};
use fg_net::{Candidate, Client, HttpCache, MirrorTable, Region, Url};
use reqwest::Method;
use serde::{Serialize, de::DeserializeOwned};

/// Official Modrinth API root.
pub const MODRINTH_API: &str = "https://api.modrinth.com/v2";
/// Official CurseForge API root. `fg-net` rewrites it to MCIM and never offers
/// the official host, because the launcher has no CurseForge API key.
pub const CURSEFORGE_API: &str = "https://api.curseforge.com/v1";

/// API client shared by [`crate::modrinth`] and [`crate::curseforge`].
#[derive(Debug, Clone)]
pub struct ContentClient {
    client: Client,
    cache: HttpCache,
    table: MirrorTable,
    source: DownloadSource,
    region: Region,
}

impl ContentClient {
    /// Builds a client from the same download settings as the rest of the
    /// launcher.
    #[must_use]
    pub fn new(
        client: Client,
        cache_root: impl Into<std::path::PathBuf>,
        table: MirrorTable,
        source: DownloadSource,
        region: Region,
    ) -> Self {
        Self {
            client,
            cache: HttpCache::new(cache_root),
            table,
            source,
            region,
        }
    }

    /// HTTP client, used by the install planner for file downloads.
    #[must_use]
    pub fn http(&self) -> &Client {
        &self.client
    }

    /// Mirror table, used by the install planner.
    #[must_use]
    pub fn mirror_table(&self) -> &MirrorTable {
        &self.table
    }

    /// Download source preference.
    #[must_use]
    pub const fn download_source(&self) -> DownloadSource {
        self.source
    }

    /// Detected region that drives "auto" mirror ordering.
    #[must_use]
    pub const fn region(&self) -> Region {
        self.region
    }

    /// Fetches JSON from `official_url`, trying mirrors in the configured
    /// order and reusing the conditional HTTP cache for the first candidate.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] when every candidate fails and
    /// [`ErrorKind::Parse`] when the body is not the expected JSON shape.
    pub async fn fetch_json<T: DeserializeOwned>(&self, official_url: &str) -> Result<T> {
        let candidates = self
            .table
            .candidates(official_url, self.source, self.region);
        if candidates.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Internal,
                "error.content.request",
                format!("no candidate URL for {official_url}"),
            ));
        }

        let mut failures: Vec<String> = Vec::with_capacity(candidates.len());
        for (index, candidate) in candidates.iter().enumerate() {
            let url = Url::parse(candidate).map_err(|error| {
                Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.content.request",
                    format!("{candidate}: {error}"),
                )
            })?;

            let result: Result<T> = if index == 0 {
                self.client
                    .fetch_json(&self.cache, official_url, &url)
                    .await
            } else {
                fetch_uncached_json(&self.client, &url).await
            };

            match result {
                Ok(value) => return Ok(value),
                Err(error) => failures.push(format!("{candidate}: {error}")),
            }
        }

        Err(Error::with_detail(
            ErrorKind::Network,
            "error.content.request",
            failures.join(" | "),
        ))
    }

    /// POSTs JSON to `official_url`, trying mirrors in the configured order.
    ///
    /// Content APIs use POST only for batch lookups (hash-based update checks),
    /// so this path deliberately skips the conditional HTTP cache.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] when every candidate fails and
    /// [`ErrorKind::Parse`] when the response is not the expected JSON shape.
    pub async fn post_json<T: DeserializeOwned, B: Serialize>(
        &self,
        official_url: &str,
        body: &B,
    ) -> Result<T> {
        let candidates = self
            .table
            .candidates(official_url, self.source, self.region);
        let mut failures: Vec<String> = Vec::with_capacity(candidates.len());
        for candidate in candidates {
            let url = Url::parse(&candidate).map_err(|error| {
                Error::with_detail(
                    ErrorKind::InvalidInput,
                    "error.content.request",
                    format!("{candidate}: {error}"),
                )
            })?;
            let response = self
                .client
                .request(Method::POST, &url)
                .json(body)
                .send()
                .await;
            let response = match response {
                Ok(response) => response,
                Err(error) => {
                    failures.push(format!("{candidate}: {error}"));
                    continue;
                }
            };
            if !response.status().is_success() {
                failures.push(format!("{candidate}: HTTP {}", response.status()));
                continue;
            }
            let bytes = match response.bytes().await {
                Ok(bytes) => bytes,
                Err(error) => {
                    failures.push(format!("{candidate}: {error}"));
                    continue;
                }
            };
            return serde_json::from_slice(&bytes).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Parse,
                    "error.content.response",
                    format!("{candidate}: {error}"),
                )
            });
        }
        Err(Error::with_detail(
            ErrorKind::Network,
            "error.content.request",
            failures.join(" | "),
        ))
    }

    /// Builds the mirror-aware candidate list for a file download.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] when the stored file URL is not a
    /// valid http(s) URL.
    pub fn candidates(&self, url: &str) -> Result<Vec<Candidate>> {
        self.table
            .candidates(url, self.source, self.region)
            .into_iter()
            .map(|candidate| Candidate::parse(&candidate))
            .collect()
    }
}

async fn fetch_uncached_json<T: DeserializeOwned>(client: &Client, url: &Url) -> Result<T> {
    let response = client.get(url).await?;
    if !response.status().is_success() {
        return Err(Error::with_detail(
            ErrorKind::Network,
            "error.content.request",
            format!("HTTP {} for {url}", response.status()),
        ));
    }
    let bytes = response.bytes().await.map_err(|error| {
        Error::with_detail(
            ErrorKind::Network,
            "error.content.request",
            format!("{url}: {error}"),
        )
    })?;
    serde_json::from_slice(&bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.content.response",
            format!("{url}: {error}"),
        )
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn api_roots_are_https() {
        assert!(MODRINTH_API.starts_with("https://"));
        assert!(CURSEFORGE_API.starts_with("https://"));
    }
}
