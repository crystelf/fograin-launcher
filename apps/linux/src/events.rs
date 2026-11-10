//! App-level UI events raised by pages and the state layer.
//!
//! Pages never reach into the window: they hand an event to the sink, and the
//! shell decides whether that means navigating, selecting an instance, opening
//! a dialog, writing `launcher.toml` or showing a transient notice.

use std::{cell::RefCell, rc::Rc};

use fg_core::ThemePreference;

use crate::navigation::Section;

/// Visual tone of a transient notice, matching the macOS `Notice.Tone`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ToastLevel {
    Info,
    Success,
    Warning,
}

#[derive(Clone, Debug, PartialEq)]
pub enum UiEvent {
    /// Switch the content pane to a top-level section.
    Navigate(Section),
    /// Open the library and select the instance with this id.
    OpenInstance(String),
    /// Present the game-settings dialog for this instance.
    ShowInstanceSettings(String),
    /// The library search field should take focus.
    FocusLibrarySearch,
    /// The settings page changed `appearance.theme`.
    ThemeChanged(ThemePreference),
    /// Transient capsule above the window bottom edge.
    Toast { message: String, level: ToastLevel },
    /// Blocking error dialog; failures must never be a 2.8 s capsule.
    Alert {
        title: String,
        message: String,
        detail: Option<String>,
    },
}

type Handler = Rc<dyn Fn(UiEvent)>;

/// Single-slot event sink shared by all pages and the state layer.
#[derive(Default)]
pub struct EventSink {
    handler: RefCell<Option<Handler>>,
}

impl EventSink {
    pub fn new() -> Rc<Self> {
        Rc::new(Self::default())
    }

    /// Installs the shell's handler. Only the shell calls this.
    pub fn connect(&self, handler: impl Fn(UiEvent) + 'static) {
        *self.handler.borrow_mut() = Some(Rc::new(handler));
    }

    pub fn emit(&self, event: UiEvent) {
        // Clone the handler out before calling so a handler that emits another
        // event cannot trip the RefCell borrow.
        let handler = self.handler.borrow().clone();
        if let Some(handler) = handler {
            handler(event);
        }
    }
}
