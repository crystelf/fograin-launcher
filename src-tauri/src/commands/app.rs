use fg_core::{PlatformSkin, paths};
use serde::Serialize;
use tauri::AppHandle;

/// Launcher and host information shown by the M0 status screen and reused by
/// the About page later (§13 of `docs/design.md`).
#[derive(Debug, Clone, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct AppInfo {
    pub name: String,
    pub version: String,
    pub tauri_version: String,
    pub platform: PlatformSkin,
    pub platform_label: String,
    pub os: String,
    pub arch: String,
    pub data_root: String,
}

#[tauri::command]
#[specta::specta]
pub fn app_info(app: AppHandle) -> AppInfo {
    let platform = PlatformSkin::detect();
    tracing::debug!(platform = platform.id(), "app_info requested");

    AppInfo {
        name: app
            .config()
            .product_name
            .clone()
            .unwrap_or_else(|| "Fograin".to_owned()),
        version: app.package_info().version.to_string(),
        tauri_version: tauri::VERSION.to_owned(),
        platform,
        platform_label: platform.label().to_owned(),
        os: std::env::consts::OS.to_owned(),
        arch: std::env::consts::ARCH.to_owned(),
        data_root: paths::data_root().display().to_string(),
    }
}
