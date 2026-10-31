//! Debug CLI for Fograin Launcher.
//!
//! Download, install and launch subcommands arrive with their crates (M2+); for
//! now it proves the workspace builds and links the core crate.

fn main() {
    println!(
        "Fograin CLI {} - {} (workspace scaffold)",
        env!("CARGO_PKG_VERSION"),
        fg_core::Platform::detect().label(),
    );
}
