//! Android installs the data root through `fg_set_data_root` before the first
//! RPC call. This integration test owns its process so the `OnceLock` override
//! cannot leak into the unit tests.

use std::fs;

use serde_json::Value;

fn temp_root() -> std::path::PathBuf {
    std::env::temp_dir().join(format!(
        "fograin-ffi-root-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock after epoch")
            .as_nanos()
    ))
}

#[test]
fn data_root_override_is_used_and_locked_after_service_start() {
    let root = temp_root();
    fs::create_dir_all(&root).expect("create root");

    let installed = fg_ffi::set_data_root(&root).expect("install data root");
    assert_eq!(installed, root);

    let snapshot: Value =
        serde_json::from_str(&fg_ffi::settings_snapshot_json()).expect("snapshot JSON");
    assert_eq!(
        snapshot["dataRoot"].as_str(),
        Some(root.to_string_lossy().as_ref())
    );
    assert_eq!(snapshot["abiVersion"], fg_ffi::ABI_VERSION);

    // The same root is idempotent; a different one is rejected.
    assert!(fg_ffi::set_data_root(&root).is_ok());
    let other = root.join("other");
    let error = fg_ffi::set_data_root(&other).expect_err("different root");
    assert_eq!(error.message_key, "error.data_root.already_set");

    // Building the service locks the root for the rest of the process.
    let service: Value =
        serde_json::from_str(&fg_ffi::service_current_json()).expect("service JSON");
    assert_eq!(service["ok"], true);
    let error = fg_ffi::set_data_root(&other).expect_err("service started");
    assert_eq!(error.message_key, "error.data_root.service_started");

    let _ = fs::remove_dir_all(root);
}
