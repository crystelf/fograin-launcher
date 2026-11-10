//! Small reusable widgets composed from native GTK/libadwaita pieces.
//!
//! Nothing here re-implements a control: rows, lists, split buttons, progress
//! bars, status pages and dialogs are stock widgets, styled with Adwaita
//! classes plus the token-derived content colors from [`crate::design`].

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::gio;

use crate::design;
use crate::models::{self, DownloadRow, Instance};

/// Tile sizes used by the banner, list rows and detail hero.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TileSize {
    Small,
    Row,
    Banner,
    Detail,
}

impl TileSize {
    const fn px(self) -> i32 {
        match self {
            Self::Small => 30,
            Self::Row => 34,
            Self::Banner => 56,
            Self::Detail => 88,
        }
    }

    const fn class(self) -> &'static str {
        match self {
            Self::Small => "fograin-tile-small",
            Self::Row => "fograin-tile-row",
            Self::Banner => "fograin-tile-banner",
            Self::Detail => "fograin-tile-detail",
        }
    }
}

/// Row density: the home list has quick actions, the library list selects the
/// preview pane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RowStyle {
    Home,
    Library,
}

/// Reusable instance artwork whose content and palette can be swapped in place.
pub struct InstanceTile {
    label: gtk::Label,
    size: TileSize,
}

impl InstanceTile {
    #[must_use]
    pub fn new(size: TileSize) -> Self {
        let label = gtk::Label::builder()
            .halign(gtk::Align::Center)
            .valign(gtk::Align::Center)
            .build();
        label.add_css_class("fograin-tile");
        label.add_css_class(size.class());
        label.set_size_request(size.px(), size.px());
        Self { label, size }
    }

    #[must_use]
    pub fn widget(&self) -> &gtk::Label {
        &self.label
    }

    pub fn show(&self, instance: &Instance) {
        self.label.set_label(&instance.monogram());
        for palette in models::Palette::ALL {
            self.label.remove_css_class(palette.class());
        }
        self.label.add_css_class(instance.palette().class());
        self.label.set_tooltip_text(Some(&format!(
            "{} · {}",
            instance.name,
            instance.loader.title()
        )));
        self.label.set_size_request(self.size.px(), self.size.px());
    }
}

/// Colored monogram tile standing in for the pen's instance artwork.
#[must_use]
pub fn tile(instance: &Instance, size: TileSize) -> gtk::Label {
    let tile = InstanceTile::new(size);
    tile.show(instance);
    tile.widget().clone()
}

/// A compact label for detail-pane values.
#[must_use]
pub fn value_label() -> gtk::Label {
    gtk::Label::builder().valign(gtk::Align::Center).build()
}

/// One instance row. Home rows carry quick actions, library rows a menu.
#[must_use]
pub fn instance_row(instance: &Instance, style: RowStyle) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    row.set_activatable(true);

    let content = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    content.set_margin_top(7);
    content.set_margin_bottom(7);
    content.set_margin_start(10);
    content.set_margin_end(10);

    let size = match style {
        RowStyle::Home => TileSize::Row,
        RowStyle::Library => TileSize::Small,
    };
    content.append(&tile(instance, size));

    let text = gtk::Box::new(gtk::Orientation::Vertical, 1);
    text.set_hexpand(true);
    text.set_valign(gtk::Align::Center);

    let title_row = gtk::Box::new(gtk::Orientation::Horizontal, 4);
    let title = gtk::Label::builder()
        .label(&instance.name)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    title.add_css_class("heading");
    title_row.append(&title);
    if instance.favorite {
        title_row.append(&favorite_star());
    }
    text.append(&title_row);

    let subtitle_text = match style {
        RowStyle::Home => instance.home_subtitle(),
        RowStyle::Library => instance.library_subtitle(),
    };
    let subtitle = gtk::Label::builder()
        .label(&subtitle_text)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    subtitle.add_css_class("caption");
    subtitle.add_css_class("dim-label");
    text.append(&subtitle);
    content.append(&text);

    let time = gtk::Label::builder()
        .label(instance.last_played_label())
        .valign(gtk::Align::Center)
        .build();
    time.add_css_class("caption");
    time.add_css_class("dim-label");
    content.append(&time);

    match style {
        RowStyle::Home => {
            content.append(&play_button(instance));
            content.append(&more_button(instance, false));
        }
        RowStyle::Library => {
            content.append(&more_button(instance, true));
        }
    }

    row.set_child(Some(&content));
    row.update_property(&[gtk::accessible::Property::Label(&format!(
        "{}，{}",
        instance.name, subtitle_text
    ))]);
    row
}

