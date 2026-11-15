//! Yggdrasil API client used by third-party (authlib-injector) accounts.
//!
//! Only the endpoints a launcher needs are implemented:
//! `authenticate`, `refresh`, `validate` and `invalidate`. Server discovery
//! follows the authlib-injector launcher specification, including the
//! `x-authlib-injector-api-location` redirect used by LittleSkin-style APIs.
//!
//! Passwords are only ever placed in the request body. Errors never echo them,
//! so the same errors are safe to log and show in diagnostics.

use fg_core::{Error, ErrorKind, Result};
use fg_net::{Client, Url};
use reqwest::{Method, StatusCode};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// Header an ALI root may use to point at the real API root.
const API_LOCATION_HEADER: &str = "x-authlib-injector-api-location";

/// A resolved Yggdrasil API root and its display metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct YggdrasilServer {
    api_root: String,
    server_name: String,
    implementation_name: Option<String>,
    implementation_version: Option<String>,
}

impl YggdrasilServer {
    /// Creates a server descriptor without a network round-trip.
    ///
    /// `server_name` defaults to the URL host when it is empty.
    #[must_use]
    pub fn new(
        api_root: impl Into<String>,
        server_name: impl Into<String>,
        implementation_name: Option<String>,
        implementation_version: Option<String>,
    ) -> Self {
        let api_root = api_root.into().trim_end_matches('/').to_string();
        let server_name = {
            let name = server_name.into();
            if name.trim().is_empty() {
                host_of(&api_root).unwrap_or_else(|| api_root.clone())
            } else {
                name.trim().to_string()
            }
        };
        Self {
            api_root,
            server_name,
            implementation_name,
            implementation_version,
        }
    }

    /// Test helper: a descriptor with only the fields the account store needs.
    #[must_use]
    pub fn for_test(api_root: &str, server_name: &str) -> Self {
        Self::new(api_root, server_name, None, None)
    }

    /// API root without a trailing slash.
    #[must_use]
    pub fn api_root(&self) -> &str {
        &self.api_root
    }

    #[must_use]
    pub fn server_name(&self) -> String {
        self.server_name.clone()
    }

    #[must_use]
    pub fn implementation_name(&self) -> Option<&str> {
        self.implementation_name.as_deref()
    }

    #[must_use]
    pub fn implementation_version(&self) -> Option<&str> {
        self.implementation_version.as_deref()
    }
}

/// One selectable Minecraft profile.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct YggdrasilProfile {
    pub id: String,
    pub name: String,
}

impl YggdrasilProfile {
    #[must_use]
    pub fn id_hex(&self) -> String {
        self.id.replace('-', "")
    }
}

/// A successful `authenticate` or `refresh` response.
#[derive(Debug, Clone, PartialEq)]
pub struct AuthResponse {
    pub access_token: String,
    pub client_token: String,
    pub available_profiles: Vec<YggdrasilProfile>,
    pub selected_profile: Option<YggdrasilProfile>,
    pub user_id: Option<String>,
    pub user_properties: Value,
}

impl AuthResponse {
    /// The profile to use: the server-selected one, else the first available.
    #[must_use]
    pub fn profile(&self) -> Option<&YggdrasilProfile> {
        self.selected_profile
            .as_ref()
            .or_else(|| self.available_profiles.first())
    }
}

/// Yggdrasil HTTP client.
#[derive(Debug, Clone)]
pub struct YggdrasilClient {
    client: Client,
}

impl YggdrasilClient {
    #[must_use]
    pub fn new(client: Client) -> Self {
        Self { client }
    }

    /// Creates a client with the launcher's default user agent and proxy
    /// discovery.
    ///
    /// # Errors
    ///
    /// See [`Client::default_client`].
    pub fn default_client() -> Result<Self> {
        Ok(Self::new(Client::default_client()?))
    }

