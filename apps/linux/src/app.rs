//! The window shell: sidebar navigation, content pages, actions and menu.
//!
//! Every page is an `Adw.NavigationPage` with its own `Adw.HeaderBar`, so the
//! window controls, back button and title area stay native in both the split
//! and collapsed layouts. Window actions own the state transitions; pages raise
//! [`crate::events::UiEvent`]s instead of reaching into the window.

use std::{cell::Cell, rc::Rc, time::Duration};

use adw::prelude::*;
use gtk::{gio, glib};

use crate::core::Core;
use crate::design;
use crate::dialogs;
use crate::events::{EventSink, ToastLevel, UiEvent};
use crate::models::SortOrder;
use crate::navigation::Section;
use crate::pages::{DownloadsPage, HomePage, LibraryPage, SettingsPage, placeholder_page};
use crate::platform::theme::{self, ThemeController};
use crate::state::AppState;
use crate::widgets;
use fg_core::ThemePreference;

pub fn build_ui(application: &adw::Application, size: Option<(i32, i32)>) {
    let (core, core_error) = Core::connect();
    let sink = EventSink::new();
    let state = AppState::new(core, sink.clone());
    let theme = Rc::new(ThemeController::load());

    let home = HomePage::new(&state);
    let library = LibraryPage::new(&state);
    let downloads = DownloadsPage::new(&state);
    let settings = Rc::new(SettingsPage::new(&theme, &sink));
    let placeholders: Vec<(Section, adw::NavigationPage)> =
        [Section::Servers, Section::Java, Section::Accounts]
            .into_iter()
            .map(|section| (section, placeholder_page(section)))
            .collect();

    let (default_width, default_height) = size.unwrap_or((1_400, 880));
    let window = adw::ApplicationWindow::builder()
        .application(application)
        .title("雾雨启动器")
        .default_width(default_width)
        .default_height(default_height)
        .build();
    window.set_size_request(880, 560);

    let toast = adw::ToastOverlay::new();
    let split = adw::NavigationSplitView::new();
    split.set_sidebar_width_unit(adw::LengthUnit::Px);
    split.set_sidebar_width_fraction(0.19);
    split.set_min_sidebar_width(230.0);
    split.set_max_sidebar_width(300.0);

    let (sidebar, nav_lists, menu_button, nav_rows) = build_sidebar();
    split.set_sidebar(Some(&sidebar));
    split.set_content(Some(&home.page));
    toast.set_child(Some(&split));
    window.set_content(Some(&toast));

    let shell = Rc::new(Shell {
        window: window.clone(),
        toast,
        split,
        nav_lists,
        nav_rows,
        home: home.clone(),
        library: library.clone(),
        downloads: downloads.clone(),
        settings,
        placeholders,
        theme,
        state: state.clone(),
        current: Cell::new(Section::Home),
        syncing_nav: Cell::new(false),
        library_breakpoint: Cell::new(false),
        downloads_breakpoint: Cell::new(false),
    });

    home.bind(&state);
    library.bind(&state);
    downloads.bind(&state);

    {
        let weak = Rc::downgrade(&shell);
        state.subscribe(move || {
            if let Some(shell) = weak.upgrade() {
                shell.refresh_chrome();
            }
        });
    }

    wire_navigation(&shell);
    shell.sync_sidebar(Section::Home);
    wire_home_breakpoint(&window, &home);
    shell.refresh_chrome();

    sink.connect({
        let weak = Rc::downgrade(&shell);
        move |event| {
            if let Some(shell) = weak.upgrade() {
                shell.handle(event);
            }
        }
    });

    install_actions(application, &shell);
    menu_button.set_menu_model(Some(&build_menu()));
    window.present();

    state.refresh_core_data();
    install_pump(&state);

    if let Some(error) = core_error {
        glib::idle_add_local_once(move || {
            state.show_error("无法连接启动器核心", &error);
        });
    }
}

fn install_pump(state: &Rc<AppState>) {
    let weak = Rc::downgrade(state);
    glib::timeout_add_local(Duration::from_millis(50), move || {
        let Some(state) = weak.upgrade() else {
            return glib::ControlFlow::Break;
        };
        state.tick();
        glib::ControlFlow::Continue
    });
}

