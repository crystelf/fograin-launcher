//! The window shell: sidebar navigation, content pages, actions and menu.
//!
//! Every page is an `Adw.NavigationPage` with its own `Adw.HeaderBar`, so the
//! window controls, back button and title area stay native in both the split
//! and collapsed layouts. Window actions own the state transitions; pages raise
//! [`crate::events::UiEvent`]s instead of reaching into the window.

use std::cell::Cell;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gio, glib};

use crate::design;
use crate::events::{EventSink, UiEvent};
use crate::models::{self, SortOrder};
use crate::navigation::Section;
use crate::pages::{HomePage, LibraryPage, SettingsPage, placeholder_page};
use crate::platform::theme::{self, ThemeController};
use crate::widgets;
use fg_core::ThemePreference;

pub fn build_ui(application: &adw::Application) {
    let sink = EventSink::new();
    let theme = Rc::new(ThemeController::load());

    let home = Rc::new(HomePage::new(&sink));
    let library = LibraryPage::new();
    let settings = Rc::new(SettingsPage::new(&theme, &sink));
    let placeholders: Vec<(Section, adw::NavigationPage)> = [
        Section::Servers,
        Section::Downloads,
        Section::Java,
        Section::Accounts,
    ]
    .into_iter()
    .map(|section| (section, placeholder_page(section)))
    .collect();

    let window = adw::ApplicationWindow::builder()
        .application(application)
        .title("雾雨启动器")
        .default_width(1_400)
        .default_height(880)
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
        settings,
        placeholders,
        theme,
        current: Cell::new(Section::Home),
        syncing_nav: Cell::new(false),
        library_breakpoint: Cell::new(false),
    });

    wire_navigation(&shell);
    shell.sync_sidebar(Section::Home);
    wire_home_breakpoint(&window, &home);

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
}

struct Shell {
    window: adw::ApplicationWindow,
    toast: adw::ToastOverlay,
    split: adw::NavigationSplitView,
    nav_lists: Vec<gtk::ListBox>,
    nav_rows: Vec<NavRow>,
    home: Rc<HomePage>,
    library: Rc<LibraryPage>,
    settings: Rc<SettingsPage>,
    placeholders: Vec<(Section, adw::NavigationPage)>,
    theme: Rc<ThemeController>,
    current: Cell<Section>,
    syncing_nav: Cell<bool>,
    library_breakpoint: Cell<bool>,
}

impl Shell {
    fn navigate(&self, section: Section) {
        if self.current.get() == section {
            return;
        }
        self.current.set(section);
        self.split.set_content(Some(&self.page(section)));
        if section == Section::Library {
            self.ensure_library_breakpoint();
        }
        self.sync_sidebar(section);
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

        // In compact mode the list is the entry point; selecting a row hides it
        // again and reveals the detail pane with the back button.
        let split = self.library.split.clone();
        breakpoint.connect_apply(move |_| split.set_show_sidebar(true));

        self.library.split.set_show_sidebar(true);
        self.window.add_breakpoint(breakpoint);
        self.library_breakpoint.set(true);
    }