    /// Resolves an API root and reads its ALI metadata.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] for an unusable URL,
    /// [`ErrorKind::Network`] when the root cannot be reached, and
    /// [`ErrorKind::Parse`] when the metadata is not JSON.
    pub async fn discover(&self, input: &str) -> Result<YggdrasilServer> {
        let mut api_root = normalize_server_url(input)?;
        let mut response = self.get(&api_root, "discover").await?;

        if let Some(location) = response
            .headers()
            .get(API_LOCATION_HEADER)
            .and_then(|value| value.to_str().ok())
        {
            // The specification says this is absolute, but some deployments
            // return a path. Resolve it against the root before normalizing.
            let absolute = Url::parse(&api_root)
                .ok()
                .and_then(|base| base.join(location).ok())
                .map_or_else(|| location.to_string(), |url| url.to_string());
            let location = normalize_server_url(&absolute)?;
            if location != api_root {
                api_root = location;
                response = self.get(&api_root, "discover").await?;
            }
        }

        let status = response.status();
        let bytes = response.bytes().await.map_err(|error| {
            Error::with_detail(
                ErrorKind::Network,
                "error.auth.server_unreachable",
                format!("{api_root}: {error}"),
            )
        })?;
        if !status.is_success() {
            return Err(discovery_status_error(status, &bytes));
        }

        let metadata: ServerMetadata = serde_json::from_slice(&bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.auth.server_metadata",
                format!("{api_root}: {error}"),
            )
        })?;
        let meta = metadata.meta.unwrap_or_default();
        let server_name = meta
            .server_name
            .filter(|name| !name.trim().is_empty())
            .unwrap_or_else(|| host_of(&api_root).unwrap_or_else(|| api_root.clone()));

        Ok(YggdrasilServer::new(
            api_root,
            server_name,
            meta.implementation_name,
            meta.implementation_version,
        ))
    }

    /// Signs in with a username (often an email) and password.
    ///
    /// # Errors
    ///
    /// [`ErrorKind::Auth`] for rejected credentials and
    /// [`ErrorKind::Network`] for transport failures.
    pub async fn authenticate(
        &self,
        server: &YggdrasilServer,
        username: &str,
        password: &str,
        client_token: &str,
    ) -> Result<AuthResponse> {
        if username.trim().is_empty() || password.is_empty() || client_token.is_empty() {
            return Err(Error::new(
                ErrorKind::InvalidInput,
                "error.auth.credentials_required",
            ));
        }
        let endpoint = endpoint(server, "authenticate")?;
        let body = json!({
            "agent": { "name": "Minecraft", "version": 1 },
            "username": username,
            "password": password,
            "clientToken": client_token,
            "requestUser": true,
        });
        let response = self.post(&endpoint, &body).await?;
        let status = response.status();
        let bytes = read_body(response, &endpoint).await?;
        if !status.is_success() {
            return Err(auth_failure(status, &bytes, Action::Authenticate));
        }
        AuthResponse::parse(&bytes, client_token)
    }

    /// Refreshes an access token, optionally binding it to a profile.
    ///
    /// # Errors
    ///
    /// Returns `error.auth.expired` when the token is no longer valid.
    pub async fn refresh(
        &self,
        server: &YggdrasilServer,
        access_token: &str,
        client_token: &str,
        profile: Option<&YggdrasilProfile>,
    ) -> Result<AuthResponse> {
        let endpoint = endpoint(server, "refresh")?;
        let mut body = json!({
            "accessToken": access_token,
            "clientToken": client_token,
            "requestUser": true,
        });
        if let Some(profile) = profile {
            body["selectedProfile"] = json!({
                "id": profile.id_hex(),
                "name": profile.name,
            });
        }
        let response = self.post(&endpoint, &body).await?;
        let status = response.status();
        let bytes = read_body(response, &endpoint).await?;
        if !status.is_success() {
            return Err(auth_failure(status, &bytes, Action::Refresh));
        }
        AuthResponse::parse(&bytes, client_token)
    }

    /// Returns `true` when the token is still valid.
    ///
    /// # Errors
    ///
    /// Returns a network/status error when validity cannot be determined.
    pub async fn validate(
        &self,
        server: &YggdrasilServer,
        access_token: &str,
        client_token: &str,
    ) -> Result<bool> {
        let endpoint = endpoint(server, "validate")?;
        let body = json!({
            "accessToken": access_token,
            "clientToken": client_token,
        });
        let response = self.post(&endpoint, &body).await?;
        match response.status() {
            status if status.is_success() => Ok(true),
            StatusCode::FORBIDDEN | StatusCode::UNAUTHORIZED => Ok(false),
            status => {
                let bytes = read_body(response, &endpoint).await?;
                Err(status_failure(status, &bytes, "validate"))
            }
        }
    }

    /// Revokes a token. A server that already forgot it is treated as success.
    ///
    /// # Errors
    ///
    /// Returns a network/status error when the request cannot be completed.
    pub async fn invalidate(
        &self,
        server: &YggdrasilServer,
        access_token: &str,
        client_token: &str,
    ) -> Result<()> {
        let endpoint = endpoint(server, "invalidate")?;
        let body = json!({
            "accessToken": access_token,
            "clientToken": client_token,
        });
        let response = self.post(&endpoint, &body).await?;
        match response.status() {
            status if status.is_success() => Ok(()),
            StatusCode::FORBIDDEN | StatusCode::UNAUTHORIZED => Ok(()),
            status => {
                let bytes = read_body(response, &endpoint).await?;
                Err(status_failure(status, &bytes, "invalidate"))
            }
        }
    }

    async fn get(&self, url: &str, action: &str) -> Result<reqwest::Response> {
        let parsed = Url::parse(url).map_err(|error| {
            Error::with_detail(
                ErrorKind::InvalidInput,
                "error.auth.server_url",
                format!("{url}: {error}"),
            )
        })?;
        self.client.get(&parsed).await.map_err(|error| {
            Error::with_detail(
                ErrorKind::Network,
                "error.auth.server_unreachable",
                format!("{action}: {error}"),
            )
        })
    }

    async fn post(&self, endpoint: &str, body: &Value) -> Result<reqwest::Response> {
        let url = Url::parse(endpoint).map_err(|error| {
            Error::with_detail(
                ErrorKind::InvalidInput,
                "error.auth.server_url",
                format!("{endpoint}: {error}"),
            )
        })?;
        self.client
            .request(Method::POST, &url)
            .json(body)
            .send()
            .await
            .map_err(|error| {
                Error::with_detail(
                    ErrorKind::Network,
                    "error.auth.server_unreachable",
                    format!("{endpoint}: {error}"),
                )
            })
    }
}

