use super::helpers::*;
use super::params::*;
use super::*;

use tempfile::TempDir;

fn fixture() -> (TempDir, Arc<Service>) {
    let directory = TempDir::new().expect("tempdir");
    let service = Service::new(directory.path().to_path_buf()).expect("service");
    (directory, service)
}

#[test]
fn app_snapshot_reports_counts() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let snapshot = service
            .rpc("app.snapshot", json!({}))
            .await
            .expect("snapshot");
        assert_eq!(
            snapshot["platform"],
            json!(fg_core::Platform::detect().id())
        );
        assert_eq!(snapshot["instanceCount"], json!(0));
        assert!(snapshot["diskFreeBytes"].is_u64());
        assert!(snapshot["totalMemoryBytes"].is_u64());
    });
}

#[test]
fn instances_list_round_trip() {
    let (_dir, service) = fixture();
    service.block_on(async {
            let created = service
                .rpc(
                    "instances.create",
                    json!({ "id": "w1", "name": "雾林", "minecraft": "1.21.4", "loaderKind": "vanilla" }),
                )
                .await
                .expect("create");
            assert_eq!(created["id"], json!("w1"));

            let rows = service.rpc("instances.list", json!({})).await.expect("rows");
            assert_eq!(rows.as_array().expect("array").len(), 1);
            assert_eq!(rows[0]["name"], json!("雾林"));
            assert_eq!(rows[0]["installed"], json!(false));

            let summary = service.rpc("instances.summary", json!({})).await.expect("summary");
            assert_eq!(summary["recent"].as_array().expect("recent").len(), 1);
        });
}

#[test]
fn content_context_follows_the_instance_version_and_loader() {
    let (_dir, service) = fixture();
    service.block_on(async {
            service
                .rpc(
                    "instances.create",
                    json!({ "id": "forge", "name": "Forge", "minecraft": "1.20.1", "loaderKind": "forge" }),
                )
                .await
                .expect("create");
        });
    let instance = service.manager().load("forge").expect("instance");

    // A Forge instance searches Forge mods for its own Minecraft version.
    assert_eq!(
        content_context(Some(&instance), ContentKind::Mod, None, None),
        (Some("1.20.1".to_string()), Some("forge".to_string()))
    );
    // Resource packs are version-scoped but loader-agnostic.
    assert_eq!(
        content_context(Some(&instance), ContentKind::ResourcePack, None, None),
        (Some("1.20.1".to_string()), None)
    );
    // Explicit callers (the modpack tab) may override the instance context.
    assert_eq!(
        content_context(
            Some(&instance),
            ContentKind::Modpack,
            Some("1.21.4"),
            Some("fabric"),
        ),
        (Some("1.21.4".to_string()), Some("fabric".to_string()))
    );
}

#[test]
fn duplicate_create_is_conflict() {
    let (_dir, service) = fixture();
    service.block_on(async {
        service
            .rpc(
                "instances.create",
                json!({ "id": "dup", "name": "a", "minecraft": "1.21.4" }),
            )
            .await
            .expect("first");
        let error = service
            .rpc(
                "instances.create",
                json!({ "id": "dup", "name": "b", "minecraft": "1.21.4" }),
            )
            .await
            .expect_err("conflict");
        assert_eq!(error.message_key, "error.instance.duplicate");
    });
}

#[test]
fn rename_favorite_delete() {
    let (_dir, service) = fixture();
    service.block_on(async {
        service
            .rpc(
                "instances.create",
                json!({ "id": "inst", "name": "a", "minecraft": "1.21.4" }),
            )
            .await
            .expect("create");

        service
            .rpc(
                "instances.rename",
                json!({ "id": "inst", "name": "新名字" }),
            )
            .await
            .expect("rename");
        service
            .rpc(
                "instances.favorite",
                json!({ "id": "inst", "favorite": true }),
            )
            .await
            .expect("favorite");

        let rows = service
            .rpc("instances.list", json!({}))
            .await
            .expect("rows");
        assert_eq!(rows[0]["name"], json!("新名字"));
        assert_eq!(rows[0]["favorite"], json!(true));

        service
            .rpc("instances.delete", json!({ "id": "inst" }))
            .await
            .expect("delete");
        let rows = service
            .rpc("instances.list", json!({}))
            .await
            .expect("rows");
        assert!(rows.as_array().expect("array").is_empty());
    });
}

