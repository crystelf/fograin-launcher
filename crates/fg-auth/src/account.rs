//! Persistent account metadata and the selected account.
//!
//! `accounts.json` is readable launcher metadata; access and client tokens live
//! in a separate `credentials.json` with owner-only permissions on Unix. The
//! split keeps the public file safe to inspect and copies the account model
//! from the launcher technical specification without ever storing a password.
//!
//! M7 ships a file-backed credential store. The OS keyring / Android Keystore
//! migration is tracked in `docs/backlog.md` and does not change this API.

use std::{
    collections::BTreeMap,
    fs,
    io::Write as _,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use fg_core::{Error, ErrorKind, Result};
use serde::{Deserialize, Serialize};

use crate::offline::{AccountKind, OfflineAccount, validate_name};
use crate::session::{AuthSession, AuthlibInjector};
use crate::yggdrasil::{AuthResponse, YggdrasilClient, YggdrasilProfile, YggdrasilServer};

/// Current `accounts.json` / `credentials.json` schema version.
pub const ACCOUNT_SCHEMA_VERSION: u32 = 1;
/// Account metadata file inside the launcher data root.
pub const ACCOUNTS_FILE: &str = "accounts.json";
/// Token file inside the launcher data root.
pub const CREDENTIALS_FILE: &str = "credentials.json";

/// One saved account. Tokens never serialize with the metadata.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Account {
    /// Stable id; equal to the player UUID so re-login updates in place.
    pub id: String,
    pub kind: AccountKind,
    pub name: String,
    /// UUID with dashes.
    pub uuid: String,
    /// Normalized Yggdrasil API root for third-party accounts.
    pub server: Option<String>,
    /// Human-readable server name from the ALI metadata.
    pub server_name: Option<String>,
    /// Yggdrasil `user.id`; unused by the game but part of the stored session.
    pub user_id: Option<String>,
    /// `user.properties` JSON returned by Yggdrasil.
    pub properties: String,
    access_token: Option<String>,
    client_token: Option<String>,
    pub created_at_ms: u64,
}

impl Account {
    /// Creates an offline account, rejecting names Minecraft would reject.
    ///
    /// # Errors
    ///
    /// See [`OfflineAccount::new`].
    pub fn offline(name: &str) -> Result<Self> {
        let offline = OfflineAccount::new(name)?;
        Ok(Self {
            id: offline.uuid().to_string(),
            kind: AccountKind::Offline,
            name: offline.name().to_string(),
            uuid: offline.uuid().to_string(),
            server: None,
            server_name: None,
            user_id: None,
            properties: "{}".to_string(),
            access_token: None,
            client_token: None,
            created_at_ms: now_ms(),
        })
    }

    #[must_use]
    pub fn uuid_hex(&self) -> String {
        self.uuid.replace('-', "")
    }

    #[must_use]
    pub fn is_yggdrasil(&self) -> bool {
        self.kind == AccountKind::Yggdrasil
    }

    #[must_use]
    pub fn access_token(&self) -> Option<&str> {
        self.access_token.as_deref()
    }

    #[must_use]
    pub fn client_token(&self) -> Option<&str> {
        self.client_token.as_deref()
    }

    /// Replaces the token pair; used after login and refresh.
    pub fn set_tokens(&mut self, access_token: impl Into<String>, client_token: impl Into<String>) {
        self.access_token = Some(access_token.into());
        self.client_token = Some(client_token.into());
    }

    pub fn clear_tokens(&mut self) {
        self.access_token = None;
        self.client_token = None;
    }

