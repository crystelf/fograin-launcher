//! Java discovery, runtime parsing, version matching and managed downloads
//! (§5.5, §8).
//!
//! Discovery finds installed JDKs and reads their version and architecture
//! without starting a JVM, so a version json can be matched to a runtime.
//! Managed downloads install the official Mojang catalog into
//! `runtimes/<vendor>-<component>-<version>-<arch>/`; the scanner treats that
//! directory as one more discovery root.

pub mod catalog;
pub mod discover;
pub mod managed;
pub mod matching;
pub mod probe;
pub mod runtime;

pub use catalog::{
    JavaRuntimeCandidate, MOJANG_RUNTIME_INDEX_URL, MOJANG_VENDOR, RuntimeArtifact, RuntimeIndex,
    RuntimePlatform, host_platforms, platforms_for, runtime_version_major,
};
pub use discover::{JavaScanner, discover, normalize_home};
pub use managed::{
    InstalledRuntime, JavaManager, PlannedRuntimeFile, RUNTIME_MARKER, RUNTIME_PLAN_SCHEMA_VERSION,
    RuntimeLink, RuntimeMarker, RuntimePlan, sanitize_id, validate_runtime_id,
};
pub use matching::{
    JavaRequirement, JavaSelection, JavaWarning, required_major, satisfies, select,
    select_available, verify_downloaded,
};
pub use probe::ProbeInfo;
pub use runtime::{
    JavaArch, JavaRuntime, JavaSource, JavaVersion, Release, detect_arch_bytes, detect_binary_arch,
    executable_in,
};
