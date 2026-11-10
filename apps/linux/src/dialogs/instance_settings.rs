//! 游戏设置 dialog: launch, display, content milestones and maintenance.
//!
//! Values come from `instances.settings.get` and are written back one field at a
//! time through `instances.settings.set`. Clearing a value stores `null`, which
//! makes the instance inherit `launcher.toml [game]` again — the same contract
//! the macOS settings sheet uses.

use std::{cell::Cell, rc::Rc};

use adw::prelude::*;
use serde_json::{Value, json};

use crate::dialogs::dialog_header;
use crate::models::Instance;
use crate::state::AppState;
use crate::widgets::{self, InstanceTile, TileSize};

const MEMORY_CHOICES_MB: [u32; 7] = [0, 2048, 4096, 6144, 8192, 12_288, 16_384];
const RESOLUTIONS: [(u32, u32); 5] = [(0, 0), (1280, 720), (1600, 900), (1920, 1080), (2560, 1440)];

/// Presents the settings dialog for one instance.
pub fn present(parent: &gtk::Window, state: &Rc<AppState>, instance_id: &str) {
    let Some(instance) = state.instance(instance_id) else {
        return;
    };

    let dialog = adw::Dialog::builder()
        .title("游戏设置")
        .content_width(720)
        .content_height(660)
        .build();

    let overview = overview_row(&instance);

    let memory_row = adw::ComboRow::builder()
        .title("内存")
        .subtitle("自动由启动器按模组数量决定；修改后只影响这个实例")
        .build();
    let memory_labels: Vec<String> = MEMORY_CHOICES_MB
        .iter()
        .map(|mb| {
            if *mb == 0 {
                "自动".to_owned()
            } else {
                format!("{} GB", mb / 1024)
            }
        })
        .collect();
    let memory_refs: Vec<&str> = memory_labels.iter().map(String::as_str).collect();
    memory_row.set_model(Some(&gtk::StringList::new(&memory_refs)));

    let jvm_row = adw::EntryRow::builder().title("JVM 参数").build();
    let java_row = adw::ActionRow::builder()
        .title("Java 运行时")
        .subtitle("自动发现")
        .build();
    let fullscreen_row = adw::SwitchRow::builder()
        .title("启动时全屏")
        .subtitle("覆盖全局显示设置")
        .build();

    let launch_group = adw::PreferencesGroup::builder().title("启动").build();
    launch_group.add(&memory_row);
    launch_group.add(&jvm_row);
    launch_group.add(&java_row);
    launch_group.add(&fullscreen_row);

    let resolution_row = adw::ComboRow::builder()
        .title("窗口分辨率")
        .subtitle("跟随游戏默认或指定固定尺寸")
        .build();
    let resolution_labels: Vec<String> = RESOLUTIONS
        .iter()
        .map(|(width, height)| {
            if *width == 0 {
                "跟随游戏默认".to_owned()
            } else {
                format!("{width} × {height}")
            }
        })
        .collect();
    let resolution_refs: Vec<&str> = resolution_labels.iter().map(String::as_str).collect();
    resolution_row.set_model(Some(&gtk::StringList::new(&resolution_refs)));

    let display_group = adw::PreferencesGroup::builder().title("显示").build();
    display_group.add(&resolution_row);

    let content_group = adw::PreferencesGroup::builder().title("内容").build();
    for (title, milestone) in [
        ("模组管理", "M8"),
        ("资源包管理", "M8"),
        ("光影包管理", "M8"),
        ("世界管理", "M9"),
        ("原理图管理", "M9"),
        ("更新整合包", "M9"),
        ("日志与崩溃报告", "M9"),
        ("导出与备份", "M9"),
    ] {
        content_group.add(&milestone_row(title, milestone));
    }

    let maintenance_group = adw::PreferencesGroup::builder().title("维护").build();
    let folder_row = adw::ActionRow::builder()
        .title("打开实例目录")
        .subtitle("在文件管理器中显示游戏目录")
        .activatable(true)
        .build();
    let folder_icon = gtk::Image::from_icon_name("folder-open-symbolic");
    folder_icon.add_css_class("dim-label");
    folder_row.add_suffix(&folder_icon);
    {
        let state = Rc::clone(state);
        let instance = instance.clone();
        folder_row.connect_activated(move |_| state.open_instance_folder(&instance));
    }
    maintenance_group.add(&folder_row);

    let delete_row = adw::ActionRow::builder()
        .title("删除实例…")
        .subtitle("删除实例记录与游戏目录，无法撤销")
        .activatable(true)
        .build();
    let delete_icon = gtk::Image::from_icon_name("user-trash-symbolic");
    delete_icon.add_css_class("error");
    delete_row.add_suffix(&delete_icon);
    {
        let state = Rc::clone(state);
        let instance = instance.clone();
        let dialog = dialog.clone();
        delete_row.connect_activated(move |_| confirm_delete(&dialog, &state, &instance));
    }
    maintenance_group.add(&delete_row);

    let preferences = adw::PreferencesPage::new();
    preferences.add(&launch_group);
    preferences.add(&display_group);
    preferences.add(&content_group);
    preferences.add(&maintenance_group);

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

    let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
    content.append(&overview);
    content.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    content.append(&error);
    content.append(&scroller);

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&dialog_header(&dialog, "游戏设置", "启动、显示与维护"));
    toolbar.set_content(Some(&content));
    dialog.set_child(Some(&toolbar));

    let syncing = Rc::new(Cell::new(false));

    // Persist one field, then let the central refresh update every page.
    {
        let state = Rc::clone(state);
        let id = instance.id.clone();
        let syncing = Rc::clone(&syncing);
        memory_row.connect_selected_notify(move |row| {
            if syncing.get() {
                return;
            }
            let index = row.selected() as usize;
            let value = MEMORY_CHOICES_MB.get(index).copied().unwrap_or(0);
            let value = if value == 0 {
                Value::Null
            } else {
                json!(value)
            };
            state.configure_instance(&id, "memory", value);
        });
    }
    {
        let state = Rc::clone(state);
        let id = instance.id.clone();
        let syncing = Rc::clone(&syncing);
        let jvm_row = jvm_row.clone();
        let row_for_persist = jvm_row.clone();
        let persist = move || {
            if syncing.get() {
                return;
            }
            state.configure_instance(&id, "jvmArgs", json!(row_for_persist.text().to_string()));
        };
        let persist = Rc::new(persist);
        {
            let persist = Rc::clone(&persist);
            jvm_row.connect_apply(move |_| persist());
        }
        {
            let controller = gtk::EventControllerFocus::new();
            let persist = Rc::clone(&persist);
            controller.connect_leave(move |_| persist());
            jvm_row.add_controller(controller);
        }
    }
    {
        let state = Rc::clone(state);
        let id = instance.id.clone();
        let syncing = Rc::clone(&syncing);
        fullscreen_row.connect_active_notify(move |row| {
            if syncing.get() {
                return;
            }
            let value = if row.is_active() {
                Value::Bool(true)
            } else {
                Value::Null
            };
            state.configure_instance(&id, "fullscreen", value);
        });
    }
    {
        let state = Rc::clone(state);
        let id = instance.id.clone();
        let syncing = Rc::clone(&syncing);
        resolution_row.connect_selected_notify(move |row| {
            if syncing.get() {
                return;
            }
            let index = row.selected() as usize;
            let value = match RESOLUTIONS.get(index).copied() {
                Some((0, _)) | None => Value::Null,
                Some((width, height)) => json!([width, height]),
            };
            state.configure_instance(&id, "resolution", value);
        });
    }

    // Load the effective values and the instance overrides after the dialog is
    // built so it appears immediately even while the RPC is in flight.
    {
        let syncing = Rc::clone(&syncing);
        let error = error.clone();
        let memory_row = memory_row.clone();
        let jvm_row = jvm_row.clone();
        let java_row = java_row.clone();
        let fullscreen_row = fullscreen_row.clone();
        let resolution_row = resolution_row.clone();
        let instance_id = instance.id.clone();
        state.instance_settings(&instance_id, move |result| {
            let payload = match result {
                Ok(payload) => payload,
                Err(failure) => {
                    error.set_label(&failure.user_message());
                    error.set_visible(true);
                    return;
                }
            };

            let overrides = payload.get("overrides").and_then(Value::as_object);
            let effective = payload.get("effective").and_then(Value::as_object);

            syncing.set(true);

            let memory_override = overrides
                .and_then(|object| object.get("memoryMb"))
                .and_then(Value::as_u64)
                .map(|mb| mb as u32);
            let memory_index = memory_override
                .and_then(|mb| MEMORY_CHOICES_MB.iter().position(|choice| *choice == mb))
                .unwrap_or(0);
            memory_row.set_selected(memory_index as u32);

            jvm_row.set_text(
                overrides
                    .and_then(|object| object.get("jvmArgs"))
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            );

            let java = overrides
                .and_then(|object| object.get("java"))
                .and_then(Value::as_str)
                .or_else(|| {
                    effective
                        .and_then(|object| object.get("java"))
                        .and_then(Value::as_str)
                });
            java_row.set_subtitle(java.unwrap_or("自动发现"));

            fullscreen_row.set_active(
                overrides
                    .and_then(|object| object.get("fullscreen"))
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
            );

            let resolution_override = overrides
                .and_then(|object| object.get("resolution"))
                .and_then(Value::as_array)
                .and_then(|values| {
                    Some((
                        values.first()?.as_u64()? as u32,
                        values.get(1)?.as_u64()? as u32,
                    ))
                });
            let resolution_index = resolution_override
                .and_then(|size| RESOLUTIONS.iter().position(|choice| *choice == size))
                .unwrap_or(0);
            resolution_row.set_selected(resolution_index as u32);

            syncing.set(false);
        });
    }

    dialog.present(Some(parent));
}