#[test]
fn game_config_round_trip() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let updated = service
            .rpc(
                "gameConfig.set",
                json!({ "username": "Alex", "memoryMb": 8192, "showLog": true }),
            )
            .await
            .expect("set");
        assert_eq!(updated["username"], json!("Alex"));
        assert_eq!(updated["memoryMb"], json!(8192));

        let read = service.rpc("gameConfig.get", json!({})).await.expect("get");
        assert_eq!(read["username"], json!("Alex"));
        assert_eq!(read["memoryMb"], json!(8192));
        assert_eq!(read["showLog"], json!(true));
    });
}

#[test]
fn launcher_settings_round_trip() {
    let (dir, service) = fixture();
    service.block_on(async {
        let defaults = service
            .rpc("settings.get", json!({}))
            .await
            .expect("settings");
        assert_eq!(defaults["language"], json!("system"));
        assert_eq!(defaults["accent"], json!("auto"));
        assert_eq!(defaults["initialPage"], json!("home"));
        assert_eq!(defaults["defaultDisplayAccount"], json!("community"));
        assert_eq!(defaults["download"]["source"], json!("auto"));
        assert_eq!(defaults["network"]["proxy"], json!("auto"));

        let updated = service
            .rpc(
                "settings.set",
                json!({
                    "theme": "light",
                    "language": "zh-Hant",
                    "accent": "#3f8f6b",
                    "initialPage": "library",
                    "defaultDisplayAccount": "launcher",
                    "download": { "source": "mirror", "concurrency": 24, "segments": 2 },
                    "network": { "proxy": "socks5://127.0.0.1:1080", "timeoutSecs": 20 }
                }),
            )
            .await
            .expect("set");
        assert_eq!(updated["settings"]["theme"], json!("light"));
        assert_eq!(updated["settings"]["language"], json!("zh-Hant"));
        assert_eq!(updated["settings"]["accent"], json!("#3F8F6B"));
        assert_eq!(updated["settings"]["initialPage"], json!("library"));
        assert_eq!(
            updated["settings"]["defaultDisplayAccount"],
            json!("launcher")
        );
        assert_eq!(updated["settings"]["download"]["source"], json!("mirror"));
        assert_eq!(updated["settings"]["download"]["concurrency"], json!(24));
        assert_eq!(updated["settings"]["network"]["timeoutSecs"], json!(20));
        assert_eq!(updated["snapshot"]["language"], json!("zh-Hant"));
        assert_eq!(updated["snapshot"]["accent"], json!("#3F8F6B"));
        assert_eq!(updated["snapshot"]["initialPage"], json!("library"));
        assert_eq!(
            updated["snapshot"]["defaultDisplayAccount"],
            json!("launcher")
        );

        let text =
            std::fs::read_to_string(fg_core::config::config_path(dir.path())).expect("config");
        assert!(text.contains("language = \"zh-Hant\""), "{text}");
        assert!(text.contains("accent = \"#3F8F6B\""), "{text}");
        assert!(text.contains("initial_page = \"library\""), "{text}");
        assert!(
            text.contains("default_display_account = \"launcher\""),
            "{text}"
        );
    });
}

#[test]
fn settings_set_rejects_a_malformed_proxy() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let error = service
            .rpc(
                "settings.set",
                json!({ "network": { "proxy": "not a proxy url" } }),
            )
            .await
            .expect_err("malformed proxy");
        assert_eq!(error.message_key, "error.settings.proxy");
    });
}