#[must_use]
fn favorite_star() -> gtk::Image {
    let star = gtk::Image::from_icon_name("starred-symbolic");
    star.add_css_class("warning");
    star.set_pixel_size(12);
    star.set_tooltip_text(Some("已收藏"));
    star
}

fn play_button(instance: &Instance) -> gtk::Button {
    let button = gtk::Button::builder()
        .icon_name("media-playback-start-symbolic")
        .tooltip_text(format!("开始游戏 · {}", instance.name))
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.set_action_name(Some("app.launch-instance"));
    button.set_action_target_value(Some(&instance.id.to_variant()));
    button.update_property(&[gtk::accessible::Property::Label(&format!(
        "开始游戏 · {}",
        instance.name
    ))]);
    button
}

fn more_button(instance: &Instance, library: bool) -> gtk::MenuButton {
    let menu = gio::Menu::new();
    if !library {
        menu.append_item(&menu_item("开始游戏", "app.launch-instance", &instance.id));
    }
    menu.append_item(&menu_item(
        "在实例库中查看",
        "app.open-instance",
        &instance.id,
    ));
    menu.append_item(&menu_item(
        if instance.favorite {
            "取消收藏"
        } else {
            "收藏"
        },
        "app.toggle-favorite",
        &instance.id,
    ));
    menu.append_item(&menu_item(
        "打开实例文件夹",
        "app.open-instance-folder",
        &instance.id,
    ));
    menu.append_item(&menu_item(
        "实例设置…",
        "app.instance-settings",
        &instance.id,
    ));

    let button = gtk::MenuButton::builder()
        .icon_name("view-more-symbolic")
        .tooltip_text("更多操作")
        .menu_model(&menu)
        .valign(gtk::Align::Center)
        .build();
    button.add_css_class("flat");
    button.add_css_class("circular");
    button.update_property(&[gtk::accessible::Property::Label("更多操作")]);
    button
}

/// Menu item bound to an action plus a string target.
#[must_use]
pub fn menu_item(label: &str, action: &str, target: &str) -> gio::MenuItem {
    let item = gio::MenuItem::new(Some(label), None);
    item.set_action_and_target_value(Some(action), Some(&target.to_variant()));
    item
}

/// Section heading with an optional trailing control (stack switcher, counter).
pub fn section_header(title: &str, build_trailing: impl FnOnce(&gtk::Box)) -> gtk::Box {
    let header = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    header.set_valign(gtk::Align::Center);
    let label = gtk::Label::builder()
        .label(title)
        .xalign(0.0)
        .hexpand(true)
        .build();
    label.add_css_class("fograin-section-title");
    header.append(&label);
    build_trailing(&header);
    header
}

/// Library group header: title and instance count, left-aligned like the pen.
#[must_use]
pub fn group_header(title: &str, count: usize) -> gtk::Box {
    let header = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    header.set_halign(gtk::Align::Start);
    let title = gtk::Label::builder().label(title).build();
    title.add_css_class("fograin-section-title");
    let count = gtk::Label::builder().label(count.to_string()).build();
    count.add_css_class("caption");
    count.add_css_class("dim-label");
    count.set_valign(gtk::Align::End);
    header.append(&title);
    header.append(&count);
    header
}

/// The hero banner from the home screen: native overlay, split launch button and
/// a status pill that reports install/session state.
pub struct HeroCard {
    root: gtk::Overlay,
    tile: InstanceTile,
    name: gtk::Label,
    meta: gtk::Label,
    status_label: gtk::Label,
    status_dot: gtk::Box,
    play: adw::SplitButton,
    play_icon: gtk::Image,
    play_label: gtk::Label,
    running: Rc<Cell<bool>>,
}

