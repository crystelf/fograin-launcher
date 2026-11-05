//! Accounts for Fograin Launcher (§10.1).
//!
//! M3 ships offline accounts only. The module boundary is deliberately shared
//! with the future authlib-injector (M7) and Microsoft (M11) backends: callers
//! depend on [`AccountProfile`] and an access token, not on the backend.

pub mod offline;

pub use offline::{
    AccountKind, AccountProfile, MAX_NAME_LENGTH, MIN_NAME_LENGTH, OfflineAccount, offline_uuid,
    validate_name,
};
