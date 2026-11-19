//! Local content management and Modrinth / CurseForge integration (M8).
//!
//! The crate owns three layers:
//!
//! - [`local`] scans an instance's `mods/`, `resourcepacks/` and
//!   `shaderpacks/` directories, reads the embedded metadata of jars and packs,
//!   and toggles or deletes individual files.
//! - [`modrinth`] and [`curseforge`] talk to the two content APIs. CurseForge
//!   is MCIM-only because the launcher has no CurseForge API key; the mirror
//!   rules in `fg-net` enforce that.
//! - [`install`] turns a project version (plus required dependencies) into a
//!   download plan and a `fg-task` graph, then records the installed
//!   project/version in the instance's content index.
//!
//! Worlds live in `fg-world`; schematics are deliberately not part of either
//! crate: the feature was removed from the M8 plan (see `docs/m8.md`), so there
//! is no scanner, no API and no UI for `.litematic` / `.schem` files.

pub mod curseforge;
pub mod index;
pub mod install;
pub mod kind;
pub mod local;
pub mod model;
pub mod modpack;
pub mod modrinth;
pub mod update;

mod api;

pub use api::ContentClient;
pub use index::{CONTENT_INDEX_FILE, ContentIndex, IndexEntry};
pub use install::{ContentPlan, InstallRequest, PlannedFile, PlannedFileKind, plan_install};
pub use kind::ContentKind;
pub use local::{LocalContent, delete_local, scan_local, set_enabled};
pub use model::{
    ContentDependency, ContentFile, ContentSearchPage, ContentSource, ContentVersion, Project,
    SearchQuery, VersionType,
};
pub use modpack::{Export, Modpack, ModpackKind, ModpackPlan, ModpackRecord, PackFile};
pub use update::{UpdateCheck, UpdateRow, check_updates};
