//! Loader installers for Minecraft (§5.4, M5).
//!
//! The crate is split into:
//!
//! - [`kind`] — the wire/`instance.toml` loader families;
//! - [`meta`] — Fabric/Quilt/Forge/NeoForge/OptiFine version discovery;
//! - [`planner`] — turns a selection into a serializable [`plan::LoaderPlan`];
//! - [`fabric`], [`forge`], [`optifine`] — family-specific profile parsing;
//! - [`execute`] — runs the plan after the vanilla download finishes.
//!
//! `fg-game` orchestrates planning and execution; this crate never touches the
//! launcher's instance model.

pub mod archive;
pub mod execute;
pub mod fabric;
pub mod forge;
pub mod kind;
pub mod maven;
pub mod meta;
pub mod optifine;
pub mod plan;
pub mod planner;
pub mod profile;

pub use execute::{ExecuteOptions, execute};
pub use kind::LoaderKind;
pub use meta::{LoaderVersion, VersionListQuery, versions};
pub use plan::{
    InstallStep, LOADER_PLAN_SCHEMA_VERSION, LoaderFile, LoaderFilePurpose, LoaderPlan,
    OutputCheck, ZipCopy,
};
pub use planner::{LoaderPaths, LoaderPlanner, LoaderRequest, RemoteArtifact};
