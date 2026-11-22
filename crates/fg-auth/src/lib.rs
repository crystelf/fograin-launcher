//! Accounts for Fograin Launcher (§10.1).
//!
//! Supports offline accounts and third-party Yggdrasil / authlib-injector
//! accounts. Skin management is deliberately out of scope: the account model
//! stores only the metadata the launcher needs to sign in and launch, and the
//! injector lets the game fetch textures from the server.
//!
//! Microsoft login is not implemented. Callers depend on [`AuthSession`] and
//! [`AccountProfile`], not on the backend, so a Microsoft variant can reuse the
//! same launch contract later.

pub mod account;
pub mod injector;
pub mod offline;
pub mod session;
pub mod yggdrasil;

pub use account::{
    ACCOUNT_SCHEMA_VERSION, ACCOUNTS_FILE, Account, AccountStore, CREDENTIALS_FILE, normalize_uuid,
    random_token,
};
pub use injector::{InjectorRelease, InjectorSource, LATEST_URL, cache_dir, cached_jar};
pub use offline::{
    AccountKind, AccountProfile, MAX_NAME_LENGTH, MIN_NAME_LENGTH, OfflineAccount, offline_uuid,
    validate_name,
};
pub use session::{AuthSession, AuthlibInjector, UserType};
pub use yggdrasil::{
    AuthResponse, YggdrasilClient, YggdrasilProfile, YggdrasilServer, normalize_server_url,
};
