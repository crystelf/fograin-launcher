//! ABI boundary between the Rust core and the four native UI clients.
//!
//! This is intentionally tiny for the workspace reset: it proves the shared
//! library can be linked from Swift, Kotlin, C# and GTK before the real
//! command/event surface is added.
//!
//! The eventual surface is described in `bindings/README.md`. Do not add
//! UI-specific types here; the native clients own their presentation types.

/// Version of the C ABI. Bump for breaking layout/function changes.
pub const ABI_VERSION: u32 = 1;

/// Returns the C ABI version. Used by each native client at startup.
#[unsafe(no_mangle)]
pub extern "C" fn fg_abi_version() -> u32 {
    ABI_VERSION
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(fg_abi_version(), ABI_VERSION);
    }
}
