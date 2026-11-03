//! Native file dialogs and desktop integration.
//!
//! `Gtk.FileDialog` goes through the XDG desktop portal on Linux, so the client
//! gets the user's own file chooser instead of a custom one.

use std::path::Path;

use gio::prelude::*;
use gtk::{gio, glib};

/// Opens the native folder chooser. `Ok(None)` means the user cancelled.
pub async fn choose_folder(
    parent: &adw::ApplicationWindow,
    title: &str,
) -> Result<Option<gio::File>, glib::Error> {
    let dialog = gtk::FileDialog::builder().title(title).modal(true).build();
    match dialog.select_folder_future(Some(parent)).await {
        Ok(folder) => Ok(Some(folder)),
        Err(error) if error.matches(gio::IOErrorEnum::Cancelled) => Ok(None),
        Err(error) => Err(error),
    }
}

/// Opens a directory in the user's file manager.
pub fn open_directory(path: &Path) -> Result<(), glib::Error> {
    let uri = gio::File::for_path(path).uri();
    gio::AppInfo::launch_default_for_uri(&uri, None::<&gio::AppLaunchContext>)
}
