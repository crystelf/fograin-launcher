//! Tauri shell for Fograin Launcher.
//!
//! The shell only wires commands, windows, logging and platform injection. Game
//! logic lives in the `fg-*` crates (§3 of `docs/design.md`).

mod commands;
mod logging;

use fg_core::{PlatformSkin, paths};
use tauri::{WebviewUrl, WebviewWindowBuilder};
use tauri_specta::{Builder, collect_commands};

const MAIN_WINDOW_LABEL: &str = "main";

/// Runs the desktop application.
pub fn run() {
    let log_dir = paths::logs_dir();
    // Keep the non-blocking writer alive until the event loop exits.
    let _log_guard = logging::init(log_dir.clone());

    let specta_builder = specta_builder();

    let result = tauri::Builder::default()
        .invoke_handler(specta_builder.invoke_handler())
        .setup(move |app| {
            specta_builder.mount_events(app);

            let skin = PlatformSkin::detect();
            tracing::info!(
                version = env!("CARGO_PKG_VERSION"),
                platform = skin.id(),
                data_root = %paths::data_root().display(),
                log_dir = %log_dir.display(),
                "starting Fograin Launcher",
            );

            create_main_window(app, skin)?;
            Ok(())
        })
        .run(tauri::generate_context!());

    if let Err(error) = result {
        tracing::error!(%error, "Tauri event loop terminated with an error");
        eprintln!("fatal: failed to run Fograin Launcher: {error}");
    }
}

fn specta_builder() -> Builder<tauri::Wry> {
    Builder::<tauri::Wry>::new().commands(collect_commands![commands::app::app_info])
}

fn create_main_window(app: &mut tauri::App, skin: PlatformSkin) -> tauri::Result<()> {
    // The skin has to be on <html> before the first paint so the frontend never
    // renders with the wrong platform's tokens. Depending on the web view,
    // documentElement may not exist yet when the initialization script runs, so
    // also expose the value for the inline bootstrap in index.html.
    let init_script = format!(
        "window.__FOGRAIN_PLATFORM__ = '{0}';\n\
         if (document.documentElement) {{\n\
           document.documentElement.dataset.platform = '{0}';\n\
         }}",
        skin.id()
    );

    WebviewWindowBuilder::new(app, MAIN_WINDOW_LABEL, WebviewUrl::App("index.html".into()))
        .title("雾雨启动器")
        .inner_size(1180.0, 760.0)
        .min_inner_size(960.0, 640.0)
        .center()
        .initialization_script(&init_script)
        .build()?;

    Ok(())
}

#[cfg(test)]
mod bindings {
    use super::*;
    use specta_typescript::Typescript;

    #[test]
    fn export_bindings() {
        let bindings =
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../ui/src/ipc/bindings.ts");
        if let Some(parent) = bindings.parent() {
            std::fs::create_dir_all(parent).expect("create bindings directory");
        }

        specta_builder()
            .export(Typescript::default(), bindings)
            .expect("failed to export TypeScript bindings");
    }
}
