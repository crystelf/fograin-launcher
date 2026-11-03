//! Home page: launch banner, recent instances and the status column.
//!
//! Layout reference is the pen "Desktop · 主页 (Linux GNOME)" frame. The banner
//! is a native `Gtk.Overlay`; lists are `Gtk.ListBox` with the Adwaita
//! `.boxed-list` style; the environment block uses `Adw.ActionRow`/`SwitchRow`.

use std::rc::Rc;

use adw::prelude::*;

use crate::design;
use crate::events::{EventSink, UiEvent};
use crate::models::{self, Instance};
use crate::widgets::{self, RowStyle};

pub struct HomePage {
    pub page: adw::NavigationPage,
    /// Columns switch to a vertical stack on narrow windows (see the shell
    /// breakpoint); they are exposed for that one setter pair.
    pub columns: gtk::Box,
    pub status_column: gtk::Box,
}

impl HomePage {
    pub fn new(sink: &Rc<EventSink>) -> Self {
        let content = gtk::Box::new(gtk::Orientation::Vertical, design::SECTION_GAP);
        content.set_margin_top(design::CONTENT_PADDING);
        content.set_margin_bottom(design::CONTENT_PADDING);
        content.set_margin_start(design::CONTENT_PADDING);
        content.set_margin_end(design::CONTENT_PADDING);
        content.set_hexpand(true);
        content.append(&widgets::launch_banner(models::current()));

        let columns = gtk::Box::new(gtk::Orientation::Horizontal, design::COLUMN_GAP);
        columns.set_hexpand(true);
        let recent = recent_column(sink);
        recent.set_hexpand(true);
        let status_column = status_column();
        status_column.set_size_request(340, -1);
        // Its section headers expand, which would make the rail compute-expand
        // and split the spare width evenly with the instance list.
        status_column.set_hexpand(false);
        columns.append(&recent);
        columns.append(&status_column);
        content.append(&columns);

        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&content)
            .build();

        let header = adw::HeaderBar::new();
        header.set_title_widget(Some(&adw::WindowTitle::new("主页", "启动上次游玩的实例")));
        let new_instance = gtk::Button::builder()
            .icon_name("list-add-symbolic")
            .tooltip_text("新建实例")
            .build();
        new_instance.add_css_class("flat");
        new_instance.set_action_name(Some("app.new-instance"));
        header.pack_start(&new_instance);

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&scroller));

        Self {
            page: adw::NavigationPage::new(&toolbar, "主页"),
            columns,
            status_column,
        }
    }
}

fn recent_column(sink: &Rc<EventSink>) -> gtk::Box {
    let column = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    column.set_valign(gtk::Align::Start);

    let stack = gtk::Stack::new();
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);

    let pages = [
        ("recent", "最近", models::recent()),
        ("favorites", "收藏", models::favorites()),
        (
            "all",
            "全部",
            models::all().iter().collect::<Vec<&'static Instance>>(),
        ),
    ];

    // Compact segmented filter built from native toggle buttons, linked like an
    // Adwaita control group. `Gtk.StackSwitcher` stretches to the widest stack
    // page, which would make this control as wide as the instance list.
    let segments = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    segments.add_css_class("fograin-segments");
    segments.set_halign(gtk::Align::End);
    let mut group: Option<gtk::ToggleButton> = None;

    for (name, label, instances) in pages {
        stack.add_named(&instance_list(instances, sink), Some(name));

        let button = gtk::ToggleButton::with_label(label);
        match &group {
            Some(first) => button.set_group(Some(first)),
            None => {
                button.set_active(true);
                group = Some(button.clone());
            }
        }
        let stack = stack.clone();
        let page = name.to_owned();
        button.connect_toggled(move |button| {
            if button.is_active() {
                stack.set_visible_child_name(&page);
            }
        });
        segments.append(&button);
    }

    column.append(&widgets::section_header("最近游玩", |header| {
        header.append(&segments);
    }));
    column.append(&stack);
    column
}

fn instance_list(instances: Vec<&'static Instance>, sink: &Rc<EventSink>) -> gtk::ListBox {
    let list = gtk::ListBox::new();
    list.add_css_class("boxed-list");
    list.set_selection_mode(gtk::SelectionMode::None);

    let mut ids = Vec::with_capacity(instances.len());
    for instance in instances {
        list.append(&widgets::instance_row(instance, RowStyle::Home));
        ids.push(instance.id);
    }

    let sink = sink.clone();
    list.connect_row_activated(move |_, row| {
        if let Some(id) = ids.get(row.index() as usize) {
            sink.emit(UiEvent::OpenInstance(id));
        }
    });
    list
}

fn status_column() -> gtk::Box {
    let column = gtk::Box::new(gtk::Orientation::Vertical, design::SECTION_GAP);
    column.set_valign(gtk::Align::Start);

    let downloads = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    let speed = gtk::Label::new(Some(models::DOWNLOAD_SPEED));
    speed.add_css_class("caption");
    speed.add_css_class("dim-label");
    downloads.append(&widgets::section_header("下载", |header| {
        header.append(&speed);
    }));
    let download_list = gtk::ListBox::new();
    download_list.add_css_class("boxed-list");
    download_list.set_selection_mode(gtk::SelectionMode::None);
    for download in models::DOWNLOADS {
        download_list.append(&widgets::download_row(download));
    }
    downloads.append(&download_list);
    column.append(&downloads);

    let environment = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    environment.append(&widgets::section_header("运行环境", |_| {}));
    let environment_list = gtk::ListBox::new();
    environment_list.add_css_class("boxed-list");
    environment_list.set_selection_mode(gtk::SelectionMode::None);

    let info = models::environment();
    environment_list.append(&widgets::milestone_row("Java", &info.java, "java"));
    environment_list.append(&widgets::milestone_row("内存分配", info.memory, "memory"));
    environment_list.append(&widgets::milestone_row("图形", info.graphics, "graphics"));
    environment_list.append(&widgets::switch_row(
        "启动后隐藏启动器",
        "游戏退出时自动恢复窗口",
        true,
    ));
    environment_list.append(&widgets::switch_row(
        "使用 GameMode",
        "由 Feral GameMode 优化 CPU 调度",
        false,
    ));

    environment.append(&environment_list);
    column.append(&environment);

    column
}
