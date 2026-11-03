//! GNOME design tokens and the small GTK stylesheet derived from them.
//!
//! Colors, radii, spacing and type sizes are generated from `design/tokens.json`
//! by `build.rs`. Chrome colors come from the Adwaita named palette
//! (`@accent_bg_color`, `@card_bg_color`, …) so the client always follows the
//! user's GNOME theme; only content colors such as instance tiles are ours.

use gtk::{gdk, glib};

#[allow(dead_code)]
mod tokens {
    include!(concat!(env!("OUT_DIR"), "/tokens.rs"));
}

pub use tokens::*;

/// Inset of the clamped home content, matching the pen's 48 px on a 1140 px pane.
pub const CONTENT_PADDING: i32 = SPACING_10;
/// Gap between the recent-instances column and the status column.
pub const COLUMN_GAP: i32 = SPACING_11;
/// Vertical rhythm between sections.
pub const SECTION_GAP: i32 = SPACING_10;

const HERO_IMAGE: &[u8] = include_bytes!("../resources/hero-forest.jpg");
const APP_ICON: &[u8] =
    include_bytes!("../resources/icons/hicolor/256x256/apps/com.fograin.launcher.png");

/// Installs the application stylesheet on the default display.
pub fn install_stylesheet() {
    let Some(display) = gdk::Display::default() else {
        return;
    };
    let provider = gtk::CssProvider::new();
    provider.load_from_string(&stylesheet());
    gtk::style_context_add_provider_for_display(
        &display,
        &provider,
        gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
    );
}