#[test]
fn settings_set_rejects_unknown_fields() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let error = service
            .rpc("settings.set", json!({ "proxy": "http://127.0.0.1:1" }))
            .await
            .expect_err("unknown top-level field");
        assert_eq!(error.message_key, "error.config.unknown_field");
    });
}

#[test]
fn settings_get_reports_inheritance() {
    let (_dir, service) = fixture();
    service.block_on(async {
        service
            .rpc(
                "instances.create",
                json!({ "id": "inst", "name": "a", "minecraft": "1.21.4" }),
            )
            .await
            .expect("create");

        let settings = service
            .rpc("instances.settings.get", json!({ "id": "inst" }))
            .await
            .expect("settings");
        assert_eq!(settings["effective"]["memoryMb"], json!(0));
        assert_eq!(settings["globals"]["username"], json!("Steve"));

        service
            .rpc(
                "instances.settings.set",
                json!({ "id": "inst", "updates": [ { "setting": "memory", "value": 6144 } ] }),
            )
            .await
            .expect("set memory");

        let settings = service
            .rpc("instances.settings.get", json!({ "id": "inst" }))
            .await
            .expect("settings");
        assert_eq!(settings["overrides"]["memoryMb"], json!(6144));
    });
}

#[test]
fn import_registers_external_directory() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let external = _dir.path().join("MyMinecraft");
        std::fs::create_dir_all(&external).expect("dir");
        std::fs::write(
            external.join("launcher_profiles.json"),
            r#"{"selectedProfile":{"lastVersionId":"1.20.1"}}"#,
        )
        .expect("profiles");

        let imported = service
            .rpc(
                "instances.import",
                json!({ "directory": external.display().to_string(), "name": "外部" }),
            )
            .await
            .expect("import");
        assert_eq!(imported["minecraft"], json!("1.20.1"));

        let rows = service
            .rpc("instances.list", json!({}))
            .await
            .expect("rows");
        assert_eq!(rows[0]["minecraft"], json!("1.20.1"));
    });
}

#[test]
fn loader_selection_is_validated_and_inherited() {
    let request = InstallStartRequest {
        version: "1.21.4".into(),
        instance_id: None,
        instance_name: None,
        loader_kind: "bogus".into(),
        loader_version: None,
    };
    let error = resolve_loader_selection(&request, None).expect_err("bad kind");
    assert_eq!(error.message_key, "error.loader.unknown_kind");

    let request = InstallStartRequest {
        version: "1.21.4".into(),
        instance_id: None,
        instance_name: None,
        loader_kind: String::new(),
        loader_version: None,
    };
    let directory = tempfile::tempdir().expect("tempdir");
    let layout = Layout::new(directory.path());
    layout.ensure_base_dirs().expect("dirs");
    let mut instance = Instance::create(&layout, "loader-selection", "1.21.4").expect("instance");
    instance.set_loader("fabric", "0.16.9", "fabric-loader-0.16.9-1.21.4");
    let selection = resolve_loader_selection(&request, Some(&instance))
        .expect("selection")
        .expect("loader");
    assert_eq!(selection.0, LoaderKind::Fabric);
    assert_eq!(selection.1, "0.16.9");
}

