//! Manual end-to-end checks against real loader installers.
//!
//! These tests are ignored by default because they download tens of megabytes
//! and need a Java runtime. Use `fg-cli install ... --dry-run` to produce a
//! plan first, then:
//!
//! ```bash
//! FOGRAIN_MANUAL_ROOT=/tmp/fograin-m5-forge \
//! FOGRAIN_MANUAL_PLAN=/tmp/fograin-m5-forge/cache/install/1.20.1-forge-47.4.29.json \
//! JAVA_HOME=/path/to/jdk \
//! cargo test -p fg-loader --test manual_installers -- --ignored --nocapture
//! ```

use std::path::{Path, PathBuf};

use fg_core::DownloadSource;
use fg_loader::{ExecuteOptions, LoaderFile, LoaderPlan, execute};
use fg_net::{Candidate, DownloadRequest, Downloader, MirrorTable, Priority, Region};
use fg_task::{Control, RunOptions, TaskGraph};

fn manual_root() -> Option<(PathBuf, LoaderPlan)> {
    let root = PathBuf::from(std::env::var("FOGRAIN_MANUAL_ROOT").ok()?);
    let plan_path = PathBuf::from(std::env::var("FOGRAIN_MANUAL_PLAN").ok()?);
    let text = std::fs::read_to_string(plan_path).expect("plan json");
    let value: serde_json::Value = serde_json::from_str(&text).expect("plan json");
    let loader = value.get("loader").cloned().unwrap_or(value);
    let plan: LoaderPlan = serde_json::from_value(loader).expect("loader plan");
    Some((root, plan))
}

fn java() -> PathBuf {
    if let Ok(home) = std::env::var("JAVA_HOME") {
        let candidate = Path::new(&home).join("bin/java");
        if candidate.is_file() {
            return candidate;
        }
    }
    PathBuf::from("java")
}

async fn download_files(root: &Path, files: &[LoaderFile]) {
    let downloader = Downloader::new(root).expect("downloader");
    let table = MirrorTable::embedded().clone();
    for file in files {
        let destination = root.join(&file.path);
        if destination.is_file() {
            continue;
        }
        let urls = table.candidates(&file.url, DownloadSource::Official, Region::RestOfWorld);
        let candidates = urls
            .into_iter()
            .map(|url| Candidate::parse(&url).expect("candidate"))
            .collect::<Vec<_>>();
        let mut request =
            DownloadRequest::new(&destination, candidates).with_priority(Priority::Library);
        if let Some(size) = file.size {
            request = request.with_size_hint(size);
        }
        if let Some(sha1) = &file.sha1 {
            request = request.with_sha1(sha1.clone());
        }
        let outcome = downloader.download(request).await.expect("download");
        eprintln!(
            "downloaded {} ({} bytes)",
            outcome.dest.display(),
            outcome.bytes
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "downloads real loader artifacts and runs Java processors"]
async fn manual_loader_install() {
    let Some((root, plan)) = manual_root() else {
        eprintln!("FOGRAIN_MANUAL_ROOT / FOGRAIN_MANUAL_PLAN are not set; skipping");
        return;
    };
    download_files(&root, &plan.files).await;

    let mut builder = TaskGraph::builder("manual-loader");
    let root_for_task = root.clone();
    let plan_for_task = plan.clone();
    let java = java();
    builder.task("execute", 1, move |cx| async move {
        execute(
            &plan_for_task,
            &ExecuteOptions {
                root: &root_for_task,
                java: Some(&java),
            },
            &cx,
        )
        .await
    });
    let report = builder
        .build()
        .run(&Control::new(), &RunOptions::default())
        .await;
    assert!(report.is_success(), "{:?}", report.failed);
    eprintln!("loader install finished for {}", plan.version_id);
}