/// Makes the bundled app icon resolvable by name for `Adw.AboutDialog`.
pub fn install_icon_theme() {
    let Some(display) = gdk::Display::default() else {
        return;
    };
    gtk::IconTheme::for_display(&display)
        .add_search_path(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/icons"));
}

/// The hero artwork used by the home banner, if the image backend can decode it.
pub fn hero_texture() -> Option<gdk::Texture> {
    gdk::Texture::from_bytes(&glib::Bytes::from_static(HERO_IMAGE)).ok()
}

/// The launcher icon for the sidebar brand row.
pub fn app_icon(pixel_size: i32) -> gtk::Image {
    match gdk::Texture::from_bytes(&glib::Bytes::from_static(APP_ICON)) {
        Ok(texture) => {
            let image = gtk::Image::from_paintable(Some(&texture));
            image.set_pixel_size(pixel_size);
            image
        }
        Err(_) => gtk::Image::from_icon_name("applications-system-symbolic"),
    }
}

fn rgb(color: (u8, u8, u8)) -> String {
    format!("rgb({}, {}, {})", color.0, color.1, color.2)
}

fn rgba(color: (u8, u8, u8), alpha: f32) -> String {
    format!("rgba({}, {}, {}, {alpha:.2})", color.0, color.1, color.2)
}

fn rule(css: &mut String, selector: &str, declarations: &str) {
    css.push_str(selector);
    css.push_str(" { ");
    css.push_str(declarations);
    css.push_str(" }\n");
}

fn stylesheet() -> String {
    let mut css = String::from("/* Generated from design/tokens.json at build time. */\n");

    for (class, background, foreground) in [
        ("fograin-tile-forest", INSTANCE_FOREST, INSTANCE_FOREST_TEXT),
        ("fograin-tile-copper", INSTANCE_COPPER, INSTANCE_COPPER_TEXT),
        ("fograin-tile-slate", INSTANCE_SLATE, INSTANCE_SLATE_TEXT),
        ("fograin-tile-violet", INSTANCE_VIOLET, INSTANCE_VIOLET_TEXT),
        ("fograin-tile-clay", INSTANCE_CLAY, INSTANCE_CLAY_TEXT),
        ("fograin-tile-lagoon", INSTANCE_LAGOON, INSTANCE_LAGOON_TEXT),
        ("fograin-tile-sand", INSTANCE_SAND, INSTANCE_SAND_TEXT),
        ("fograin-tile-moss", INSTANCE_MOSS, INSTANCE_MOSS_TEXT),
        ("fograin-tile-ash", INSTANCE_ASH, INSTANCE_ASH_TEXT),
    ] {
        rule(
            &mut css,
            &format!(".{class}"),
            &format!(
                "background-color: {}; color: {};",
                rgb(background),
                rgb(foreground)
            ),
        );
    }

    for (class, background, foreground) in [
        ("fograin-chip-fabric", LOADER_FABRIC, LOADER_FABRIC_TEXT),
        ("fograin-chip-quilt", LOADER_QUILT, LOADER_QUILT_TEXT),
        ("fograin-chip-forge", LOADER_FORGE, LOADER_FORGE_TEXT),
        (
            "fograin-chip-neoforge",
            LOADER_NEOFORGE,
            LOADER_NEOFORGE_TEXT,
        ),
        ("fograin-chip-vanilla", LOADER_VANILLA, LOADER_VANILLA_TEXT),
        (
            "fograin-chip-optifine",
            LOADER_OPTIFINE,
            LOADER_OPTIFINE_TEXT,
        ),
    ] {
        rule(
            &mut css,
            &format!(".{class}"),
            &format!(
                "background-color: {}; color: {};",
                rgb(background),
                rgb(foreground)
            ),
        );
    }

    rule(
        &mut css,
        ".fograin-tile",
        &format!("border-radius: {}px; font-weight: 700;", RADIUS_ICON),
    );
    rule(
        &mut css,
        ".fograin-tile-small",
        &format!("font-size: {}px;", TYPE_SUBHEAD),
    );
    rule(
        &mut css,
        ".fograin-tile-row",
        &format!("font-size: {}px;", TYPE_SUBHEAD),
    );
    rule(
        &mut css,
        ".fograin-tile-banner",
        &format!("font-size: {}px;", TYPE_TITLE),
    );
    rule(
        &mut css,
        ".fograin-tile-detail",
        &format!("font-size: {}px;", TYPE_HERO),
    );
    rule(
        &mut css,
        ".fograin-chip",
        &format!(
            "border-radius: {}px; padding: 2px 10px; font-size: {}px; font-weight: 600;",
            RADIUS_PILL, TYPE_SMALL
        ),
    );
    rule(
        &mut css,
        ".fograin-hero",
        &format!("border-radius: {}px;", RADIUS_CARD),
    );
    rule(
        &mut css,
        ".fograin-hero-image",
        &format!("border-radius: {}px;", RADIUS_CARD),
    );
    rule(
        &mut css,
        ".fograin-hero-bar",
        &format!(
            "background-image: linear-gradient(to bottom, rgba(0, 0, 0, 0), rgba(0, 0, 0, 0.78)); \
             border-radius: 0 0 {radius}px {radius}px; padding: 18px 24px;",
            radius = RADIUS_CARD
        ),
    );
    rule(
        &mut css,
        ".fograin-hero-title",
        &format!(
            "font-size: {}px; font-weight: 800; color: {};",
            TYPE_TITLE,
            rgb(TEXT_PRIMARY)
        ),
    );
    rule(
        &mut css,
        ".fograin-on-image",
        &format!("color: {};", rgb(TEXT_PRIMARY)),
    );
    rule(
        &mut css,
        ".fograin-on-image-dim",
        &format!("color: {};", rgba(TEXT_PRIMARY, 0.78)),
    );
    rule(
        &mut css,
        ".fograin-status-pill",
        &format!(
            "background-color: rgba(0, 0, 0, 0.45); border-radius: {}px; padding: 4px 12px;",
            RADIUS_PILL
        ),
    );
    rule(
        &mut css,
        ".fograin-status-dot",
        &format!("background-color: {}; border-radius: 99px;", rgb(SUCCESS)),
    );
    rule(&mut css, ".fograin-section-title", "font-weight: 700;");
    rule(&mut css, ".fograin-row-title", "font-weight: 600;");
    rule(&mut css, ".fograin-account-name", "font-weight: 600;");
    rule(
        &mut css,
        ".fograin-thin-progress trough",
        "min-height: 4px; border-radius: 99px;",
    );
    rule(
        &mut css,
        ".fograin-thin-progress progress",
        "min-height: 4px; border-radius: 99px;",
    );
    rule(
        &mut css,
        ".fograin-split > button",
        &format!(
            "min-height: 38px; padding: 0 18px; border-radius: {radius}px 0 0 {radius}px;",
            radius = RADIUS_PILL
        ),
    );
    rule(
        &mut css,
        ".fograin-split > menubutton > button",
        &format!(
            "min-height: 38px; padding: 0 10px; border-radius: 0 {radius}px {radius}px 0;",
            radius = RADIUS_PILL
        ),
    );
    rule(
        &mut css,
        ".fograin-split label",
        &format!("font-size: {}px; font-weight: 700;", TYPE_SUBHEAD),
    );
    rule(
        &mut css,
        ".fograin-segments",
        &format!(
            "background-color: alpha(@window_fg_color, 0.08); border-radius: {}px; padding: 3px;",
            RADIUS_PILL
        ),
    );
    rule(
        &mut css,
        ".fograin-segments > togglebutton",
        &format!(
            "border-radius: {}px; background-color: transparent; background-image: none; \
             box-shadow: none; border: none; min-height: 24px; padding: 0 12px;",
            RADIUS_PILL - 3
        ),
    );
    rule(
        &mut css,
        ".fograin-segments > togglebutton:checked",
        "background-color: alpha(@window_fg_color, 0.14); font-weight: 600;",
    );
    rule(
        &mut css,
        ".fograin-hero-button",
        &format!(
            "min-width: 38px; min-height: 38px; border-radius: {}px;",
            RADIUS_PILL
        ),
    );
    rule(
        &mut css,
        ".fograin-detail-hero",
        &format!(
            "padding: 20px; border-radius: {}px; background-color: alpha(@card_bg_color, 0.75);",
            RADIUS_CARD
        ),
    );

    css
}
