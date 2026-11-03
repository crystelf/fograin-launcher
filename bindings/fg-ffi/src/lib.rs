//! ABI boundary between the Rust core and the four native UI clients.
//!
//! The surface stays deliberately small while the native shells are built out:
//!
//! - [`fg_abi_version`] identifies the C ABI.
//! - [`fg_settings_snapshot`] hands the UI the settings it needs before the
//!   first frame (theme preference, data root, config path).
//! - [`fg_settings_set_theme`] is the first write path, so the shells can prove
//!   that Rust owns `launcher.toml` instead of duplicating config I/O.
//! - [`fg_string_free`] releases every string returned by this library.
//!
//! Everything crosses the boundary as JSON. The contract is documented in
//! `bindings/README.md`; UI clients own their presentation types, so no Swift,
//! Kotlin or C# types belong here.

use std::{
    ffi::{CStr, CString, c_char},
    panic::{AssertUnwindSafe, catch_unwind},
    path::Path,
    ptr,
};

use fg_core::{Error, ErrorKind, LauncherConfig, Platform, Result, ThemePreference, config, paths};
use serde::Serialize;

/// Version of the C ABI. Bump for breaking layout/function changes.
pub const ABI_VERSION: u32 = 1;

/// Settings the native shells read before creating their first window (§4.1.1).
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct SettingsSnapshot {
    abi_version: u32,
    app_version: &'static str,
    platform: &'static str,
    theme: ThemePreference,
    data_root: String,
    config_path: String,
    /// Present when `launcher.toml` exists but could not be parsed; the
    /// snapshot then carries defaults so the shell can still start.
    #[serde(skip_serializing_if = "Option::is_none")]
    config_error: Option<Error>,
}

impl SettingsSnapshot {
    fn at(root: &Path) -> Self {
        let mut config_error = None;
        let config = match config::read_config(root) {
            Ok(config) => config,
            Err(error) => {
                config_error = Some(error);
                LauncherConfig::new()
            }
        };

        Self {
            abi_version: ABI_VERSION,
            app_version: env!("CARGO_PKG_VERSION"),
            platform: Platform::detect().id(),
            theme: config.appearance.theme,
            data_root: root.display().to_string(),
            config_path: config::config_path(root).display().to_string(),
            config_error,
        }
    }
}

/// Result of a settings write, used by [`fg_settings_set_theme`].
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct WriteResult {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    theme: Option<ThemePreference>,
    #[serde(skip_serializing_if = "Option::is_none")]
    snapshot: Option<SettingsSnapshot>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<Error>,
}

impl WriteResult {
    fn applied(theme: ThemePreference, snapshot: SettingsSnapshot) -> Self {
        Self {
            ok: true,
            theme: Some(theme),
            snapshot: Some(snapshot),
            error: None,
        }
    }

    fn failed(error: Error) -> Self {
        Self {
            ok: false,
            theme: None,
            snapshot: None,
            error: Some(error),
        }
    }
}

fn parse_theme(value: &str) -> Result<ThemePreference> {
    match value {
        "system" => Ok(ThemePreference::System),
        "light" => Ok(ThemePreference::Light),
        "dark" => Ok(ThemePreference::Dark),
        other => Err(Error::with_detail(
            ErrorKind::InvalidInput,
            "error.theme.invalid",
            format!("expected system, light or dark, got {other:?}"),
        )),
    }
}

/// Reads the current config, applies only `appearance.theme`, and writes it back
/// atomically. Unparsable config files are left untouched.
fn write_theme_at(root: &Path, theme: ThemePreference) -> Result<SettingsSnapshot> {
    let mut config = config::read_config(root)?;
    if config.appearance.theme != theme {
        config.appearance.theme = theme;
        config::write_config(root, &config)?;
    }
    Ok(SettingsSnapshot::at(root))
}

fn to_c_string<T: Serialize>(value: &T) -> *mut c_char {
    serde_json::to_vec(value)
        .ok()
        .and_then(|bytes| CString::new(bytes).ok())
        .map_or(ptr::null_mut(), CString::into_raw)
}

