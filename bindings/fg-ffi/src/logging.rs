//! Rolling launcher log.
//!
//! The UI shells own stdout, so the core writes its own `tracing` events to
//! `<data root>/logs/launcher.log`. The file is truncated once it passes
//! [`MAX_LOG_BYTES`] so a long-lived install cannot fill the user's disk.
//!
//! Initialization is best-effort: a read-only data directory must never stop
//! the launcher, and tests keep running with the default no-op subscriber.

use std::{
    path::Path,
    sync::{Arc, Mutex, Once},
};

use tracing_subscriber::fmt::MakeWriter;

/// A launcher log bigger than this is rotated to `launcher.log.1` at startup.
const MAX_LOG_BYTES: u64 = 5 * 1024 * 1024;

const LOG_FILE: &str = "launcher.log";

/// File handle shared by every `tracing` writer.
#[derive(Clone)]
struct LogFile(Arc<Mutex<std::fs::File>>);

impl std::io::Write for LogFile {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        let mut file = self
            .0
            .lock()
            .map_err(|_| std::io::Error::other("log file lock poisoned"))?;
        file.write(buffer)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        let mut file = self
            .0
            .lock()
            .map_err(|_| std::io::Error::other("log file lock poisoned"))?;
        file.flush()
    }
}

impl<'a> MakeWriter<'a> for LogFile {
    type Writer = Self;

    fn make_writer(&'a self) -> Self::Writer {
        self.clone()
    }
}

/// Installs the process log sink once, under `<root>/logs/launcher.log`.
///
/// `FOGRAIN_LOG` overrides the filter (default `info`), mirroring `RUST_LOG`
/// in `fg-cli`.
pub fn init(root: &Path) {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let Ok(file) = open_log(root) else { return };
        let filter = std::env::var("FOGRAIN_LOG").unwrap_or_else(|_| "info".to_string());
        let Ok(filter) = tracing_subscriber::EnvFilter::try_new(filter) else {
            return;
        };
        let _ = tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_ansi(false)
            .with_target(false)
            .with_writer(LogFile(Arc::new(Mutex::new(file))))
            .try_init();
    });
}

fn open_log(root: &Path) -> std::io::Result<std::fs::File> {
    let directory = root.join("logs");
    std::fs::create_dir_all(&directory)?;
    let path = directory.join(LOG_FILE);
    if std::fs::metadata(&path).is_ok_and(|meta| meta.len() > MAX_LOG_BYTES) {
        let _ = std::fs::rename(&path, directory.join(format!("{LOG_FILE}.1")));
    }
    std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
}
