//! The single pooled HTTP client used by every download (§6.2).

use std::time::Duration;

use bytes::Bytes;
use fg_core::{Error, ErrorKind, NetworkConfig, Result};
use reqwest::{
    Method, Proxy, Response, StatusCode, Url,
    header::{self, HeaderValue},
};
use serde::de::DeserializeOwned;

use crate::cache::{CachedMeta, HttpCache, now_secs};

/// Default user agent. Modrinth and CurseForge mirrors require an identifying UA.
pub const DEFAULT_USER_AGENT: &str =
    concat!("Fograin/", env!("CARGO_PKG_VERSION"), " (fograin-launcher)");

/// Upper bound for conditional-cache bodies (manifests and API responses).
const MAX_CACHED_BODY: u64 = 32 * 1024 * 1024;

/// Proxy selection (§6.1).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProxyMode {
    /// Follow environment variables and system settings (reqwest default).
    System,
    /// Never use a proxy.
    None,
    /// Explicit proxy URL: `http`, `https`, `socks5` or `socks5h`.
    Url(String),
}

impl ProxyMode {
    /// Parses `network.proxy` from `launcher.toml`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for malformed or unsupported URLs.
    pub fn parse(value: &str) -> Result<Self> {
        match value.trim() {
            "" | "auto" | "system" => Ok(Self::System),
            "none" | "off" | "direct" => Ok(Self::None),
            other => {
                let url = Url::parse(other).map_err(|error| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.net.proxy",
                        format!("{other}: {error}"),
                    )
                })?;
                if !matches!(url.scheme(), "http" | "https" | "socks5" | "socks5h") {
                    return Err(Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.net.proxy",
                        format!("unsupported proxy scheme: {}", url.scheme()),
                    ));
                }
                Ok(Self::Url(other.to_string()))
            }
        }
    }
}

/// Builds a [`Client`].
#[derive(Debug, Clone)]
pub struct ClientBuilder {
    user_agent: String,
    proxy: ProxyMode,
    connect_timeout: Duration,
    pool_idle_per_host: usize,
}

impl Default for ClientBuilder {
    fn default() -> Self {
        Self {
            user_agent: DEFAULT_USER_AGENT.to_string(),
            proxy: ProxyMode::System,
            connect_timeout: Duration::from_secs(10),
            pool_idle_per_host: 32,
        }
    }
}

impl ClientBuilder {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Applies `[network]` from `launcher.toml`.
    ///
    /// # Errors
    ///
    /// Propagates an invalid proxy URL.
    pub fn from_config(mut self, config: &NetworkConfig) -> Result<Self> {
        self.proxy = ProxyMode::parse(&config.proxy)?;
        self.connect_timeout = config.timeout();
        Ok(self)
    }

    #[must_use]
    pub fn user_agent(mut self, user_agent: impl Into<String>) -> Self {
        self.user_agent = user_agent.into();
        self
    }

    #[must_use]
    pub fn proxy(mut self, proxy: ProxyMode) -> Self {
        self.proxy = proxy;
        self
    }

    #[must_use]
    pub fn connect_timeout(mut self, timeout: Duration) -> Self {
        self.connect_timeout = timeout;
        self
    }

    #[must_use]
    pub fn pool_idle_per_host(mut self, idle: usize) -> Self {
        self.pool_idle_per_host = idle;
        self
    }

    /// Builds the shared client.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Internal`] when the TLS backend or proxy setup fails.
    pub fn build(self) -> Result<Client> {
        let mut builder = reqwest::Client::builder()
            .user_agent(&self.user_agent)
            .connect_timeout(self.connect_timeout)
            .pool_max_idle_per_host(self.pool_idle_per_host)
            .tcp_nodelay(true)
            // Mirrors occasionally redirect to a login page; a bounded policy
            // keeps a misconfigured mirror from looping forever.
            .redirect(reqwest::redirect::Policy::limited(10));

        match &self.proxy {
            ProxyMode::System => {}
            ProxyMode::None => {
                builder = builder.no_proxy();
            }
            ProxyMode::Url(url) => {
                let proxy = Proxy::all(url).map_err(|error| {
                    Error::with_detail(
                        ErrorKind::InvalidInput,
                        "error.net.proxy",
                        error.to_string(),
                    )
                })?;
                builder = builder.proxy(proxy);
            }
        }

        let http = builder.build().map_err(|error| {
            Error::with_detail(ErrorKind::Internal, "error.net.client", error.to_string())
        })?;

        Ok(Client {
            http,
            user_agent: self.user_agent,
        })
    }
}