impl HeroCard {
    /// `on_play` / `on_stop` and `on_settings` receive the currently displayed
    /// instance id. Clicks are handled directly instead of through action
    /// targets so the hero can swap instances after the window is mapped
    /// without rebinding actions. The primary button becomes 停止游戏 while a
    /// session is running, keeping stop reachable from the primary action.
    #[must_use]
    pub fn new(
        on_play: impl Fn(&str) + 'static,
        on_stop: impl Fn() + 'static,
        on_settings: impl Fn(&str) + 'static,
    ) -> Self {
        let overlay = gtk::Overlay::new();
        overlay.add_css_class("fograin-hero");
        overlay.set_height_request(280);

        if let Some(texture) = design::hero_texture() {
            let picture = gtk::Picture::for_paintable(&texture);
            picture.set_content_fit(gtk::ContentFit::Cover);
            picture.set_can_shrink(true);
            // A GtkPicture reports its texture's natural height, which would
            // stretch the hero to ~480 px. A fixed-height scroller caps the
            // measured height at the pen's 280 px while still letting the image
            // cover the full width.
            let image = gtk::ScrolledWindow::builder()
                .hscrollbar_policy(gtk::PolicyType::Never)
                .vscrollbar_policy(gtk::PolicyType::Never)
                .min_content_height(280)
                .max_content_height(280)
                .child(&picture)
                .build();
            image.add_css_class("fograin-hero-image");
            overlay.set_child(Some(&image));
        }

        let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
        content.set_hexpand(true);
        content.set_vexpand(true);

        let top = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        top.set_margin_top(design::SPACING_8);
        top.set_margin_start(design::SPACING_8);
        top.set_margin_end(design::SPACING_8);
        let status_dot = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        status_dot.add_css_class("fograin-status-dot");
        status_dot.set_size_request(8, 8);
        status_dot.set_valign(gtk::Align::Center);
        let status_label = gtk::Label::new(None);
        status_label.add_css_class("caption");
        status_label.add_css_class("fograin-on-image");
        let pill = gtk::Box::new(gtk::Orientation::Horizontal, 8);
        pill.add_css_class("fograin-status-pill");
        pill.set_halign(gtk::Align::Start);
        pill.append(&status_dot);
        pill.append(&status_label);
        top.append(&pill);
        content.append(&top);

        let spacer = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        spacer.set_vexpand(true);
        content.append(&spacer);

        let bar = gtk::Box::new(gtk::Orientation::Horizontal, 16);
        bar.add_css_class("fograin-hero-bar");
        bar.set_valign(gtk::Align::End);

        let info = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        info.set_hexpand(true);
        let tile = InstanceTile::new(TileSize::Banner);
        info.append(tile.widget());

        let text = gtk::Box::new(gtk::Orientation::Vertical, 2);
        text.set_valign(gtk::Align::Center);
        let name = gtk::Label::builder().xalign(0.0).build();
        name.add_css_class("fograin-hero-title");
        let meta = gtk::Label::builder().xalign(0.0).build();
        meta.add_css_class("caption");
        meta.add_css_class("fograin-on-image-dim");
        text.append(&name);
        text.append(&meta);
        info.append(&text);
        bar.append(&info);

        let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
        // Sit on the hero bar's baseline so the gear + 开始游戏 cluster shares
        // its lower edge with the instance tile on the left.
        actions.set_valign(gtk::Align::End);

        let settings = gtk::Button::builder()
            .icon_name("emblem-system-symbolic")
            .tooltip_text("游戏设置")
            .build();
        settings.add_css_class("flat");
        settings.add_css_class("circular");
        settings.add_css_class("fograin-on-image");
        settings.add_css_class("fograin-hero-button");
        settings.update_property(&[gtk::accessible::Property::Label("游戏设置")]);
        actions.append(&settings);

        let play = adw::SplitButton::builder()
            .dropdown_tooltip("更多启动选项")
            .build();
        let play_content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        let play_icon = gtk::Image::from_icon_name("media-playback-start-symbolic");
        let play_label = gtk::Label::new(Some("开始游戏"));
        play_content.append(&play_icon);
        play_content.append(&play_label);
        play.set_child(Some(&play_content));
        play.add_css_class("suggested-action");
        play.add_css_class("fograin-split");
        actions.append(&play);
        bar.append(&actions);

        let current_id = std::rc::Rc::new(std::cell::RefCell::new(String::new()));
        let running = Rc::new(Cell::new(false));
        {
            let current_id = std::rc::Rc::clone(&current_id);
            let running = Rc::clone(&running);
            play.connect_clicked(move |_| {
                if running.get() {
                    on_stop();
                    return;
                }
                let id = current_id.borrow().clone();
                if !id.is_empty() {
                    on_play(&id);
                }
            });
        }
        {
            let current_id = std::rc::Rc::clone(&current_id);
            settings.connect_clicked(move |_| {
                let id = current_id.borrow().clone();
                if !id.is_empty() {
                    on_settings(&id);
                }
            });
        }

        content.append(&bar);
        overlay.add_overlay(&content);

        Self {
            root: overlay,
            tile,
            name,
            meta,
            status_label,
            status_dot,
            play,
            play_icon,
            play_label,
            running,
        }
    }