/// Normalizes a user-provided API root.
///
/// Accepts bare hosts (prepends `https://`), trims trailing slashes, drops a
/// pasted `/authserver` suffix, and unwraps the `authlib-injector:` drag-and-drop
/// scheme. Rejects non-HTTP schemes, queries and fragments.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] with `error.auth.server_url` when the
/// value cannot be turned into an http(s) URL.
pub fn normalize_server_url(input: &str) -> Result<String> {
    let mut value = input.trim().to_string();
    if let Some(rest) = value.strip_prefix("authlib-injector:yggdrasil-server:") {
        value = decode_component(rest).unwrap_or_else(|| rest.to_string());
    }
    if value.is_empty() {
        return Err(Error::new(ErrorKind::InvalidInput, "error.auth.server_url"));
    }
    if value.contains("://") {
        // Keep the scheme as typed.
    } else {
        value = format!("https://{value}");
    }

    let mut url = Url::parse(&value).map_err(|error| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.auth.server_url",
            format!("{input}: {error}"),
        )
    })?;
    if !matches!(url.scheme(), "http" | "https") || url.host_str().is_none() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.auth.server_url",
            format!("{input}: expected an http(s) URL"),
        ));
    }
    if url.query().is_some() || url.fragment().is_some() || !url.username().is_empty() {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.auth.server_url",
            format!("{input}: query strings, fragments and user info are not supported"),
        ));
    }

    let mut path = url.path().trim_end_matches('/').to_string();
    if path.to_ascii_lowercase().ends_with("/authserver") {
        path.truncate(path.len() - "/authserver".len());
    }
    url.set_path(if path.is_empty() { "/" } else { &path });
    Ok(url.as_str().trim_end_matches('/').to_string())
}