/// Returns the C ABI version. Used by each native client at startup.
#[unsafe(no_mangle)]
pub extern "C" fn fg_abi_version() -> u32 {
    ABI_VERSION
}

/// Returns a JSON [`SettingsSnapshot`] for the current launcher data root.
///
/// The caller owns the returned string and must release it with
/// [`fg_string_free`]. Returns `NULL` only if serialization or allocation fails.
#[unsafe(no_mangle)]
pub extern "C" fn fg_settings_snapshot() -> *mut c_char {
    catch_unwind(|| to_c_string(&SettingsSnapshot::at(&paths::data_root())))
        .unwrap_or(ptr::null_mut())
}

/// Writes `appearance.theme` to `launcher.toml` and returns a JSON
/// [`WriteResult`].
///
/// `theme` must point to a NUL-terminated UTF-8 string containing `system`,
/// `light` or `dark`. A `NULL` pointer is reported as invalid input. The caller
/// owns the returned string and must release it with [`fg_string_free`].
///
/// # Safety
///
/// `theme` must be null or a valid, NUL-terminated C string that stays valid for
/// the duration of the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_settings_set_theme(theme: *const c_char) -> *mut c_char {
    // Copy before crossing `catch_unwind`: borrowed C memory must not outlive a
    // panic boundary and must not be captured by the closure.
    let requested: Option<String> = if theme.is_null() {
        None
    } else {
        // SAFETY: documented contract; the pointer, when non-null, is a valid
        // NUL-terminated C string owned by the caller.
        unsafe { CStr::from_ptr(theme) }
            .to_str()
            .ok()
            .map(str::to_owned)
    };

    let result = catch_unwind(AssertUnwindSafe(|| {
        let Some(requested) = requested.as_deref() else {
            return to_c_string(&WriteResult::failed(Error::new(
                ErrorKind::InvalidInput,
                "error.theme.missing",
            )));
        };

        let outcome = parse_theme(requested).and_then(|theme| {
            let root = paths::data_root();
            write_theme_at(&root, theme).map(|snapshot| (theme, snapshot))
        });

        match outcome {
            Ok((theme, snapshot)) => to_c_string(&WriteResult::applied(theme, snapshot)),
            Err(error) => to_c_string(&WriteResult::failed(error)),
        }
    }));

    result.unwrap_or(ptr::null_mut())
}

/// Releases a string returned by another `fg_*` function.
///
/// Passing `NULL` is a no-op.
///
/// # Safety
///
/// `value` must be null or a pointer returned by this library that has not been
/// freed yet. Any other pointer is undefined behaviour.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn fg_string_free(value: *mut c_char) {
    if value.is_null() {
        return;
    }

    // SAFETY: the pointer came from `CString::into_raw` in this library.
    drop(unsafe { CString::from_raw(value) });
}

#[cfg(test)]
mod tests {
    use std::{fs, path::PathBuf};

    use serde_json::{Value, json};

    use super::*;

