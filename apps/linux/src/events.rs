//! App-level UI events raised by pages.
//!
//! Pages never reach into the window: they hand an event to the sink, and the
//! shell decides whether that means navigating, selecting an instance or
//! writing `launcher.toml`.

use std::{cell::RefCell, rc::Rc};

use fg_core::ThemePreference;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum UiEvent {
    /// Open the library and select the instance with this id.
    OpenInstance(&'static str),
    /// The settings page changed `appearance.theme`.
    ThemeChanged(ThemePreference),
}

type Handler = Rc<dyn Fn(UiEvent)>;

/// Single-slot event sink shared by all pages.
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
