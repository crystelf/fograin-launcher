//! Shared, UI-framework-independent core types for Fograin Launcher.
//!
//! This crate owns the pieces every other `fg-*` crate needs: the unified error
//! type (§3 of `docs/design.md`), data-directory resolution (§5.1) and host
//! platform detection (§4.1).
//!
//! It must stay free of UI frameworks and asynchronous runtime dependencies so
//! the launcher core can be unit tested and driven from `fg-cli`.

pub mod config;
pub mod error;
pub mod paths;
pub mod platform;

pub use config::{
    DownloadConfig, DownloadSource, LauncherConfig, NetworkConfig, ResolvedTheme, ThemePreference,
};
pub use error::{Error, ErrorKind, Result};
pub use platform::Platform;