struct Shell {
    window: adw::ApplicationWindow,
    toast: adw::ToastOverlay,
    split: adw::NavigationSplitView,
    nav_lists: Vec<gtk::ListBox>,
    nav_rows: Vec<NavRow>,
    home: Rc<HomePage>,
    library: Rc<LibraryPage>,
    downloads: Rc<DownloadsPage>,
    settings: Rc<SettingsPage>,
    placeholders: Vec<(Section, adw::NavigationPage)>,
    theme: Rc<ThemeController>,
    state: Rc<AppState>,
    current: Cell<Section>,
    syncing_nav: Cell<bool>,
    library_breakpoint: Cell<bool>,
    downloads_breakpoint: Cell<bool>,
}

impl Shell {
    fn navigate(&self, section: Section) {
        if self.current.get() == section {
            self.on_section_shown(section);
            return;
        }
        self.current.set(section);
        self.split.set_content(Some(&self.page(section)));
        self.on_section_shown(section);
        if section == Section::Library {
            self.ensure_library_breakpoint();
        }
        if section == Section::Downloads {
            self.ensure_downloads_breakpoint();
        }
        self.sync_sidebar(section);
    }

    fn on_section_shown(&self, section: Section) {
        match section {
            Section::Library => self.library.on_shown(),
            Section::Downloads => self.downloads.on_shown(),
            _ => {}
        }
    }

    /// `Adw.Breakpoint` setters only act on widgets that are inside the window,
    /// so the library split is wired the first time its page is shown.
    fn ensure_library_breakpoint(&self) {
        if self.library_breakpoint.get() {
            return;
        }
        let Ok(condition) = adw::BreakpointCondition::parse("max-width: 1180px") else {
            return;
        };
        let breakpoint = adw::Breakpoint::new(condition);
        let collapsed = glib::Value::from(true);
        breakpoint.add_setter(&self.library.split, "collapsed", Some(&collapsed));
        let full_width = glib::Value::from(1.0_f64);
        breakpoint.add_setter(
            &self.library.split,
            "sidebar-width-fraction",
            Some(&full_width),
        );
        let wide = glib::Value::from(10_000.0_f64);
        breakpoint.add_setter(&self.library.split, "max-sidebar-width", Some(&wide));

        // In compact mode the list is the entry point; selecting a row hides it
        // again and reveals the detail pane with the back button.
        let split = self.library.split.clone();
        let library = Rc::clone(&self.library);
        breakpoint.connect_apply(move |_| {
            split.set_show_sidebar(true);
            library.prepare_compact();
        });
        let library = Rc::clone(&self.library);
        breakpoint.connect_unapply(move |_| library.restore_selection());

        self.library.split.set_show_sidebar(true);
        self.window.add_breakpoint(breakpoint);
        self.library_breakpoint.set(true);
    }

    fn ensure_downloads_breakpoint(&self) {
        if self.downloads_breakpoint.get() {
            return;
        }
        // 1240 px is where the queue rail, version list and plan card stop
        // fitting together. Hiding the rail and collapsing the split in one
        // breakpoint matters: hiding only one of them still leaves a minimum
        // width above the breakpoint, so the breakpoint would never apply.
        let Ok(condition) = adw::BreakpointCondition::parse("max-width: 1240px") else {
            return;
        };
        let breakpoint = adw::Breakpoint::new(condition);
        let hidden = glib::Value::from(false);
        breakpoint.add_setter(&self.downloads.queue, "visible", Some(&hidden));
        let collapsed = glib::Value::from(true);
        breakpoint.add_setter(&self.downloads.split, "collapsed", Some(&collapsed));
        let full_width = glib::Value::from(1.0_f64);
        breakpoint.add_setter(
            &self.downloads.split,
            "sidebar-width-fraction",
            Some(&full_width),
        );
        let wide = glib::Value::from(10_000.0_f64);
        breakpoint.add_setter(&self.downloads.split, "max-sidebar-width", Some(&wide));
        let split = self.downloads.split.clone();
        let downloads = Rc::clone(&self.downloads);
        breakpoint.connect_apply(move |_| {
            split.set_show_sidebar(true);
            downloads.set_compact_header(true);
        });
        let downloads = Rc::clone(&self.downloads);
        breakpoint.connect_unapply(move |_| downloads.set_compact_header(false));
        self.downloads.queue.set_visible(true);
        self.window.add_breakpoint(breakpoint);
        self.downloads_breakpoint.set(true);
    }

