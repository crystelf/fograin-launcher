//! Install dialog: game version, loader, loader version and instance name.
//!
//! Only 原版 can be installed in M4; the other loader cards are reserved slots
//! that show their placeholder versions and an honest M5 note. The actual
//! download starts through `AppState` and continues on the downloads page.

use std::{
    cell::{Cell, RefCell},
    rc::Rc,
};

use adw::prelude::*;

use crate::dialogs::dialog_header;
use crate::models::GameLoaderKind;
use crate::state::AppState;

/// Presents the modal install sheet for `version`.
pub fn present(parent: &gtk::Window, state: &Rc<AppState>, version: &str) {
    let view = state.download_page();
    let mut versions: Vec<String> = view.versions.iter().map(|entry| entry.id.clone()).collect();
    if !versions.iter().any(|id| id == version) {
        versions.insert(0, version.to_owned());
    }
    let labels: Vec<&str> = versions.iter().map(String::as_str).collect();
    let initial_index = versions.iter().position(|id| id == version).unwrap_or(0);

    let dialog = adw::Dialog::builder()
        .title("安装游戏")
        .content_width(620)
        .content_height(640)
        .build();

    let version_row = adw::ComboRow::builder()
        .title("Minecraft 版本")
        .subtitle("选择要安装的游戏版本")
        .build();
    let version_model = gtk::StringList::new(&labels);
    version_row.set_model(Some(&version_model));
    version_row.set_selected(initial_index as u32);

    let name_row = adw::EntryRow::builder().title("实例名称").build();

    let game_group = adw::PreferencesGroup::builder()
        .title("游戏")
        .description("留空时使用版本号作为实例名称")
        .build();
    game_group.add(&version_row);
    game_group.add(&name_row);

    let loader_group = adw::PreferencesGroup::builder().title("加载器").build();
    let loader_grid = gtk::Grid::builder()
        .column_spacing(10)
        .row_spacing(10)
        .margin_top(6)
        .margin_bottom(6)
        .margin_start(6)
        .margin_end(6)
        .build();

    let mut buttons: Vec<(GameLoaderKind, gtk::ToggleButton)> = Vec::new();
    let mut group: Option<gtk::ToggleButton> = None;
    for (index, loader) in GameLoaderKind::ALL.into_iter().enumerate() {
        let button = gtk::ToggleButton::new();
        button.set_hexpand(true);
        button.add_css_class("fograin-loader-card");
        button.set_child(Some(&loader_content(loader)));
        match &group {
            Some(first) => button.set_group(Some(first)),
            None => {
                button.set_active(true);
                group = Some(button.clone());
            }
        }
        loader_grid.attach(&button, (index % 2) as i32, (index / 2) as i32, 1, 1);
        buttons.push((loader, button));
    }
    loader_group.add(&loader_grid);

    let loader_version_row = adw::ComboRow::builder()
        .title("加载器版本")
        .visible(false)
        .build();
    loader_group.add(&loader_version_row);

    let loader_note = gtk::Label::builder()
        .label("加载器安装器将在 M5 接入；当前请选择原版安装。")
        .xalign(0.0)
        .wrap(true)
        .visible(false)
        .build();
    loader_note.add_css_class("caption");
    loader_note.add_css_class("dim-label");
    loader_note.set_margin_start(6);
    loader_note.set_margin_end(6);
    loader_note.set_margin_bottom(6);
    loader_group.add(&loader_note);

    let preferences = adw::PreferencesPage::new();
    preferences.add(&game_group);
    preferences.add(&loader_group);

    let error = gtk::Label::builder()
        .xalign(0.0)
        .wrap(true)
        .visible(false)
        .build();
    error.add_css_class("fograin-error-banner");
    error.set_margin_start(24);
    error.set_margin_end(24);
    error.set_margin_top(12);

    let scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .child(&preferences)
        .build();

    let footer_status = gtk::Label::builder()
        .label(format!("将安装 {version}（原版）"))
        .xalign(0.0)
        .hexpand(true)
        .build();
    footer_status.add_css_class("caption");
    footer_status.add_css_class("dim-label");

    let cancel = gtk::Button::with_label("取消");
    let install = gtk::Button::with_label("安装");
    install.add_css_class("suggested-action");
    install.add_css_class("pill");
    {
        let dialog = dialog.clone();
        cancel.connect_clicked(move |_| {
            dialog.close();
        });
    }

    let footer = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    footer.set_margin_top(12);
    footer.set_margin_bottom(12);
    footer.set_margin_start(24);
    footer.set_margin_end(24);
    footer.append(&footer_status);
    footer.append(&cancel);
    footer.append(&install);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
    content.append(&scroller);
    content.append(&error);
    content.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    content.append(&footer);

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&dialog_header(
        &dialog,
        "安装游戏",
        "选择加载器与版本后开始下载",
    ));
    toolbar.set_content(Some(&content));
    dialog.set_child(Some(&toolbar));
    dialog.set_default_widget(Some(&install));

    let selected_loader = Rc::new(Cell::new(GameLoaderKind::Vanilla));
    let requested_version = Rc::new(RefCell::new(version.to_owned()));

    {
        let requested_version = Rc::clone(&requested_version);
        let selected_loader = Rc::clone(&selected_loader);
        let footer_status = footer_status.clone();
        let versions = versions.clone();
        let version_for_status = version.to_owned();
        version_row.connect_selected_notify(move |row| {
            let index = row.selected() as usize;
            if let Some(id) = versions.get(index) {
                *requested_version.borrow_mut() = id.clone();
                footer_status.set_label(&install_label(id, selected_loader.get()));
            } else {
                footer_status.set_label(&install_label(&version_for_status, selected_loader.get()));
            }
        });
    }

    for (loader, button) in &buttons {
        let loader = *loader;
        let selected_loader = Rc::clone(&selected_loader);
        let loader_version_row = loader_version_row.clone();
        let loader_note = loader_note.clone();
        let install = install.clone();
        let footer_status = footer_status.clone();
        let requested_version = Rc::clone(&requested_version);
        button.connect_toggled(move |button| {
            if !button.is_active() {
                return;
            }
            selected_loader.set(loader);
            let placeholder = loader.placeholder_versions();
            if loader.is_available() || placeholder.is_empty() {
                loader_version_row.set_visible(false);
            } else {
                let model = gtk::StringList::new(placeholder);
                loader_version_row.set_model(Some(&model));
                loader_version_row.set_selected(0);
                loader_version_row.set_visible(true);
            }
            loader_note.set_visible(!loader.is_available());
            install.set_sensitive(loader.is_available());
            footer_status.set_label(&install_label(&requested_version.borrow(), loader));
        });
    }

    {
        let state = Rc::clone(state);
        let dialog = dialog.clone();
        let error = error.clone();
        let install = install.clone();
        let requested_version = Rc::clone(&requested_version);
        install.connect_clicked(move |button| {
            button.set_sensitive(false);
            error.set_visible(false);
            let dialog = dialog.clone();
            let error = error.clone();
            let button = button.clone();
            let requested = requested_version.borrow().clone();
            let instance_name = name_row.text().to_string();
            state.start_game_install(&requested, &instance_name, move |result| match result {
                Ok(()) => {
                    dialog.close();
                }
                Err(message) => {
                    error.set_label(&message);
                    error.set_visible(true);
                    button.set_sensitive(true);
                }
            });
        });
    }

    dialog.present(Some(parent));
}