#[test]
fn java_list_merges_managed_markers_and_policy() {
    let (_dir, service) = fixture();
    let layout = service.layout().clone();
    install_fake_managed_runtime(&layout, "mojang-java-runtime-delta-21.0.7-x86_64", 21);

    service.block_on(async {
        let listed = service
            .rpc("java.list", json!({}))
            .await
            .expect("java.list");
        let runtimes = listed["runtimes"].as_array().expect("runtimes");
        let managed = runtimes
            .iter()
            .find(|row| row["id"] == json!("mojang-java-runtime-delta-21.0.7-x86_64"))
            .expect("managed runtime");
        assert_eq!(managed["managed"], json!(true));
        assert_eq!(managed["major"], json!(21));
        assert_eq!(managed["vendor"], json!("mojang"));
        assert_eq!(managed["source"], json!("Fograin runtimes"));
        assert_eq!(listed["autoDownload"], json!(true));

        let snapshot = service
            .rpc("app.snapshot", json!({}))
            .await
            .expect("snapshot");
        assert!(
            snapshot["javaCount"].as_u64().expect("count") >= 1,
            "{snapshot}"
        );

        let updated = service
            .rpc("java.policy.set", json!({ "autoDownload": false }))
            .await
            .expect("policy");
        assert_eq!(updated["autoDownload"], json!(false));
        let listed = service
            .rpc("java.list", json!({}))
            .await
            .expect("java.list");
        assert_eq!(listed["autoDownload"], json!(false));
    });

    let text =
        std::fs::read_to_string(fg_core::config::config_path(layout.root())).expect("config");
    assert!(text.contains("[java]"), "{text}");
    assert!(text.contains("auto_download = false"), "{text}");

    let removed = service
        .block_on(service.rpc(
            "java.remove",
            json!({
                "id": "mojang-java-runtime-delta-21.0.7-x86_64"
            }),
        ))
        .expect("remove");
    assert_eq!(
        removed["id"],
        json!("mojang-java-runtime-delta-21.0.7-x86_64")
    );
    assert!(
        !layout
            .runtimes()
            .join("mojang-java-runtime-delta-21.0.7-x86_64")
            .exists()
    );
}

#[test]
fn explicit_java_path_is_resolved_from_home_or_executable() {
    let directory = tempfile::tempdir().expect("tempdir");
    let home = directory.path().join("jdk-21");
    std::fs::create_dir_all(home.join("bin")).expect("bin");
    let executable = home
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
    std::fs::write(&executable, b"java").expect("java");
    std::fs::write(home.join("release"), "JAVA_VERSION=\"21.0.9\"\n").expect("release");

    let from_home = explicit_java(&home).expect("home");
    assert_eq!(from_home.major(), Some(21));
    let from_binary = explicit_java(&executable).expect("binary");
    assert_eq!(from_binary.home, home);

    let error = explicit_java(&directory.path().join("missing")).expect_err("missing");
    assert_eq!(error.message_key, "error.java.not_found");
}

#[test]
fn java_install_rejects_a_zero_major_without_network() {
    let (_dir, service) = fixture();
    let error = service
        .block_on(service.rpc("java.install", json!({ "major": 0 })))
        .expect_err("zero major");
    assert_eq!(error.message_key, "error.java.runtime_major");
}

fn install_fake_managed_runtime(layout: &Layout, id: &str, major: u32) {
    let directory = layout.runtimes().join(id);
    std::fs::create_dir_all(directory.join("bin")).expect("runtime bin");
    let executable = directory
        .join("bin")
        .join(if cfg!(windows) { "java.exe" } else { "java" });
    std::fs::write(&executable, b"fake java").expect("java");
    std::fs::write(
        directory.join("release"),
        format!("JAVA_VERSION=\"{major}.0.7\"\nOS_ARCH=\"x86_64\"\n"),
    )
    .expect("release");
    std::fs::write(
        directory.join(fg_java::RUNTIME_MARKER),
        serde_json::to_vec(&json!({
            "schemaVersion": 1,
            "id": id,
            "vendor": "mojang",
            "component": "java-runtime-delta",
            "version": format!("{major}.0.7"),
            "major": major,
            "arch": "x86_64",
            "platform": "linux",
            "fileCount": 1,
            "totalBytes": 9,
            "installedAtMs": 1,
        }))
        .expect("marker"),
    )
    .expect("write marker");
}

#[test]
fn unknown_method_is_invalid_input() {
    let (_dir, service) = fixture();
    service.block_on(async {
        let error = service
            .rpc("nope.nothing", json!({}))
            .await
            .expect_err("unknown");
        assert_eq!(error.message_key, "error.rpc.unknown_method");
    });
}