fn endpoint(server: &YggdrasilServer, action: &str) -> Result<String> {
    Ok(format!("{}/authserver/{action}", server.api_root()))
}

fn host_of(url: &str) -> Option<String> {
    Url::parse(url)
        .ok()
        .and_then(|url| url.host_str().map(str::to_string))
}

async fn read_body(response: reqwest::Response, endpoint: &str) -> Result<Vec<u8>> {
    response
        .bytes()
        .await
        .map(|bytes| bytes.to_vec())
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Network,
                "error.auth.server_unreachable",
                format!("{endpoint}: {error}"),
            )
        })
}

#[derive(Debug, Clone, Copy)]
enum Action {
    Authenticate,
    Refresh,
}

fn auth_failure(status: StatusCode, body: &[u8], action: Action) -> Error {
    let parsed = serde_json::from_slice::<YggdrasilError>(body).ok();
    let code = parsed
        .as_ref()
        .and_then(|error| error.error.clone())
        .unwrap_or_default();
    let message = parsed
        .as_ref()
        .and_then(|error| error.error_message.clone())
        .or_else(|| String::from_utf8(body.to_vec()).ok())
        .unwrap_or_default();
    let detail = if code.is_empty() {
        message
    } else if message.is_empty() {
        code
    } else {
        format!("{code}: {message}")
    };

    match status {
        StatusCode::FORBIDDEN | StatusCode::UNAUTHORIZED => match action {
            Action::Authenticate => {
                Error::with_detail(ErrorKind::Auth, "error.auth.invalid_credentials", detail)
            }
            Action::Refresh => Error::with_detail(ErrorKind::Auth, "error.auth.expired", detail),
        },
        StatusCode::TOO_MANY_REQUESTS => {
            Error::with_detail(ErrorKind::Auth, "error.auth.rate_limited", detail)
        }
        _ => status_failure(status, body, "auth"),
    }
}

fn discovery_status_error(status: StatusCode, body: &[u8]) -> Error {
    status_failure(status, body, "discover")
}

fn status_failure(status: StatusCode, body: &[u8], action: &str) -> Error {
    let detail = serde_json::from_slice::<YggdrasilError>(body)
        .ok()
        .and_then(|error| error.error_message)
        .or_else(|| String::from_utf8(body.to_vec()).ok())
        .filter(|text| !text.is_empty())
        .unwrap_or_else(|| status.to_string());
    Error::with_detail(
        ErrorKind::Network,
        "error.auth.server_status",
        format!("{action}: HTTP {status}: {detail}"),
    )
}

fn decode_component(value: &str) -> Option<String> {
    let mut output = Vec::with_capacity(value.len());
    let bytes = value.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        match bytes[index] {
            b'%' if index + 2 < bytes.len() => {
                let hex = std::str::from_utf8(&bytes[index + 1..index + 3]).ok()?;
                output.push(u8::from_str_radix(hex, 16).ok()?);
                index += 3;
            }
            b'+' => {
                output.push(b' ');
                index += 1;
            }
            byte => {
                output.push(byte);
                index += 1;
            }
        }
    }
    String::from_utf8(output).ok()
}

