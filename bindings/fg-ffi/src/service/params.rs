//! Request payloads accepted by `Service::rpc`.
//!
//! Every handler deserializes one of these structs, so unknown fields are
//! rejected by serde and the JSON shape stays in one place instead of being
//! read field by field from `serde_json::Value`.

use super::*;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InstanceOnlyRequest {
    pub(super) instance_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentToggleRequest {
    pub(super) instance_id: String,
    pub(super) kind: String,
    pub(super) file_name: String,
    pub(super) enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentDeleteRequest {
    pub(super) instance_id: String,
    pub(super) kind: String,
    pub(super) file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentSearchRequest {
    #[serde(default)]
    pub(super) source: String,
    pub(super) kind: String,
    #[serde(default)]
    pub(super) query: String,
    #[serde(default)]
    pub(super) instance_id: Option<String>,
    #[serde(default)]
    pub(super) game_version: Option<String>,
    #[serde(default)]
    pub(super) loader: Option<String>,
    #[serde(default)]
    pub(super) offset: u32,
    #[serde(default = "default_content_limit")]
    pub(super) limit: u32,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentVersionsRequest {
    #[serde(default)]
    pub(super) source: String,
    pub(super) project_id: String,
    pub(super) kind: String,
    #[serde(default)]
    pub(super) instance_id: Option<String>,
    #[serde(default)]
    pub(super) game_version: Option<String>,
    #[serde(default)]
    pub(super) loader: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentInstallRequest {
    #[serde(default)]
    pub(super) source: String,
    pub(super) project_id: String,
    #[serde(default)]
    pub(super) version_id: Option<String>,
    pub(super) instance_id: String,
    pub(super) kind: String,
    /// Target world for data packs.
    #[serde(default)]
    pub(super) world: Option<String>,
    #[serde(default)]
    pub(super) replace_files: Vec<String>,
    /// Defaults to true so older clients keep auto-installing prerequisites.
    #[serde(default = "default_true")]
    pub(super) install_dependencies: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ContentUpdatesRequest {
    pub(super) instance_id: String,
    #[serde(default)]
    pub(super) kind: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WorldNameRequest {
    pub(super) instance_id: String,
    pub(super) name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WorldRestoreRequest {
    pub(super) instance_id: String,
    pub(super) backup: String,
    pub(super) name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct WorldBackupRequest {
    pub(super) instance_id: String,
    pub(super) backup: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct DatapackRequest {
    pub(super) instance_id: String,
    pub(super) world: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct DatapackToggleRequest {
    pub(super) instance_id: String,
    pub(super) world: String,
    pub(super) file_name: String,
    pub(super) enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct DatapackDeleteRequest {
    pub(super) instance_id: String,
    pub(super) world: String,
    pub(super) file_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct DatapackImportRequest {
    pub(super) instance_id: String,
    pub(super) world: String,
    pub(super) path: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ModpackInspectRequest {
    pub(super) path: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ModpackInstallRequest {
    #[serde(default)]
    pub(super) source: Option<String>,
    pub(super) project_id: String,
    pub(super) version_id: String,
    #[serde(default)]
    pub(super) name: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ModpackImportRequest {
    pub(super) path: String,
    #[serde(default)]
    pub(super) name: Option<String>,
    /// Instance chosen by `modpack.install` before the download finished.
    /// Kept optional so older clients and local imports keep deriving one.
    #[serde(default)]
    pub(super) instance_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ModpackExportRequest {
    pub(super) instance_id: String,
    #[serde(default)]
    pub(super) name: Option<String>,
    #[serde(default)]
    pub(super) author: Option<String>,
    #[serde(default)]
    pub(super) version: Option<String>,
    #[serde(default)]
    pub(super) description: Option<String>,
    #[serde(default)]
    pub(super) sections: Option<Vec<String>>,
    /// Full archive path chosen in the save dialog; absent writes to `exports/`.
    #[serde(default)]
    pub(super) output_path: Option<String>,
}

pub(super) fn default_content_limit() -> u32 {
    20
}

pub(super) const fn default_true() -> bool {
    true
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InstanceCreateRequest {
    pub(super) id: String,
    #[serde(default)]
    pub(super) name: String,
    pub(super) minecraft: String,
    #[serde(default)]
    pub(super) loader_kind: String,
    #[serde(default)]
    pub(super) loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InstanceImportRequest {
    pub(super) directory: String,
    #[serde(default)]
    pub(super) name: Option<String>,
    #[serde(default)]
    pub(super) minecraft: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct IdRequest {
    pub(super) id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct IdNameRequest {
    pub(super) id: String,
    #[serde(default)]
    pub(super) name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct IdFlagRequest {
    pub(super) id: String,
    #[serde(default)]
    pub(super) favorite: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SettingsSetRequest {
    pub(super) id: String,
    #[serde(default)]
    pub(super) updates: Vec<SettingUpdate>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct VersionsListRequest {
    #[serde(default)]
    pub(super) kind: String,
    #[serde(default = "default_limit")]
    pub(super) limit: usize,
    #[serde(default)]
    pub(super) refresh: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct NewsRequest {
    #[serde(default)]
    pub(super) refresh: bool,
    #[serde(default = "default_limit")]
    pub(super) limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct VersionRequest {
    pub(super) version: String,
    #[serde(default)]
    pub(super) loader_kind: String,
    #[serde(default)]
    pub(super) loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct LoaderVersionsRequest {
    pub(super) kind: String,
    pub(super) minecraft: String,
    #[serde(default = "default_limit")]
    pub(super) limit: usize,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct InstallStartRequest {
    pub(super) version: String,
    #[serde(default)]
    pub(super) instance_id: Option<String>,
    #[serde(default)]
    pub(super) instance_name: Option<String>,
    #[serde(default)]
    pub(super) loader_kind: String,
    #[serde(default)]
    pub(super) loader_version: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct TaskIdRequest {
    pub(super) task_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct JavaInstallRequest {
    pub major: u32,
    #[serde(default)]
    pub component: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct JavaRemoveRequest {
    pub id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct LaunchStartRequest {
    pub(super) instance_id: String,
    #[serde(default)]
    pub(super) account_id: Option<String>,
    /// Legacy one-shot offline override. The account panel sends `accountId`
    /// instead.
    #[serde(default)]
    pub(super) username: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct AccountNameRequest {
    #[serde(default)]
    pub(super) name: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct OptionalIdRequest {
    #[serde(default)]
    pub(super) id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ServerRequest {
    pub(super) server: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct YggdrasilLoginRequest {
    pub(super) server: String,
    pub(super) username: String,
    pub(super) password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct ProfileSelectRequest {
    pub(super) pending_id: String,
    pub(super) profile_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct SessionStopRequest {
    #[serde(default)]
    pub(super) instance_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(super) struct GameLogRequest {
    pub(super) instance_id: String,
    #[serde(default)]
    pub(super) limit: usize,
}

pub(super) fn default_limit() -> usize {
    100
}
