//! Development-only screenshot harness.
//!
//! The visual acceptance pass in `docs/design.md` §4.4 compares native windows
//! against the pen layouts. These environment variables make that repeatable
//! without scripting a window manager:
//!
//! - `FOGRAIN_SCREENSHOT=<path>` renders the window to a PNG and exits.
//! - `FOGRAIN_PAGE=library|settings|servers…` opens that section first.
//! - `FOGRAIN_SIZE=1000x700` resizes the window before capturing.
//! - `FOGRAIN_THEME=light|dark|system` activates the appearance action.
//!
//! None of this is part of the shipped UI; the flags are read once at startup.

use std::path::{Path, PathBuf};
use std::time::Duration;

use adw::prelude::*;
use gtk::glib;

pub fn requested() -> Option<PathBuf> {
    std::env::var_os("FOGRAIN_SCREENSHOT")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

/// Applies `FOGRAIN_PAGE` / `FOGRAIN_SIZE`, when set.
pub fn apply_overrides(window: &gtk::Window) {
    if let Some(section) = std::env::var_os("FOGRAIN_PAGE") {
        let action = format!("app.goto-{}", section.to_string_lossy());
        let _ = window.activate_action(&action, None);
    }
    if let Some(size) = std::env::var_os("FOGRAIN_SIZE")
        && let Some((width, height)) = parse_size(&size.to_string_lossy())
    {
        window.set_default_size(width, height);
    }
    if let Some(theme) = std::env::var_os("FOGRAIN_THEME") {
        let theme = theme.to_string_lossy();
        let _ = window.activate_action("app.theme", Some(&theme.as_ref().to_variant()));
    }
}

fn parse_size(value: &str) -> Option<(i32, i32)> {
    let (width, height) = value.split_once('x')?;
    Some((width.parse().ok()?, height.parse().ok()?))
}

/// Captures the window after it has been mapped once, then quits.
pub fn schedule_screenshot(window: &adw::ApplicationWindow, path: PathBuf) {
    let window = window.clone();
    glib::timeout_add_local_once(Duration::from_millis(800), move || {
        match capture_window(&window, &path) {
            Ok(()) => println!("screenshot written to {}", path.display()),
            Err(error) => eprintln!("screenshot failed: {error}"),
        }
        if let Some(application) = window.application() {
            application.quit();
        }
    });
}

fn capture_window(window: &adw::ApplicationWindow, path: &Path) -> Result<(), String> {
    let width = f64::from(window.width());
    let height = f64::from(window.height());
    if width <= 0.0 || height <= 0.0 {
        return Err("the window has not been allocated yet".to_owned());
    }

    let paintable = gtk::WidgetPaintable::new(Some(window));
    let snapshot = gtk::Snapshot::new();
    paintable.snapshot(&snapshot, width, height);
    let node = snapshot
        .to_node()
        .ok_or_else(|| "the window produced no render node".to_owned())?;

    let native = window.clone().upcast::<gtk::Native>();
    let renderer = native
        .renderer()
        .ok_or_else(|| "the window has no GSK renderer".to_owned())?;
    let viewport = gtk::graphene::Rect::new(0.0, 0.0, width as f32, height as f32);
    let texture = renderer.render_texture(&node, Some(&viewport));
    texture.save_to_png(path).map_err(|error| error.to_string())
}