#[test]
fn install_start_creates_the_instance_and_completes_a_local_install() {
    let (_dir, service) = fixture();
    let layout = service.layout().clone();
    // Minimal local version: resolve_remote prefers installed JSON, and the
    // client jar is already present, so the graph only finalizes metadata.
    let version_id = "test-version";
    let jar_bytes = b"test";
    let sha1 = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
    std::fs::create_dir_all(layout.version_dir(version_id)).expect("version dir");
    std::fs::write(layout.version_jar(version_id), jar_bytes).expect("jar");
    std::fs::write(
            layout.version_json(version_id),
            format!(
                r#"{{"id":"{version_id}","type":"release","mainClass":"TestMain","downloads":{{"client":{{"url":"https://example.invalid/client.jar","sha1":"{sha1}","size":{}}}}}}}"#,
                jar_bytes.len()
            ),
        )
        .expect("version json");

    let started = service.block_on(async {
        service
            .rpc(
                "install.start",
                json!({
                    "version": version_id,
                    "instanceId": "inst-local",
                    "instanceName": "本地实例",
                }),
            )
            .await
            .expect("install started")
    });
    assert!(started["taskId"].is_string(), "{started}");

    // The instance row exists before the graph finishes so the UI can open
    // its settings page from the finished event.
    assert!(service.manager().exists("inst-local"));

    let mut succeeded = false;
    for _ in 0..80 {
        let rows = service.block_on(service.tasks_list()).expect("rows");
        if rows
            .as_array()
            .expect("array")
            .iter()
            .any(|row| row["state"] == json!("succeeded"))
        {
            succeeded = true;
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(25));
    }
    assert!(succeeded, "install task did not finish");
    assert!(layout.install_meta(version_id).is_file());
    let rows = service
        .block_on(service.instance_rows())
        .expect("rows after install");
    assert!(
        rows[0].installed,
        "installed flag must require the meta marker"
    );

    // Pressing play on an existing incomplete instance reuses the row and
    // queues the missing resources instead of failing as a duplicate.
    let restarted = service.block_on(async {
        service
            .rpc(
                "install.start",
                json!({
                    "version": version_id,
                    "instanceId": "inst-local",
                    "instanceName": "本地实例",
                }),
            )
            .await
            .expect("existing instance install")
    });
    assert!(restarted["taskId"].is_string());
    let rows = service
        .block_on(service.instance_rows())
        .expect("rows after restart");
    assert_eq!(rows.len(), 1);
}

#[test]
fn version_channels_separate_april_fools() {
    assert!(kind_matches(
        "release",
        "26.1.1",
        "release",
        Some("2026-04-01T09:06:36+00:00")
    ));
    assert!(!kind_matches(
        "april_fools",
        "26.1.1",
        "release",
        Some("2026-04-01T09:06:36+00:00")
    ));
    assert!(kind_matches(
        "april_fools",
        "1.RV-Pre1",
        "snapshot",
        Some("2016-03-31T16:18:53+00:00")
    ));
    assert!(!kind_matches(
        "snapshot",
        "1.RV-Pre1",
        "snapshot",
        Some("2016-03-31T16:18:53+00:00")
    ));
    assert!(kind_matches(
        "april_fools",
        "26w14a",
        "snapshot",
        Some("2026-04-01T12:13:30+00:00")
    ));
    assert!(!kind_matches(
        "snapshot",
        "26w14a",
        "snapshot",
        Some("2026-04-01T12:13:30+00:00")
    ));
    assert!(kind_matches(
        "old",
        "a1.2.0",
        "old_alpha",
        Some("2010-10-29T22:00:00+00:00")
    ));
    assert!(kind_matches(
        "april_fools",
        "2.0",
        "old_alpha",
        Some("2013-04-01T00:00:00+00:00")
    ));
    assert!(!kind_matches(
        "old",
        "2.0",
        "old_alpha",
        Some("2013-04-01T00:00:00+00:00")
    ));
}

