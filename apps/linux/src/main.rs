//! Native GNOME client for Fograin Launcher: GTK4 + libadwaita.
//!
//! The M1 shell owns the window, navigation, theme and static fixtures. The
//! Rust core (`fg-*`) is linked directly, as the design allows for the Linux
//! client; business logic arrives in M3/M4.

mod app;
mod design;
mod dev;
mod events;
mod models;
mod navigation;
mod pages;
mod platform;
mod widgets;

use adw::prelude::*;
use gtk::glib;

const APP_ID: &str = "com.fograin.launcher";

fn main() -> glib::ExitCode {
    let screenshot = dev::requested();
    let application = adw::Application::builder().application_id(APP_ID).build();

    application.connect_startup(|_| {
        design::install_icon_theme();
        design::install_stylesheet();
        // Before the first window exists, so there is no light/dark flash.
        platform::theme::apply_saved();
    });

    application.connect_activate(move |application| {
        if let Some(window) = application.active_window() {
            window.present();
            return;
        }

        app::build_ui(application);
        let Some(window) = application.active_window() else {
            return;
        };

        if let Some(path) = screenshot.clone() {
            dev::apply_overrides(&window);
            if let Ok(window) = window.downcast::<adw::ApplicationWindow>() {
                dev::schedule_screenshot(&window, path);
            }
        }
    });

    install_accelerators(&application);
    application.run()
}

fn install_accelerators(application: &adw::Application) {
    for (index, section) in navigation::Section::ALL.iter().enumerate() {
        let accelerator = format!("<Control>{}", index + 1);
        application.set_accels_for_action(section.action_name(), &[accelerator.as_str()]);
    }
    application.set_accels_for_action("app.goto-settings", &["<Control>comma"]);
    application.set_accels_for_action("app.focus-search", &["<Control>f"]);
    application.set_accels_for_action("app.import-instance", &["<Control>i"]);
    application.set_accels_for_action("app.show-about", &["F1"]);
    application.set_accels_for_action("app.quit", &["<Control>q"]);
}
