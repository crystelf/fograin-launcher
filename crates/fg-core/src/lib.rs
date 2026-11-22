//! Shared, UI-framework-independent core types for Fograin Launcher.
//!
//! This crate owns the pieces every other `fg-*` crate needs: the unified error
//! type, data-directory resolution, host platform detection and the small
//! filesystem/time helpers shared by the other crates.
//!
//! It must stay free of UI frameworks and asynchronous runtime dependencies so
//! the launcher core can be unit tested and driven from `fg-cli`.

pub mod config;
pub mod error;
pub mod paths;
pub mod platform;
pub mod process;
pub mod util;

pub use config::{
    DownloadConfig, DownloadSource, GameConfig, GeneralConfig, InitialPage, JavaConfig,
    LanguagePreference, LauncherConfig, NetworkConfig, ResolvedTheme, ThemePreference,
    normalize_accent,
};
pub use error::{Error, ErrorKind, Result};
pub use platform::Platform;
pub use process::CREATE_NO_WINDOW;
pub use util::{now_ms, now_ms_i64, write_atomic};
