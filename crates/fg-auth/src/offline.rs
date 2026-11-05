//! Offline accounts (§10.1).
//!
//! An offline profile needs no network: the only contract is that the UUID is
//! the same one a vanilla server derives in `online-mode=false`, so worlds and
//! single-player saves keep the player identity. That UUID is UUID v3 of
//! `"OfflinePlayer:<name>"` with the raw MD5 digest, version and variant bits
//! rewritten — exactly what [`OfflineAccount::uuid`] implements.

use std::fmt;

use md5::{Digest, Md5};
use serde::{Deserialize, Serialize};

use fg_core::{Error, ErrorKind, Result};

/// Maximum length of a Minecraft Java username.
pub const MAX_NAME_LENGTH: usize = 16;
/// Minimum length of a Minecraft Java username.
pub const MIN_NAME_LENGTH: usize = 3;

/// A name-only account, used for single-player and offline servers.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct OfflineAccount {
    name: String,
    uuid: String,
}

impl OfflineAccount {
    /// Creates an offline account, rejecting names Minecraft would reject.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::InvalidInput`] when the name is too short, too long
    /// or contains characters outside `[A-Za-z0-9_]`.
    pub fn new(name: impl Into<String>) -> Result<Self> {
        let name = name.into();
        validate_name(&name)?;
        let uuid = offline_uuid(&name);
        Ok(Self { name, uuid })
    }

    /// Creates an account without validation, for data read back from disk.
    ///
    /// The caller is responsible for validating the name; use [`Self::new`] for
    /// any user-provided value.
    #[must_use]
    pub fn from_parts(name: impl Into<String>, uuid: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            uuid: uuid.into(),
        }
    }

    /// The player name exactly as typed.
    #[must_use]
    pub fn name(&self) -> &str {
        &self.name
    }

    /// UUID with dashes, e.g. `b50ad385-829d-3141-a216-7e7d7539ba7f`.
    #[must_use]
    pub fn uuid(&self) -> &str {
        &self.uuid
    }

    /// UUID without dashes, which is what the game's `--uuid` expects.
    #[must_use]
    pub fn uuid_hex(&self) -> String {
        self.uuid.replace('-', "")
    }

    /// The access token passed to the game for offline sessions.
    ///
    /// Vanilla treats it as opaque; servers in offline mode do not verify it.
    #[must_use]
    pub const fn access_token(&self) -> &'static str {
        "0"
    }

    /// The legacy `${auth_session}` value (Minecraft 1.6 and older).
    #[must_use]
    pub fn session(&self) -> String {
        format!("token:{}:{}", self.access_token(), self.uuid_hex())
    }

    /// Wire shape used by the clients: kind plus the public profile.
    #[must_use]
    pub fn profile(&self) -> AccountProfile {
        AccountProfile {
            kind: AccountKind::Offline,
            id: self.uuid.clone(),
            name: self.name.clone(),
        }
    }
}

impl fmt::Display for OfflineAccount {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{} ({})", self.name, self.uuid)
    }
}

/// Account backend. `Microsoft` is reserved for M11, `Yggdrasil` for M7.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AccountKind {
    Offline,
    Yggdrasil,
    Microsoft,
}

/// Public (non-secret) account metadata safe to store in `accounts.json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AccountProfile {
    pub kind: AccountKind,
    pub id: String,
    pub name: String,
}

/// Returns the offline-mode UUID for `name`, including dashes.
#[must_use]
pub fn offline_uuid(name: &str) -> String {
    let mut hasher = Md5::new();
    hasher.update(format!("OfflinePlayer:{name}").as_bytes());
    let mut bytes = hasher.finalize();
    // RFC 4122 version 3 and IETF variant, matching the vanilla server.
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
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

/// Validates a Java Edition username.
///
/// # Errors
///
/// Returns [`ErrorKind::InvalidInput`] with a message key the UI can localize.
pub fn validate_name(name: &str) -> Result<()> {
    let length = name.chars().count();
    if !(MIN_NAME_LENGTH..=MAX_NAME_LENGTH).contains(&length) {
        return Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.auth.name_length",
            format!("{length} characters; expected {MIN_NAME_LENGTH}..={MAX_NAME_LENGTH}"),
        ));
    }
    if !name
        .chars()
        .all(|character| character.is_ascii_alphanumeric() || character == '_')
    {
        return Err(Error::new(
            ErrorKind::InvalidInput,
            "error.auth.name_characters",
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offline_uuid_matches_the_vanilla_algorithm() {
        // Reference values produced by the vanilla `OfflinePlayer:` UUID rule.
        assert_eq!(
            offline_uuid("Notch"),
            "b50ad385-829d-3141-a216-7e7d7539ba7f"
        );
        assert_eq!(offline_uuid("jeb_"), "a762f560-4fce-3236-812a-b80efff0b62b");
        assert_eq!(
            offline_uuid("Steve"),
            "5627dd98-e6be-3c21-b8a8-e92344183641"
        );
    }

    #[test]
    fn offline_uuid_sets_the_version_and_variant_bits() {
        let uuid = offline_uuid("Fograin");
        assert_eq!(&uuid[14..15], "3", "version nibble: {uuid}");
        assert!(matches!(&uuid[19..20], "8" | "9" | "a" | "b"), "{uuid}");
    }

    #[test]
    fn account_exposes_the_game_facing_values() {
        let account = OfflineAccount::new("Steve").expect("valid name");
        assert_eq!(account.name(), "Steve");
        assert_eq!(account.uuid_hex(), "5627dd98e6be3c21b8a8e92344183641");
        assert_eq!(
            account.session(),
            "token:0:5627dd98e6be3c21b8a8e92344183641"
        );
        assert_eq!(account.profile().kind, AccountKind::Offline);
    }

    #[test]
    fn rejects_names_minecraft_rejects() {
        for name in ["", "ab", "abcdefghijklmnopq", "with space", "dash-name"] {
            let error = OfflineAccount::new(name).expect_err(name);
            assert_eq!(error.kind, ErrorKind::InvalidInput, "{name}");
        }
        assert!(OfflineAccount::new("a_1").is_ok());
        assert!(OfflineAccount::new("abcdefghijklmnop").is_ok());
    }
}