impl AuthResponse {
    fn parse(bytes: &[u8], sent_client_token: &str) -> Result<Self> {
        let raw: RawAuthResponse = serde_json::from_slice(bytes).map_err(|error| {
            Error::with_detail(ErrorKind::Parse, "error.auth.response", format!("{error}"))
        })?;
        let access_token = raw
            .access_token
            .ok_or_else(|| Error::new(ErrorKind::Parse, "error.auth.response"))?;
        let mut available_profiles = raw.available_profiles.unwrap_or_default();
        for profile in &mut available_profiles {
            if profile.name.trim().is_empty() {
                profile.name = profile.id.clone();
            }
        }
        let selected_profile = raw.selected_profile.map(|mut profile| {
            if profile.name.trim().is_empty() {
                profile.name = profile.id.clone();
            }
            profile
        });
        let (user_id, user_properties) = raw
            .user
            .map(|user| {
                let properties = if user.properties.is_null() {
                    json!({})
                } else {
                    user.properties
                };
                (user.id, properties)
            })
            .unwrap_or_else(|| (None, json!({})));

        Ok(Self {
            access_token,
            client_token: raw
                .client_token
                .filter(|token| !token.is_empty())
                .unwrap_or_else(|| sent_client_token.to_string()),
            available_profiles,
            selected_profile,
            user_id,
            user_properties,
        })
    }
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct ServerMetadata {
    meta: Option<ServerMeta>,
}

#[derive(Debug, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct ServerMeta {
    server_name: Option<String>,
    implementation_name: Option<String>,
    implementation_version: Option<String>,
}

#[derive(Debug, Deserialize)]
struct YggdrasilError {
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    error_message: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RawAuthResponse {
    access_token: Option<String>,
    client_token: Option<String>,
    available_profiles: Option<Vec<YggdrasilProfile>>,
    selected_profile: Option<YggdrasilProfile>,
    user: Option<RawUser>,
}

#[derive(Debug, Deserialize)]
struct RawUser {
    id: Option<String>,
    #[serde(default)]
    properties: Value,
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        Mock, MockServer, ResponseTemplate,
        matchers::{method, path},
    };

    fn client() -> YggdrasilClient {
        YggdrasilClient::new(Client::default_client().expect("client"))
    }

    fn metadata(name: &str) -> Value {
        json!({
            "meta": {
                "serverName": name,
                "implementationName": "authlib-injector",
                "implementationVersion": "1.2.8"
            },
            "skinDomains": ["example.com"],
            "signaturePublickey": "unused"
        })
    }

    #[test]
    fn normalizes_api_roots() {
        assert_eq!(
            normalize_server_url("littleskin.cn/api/yggdrasil").expect("url"),
            "https://littleskin.cn/api/yggdrasil"
        );
        assert_eq!(
            normalize_server_url("https://littleskin.cn/api/yggdrasil/").expect("url"),
            "https://littleskin.cn/api/yggdrasil"
        );
        assert_eq!(
            normalize_server_url("https://example.com/authserver/").expect("url"),
            "https://example.com"
        );
        assert_eq!(
            normalize_server_url(
                "authlib-injector:yggdrasil-server:https%3A%2F%2Fexample.com%2Fapi%2Fyggdrasil"
            )
            .expect("url"),
            "https://example.com/api/yggdrasil"
        );
        assert!(normalize_server_url("").is_err());
        assert!(normalize_server_url("ftp://example.com").is_err());
        assert!(normalize_server_url("https://example.com/?a=1").is_err());
    }

