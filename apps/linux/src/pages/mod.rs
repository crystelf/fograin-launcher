//! Native pages of the GNOME client.

mod detail;
mod home;
mod library;
mod placeholder;
mod settings;

pub use home::HomePage;
pub use library::LibraryPage;
pub use placeholder::placeholder_page;
pub use settings::SettingsPage;
