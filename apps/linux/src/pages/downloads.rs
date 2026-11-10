//! Downloads page: version browser, install preview, install dialog and queue.
//!
//! Follows the HMCL/PCL flow the macOS client established: the category switcher
//! lives in the titlebar, the 游戏 tab lists remote versions, the detail pane
//! shows the computed install plan, and loader / version / name are chosen in
//! the modal install dialog. Memory, JVM and display options stay in 游戏设置.

use std::{
    cell::{Cell, RefCell},
    rc::{Rc, Weak},
};

use adw::prelude::*;

use crate::design;
use crate::dialogs;
use crate::models::{self, DownloadChannel, DownloadsCategory, VersionEntry};
use crate::state::{AppState, DownloadPageState};
use crate::widgets;

/// What the version pane was last drawn from. The event pump refreshes state
/// several times per second while a task runs, so rebuilding a 500-row version
/// list on every progress tick would be wasteful.
#[derive(Default, PartialEq)]
struct Signature {
    versions_len: usize,
    selected: Option<String>,
    loading: bool,
    versions_error: Option<String>,
    plan: Option<(u32, usize, u64)>,
    plan_error: Option<String>,
    channel: usize,
}

impl Signature {
    fn of(view: &DownloadPageState) -> Self {
        Self {
            versions_len: view.versions.len(),
            selected: view.selected.clone(),
            loading: view.loading,
            versions_error: view.versions_error.clone(),
            plan: view
                .plan
                .as_ref()
                .map(|plan| (plan.java_major, plan.files, plan.total_bytes)),
            plan_error: view.plan_error.clone(),
            channel: DownloadChannel::ALL
                .iter()
                .position(|channel| *channel == view.channel)
                .unwrap_or(0),
        }
    }
}

pub struct DownloadsPage {
    pub page: adw::NavigationPage,
    /// Version/detail split, collapsed by the shell breakpoint on narrow
    /// windows so the detail pane never pushes the page past the window edge.
    pub split: adw::OverlaySplitView,
    /// Queue rail hidden by the shell breakpoint on narrow windows.
    pub queue: gtk::Box,
    state: Rc<AppState>,
    channel: gtk::DropDown,
    category_switcher: gtk::StackSwitcher,
    category_dropdown: gtk::DropDown,
    version_search: gtk::SearchEntry,
    version_list: gtk::ListBox,
    version_scroller: gtk::ScrolledWindow,
    version_loading: gtk::Spinner,
    version_status: gtk::Label,
    detail_stack: gtk::Stack,
    detail_title: gtk::Label,
    detail_kind: gtk::Label,
    plan_list: gtk::ListBox,
    plan_status: gtk::Label,
    plan_spinner: gtk::Spinner,
    install_button: gtk::Button,
    install_hint: gtk::Label,
    queue_speed: gtk::Label,
    queue_list: gtk::ListBox,
    queue_empty: gtk::Label,
    syncing_channel: Cell<bool>,
    syncing_selection: Cell<bool>,
    loaded: Cell<bool>,
    dirty: Cell<bool>,
    syncing_category: Cell<bool>,
    signature: RefCell<Signature>,
    self_weak: Weak<Self>,
}

