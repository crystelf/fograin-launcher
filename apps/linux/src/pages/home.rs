//! Home page: launch hero, recent instances and the live status column.
//!
//! The layout follows the pen's GNOME home frame and the macOS M4 wiring: the
//! hero always shows the selected instance and owns the start/stop action, the
//! titlebar keeps a scheme-style instance item, and the status column reads the
//! real task queue and runtime summary from the launcher core.

use std::{
    cell::Cell,
    rc::{Rc, Weak},
};

use adw::prelude::*;

use crate::design;
use crate::models::{self, Instance};
use crate::platform::system::SystemInfo;
use crate::state::AppState;
use crate::widgets;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RecentFilter {
    Recent,
    Favorites,
    All,
}

impl RecentFilter {
    const ALL: [Self; 3] = [Self::Recent, Self::Favorites, Self::All];

    const fn title(self) -> &'static str {
        match self {
            Self::Recent => "最近",
            Self::Favorites => "收藏",
            Self::All => "全部",
        }
    }

    const fn page_name(self) -> &'static str {
        match self {
            Self::Recent => "recent",
            Self::Favorites => "favorites",
            Self::All => "all",
        }
    }
}

struct ListData {
    list: gtk::ListBox,
}

pub struct HomePage {
    pub page: adw::NavigationPage,
    /// Columns switch to a vertical stack on narrow windows; they are exposed
    /// for that one setter pair in the shell breakpoint.
    pub columns: gtk::Box,
    pub status_column: gtk::Box,
    state: Rc<AppState>,
    title: adw::WindowTitle,
    hero: widgets::HeroCard,
    hero_stack: gtk::Stack,
    status_button: widgets::InstanceStatusButton,
    loading: gtk::Box,
    lists: Vec<ListData>,
    syncing: Rc<Cell<bool>>,
    queue_speed: gtk::Label,
    queue_list: gtk::ListBox,
    queue_empty: gtk::Label,
    runtime_list: gtk::ListBox,
}

impl HomePage {
    #[must_use]
    pub fn new(state: &Rc<AppState>) -> Rc<Self> {
        let content = gtk::Box::new(gtk::Orientation::Vertical, design::SECTION_GAP);
        content.set_margin_top(design::CONTENT_PADDING);
        content.set_margin_bottom(design::CONTENT_PADDING);
        content.set_margin_start(design::CONTENT_PADDING);
        content.set_margin_end(design::CONTENT_PADDING);
        content.set_hexpand(true);

        let hero = widgets::HeroCard::new(
            {
                let state = Rc::clone(state);
                move |id| {
                    if let Some(instance) = state.instance(id) {
                        state.start_game(&instance);
                    }
                }
            },
            {
                let state = Rc::clone(state);
                move || state.stop_game()
            },
            {
                let state = Rc::clone(state);
                move |id| state.show_instance_settings(id)
            },
        );
        let hero_stack = gtk::Stack::new();
        hero_stack.set_transition_type(gtk::StackTransitionType::Crossfade);
        hero_stack.add_named(hero.widget(), Some("hero"));
        hero_stack.add_named(&empty_hero(), Some("empty"));
        content.append(&hero_stack);

        let columns = gtk::Box::new(gtk::Orientation::Horizontal, design::COLUMN_GAP);
        columns.set_hexpand(true);

        let syncing = Rc::new(Cell::new(false));
        let (recent_column, lists) = recent_column(state, &syncing);
        recent_column.set_hexpand(true);

        let (status_column, queue_speed, queue_list, queue_empty, runtime_list) = status_column();
        status_column.set_size_request(340, -1);
        // Its section headers expand, which would make the rail compute-expand
        // and split the spare width evenly with the instance list.
        status_column.set_hexpand(false);

        columns.append(&recent_column);
        columns.append(&status_column);
        content.append(&columns);

        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&content)
            .build();

        let loading = gtk::Box::new(gtk::Orientation::Vertical, 8);
        loading.add_css_class("fograin-loading");
        loading.set_halign(gtk::Align::Center);
        loading.set_valign(gtk::Align::Center);
        let spinner = gtk::Spinner::new();
        spinner.start();
        loading.append(&spinner);
        let label = gtk::Label::new(Some("正在读取实例…"));
        label.add_css_class("dim-label");
        loading.append(&label);

        let overlay = gtk::Overlay::new();
        overlay.set_child(Some(&scroller));
        overlay.add_overlay(&loading);

        let header = adw::HeaderBar::new();
        let title = adw::WindowTitle::new("主页", "启动上次游玩的实例");
        header.set_title_widget(Some(&title));