    fn page(&self, section: Section) -> adw::NavigationPage {
        match section {
            Section::Home => self.home.page.clone(),
            Section::Library => self.library.page.clone(),
            Section::Downloads => self.downloads.page.clone(),
            Section::Settings => self.settings.page.clone(),
            other => self
                .placeholders
                .iter()
                .find(|(candidate, _)| *candidate == other)
                .map(|(_, page)| page.clone())
                .expect("placeholder page exists for every stub section"),
        }
    }

    fn sync_sidebar(&self, section: Section) {
        self.syncing_nav.set(true);
        for (candidate, list, row, _) in &self.nav_rows {
            if *candidate == section {
                list.select_row(Some(row));
            } else {
                list.unselect_all();
            }
        }
        self.syncing_nav.set(false);
    }

    fn refresh_chrome(&self) {
        let instances = self.state.instances().len();
        let downloads = self.state.active_download_count();
        for (section, _, _, badge) in &self.nav_rows {
            let Some(badge) = badge else { continue };
            let count = match section {
                Section::Library => instances,
                Section::Downloads => downloads,
                _ => continue,
            };
            badge.set_label(&count.to_string());
        }
    }

    fn open_instance(&self, id: &str) {
        self.navigate(Section::Library);
        self.library.select(id);
    }

    fn add_instance(&self) {
        self.navigate(Section::Downloads);
        self.downloads.on_shown();
    }

    fn handle(&self, event: UiEvent) {
        match event {
            UiEvent::Navigate(section) => self.navigate(section),
            UiEvent::OpenInstance(id) => self.open_instance(&id),
            UiEvent::ShowInstanceSettings(id) => {
                dialogs::instance_settings::present(self.window.upcast_ref(), &self.state, &id);
            }
            UiEvent::FocusLibrarySearch => {
                self.navigate(Section::Library);
                self.library.focus_search();
            }
            UiEvent::ThemeChanged(preference) => self.set_theme(preference),
            UiEvent::Toast { message, level } => self.show_toast(&message, level),
            UiEvent::Alert {
                title,
                message,
                detail,
            } => self.show_alert(&title, &message, detail.as_deref()),
        }
    }

    fn set_theme(&self, preference: ThemePreference) {
        let result = self.theme.apply(preference);
        self.settings.sync_theme(preference);
        if let Some(action) = app_simple_action(&self.window, "theme") {
            action.set_state(&preference.as_str().to_variant());
        }
        if let Err(error) = result {
            self.show_toast(
                &format!("外观已切换，但保存失败：{error}"),
                ToastLevel::Warning,
            );
        }
    }

    fn show_toast(&self, message: &str, level: ToastLevel) {
        // A stock toast lets Adwaita size and wrap the capsule; a custom icon
        // row made short notices wrap mid-sentence. Warnings use the high
        // priority slot so they are not pushed out by routine progress notices.
        let toast = adw::Toast::new(message);
        toast.set_timeout(4);
        if level == ToastLevel::Warning {
            toast.set_priority(adw::ToastPriority::High);
        }
        self.toast.add_toast(toast);
    }

    fn show_alert(&self, title: &str, message: &str, detail: Option<&str>) {
        let body = match detail {
            Some(detail) if !detail.is_empty() => {
                let mut trimmed: String = detail.chars().take(480).collect();
                if detail.chars().count() > 480 {
                    trimmed.push('…');
                }
                format!("{message}\n\n{trimmed}")
            }
            _ => message.to_owned(),
        };
        let alert = adw::AlertDialog::new(Some(title), Some(&body));
        alert.add_response("close", "好");
        alert.set_default_response(Some("close"));
        alert.set_close_response("close");
        alert.present(Some(&self.window));
    }

    fn import_instance(&self) {
        let window = self.window.clone();
        let state = self.state.clone();
        glib::spawn_future_local(async move {
            match crate::platform::files::choose_folder(&window, "导入现有 .minecraft 目录").await
            {
                Ok(Some(folder)) => {
                    let Some(path) = folder.path() else {
                        state.show_notice("无法读取所选目录的路径", ToastLevel::Warning);
                        return;
                    };
                    state.import_directory(path);
                }
                Ok(None) => {}
                Err(error) => {
                    state.show_notice(format!("无法打开文件选择器：{error}"), ToastLevel::Warning);
                }
            }
        });
    }

    fn open_data_dir(&self) {
        let root = fg_core::paths::data_root();
        if let Err(error) = std::fs::create_dir_all(&root) {
            self.show_toast(&format!("无法创建数据目录：{error}"), ToastLevel::Warning);
            return;
        }
        if let Err(error) = crate::platform::files::open_directory(&root) {
            self.show_toast(&format!("无法打开数据目录：{error}"), ToastLevel::Warning);
        }
    }

