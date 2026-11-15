//! The account contract consumed by `fg-game::launch`.
//!
//! Launch argument construction must not know whether a player signed in
//! offline or against a Yggdrasil server. [`AuthSession`] carries exactly the
//! values the game needs plus the optional authlib-injector JVM arguments, and
//! [`AuthSession::offline`] keeps the M3 offline behaviour unchanged.

use std::path::PathBuf;

use fg_core::{Error, ErrorKind, Result};

use crate::account::Account;
use crate::offline::AccountKind;
use crate::offline::OfflineAccount;

/// The `--userType` value Minecraft expects.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserType {
    /// Offline / legacy sessions. Vanilla servers do not verify the token.
    Legacy,
    /// Yggdrasil / authlib-injector sessions (the launcher technical spec calls
    /// this `mojang`, even when the account is not a Mojang account).
    Mojang,
}

impl UserType {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Legacy => "legacy",
            Self::Mojang => "mojang",
        }
    }
}

/// The authlib-injector javaagent and the API root it should use.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthlibInjector {
    /// Cached `authlib-injector-<version>.jar`.
    pub jar: PathBuf,
    /// Yggdrasil API root, e.g. `https://littleskin.cn/api/yggdrasil`.
    pub server: String,
}

impl AuthlibInjector {
    #[must_use]
    pub fn new(jar: impl Into<PathBuf>, server: impl Into<String>) -> Self {
        Self {
            jar: jar.into(),
            server: server.into(),
        }
    }

    /// JVM arguments that install the agent before Minecraft starts.
    #[must_use]
    pub fn jvm_args(&self) -> Vec<String> {
        vec![
            format!("-javaagent:{}={}", self.jar.display(), self.server),
            "-Dauthlibinjector.side=client".to_string(),
        ]
    }
}

/// Everything the launch command needs to authenticate one player.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AuthSession {
    name: String,
    uuid: String,
    access_token: String,
    user_type: UserType,
    user_properties: String,
    injector: Option<AuthlibInjector>,
}

impl AuthSession {
    /// Builds the session for an offline profile.
    #[must_use]
    pub fn offline(account: &OfflineAccount) -> Self {
        Self {
            name: account.name().to_string(),
            uuid: account.uuid().to_string(),
            access_token: account.access_token().to_string(),
            user_type: UserType::Legacy,
            user_properties: "{}".to_string(),
            injector: None,
        }
    }

    /// Builds the session for a saved Yggdrasil account.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Auth`] when the account is not a Yggdrasil account
    /// or has no access token.
    pub fn yggdrasil(account: &Account, injector: AuthlibInjector) -> Result<Self> {
        if account.kind != AccountKind::Yggdrasil {
            return Err(Error::with_detail(
                ErrorKind::Auth,
                "error.auth.kind_mismatch",
                format!("account {} is {:?}", account.id, account.kind),
            ));
        }
        let access_token = account.access_token().ok_or_else(|| {
            Error::with_detail(
                ErrorKind::Auth,
                "error.auth.login_required",
                format!("account {} has no access token", account.id),
            )
        })?;
        Ok(Self {
            name: account.name.clone(),
            uuid: account.uuid.clone(),
            access_token: access_token.to_string(),
            user_type: UserType::Mojang,
            user_properties: account.properties.clone(),
            injector: Some(injector),
        })
    }

    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// UUID with dashes.
    #[must_use]
    pub fn uuid(&self) -> &str {
        &self.uuid
    }

    /// UUID without dashes, which is what `--uuid` expects.
    #[must_use]
    pub fn uuid_hex(&self) -> String {
        self.uuid.replace('-', "")
    }

    #[must_use]
    pub fn access_token(&self) -> &str {
        &self.access_token
    }

    #[must_use]
    pub const fn user_type(&self) -> UserType {
        self.user_type
    }

    /// JSON object passed through `--userProperties` (empty object by default).
    #[must_use]
    pub fn user_properties(&self) -> &str {
        &self.user_properties
    }

    /// Legacy `${auth_session}` value.
    ///
    /// Offline sessions keep the `token:<token>:<uuid>` form from M3. Yggdrasil
    /// sessions follow the authlib-injector launcher spec, which substitutes
    /// the access token directly.
    #[must_use]
    pub fn session(&self) -> String {
        match self.user_type {
            UserType::Legacy => format!("token:{}:{}", self.access_token, self.uuid_hex()),
            UserType::Mojang => self.access_token.clone(),
        }
    }

    /// Extra JVM arguments for authlib-injector; empty for offline sessions.
    #[must_use]
    pub fn jvm_args(&self) -> Vec<String> {
        self.injector
            .as_ref()
            .map(AuthlibInjector::jvm_args)
            .unwrap_or_default()
    }

    #[must_use]
    pub fn injector(&self) -> Option<&AuthlibInjector> {
        self.injector.as_ref()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn yggdrasil_account() -> Account {
        let mut account = Account::offline("Steve").expect("offline");
        account.kind = AccountKind::Yggdrasil;
        account.server = Some("https://littleskin.cn/api/yggdrasil".to_string());
        account.server_name = Some("LittleSkin".to_string());
        account.properties = r#"{"textures":[{"name":"textures"}]}"#.to_string();
        account.set_tokens("token-123", "client-456");
        account
    }

    #[test]
    fn offline_sessions_keep_the_m3_shape() {
        let session = AuthSession::offline(&OfflineAccount::new("Steve").expect("name"));
        assert_eq!(session.name(), "Steve");
        assert_eq!(session.user_type(), UserType::Legacy);
        assert_eq!(session.user_properties(), "{}");
        assert_eq!(
            session.session(),
            "token:0:5627dd98e6be3c21b8a8e92344183641"
        );
        assert!(session.jvm_args().is_empty());
    }

    #[test]
    fn yggdrasil_sessions_use_the_injector_contract() {
        let account = yggdrasil_account();
        let session = AuthSession::yggdrasil(
            &account,
            AuthlibInjector::new(
                "/cache/authlib-injector.jar",
                "https://littleskin.cn/api/yggdrasil",
            ),
        )
        .expect("session");
        assert_eq!(session.user_type(), UserType::Mojang);
        assert_eq!(session.access_token(), "token-123");
        assert_eq!(session.user_properties(), account.properties);
        assert_eq!(session.session(), "token-123");
        let args = session.jvm_args();
        assert_eq!(
            args,
            [
                "-javaagent:/cache/authlib-injector.jar=https://littleskin.cn/api/yggdrasil",
                "-Dauthlibinjector.side=client",
            ]
        );
    }

    #[test]
    fn yggdrasil_sessions_require_a_token() {
        let mut account = yggdrasil_account();
        account.clear_tokens();
        let error = AuthSession::yggdrasil(&account, AuthlibInjector::new("a.jar", "https://x"))
            .expect_err("missing token");
        assert_eq!(error.message_key, "error.auth.login_required");
    }
}
