//! Host information the home runtime card shows before `fg-sys` lands.
//!
//! Only facts the process can read without privileges: physical memory, the
//! session type and the target architecture. Java discovery comes from the
//! launcher core (`app.snapshot`), not from this module.

use std::sync::OnceLock;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SystemInfo {
    pub memory_bytes: u64,
    pub session: String,
    pub architecture: String,
}

impl SystemInfo {
    #[must_use]
    pub fn current() -> Self {
        static INFO: OnceLock<SystemInfo> = OnceLock::new();
        INFO.get_or_init(read_host).clone()
    }

    #[must_use]
    pub fn memory_text(&self) -> String {
        if self.memory_bytes == 0 {
            return "未知".to_owned();
        }
        let gigabytes = self.memory_bytes as f64 / 1_073_741_824.0;
        format!("{} GB", (gigabytes.round() as u64).max(1))
    }

    /// The renderer is chosen by the compositor; the client only reports the
    /// session so the card never invents a GPU name.
    #[must_use]
    pub fn gpu_text(&self) -> String {
        match self.session.as_str() {
            "wayland" => "Wayland · 系统默认渲染".to_owned(),
            "x11" => "X11 · 系统默认渲染".to_owned(),
            _ => "系统默认渲染".to_owned(),
        }
    }
}

fn read_host() -> SystemInfo {
    SystemInfo {
        memory_bytes: physical_memory(),
        session: std::env::var("XDG_SESSION_TYPE")
            .map(|value| value.to_ascii_lowercase())
            .unwrap_or_default(),
        architecture: std::env::consts::ARCH.to_owned(),
    }
}

#[cfg(unix)]
fn physical_memory() -> u64 {
    let pages = unsafe { libc::sysconf(libc::_SC_PHYS_PAGES) };
    let page_size = unsafe { libc::sysconf(libc::_SC_PAGESIZE) };
    if pages <= 0 || page_size <= 0 {
        return 0;
    }
    (pages as u64).saturating_mul(page_size as u64)
}

#[cfg(not(unix))]
fn physical_memory() -> u64 {
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memory_text_is_never_blank() {
        let info = SystemInfo {
            memory_bytes: 16 * 1_073_741_824,
            session: "wayland".to_owned(),
            architecture: "x86_64".to_owned(),
        };
        assert_eq!(info.memory_text(), "16 GB");
        assert_eq!(info.gpu_text(), "Wayland · 系统默认渲染");

        let unknown = SystemInfo {
            memory_bytes: 0,
            session: String::new(),
            architecture: String::new(),
        };
        assert_eq!(unknown.memory_text(), "未知");
        assert_eq!(unknown.gpu_text(), "系统默认渲染");
    }
}
