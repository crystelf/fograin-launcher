//! Process-creation details for launching console programs from the GUI.
//!
//! Windows copies the parent's console state to a child unless
//! `CREATE_NO_WINDOW` is set. A `javaw`/GUI launcher has no console, so every
//! `java.exe` or `cmd.exe` probe would otherwise flash a black console window
//! for a fraction of a second. The constant lives here so all backends use the
//! same flag and the reason is documented in one place.

/// `CreateProcess` flag that gives the child a hidden console.
///
/// Rust exposes it as [`std::os::windows::process::CommandExt::creation_flags`]
/// and tokio's `Command` has the matching inherent method. The value is
/// meaningless on non-Windows targets; call sites keep it inside `cfg` blocks.
pub const CREATE_NO_WINDOW: u32 = 0x0800_0000;