    /// Returns a copy of this account with a valid access token.
    ///
    /// Offline accounts are returned unchanged. A third-party account is
    /// validated first and refreshed when the server rejects the old token; a
    /// refresh failure is reported as `error.auth.expired` so the UI can ask
    /// the user to sign in again.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Auth`] for missing/expired tokens and network
    /// errors from the Yggdrasil endpoints.
    pub async fn ensure_valid(&self, client: &YggdrasilClient) -> Result<Self> {
        if self.kind != AccountKind::Yggdrasil {
            return Ok(self.clone());
        }
        let server_url = self.server.as_deref().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Auth,
                "error.auth.login_required",
                format!("account {} has no server", self.id),
            )
        })?;
        let access_token = self.access_token().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Auth,
                "error.auth.login_required",
                format!("account {} has no access token", self.id),
            )
        })?;
        let client_token = self.client_token().unwrap_or_default();
        if client_token.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Auth,
                "error.auth.login_required",
                format!("account {} has no client token", self.id),
            ));
        }

        let server = YggdrasilServer::new(
            server_url,
            self.server_name.clone().unwrap_or_default(),
            None,
            None,
        );
        if client.validate(&server, access_token, client_token).await? {
            return Ok(self.clone());
        }

        let response = client
            .refresh(&server, access_token, client_token, None)
            .await?;
        let mut updated = self.clone();
        updated.access_token = Some(response.access_token);
        updated.client_token = Some(response.client_token);
        updated.user_id = response.user_id;
        if let Ok(properties) = serde_json::to_string(&response.user_properties) {
            updated.properties = properties;
        }
        if let Some(profile) = response.selected_profile {
            updated.name = profile.name;
        }
        Ok(updated)
    }

    /// Creates the launch-facing session for this account.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Auth`] for a Yggdrasil account without a token or
    /// when the account kind has no launch support.
    pub fn session(&self, injector: Option<AuthlibInjector>) -> Result<AuthSession> {
        match self.kind {
            AccountKind::Offline => {
                let offline = OfflineAccount::from_parts(&self.name, &self.uuid);
                Ok(AuthSession::offline(&offline))
            }
            AccountKind::Yggdrasil => {
                let injector = injector.ok_or_else(|| {
                    Error::with_detail(
                        ErrorKind::Auth,
                        "error.auth.injector_required",
                        format!("account {} needs authlib-injector", self.id),
                    )
                })?;
                AuthSession::yggdrasil(self, injector)
            }
            AccountKind::Microsoft => Err(Error::new(
                ErrorKind::Unsupported,
                "error.auth.microsoft_pending",
            )),
        }
    }
}

/// The account list and selection loaded from `<root>`.
#[derive(Debug, Clone)]
pub struct AccountStore {
    root: PathBuf,
    selected_id: Option<String>,
    accounts: Vec<Account>,
}

