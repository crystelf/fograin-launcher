//! Detail pane of the instance library.
//!
//! Mirrors the macOS inspector: the instance identity and launch actions on top,
//! then the values computed from the core (`instances.list`, `gameConfig.get`,
//! `app.snapshot`). Milestone-only content management stays in 游戏设置.

use std::{cell::RefCell, rc::Rc};

use adw::prelude::*;

use crate::design;
use crate::models::{self, Instance, LoaderKind};
use crate::state::AppState;
use crate::widgets::{self, InstanceTile, TileSize};

pub struct DetailPane {
    root: gtk::Stack,
    state: Rc<AppState>,
    tile: InstanceTile,
    name: gtk::Label,
    meta: gtk::Label,
    chip: gtk::Label,
    favorite: gtk::Button,
    mods: gtk::Label,
    java: gtk::Label,
    memory: gtk::Label,
    size: gtk::Label,
    play_time: gtk::Label,
    last_played: gtk::Label,
    current_id: Rc<RefCell<String>>,
}

impl DetailPane {
    #[must_use]
    pub fn new(state: &Rc<AppState>) -> Self {
        let tile = InstanceTile::new(TileSize::Detail);
        tile.widget().set_halign(gtk::Align::Center);

        let name = gtk::Label::new(None);
        name.add_css_class("title-1");
        name.set_halign(gtk::Align::Center);
        name.set_justify(gtk::Justification::Center);
        name.set_ellipsize(gtk::pango::EllipsizeMode::End);

        let meta = gtk::Label::new(None);
        meta.add_css_class("caption");
        meta.add_css_class("dim-label");
        meta.set_halign(gtk::Align::Center);

        let chip = gtk::Label::new(None);
        chip.add_css_class("fograin-chip");
        chip.set_halign(gtk::Align::Center);

        let hero = gtk::Box::new(gtk::Orientation::Vertical, 8);
        hero.add_css_class("fograin-detail-hero");
        hero.append(tile.widget());
        hero.append(&name);
        hero.append(&meta);
        hero.append(&chip);

        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
        actions.set_margin_top(design::SPACING_3);
        actions.set_halign(gtk::Align::Center);

        let play = gtk::Button::builder().tooltip_text("开始游戏").build();
        let play_content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        play_content.append(&gtk::Image::from_icon_name("media-playback-start-symbolic"));
        play_content.append(&gtk::Label::new(Some("开始游戏")));
        play.set_child(Some(&play_content));
        play.add_css_class("suggested-action");
        play.add_css_class("pill");
        actions.append(&play);

        let folder = circular_button("folder-open-symbolic", "打开实例文件夹");
        actions.append(&folder);

        let favorite = circular_button("starred-symbolic", "收藏");
        actions.append(&favorite);

        let settings = circular_button("emblem-system-symbolic", "游戏设置");
        actions.append(&settings);

        hero.append(&actions);

        let hint = gtk::Label::builder()
            .label("双击列表项进入实例管理")
            .halign(gtk::Align::Center)
            .build();
        hint.add_css_class("caption");
        hint.add_css_class("dim-label");
        hint.set_margin_top(design::SPACING_2);

        let info_group = adw::PreferencesGroup::builder().title("信息").build();
        let mods = widgets::value_label();
        info_group.add(&detail_row("模组", &mods));
        let java = widgets::value_label();
        info_group.add(&detail_row("Java", &java));
        let memory = widgets::value_label();
        info_group.add(&detail_row("内存", &memory));
        let size = widgets::value_label();
        info_group.add(&detail_row("占用空间", &size));
        let play_time = widgets::value_label();
        info_group.add(&detail_row("游玩时长", &play_time));
        let last_played = widgets::value_label();
        info_group.add(&detail_row("上次游玩", &last_played));

        let content = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
        content.set_margin_top(design::SPACING_8);
        content.set_margin_bottom(design::SPACING_10);
        content.set_margin_start(design::SPACING_6);
        content.set_margin_end(design::SPACING_6);
        content.append(&hero);
        content.append(&hint);
        content.append(&info_group);

        let clamp = adw::Clamp::new();
        clamp.set_maximum_size(460);
        clamp.set_hexpand(true);
        clamp.set_child(Some(&content));

        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&clamp)
            .build();

