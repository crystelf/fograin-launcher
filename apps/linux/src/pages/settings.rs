//! Settings page for the parts that already work in M1: appearance and paths.
//!
//! Everything else stays a native `Adw.PreferencesGroup` placeholder rather than
//! a fake control; the milestone copy is honest about when it lands.

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use fg_core::{ThemePreference, config, paths};

use crate::events::{EventSink, UiEvent};
use crate::platform::theme::{self, ThemeController};

pub struct SettingsPage {
    pub page: adw::NavigationPage,
    theme_row: adw::ComboRow,
    syncing: Rc<Cell<bool>>,
}

impl SettingsPage {
    pub fn new(controller: &Rc<ThemeController>, sink: &Rc<EventSink>) -> Self {
        let preferences = adw::PreferencesPage::new();

        let appearance = adw::PreferencesGroup::builder().title("外观").build();
        let theme_row = adw::ComboRow::builder()
            .title("主题")
            .subtitle("「跟随系统」由 Adw.StyleManager 决定")
            .build();
        let model = gtk::StringList::new(&theme::LABELS);
        theme_row.set_model(Some(&model));
        theme_row.set_selected(theme::index(controller.preference()));

        let syncing = Rc::new(Cell::new(false));
        {
            let controller = controller.clone();
            let sink = sink.clone();
            let syncing = syncing.clone();
            theme_row.connect_selected_notify(move |row| {
                if syncing.get() {
                    return;
                }
                let preference = theme::from_index(row.selected());
                if preference != controller.preference() {
                    sink.emit(UiEvent::ThemeChanged(preference));
                }
            });
        }
        appearance.add(&theme_row);
        preferences.add(&appearance);

        let data = adw::PreferencesGroup::builder().title("数据").build();
        let root = paths::data_root();
        let data_root = adw::ActionRow::builder()
            .title("数据目录")
            .subtitle(root.display().to_string())
            .build();
        data_root.set_subtitle_selectable(true);
        let open = gtk::Button::builder()
            .icon_name("folder-open-symbolic")
            .tooltip_text("打开数据目录")
            .valign(gtk::Align::Center)
            .build();
        open.add_css_class("flat");
        open.add_css_class("circular");
        open.set_action_name(Some("app.open-data-dir"));
        data_root.add_suffix(&open);
        data.add(&data_root);

        let config_row = adw::ActionRow::builder()
            .title("全局配置")
            .subtitle(config::config_path(&root).display().to_string())
            .build();
        config_row.set_subtitle_selectable(true);
        data.add(&config_row);
        preferences.add(&data);

        let about = adw::PreferencesGroup::builder().title("关于").build();
        let about_row = adw::ActionRow::builder()
            .title("雾雨启动器")
            .subtitle(format!(
                "版本 {} · GTK4 + libadwaita",
                env!("CARGO_PKG_VERSION")
            ))
            .activatable(true)
            .build();
        let chevron = gtk::Image::from_icon_name("go-next-symbolic");
        chevron.add_css_class("dim-label");
        about_row.add_suffix(&chevron);
        about_row.connect_activated(|row| {
            let _ = row.activate_action("app.show-about", None);
        });
        about.add(&about_row);

        let tokens_row = adw::ActionRow::builder()
            .title("设计令牌")
            .subtitle("design/tokens.json · 构建时生成")
            .build();
        about.add(&tokens_row);
        preferences.add(&about);

        let header = adw::HeaderBar::new();
        header.set_title_widget(Some(&adw::WindowTitle::new("设置", "外观与全局设置")));

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&preferences));

        Self {
            page: adw::NavigationPage::new(&toolbar, "设置"),
            theme_row,
            syncing,
        }
    }

    /// Mirrors a theme change made elsewhere (primary menu, accelerator).
    pub fn sync_theme(&self, preference: ThemePreference) {
        self.syncing.set(true);
        self.theme_row.set_selected(theme::index(preference));
        self.syncing.set(false);
    }
}