impl AccountStore {
    /// Reads `accounts.json` and `credentials.json`; both are optional.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Parse`] for a malformed `accounts.json`, unsupported
    /// schema versions, or I/O failures other than "not found". A malformed
    /// token file is ignored with a warning so a damaged secret cannot brick
    /// the launcher; the affected account simply needs to sign in again.
    pub fn load(root: &Path) -> Result<Self> {
        let accounts_path = root.join(ACCOUNTS_FILE);
        let file = match fs::read(&accounts_path) {
            Ok(bytes) => serde_json::from_slice::<AccountFile>(&bytes).map_err(|error| {
                Error::with_detail(
                    ErrorKind::Parse,
                    "error.auth.accounts_parse",
                    format!("{}: {error}", accounts_path.display()),
                )
            })?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => AccountFile::new(),
            Err(error) => {
                return Err(Error::with_detail(
                    ErrorKind::Io,
                    "error.auth.accounts_read",
                    format!("{}: {error}", accounts_path.display()),
                ));
            }
        };
        if file.schema_version > ACCOUNT_SCHEMA_VERSION {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.auth.accounts_schema",
                format!(
                    "{} schema {} is newer than {}",
                    accounts_path.display(),
                    file.schema_version,
                    ACCOUNT_SCHEMA_VERSION
                ),
            ));
        }

        let credentials_path = root.join(CREDENTIALS_FILE);
        let credentials = match fs::read(&credentials_path) {
            Ok(bytes) => serde_json::from_slice::<CredentialFile>(&bytes).unwrap_or_else(|error| {
                tracing::warn!(
                    target: "fg_auth",
                    path = %credentials_path.display(),
                    "ignoring unreadable credentials file: {error}"
                );
                CredentialFile::new()
            }),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => CredentialFile::new(),
            Err(error) => {
                tracing::warn!(
                    target: "fg_auth",
                    path = %credentials_path.display(),
                    "ignoring unreadable credentials file: {error}"
                );
                CredentialFile::new()
            }
        };

        let mut accounts = Vec::with_capacity(file.accounts.len());
        for meta in file.accounts {
            let mut account = Account::from(meta);
            if let Some(tokens) = credentials.tokens.get(&account.id) {
                account.access_token = Some(tokens.access_token.clone());
                account.client_token = Some(tokens.client_token.clone());
            }
            if accounts
                .iter()
                .all(|existing: &Account| existing.id != account.id)
            {
                accounts.push(account);
            } else {
                tracing::warn!(
                    target: "fg_auth",
                    id = %account.id,
                    "ignoring duplicate account entry"
                );
            }
        }

        let selected_id = file
            .selected_id
            .filter(|id| accounts.iter().any(|account| &account.id == id))
            .or_else(|| accounts.first().map(|account| account.id.clone()));

        Ok(Self {
            root: root.to_path_buf(),
            selected_id,
            accounts,
        })
    }

    /// Loads the store and creates a default offline account when it is empty.
    ///
    /// `fallback_name` is the legacy `[game] username`; invalid values fall back
    /// to `Steve` instead of blocking startup.
    ///
    /// # Errors
    ///
    /// See [`Self::load`] and [`Self::save`].
    pub fn load_or_default(root: &Path, fallback_name: &str) -> Result<Self> {
        let mut store = Self::load(root)?;
        if store.accounts.is_empty() {
            let name = if validate_name(fallback_name).is_ok() {
                fallback_name
            } else {
                "Steve"
            };
            store.add_offline(name)?;
            store.save()?;
        }
        Ok(store)
    }

    /// Writes metadata and tokens atomically.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the directory or either file cannot be
    /// written.
    pub fn save(&self) -> Result<()> {
        fs::create_dir_all(&self.root).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.auth.accounts_write",
                format!("{}: {error}", self.root.display()),
            )
        })?;

        let file = AccountFile {
            schema_version: ACCOUNT_SCHEMA_VERSION,
            selected_id: self.selected_id.clone(),
            accounts: self.accounts.iter().map(AccountMeta::from).collect(),
        };
        let bytes = serde_json::to_vec_pretty(&file).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.auth.accounts_serialize",
                error.to_string(),
            )
        })?;
        write_atomic(&self.root.join(ACCOUNTS_FILE), &bytes)?;

        let mut tokens = BTreeMap::new();
        for account in &self.accounts {
            if let (Some(access_token), Some(client_token)) =
                (&account.access_token, &account.client_token)
            {
                tokens.insert(
                    account.id.clone(),
                    TokenPair {
                        access_token: access_token.clone(),
                        client_token: client_token.clone(),
                    },
                );
            }
        }
        let credentials = CredentialFile {
            schema_version: ACCOUNT_SCHEMA_VERSION,
            tokens,
        };
        let bytes = serde_json::to_vec_pretty(&credentials).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.auth.accounts_serialize",
                error.to_string(),
            )
        })?;
        write_private_atomic(&self.root.join(CREDENTIALS_FILE), &bytes)
    }

    #[must_use]
    pub fn root(&self) -> &Path {
        &self.root
    }

    #[must_use]
    pub fn accounts(&self) -> &[Account] {
        &self.accounts
    }

    #[must_use]
    pub fn selected_id(&self) -> Option<&str> {
        self.selected_id.as_deref()
    }

    #[must_use]
    pub fn selected(&self) -> Option<&Account> {
        self.selected_id.as_deref().and_then(|id| self.account(id))
    }

    #[must_use]
    pub fn account(&self, id: &str) -> Option<&Account> {
        self.accounts.iter().find(|account| account.id == id)
    }

    /// Adds (or returns) an offline account and selects it.
    ///
    /// # Errors
    ///
    /// See [`OfflineAccount::new`].
    pub fn add_offline(&mut self, name: &str) -> Result<&Account> {
        let account = Account::offline(name)?;
        let id = account.id.clone();
        if self.account(&id).is_none() {
            self.accounts.push(account);
        }
        self.selected_id = Some(id.clone());
        Ok(self
            .account(&id)
            .expect("account was inserted or already present"))
    }

    /// Saves a successful Yggdrasil login and selects the account.
    ///
    /// The profile id becomes the account id; an existing row with the same
    /// UUID is updated in place.
    pub fn upsert_yggdrasil(
        &mut self,
        server: &YggdrasilServer,
        response: &AuthResponse,
        profile: &YggdrasilProfile,
    ) -> Result<&Account> {
        let id = normalize_uuid(&profile.id);
        let properties =
            serde_json::to_string(&response.user_properties).unwrap_or_else(|_| "{}".to_string());
        let existing = self.accounts.iter().position(|account| account.id == id);
        if let Some(index) = existing {
            let account = &mut self.accounts[index];
            account.kind = AccountKind::Yggdrasil;
            account.name = profile.name.clone();
            account.uuid = id.clone();
            account.server = Some(server.api_root().to_string());
            account.server_name = Some(server.server_name());
            account.user_id = response.user_id.clone();
            account.properties = properties;
            account.access_token = Some(response.access_token.clone());
            account.client_token = Some(response.client_token.clone());
        } else {
            self.accounts.push(Account {
                id: id.clone(),
                kind: AccountKind::Yggdrasil,
                name: profile.name.clone(),
                uuid: id.clone(),
                server: Some(server.api_root().to_string()),
                server_name: Some(server.server_name()),
                user_id: response.user_id.clone(),
                properties,
                access_token: Some(response.access_token.clone()),
                client_token: Some(response.client_token.clone()),
                created_at_ms: now_ms(),
            });
        }
        self.selected_id = Some(id.clone());
        Ok(self.account(&id).expect("account was inserted or updated"))
    }

    /// Applies a refreshed token pair (and optional bound profile) to an
    /// existing account.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the id is unknown.
    pub fn update_auth(
        &mut self,
        id: &str,
        response: &AuthResponse,
        profile: Option<&YggdrasilProfile>,
    ) -> Result<&Account> {
        let index = self
            .accounts
            .iter()
            .position(|account| account.id == id)
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.auth.account_not_found",
                    id.to_string(),
                )
            })?;
        let account = &mut self.accounts[index];
        account.access_token = Some(response.access_token.clone());
        account.client_token = Some(response.client_token.clone());
        account.user_id = response.user_id.clone();
        if let Ok(properties) = serde_json::to_string(&response.user_properties) {
            account.properties = properties;
        }
        if let Some(profile) = profile {
            account.name = profile.name.clone();
        }
        Ok(account)
    }

    /// Selects an existing account.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] for unknown ids.
    pub fn select(&mut self, id: &str) -> Result<&Account> {
        if self.account(id).is_none() {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.auth.account_not_found",
                id.to_string(),
            ));
        }
        self.selected_id = Some(id.to_string());
        Ok(self.account(id).expect("account checked above"))
    }

    /// Replaces an account row with a refreshed copy.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the id is unknown.
    pub fn update_account(&mut self, account: Account) -> Result<()> {
        let index = self
            .accounts
            .iter()
            .position(|existing| existing.id == account.id)
            .ok_or_else(|| {
                Error::with_detail(
                    ErrorKind::NotFound,
                    "error.auth.account_not_found",
                    account.id.clone(),
                )
            })?;
        self.accounts[index] = account;
        Ok(())
    }

    /// Removes an account and falls back to the first remaining row.
    pub fn remove(&mut self, id: &str) -> Option<Account> {
        let index = self.accounts.iter().position(|account| account.id == id)?;
        let removed = self.accounts.remove(index);
        if self.selected_id.as_deref() == Some(id) {
            self.selected_id = self.accounts.first().map(|account| account.id.clone());
        }
        Some(removed)
    }

    /// Builds the launch session for `id`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] for unknown ids and the errors from
    /// [`Account::session`].
    pub fn session_for(&self, id: &str, injector: Option<AuthlibInjector>) -> Result<AuthSession> {
        let account = self.account(id).ok_or_else(|| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.auth.account_not_found",
                id.to_string(),
            )
        })?;
        account.session(injector)
    }

    /// Builds the launch session for the selected account.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] with `error.auth.no_account` when the
    /// store is empty.
    pub fn selected_session(&self, injector: Option<AuthlibInjector>) -> Result<AuthSession> {
        let id = self
            .selected_id
            .as_deref()
            .ok_or_else(|| Error::new(ErrorKind::NotFound, "error.auth.no_account"))?;
        self.session_for(id, injector)
    }
}