fn install_label(version: &str, loader: GameLoaderKind) -> String {
    if loader.is_available() {
        format!("将安装 {version}（原版）")
    } else {
        format!("{} 暂不可用", loader.title())
    }
}

fn loader_content(loader: GameLoaderKind) -> gtk::Box {
    let content = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    content.set_margin_top(8);
    content.set_margin_bottom(8);
    content.set_margin_start(8);
    content.set_margin_end(8);

    let icon = gtk::Image::from_icon_name(loader.icon());
    icon.set_pixel_size(20);
    icon.set_valign(gtk::Align::Start);
    content.append(&icon);

    let text = gtk::Box::new(gtk::Orientation::Vertical, 1);
    let title = gtk::Box::new(gtk::Orientation::Horizontal, 4);
    let name = gtk::Label::builder()
        .label(loader.title())
        .xalign(0.0)
        .build();
    name.add_css_class("heading");
    title.append(&name);
    if let Some(milestone) = loader.milestone() {
        let badge = gtk::Label::new(Some(milestone));
        badge.add_css_class("caption");
        badge.add_css_class("accent");
        title.append(&badge);
    }
    let subtitle = gtk::Label::builder()
        .label(loader.subtitle())
        .xalign(0.0)
        .wrap(true)
        .build();
    subtitle.add_css_class("caption");
    subtitle.add_css_class("dim-label");
    text.append(&title);
    text.append(&subtitle);
    content.append(&text);
    content
}
