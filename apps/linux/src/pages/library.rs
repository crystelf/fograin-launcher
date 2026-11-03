//! Instance library: loader-grouped list pane plus adaptive detail pane.
//!
//! The pen shows a 760 px list and a 380 px detail column inside the content
//! area. Here the split is a native `Adw.OverlaySplitView`: it collapses into
//! the list on narrow windows, and selecting a row reveals the detail overlay
//! with a native back button.

use std::cell::{Cell, RefCell};
use std::rc::{Rc, Weak};

use adw::prelude::*;
use gtk::gio;

use crate::design;
use crate::models::{self, LibraryGroup, SortOrder};
use crate::pages::detail::DetailPane;
use crate::widgets::{self, RowStyle};

struct Entry {
    list: gtk::ListBox,
    row: gtk::ListBoxRow,
    id: &'static str,
}

pub struct LibraryPage {
    pub page: adw::NavigationPage,
    pub split: adw::OverlaySplitView,
    search: gtk::SearchEntry,
    groups: gtk::Box,
    title: adw::WindowTitle,
    sort: Cell<SortOrder>,
    selected: Cell<Option<&'static str>>,
    entries: RefCell<Vec<Entry>>,
    syncing: Cell<bool>,
    detail: DetailPane,
    self_weak: Weak<Self>,
}

impl LibraryPage {
    pub fn new() -> Rc<Self> {
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
        search.set_placeholder_text(Some("搜索实例名称、版本或模组"));
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

        let detail = DetailPane::new();

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

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&split));
        let page = adw::NavigationPage::new(&toolbar, "实例库");

        let search_signal = search.clone();
        let this = Rc::new_cyclic(|weak: &Weak<Self>| {
            let weak_for_search = weak.clone();
            search_signal.connect_search_changed(move |_| {
                if let Some(page) = weak_for_search.upgrade() {
                    page.rebuild();
                }
            });

            Self {
                page,
                split,
                search,
                groups,
                title,
                sort: Cell::new(SortOrder::Recent),
                selected: Cell::new(None),
                entries: RefCell::new(Vec::new()),
                syncing: Cell::new(false),
                detail,
                self_weak: weak.clone(),
            }
        });

        this.select(models::current().id);
        this
    }

    /// Selects an instance and makes sure it is visible in the filtered list.
    pub fn select(&self, id: &str) {
        let Some(instance) = models::by_id(id) else {
            return;
        };
        self.selected.set(Some(instance.id));
        self.detail.show(instance);
        if self.search.text().is_empty() {
            self.rebuild();
        } else {
            self.search.set_text("");
        }
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

    fn rebuild(&self) {
        let query = self.search.text().to_string();

        while let Some(child) = self.groups.first_child() {
            self.groups.remove(&child);
        }
        self.entries.borrow_mut().clear();

        let mut total = 0usize;
        let mut bytes = 0u64;

        for group in LibraryGroup::ALL {
            let items = models::group_items(&query, group, self.sort.get());
            if items.is_empty() {
                continue;
            }
            total += items.len();
            bytes += models::total_size(&items);

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
            for instance in items {
                let row = widgets::instance_row(instance, RowStyle::Library);
                self.entries.borrow_mut().push(Entry {
                    list: list.clone(),
                    row: row.clone(),
                    id: instance.id,
                });
                list.append(&row);
            }

            group_box.append(&list);
            self.groups.append(&group_box);
        }

        if total == 0 {
            let empty = adw::StatusPage::builder()
                .icon_name("edit-find-symbolic")
                .title("没有匹配的实例")
                .description("换个关键词试试：实例名称、Minecraft 版本或加载器。")
                .build();
            empty.set_vexpand(true);
            self.groups.append(&empty);
        }

        self.title
            .set_subtitle(&widgets::library_summary(total, bytes));
        self.apply_selection();
    }

    fn apply_selection(&self) {
        let selected = self.selected.get();
        self.syncing.set(true);
        for entry in self.entries.borrow().iter() {
            if Some(entry.id) == selected {
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
        let id = {
            let entries = self.entries.borrow();
            let Some(entry) = entries.iter().find(|entry| entry.row == *row) else {
                return;
            };
            entry.id
        };

        self.selected.set(Some(id));
        if let Some(instance) = models::by_id(id) {
            self.detail.show(instance);
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
        }
    }
}