#[test]
fn datapacks_are_managed_per_world() {
    let (dir, service) = fixture();
    service.block_on(async {
        service
            .rpc(
                "instances.create",
                json!({ "id": "packs", "name": "数据包", "minecraft": "1.21.4" }),
            )
            .await
            .expect("create");
        let game_dir = dir.path().join("instances/packs/.minecraft");
        let saves = game_dir.join("saves/World/datapacks");
        std::fs::create_dir_all(&saves).expect("saves");
        fg_world::datapack::write_pack(&saves.join("terralith.zip"), 48, "Terralith")
            .expect("pack");

        let listed = service
            .rpc(
                "datapacks.list",
                json!({ "instanceId": "packs", "world": "World" }),
            )
            .await
            .expect("list");
        assert_eq!(listed["count"], json!(1));
        assert_eq!(listed["items"][0]["title"], json!("Terralith"));

        let toggled = service
            .rpc(
                "datapacks.toggle",
                json!({
                    "instanceId": "packs",
                    "world": "World",
                    "fileName": "terralith.zip",
                    "enabled": false
                }),
            )
            .await
            .expect("toggle");
        assert_eq!(toggled["fileName"], json!("terralith.zip.disabled"));

        let deleted = service
            .rpc(
                "datapacks.delete",
                json!({
                    "instanceId": "packs",
                    "world": "World",
                    "fileName": "terralith.zip.disabled"
                }),
            )
            .await
            .expect("delete");
        assert!(deleted["items"].as_array().expect("array").is_empty());
    });
}

#[test]
fn diagnostics_find_a_memory_crash_and_export_a_bundle() {
    let (dir, service) = fixture();
    service.block_on(async {
        service
            .rpc(
                "instances.create",
                json!({ "id": "diag", "name": "诊断", "minecraft": "1.21.4" }),
            )
            .await
            .expect("create");
        let game_dir = dir.path().join("instances/diag/.minecraft");
        std::fs::create_dir_all(game_dir.join("logs")).expect("logs");
        std::fs::write(
            game_dir.join("logs/latest.log"),
            "[main/ERROR]: java.lang.OutOfMemoryError: Java heap space\n",
        )
        .expect("log");

        let analysis = service
            .rpc("diag.analyze", json!({ "instanceId": "diag" }))
            .await
            .expect("analyze");
        let findings = analysis["findings"].as_array().expect("findings");
        assert!(
            findings
                .iter()
                .any(|finding| finding["id"] == json!("java.memory"))
        );
        assert_eq!(analysis["severity"], json!("error"));
        assert!(analysis["scannedLines"].as_u64().unwrap_or_default() >= 1);

        let logs = service
            .rpc("diag.logs", json!({ "instanceId": "diag" }))
            .await
            .expect("logs");
        assert!(
            logs["texts"]["log"]
                .as_str()
                .unwrap_or_default()
                .contains("OutOfMemory")
        );

        let exported = service
            .rpc("diag.export", json!({ "instanceId": "diag" }))
            .await
            .expect("export");
        let path = exported["bundle"]["path"].as_str().expect("path");
        assert!(std::path::Path::new(path).is_file());
        assert!(exported["bundle"]["sizeBytes"].as_u64().unwrap_or_default() > 0);
    });
}