    fn page(&self, section: Section) -> adw::NavigationPage {
        match section {
            Section::Home => self.home.page.clone(),
            Section::Library => self.library.page.clone(),
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
        for (candidate, list, row) in &self.nav_rows {
            if *candidate == section {
                list.select_row(Some(row));
            } else {
                list.unselect_all();
            }
        }
        self.syncing_nav.set(false);
    }

    fn open_instance(&self, id: &str) {
        self.navigate(Section::Library);
        self.library.select(id);
    }

    fn handle(&self, event: UiEvent) {
        match event {
            UiEvent::OpenInstance(id) => self.open_instance(id),
            UiEvent::ThemeChanged(preference) => self.set_theme(preference),
        }
    }

    fn set_theme(&self, preference: ThemePreference) {
        let result = self.theme.apply(preference);
        self.settings.sync_theme(preference);
        if let Some(action) = app_simple_action(&self.window, "theme") {
            action.set_state(&preference.as_str().to_variant());
        }
        if let Err(error) = result {
            self.toast(&format!("外观已切换，但保存失败：{error}"));
        }
    }

    fn toast(&self, message: &str) {
        show_toast(&self.toast, message);
    }

    fn import_instance(&self) {
        let window = self.window.clone();
        let toast = self.toast.clone();
        glib::spawn_future_local(async move {
            match crate::platform::files::choose_folder(&window, "导入现有 .minecraft 目录").await
            {
                Ok(Some(folder)) => {
                    let path = folder.path().map_or_else(
                        || folder.uri().to_string(),
                        |path| path.display().to_string(),
                    );
                    show_toast(&toast, &format!("已选择 {path}（导入将在 M4 接入）"));
                }
                Ok(None) => {}
                Err(error) => show_toast(&toast, &format!("无法打开文件选择器：{error}")),
            }
        });
    }

    fn open_data_dir(&self) {
        let root = fg_core::paths::data_root();
        if let Err(error) = std::fs::create_dir_all(&root) {
            self.toast(&format!("无法创建数据目录：{error}"));
            return;
        }
        if let Err(error) = crate::platform::files::open_directory(&root) {
            self.toast(&format!("无法打开数据目录：{error}"));
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
            if let Some((section, _, _)) = shell
                .nav_rows
                .iter()
                .find(|(_, _, candidate)| candidate == row)
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

    let vertical = gtk::glib::prelude::ToValue::to_value(&gtk::Orientation::Vertical);
    breakpoint.add_setter(&home.columns, "orientation", Some(&vertical));

    let natural = glib::Value::from(-1i32);
    breakpoint.add_setter(&home.status_column, "width-request", Some(&natural));

    window.add_breakpoint(breakpoint);
}

type NavRow = (Section, gtk::ListBox, gtk::ListBoxRow);
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
        let badge = match section {
            Section::Library => Some(models::all().len().to_string()),
            _ => None,
        };
        let row = nav_row(section.icon(), section.title(), badge.as_deref());
        launch_nav.append(&row);
        nav_rows.push((section, launch_nav.clone(), row));
    }
    for section in Section::RESOURCES {
        let badge = match section {
            Section::Downloads => Some(models::DOWNLOADS.len().to_string()),
            _ => None,
        };
        let row = nav_row(section.icon(), section.title(), badge.as_deref());
        resources_nav.append(&row);
        nav_rows.push((section, resources_nav.clone(), row));
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

fn nav_row(icon_name: &str, title: &str, badge: Option<&str>) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    let content = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_5);
    content.set_margin_top(design::SPACING_2);
    content.set_margin_bottom(design::SPACING_2);
    content.set_margin_start(design::SPACING_5);
    content.set_margin_end(design::SPACING_5);

    let icon = gtk::Image::from_icon_name(icon_name);
    icon.set_pixel_size(16);
    content.append(&icon);

    let label = gtk::Label::builder()
        .label(title)
        .xalign(0.0)
        .hexpand(true)
        .build();
    content.append(&label);

    if let Some(badge) = badge {
        let count = gtk::Label::new(Some(badge));
        count.add_css_class("caption");
        count.add_css_class("dim-label");
        count.set_valign(gtk::Align::Center);
        content.append(&count);
    }

    row.set_child(Some(&content));
    row.update_property(&[gtk::accessible::Property::Label(title)]);
    row
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
    menu.append(Some("设置"), Some("app.goto-settings"));
    menu.append(Some("导入现有实例…"), Some("app.import-instance"));
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
            let Some(instance) = parameter
                .and_then(|value| value.str())
                .and_then(models::by_id)
            else {
                return;
            };
            shell.toast(&format!("「{}」的启动流程将在 M3 接入。", instance.name));
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
            let Some(instance) = parameter
                .and_then(|value| value.str())
                .and_then(models::by_id)
            else {
                return;
            };
            shell.toast(&format!("「{}」的实例设置将在 M4 接入。", instance.name));
        }
    });

    string_action(application, "milestone", {
        let shell = shell.clone();
        move |_, parameter| {
            if let Some(id) = parameter.and_then(|value| value.str()) {
                shell.toast(milestone_message(id));
            }
        }
    });

    simple_action(application, "new-instance", {
        let shell = shell.clone();
        move |_, _| shell.toast("新建实例向导将在 M4 接入。")
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
        move |_, _| {
            shell.navigate(Section::Library);
            shell.library.focus_search();
        }
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
        "memory" => "内存分配策略将在 M4 接入。",
        "graphics" => "图形与 GameMode 设置将在 M4 接入。",
        "mods" => "模组管理将在 M8 接入。",
        "resourcepacks" => "资源包管理将在 M8 接入。",
        "shaders" => "光影管理将在 M8 接入。",
        "worlds" => "世界管理将在 M8 接入。",
        "instance-folder" => "实例目录将在 M4 接入。",
        _ => "该功能尚未接入。",
    }
}

fn show_toast(overlay: &adw::ToastOverlay, message: &str) {
    let toast = adw::Toast::new(message);
    toast.set_timeout(4);
    overlay.add_toast(toast);
}
