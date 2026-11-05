//! Java discovery, runtime parsing and version matching (§8).
//!
//! M3 needs enough runtime awareness to *launch*: find installed JDKs, read
//! their version and architecture without starting a JVM, and pick the right
//! one for a version json. Downloading managed runtimes is M6; the
//! `runtimes/<vendor>-<version>-<arch>/` directory from §5.1 is already scanned
//! so M6 can drop files there without touching discovery.

pub mod discover;
pub mod matching;
pub mod probe;
pub mod runtime;

pub use discover::{JavaScanner, discover, normalize_home};
pub use matching::{JavaSelection, JavaWarning, required_major, select};
pub use probe::ProbeInfo;
pub use runtime::{
    JavaArch, JavaRuntime, JavaSource, JavaVersion, Release, detect_arch_bytes, detect_binary_arch,
    executable_in,
};