impl DownloadsPage {
    #[must_use]
    pub fn new(state: &Rc<AppState>) -> Rc<Self> {
        let stack = gtk::Stack::new();
        stack.set_transition_type(gtk::StackTransitionType::Crossfade);
        stack.set_vexpand(true);

        let header = adw::HeaderBar::new();
        let switcher = gtk::StackSwitcher::new();
        switcher.set_stack(Some(&stack));
        switcher.set_halign(gtk::Align::Center);

        let category_labels: Vec<&str> = DownloadsCategory::ALL
            .iter()
            .map(|category| category.short_title())
            .collect();
        let category_dropdown = gtk::DropDown::from_strings(&category_labels);
        category_dropdown.set_visible(false);
        category_dropdown.set_tooltip_text(Some("选择下载分类"));
        let category_box = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        category_box.set_halign(gtk::Align::Center);
        category_box.append(&switcher);
        category_box.append(&category_dropdown);
        header.set_title_widget(Some(&category_box));

        let channel = gtk::DropDown::from_strings(&[
            DownloadChannel::Release.title(),
            DownloadChannel::Snapshot.title(),
            DownloadChannel::AprilFools.title(),
            DownloadChannel::Old.title(),
        ]);
        channel.set_tooltip_text(Some("选择正式版、快照、愚人节或远古版本"));
        channel.set_valign(gtk::Align::Center);
        header.pack_end(&channel);

        let refresh = gtk::Button::builder()
            .icon_name("view-refresh-symbolic")
            .tooltip_text("重新读取版本清单")
            .build();
        refresh.add_css_class("flat");
        header.pack_end(&refresh);

        let (game_page, game) = game_page();
        stack.add_titled(
            &game_page,
            Some("game"),
            DownloadsCategory::Game.short_title(),
        );
        for category in DownloadsCategory::ALL {
            if category == DownloadsCategory::Game {
                continue;
            }
            stack.add_titled(
                &placeholder_page(category),
                Some(category_key(category)),
                category.short_title(),
            );
        }

        let toolbar = adw::ToolbarView::new();
        toolbar.add_top_bar(&header);
        toolbar.set_content(Some(&stack));

        let page = adw::NavigationPage::new(&toolbar, "下载");

        let this = Rc::new_cyclic(|weak: &Weak<Self>| {
            {
                let weak_for_search = weak.clone();
                game.version_search.connect_search_changed(move |_| {
                    if let Some(page) = weak_for_search.upgrade() {
                        page.refresh_versions();
                    }
                });
            }
            {
                let weak_for_selection = weak.clone();
                game.version_list.connect_row_selected(move |_, row| {
                    let Some(page) = weak_for_selection.upgrade() else {
                        return;
                    };
                    if page.syncing_selection.get() {
                        return;
                    }
                    if let Some(row) = row {
                        let id = row.widget_name().to_string();
                        if !id.is_empty() {
                            page.state.select_download_version(&id);
                            if page.split.is_collapsed() {
                                page.split.set_show_sidebar(false);
                                // The row stays selected in the hidden list;
                                // clearing it keeps the next click effective.
                                page.syncing_selection.set(true);
                                page.version_list.unselect_all();
                                page.syncing_selection.set(false);
                            }
                        }
                    }
                });
            }

            Self {
                page,
                split: game.split.clone(),
                queue: game.queue,
                state: Rc::clone(state),
                channel: channel.clone(),
                category_switcher: switcher.clone(),
                category_dropdown: category_dropdown.clone(),
                version_search: game.version_search,
                version_list: game.version_list,
                version_scroller: game.version_scroller,
                version_loading: game.version_loading,
                version_status: game.version_status,
                detail_stack: game.detail_stack,
                detail_title: game.detail_title,
                detail_kind: game.detail_kind,
                plan_list: game.plan_list,
                plan_status: game.plan_status,
                plan_spinner: game.plan_spinner,
                install_button: game.install_button,
                install_hint: game.install_hint,
                queue_speed: game.queue_speed,
                queue_list: game.queue_list,
                queue_empty: game.queue_empty,
                syncing_channel: Cell::new(false),
                syncing_selection: Cell::new(false),
                syncing_category: Cell::new(false),
                loaded: Cell::new(false),
                dirty: Cell::new(false),
                signature: RefCell::new(Signature::default()),
                self_weak: weak.clone(),
            }
        });

        {
            let weak = this.self_weak.clone();
            this.channel.connect_selected_notify(move |dropdown| {
                let Some(page) = weak.upgrade() else {
                    return;
                };
                if page.syncing_channel.get() {
                    return;
                }
                let index = dropdown.selected() as usize;
                if let Some(channel) = DownloadChannel::ALL.get(index) {
                    page.state.set_download_channel(*channel);
                }
            });
        }
        {
            let weak = this.self_weak.clone();
            let stack = stack.clone();
            this.category_dropdown
                .connect_selected_notify(move |dropdown| {
                    let Some(page) = weak.upgrade() else {
                        return;
                    };
                    if page.syncing_category.get() {
                        return;
                    }
                    let index = dropdown.selected() as usize;
                    if let Some(category) = DownloadsCategory::ALL.get(index) {
                        page.syncing_category.set(true);
                        stack.set_visible_child_name(category_key(*category));
                        page.syncing_category.set(false);
                    }
                });
        }
        {
            let weak = this.self_weak.clone();
            let dropdown = this.category_dropdown.clone();
            stack.connect_visible_child_name_notify(move |stack| {
                let Some(page) = weak.upgrade() else {
                    return;
                };
                page.channel
                    .set_visible(stack.visible_child_name().as_deref() == Some("game"));
                if let Some(name) = stack.visible_child_name()
                    && let Some(index) = DownloadsCategory::ALL
                        .iter()
                        .position(|category| category_key(*category) == name.as_str())
                {
                    page.syncing_category.set(true);
                    dropdown.set_selected(index as u32);
                    page.syncing_category.set(false);
                }
            });
        }
        {
            let weak = this.self_weak.clone();
            this.install_button.connect_clicked(move |button| {
                let Some(page) = weak.upgrade() else {
                    return;
                };
                let Some(root) = button.root() else {
                    return;
                };
                let Ok(window) = root.downcast::<gtk::Window>() else {
                    return;
                };
                let Some(version) = page.state.download_page().selected else {
                    return;
                };
                dialogs::install::present(&window, &page.state, &version);
            });
        }
        {
            let weak = this.self_weak.clone();
            refresh.connect_clicked(move |_| {
                if let Some(page) = weak.upgrade() {
                    page.loaded.set(true);
                    *page.signature.borrow_mut() = Signature::default();
                    page.state.load_download_versions(true);
                }
            });
        }
        this.refresh();
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

    /// Below the shell breakpoint the six-way segmented switcher is wider than
    /// the titlebar can hold, so a native dropdown takes its place.
    pub fn set_compact_header(&self, compact: bool) {
        self.category_switcher.set_visible(!compact);
        self.category_dropdown.set_visible(compact);
        if compact {
            self.syncing_selection.set(true);
            self.version_list.unselect_all();
            self.syncing_selection.set(false);
        } else {
            self.refresh_versions();
        }
    }

    /// Called by the shell when the page becomes visible; the manifest is only
    /// fetched once unless the user asks for a refresh.
    pub fn on_shown(&self) {
        if !self.loaded.replace(true) {
            self.state.load_download_versions(false);
        }
        if self.dirty.replace(false) {
            self.refresh();
        }
    }

    fn refresh(&self) {
        let view = self.state.download_page();

        let index = DownloadChannel::ALL
            .iter()
            .position(|channel| *channel == view.channel)
            .unwrap_or(0) as u32;
        self.syncing_channel.set(true);
        self.channel.set_selected(index);
        self.syncing_channel.set(false);

        let signature = Signature::of(&view);
        let changed = *self.signature.borrow() != signature;
        if changed {
            *self.signature.borrow_mut() = signature;
            self.refresh_versions();
        }

        self.install_button
            .set_sensitive(!view.install_running && view.selected.is_some());
        self.install_hint.set_visible(view.install_running);

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
    }

    fn refresh_versions(&self) {
        let view = self.state.download_page();
        let query = self.version_search.text().trim().to_lowercase();

        self.version_loading
            .set_visible(view.loading && view.versions.is_empty());
        if view.loading && view.versions.is_empty() {
            self.version_loading.start();
        } else {
            self.version_loading.stop();
        }

        while let Some(child) = self.version_list.first_child() {
            self.version_list.remove(&child);
        }

        let filtered: Vec<&VersionEntry> = view
            .versions
            .iter()
            .filter(|entry| query.is_empty() || entry.id.to_lowercase().contains(&query))
            .take(80)
            .collect();

        let show_list = !filtered.is_empty();
        self.version_scroller.set_visible(show_list);
        self.version_status.set_visible(!show_list);

        if view.versions.is_empty() {
            if view.loading {
                self.version_status.set_label("版本清单加载中…");
                self.version_status.remove_css_class("error");
            } else if let Some(error) = &view.versions_error {
                self.version_status.set_label(error);
                self.version_status.add_css_class("error");
            } else {
                self.version_status.set_label(view.channel.empty_message());
                self.version_status.remove_css_class("error");
            }
        } else if filtered.is_empty() {
            self.version_status.set_label("没有匹配的版本");
            self.version_status.remove_css_class("error");
        }

        if show_list {
            for entry in &filtered {
                let row = version_row(entry);
                row.set_widget_name(&entry.id);
                self.version_list.append(&row);
            }

            // Compact list mode leaves the row unselected so any click opens
            // the detail overlay, including the version selected by default.
            let compact_list = self.split.is_collapsed() && self.split.shows_sidebar();
            if let Some(selected) = view.selected.as_deref()
                && !compact_list
            {
                self.syncing_selection.set(true);
                let mut index = 0;
                while let Some(row) = self.version_list.row_at_index(index) {
                    if row.widget_name().as_str() == selected {
                        self.version_list.select_row(Some(&row));
                        break;
                    }
                    index += 1;
                }
                self.syncing_selection.set(false);
            }
        }

        self.refresh_detail(&view);
    }

    fn refresh_detail(&self, view: &DownloadPageState) {
        let Some(selected) = view
            .selected
            .as_deref()
            .and_then(|id| view.versions.iter().find(|entry| entry.id == id))
        else {
            self.detail_stack.set_visible_child_name("empty");
            return;
        };

        self.detail_stack.set_visible_child_name("detail");
        self.detail_title.set_label(&selected.id);
        self.detail_kind.set_label(&selected.kind_label());

        while let Some(child) = self.plan_list.first_child() {
            self.plan_list.remove(&child);
        }

        let mut has_plan_rows = false;
        if let Some(plan) = &view.plan {
            self.plan_list.append(&widgets::key_value_row(
                "Java 运行时",
                &format!("Java {}", plan.java_major),
                false,
            ));
            self.plan_list.append(&widgets::key_value_row(
                "文件",
                &format!("{} 个", plan.files),
                false,
            ));
            self.plan_list.append(&widgets::key_value_row(
                "体积",
                &models::format_size(plan.total_bytes),
                false,
            ));
            if let Some(released) = selected.released_date() {
                self.plan_list
                    .append(&widgets::key_value_row("发布", &released, false));
            }
            has_plan_rows = true;
        }
        self.plan_list.set_visible(has_plan_rows);

        let calculating = view.plan.is_none() && view.plan_error.is_none();
        self.plan_spinner.set_visible(calculating);
        if calculating {
            self.plan_spinner.start();
        } else {
            self.plan_spinner.stop();
        }

        self.plan_status.set_visible(!has_plan_rows);
        if let Some(error) = &view.plan_error {
            self.plan_status
                .set_label(&format!("无法计算安装内容：{error}"));
            self.plan_status.add_css_class("error");
        } else if calculating {
            self.plan_status.set_label("正在计算安装内容…");
            self.plan_status.remove_css_class("error");
        }
    }
}

struct GamePage {
    split: adw::OverlaySplitView,
    queue: gtk::Box,
    version_search: gtk::SearchEntry,
    version_list: gtk::ListBox,
    version_scroller: gtk::ScrolledWindow,
    version_loading: gtk::Spinner,
    version_status: gtk::Label,
    detail_stack: gtk::Stack,
    detail_title: gtk::Label,
    detail_kind: gtk::Label,
    plan_list: gtk::ListBox,
    plan_status: gtk::Label,
    plan_spinner: gtk::Spinner,
    install_button: gtk::Button,
    install_hint: gtk::Label,
    queue_speed: gtk::Label,
    queue_list: gtk::ListBox,
    queue_empty: gtk::Label,
}

fn game_page() -> (gtk::Box, GamePage) {
    let version_search = gtk::SearchEntry::new();
    version_search.set_placeholder_text(Some("搜索版本"));
    version_search.set_margin_bottom(design::SPACING_3);

    let version_list = gtk::ListBox::new();
    version_list.set_selection_mode(gtk::SelectionMode::Single);
    version_list.add_css_class("fograin-version-list");

    let version_scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .child(&version_list)
        .build();

    let version_loading = gtk::Spinner::new();
    version_loading.set_visible(false);
    let version_status = gtk::Label::builder()
        .label("正在读取版本清单…")
        .xalign(0.0)
        .wrap(true)
        .build();
    version_status.add_css_class("caption");
    version_status.add_css_class("dim-label");

    let version_pane = gtk::Box::new(gtk::Orientation::Vertical, 6);
    version_pane.set_valign(gtk::Align::Fill);
    version_pane.append(&version_search);
    version_pane.append(&version_loading);
    version_pane.append(&version_status);
    version_pane.append(&version_scroller);

    let detail_title = gtk::Label::builder().xalign(0.0).build();
    detail_title.add_css_class("title-2");
    let detail_kind = gtk::Label::new(None);
    detail_kind.add_css_class("fograin-milestone-chip");
    let title_row = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    title_row.append(&detail_title);
    title_row.append(&detail_kind);

    let plan_list = gtk::ListBox::new();
    plan_list.add_css_class("boxed-list");
    plan_list.set_selection_mode(gtk::SelectionMode::None);
    plan_list.set_visible(false);

    let plan_spinner = gtk::Spinner::new();
    plan_spinner.set_visible(false);
    plan_spinner.set_halign(gtk::Align::Start);
    let plan_status = gtk::Label::builder()
        .xalign(0.0)
        .wrap(true)
        .visible(false)
        .build();
    plan_status.add_css_class("caption");
    plan_status.add_css_class("dim-label");

    let install_button = gtk::Button::new();
    let install_content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    install_content.append(&gtk::Image::from_icon_name("folder-download-symbolic"));
    install_content.append(&gtk::Label::new(Some("安装")));
    install_button.set_child(Some(&install_content));
    install_button.add_css_class("suggested-action");
    install_button.add_css_class("pill");
    install_button.set_tooltip_text(Some("下载并安装所选版本"));
    let install_hint = gtk::Label::builder()
        .label("任务进行中…可在右侧队列查看进度")
        .xalign(0.0)
        .visible(false)
        .build();
    install_hint.add_css_class("caption");
    install_hint.add_css_class("dim-label");

    let actions = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_3);
    actions.append(&install_button);
    actions.append(&install_hint);

