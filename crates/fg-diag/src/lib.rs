//! Log collection and the crash analysis rule engine.
//!
//! Three layers:
//!
//! - [`log`] finds the sources of an instance's diagnostics: the launcher's own
//!   stdout ring buffer, `logs/latest.log`, the newest `crash-reports/*.txt`
//!   and `hs_err_pid*.log`.
//! - [`rule`] loads the built-in TOML rules and merges user overrides from
//!   `<data root>/diag/rules/*.toml`.
//! - [`analyze`] matches rules against the collected lines and ranks findings by
//!   severity and confidence. [`export`] writes the redacted support bundle.
//!
//! The rules carry their display copy (`title` / `body`) so a finding renders
//! without a second lookup table; the `id` is the stable key.

pub mod analyze;
pub mod export;
pub mod log;
pub mod rule;

pub use analyze::{Analysis, Finding, analyze};
pub use export::{Bundle, BundleRequest, export_bundle};
pub use log::{CrashSummary, LogBundle, LogInput, LogSourceKind, SourceInfo, collect, read_tail};
pub use rule::{FixKind, Rule, RuleSet, RuleSource, Severity};
