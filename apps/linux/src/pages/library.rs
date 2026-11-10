//! Instance library: loader-grouped list pane plus adaptive detail pane.
//!
//! The split is a native `Adw.OverlaySplitView`: it collapses into the list on
//! narrow windows, and selecting a row reveals the detail overlay with a native
//! back button. Double-clicking a row opens 游戏设置, like the macOS client.

use std::{
    cell::{Cell, RefCell},
    rc::{Rc, Weak},
};

use adw::prelude::*;
use gtk::gio;

use crate::design;
use crate::models::{self, SortOrder};
use crate::pages::detail::DetailPane;
use crate::state::AppState;
use crate::widgets::{self, RowStyle};

struct Entry {
    list: gtk::ListBox,
    row: gtk::ListBoxRow,
    id: String,
}

pub struct LibraryPage {
    pub page: adw::NavigationPage,
    pub split: adw::OverlaySplitView,
    state: Rc<AppState>,
    search: gtk::SearchEntry,
    groups: gtk::Box,
    title: adw::WindowTitle,
    sort: Cell<SortOrder>,
    selected: RefCell<Option<String>>,
    entries: RefCell<Vec<Entry>>,
    syncing: Cell<bool>,
    dirty: Cell<bool>,
    loading: gtk::Box,
    self_weak: Weak<Self>,
    detail: DetailPane,
}

impl LibraryPage {
    #[must_use]
    pub fn new(state: &Rc<AppState>) -> Rc<Self> {
        let header = adw::HeaderBar::new();
        let title = adw::WindowTitle::new("实例库", "");
        header.set_title_widget(Some(&title));

        let add = gtk::Button::builder()
            .icon_name("list-add-symbolic")
            .tooltip_text("新建实例")
            .build();
        add.add_css_class("flat");
        add.set_action_name(Some("app.new-instance"));
        header.pack_start(&add);

        let back = gtk::Button::builder()
            .icon_name("go-previous-symbolic")
            .tooltip_text("返回实例列表")
            .visible(false)
            .build();
        back.add_css_class("flat");
        header.pack_start(&back);

        let sort_menu = gio::Menu::new();
        for order in SortOrder::ALL {
            sort_menu.append_item(&widgets::menu_item(order.label(), "app.sort", order.id()));
        }
        let sort_button = gtk::MenuButton::builder()
            .icon_name("view-sort-descending-symbolic")
            .tooltip_text("排序")
            .menu_model(&sort_menu)
            .build();
        sort_button.add_css_class("flat");
        header.pack_end(&sort_button);

        let search = gtk::SearchEntry::new();
        search.set_placeholder_text(Some("搜索实例名称、版本或加载器"));
        search.set_margin_top(design::SPACING_6);
        search.set_margin_bottom(design::SPACING_3);
        search.set_margin_start(design::SPACING_11);
        search.set_margin_end(design::SPACING_11);

        let groups = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_9);
        groups.set_margin_top(design::SPACING_3);
        groups.set_margin_bottom(design::SPACING_10);
        groups.set_margin_start(design::SPACING_11);
        groups.set_margin_end(design::SPACING_11);

        let list_scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vexpand(true)
            .child(&groups)
            .build();

        let pane = gtk::Box::new(gtk::Orientation::Vertical, 0);
        pane.append(&search);
        pane.append(&list_scroller);

        let detail = DetailPane::new(state);

        let split = adw::OverlaySplitView::new();
        split.set_sidebar_width_unit(adw::LengthUnit::Px);
        split.set_sidebar_width_fraction(0.667);
        split.set_min_sidebar_width(420.0);
        split.set_max_sidebar_width(760.0);
        split.set_sidebar(Some(&pane));
        split.set_content(Some(detail.widget()));
        split.set_show_sidebar(true);

