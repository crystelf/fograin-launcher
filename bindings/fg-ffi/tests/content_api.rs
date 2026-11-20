//! Content API smoke tests through the real RPC dispatch and a wiremock
//! Modrinth server. Verifies the camelCase request/response shapes that the
//! Kotlin client depends on and that `content.install` really queues a task.

use std::{fs, path::PathBuf};

use serde_json::json;
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{method, path},
};

fn temp_root() -> PathBuf {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    std::env::temp_dir().join(format!(
        "fograin-ffi-content-api-{}-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock after epoch")
            .as_nanos(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ))
}

#[tokio::test]
async fn content_search_versions_install_and_task_round_trip() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v2/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "hits": [{
                "project_id": "AANobbMI",
                "slug": "sodium",
                "title": "Sodium",
                "description": "Faster rendering",
                "downloads": 100,
                "follows": 7,
                "categories": ["fabric"]
            }],
            "total_hits": 1,
            "offset": 0,
            "limit": 5
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/AANobbMI"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "id": "AANobbMI",
            "slug": "sodium",
            "title": "Sodium",
            "description": "Faster rendering",
            "downloads": 100,
            "followers": 7,
            "categories": ["fabric"]
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/AANobbMI/version"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{
            "id": "c3YkZvne",
            "project_id": "AANobbMI",
            "name": "Sodium 1.0",
            "version_number": "1.0",
            "game_versions": ["1.21.4"],
            "loaders": ["fabric"],
            "downloads": 1,
            "version_type": "release",
            "files": [{
                "filename": "sodium.jar",
                "url": format!("{}/files/sodium.jar", server.uri()),
                "size": 3,
                "hashes": {"sha1": "a9993e364706816aba3e25717850c26c9cd0d89d"},
                "primary": true
            }],
            "dependencies": []
        }])))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/terralith"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "id": "terralith",
            "slug": "terralith",
            "title": "Terralith",
            "description": "New terrain",
            "downloads": 100,
            "followers": 7,
            "categories": ["datapack"]
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/terralith/version"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{
            "id": "terra-26",
            "project_id": "terralith",
            "name": "Terralith 2.6",
            "version_number": "2.6",
            "game_versions": ["1.21.4"],
            "loaders": [],
            "downloads": 1,
            "version_type": "release",
            "files": [{
                "filename": "terralith.zip",
                "url": format!("{}/files/terralith.zip", server.uri()),
                "size": 3,
                "hashes": {"sha1": "f13e27693c85aed522df8c3fcb0bb0110ca54e14"},
                "primary": true
            }],
            "dependencies": []
        }])))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/files/terralith.zip"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(b"zip".to_vec()))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/files/sodium.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(b"abc".to_vec()))
        .mount(&server)
        .await;

    let root = temp_root();
    fs::create_dir_all(root.join("cache")).expect("cache dir");
    // Resolve every Modrinth request through the mock server. The official API
    // must stay disabled here: with the default `official = true`, a runner
    // that can reach api.modrinth.com gets real search results instead of the
    // fixture, which made this test depend on the CI network.
    fs::write(
        root.join("cache").join("mirrors.toml"),
        format!(
            "version = 1\n\
             [[rule]]\n\
             source = \"https://api.modrinth.com\"\n\
             mirror = \"{}\"\n\
             official = false\n",
            server.uri()
        ),
    )
    .expect("mirror table");

    let service = fg_ffi::Service::new(root.clone()).expect("service");
    service
        .rpc(
            "instances.create",
            json!({
                "id": "fog",
                "name": "Fog",
                "minecraft": "1.21.4",
                "loaderKind": "fabric"
            }),
        )
        .await
        .expect("create instance");

    let search = service
        .rpc(
            "content.search",
            json!({
                "source": "modrinth",
                "kind": "mod",
                "query": "sodium",
                "instanceId": "fog",
                "limit": 5
            }),
        )
        .await
        .expect("content.search");
    assert_eq!(search["projects"][0]["title"], "Sodium");
    assert_eq!(search["projects"][0]["projectId"], "AANobbMI");
    assert_eq!(search["total"], 1);

    let versions = service
        .rpc(
            "content.versions",
            json!({
                "source": "modrinth",
                "projectId": "AANobbMI",
                "kind": "mod",
                "instanceId": "fog"
            }),
        )
        .await
        .expect("content.versions");
    assert_eq!(versions["versions"][0]["versionId"], "c3YkZvne");
    assert_eq!(versions["versions"][0]["file"]["fileName"], "sodium.jar");

    let plan = service
        .rpc(
            "content.plan",
            json!({
                "source": "modrinth",
                "projectId": "AANobbMI",
                "instanceId": "fog",
                "kind": "mod"
            }),
        )
        .await
        .expect("content.plan");
    assert_eq!(plan["projectName"], "Sodium");
    assert_eq!(plan["files"], 1);
    assert_eq!(plan["totalBytes"], 3);
    assert!(plan.get("taskId").is_none(), "{plan}");

    let install = service
        .rpc(
            "content.install",
            json!({
                "source": "modrinth",
                "projectId": "AANobbMI",
                "instanceId": "fog",
                "kind": "mod"
            }),
        )
        .await
        .expect("content.install");
    assert_eq!(install["projectName"], "Sodium");
    assert_eq!(install["files"], 1);
    assert_eq!(install["totalBytes"], 3);
    assert!(
        install["taskId"]
            .as_str()
            .is_some_and(|task| !task.is_empty()),
        "{install}"
    );

    let file = root
        .join("instances")
        .join("fog")
        .join(".minecraft")
        .join("mods")
        .join("sodium.jar");
    let index = root.join("instances").join("fog").join("content.json");
    for _ in 0..100 {
        if file.is_file() && index.is_file() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    assert_eq!(fs::read(&file).expect("downloaded mod"), b"abc");
    let index: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(&index).expect("index")).expect("index json");
    assert_eq!(index["items"][0]["projectId"], "AANobbMI");
    assert_eq!(index["items"][0]["source"], "modrinth");

    // Data packs install into one world and stay out of the instance index.
    let world_dir = root
        .join("instances")
        .join("fog")
        .join(".minecraft")
        .join("saves")
        .join("World");
    fs::create_dir_all(&world_dir).expect("world dir");
    let pack_install = service
        .rpc(
            "content.install",
            json!({
                "source": "modrinth",
                "projectId": "terralith",
                "instanceId": "fog",
                "kind": "datapack",
                "world": "World"
            }),
        )
        .await
        .expect("datapack install");
    assert_eq!(pack_install["kind"], "datapack");
    let pack_file = world_dir.join("datapacks").join("terralith.zip");
    for _ in 0..100 {
        if pack_file.is_file() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    assert_eq!(fs::read(&pack_file).expect("downloaded data pack"), b"zip");

    let _ = fs::remove_dir_all(root);
}
