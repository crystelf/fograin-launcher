//! Detail pane of the instance library.
//!
//! Mirrors the pen's right column: hero with the instance tile and launch
//! actions, then "内容" and "信息" groups built from `Adw.PreferencesGroup`.

use adw::prelude::*;

use crate::design;
use crate::models::{self, Instance, LoaderKind, Palette};
use crate::widgets::{self, TileSize};

pub struct DetailPane {
    root: gtk::Widget,
    tile: gtk::Label,
    name: gtk::Label,
    meta: gtk::Label,
    chip: gtk::Label,
    play: gtk::Button,
    settings: gtk::Button,
    mods_value: gtk::Label,
    mods_badge: gtk::Label,
    resource_pack: gtk::Label,
    shader: gtk::Label,
    worlds: gtk::Label,
    java: gtk::Label,
    size: gtk::Label,
    play_time: gtk::Label,
}

impl DetailPane {
    pub fn new() -> Self {
        let instance = models::current();

        let hero = gtk::Box::new(gtk::Orientation::Vertical, 8);
        hero.add_css_class("fograin-detail-hero");

        let tile = widgets::tile(instance, TileSize::Detail);
        tile.set_halign(gtk::Align::Center);
        hero.append(&tile);

        let name = gtk::Label::new(Some(instance.name));
        name.add_css_class("title-1");
        name.set_halign(gtk::Align::Center);
        name.set_justify(gtk::Justification::Center);
        hero.append(&name);

        let meta = gtk::Label::new(Some(&instance.detail_meta()));
        meta.add_css_class("caption");
        meta.add_css_class("dim-label");
        meta.set_halign(gtk::Align::Center);
        hero.append(&meta);

        let chip = gtk::Label::new(Some(&instance.loader_chip()));
        chip.add_css_class("fograin-chip");
        chip.add_css_class(instance.loader.chip_class());
        chip.set_halign(gtk::Align::Center);
        hero.append(&chip);

        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
        actions.set_margin_top(design::SPACING_3);

        let play = widgets::start_button(instance);
        play.set_hexpand(true);
        play.add_css_class("pill");
        actions.append(&play);

        actions.append(&icon_button(
            "folder-open-symbolic",
            "打开实例目录",
            "app.milestone",
            "instance-folder",
        ));

        let settings = icon_button(
            "emblem-system-symbolic",
            "实例设置",
            "app.instance-settings",
            instance.id,
        );
        actions.append(&settings);
        hero.append(&actions);

        let content_group = adw::PreferencesGroup::builder().title("内容").build();
        let mods_value = widgets::value_label();
        let mods_badge = widgets::badge_label();
        content_group.add(&widgets::value_row(
            "模组",
            &mods_value,
            Some(&mods_badge),
            Some("mods"),
        ));
        let resource_pack = widgets::value_label();
        content_group.add(&widgets::value_row(
            "资源包",
            &resource_pack,
            None,
            Some("resourcepacks"),
        ));
        let shader = widgets::value_label();
        content_group.add(&widgets::value_row("光影", &shader, None, Some("shaders")));
        let worlds = widgets::value_label();
        content_group.add(&widgets::value_row("世界", &worlds, None, Some("worlds")));

        let info_group = adw::PreferencesGroup::builder().title("信息").build();
        let java = widgets::value_label();
        info_group.add(&widgets::value_row("Java", &java, None, None));
        let size = widgets::value_label();
        info_group.add(&widgets::value_row("占用空间", &size, None, None));
        let play_time = widgets::value_label();
        info_group.add(&widgets::value_row("游玩时长", &play_time, None, None));

        let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
        content.set_margin_top(design::SPACING_8);
        content.set_margin_bottom(design::SPACING_10);
        content.set_margin_start(design::SPACING_6);
        content.set_margin_end(design::SPACING_6);
        content.append(&hero);
        content.append(&content_group);
        content.append(&info_group);

        let clamp = adw::Clamp::new();
        clamp.set_maximum_size(460);
        clamp.set_hexpand(true);
        clamp.set_child(Some(&content));

        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&clamp)
            .build();

        let pane = Self {
            root: scroller.upcast(),
            tile,
            name,
            meta,
            chip,
            play,
            settings,
            mods_value,
            mods_badge,
            resource_pack,
            shader,
            worlds,
            java,
            size,
            play_time,
        };
        pane.show(instance);
        pane
    }

    pub fn widget(&self) -> &gtk::Widget {
        &self.root
    }

    pub fn show(&self, instance: &'static Instance) {
        self.tile.set_label(instance.monogram);
        for palette in Palette::ALL {
            self.tile.remove_css_class(palette.class());
        }
        self.tile.add_css_class(instance.palette.class());

        self.name.set_label(instance.name);
        self.meta.set_label(&instance.detail_meta());

        for loader in LoaderKind::ALL {
            self.chip.remove_css_class(loader.chip_class());
        }
        self.chip.add_css_class(instance.loader.chip_class());
        self.chip.set_label(&instance.loader_chip());

        self.play
            .set_action_target_value(Some(&instance.id.to_variant()));
        self.settings
            .set_action_target_value(Some(&instance.id.to_variant()));

        self.mods_value.set_label(&instance.mods_label());
        self.mods_badge.set_label(&instance.mods_update_label());
        self.mods_badge.set_visible(instance.updates > 0);
        self.resource_pack.set_label(instance.resource_pack_label());
        self.shader.set_label(instance.shader_label());
        self.worlds.set_label(&instance.worlds_label());
        self.java.set_label(instance.java);
        self.size
            .set_label(&models::format_size(instance.size_bytes));
        self.play_time.set_label(&instance.play_time_label());
    }
}

fn icon_button(icon: &str, tooltip: &str, action: &str, target: &str) -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name(icon)
        .tooltip_text(tooltip)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.set_action_name(Some(action));
    button.set_action_target_value(Some(&target.to_variant()));
    button.update_property(&[gtk::accessible::Property::Label(tooltip)]);
    button
}
