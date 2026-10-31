use std::path::PathBuf;

/// Environment variable that overrides the launcher data root.
///
/// Useful for tests, portable installs and development. When it is set to a
/// non-empty value, the whole data layout from §5.1 lives under that directory.
pub const DATA_ROOT_ENV: &str = "FOGRAIN_HOME";

const APP_DIR_MACOS: &str = "Fograin";
const APP_DIR_WINDOWS: &str = "Fograin";
const APP_DIR_LINUX: &str = "fograin";

/// Root of the launcher data directory (§5.1 of `docs/design.md`).
///
/// - macOS: `~/Library/Application Support/Fograin`
/// - Windows: `%APPDATA%\Fograin`
/// - Linux: `$XDG_DATA_HOME/fograin` (usually `~/.local/share/fograin`)
///
/// Android uses the app-private directory as its data root; that lookup is added
/// with the Android milestone.
#[must_use]
pub fn data_root() -> PathBuf {
    if let Some(root) = env_override() {
        return root;
    }

    let base = dirs::data_dir()
        .or_else(dirs::home_dir)
        .unwrap_or_else(|| PathBuf::from("."));

    base.join(app_dir_name())
}

/// Directory for rolling launcher logs and panic crash reports.
#[must_use]
pub fn logs_dir() -> PathBuf {
    data_root().join("logs")
}

fn env_override() -> Option<PathBuf> {
    std::env::var_os(DATA_ROOT_ENV)
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
}

fn app_dir_name() -> &'static str {
    if cfg!(target_os = "linux") {
        APP_DIR_LINUX
    } else if cfg!(target_os = "windows") {
        APP_DIR_WINDOWS
    } else {
        APP_DIR_MACOS
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logs_live_under_the_data_root() {
        assert_eq!(logs_dir(), data_root().join("logs"));
    }

    #[test]
    fn linux_uses_a_lowercase_directory() {
        let expected = if cfg!(target_os = "linux") {
            APP_DIR_LINUX
        } else {
            APP_DIR_MACOS
        };
        assert_eq!(app_dir_name(), expected);
    }
}
