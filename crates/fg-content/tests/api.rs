//! Wiremock coverage for the content APIs and the install graph.

use std::sync::Arc;

use fg_content::{
    ContentClient, ContentIndex, ContentKind, ContentSource, InstallRequest, SearchQuery,
    plan_install,
};
use fg_core::DownloadSource;
use fg_net::{Client, Downloader, MirrorTable, Region};
use fg_task::{Control, RunOptions};
use serde_json::json;
use sha1::{Digest, Sha1};
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{method, path},
};

fn table_for(server: &MockServer, source: &str) -> MirrorTable {
    MirrorTable::from_toml(&format!(
        "version = 1\n\
         [[rule]]\n\
         source = \"{source}\"\n\
         mirror = \"{}\"\n",
        server.uri()
    ))
    .expect("mirror table")
}

fn client(server: &MockServer, source: &str) -> ContentClient {
    ContentClient::new(
        Client::default_client().expect("client"),
        tempfile::tempdir().expect("cache").keep(),
        table_for(server, source),
        DownloadSource::Mirror,
        Region::RestOfWorld,
    )
}

fn sha1_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

#[tokio::test]
async fn modrinth_search_and_versions_parse_into_wire_models() {
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
                "icon_url": "https://cdn.modrinth.com/icon.png",
                "categories": ["fabric", "optimization"],
                "date_modified": "2026-01-01T00:00:00Z"
            }],
            "total_hits": 1,
            "offset": 0,
            "limit": 20
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/sodium/version"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{
            "id": "c3YkZvne",
            "project_id": "AANobbMI",
            "name": "Sodium 0.6.13",
            "version_number": "0.6.13",
            "game_versions": ["1.21.4"],
            "loaders": ["fabric"],
            "downloads": 5,
            "version_type": "release",
            "date_published": "2025-04-04T00:00:00Z",
            "files": [{
                "filename": "sodium.jar",
                "url": "https://cdn.modrinth.com/data/x/sodium.jar",
                "size": 10,
                "hashes": {"sha1": "abc"},
                "primary": true
            }],
            "dependencies": []
        }])))
        .mount(&server)
        .await;

    let client = client(&server, "https://api.modrinth.com");
    let page = client
        .modrinth_search(
            &SearchQuery::new(ContentKind::Mod)
                .with_query("sodium")
                .with_game_version(Some("1.21.4".into()))
                .with_loader(Some("fabric".into())),
        )
        .await
        .expect("search");
    assert_eq!(page.total, 1);
    assert_eq!(page.projects[0].title, "Sodium");
    assert_eq!(page.projects[0].downloads, 100);

    let versions = client
        .modrinth_versions("sodium", Some("1.21.4"), Some("fabric"))
        .await
        .expect("versions");
    assert_eq!(versions.len(), 1);
    assert_eq!(versions[0].version_id, "c3YkZvne");
    assert_eq!(
        versions[0].file.as_ref().expect("file").file_name,
        "sodium.jar"
    );
    assert_eq!(versions[0].version_type.as_str(), "release");
}

#[tokio::test]
async fn curseforge_search_maps_mcim_fields() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/mods/search"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{
                "id": 238222,
                "name": "Just Enough Items",
                "slug": "jei",
                "summary": "View items and recipes",
                "downloadCount": 42,
                "logo": {"thumbnailUrl": "https://media.forgecdn.net/jei.png"},
                "categories": [{"name": "Utility"}],
                "dateModified": "2026-01-01T00:00:00Z"
            }],
            "pagination": {"index": 0, "pageSize": 20, "totalCount": 1}
        })))
        .mount(&server)
        .await;

    let client = client(&server, "https://api.curseforge.com");
    let page = client
        .curseforge_search(&SearchQuery::new(ContentKind::Mod).with_query("jei"))
        .await
        .expect("search");
    assert_eq!(page.total, 1);
    assert_eq!(page.projects[0].project_id, "238222");
    assert_eq!(page.projects[0].title, "Just Enough Items");
    assert_eq!(page.projects[0].downloads, 42);
    assert_eq!(
        page.projects[0].icon_url.as_deref(),
        Some("https://media.forgecdn.net/jei.png")
    );
}

#[tokio::test]
async fn curseforge_files_resolve_download_url_and_dependencies() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path("/v1/mods/1/files"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [{
                "id": 10,
                "displayName": "Wick 1.0.1",
                "fileName": "wick.jar",
                "releaseType": 1,
                "fileDate": "2026-10-07T20:06:57.100Z",
                "fileLength": 42,
                "downloadUrl": null,
                "gameVersions": ["Fabric", "1.21.4"],
                "dependencies": [{"modId": 2, "relationType": 3}],
                "hashes": [{"value": "abc", "algo": 1}]
            }]
        })))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v1/mods/1/files/10/download-url"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": "https://edge.forgecdn.net/files/10/wick.jar"
        })))
        .mount(&server)
        .await;

    let client = client(&server, "https://api.curseforge.com");
    let versions = client
        .curseforge_versions("1", Some("1.21.4"), Some("fabric"))
        .await
        .expect("versions");
    assert_eq!(versions.len(), 1);
    assert_eq!(versions[0].version_id, "10");
    assert_eq!(versions[0].file.as_ref().expect("file").url, "");
    assert_eq!(versions[0].dependencies[0].kind, "required");

    let url = client
        .curseforge_download_url("1", 10)
        .await
        .expect("download url");
    assert!(url.contains("wick.jar"));
}