fn overview_row(instance: &Instance) -> gtk::Box {
    let row = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    row.set_margin_top(16);
    row.set_margin_bottom(16);
    row.set_margin_start(24);
    row.set_margin_end(24);

    let tile = InstanceTile::new(TileSize::Banner);
    tile.show(instance);
    tile.widget().set_size_request(48, 48);
    row.append(tile.widget());

    let text = gtk::Box::new(gtk::Orientation::Vertical, 2);
    let name = gtk::Label::builder()
        .label(&instance.name)
        .xalign(0.0)
        .build();
    name.add_css_class("title-4");
    let detail = gtk::Label::builder()
        .label(format!("{} · 游戏设置", instance.display_version()))
        .xalign(0.0)
        .build();
    detail.add_css_class("caption");
    detail.add_css_class("dim-label");
    text.append(&name);
    text.append(&detail);
    row.append(&text);

    let spacer = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    spacer.set_hexpand(true);
    row.append(&spacer);
    row
}

fn milestone_row(title: &str, milestone: &str) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(title)
        .subtitle("尚未接入")
        .build();
    row.add_suffix(&widgets::milestone_chip(milestone));
    row
}

fn confirm_delete(dialog: &adw::Dialog, state: &Rc<AppState>, instance: &Instance) {
    let alert = adw::AlertDialog::builder()
        .heading("删除实例？")
        .body(format!(
            "将删除“{}”的实例记录与游戏目录，此操作无法撤销。",
            instance.name
        ))
        .build();
    alert.add_response("cancel", "取消");
    alert.add_response("delete", "删除");
    alert.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
    alert.set_default_response(Some("cancel"));
    alert.set_close_response("cancel");
    {
        let state = Rc::clone(state);
        let instance = instance.clone();
        let dialog = dialog.clone();
        alert.connect_response(None, move |_, response| {
            if response == "delete" {
                state.delete_instance(&instance);
                dialog.close();
            }
        });
    }
    alert.present(Some(dialog));
}