    #[tokio::test]
    async fn discovery_follows_the_ali_header_and_reads_the_server_name() {
        let server = MockServer::start().await;
        let api_root = format!("{}/api/yggdrasil", server.uri());
        Mock::given(method("GET"))
            .and(path("/"))
            .respond_with(
                ResponseTemplate::new(200)
                    .insert_header("x-authlib-injector-api-location", api_root.as_str())
                    .set_body_json(json!({ "unused": true })),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/yggdrasil"))
            .respond_with(ResponseTemplate::new(200).set_body_json(metadata("LittleSkin")))
            .mount(&server)
            .await;

        let discovered = client().discover(&server.uri()).await.expect("discover");
        assert_eq!(discovered.api_root(), api_root);
        assert_eq!(discovered.server_name(), "LittleSkin");
        assert_eq!(discovered.implementation_name(), Some("authlib-injector"));
    }

    #[tokio::test]
    async fn authenticate_returns_the_selected_profile_and_user_properties() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/api/yggdrasil"))
            .respond_with(ResponseTemplate::new(200).set_body_json(metadata("Test")))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/yggdrasil/authserver/authenticate"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "accessToken": "access-1",
                "clientToken": "client-1",
                "selectedProfile": { "id": "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5", "name": "Fograin" },
                "availableProfiles": [
                    { "id": "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5", "name": "Fograin" }
                ],
                "user": {
                    "id": "user-1",
                    "properties": [{ "name": "textures", "value": "abc" }]
                }
            })))
            .mount(&server)
            .await;

        let discovered =
            YggdrasilServer::for_test(&format!("{}/api/yggdrasil", server.uri()), "Test");
        let response = client()
            .authenticate(&discovered, "user@example.com", "secret", "client-0")
            .await
            .expect("authenticate");
        assert_eq!(response.access_token, "access-1");
        assert_eq!(response.client_token, "client-1");
        assert_eq!(
            response.profile().map(|profile| profile.name.as_str()),
            Some("Fograin")
        );
        assert_eq!(response.user_id.as_deref(), Some("user-1"));
        assert!(response.user_properties.to_string().contains("textures"));
    }

    #[tokio::test]
    async fn refresh_binds_a_profile_and_reports_invalid_tokens() {
        let server = MockServer::start().await;
        let api_root = format!("{}/api/yggdrasil", server.uri());
        Mock::given(method("POST"))
            .and(path("/api/yggdrasil/authserver/refresh"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "accessToken": "access-2",
                "clientToken": "client-1",
                "selectedProfile": { "id": "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5", "name": "Fograin" }
            })))
            .mount(&server)
            .await;
        let discovered = YggdrasilServer::for_test(&api_root, "Test");
        let profile = YggdrasilProfile {
            id: "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5".to_string(),
            name: "Fograin".to_string(),
        };
        let response = client()
            .refresh(&discovered, "access-1", "client-1", Some(&profile))
            .await
            .expect("refresh");
        assert_eq!(response.access_token, "access-2");
        assert_eq!(response.selected_profile.as_ref(), Some(&profile));

        let expired = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/authserver/refresh"))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "error": "ForbiddenOperationException",
                "errorMessage": "Invalid token."
            })))
            .mount(&expired)
            .await;
        let server = YggdrasilServer::for_test(&expired.uri(), "Expired");
        let error = client()
            .refresh(&server, "bad", "client-1", None)
            .await
            .expect_err("expired");
        assert_eq!(error.message_key, "error.auth.expired");
    }

    #[tokio::test]
    async fn validate_and_invalidate_treat_forbidden_as_terminal() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/authserver/validate"))
            .respond_with(ResponseTemplate::new(204))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/authserver/invalidate"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&server)
            .await;
        let discovered = YggdrasilServer::for_test(&server.uri(), "Test");
        assert!(
            client()
                .validate(&discovered, "access", "client")
                .await
                .expect("validate")
        );
        client()
            .invalidate(&discovered, "access", "client")
            .await
            .expect("invalidate");
    }

    #[tokio::test]
    async fn invalid_credentials_map_to_a_safe_error() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/authserver/authenticate"))
            .respond_with(ResponseTemplate::new(403).set_body_json(json!({
                "error": "ForbiddenOperationException",
                "errorMessage": "Invalid credentials. Invalid username or password."
            })))
            .mount(&server)
            .await;
        let discovered = YggdrasilServer::for_test(&server.uri(), "Test");
        let error = client()
            .authenticate(&discovered, "user", "wrong", "client")
            .await
            .expect_err("bad credentials");
        assert_eq!(error.kind, ErrorKind::Auth);
        assert_eq!(error.message_key, "error.auth.invalid_credentials");
        let detail = error.detail.unwrap_or_default();
        assert!(!detail.contains("wrong"));
    }
}
