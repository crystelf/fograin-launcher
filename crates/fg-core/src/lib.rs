//! Shared, Tauri-independent core types for Fograin Launcher.
//!
//! This crate owns the pieces every other `fg-*` crate needs: the unified error
//! type (§3 of `docs/design.md`), data-directory resolution (§5.1) and platform
//! skin detection (§4.1).
//!
//! It must stay free of Tauri and of asynchronous runtime dependencies so the
//! launcher core can be unit tested and driven from `fg-cli`.

pub mod error;
pub mod paths;
pub mod platform;

pub use error::{Error, ErrorKind, Result};
pub use platform::PlatformSkin;