/// Generates a random RFC 4122 v4 UUID-shaped token.
#[must_use]
pub fn random_token() -> String {
    let mut bytes: [u8; 16] = rand::random();
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    format_uuid(&bytes)
}

/// Normalizes a Yggdrasil profile id to a dashed lowercase UUID when possible.
#[must_use]
pub fn normalize_uuid(value: &str) -> String {
    let trimmed = value.trim();
    let hex: String = trimmed
        .chars()
        .filter(|character| *character != '-')
        .collect();
    if hex.len() == 32 && hex.chars().all(|character| character.is_ascii_hexdigit()) {
        let hex = hex.to_ascii_lowercase();
        format!(
            "{}-{}-{}-{}-{}",
            &hex[0..8],
            &hex[8..12],
            &hex[12..16],
            &hex[16..20],
            &hex[20..32]
        )
    } else {
        trimmed.to_string()
    }
}

fn format_uuid(bytes: &[u8; 16]) -> String {
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        bytes[0],
        bytes[1],
        bytes[2],
        bytes[3],
        bytes[4],
        bytes[5],
        bytes[6],
        bytes[7],
        bytes[8],
        bytes[9],
        bytes[10],
        bytes[11],
        bytes[12],
        bytes[13],
        bytes[14],
        bytes[15]
    )
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