        let new_instance = gtk::Button::builder()
            .icon_name("list-add-symbolic")
            .tooltip_text("新建实例")
            .build();
        new_instance.add_css_class("flat");
        new_instance.set_action_name(Some("app.new-instance"));
        header.pack_end(&new_instance);

        let status_button = widgets::InstanceStatusButton::new();
        {
            let state = Rc::clone(state);
            status_button.root.connect_clicked(move |_| {
                if let Some(id) = state.current_instance().map(|instance| instance.id) {
                    state.show_instance_settings(&id);
                }
            });
        }
        header.pack_start(&status_button.root);

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&overlay));

        let page = adw::NavigationPage::new(&toolbar, "主页");

        let home = Rc::new(Self {
            page,
            columns,
            status_column,
            state: Rc::clone(state),
            title,
            hero,
            hero_stack,
            status_button,
            loading,
            lists,
            syncing,
            queue_speed,
            queue_list,
            queue_empty,
            runtime_list,
        });
        home.refresh();
        home
    }

    /// Registers the state listener without creating an ownership cycle.
    pub fn bind(self: &Rc<Self>, state: &Rc<AppState>) {
        let weak: Weak<Self> = Rc::downgrade(self);
        state.subscribe(move || {
            if let Some(page) = weak.upgrade() {
                page.refresh();
            }
        });
    }

    fn refresh(&self) {
        let instances = self.state.instances();
        let current = self.state.current_instance();
        let session = self.state.session();

        let last_played = instances
            .iter()
            .filter_map(|instance| instance.last_played_ms)
            .max();
        let subtitle = if instances.is_empty() {
            "还没有实例 · 从下载页开始".to_owned()
        } else if last_played.is_none() {
            format!("{} 个实例 · 还没有游玩记录", instances.len())
        } else {
            format!(
                "{} 个实例 · 上次游玩 {}",
                instances.len(),
                models::relative_date(last_played)
            )
        };
        self.title.set_subtitle(&subtitle);

        match &current {
            Some(instance) => {
                self.hero_stack.set_visible_child_name("hero");
                self.hero.show(
                    instance,
                    self.state.is_downloading(instance),
                    session.running,
                    session.loading,
                );
            }
            None => self.hero_stack.set_visible_child_name("empty"),
        }

        self.status_button.show(current.as_ref(), session.loading);

        let recent_items = {
            let mut items = instances.clone();
            items.sort_by(|left, right| {
                right
                    .last_played_ms
                    .unwrap_or(i64::MIN)
                    .cmp(&left.last_played_ms.unwrap_or(i64::MIN))
            });
            items.truncate(6);
            items
        };
        let favorite_items: Vec<Instance> = instances
            .iter()
            .filter(|instance| instance.favorite)
            .cloned()
            .collect();
        let all_items = instances;

        for (data, items) in self
            .lists
            .iter()
            .zip([recent_items, favorite_items, all_items])
        {
            while let Some(child) = data.list.first_child() {
                data.list.remove(&child);
            }
            for instance in &items {
                let row = widgets::instance_row(instance, widgets::RowStyle::Home);
                row.set_widget_name(&instance.id);
                data.list.append(&row);
            }
        }

        self.apply_selection(current.as_ref().map(|instance| instance.id.as_str()));

        self.queue_speed
            .set_label(&self.state.downloads_speed_text());
        let active = self.state.active_downloads();
        while let Some(child) = self.queue_list.first_child() {
            self.queue_list.remove(&child);
        }
        for row in &active {
            self.queue_list.append(&widgets::download_row(row));
        }
        self.queue_list.set_visible(!active.is_empty());
        self.queue_empty.set_visible(active.is_empty());

        while let Some(child) = self.runtime_list.first_child() {
            self.runtime_list.remove(&child);
        }
        let java_count = self
            .state
            .app_snapshot()
            .map_or(0, |snapshot| snapshot.java_count);
        self.runtime_list.append(&widgets::key_value_row(
            "Java",
            &self.state.java_summary(),
            java_count > 0,
        ));
        self.runtime_list.append(&widgets::key_value_row(
            "内存分配",
            &self.state.memory_summary(),
            true,
        ));
        self.runtime_list.append(&widgets::key_value_row(
            "渲染",
            &SystemInfo::current().gpu_text(),
            true,
        ));

        self.loading.set_visible(self.state.is_initial_loading());
    }

    fn apply_selection(&self, selected: Option<&str>) {
        self.syncing.set(true);
        for data in &self.lists {
            let mut found = false;
            let mut index = 0;
            while let Some(row) = data.list.row_at_index(index) {
                if selected == Some(row.widget_name().as_str()) {
                    data.list.select_row(Some(&row));
                    found = true;
                }
                index += 1;
            }
            if !found {
                data.list.unselect_all();
            }
        }
        self.syncing.set(false);
    }
}

