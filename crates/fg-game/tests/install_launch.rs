//! End-to-end install + launch-plan test against a local HTTP server.
//!
//! The real Mojang endpoints are covered by the CLI acceptance run; this test
//! pins the wiring (manifest → version json → client/libraries/assets → launch
//! command) in CI without network access or hundreds of megabytes.

use std::{path::PathBuf, sync::Arc};

use fg_auth::OfflineAccount;
use fg_core::DownloadSource;
use fg_game::{
    install::{FileKind, Installer, run_plan},
    instance::Instance,
    launch::{LaunchOptions, LaunchPlan, verify_installed},
    layout::Layout,
    resolver::VersionResolver,
    rules::{Features, OsInfo, RuleContext},
};
use fg_java::{JavaArch, JavaRuntime, JavaSource, JavaVersion};
use fg_net::{Downloader, MirrorTable, Region};
use fg_task::{Control, RunOptions};
use serde_json::json;
use sha1::{Digest, Sha1};
use wiremock::{
    Mock, MockServer, ResponseTemplate,
    matchers::{method, path},
};

fn sha1(bytes: &[u8]) -> String {
    let mut hasher = Sha1::new();
    hasher.update(bytes);
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn context() -> RuleContext {
    RuleContext::new(
        OsInfo {
            name: "osx".into(),
            arch: JavaArch::Arm64,
            version: Some("15.1".into()),
        },
        Features::new(),
    )
}

#[tokio::test]
async fn installs_and_builds_a_launch_command_against_a_mock_origin() {
    let server = MockServer::start().await;
    let base = server.uri();

    let client = b"client jar";
    let library = b"library jar";
    let asset = b"abc";
    let asset_hash = sha1(asset);
    let index_body = format!(
        r#"{{"objects":{{"foo.txt":{{"hash":"{asset_hash}","size":{}}}}}}}"#,
        asset.len()
    );
    let log_body = b"<Configuration/>";

    let version = json!({
        "id": "test",
        "type": "release",
        "time": "2026-01-01T00:00:00Z",
        "releaseTime": "2026-01-01T00:00:00Z",
        "mainClass": "test.Main",
        "assets": "test-assets",
        "assetIndex": {
            "id": "test-assets",
            "sha1": sha1(index_body.as_bytes()),
            "size": index_body.len(),
            "url": format!("{base}/assets/index.json")
        },
        "downloads": {
            "client": {
                "path": "test/test.jar",
                "sha1": sha1(client),
                "size": client.len(),
                "url": format!("{base}/client.jar")
            }
        },
        "libraries": [{
            "name": "com.example:lib:1.0",
            "downloads": { "artifact": {
                "path": "com/example/lib/1.0/lib-1.0.jar",
                "sha1": sha1(library),
                "size": library.len(),
                "url": format!("{base}/lib.jar")
            }}
        }],
        "logging": { "client": {
            "argument": "-Dlog4j.configurationFile=${path}",
            "file": {
                "id": "client-test.xml",
                "sha1": sha1(log_body),
                "size": log_body.len(),
                "url": format!("{base}/log.xml")
            },
            "type": "log4j2-xml"
        }},
        "javaVersion": { "majorVersion": 21 },
        "arguments": {
            "game": [
                "--username", "${auth_player_name}",
                "--version", "${version_name}",
                "--gameDir", "${game_directory}",
                "--assetsDir", "${assets_root}",
                "--assetIndex", "${assets_index_name}",
                "--uuid", "${auth_uuid}",
                "--accessToken", "${auth_access_token}",
                "--userType", "${user_type}",
                "--versionType", "${version_type}"
            ],
            "jvm": [
                "-Djava.library.path=${natives_directory}",
                "-cp", "${classpath}"
            ]
        }
    });
    let version_body = serde_json::to_vec(&version).expect("version json");
    let manifest = json!({
        "latest": { "release": "test", "snapshot": "test" },
        "versions": [{
            "id": "test",
            "type": "release",
            "url": format!("{base}/versions/test.json"),
            "time": "2026-01-01T00:00:00Z",
            "releaseTime": "2026-01-01T00:00:00Z",
            "sha1": sha1(&version_body),
            "complianceLevel": 1
        }]
    });

    for (route, body) in [
        (
            "/mc/game/version_manifest_v2.json",
            serde_json::to_vec(&manifest).expect("manifest"),
        ),
        ("/versions/test.json", version_body),
        ("/client.jar", client.to_vec()),
        ("/lib.jar", library.to_vec()),
        ("/assets/index.json", index_body.into_bytes()),
        (
            &format!("/assets/{}/{}", &asset_hash[..2], asset_hash) as &str,
            asset.to_vec(),
        ),
        ("/log.xml", log_body.to_vec()),
    ] {
        Mock::given(method("GET"))
            .and(path(route))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(body))
            .mount(&server)
            .await;
    }

    let directory = tempfile::tempdir().expect("tempdir");
    let layout = Layout::new(directory.path());
    layout.ensure_base_dirs().expect("dirs");
    // A stale local version must not win when `--refresh` is used.
    std::fs::create_dir_all(layout.version_dir("test")).expect("version dir");
    std::fs::write(
        layout.version_json("test"),
        br#"{"id":"test","mainClass":"stale.Main","libraries":[]}"#,
    )
    .expect("stale json");
    let downloader = Arc::new(Downloader::new(layout.root()).expect("downloader"));
    let resolver = VersionResolver::new(
        downloader.clone(),
        layout.clone(),
        MirrorTable::embedded().clone(),
        DownloadSource::Official,
        Region::RestOfWorld,
    )
    .with_manifest_url(format!("{base}/mc/game/version_manifest_v2.json"));
    let installer = Installer::new(
        downloader,
        resolver.clone(),
        layout.clone(),
        MirrorTable::embedded().clone(),
        DownloadSource::Official,
        Region::RestOfWorld,
        context(),
    )
    .with_asset_objects_url(format!("{base}/assets"));

    let plan = installer
        .plan("test", true, Some("inst"))
        .await
        .expect("plan");
    assert_eq!(plan.java_major, 21);
    assert_eq!(
        plan.main_class, "test.Main",
        "refresh must ignore stale local json"
    );
    assert_eq!(plan.files_of(FileKind::Client).len(), 1);
    assert_eq!(plan.files_of(FileKind::Library).len(), 1);
    assert_eq!(plan.files_of(FileKind::Asset).len(), 1);
    assert_eq!(plan.files_of(FileKind::AssetIndex).len(), 1);
    assert_eq!(plan.files_of(FileKind::LogConfig).len(), 1);

    plan.save(&layout).expect("save plan");
    let report = run_plan(&installer, &plan, &Control::new(), &RunOptions::default())
        .await
        .expect("install");
    assert!(report.is_success());
    assert!(layout.version_jar("test").is_file());
    assert!(
        layout
            .libraries()
            .join("com/example/lib/1.0/lib-1.0.jar")
            .is_file()
    );
    assert!(
        layout
            .asset_objects()
            .join(&asset_hash[..2])
            .join(&asset_hash)
            .is_file()
    );
    assert!(layout.log_configs().join("client-test.xml").is_file());

    let instance = Instance::create(&layout, "inst", "test").expect("instance");
    let resolved = resolver.resolve_local("test").expect("resolved");
    verify_installed(&layout, &resolved, &context()).expect("complete");
    let java = JavaRuntime {
        home: PathBuf::from("/jdk-21"),
        executable: PathBuf::from("/jdk-21/bin/java"),
        version: Some(JavaVersion {
            major: 21,
            full: "21.0.9".into(),
        }),
        arch: JavaArch::Arm64,
        vendor: None,
        source: JavaSource::ExtraRoot,
    };
    let plan = LaunchPlan::build(
        &layout,
        &instance,
        &resolved,
        &java,
        &OfflineAccount::new("Steve").expect("account"),
        &LaunchOptions {
            rule_context: Some(context()),
            ..LaunchOptions::default()
        },
    )
    .expect("launch plan");

    let joined = plan.args.join(" ");
    assert!(joined.contains("test.Main"));
    assert!(joined.contains("--username Steve"));
    assert!(joined.contains("--accessToken 0"));
    assert!(joined.contains("--assetIndex test-assets"));
    assert!(joined.contains("-cp"));
    assert!(
        plan.classpath
            .iter()
            .any(|path| path.ends_with("client.jar") || path.ends_with("test.jar"))
    );
}
