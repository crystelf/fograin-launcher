//! Small reusable widgets composed from native GTK/libadwaita pieces.
//!
//! Nothing here re-implements a control: rows, lists, switches, split buttons
//! and popovers are stock widgets, styled with Adwaita classes plus the
//! token-derived content colors from [`crate::design`].

use adw::prelude::*;
use gtk::gio;

use crate::design;
use crate::models::{self, Download, Instance};

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
            Self::Small => 32,
            Self::Row => 36,
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

/// Row density: the home list has quick actions, the library list is for
/// selecting an instance in the detail pane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RowStyle {
    Home,
    Library,
}

/// Colored monogram tile standing in for the pen's instance artwork.
pub fn tile(instance: &Instance, size: TileSize) -> gtk::Label {
    let label = gtk::Label::builder()
        .label(instance.monogram)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .build();
    label.add_css_class("fograin-tile");
    label.add_css_class(size.class());
    label.add_css_class(instance.palette.class());
    label.set_size_request(size.px(), size.px());
    label.set_tooltip_text(Some(&format!(
        "{} · {}",
        instance.name,
        instance.loader.title()
    )));
    label
}

/// Native action row with a value suffix and an optional chevron.
pub fn value_row(
    title: &str,
    value: &gtk::Label,
    badge: Option<&gtk::Label>,
    milestone: Option<&'static str>,
) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(title).build();
    if let Some(badge) = badge {
        row.add_suffix(badge);
    }
    value.add_css_class("dim-label");
    row.add_suffix(value);
    if let Some(milestone) = milestone {
        let chevron = gtk::Image::from_icon_name("go-next-symbolic");
        chevron.add_css_class("dim-label");
        row.add_suffix(&chevron);
        row.set_activatable(true);
        row.connect_activated(move |row| {
            let _ = row.activate_action("app.milestone", Some(&milestone.to_variant()));
        });
    }
    row
}

/// A compact label for detail-pane values.
pub fn value_label() -> gtk::Label {
    gtk::Label::builder().valign(gtk::Align::Center).build()
}

/// Accent-colored badge for "N 个可更新".
pub fn badge_label() -> gtk::Label {
    let label = gtk::Label::builder()
        .valign(gtk::Align::Center)
        .visible(false)
        .build();
    label.add_css_class("caption");
    label.add_css_class("accent");
    label
}

/// One instance row. Home rows carry quick actions, library rows a chevron.
pub fn instance_row(instance: &'static Instance, style: RowStyle) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    row.set_activatable(true);

    let content = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    content.set_margin_top(8);
    content.set_margin_bottom(8);
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

    let title = gtk::Label::builder()
        .label(instance.name)
        .xalign(0.0)
        .build();
    title.add_css_class("heading");
    let subtitle_text = match style {
        RowStyle::Home => instance.home_subtitle(),
        RowStyle::Library => instance.library_subtitle(),
    };
    let subtitle = gtk::Label::builder()
        .label(&subtitle_text)
        .xalign(0.0)
        .build();
    subtitle.add_css_class("caption");
    subtitle.add_css_class("dim-label");
    text.append(&title);
    text.append(&subtitle);
    content.append(&text);

    let time = gtk::Label::builder()
        .label(instance.played.label())
        .valign(gtk::Align::Center)
        .build();
    time.add_css_class("caption");
    time.add_css_class("dim-label");
    content.append(&time);

    match style {
        RowStyle::Home => {
            content.append(&play_button(instance));
            content.append(&more_button(instance));
        }
        RowStyle::Library => {
            let chevron = gtk::Image::from_icon_name("go-next-symbolic");
            chevron.add_css_class("dim-label");
            content.append(&chevron);
        }
    }

    row.set_child(Some(&content));
    row.update_property(&[gtk::accessible::Property::Label(&format!(
        "{}，{}",
        instance.name, subtitle_text
    ))]);
    row
}