#[test]
fn task_queue_pauses_and_cancels_and_rejects_unsupported_resume() {
    let (_dir, service) = fixture();
    let mut builder = fg_task::TaskGraph::builder("slow");
    builder.task("slow", 1, |cx| async move {
        cx.cancel_handle().cancelled().await;
        Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"))
    });
    let graph = builder.build();
    let control = Control::new();
    let task_id = service.queue_task("test", control, graph, None, None);

    let state = |service: &Arc<Service>, task_id: &str| {
        service
            .block_on(service.tasks_list())
            .expect("rows")
            .as_array()
            .expect("array")
            .iter()
            .find(|row| row["id"] == json!(task_id))
            .map(|row| row["state"].as_str().unwrap_or_default().to_string())
            .unwrap_or_default()
    };

    for _ in 0..40 {
        if state(&service, &task_id) == "running" {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(state(&service, &task_id), "running");

    service
        .block_on(service.rpc("tasks.pause", json!({ "taskId": task_id })))
        .expect("pause");
    for _ in 0..40 {
        if state(&service, &task_id) == "paused" {
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(state(&service, &task_id), "paused");
    // Wait for `run_task` to exit so the service (and its runtime) is not
    // dropped from one of its own worker threads at the end of the test.
    assert!(service.block_on(service.wait_task(&task_id)).is_err());

    let error = service
        .block_on(service.rpc("tasks.resume", json!({ "taskId": task_id })))
        .expect_err("a graph without replay data cannot resume");
    assert_eq!(error.message_key, "error.task.resume_unsupported");

    service
        .block_on(service.rpc("tasks.cancel", json!({ "taskId": task_id })))
        .expect("cancel");
    assert_eq!(state(&service, &task_id), "cancelled");
}

#[test]
fn task_queue_resume_replays_the_original_rpc() {
    let (_dir, service) = fixture();
    let layout = service.layout().clone();
    let version_id = "resume-version";
    let jar_bytes = b"test";
    std::fs::create_dir_all(layout.version_dir(version_id)).expect("version dir");
    std::fs::write(layout.version_jar(version_id), jar_bytes).expect("jar");
    std::fs::write(
            layout.version_json(version_id),
            format!(
                r#"{{"id":"{version_id}","type":"release","mainClass":"TestMain","downloads":{{"client":{{"url":"https://example.invalid/client.jar","sha1":"a94a8fe5ccb19ba61c4c0873d391e987982fbbd3","size":{}}}}}}}"#,
                jar_bytes.len()
            ),
        )
        .expect("version json");

    let mut builder = fg_task::TaskGraph::builder("slow");
    builder.task("slow", 1, |cx| async move {
        cx.cancel_handle().cancelled().await;
        Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"))
    });
    let graph = builder.build();
    let control = Control::new();
    let replay = Some(TaskReplay {
        method: "install.start",
        params: json!({
            "version": version_id,
            "instanceId": "resume-instance",
            "instanceName": "恢复下载",
        }),
    });
    let task_id = service.queue_task("slow", control, graph, None, replay);
    std::thread::sleep(Duration::from_millis(30));
    service
        .block_on(service.rpc("tasks.pause", json!({ "taskId": task_id })))
        .expect("pause");
    assert!(service.block_on(service.wait_task(&task_id)).is_err());

    let resumed = service
        .block_on(service.rpc("tasks.resume", json!({ "taskId": task_id })))
        .expect("resume");
    let new_task = resumed["taskId"].as_str().expect("new task").to_string();
    assert_ne!(new_task, task_id);
    assert_eq!(resumed["oldTaskId"], json!(task_id));
    service
        .block_on(service.wait_task(&new_task))
        .expect("replayed install should finish");
    let rows = service.block_on(service.tasks_list()).expect("rows");
    assert!(
        rows.as_array()
            .expect("array")
            .iter()
            .all(|row| row["id"] != json!(task_id)),
        "paused row should be replaced by its replay"
    );
    assert!(service.manager().exists("resume-instance"));
}

#[test]
fn task_rows_expose_running_graphs() {
    let (_dir, service) = fixture();
    let mut builder = fg_task::TaskGraph::builder("t");
    builder.task("noop", 1, |_cx| async { Ok(()) });
    let graph = builder.build();
    let control = Control::new();
    service.tasks.lock().expect("queue").push(QueuedTask {
        id: "task-1".into(),
        name: "test".into(),
        control: control.clone(),
        graph: Arc::new(graph),
        runtime_id: None,
        replay: None,
        done: Arc::new(TaskDone::default()),
    });
    let rows = service.block_on(service.tasks_list()).expect("rows");
    let list = rows.as_array().expect("array");
    assert_eq!(list.len(), 1);
    assert_eq!(list[0]["id"], json!("task-1"));
}