    #[must_use]
    pub fn widget(&self) -> &gtk::Overlay {
        &self.root
    }

    pub fn show(&self, instance: &Instance, downloading: bool, running: bool, loading: bool) {
        self.tile.show(instance);
        self.name.set_label(&instance.name);
        self.meta.set_label(&instance.banner_meta());
        self.running.set(running);

        if running {
            self.play_icon
                .set_icon_name(Some("media-playback-stop-symbolic"));
            self.play_label.set_label("停止游戏");
            self.play.remove_css_class("suggested-action");
            self.play.add_css_class("fograin-stop-action");
            self.play
                .set_tooltip_text(Some(&format!("停止游戏 · {}", instance.name)));
        } else {
            self.play_icon
                .set_icon_name(Some("media-playback-start-symbolic"));
            self.play_label.set_label(if loading {
                "启动中…"
            } else {
                "开始游戏"
            });
            self.play.add_css_class("suggested-action");
            self.play
                .set_tooltip_text(Some(&format!("开始游戏 · {}", instance.name)));
        }
        self.play.set_sensitive(!loading);
        let menu = gio::Menu::new();
        if running {
            menu.append(Some("停止游戏"), Some("app.stop-session"));
        }
        menu.append_item(&menu_item(
            "游戏设置…",
            "app.instance-settings",
            &instance.id,
        ));
        self.play.set_menu_model(Some(&menu));

        let (status, state) = if loading {
            ("正在启动…".to_owned(), "info")
        } else if running {
            ("游戏运行中".to_owned(), "ok")
        } else if downloading {
            ("正在下载 · 补全游戏资源".to_owned(), "info")
        } else if !instance.installed {
            ("未安装 · 需要补全游戏资源".to_owned(), "warn")
        } else if instance.play_seconds >= 60 {
            (
                format!("已安装 · 游玩 {}", instance.play_time_label()),
                "ok",
            )
        } else {
            ("已安装 · 可以直接启动".to_owned(), "ok")
        };
        self.status_label.set_label(&status);
        for class in [
            "fograin-status-ok",
            "fograin-status-warn",
            "fograin-status-info",
        ] {
            self.status_dot.remove_css_class(class);
        }
        self.status_dot.add_css_class(match state {
            "warn" => "fograin-status-warn",
            "info" => "fograin-status-info",
            _ => "fograin-status-ok",
        });
    }
}

/// Scheme-style instance item: tile (or a native spinner while launching),
/// name and version; clicking opens 游戏设置.
pub struct InstanceStatusButton {
    pub root: gtk::Button,
    tile: InstanceTile,
    spinner: gtk::Spinner,
    name: gtk::Label,
    subtitle: gtk::Label,
}

impl InstanceStatusButton {
    #[must_use]
    pub fn new() -> Self {
        let root = gtk::Button::builder().tooltip_text("游戏设置").build();
        root.add_css_class("flat");
        root.add_css_class("fograin-status-button");

        let content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        let tile = InstanceTile::new(TileSize::Small);
        tile.widget().set_size_request(22, 22);
        content.append(tile.widget());

        let spinner = gtk::Spinner::new();
        spinner.set_size_request(18, 18);
        spinner.set_visible(false);
        content.append(&spinner);

        let text = gtk::Box::new(gtk::Orientation::Vertical, 0);
        text.set_valign(gtk::Align::Center);
        let name = gtk::Label::builder().xalign(0.0).build();
        name.add_css_class("fograin-status-title");
        let subtitle = gtk::Label::builder().xalign(0.0).build();
        subtitle.add_css_class("caption");
        subtitle.add_css_class("dim-label");
        text.append(&name);
        text.append(&subtitle);
        content.append(&text);

        root.set_child(Some(&content));
        root.update_property(&[gtk::accessible::Property::Label("游戏设置")]);

        Self {
            root,
            tile,
            spinner,
            name,
            subtitle,
        }
    }