    fn temp_root() -> PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};

        static NEXT: AtomicU64 = AtomicU64::new(0);
        let unique = NEXT.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir().join(format!(
            "fograin-ffi-test-{}-{}-{unique}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("clock after epoch")
                .as_nanos()
        ))
    }

    fn read_json(pointer: *mut c_char) -> Value {
        assert!(!pointer.is_null(), "FFI returned a null string");
        // SAFETY: the pointer is a NUL-terminated string from this library.
        let text = unsafe { CStr::from_ptr(pointer) }
            .to_str()
            .expect("UTF-8 JSON");
        let value = serde_json::from_str(text).expect("valid JSON");
        // SAFETY: the pointer is a NUL-terminated string from this library.
        unsafe { fg_string_free(pointer) };
        value
    }

    #[test]
    fn abi_version_is_stable() {
        assert_eq!(fg_abi_version(), ABI_VERSION);
        assert_eq!(ABI_VERSION, 1);
    }

    #[test]
    fn snapshot_defaults_to_system_theme() {
        let root = temp_root();
        let snapshot = SettingsSnapshot::at(&root);
        assert_eq!(snapshot.theme, ThemePreference::System);
        assert_eq!(snapshot.abi_version, ABI_VERSION);
        assert_eq!(snapshot.platform, Platform::detect().id());
        assert_eq!(snapshot.data_root, root.display().to_string());
        assert!(snapshot.config_error.is_none());

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn snapshot_serializes_the_camel_case_contract() {
        let root = temp_root();
        let json = serde_json::to_value(SettingsSnapshot::at(&root)).expect("serialize");
        assert_eq!(json["abiVersion"], json!(ABI_VERSION));
        assert_eq!(json["theme"], json!("system"));
        assert_eq!(json["platform"], json!(Platform::detect().id()));
        assert!(json["appVersion"].is_string());
        assert!(json["dataRoot"].is_string());
        assert!(json["configPath"].is_string());
        assert!(json.get("configError").is_none());

        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn write_theme_round_trips_through_launcher_toml() {
        let root = temp_root();
        let snapshot = write_theme_at(&root, ThemePreference::Dark).expect("write theme");
        assert_eq!(snapshot.theme, ThemePreference::Dark);

        let text = fs::read_to_string(config::config_path(&root)).expect("read config");
        assert!(text.contains("theme = \"dark\""), "{text}");

        let snapshot = SettingsSnapshot::at(&root);
        assert_eq!(snapshot.theme, ThemePreference::Dark);

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn write_theme_rejects_a_broken_config_instead_of_overwriting_it() {
        let root = temp_root();
        fs::create_dir_all(&root).expect("create root");
        fs::write(config::config_path(&root), "this is not toml").expect("write config");

        let error = write_theme_at(&root, ThemePreference::Light).expect_err("must not overwrite");
        assert_eq!(error.kind, ErrorKind::Parse);
        assert_eq!(
            fs::read_to_string(config::config_path(&root)).expect("read config"),
            "this is not toml"
        );

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn snapshot_reports_a_broken_config_and_falls_back() {
        let root = temp_root();
        fs::create_dir_all(&root).expect("create root");
        fs::write(config::config_path(&root), "[[[broken").expect("write config");

        let snapshot = SettingsSnapshot::at(&root);
        assert_eq!(snapshot.theme, ThemePreference::System);
        assert_eq!(
            snapshot.config_error.as_ref().map(|error| error.kind),
            Some(ErrorKind::Parse)
        );

        fs::remove_dir_all(root).expect("cleanup");
    }

    #[test]
    fn parses_theme_wire_values() {
        assert_eq!(
            parse_theme("system").expect("system"),
            ThemePreference::System
        );
        assert_eq!(parse_theme("light").expect("light"), ThemePreference::Light);
        assert_eq!(parse_theme("dark").expect("dark"), ThemePreference::Dark);

        let error = parse_theme("sepia").expect_err("unknown theme");
        assert_eq!(error.kind, ErrorKind::InvalidInput);
        assert_eq!(error.message_key, "error.theme.invalid");
    }

    #[test]
    fn set_theme_reports_missing_and_invalid_pointers_as_json() {
        // SAFETY: a null pointer is explicitly reported as invalid input.
        let missing = read_json(unsafe { fg_settings_set_theme(ptr::null()) });
        assert_eq!(missing["ok"], json!(false));
        assert_eq!(missing["error"]["kind"], json!("invalidInput"));
        assert_eq!(missing["error"]["messageKey"], json!("error.theme.missing"));

        let invalid = CString::new("sepia").expect("CString");
        // SAFETY: `invalid` is a valid NUL-terminated C string.
        let rejected = read_json(unsafe { fg_settings_set_theme(invalid.as_ptr()) });
        assert_eq!(rejected["ok"], json!(false));
        assert_eq!(rejected["error"]["kind"], json!("invalidInput"));
    }

    #[test]
    fn string_free_tolerates_null_and_releases_exported_strings() {
        // SAFETY: releasing a null pointer is a documented no-op.
        unsafe { fg_string_free(ptr::null_mut()) };

        let snapshot = fg_settings_snapshot();
        assert!(!snapshot.is_null());
        // SAFETY: the pointer is a NUL-terminated string from this library.
        unsafe { fg_string_free(snapshot) };
    }
}