/// Metadata file shape (`accounts.json`).
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct AccountFile {
    schema_version: u32,
    selected_id: Option<String>,
    accounts: Vec<AccountMeta>,
}

impl AccountFile {
    fn new() -> Self {
        Self {
            schema_version: ACCOUNT_SCHEMA_VERSION,
            selected_id: None,
            accounts: Vec::new(),
        }
    }
}

impl Default for AccountFile {
    fn default() -> Self {
        Self::new()
    }
}

/// Token file shape (`credentials.json`); never part of `accounts.json`.
#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct CredentialFile {
    schema_version: u32,
    tokens: BTreeMap<String, TokenPair>,
}

impl CredentialFile {
    fn new() -> Self {
        Self {
            schema_version: ACCOUNT_SCHEMA_VERSION,
            tokens: BTreeMap::new(),
        }
    }
}

impl Default for CredentialFile {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TokenPair {
    access_token: String,
    client_token: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct AccountMeta {
    id: String,
    kind: AccountKind,
    name: String,
    uuid: String,
    server: Option<String>,
    server_name: Option<String>,
    user_id: Option<String>,
    properties: String,
    created_at_ms: u64,
}

impl Default for AccountMeta {
    fn default() -> Self {
        Self {
            id: String::new(),
            kind: AccountKind::Offline,
            name: String::new(),
            uuid: String::new(),
            server: None,
            server_name: None,
            user_id: None,
            properties: "{}".to_string(),
            created_at_ms: 0,
        }
    }
}

impl From<&Account> for AccountMeta {
    fn from(account: &Account) -> Self {
        Self {
            id: account.id.clone(),
            kind: account.kind,
            name: account.name.clone(),
            uuid: account.uuid.clone(),
            server: account.server.clone(),
            server_name: account.server_name.clone(),
            user_id: account.user_id.clone(),
            properties: account.properties.clone(),
            created_at_ms: account.created_at_ms,
        }
    }
}

impl From<AccountMeta> for Account {
    fn from(meta: AccountMeta) -> Self {
        let id = if meta.id.is_empty() {
            meta.uuid.clone()
        } else {
            meta.id
        };
        Self {
            id: id.clone(),
            kind: meta.kind,
            name: meta.name,
            uuid: if meta.uuid.is_empty() { id } else { meta.uuid },
            server: meta.server,
            server_name: meta.server_name,
            user_id: meta.user_id,
            properties: if meta.properties.is_empty() {
                "{}".to_string()
            } else {
                meta.properties
            },
            access_token: None,
            client_token: None,
            created_at_ms: meta.created_at_ms,
        }
    }
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.auth.accounts_write",
                format!("{}: {error}", parent.display()),
            )
        })?;
    }
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.auth.accounts_write",
            format!("{}: {error}", temporary.display()),
        )
    })?;
    fs::rename(&temporary, path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.auth.accounts_write",
            format!("{}: {error}", path.display()),
        )
    })
}

