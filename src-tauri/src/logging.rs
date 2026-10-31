//! File logging and panic crash reports (§5.1 and M0 of `docs/design.md`).
//!
//! Logs are written to `<data-root>/logs/launcher-YYYY-MM-DD.log` and roll over
//! at local midnight. Panics write a separate `crash-YYYYMMDD-HHMMSS.log` with a
//! forced backtrace before the default hook prints to stderr.

use std::{
    fs::{self, File, OpenOptions},
    io::{self, IsTerminal, Write},
    panic,
    path::{Path, PathBuf},
};

use fg_core::PlatformSkin;
use time::OffsetDateTime;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::{EnvFilter, fmt, layer::SubscriberExt, util::SubscriberInitExt};

/// Keeps the non-blocking log writer alive for the lifetime of the process.
pub struct LogGuard {
    _guard: WorkerGuard,
}

/// Initializes tracing, the rolling log file and the panic hook.
///
/// Returns `None` when the log file cannot be opened or a subscriber is already
/// installed; the launcher still starts and logs to stderr in that case.
#[must_use]
pub fn init(log_dir: PathBuf) -> Option<LogGuard> {
    install_panic_hook(log_dir.clone());

    if let Err(error) = fs::create_dir_all(&log_dir) {
        eprintln!(
            "warning: cannot create log directory {}: {error}",
            log_dir.display()
        );
        return None;
    }

    let writer = match DailyFile::new(&log_dir) {
        Ok(writer) => writer,
        Err(error) => {
            eprintln!(
                "warning: cannot open log file in {}: {error}",
                log_dir.display()
            );
            return None;
        }
    };

    let (non_blocking, guard) = tracing_appender::non_blocking(writer);
    let default_filter = if cfg!(debug_assertions) {
        "info,fograin_lib=debug"
    } else {
        "info"
    };
    let filter =
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default_filter));

    let file_layer = fmt::layer()
        .with_writer(non_blocking)
        .with_ansi(false)
        .with_target(true)
        .with_thread_ids(true);

    let stderr_layer = fmt::layer()
        .with_writer(io::stderr)
        .with_ansi(io::stderr().is_terminal());

    if let Err(error) = tracing_subscriber::registry()
        .with(filter)
        .with(file_layer)
        .with(stderr_layer)
        .try_init()
    {
        eprintln!("warning: failed to initialize tracing: {error}");
        return None;
    }

    Some(LogGuard { _guard: guard })
}

/// A `Write` sink that appends to the daily launcher log and rotates when the
/// local date changes. This keeps the `<prefix>-YYYY-MM-DD.log` naming from
/// §5.1 while letting the process run across midnight.
struct DailyFile {
    dir: PathBuf,
    current: Option<(String, File)>,
}

impl DailyFile {
    fn new(dir: &Path) -> io::Result<Self> {
        fs::create_dir_all(dir)?;
        let mut file = Self {
            dir: dir.to_owned(),
            current: None,
        };
        file.rotate()?;
        Ok(file)
    }

    fn rotate(&mut self) -> io::Result<()> {
        let date = date_stamp(local_now());
        let path = self.dir.join(format!("launcher-{date}.log"));
        let file = OpenOptions::new().create(true).append(true).open(path)?;
        self.current = Some((date, file));
        Ok(())
    }

    fn writable(&mut self) -> io::Result<&mut File> {
        let today = date_stamp(local_now());
        let needs_rotation = self.current.as_ref().is_none_or(|(date, _)| date != &today);

        if needs_rotation {
            self.rotate()?;
        }

        self.current
            .as_mut()
            .map(|(_, file)| file)
            .ok_or_else(|| io::Error::other("daily log file was not initialized"))
    }
}

impl Write for DailyFile {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.writable()?.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        match &mut self.current {
            Some((_, file)) => file.flush(),
            None => Ok(()),
        }
    }
}

