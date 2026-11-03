//! Placeholder pages for sections whose back end lands in a later milestone.
//!
//! These are real `Adw.StatusPage`s with the header bar the section will keep,
//! not disabled mockups: the client says what it does today.

use adw::prelude::*;

use crate::navigation::Section;

pub fn placeholder_page(section: Section) -> adw::NavigationPage {
    let header = adw::HeaderBar::new();
    header.set_title_widget(Some(&adw::WindowTitle::new(
        section.title(),
        section.subtitle(),
    )));

    let toolbar = adw::ToolbarView::new();
    toolbar.add_top_bar(&header);

    if let Some(milestone) = section.milestone() {
        let status = adw::StatusPage::builder()
            .icon_name(section.icon())
            .title(format!("{} · {}", section.title(), milestone.name))
            .description(milestone.detail)
            .build();
        status.set_vexpand(true);
        toolbar.set_content(Some(&status));
    }

    adw::NavigationPage::new(&toolbar, section.title())
}