    fn show_about(&self) {
        let about = adw::AboutDialog::builder()
            .application_name("雾雨启动器")
            .application_icon("com.fograin.launcher")
            .developer_name("Fograin Launcher contributors")
            .version(env!("CARGO_PKG_VERSION"))
            .comments("Minecraft Java 版启动器 · Linux 原生客户端（GTK4 + libadwaita）")
            .website("https://github.com/crystelf/fograin-launcher")
            .issue_url("https://github.com/crystelf/fograin-launcher/issues")
            .license_type(gtk::License::Gpl30)
            .build();
        about.present(Some(&self.window));
    }
}

fn wire_navigation(shell: &Rc<Shell>) {
    for list in &shell.nav_lists {
        let weak = Rc::downgrade(shell);
        list.connect_row_selected(move |_, row| {
            let Some(shell) = weak.upgrade() else {
                return;
            };
            if shell.syncing_nav.get() {
                return;
            }
            let Some(row) = row else {
                return;
            };
            if let Some((section, _, _, _)) = shell
                .nav_rows
                .iter()
                .find(|(_, _, candidate, _)| candidate == row)
            {
                shell.navigate(*section);
            }
        });
    }
}

/// Stacks the home columns when the content pane is too narrow for two columns.
fn wire_home_breakpoint(window: &adw::ApplicationWindow, home: &Rc<HomePage>) {
    let Ok(condition) = adw::BreakpointCondition::parse("max-width: 1000px") else {
        return;
    };
    let breakpoint = adw::Breakpoint::new(condition);

    let vertical = glib::prelude::ToValue::to_value(&gtk::Orientation::Vertical);
    breakpoint.add_setter(&home.columns, "orientation", Some(&vertical));

    let natural = glib::Value::from(-1i32);
    breakpoint.add_setter(&home.status_column, "width-request", Some(&natural));

    window.add_breakpoint(breakpoint);
}

type NavRow = (Section, gtk::ListBox, gtk::ListBoxRow, Option<gtk::Label>);
type Sidebar = (
    adw::NavigationPage,
    Vec<gtk::ListBox>,
    gtk::MenuButton,
    Vec<NavRow>,
);

fn build_sidebar() -> Sidebar {
    let header = adw::HeaderBar::new();
    header.set_show_start_title_buttons(false);
    header.set_show_end_title_buttons(false);

    // App identity on the left, following GNOME sidebar headers; the
    // header bar's centered title area stays free for the content page.
    let brand = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    brand.append(&design::app_icon(20));
    let name = gtk::Label::new(Some("Fograin"));
    name.add_css_class("title-4");
    brand.append(&name);
    header.pack_start(&brand);
    header.set_show_title(false);

    let menu_button = gtk::MenuButton::builder()
        .icon_name("open-menu-symbolic")
        .tooltip_text("主菜单")
        .build();
    menu_button.add_css_class("flat");
    header.pack_end(&menu_button);

    let launch_nav = nav_list_box();
    let resources_nav = nav_list_box();
    let mut nav_rows = Vec::new();

    for section in Section::LAUNCH {
        let (row, badge) = nav_row(section);
        launch_nav.append(&row);
        nav_rows.push((section, launch_nav.clone(), row, badge));
    }
    for section in Section::RESOURCES {
        let (row, badge) = nav_row(section);
        resources_nav.append(&row);
        nav_rows.push((section, resources_nav.clone(), row, badge));
    }

    let separator = gtk::Separator::new(gtk::Orientation::Horizontal);
    separator.set_margin_top(design::SPACING_3);
    separator.set_margin_bottom(design::SPACING_3);
    separator.set_margin_start(design::SPACING_6);
    separator.set_margin_end(design::SPACING_6);

    let nav_area = gtk::Box::new(gtk::Orientation::Vertical, 0);
    nav_area.set_margin_top(design::SPACING_3);
    nav_area.set_margin_bottom(design::SPACING_3);
    nav_area.append(&launch_nav);
    nav_area.append(&separator);
    nav_area.append(&resources_nav);

    let scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .child(&nav_area)
        .build();

    let account = account_button();
    let content = gtk::Box::new(gtk::Orientation::Vertical, 0);
    content.append(&scroller);
    content.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    content.append(&account);

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header);
    toolbar.set_content(Some(&content));

    (
        adw::NavigationPage::new(&toolbar, "雾雨启动器"),
        vec![launch_nav, resources_nav],
        menu_button,
        nav_rows,
    )
}