        let split_for_back = split.clone();
        back.connect_clicked(move |_| split_for_back.set_show_sidebar(true));
        {
            let back = back.clone();
            split.connect_collapsed_notify(move |split| {
                back.set_visible(split.is_collapsed() && !split.shows_sidebar());
            });
        }
        {
            let back = back.clone();
            split.connect_show_sidebar_notify(move |split| {
                back.set_visible(split.is_collapsed() && !split.shows_sidebar());
            });
        }

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
        overlay.set_child(Some(&split));
        overlay.add_overlay(&loading);

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&overlay));
        let page = adw::NavigationPage::new(&toolbar, "实例库");

        let this = Rc::new_cyclic(|weak: &Weak<Self>| {
            let weak_for_search = weak.clone();
            search.connect_search_changed(move |_| {
                if let Some(page) = weak_for_search.upgrade() {
                    page.rebuild();
                }
            });

            Self {
                page,
                split,
                state: Rc::clone(state),
                search,
                groups,
                title,
                sort: Cell::new(SortOrder::Recent),
                selected: RefCell::new(None),
                entries: RefCell::new(Vec::new()),
                syncing: Cell::new(false),
                dirty: Cell::new(false),
                loading,
                self_weak: weak.clone(),
                detail,
            }
        });

        this.selected
            .replace(this.state.current_instance_id().into());
        this.rebuild();
        this
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

    /// Compact mode starts on the list and lets the first row click open the
    /// detail overlay. Unselecting keeps the already-selected current instance
    /// from swallowing that first click.
    pub fn prepare_compact(&self) {
        self.syncing.set(true);
        for entry in self.entries.borrow().iter() {
            entry.list.unselect_all();
        }
        self.syncing.set(false);
    }

    /// Wide layout restores the highlight for the selected instance.
    pub fn restore_selection(&self) {
        let selected = self.selected.borrow().clone();
        self.apply_selection(selected.as_deref());
    }

    /// Called by the shell every time the page becomes visible.
    pub fn on_shown(&self) {
        if self.dirty.replace(false) {
            self.rebuild();
        }
    }

    /// Selects an instance and makes sure it is visible in the filtered list.
    pub fn select(&self, id: &str) {
        self.selected.replace(Some(id.to_owned()));
        if !self.search.text().is_empty() {
            // Setting the text triggers `search_changed`, which rebuilds.
            self.search.set_text("");
        }
        self.rebuild();
    }

    pub fn set_sort(&self, order: SortOrder) {
        if self.sort.get() == order {
            return;
        }
        self.sort.set(order);
        self.rebuild();
    }

    /// Focuses the filter entry; used by the Ctrl+F accelerator.
    pub fn focus_search(&self) {
        self.search.grab_focus();
        self.search.select_region(0, -1);
    }

    fn refresh(&self) {
        self.loading.set_visible(self.state.is_initial_loading());
        if !self.page.is_mapped() {
            self.dirty.set(true);
            return;
        }
        self.dirty.set(false);
        self.rebuild();
    }

    fn rebuild(&self) {
        let query = self.search.text().to_string();
        let instances = self.state.instances();

        let mut selected = self.selected.borrow().clone();
        if selected
            .as_deref()
            .is_none_or(|id| !instances.iter().any(|instance| instance.id == id))
        {
            selected = Some(self.state.current_instance_id()).filter(|id| !id.is_empty());
            if selected.is_none() {
                selected = instances.first().map(|instance| instance.id.clone());
            }
            self.selected.replace(selected.clone());
        }

        while let Some(child) = self.groups.first_child() {
            self.groups.remove(&child);
        }
        self.entries.borrow_mut().clear();

        let sections = models::grouped_instances(&instances, &query, self.sort.get());
        for (group, items) in &sections {
            let group_box = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_3);
            group_box.append(&widgets::group_header(group.title(), items.len()));

            let list = gtk::ListBox::new();
            list.add_css_class("boxed-list");
            list.set_selection_mode(gtk::SelectionMode::Single);
            {
                let weak = self.self_weak.clone();
                list.connect_row_selected(move |list, row| {
                    if let Some(page) = weak.upgrade() {
                        page.on_row_selected(list, row);
                    }
                });
            }
            {
                let weak = self.self_weak.clone();
                list.connect_row_activated(move |_, row| {
                    if let Some(page) = weak.upgrade()
                        && let Some(id) = page.entry_id(row)
                    {
                        page.state.show_instance_settings(&id);
                    }
                });
            }
            for instance in items {
                let row = widgets::instance_row(instance, RowStyle::Library);
                row.set_widget_name(&instance.id);
                self.entries.borrow_mut().push(Entry {
                    list: list.clone(),
                    row: row.clone(),
                    id: instance.id.clone(),
                });
                list.append(&row);
            }

            group_box.append(&list);
            self.groups.append(&group_box);
        }

        if sections.is_empty() {
            let no_instances = instances.is_empty();
            let empty = if no_instances {
                adw::StatusPage::builder()
                    .icon_name("applications-games-symbolic")
                    .title("还没有实例")
                    .description(
                        "通过下载页选择 Minecraft 版本、加载器并安装，几分钟后就能开始游戏。",
                    )
                    .build()
            } else {
                adw::StatusPage::builder()
                    .icon_name("edit-find-symbolic")
                    .title("没有匹配的实例")
                    .description("换个关键词试试：实例名称、Minecraft 版本或加载器。")
                    .build()
            };
            empty.set_vexpand(true);

            let actions = gtk::Box::new(gtk::Orientation::Horizontal, 8);
            actions.set_halign(gtk::Align::Center);
            actions.set_margin_top(design::SPACING_3);
            if !no_instances {
                let clear = gtk::Button::with_label("清除搜索");
                {
                    let search = self.search.clone();
                    clear.connect_clicked(move |_| search.set_text(""));
                }
                actions.append(&clear);
            }
            let add = gtk::Button::with_label("添加实例");
            add.add_css_class("suggested-action");
            add.set_action_name(Some("app.new-instance"));
            actions.append(&add);
            if no_instances {
                let import = gtk::Button::with_label("导入现有实例");
                import.set_action_name(Some("app.import-instance"));
                actions.append(&import);
            }
            empty.set_child(Some(&actions));
            self.groups.append(&empty);
        }

        self.title.set_subtitle(&widgets::library_summary(
            instances.len(),
            models::total_size(&instances),
        ));
        self.apply_selection(selected.as_deref());

        if let Some(instance) = selected
            .as_deref()
            .and_then(|id| instances.iter().find(|instance| instance.id == id))
        {
            self.detail.show(Some(instance));
        } else {
            self.detail.show(None);
        }

        self.loading.set_visible(self.state.is_initial_loading());
    }

    fn entry_id(&self, row: &gtk::ListBoxRow) -> Option<String> {
        self.entries
            .borrow()
            .iter()
            .find(|entry| entry.row == *row)
            .map(|entry| entry.id.clone())
    }

    fn apply_selection(&self, selected: Option<&str>) {
        // In compact list mode no row stays highlighted: the selected row would
        // otherwise swallow the first click instead of revealing the detail
        // overlay.
        let compact_list = self.split.is_collapsed() && self.split.shows_sidebar();
        self.syncing.set(true);
        for entry in self.entries.borrow().iter() {
            if !compact_list && Some(entry.id.as_str()) == selected {
                entry.list.select_row(Some(&entry.row));
            } else {
                entry.list.unselect_all();
            }
        }
        self.syncing.set(false);
    }

    fn on_row_selected(&self, list: &gtk::ListBox, row: Option<&gtk::ListBoxRow>) {
        if self.syncing.get() {
            return;
        }
        let Some(row) = row else {
            return;
        };
        let id = row.widget_name().to_string();
        if id.is_empty() {
            return;
        }

        self.selected.replace(Some(id.clone()));
        if let Some(instance) = self.state.instance(&id) {
            self.detail.show(Some(&instance));
        }

        self.syncing.set(true);
        for entry in self.entries.borrow().iter() {
            if entry.list != *list {
                entry.list.unselect_all();
            }
        }
        self.syncing.set(false);

        if self.split.is_collapsed() {
            self.split.set_show_sidebar(false);
            // Leaving the row selected would make the next click on the same
            // row a no-op when the user comes back from the detail pane.
            self.syncing.set(true);
            for entry in self.entries.borrow().iter() {
                entry.list.unselect_all();
            }
            self.syncing.set(false);
        }
    }
}