fn write_private_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    #[cfg(unix)]
    use std::os::unix::fs::{OpenOptionsExt as _, PermissionsExt as _};

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.auth.accounts_write",
                format!("{}: {error}", parent.display()),
            )
        })?;
    }
    let temporary = path.with_extension("tmp");

    #[cfg(unix)]
    {
        let mut file = fs::OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .mode(0o600)
            .open(&temporary)
            .map_err(|error| {
                Error::with_detail(
                    ErrorKind::Io,
                    "error.auth.credentials_write",
                    format!("{}: {error}", temporary.display()),
                )
            })?;
        file.write_all(bytes).map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.auth.credentials_write",
                format!("{}: {error}", temporary.display()),
            )
        })?;
        file.sync_all().ok();
    }

    #[cfg(not(unix))]
    fs::write(&temporary, bytes).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.auth.credentials_write",
            format!("{}: {error}", temporary.display()),
        )
    })?;

    fs::rename(&temporary, path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.auth.credentials_write",
            format!("{}: {error}", path.display()),
        )
    })?;

    #[cfg(unix)]
    fs::set_permissions(path, fs::Permissions::from_mode(0o600)).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.auth.credentials_write",
            format!("{}: {error}", path.display()),
        )
    })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server() -> YggdrasilServer {
        YggdrasilServer::for_test("https://littleskin.cn/api/yggdrasil", "LittleSkin")
    }

    fn response() -> AuthResponse {
        AuthResponse {
            access_token: "access-1".to_string(),
            client_token: "client-1".to_string(),
            available_profiles: vec![YggdrasilProfile {
                id: "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5".to_string(),
                name: "Fograin".to_string(),
            }],
            selected_profile: None,
            user_id: Some("user-1".to_string()),
            user_properties: serde_json::json!({"textures": [{"name": "textures"}]}),
        }
    }

    #[test]
    fn offline_accounts_are_deterministic_and_idempotent() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut store = AccountStore::load(temp.path()).expect("store");
        assert!(store.accounts().is_empty());

        let first = store.add_offline("Steve").expect("add").id.clone();
        assert_eq!(first, "5627dd98-e6be-3c21-b8a8-e92344183641");
        store.add_offline("Steve").expect("add again");
        assert_eq!(store.accounts().len(), 1);
        assert_eq!(store.selected_id(), Some(first.as_str()));
    }

    #[test]
    fn tokens_never_land_in_accounts_json() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut store = AccountStore::load_or_default(temp.path(), "Steve").expect("store");
        let profile = response().available_profiles[0].clone();
        store
            .upsert_yggdrasil(&server(), &response(), &profile)
            .expect("upsert");
        store.save().expect("save");

        let accounts = fs::read_to_string(temp.path().join(ACCOUNTS_FILE)).expect("accounts");
        assert!(accounts.contains("Fograin"));
        assert!(accounts.contains("\"kind\": \"yggdrasil\""));
        assert!(!accounts.contains("access-1"));
        assert!(!accounts.contains("client-1"));

        let credentials =
            fs::read_to_string(temp.path().join(CREDENTIALS_FILE)).expect("credentials");
        assert!(credentials.contains("access-1"));
        assert!(credentials.contains("client-1"));

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt as _;
            let mode = fs::metadata(temp.path().join(CREDENTIALS_FILE))
                .expect("metadata")
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(mode, 0o600);
        }
    }

    #[test]
    fn reloads_the_selected_account_and_tokens() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut store = AccountStore::load_or_default(temp.path(), "Steve").expect("store");
        let profile = response().available_profiles[0].clone();
        let account_id = store
            .upsert_yggdrasil(&server(), &response(), &profile)
            .expect("upsert")
            .id
            .clone();
        store.save().expect("save");

        let loaded = AccountStore::load(temp.path()).expect("reload");
        let account = loaded.account(&account_id).expect("account");
        assert_eq!(account.name, "Fograin");
        assert_eq!(account.access_token(), Some("access-1"));
        assert_eq!(account.server_name.as_deref(), Some("LittleSkin"));
        assert_eq!(loaded.selected_id(), Some(account_id.as_str()));
        assert!(
            account.properties.contains("\"textures\""),
            "{}",
            account.properties
        );
    }

    #[test]
    fn removing_the_selected_account_falls_back_to_the_first_row() {
        let temp = tempfile::tempdir().expect("tempdir");
        let mut store = AccountStore::load_or_default(temp.path(), "Steve").expect("store");
        store.add_offline("Alex").expect("Alex");
        let alex = store.selected_id().expect("selected").to_string();
        assert!(store.remove(&alex).is_some());
        assert_eq!(store.accounts().len(), 1);
        assert_eq!(store.selected_id(), Some(store.accounts()[0].id.as_str()));
    }

    #[tokio::test]
    async fn ensure_valid_refreshes_an_expired_token() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };

        let mock = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/authserver/validate"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&mock)
            .await;
        Mock::given(method("POST"))
            .and(path("/authserver/refresh"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "accessToken": "access-2",
                "clientToken": "client-2",
                "selectedProfile": {
                    "id": "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5",
                    "name": "Fograin"
                },
                "user": { "id": "user-1", "properties": {} }
            })))
            .mount(&mock)
            .await;

        let mut account = Account::offline("Steve").expect("offline");
        account.kind = AccountKind::Yggdrasil;
        account.server = Some(mock.uri());
        account.set_tokens("access-1", "client-1");
        let refreshed = account
            .ensure_valid(&crate::yggdrasil::YggdrasilClient::new(
                fg_net::Client::default_client().expect("client"),
            ))
            .await
            .expect("refresh");
        assert_eq!(refreshed.access_token(), Some("access-2"));
        assert_eq!(refreshed.client_token(), Some("client-2"));
        assert_eq!(refreshed.name, "Fograin");
    }

    #[test]
    fn normalizes_yggdrasil_profile_ids() {
        assert_eq!(
            normalize_uuid("8F0C1D2E3A4B5C6D7E8F90A1B2C3D4E5"),
            "8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5"
        );
        assert_eq!(
            normalize_uuid("8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5"),
            "8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5"
        );
    }

    #[test]
    fn random_tokens_are_uuid_v4_shaped() {
        let token = random_token();
        assert_eq!(token.len(), 36);
        assert_eq!(&token[14..15], "4");
        assert!(matches!(&token[19..20], "8" | "9" | "a" | "b"));
    }
}