        let empty = adw::StatusPage::builder()
            .icon_name("view-list-symbolic")
            .title("未选择实例")
            .description("在列表中选择一个实例查看详情。")
            .build();
        empty.set_vexpand(true);

        let root = gtk::Stack::new();
        root.set_transition_type(gtk::StackTransitionType::Crossfade);
        root.add_named(&empty, Some("empty"));
        root.add_named(&scroller, Some("detail"));
        root.set_visible_child_name("empty");

        let current_id = Rc::new(RefCell::new(String::new()));
        {
            let state = Rc::clone(state);
            let current_id = Rc::clone(&current_id);
            play.connect_clicked(move |_| {
                let id = current_id.borrow().clone();
                if let Some(instance) = state.instance(&id) {
                    state.start_game(&instance);
                }
            });
        }
        {
            let state = Rc::clone(state);
            let current_id = Rc::clone(&current_id);
            folder.connect_clicked(move |_| {
                let id = current_id.borrow().clone();
                if let Some(instance) = state.instance(&id) {
                    state.open_instance_folder(&instance);
                }
            });
        }
        {
            let state = Rc::clone(state);
            let current_id = Rc::clone(&current_id);
            favorite.connect_clicked(move |_| {
                let id = current_id.borrow().clone();
                if let Some(instance) = state.instance(&id) {
                    state.toggle_favorite(&instance);
                }
            });
        }
        {
            let state = Rc::clone(state);
            let current_id = Rc::clone(&current_id);
            settings.connect_clicked(move |_| {
                let id = current_id.borrow().clone();
                if !id.is_empty() {
                    state.show_instance_settings(&id);
                }
            });
        }

        Self {
            root,
            state: Rc::clone(state),
            tile,
            name,
            meta,
            chip,
            favorite,
            mods,
            java,
            memory,
            size,
            play_time,
            last_played,
            current_id,
        }
    }

    #[must_use]
    pub fn widget(&self) -> &gtk::Widget {
        self.root.upcast_ref()
    }

    pub fn show(&self, instance: Option<&Instance>) {
        let Some(instance) = instance else {
            self.root.set_visible_child_name("empty");
            return;
        };

        self.root.set_visible_child_name("detail");
        self.tile.show(instance);
        self.name.set_label(&instance.name);
        self.meta.set_label(&instance.detail_meta());

        for loader in LoaderKind::ALL {
            self.chip.remove_css_class(loader.chip_class());
        }
        self.chip.add_css_class(instance.loader.chip_class());
        self.chip.set_label(&instance.loader_chip());

        self.current_id.replace(instance.id.clone());
        self.favorite.set_icon_name(if instance.favorite {
            "starred-symbolic"
        } else {
            "non-starred-symbolic"
        });
        self.favorite.set_tooltip_text(Some(if instance.favorite {
            "取消收藏"
        } else {
            "收藏"
        }));
        self.favorite.remove_css_class("warning");
        if instance.favorite {
            self.favorite.add_css_class("warning");
        }

        self.mods.set_label(&if instance.installed {
            instance.mods_text()
        } else {
            "未安装".to_owned()
        });

        let config = self.state.game_config();
        let snapshot = self.state.app_snapshot();
        let java = match snapshot {
            Some(snapshot) if snapshot.java_count > 0 => {
                format!("自动发现 · {} 个", snapshot.java_count)
            }
            Some(_) => "未发现 Java".to_owned(),
            None => "扫描中…".to_owned(),
        };
        self.java.set_label(&java);

        let memory = if config.memory_mb == 0 {
            "自动".to_owned()
        } else {
            format!("{} GB", (config.memory_mb / 1024).max(1))
        };
        self.memory.set_label(&memory);
        self.size
            .set_label(&models::format_size(instance.size_bytes));
        self.play_time.set_label(&instance.play_time_label());
        self.last_played.set_label(&instance.last_played_label());
    }
}

fn circular_button(icon: &str, tooltip: &str) -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name(icon)
        .tooltip_text(tooltip)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.update_property(&[gtk::accessible::Property::Label(tooltip)]);
    button
}

fn detail_row(title: &str, value: &gtk::Label) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(title).build();
    value.add_css_class("dim-label");
    row.add_suffix(value);
    row
}