    let detail_content = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_4);
    detail_content.append(&title_row);
    detail_content.append(&plan_spinner);
    detail_content.append(&plan_status);
    detail_content.append(&plan_list);
    detail_content.append(&actions);

    let detail_clamp = adw::Clamp::new();
    detail_clamp.set_maximum_size(520);
    detail_clamp.set_hexpand(true);
    detail_clamp.set_child(Some(&detail_content));

    let detail_scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&detail_clamp)
        .build();

    let detail_empty = adw::StatusPage::builder()
        .icon_name("applications-games-symbolic")
        .title("选择一个版本")
        .description("在左侧选择一个 Minecraft 版本后即可安装。")
        .build();
    detail_empty.set_vexpand(true);

    let detail_stack = gtk::Stack::new();
    detail_stack.set_transition_type(gtk::StackTransitionType::Crossfade);
    detail_stack.add_named(&detail_empty, Some("empty"));
    detail_stack.add_named(&detail_scroller, Some("detail"));
    detail_stack.set_visible_child_name("empty");
    detail_stack.set_hexpand(true);
    detail_stack.set_vexpand(true);

    let queue_speed = gtk::Label::new(Some("空闲"));
    queue_speed.add_css_class("caption");
    queue_speed.add_css_class("dim-label");

    let queue_list = gtk::ListBox::new();
    queue_list.add_css_class("boxed-list");
    queue_list.set_selection_mode(gtk::SelectionMode::None);
    let queue_empty = widgets::empty_label("没有进行中的任务");

    let history_title = gtk::Label::builder().label("最近完成").xalign(0.0).build();
    history_title.add_css_class("fograin-section-title");
    let history_text = gtk::Label::builder()
        .label("完成的安装会进入实例库，并可继续在「游戏设置」里调整内存与显示选项。")
        .xalign(0.0)
        .wrap(true)
        .build();
    history_text.add_css_class("caption");
    history_text.add_css_class("dim-label");

    let queue_content = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    queue_content.set_margin_top(design::CONTENT_PADDING);
    queue_content.set_margin_bottom(design::CONTENT_PADDING);
    queue_content.set_margin_start(design::SPACING_6);
    queue_content.set_margin_end(design::SPACING_6);
    queue_content.append(&widgets::section_header("任务队列", |header| {
        header.append(&queue_speed);
    }));
    queue_content.append(&queue_empty);
    queue_content.append(&queue_list);
    queue_content.append(&gtk::Separator::new(gtk::Orientation::Horizontal));
    queue_content.append(&history_title);
    queue_content.append(&history_text);

    let queue_scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .child(&queue_content)
        .build();
    queue_scroller.set_propagate_natural_width(false);

    let queue = gtk::Box::new(gtk::Orientation::Vertical, 0);
    queue.add_css_class("fograin-queue-column");
    queue.set_size_request(240, -1);
    queue.set_hexpand(false);
    queue.append(&queue_scroller);

    // Native split: both panes on wide windows; below the breakpoint the list
    // is the entry point and picking a version reveals the detail overlay.
    let back = gtk::Button::builder()
        .tooltip_text("返回版本列表")
        .visible(false)
        .build();
    let back_content = gtk::Box::new(gtk::Orientation::Horizontal, 6);
    back_content.append(&gtk::Image::from_icon_name("go-previous-symbolic"));
    back_content.append(&gtk::Label::new(Some("版本列表")));
    back.set_child(Some(&back_content));
    back.add_css_class("flat");
    back.set_halign(gtk::Align::Start);
    back.set_margin_start(design::CONTENT_PADDING);
    back.set_margin_top(design::SPACING_3);

    let detail_root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    detail_root.append(&back);
    detail_root.append(&detail_stack);

    let split = adw::OverlaySplitView::new();
    split.set_sidebar_width_unit(adw::LengthUnit::Px);
    split.set_sidebar_width_fraction(0.34);
    split.set_min_sidebar_width(200.0);
    split.set_max_sidebar_width(300.0);
    split.set_sidebar(Some(&version_pane));
    split.set_content(Some(&detail_root));
    split.set_show_sidebar(true);
    split.set_hexpand(true);

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

    let separator = gtk::Separator::new(gtk::Orientation::Vertical);
    separator.set_margin_top(design::SPACING_4);
    separator.set_margin_bottom(design::SPACING_4);

    let body = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_6);
    body.set_margin_top(design::CONTENT_PADDING);
    body.set_margin_bottom(design::CONTENT_PADDING);
    body.set_margin_start(design::CONTENT_PADDING);
    body.set_margin_end(design::CONTENT_PADDING);
    body.set_hexpand(true);
    body.set_vexpand(true);
    body.append(&split);
    body.append(&separator);
    body.append(&queue);

    let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
    root.set_vexpand(true);
    root.append(&body);

    let game = GamePage {
        split,
        queue,
        version_search,
        version_list,
        version_scroller,
        version_loading,
        version_status,
        detail_stack,
        detail_title,
        detail_kind,
        plan_list,
        plan_status,
        plan_spinner,
        install_button,
        install_hint,
        queue_speed,
        queue_list,
        queue_empty,
    };
    (root, game)
}

