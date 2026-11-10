//! Modal surfaces: the install wizard and 游戏设置.

use adw::prelude::*;

pub mod install;
pub mod instance_settings;

/// Header bar for a modal surface. `Adw.Dialog` does not draw a title header on
/// its own when it hosts a plain widget, so both dialogs build this explicitly.
pub fn dialog_header(dialog: &adw::Dialog, title: &str, subtitle: &str) -> adw::HeaderBar {
    let header = adw::HeaderBar::new();
    header.set_show_start_title_buttons(false);
    header.set_show_end_title_buttons(false);
    header.set_title_widget(Some(&adw::WindowTitle::new(title, subtitle)));

    let close = gtk::Button::builder()
        .icon_name("window-close-symbolic")
        .tooltip_text("关闭")
        .build();
    close.add_css_class("flat");
    close.add_css_class("circular");
    close.update_property(&[gtk::accessible::Property::Label("关闭")]);
    let dialog = dialog.clone();
    close.connect_clicked(move |_| {
        dialog.close();
    });
    header.pack_end(&close);
    header
}