#[tokio::test]
async fn install_plan_resolves_dependencies_and_writes_the_index() {
    let server = MockServer::start().await;
    let sodium_bytes = b"sodium bytes";
    let api_bytes = b"fabric api bytes";
    let sodium_sha1 = sha1_hex(sodium_bytes);
    let api_sha1 = sha1_hex(api_bytes);

    for (project, title) in [("sodium", "Sodium"), ("fabric-api", "Fabric API")] {
        Mock::given(method("GET"))
            .and(path(format!("/v2/project/{project}")))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({
                "id": project,
                "slug": project,
                "title": title,
                "description": "",
                "downloads": 1,
                "followers": 1,
                "icon_url": format!("https://cdn.example/{project}.png"),
                "categories": []
            })))
            .mount(&server)
            .await;
    }

    Mock::given(method("GET"))
        .and(path("/v2/project/sodium/version"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{
            "id": "sodium-v",
            "project_id": "sodium",
            "name": "Sodium",
            "version_number": "1.0.0",
            "game_versions": ["1.21.4"],
            "loaders": ["fabric"],
            "downloads": 1,
            "version_type": "release",
            "files": [{
                "filename": "sodium.jar",
                "url": format!("{}/files/sodium.jar", server.uri()),
                "size": sodium_bytes.len(),
                "hashes": {"sha1": sodium_sha1},
                "primary": true
            }],
            "dependencies": [{
                "project_id": "fabric-api",
                "dependency_type": "required"
            }]
        }])))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/v2/project/fabric-api/version"))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!([{
            "id": "api-v",
            "project_id": "fabric-api",
            "name": "Fabric API",
            "version_number": "0.1.0",
            "game_versions": ["1.21.4"],
            "loaders": ["fabric"],
            "downloads": 1,
            "version_type": "release",
            "files": [{
                "filename": "fabric-api.jar",
                "url": format!("{}/files/fabric-api.jar", server.uri()),
                "size": api_bytes.len(),
                "hashes": {"sha1": api_sha1},
                "primary": true
            }],
            "dependencies": []
        }])))
        .mount(&server)
        .await;

    Mock::given(method("GET"))
        .and(path("/files/sodium.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(sodium_bytes.to_vec()))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/files/fabric-api.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(api_bytes.to_vec()))
        .mount(&server)
        .await;

    let temp = tempfile::tempdir().expect("tempdir");
    let instance_dir = temp.path().join("instances").join("fog");
    let game_dir = instance_dir.join(".minecraft");
    std::fs::create_dir_all(&game_dir).expect("game dir");

    let client = client(&server, "https://api.modrinth.com");
    let plan = plan_install(
        &client,
        InstallRequest {
            source: ContentSource::Modrinth,
            project_id: "sodium".into(),
            version_id: None,
            kind: ContentKind::Mod,
            instance_id: "fog".into(),
            instance_dir: instance_dir.clone(),
            game_dir: game_dir.clone(),
            game_version: "1.21.4".into(),
            loader: Some("fabric".into()),
            world: None,
            replace_files: Vec::new(),
            installed_projects: Vec::new(),
            install_dependencies: true,
        },
    )
    .await
    .expect("plan");
    assert_eq!(plan.items.len(), 2);
    assert_eq!(
        plan.total_bytes,
        (sodium_bytes.len() + api_bytes.len()) as u64
    );

    let primary_only = plan_install(
        &client,
        InstallRequest {
            source: ContentSource::Modrinth,
            project_id: "sodium".into(),
            version_id: None,
            kind: ContentKind::Mod,
            instance_id: "fog".into(),
            instance_dir: instance_dir.clone(),
            game_dir: game_dir.clone(),
            game_version: "1.21.4".into(),
            loader: Some("fabric".into()),
            world: None,
            replace_files: Vec::new(),
            installed_projects: Vec::new(),
            install_dependencies: false,
        },
    )
    .await
    .expect("primary-only plan");
    assert_eq!(primary_only.items.len(), 1);
    assert_eq!(primary_only.items[0].file_name, "sodium.jar");

    let downloader = Arc::new(
        Downloader::builder(temp.path())
            .build()
            .expect("downloader"),
    );
    let graph = Arc::new(plan).graph(downloader, client);
    let report = graph.run(&Control::new(), &RunOptions::default()).await;
    assert!(report.is_success(), "{:?}", report.failed);

    assert_eq!(
        std::fs::read(game_dir.join("mods").join("sodium.jar")).expect("sodium"),
        sodium_bytes
    );
    assert_eq!(
        std::fs::read(game_dir.join("mods").join("fabric-api.jar")).expect("api"),
        api_bytes
    );
    let index = ContentIndex::load(&instance_dir).expect("index");
    assert_eq!(index.items.len(), 2);
    let sodium = index
        .items
        .iter()
        .find(|entry| entry.project_id == "sodium")
        .expect("sodium index entry");
    assert_eq!(sodium.version_id, "sodium-v");
    assert_eq!(
        sodium.icon_url.as_deref(),
        Some("https://cdn.example/sodium.png")
    );
    assert!(
        index
            .items
            .iter()
            .any(|entry| entry.project_id == "fabric-api"
                && entry.icon_url.as_deref() == Some("https://cdn.example/fabric-api.png"))
    );
}