/// Pooled HTTP client with the launcher's user agent and proxy policy.
#[derive(Debug, Clone)]
pub struct Client {
    http: reqwest::Client,
    user_agent: String,
}

impl Client {
    /// Creates a client with the documented defaults.
    ///
    /// # Errors
    ///
    /// See [`ClientBuilder::build`].
    pub fn default_client() -> Result<Self> {
        ClientBuilder::new().build()
    }

    #[must_use]
    pub fn builder() -> ClientBuilder {
        ClientBuilder::new()
    }

    /// The user agent sent with every request.
    #[must_use]
    pub fn user_agent(&self) -> &str {
        &self.user_agent
    }

    /// Access to the underlying reqwest client for the download engine.
    #[must_use]
    pub fn http(&self) -> &reqwest::Client {
        &self.http
    }

    /// Starts a request with the configured UA.
    pub fn request(&self, method: Method, url: &Url) -> reqwest::RequestBuilder {
        self.http.request(method, url.clone())
    }

    /// Sends `GET` and maps transport failures onto the public error shape.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] for transport and timeout failures.
    pub async fn get(&self, url: &Url) -> Result<Response> {
        self.http.get(url.clone()).send().await.map_err(map_reqwest)
    }

    /// Sends `HEAD`, used by health probes.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Network`] for transport and timeout failures.
    pub async fn head(&self, url: &Url) -> Result<Response> {
        self.http
            .head(url.clone())
            .send()
            .await
            .map_err(map_reqwest)
    }

    /// Fetches a body through the HTTP conditional cache (§6.2).
    ///
    /// `cache_key` identifies the entry across mirrors: callers pass the
    /// official URL even when the actual request goes through BMCLAPI, so
    /// switching mirrors still reuses the cached body.
    ///
    /// # Errors
    ///
    /// Returns a network error for failed requests or unusable responses.
    pub async fn fetch_cached(
        &self,
        cache: &HttpCache,
        cache_key: &str,
        url: &Url,
    ) -> Result<CachedResponse> {
        let cached = cache.get(cache_key).await;
        let mut request = self.http.get(url.clone());

        if let Some((meta, _)) = &cached {
            if let Some(etag) = &meta.etag
                && let Ok(value) = HeaderValue::from_str(etag)
            {
                request = request.header(header::IF_NONE_MATCH, value);
            }
            if let Some(last_modified) = &meta.last_modified
                && let Ok(value) = HeaderValue::from_str(last_modified)
            {
                request = request.header(header::IF_MODIFIED_SINCE, value);
            }
        }

        let response = request.send().await.map_err(map_reqwest)?;

        if response.status() == StatusCode::NOT_MODIFIED
            && let Some((meta, body_path)) = cached
        {
            let bytes = tokio::fs::read(&body_path).await.map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.io",
                    format!("{}: {error}", body_path.display()),
                )
            })?;
            return Ok(CachedResponse {
                bytes: Bytes::from(bytes),
                url: url.clone(),
                content_type: meta.content_type,
                from_cache: true,
            });
        }

        // 304 without a cached body, or a plain response: fetch unconditionally.
        let response = if response.status() == StatusCode::NOT_MODIFIED {
            self.http
                .get(url.clone())
                .send()
                .await
                .map_err(map_reqwest)?
        } else {
            response
        };

        if !response.status().is_success() {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.net.status",
                format!("HTTP {} for {url}", response.status()),
            ));
        }

        let content_type = header_string(&response, header::CONTENT_TYPE);
        let etag = header_string(&response, header::ETAG);
        let last_modified = header_string(&response, header::LAST_MODIFIED);

        if response
            .content_length()
            .is_some_and(|len| len > MAX_CACHED_BODY)
        {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.net.response_too_large",
                format!("{url} is larger than {MAX_CACHED_BODY} bytes"),
            ));
        }

        let bytes = response.bytes().await.map_err(map_reqwest)?;
        if bytes.len() as u64 > MAX_CACHED_BODY {
            return Err(Error::with_detail(
                ErrorKind::Network,
                "error.net.response_too_large",
                format!("{url} is larger than {MAX_CACHED_BODY} bytes"),
            ));
        }

        let meta = CachedMeta {
            key: cache_key.to_string(),
            url: url.to_string(),
            etag,
            last_modified,
            content_type: content_type.clone(),
            fetched_at: now_secs(),
            len: bytes.len() as u64,
        };

        if let Err(error) = cache.put(cache_key, &meta, &bytes).await {
            tracing::warn!(%url, detail = %error, "cannot write HTTP cache entry");
        }

        Ok(CachedResponse {
            bytes,
            url: url.clone(),
            content_type,
            from_cache: false,
        })
    }

    /// Fetches and deserialises a JSON body through the conditional cache.
    ///
    /// # Errors
    ///
    /// Returns a parse error when the payload is not valid JSON of type `T`.
    pub async fn fetch_json<T: DeserializeOwned>(
        &self,
        cache: &HttpCache,
        cache_key: &str,
        url: &Url,
    ) -> Result<T> {
        let response = self.fetch_cached(cache, cache_key, url).await?;
        serde_json::from_slice(&response.bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.net.response_json",
                format!("{url}: {error}"),
            )
        })
    }
}

