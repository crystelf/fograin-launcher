//! Content and world RPC smoke tests through the real dispatch.

use std::{fs, path::PathBuf};

use serde_json::json;

fn temp_root() -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    std::env::temp_dir().join(format!(
        "fograin-ffi-content-{}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock after epoch")
            .as_nanos(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ))
}

fn create_instance() -> (PathBuf, std::sync::Arc<fg_ffi::Service>, PathBuf) {
    let root = temp_root();
    fs::create_dir_all(&root).expect("create root");
    let service = fg_ffi::Service::new(root.clone()).expect("service");
    service
        .block_on(service.rpc(
            "instances.create",
            json!({
                "id": "fog",
                "name": "雾林生存",
                "minecraft": "1.21.4",
                "loaderKind": "fabric",
                "loaderVersion": "0.16.9"
            }),
        ))
        .expect("create instance");
    let game_dir = root.join("instances").join("fog").join(".minecraft");
    (root, service, game_dir)
}

#[test]
fn content_local_toggle_and_delete_round_trip() {
    let (root, service, game_dir) = create_instance();

    let pack = game_dir.join("resourcepacks").join("Fograin UI");
    fs::create_dir_all(&pack).expect("pack dir");
    fs::write(
        pack.join("pack.mcmeta"),
        r#"{"pack":{"pack_format":34,"description":"Calm forest UI"}}"#,
    )
    .expect("pack meta");

    let listed = service
        .block_on(service.rpc("content.local", json!({ "instanceId": "fog" })))
        .expect("content.local");
    assert_eq!(listed["counts"]["resourcepack"], 1, "{listed}");
    assert_eq!(listed["counts"]["enabled"], 1, "{listed}");
    let item = &listed["items"][0];
    assert_eq!(item["title"], "Fograin UI");
    assert_eq!(item["packFormat"], 34);
    assert_eq!(item["enabled"], true);

    let toggled = service
        .block_on(service.rpc(
            "content.toggle",
            json!({
                "instanceId": "fog",
                "kind": "resourcepack",
                "fileName": "Fograin UI",
                "enabled": false
            }),
        ))
        .expect("toggle");
    assert_eq!(toggled["fileName"], "Fograin UI.disabled");
    assert!(
        game_dir
            .join("resourcepacks")
            .join("Fograin UI.disabled")
            .is_dir()
    );

    let listed = service
        .block_on(service.rpc("content.local", json!({ "instanceId": "fog" })))
        .expect("content.local disabled");
    assert_eq!(listed["items"][0]["enabled"], false);
    assert_eq!(listed["counts"]["enabled"], 0);

    service
        .block_on(service.rpc(
            "content.delete",
            json!({
                "instanceId": "fog",
                "kind": "resourcepack",
                "fileName": "Fograin UI.disabled"
            }),
        ))
        .expect("delete");
    assert!(
        !game_dir
            .join("resourcepacks")
            .join("Fograin UI.disabled")
            .exists()
    );

    let _ = fs::remove_dir_all(root);
}

#[test]
fn worlds_are_listed_backed_up_restored_and_deleted() {
    let (root, service, game_dir) = create_instance();
    let world = game_dir.join("saves").join("New World");
    fs::create_dir_all(&world).expect("world");
    fs::write(world.join("level.dat"), b"level").expect("level");
    fs::write(world.join("session.lock"), b"lock").expect("lock");

    let listed = service
        .block_on(service.rpc("worlds.list", json!({ "instanceId": "fog" })))
        .expect("worlds.list");
    assert_eq!(listed["worlds"][0]["name"], "New World");
    assert!(listed["backups"].as_array().expect("backups").is_empty());

    let backed_up = service
        .block_on(service.rpc(
            "worlds.backup",
            json!({ "instanceId": "fog", "name": "New World" }),
        ))
        .expect("worlds.backup");
    assert_eq!(backed_up["ok"], true);
    let backup_name = backed_up["backup"]["name"]
        .as_str()
        .expect("backup name")
        .to_string();
    assert!(backed_up["backups"].as_array().expect("backups").len() == 1);
    assert!(
        root.join("backups")
            .join("fog")
            .join(&backup_name)
            .is_file()
    );

    let restored = service
        .block_on(service.rpc(
            "worlds.restore",
            json!({
                "instanceId": "fog",
                "backup": backup_name,
                "name": "New World Restored"
            }),
        ))
        .expect("worlds.restore");
    assert_eq!(restored["ok"], true);
    assert_eq!(
        fs::read(
            game_dir
                .join("saves")
                .join("New World Restored")
                .join("level.dat")
        )
        .expect("restored level"),
        b"level"
    );

    service
        .block_on(service.rpc(
            "worlds.delete",
            json!({ "instanceId": "fog", "name": "New World Restored" }),
        ))
        .expect("delete world");
    assert!(!game_dir.join("saves").join("New World Restored").exists());

    service
        .block_on(service.rpc(
            "worlds.backup.delete",
            json!({ "instanceId": "fog", "backup": backup_name }),
        ))
        .expect("delete backup");

    let _ = fs::remove_dir_all(root);
}
