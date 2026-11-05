//! Composition test: a task graph drives the download engine (§6.2 + §6.3).
//!
//! This is the wiring the installer will use from M3: one node per download
//! step, node progress forwarded into the graph, and the graph's cancellation
//! token handed to the engine.

use std::{
    sync::Arc,
    time::{Duration, Instant},
};

use fg_net::{DownloadCtx, DownloadRequest, Downloader, ProgressSink};
use fg_task::{Control, GraphBuilder, RunOptions, StopReason, TaskCx};
use wiremock::{Mock, MockServer, ResponseTemplate, matchers::method};

/// Forwards engine progress into the task node.
struct TaskProgress(TaskCx);

impl ProgressSink for TaskProgress {
    fn on_total(&self, total: Option<u64>) {
        if let Some(total) = total {
            self.0.set_total(total);
        }
    }

    fn on_bytes(&self, bytes: u64) {
        self.0.advance(bytes);
    }
}

fn options() -> RunOptions {
    RunOptions {
        max_parallel: 4,
        ..RunOptions::default()
    }
}

#[tokio::test]
async fn a_graph_node_can_drive_a_download() {
    let server = MockServer::start().await;
    let payload = b"fograin graph payload".to_vec();
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = Arc::new(Downloader::new(dir.path()).expect("engine"));
    let url = format!("{}/file.bin", server.uri());
    let dest = dir.path().join("file.bin");
    let digest = fg_net::hash::hash_bytes(&payload, fg_net::HashAlgorithm::Sha1);

    let mut builder = GraphBuilder::new("install");
    {
        let downloader = downloader.clone();
        let url = url.clone();
        let dest = dest.clone();
        let task_dest = dest.clone();
        let digest = digest.clone();
        builder.task("download", 2, move |cx| async move {
            let ctx = DownloadCtx {
                cancel: cx.cancel_handle(),
                progress: Arc::new(TaskProgress(cx.clone())),
            };
            let request = DownloadRequest::from_url(&task_dest, &url)
                .expect("url")
                .with_sha1(digest);

            downloader.download_with(request, ctx).await?;
            Ok(())
        });
    }
    builder.task("install", 1, |cx| async move { cx.check() });

    let graph = builder.build();
    let report = graph.run(&Control::new(), &options()).await;

    assert!(report.is_success(), "{report:?}");
    assert_eq!(report.succeeded, 2);
    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);

    let progress = graph.progress();
    assert!((progress.fraction - 1.0).abs() < 1e-9);
}

#[tokio::test]
async fn pausing_the_graph_stops_the_download() {
    let server = MockServer::start().await;
    let payload = vec![7_u8; 64 * 1024];
    Mock::given(method("GET"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_delay(Duration::from_millis(800))
                .set_body_bytes(payload),
        )
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = Arc::new(Downloader::new(dir.path()).expect("engine"));
    let url = format!("{}/file.bin", server.uri());
    let dest = dir.path().join("file.bin");
    let control = Control::new();

    let mut builder = GraphBuilder::new("install");
    {
        let downloader = downloader.clone();
        let control = control.clone();
        let task_dest = dest.clone();
        builder.task("download", 1, move |cx| async move {
            let ctx = DownloadCtx {
                cancel: cx.cancel_handle(),
                progress: Arc::new(TaskProgress(cx.clone())),
            };
            let request = DownloadRequest::from_url(&task_dest, &url).expect("url");

            // Pause while the server is still delaying its response.
            let pause = tokio::spawn(async move {
                tokio::time::sleep(Duration::from_millis(80)).await;
                control.pause();
            });

            let result = downloader.download_with(request, ctx).await;
            pause.await.expect("pause task");
            result.map(|_| ())
        });
    }

    let started = Instant::now();
    let report = builder.build().run(&control, &options()).await;

    assert_eq!(report.interrupted, Some(StopReason::Paused), "{report:?}");
    assert_eq!(report.succeeded, 0);
    assert_eq!(report.skipped, vec!["download".to_string()]);
    assert!(started.elapsed() < Duration::from_secs(5));
    assert!(!dest.exists());
}