fn nav_list_box() -> gtk::ListBox {
    let list = gtk::ListBox::new();
    list.add_css_class("navigation-sidebar");
    list.set_selection_mode(gtk::SelectionMode::Single);
    list
}

fn nav_row(section: Section) -> (gtk::ListBoxRow, Option<gtk::Label>) {
    let row = gtk::ListBoxRow::new();
    let content = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_5);
    content.set_margin_top(design::SPACING_2);
    content.set_margin_bottom(design::SPACING_2);
    content.set_margin_start(design::SPACING_5);
    content.set_margin_end(design::SPACING_5);

    let icon = gtk::Image::from_icon_name(section.icon());
    icon.set_pixel_size(16);
    content.append(&icon);

    let label = gtk::Label::builder()
        .label(section.title())
        .xalign(0.0)
        .hexpand(true)
        .build();
    content.append(&label);

    let badge = match section {
        Section::Library | Section::Downloads => {
            let count = gtk::Label::new(Some("0"));
            count.add_css_class("caption");
            count.add_css_class("dim-label");
            count.set_valign(gtk::Align::Center);
            content.append(&count);
            Some(count)
        }
        _ => None,
    };

    row.set_child(Some(&content));
    row.update_property(&[gtk::accessible::Property::Label(section.title())]);
    (row, badge)
}

fn account_button() -> gtk::Button {
    let button = gtk::Button::builder().has_frame(false).build();
    button.add_css_class("flat");
    button.set_action_name(Some("app.goto-accounts"));

    let content = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_5);
    content.set_margin_top(design::SPACING_4);
    content.set_margin_bottom(design::SPACING_4);
    content.set_margin_start(design::SPACING_6);
    content.set_margin_end(design::SPACING_6);

    let avatar = adw::Avatar::new(32, Some("S"), true);
    content.append(&avatar);

    let text = gtk::Box::new(gtk::Orientation::Vertical, 0);
    text.set_hexpand(true);
    text.set_valign(gtk::Align::Center);
    let name = gtk::Label::builder().label("Steve_Fog").xalign(0.0).build();
    name.add_css_class("fograin-account-name");
    let kind = gtk::Label::builder()
        .label("Microsoft 账户")
        .xalign(0.0)
        .build();
    kind.add_css_class("caption");
    kind.add_css_class("dim-label");
    text.append(&name);
    text.append(&kind);
    content.append(&text);

    let chevron = gtk::Image::from_icon_name("go-next-symbolic");
    chevron.add_css_class("dim-label");
    content.append(&chevron);

    button.set_child(Some(&content));
    button.update_property(&[gtk::accessible::Property::Label("账户 · Steve_Fog")]);
    button
}

fn build_menu() -> gio::Menu {
    let menu = gio::Menu::new();

    let appearance = gio::Menu::new();
    for preference in [
        ThemePreference::System,
        ThemePreference::Light,
        ThemePreference::Dark,
    ] {
        appearance.append_item(&widgets::menu_item(
            theme::label(preference),
            "app.theme",
            preference.as_str(),
        ));
    }
    menu.append_submenu(Some("外观"), &appearance);
    menu.append(Some("新建实例"), Some("app.new-instance"));
    menu.append(Some("导入现有实例…"), Some("app.import-instance"));
    // Global stop route: the titlebar capsule is intentionally gone, and the
    // hero stop is only visible on 主页.
    menu.append(Some("停止游戏"), Some("app.stop-session"));
    menu.append(Some("设置"), Some("app.goto-settings"));
    menu.append(Some("打开数据目录"), Some("app.open-data-dir"));
    menu.append(Some("关于雾雨启动器"), Some("app.show-about"));
    menu.append(Some("退出"), Some("app.quit"));
    menu
}