/// "开始游戏" button. Gtk.Button shows either a label or an icon, so the icon
/// and label live in a native box child instead of using both properties.
pub fn start_button(instance: &Instance) -> gtk::Button {
    let content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    content.append(&gtk::Image::from_icon_name("media-playback-start-symbolic"));
    content.append(&gtk::Label::new(Some("开始游戏")));

    let button = gtk::Button::builder()
        .tooltip_text(format!("开始游戏 · {}", instance.name))
        .build();
    button.set_child(Some(&content));
    button.add_css_class("suggested-action");
    button.set_action_name(Some("app.launch-instance"));
    button.set_action_target_value(Some(&instance.id.to_variant()));
    button.update_property(&[gtk::accessible::Property::Label(&format!(
        "开始游戏 · {}",
        instance.name
    ))]);
    button
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

fn more_button(instance: &Instance) -> gtk::MenuButton {
    let menu = gtk::gio::Menu::new();
    menu.append_item(&menu_item(
        "在实例库中查看",
        "app.open-instance",
        instance.id,
    ));
    menu.append_item(&menu_item("启动游戏", "app.launch-instance", instance.id));
    menu.append_item(&menu_item(
        "实例设置…",
        "app.instance-settings",
        instance.id,
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

/// The hero banner from the pen home screen, built from native overlays,
/// buttons and a split "start game" control.
pub fn launch_banner(instance: &'static Instance) -> gtk::Overlay {
    let overlay = gtk::Overlay::new();
    overlay.add_css_class("fograin-hero");
    overlay.set_height_request(280);

    if let Some(texture) = design::hero_texture() {
        let picture = gtk::Picture::for_paintable(&texture);
        picture.set_content_fit(gtk::ContentFit::Cover);
        picture.set_can_shrink(true);
        picture.add_css_class("fograin-hero-image");
        overlay.set_child(Some(&picture));
    }

    let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
    content.set_hexpand(true);
    content.set_vexpand(true);

    let top = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    top.set_margin_top(design::SPACING_8);
    top.set_margin_start(design::SPACING_8);
    top.set_margin_end(design::SPACING_8);
    top.append(&status_pill());
    content.append(&top);

    let spacer = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    spacer.set_vexpand(true);
    content.append(&spacer);

    content.append(&banner_bar(instance));
    overlay.add_overlay(&content);
    overlay
}

fn status_pill() -> gtk::Box {
    let pill = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    pill.add_css_class("fograin-status-pill");
    pill.set_halign(gtk::Align::Start);

    let dot = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    dot.add_css_class("fograin-status-dot");
    dot.set_size_request(8, 8);
    dot.set_valign(gtk::Align::Center);
    pill.append(&dot);

    let label = gtk::Label::new(Some("已就绪 · 资源校验完成"));
    label.add_css_class("caption");
    label.add_css_class("fograin-on-image");
    pill.append(&label);
    pill
}

fn banner_bar(instance: &'static Instance) -> gtk::Box {
    let bar = gtk::Box::new(gtk::Orientation::Horizontal, 16);
    bar.add_css_class("fograin-hero-bar");
    bar.set_valign(gtk::Align::End);

    let info = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    info.set_hexpand(true);
    info.append(&tile(instance, TileSize::Banner));

    let text = gtk::Box::new(gtk::Orientation::Vertical, 2);
    text.set_valign(gtk::Align::Center);
    let name = gtk::Label::builder()
        .label(instance.name)
        .xalign(0.0)
        .build();
    name.add_css_class("fograin-hero-title");
    let meta = gtk::Label::builder()
        .label(instance.banner_meta())
        .xalign(0.0)
        .build();
    meta.add_css_class("caption");
    meta.add_css_class("fograin-on-image-dim");
    text.append(&name);
    text.append(&meta);
    info.append(&text);
    bar.append(&info);

    let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    actions.set_valign(gtk::Align::Center);

    let settings = gtk::Button::builder()
        .icon_name("emblem-system-symbolic")
        .tooltip_text("实例设置")
        .build();
    settings.add_css_class("flat");
    settings.add_css_class("circular");
    settings.add_css_class("fograin-on-image");
    settings.add_css_class("fograin-hero-button");
    settings.update_property(&[gtk::accessible::Property::Label("实例设置")]);
    settings.set_action_name(Some("app.instance-settings"));
    settings.set_action_target_value(Some(&instance.id.to_variant()));
    actions.append(&settings);

    let menu = gio::Menu::new();
    menu.append_item(&menu_item(
        "启动设置…",
        "app.instance-settings",
        instance.id,
    ));

    // One native pill with the play action on the left and the launch menu on
    // the right, matching the pen's split button instead of two glued buttons.
    let play = adw::SplitButton::builder()
        .menu_model(&menu)
        .dropdown_tooltip("更多启动选项")
        .build();
    let content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    content.append(&gtk::Image::from_icon_name("media-playback-start-symbolic"));
    content.append(&gtk::Label::new(Some("开始游戏")));
    play.set_child(Some(&content));
    play.add_css_class("suggested-action");
    play.add_css_class("fograin-split");
    play.set_action_name(Some("app.launch-instance"));
    play.set_action_target_value(Some(&instance.id.to_variant()));
    play.set_tooltip_text(Some(&format!("开始游戏 · {}", instance.name)));
    play.update_property(&[gtk::accessible::Property::Label(&format!(
        "开始游戏 · {}",
        instance.name
    ))]);
    actions.append(&play);
    bar.append(&actions);
    bar
}

/// One download card with a native progress bar.
pub fn download_row(download: &Download) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    row.set_activatable(false);
    row.set_selectable(false);

    let content = gtk::Box::new(gtk::Orientation::Vertical, 6);
    content.set_margin_top(12);
    content.set_margin_bottom(12);
    content.set_margin_start(14);
    content.set_margin_end(14);

    let top = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let name = gtk::Label::builder()
        .label(download.title)
        .xalign(0.0)
        .hexpand(true)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    name.add_css_class("fograin-row-title");
    let detail = gtk::Label::builder()
        .label(download.detail)
        .xalign(1.0)
        .build();
    detail.add_css_class("caption");
    detail.add_css_class("dim-label");
    top.append(&name);
    top.append(&detail);

    let progress = gtk::ProgressBar::new();
    progress.set_fraction(download.fraction);
    progress.set_show_text(false);
    progress.add_css_class("fograin-thin-progress");

    content.append(&top);
    content.append(&progress);
    row.set_child(Some(&content));
    row
}

/// Settings-style row that opens the milestone copy for a not-yet-wired feature.
pub fn milestone_row(title: &str, subtitle: &str, milestone: &'static str) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .title(title)
        .subtitle(subtitle)
        .activatable(true)
        .build();
    let chevron = gtk::Image::from_icon_name("go-next-symbolic");
    chevron.add_css_class("dim-label");
    row.add_suffix(&chevron);
    row.connect_activated(move |row| {
        let _ = row.activate_action("app.milestone", Some(&milestone.to_variant()));
    });
    row
}

/// Native switch row used by the home status column.
pub fn switch_row(title: &str, subtitle: &str, active: bool) -> adw::SwitchRow {
    adw::SwitchRow::builder()
        .title(title)
        .subtitle(subtitle)
        .active(active)
        .build()
}

/// Total size label for the library header.
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