/// Body returned by [`Client::fetch_cached`].
#[derive(Debug, Clone)]
pub struct CachedResponse {
    pub bytes: Bytes,
    pub url: Url,
    pub content_type: Option<String>,
    /// `true` when the server answered `304 Not Modified`.
    pub from_cache: bool,
}

fn header_string(response: &Response, name: header::HeaderName) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(str::to_owned)
}

fn map_reqwest(error: reqwest::Error) -> Error {
    let key = if error.is_timeout() {
        "error.net.timeout"
    } else {
        "error.net.connect"
    };
    Error::with_detail(ErrorKind::Network, key, error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proxy_modes_parse_the_documented_values() {
        assert_eq!(ProxyMode::parse("auto").expect("auto"), ProxyMode::System);
        assert_eq!(ProxyMode::parse("").expect("empty"), ProxyMode::System);
        assert_eq!(ProxyMode::parse("none").expect("none"), ProxyMode::None);
        assert_eq!(
            ProxyMode::parse("socks5://127.0.0.1:1080").expect("socks"),
            ProxyMode::Url("socks5://127.0.0.1:1080".into())
        );
    }

    #[test]
    fn unsupported_proxy_schemes_are_rejected() {
        assert_eq!(
            ProxyMode::parse("ftp://example.com").expect_err("ftp").kind,
            ErrorKind::InvalidInput
        );
        assert_eq!(
            ProxyMode::parse("not a url").expect_err("garbage").kind,
            ErrorKind::InvalidInput
        );
    }

    #[test]
    fn config_drives_proxy_and_timeout() {
        let config = NetworkConfig {
            proxy: "none".into(),
            timeout_secs: 25,
        };
        let builder = ClientBuilder::new()
            .from_config(&config)
            .expect("config applies");
        assert_eq!(builder.proxy, ProxyMode::None);
        assert_eq!(builder.connect_timeout, Duration::from_secs(25));
    }

    #[test]
    fn default_user_agent_identifies_the_launcher() {
        assert!(DEFAULT_USER_AGENT.starts_with("Fograin/"));
        assert!(DEFAULT_USER_AGENT.contains("fograin-launcher"));
    }

    #[tokio::test]
    async fn clients_are_cheap_to_clone() {
        let client = Client::default_client().expect("client");
        let clone = client.clone();
        assert_eq!(client.user_agent(), clone.user_agent());
    }
}
