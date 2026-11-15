//! Account repository smoke tests through the real RPC dispatch.
//!
//! These tests own their data root and never touch the network: they verify the
//! default account migration, selection, removal fallback and that the wire
//! rows never contain token fields.

use std::fs;

use serde_json::{Value, json};
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{method, path},
};

fn temp_root() -> std::path::PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    std::env::temp_dir().join(format!(
        "fograin-ffi-accounts-{}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock after epoch")
            .as_nanos(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ))
}

#[test]
fn account_crud_uses_the_default_offline_account() {
    let root = temp_root();
    fs::create_dir_all(&root).expect("create root");
    let service = fg_ffi::Service::new(root.clone()).expect("service");

    let listed = service
        .block_on(service.rpc("accounts.list", json!({})))
        .expect("accounts.list");
    let accounts = listed["accounts"].as_array().expect("accounts array");
    assert_eq!(accounts.len(), 1, "{listed}");
    let first_id = accounts[0]["id"].as_str().expect("id").to_string();
    assert_eq!(accounts[0]["name"], "Steve");
    assert_eq!(accounts[0]["kind"], "offline");
    assert_eq!(accounts[0]["selected"], true);
    assert_eq!(listed["selectedId"], first_id);

    let added = service
        .block_on(service.rpc("accounts.addOffline", json!({ "name": "Alex" })))
        .expect("addOffline");
    assert_eq!(added["account"]["name"], "Alex");
    let alex_id = added["account"]["id"]
        .as_str()
        .expect("alex id")
        .to_string();
    assert_eq!(added["selectedId"], alex_id);

    let selected = service
        .block_on(service.rpc("accounts.select", json!({ "id": first_id })))
        .expect("select");
    assert_eq!(selected["selectedId"], first_id);

    let removed = service
        .block_on(service.rpc("accounts.remove", json!({ "id": alex_id })))
        .expect("remove");
    assert_eq!(removed["ok"], true);
    let remaining = service
        .block_on(service.rpc("accounts.list", json!({})))
        .expect("list after remove");
    assert_eq!(remaining["accounts"].as_array().expect("rows").len(), 1);

    let accounts_json = fs::read_to_string(root.join("accounts.json")).expect("accounts file");
    let parsed: Value = serde_json::from_str(&accounts_json).expect("json");
    assert_eq!(parsed["schemaVersion"], 1);
    assert_eq!(parsed["accounts"][0]["kind"], "offline");
    assert!(!accounts_json.contains("accessToken"));
    assert!(root.join("credentials.json").is_file());

    let _ = fs::remove_dir_all(root);
}

#[test]
fn removing_the_last_account_recreates_a_usable_default() {
    let root = temp_root();
    fs::create_dir_all(&root).expect("create root");
    let service = fg_ffi::Service::new(root.clone()).expect("service");
    let listed = service
        .block_on(service.rpc("accounts.list", json!({})))
        .expect("accounts.list");
    let id = listed["accounts"][0]["id"]
        .as_str()
        .expect("id")
        .to_string();

    let removed = service
        .block_on(service.rpc("accounts.remove", json!({ "id": id })))
        .expect("remove");
    assert_eq!(removed["ok"], true);
    assert!(
        removed["selectedId"]
            .as_str()
            .is_some_and(|id| !id.is_empty())
    );

    let listed = service
        .block_on(service.rpc("accounts.list", json!({})))
        .expect("list after remove");
    assert_eq!(listed["accounts"].as_array().expect("rows").len(), 1);

    let _ = fs::remove_dir_all(root);
}

#[test]
fn yggdrasil_login_persists_metadata_without_tokens_in_accounts_json() {
    use std::io::Write as _;

    let runtime = tokio::runtime::Runtime::new().expect("mock runtime");
    let server = runtime.block_on(MockServer::start());
    runtime.block_on(async {
        Mock::given(method("GET"))
            .and(path("/api/yggdrasil"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "meta": {
                    "serverName": "LittleSkin",
                    "implementationName": "authlib-injector",
                    "implementationVersion": "1.2.8"
                }
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/yggdrasil/authserver/authenticate"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "accessToken": "access-secret",
                "clientToken": "client-secret",
                "selectedProfile": {
                    "id": "8f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5",
                    "name": "Fograin"
                },
                "user": { "id": "user-1", "properties": {} }
            })))
            .mount(&server)
            .await;
    });

    let root = temp_root();
    fs::create_dir_all(&root).expect("create root");
    {
        let mut config = fs::File::create(root.join("launcher.toml")).expect("config");
        writeln!(config, "[network]\nproxy = \"none\"\ntimeout_secs = 5").expect("write");
    }

    let service = fg_ffi::Service::new(root.clone()).expect("service");
    let result = service
        .block_on(service.rpc(
            "accounts.yggdrasil.login",
            json!({
                "server": format!("{}/api/yggdrasil", server.uri()),
                "username": "user@example.com",
                "password": "secret-password"
            }),
        ))
        .expect("login");
    assert_eq!(result["account"]["kind"], "yggdrasil");
    assert_eq!(result["account"]["name"], "Fograin");
    assert_eq!(result["account"]["serverName"], "LittleSkin");
    assert_eq!(result["selectedId"], "8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5");

    let accounts = fs::read_to_string(root.join("accounts.json")).expect("accounts");
    assert!(!accounts.contains("access-secret"));
    assert!(!accounts.contains("secret-password"));
    let credentials = fs::read_to_string(root.join("credentials.json")).expect("credentials");
    assert!(credentials.contains("access-secret"));
    assert!(credentials.contains("client-secret"));

    let listed = service
        .block_on(service.rpc("accounts.list", json!({})))
        .expect("list");
    assert_eq!(listed["accounts"].as_array().expect("rows").len(), 2);
    assert_eq!(listed["selectedId"], "8f0c1d2e-3a4b-5c6d-7e8f-90a1b2c3d4e5");

    let _ = fs::remove_dir_all(root);
}