fn version_row(entry: &VersionEntry) -> gtk::ListBoxRow {
    let row = gtk::ListBoxRow::new();
    row.set_activatable(true);

    let content = gtk::Box::new(gtk::Orientation::Horizontal, design::SPACING_3);
    content.set_margin_top(7);
    content.set_margin_bottom(7);
    content.set_margin_start(10);
    content.set_margin_end(10);

    let icon = gtk::Image::from_icon_name("applications-games-symbolic");
    icon.add_css_class("dim-label");
    content.append(&icon);

    let name = gtk::Label::builder()
        .label(&entry.id)
        .xalign(0.0)
        .hexpand(true)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    content.append(&name);

    if let Some(released) = entry.released_date() {
        let date = gtk::Label::new(Some(&released));
        date.add_css_class("caption");
        date.add_css_class("dim-label");
        content.append(&date);
    }

    row.set_child(Some(&content));
    row.update_property(&[gtk::accessible::Property::Label(&entry.id)]);
    row
}

fn placeholder_page(category: DownloadsCategory) -> gtk::Box {
    let root = gtk::Box::new(gtk::Orientation::Vertical, design::SPACING_6);
    root.set_vexpand(true);

    let status = adw::StatusPage::builder()
        .icon_name(category.icon())
        .title(category.title())
        .description(category.description())
        .build();
    status.set_vexpand(true);
    root.append(&status);

    if let Some(milestone) = category.milestone() {
        let chip = widgets::milestone_chip(&format!("{milestone} 接入"));
        chip.set_halign(gtk::Align::Center);
        chip.set_margin_bottom(design::CONTENT_PADDING);
        root.append(&chip);
    }
    root
}

const fn category_key(category: DownloadsCategory) -> &'static str {
    match category {
        DownloadsCategory::Game => "game",
        DownloadsCategory::Mod => "mod",
        DownloadsCategory::Modpack => "modpack",
        DownloadsCategory::Resourcepack => "resourcepack",
        DownloadsCategory::Shaderpack => "shaderpack",
        DownloadsCategory::Datapack => "datapack",
    }
}