fn install_actions(application: &adw::Application, shell: &Rc<Shell>) {
    for section in Section::ALL {
        let shell = shell.clone();
        simple_action(application, section.action_id(), move |_, _| {
            shell.navigate(section);
        });
    }

    let theme_action = gio::SimpleAction::new_stateful(
        "theme",
        Some(glib::VariantTy::STRING),
        &shell.theme.preference().as_str().to_variant(),
    );
    {
        let shell = shell.clone();
        theme_action.connect_activate(move |action, parameter| {
            let Some(preference) = parameter
                .and_then(|value| value.str())
                .and_then(theme::from_id)
            else {
                return;
            };
            action.set_state(&preference.as_str().to_variant());
            shell.set_theme(preference);
        });
    }
    application.add_action(&theme_action);

    let sort_action = gio::SimpleAction::new_stateful(
        "sort",
        Some(glib::VariantTy::STRING),
        &SortOrder::Recent.id().to_variant(),
    );
    {
        let shell = shell.clone();
        sort_action.connect_activate(move |action, parameter| {
            let Some(order) = parameter
                .and_then(|value| value.str())
                .and_then(SortOrder::from_id)
            else {
                return;
            };
            action.set_state(&order.id().to_variant());
            shell.library.set_sort(order);
        });
    }
    application.add_action(&sort_action);

    string_action(application, "launch-instance", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(instance) = parameter
                .and_then(|value| value.str())
                .and_then(|id| shell.state.instance(id))
            {
                shell.state.start_game(&instance);
            }
        }
    });

    string_action(application, "open-instance", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(id) = parameter.and_then(|value| value.str()) {
                shell.open_instance(id);
            }
        }
    });

    string_action(application, "instance-settings", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(id) = parameter.and_then(|value| value.str()) {
                shell.handle(UiEvent::ShowInstanceSettings(id.to_owned()));
            }
        }
    });

    string_action(application, "open-instance-folder", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(instance) = parameter
                .and_then(|value| value.str())
                .and_then(|id| shell.state.instance(id))
            {
                shell.state.open_instance_folder(&instance);
            }
        }
    });

    string_action(application, "toggle-favorite", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(instance) = parameter
                .and_then(|value| value.str())
                .and_then(|id| shell.state.instance(id))
            {
                shell.state.toggle_favorite(&instance);
            }
        }
    });

    string_action(application, "milestone", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(id) = parameter.and_then(|value| value.str()) {
                shell.show_toast(milestone_message(id), ToastLevel::Info);
            }
        }
    });

    simple_action(application, "new-instance", {
        let shell = shell.clone();
        move |_, _| shell.add_instance()
    });

    simple_action(application, "add-instance", {
        let shell = shell.clone();
        move |_, _| shell.add_instance()
    });

    simple_action(application, "import-instance", {
        let shell = shell.clone();
        move |_, _| shell.import_instance()
    });

    simple_action(application, "open-data-dir", {
        let shell = shell.clone();
        move |_, _| shell.open_data_dir()
    });

    simple_action(application, "show-about", {
        let shell = shell.clone();
        move |_, _| shell.show_about()
    });

    simple_action(application, "focus-search", {
        let shell = shell.clone();
        move |_, _| shell.handle(UiEvent::FocusLibrarySearch)
    });

    simple_action(application, "stop-session", {
        let shell = shell.clone();
        move |_, _| shell.state.stop_game()
    });

    simple_action(application, "refresh", {
        let shell = shell.clone();
        move |_, _| shell.state.refresh_core_data()
    });

    let application_weak = application.downgrade();
    simple_action(application, "quit", move |_, _| {
        if let Some(application) = application_weak.upgrade() {
            application.quit();
        }
    });
}

fn simple_action(
    application: &adw::Application,
    name: &str,
    callback: impl Fn(&gio::SimpleAction, Option<&glib::Variant>) + 'static,
) {
    let action = gio::SimpleAction::new(name, None);
    action.connect_activate(callback);
    application.add_action(&action);
}

fn string_action(
    application: &adw::Application,
    name: &str,
    callback: impl Fn(&gio::SimpleAction, Option<&glib::Variant>) + 'static,
) {
    let action = gio::SimpleAction::new(name, Some(glib::VariantTy::STRING));
    action.connect_activate(callback);
    application.add_action(&action);
}

fn app_simple_action(window: &adw::ApplicationWindow, name: &str) -> Option<gio::SimpleAction> {
    let application = window.application()?;
    let action = application.lookup_action(name)?;
    action.downcast::<gio::SimpleAction>().ok()
}

fn milestone_message(id: &str) -> &'static str {
    match id {
        "java" => "Java 运行时管理将在 M6 接入。",
        "mods" | "resourcepacks" | "shaders" => "模组、资源包与光影管理将在 M8 接入。",
        "worlds" => "世界管理将在 M9 接入。",
        "instance-folder" => "请在实例详情或游戏设置中打开实例目录。",
        _ => "该功能尚未接入。",
    }
}