    pub fn show(&self, instance: Option<&Instance>, loading: bool) {
        match instance {
            Some(instance) => {
                self.tile.widget().set_visible(!loading);
                self.spinner.set_visible(loading);
                if loading {
                    self.spinner.start();
                } else {
                    self.spinner.stop();
                }
                self.name.set_label(&instance.name);
                if instance.loader_version.is_empty() {
                    self.subtitle
                        .set_label(&format!("Java 版 {}", instance.minecraft));
                } else {
                    self.subtitle.set_label(&format!(
                        "{} · {}",
                        instance.minecraft,
                        instance.loader.title()
                    ));
                }
                self.root.set_sensitive(true);
            }
            None => {
                self.tile.widget().set_visible(true);
                self.spinner.stop();
                self.spinner.set_visible(false);
                self.name.set_label("未选择实例");
                self.subtitle.set_label("在实例库中添加一个");
                self.root.set_sensitive(false);
            }
        }
    }
}

impl Default for InstanceStatusButton {
    fn default() -> Self {
        Self::new()
    }
}

/// One download row with a native progress bar.
#[must_use]
pub fn download_row(download: &DownloadRow) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    row.set_activatable(false);
    row.set_selectable(false);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 6);
    content.set_margin_top(10);
    content.set_margin_bottom(10);
    content.set_margin_start(12);
    content.set_margin_end(12);

    let top = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let name = gtk::Label::builder()
        .label(&download.title)
        .xalign(0.0)
        .hexpand(true)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    name.add_css_class("fograin-row-title");
    let state_text = if download.speed > 0.0 {
        format!("{} · {}", download.state_label(), download.speed_text())
    } else {
        download.state_label()
    };
    let state = gtk::Label::builder().label(state_text).xalign(1.0).build();
    state.add_css_class("caption");
    state.add_css_class("dim-label");
    top.append(&name);
    top.append(&state);

    let progress = gtk::ProgressBar::new();
    progress.set_fraction(download.fraction);
    progress.set_show_text(false);
    progress.add_css_class("fograin-thin-progress");
    progress.update_property(&[gtk::accessible::Property::Label(&download.title)]);

    content.append(&top);
    content.append(&progress);
    row.set_child(Some(&content));
    row
}

/// Key/value row for the home runtime card and the version detail pane.
#[must_use]
pub fn key_value_row(label: &str, value: &str, checkmark: bool) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(label).build();
    if checkmark {
        let icon = gtk::Image::from_icon_name("object-select-symbolic");
        icon.add_css_class("success");
        row.add_prefix(&icon);
    }
    let value = gtk::Label::builder()
        .label(value)
        .valign(gtk::Align::Center)
        .build();
    value.add_css_class("dim-label");
    value.set_ellipsize(gtk::pango::EllipsizeMode::Middle);
    row.add_suffix(&value);
    row
}

/// Empty queue copy.
#[must_use]
pub fn empty_label(text: &str) -> gtk::Label {
    let label = gtk::Label::builder()
        .label(text)
        .xalign(0.0)
        .wrap(true)
        .build();
    label.add_css_class("caption");
    label.add_css_class("dim-label");
    label
}

/// Accent milestone chip, matching the macOS `MilestoneChip`.
#[must_use]
pub fn milestone_chip(text: &str) -> gtk::Label {
    let label = gtk::Label::new(Some(text));
    label.add_css_class("fograin-milestone-chip");
    // `Adw.ActionRow` allocates suffixes the full row height; centering keeps
    // the capsule at its natural size instead of stretching into a blue disc.
    label.set_valign(gtk::Align::Center);
    label.set_halign(gtk::Align::Center);
    label
}

/// Total size label for the library header.
#[must_use]
pub fn library_summary(query_matches: usize, bytes: u64) -> String {
    format!("{query_matches} 个实例 · {}", models::format_size(bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn library_summary_uses_the_shared_size_format() {
        assert_eq!(library_summary(9, 17_870_000_000), "9 个实例 · 17.9 GB");
    }
}