fn recent_column(state: &Rc<AppState>, syncing: &Rc<Cell<bool>>) -> (gtk::Box, Vec<ListData>) {
    let column = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    column.set_valign(gtk::Align::Start);

    let stack = gtk::Stack::new();
    stack.set_transition_type(gtk::StackTransitionType::Crossfade);

    let mut lists = Vec::new();
    for filter in RecentFilter::ALL {
        let list = gtk::ListBox::new();
        list.add_css_class("boxed-list");
        list.set_selection_mode(gtk::SelectionMode::Single);

        {
            let state = Rc::clone(state);
            let syncing = Rc::clone(syncing);
            list.connect_row_selected(move |_, row| {
                if syncing.get() {
                    return;
                }
                if let Some(row) = row {
                    state.set_current_instance(row.widget_name().as_str());
                }
            });
        }
        {
            let state = Rc::clone(state);
            list.connect_row_activated(move |_, row| {
                state.open_instance(row.widget_name().as_str());
            });
        }

        stack.add_named(&list, Some(filter.page_name()));
        lists.push(ListData { list });
    }

    // Compact segmented filter. Gtk.StackSwitcher's minimum width follows the
    // widest stack page (~400 px here), so the pen's compact three-way control
    // is built from native toggle buttons and the shared filter stylesheet.
    let segments = gtk::Box::new(gtk::Orientation::Horizontal, 0);
    segments.add_css_class("fograin-filter");
    segments.set_halign(gtk::Align::End);
    segments.set_valign(gtk::Align::Center);
    let mut group: Option<gtk::ToggleButton> = None;

    for filter in RecentFilter::ALL {
        let button = gtk::ToggleButton::with_label(filter.title());
        match &group {
            Some(first) => button.set_group(Some(first)),
            None => {
                button.set_active(true);
                group = Some(button.clone());
            }
        }
        let stack = stack.clone();
        let page = filter.page_name();
        button.connect_toggled(move |button| {
            if button.is_active() {
                stack.set_visible_child_name(page);
            }
        });
        segments.append(&button);
    }

    let header = widgets::section_header("实例", |header| {
        header.append(&segments);
    });
    column.append(&header);
    column.append(&stack);
    (column, lists)
}

fn empty_hero() -> gtk::Box {
    let card = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    card.set_margin_top(design::SPACING_8);
    card.set_margin_bottom(design::SPACING_8);
    card.set_margin_start(design::SPACING_8);
    card.set_margin_end(design::SPACING_8);
    card.add_css_class("fograin-empty-hero");

    let title = gtk::Label::builder()
        .label("还没有实例")
        .xalign(0.0)
        .build();
    title.add_css_class("title-1");
    card.append(&title);

    let body = gtk::Label::builder()
        .label("通过下载页选择 Minecraft 版本、加载器并安装，几分钟后就能开始游戏。")
        .xalign(0.0)
        .wrap(true)
        .build();
    body.add_css_class("dim-label");
    card.append(&body);

    let add = gtk::Button::builder()
        .label("添加实例")
        .halign(gtk::Align::Start)
        .build();
    add.add_css_class("suggested-action");
    add.add_css_class("pill");
    add.set_action_name(Some("app.new-instance"));
    card.append(&add);
    card
}

fn status_column() -> (gtk::Box, gtk::Label, gtk::ListBox, gtk::Label, gtk::ListBox) {
    let column = gtk::Box::new(gtk::Orientation::Vertical, design::SECTION_GAP);
    column.set_valign(gtk::Align::Start);

    let downloads = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    let speed = gtk::Label::new(Some("空闲"));
    speed.add_css_class("caption");
    speed.add_css_class("dim-label");
    downloads.append(&widgets::section_header("任务队列", |header| {
        header.append(&speed);
    }));
    let queue_list = gtk::ListBox::new();
    queue_list.add_css_class("boxed-list");
    queue_list.set_selection_mode(gtk::SelectionMode::None);
    let queue_empty = widgets::empty_label("没有进行中的任务");
    queue_empty.set_margin_start(design::SPACING_3);
    downloads.append(&queue_list);
    downloads.append(&queue_empty);
    column.append(&downloads);

    let separator = gtk::Separator::new(gtk::Orientation::Horizontal);
    column.append(&separator);

    let environment = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    environment.append(&widgets::section_header("运行环境", |_| {}));
    let runtime_list = gtk::ListBox::new();
    runtime_list.add_css_class("boxed-list");
    runtime_list.set_selection_mode(gtk::SelectionMode::None);
    environment.append(&runtime_list);
    column.append(&environment);

    (column, speed, queue_list, queue_empty, runtime_list)
}