fn install_panic_hook(log_dir: PathBuf) {
    let default_hook = panic::take_hook();

    panic::set_hook(Box::new(move |info| {
        let now = local_now();
        let location = info
            .location()
            .map(|location| {
                format!(
                    "{}:{}:{}",
                    location.file(),
                    location.line(),
                    location.column()
                )
            })
            .unwrap_or_else(|| "<unknown>".to_owned());
        let thread = std::thread::current();
        let thread_name = thread.name().unwrap_or("<unnamed>");
        let backtrace = std::backtrace::Backtrace::force_capture();
        let report = format!(
            "Fograin Launcher crash report\n\
             version: {}\n\
             platform: {}\n\
             time: {}\n\
             thread: {thread_name}\n\
             location: {location}\n\
             panic: {}\n\n\
             {backtrace}\n",
            env!("CARGO_PKG_VERSION"),
            PlatformSkin::detect().id(),
            now,
            panic_message(info),
        );

        let path = log_dir.join(format!("crash-{}.log", crash_stamp(now)));
        let result = fs::create_dir_all(&log_dir).and_then(|()| fs::write(&path, report));

        match result {
            Ok(()) => eprintln!("crash report written to {}", path.display()),
            Err(error) => eprintln!(
                "warning: failed to write crash report {}: {error}",
                path.display()
            ),
        }

        default_hook(info);
    }));
}

fn panic_message(info: &panic::PanicHookInfo<'_>) -> String {
    if let Some(message) = info.payload().downcast_ref::<&str>() {
        (*message).to_owned()
    } else if let Some(message) = info.payload().downcast_ref::<String>() {
        message.clone()
    } else {
        "<non-string panic payload>".to_owned()
    }
}

fn local_now() -> OffsetDateTime {
    OffsetDateTime::now_local().unwrap_or_else(|_| OffsetDateTime::now_utc())
}

fn date_stamp(now: OffsetDateTime) -> String {
    format!(
        "{:04}-{:02}-{:02}",
        now.year(),
        u8::from(now.month()),
        now.day()
    )
}

fn crash_stamp(now: OffsetDateTime) -> String {
    format!(
        "{:04}{:02}{:02}-{:02}{:02}{:02}",
        now.year(),
        u8::from(now.month()),
        now.day(),
        now.hour(),
        now.minute(),
        now.second()
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed_offset() -> OffsetDateTime {
        let date =
            time::Date::from_calendar_date(2026, time::Month::October, 31).expect("valid date");
        let time = time::Time::from_hms(18, 5, 7).expect("valid time");
        OffsetDateTime::new_utc(date, time)
    }

    #[test]
    fn formats_the_daily_log_date() {
        assert_eq!(date_stamp(fixed_offset()), "2026-10-31");
    }

    #[test]
    fn formats_the_crash_timestamp() {
        assert_eq!(crash_stamp(fixed_offset()), "20261031-180507");
    }

    #[test]
    fn daily_file_rotates_and_appends() {
        let dir = std::env::temp_dir().join(format!(
            "fograin-log-test-{}-{}",
            std::process::id(),
            time::OffsetDateTime::now_utc().unix_timestamp_nanos()
        ));
        let mut file = DailyFile::new(&dir).expect("open daily file");
        file.write_all(b"first\n").expect("write");
        file.write_all(b"second\n").expect("write");
        file.flush().expect("flush");

        let today = date_stamp(local_now());
        let path = dir.join(format!("launcher-{today}.log"));
        let contents = fs::read_to_string(&path).expect("read log");
        assert_eq!(contents, "first\nsecond\n");

        fs::remove_dir_all(dir).expect("cleanup");
    }

    #[test]
    fn panic_hook_writes_a_crash_report() {
        let dir = std::env::temp_dir().join(format!(
            "fograin-crash-test-{}-{}",
            std::process::id(),
            time::OffsetDateTime::now_utc().unix_timestamp_nanos()
        ));

        // The panic hook is process-global, so temporarily save the harness hook
        // and restore it before the test returns.
        let harness_hook = panic::take_hook();
        install_panic_hook(dir.clone());

        let result = panic::catch_unwind(|| panic!("m0 panic hook test"));
        assert!(result.is_err());

        let installed_hook = panic::take_hook();
        panic::set_hook(harness_hook);

        let crash_file = fs::read_dir(&dir)
            .expect("read crash directory")
            .filter_map(Result::ok)
            .map(|entry| entry.path())
            .find(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .is_some_and(|name| name.starts_with("crash-") && name.ends_with(".log"))
            })
            .expect("crash report should exist");
        let report = fs::read_to_string(crash_file).expect("read crash report");
        assert!(report.contains("Fograin Launcher crash report"));
        assert!(report.contains("m0 panic hook test"));

        fs::remove_dir_all(dir).expect("cleanup");
        drop(installed_hook);
    }
}
