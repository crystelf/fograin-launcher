//! Native GNOME client. GTK4 + libadwaita; no WebView, no CSS skin layer.

use adw::prelude::*;
use gtk::prelude::*;

const APP_ID: &str = "com.fograin.launcher";

fn main() -> gtk::glib::ExitCode {
    let app = adw::Application::builder().application_id(APP_ID).build();
    app.connect_activate(build_ui);
    app.run()
}

fn build_ui(app: &adw::Application) {
    let header = adw::HeaderBar::new();
    let title = gtk::Label::new(Some("主页"));
    title.add_css_class("title");
    header.set_title_widget(Some(&title));
    header.pack_start(&gtk::Button::from_icon_name("list-add-symbolic"));

    let sidebar = gtk::ListBox::new();
    sidebar.add_css_class("navigation-sidebar");
    for (label, icon) in [
        ("主页", "go-home-symbolic"),
        ("实例库", "view-grid-symbolic"),
        ("服务器", "network-server-symbolic"),
        ("下载", "folder-download-symbolic"),
        ("Java 运行时", "applications-utilities-symbolic"),
        ("账户", "avatar-default-symbolic"),
        ("设置", "emblem-system-symbolic"),
    ] {
        let row = adw::ActionRow::builder().title(label).build();
        row.add_prefix(&gtk::Image::from_icon_name(icon));
        sidebar.append(&row);
    }

    let sidebar_scroller = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&sidebar)
        .build();
    sidebar_scroller.set_size_request(240, -1);

    let placeholder = adw::StatusPage::builder()
        .title("原生 GTK4 外壳已就绪")
        .description("这一页之后会换成原生主页布局；不再使用 WebView 或 CSS 皮肤。")
        .icon_name("applications-system-symbolic")
        .build();
    placeholder.set_hexpand(true);
    placeholder.set_vexpand(true);

    let split = gtk::Paned::new(gtk::Orientation::Horizontal);
    split.set_start_child(Some(&sidebar_scroller));
    split.set_end_child(Some(&placeholder));
    split.set_position(240);
    split.set_resize_start_child(false);
    split.set_shrink_start_child(false);

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header);
    toolbar.set_content(Some(&split));

    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("雾雨启动器")
        .default_width(1400)
        .default_height(880)
        .content(&toolbar)
        .build();
    window.present();
}
