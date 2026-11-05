//! End-to-end tests for the download engine (§6.2, §14 M2).
//!
//! `wiremock` covers well-formed servers; a tiny raw TCP server covers protocol
//! abuse (truncated bodies, 416) that a mocking library purposely does not
//! emulate.

use std::{
    path::Path,
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration,
};

use fg_net::{
    CachePolicy, Candidate, DownloadOptions, DownloadRequest, Downloader, Integrity,
    hash::hash_bytes,
};
use tokio::{
    io::{AsyncReadExt as _, AsyncWriteExt as _},
    net::TcpListener,
};
use wiremock::{
    Mock, MockServer, Request, Respond, ResponseTemplate,
    matchers::{header, method, path},
};

fn engine(root: &Path) -> Downloader {
    Downloader::builder(root)
        .options(DownloadOptions {
            retries: 3,
            backoff_base: Duration::from_millis(5),
            backoff_cap: Duration::from_millis(25),
            connect_timeout: Duration::from_secs(3),
            read_timeout: Duration::from_secs(3),
            segments: 4,
            segment_threshold: 4,
            enable_segments: true,
        })
        .concurrency(8)
        .build()
        .expect("engine")
}

fn sha1(bytes: &[u8]) -> String {
    hash_bytes(bytes, fg_net::HashAlgorithm::Sha1)
}

#[tokio::test]
async fn downloads_a_file_and_verifies_its_sha1() {
    let server = MockServer::start().await;
    let payload = b"fograin payload".to_vec();
    Mock::given(method("GET"))
        .and(path("/file.bin"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = engine(dir.path());
    let dest = dir.path().join("nested").join("file.bin");
    let url = format!("{}/file.bin", server.uri());

    let outcome = downloader
        .download(
            DownloadRequest::from_url(&dest, &url)
                .expect("url")
                .with_sha1(sha1(&payload)),
        )
        .await
        .expect("download");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(outcome.bytes, payload.len() as u64);
    assert!(!outcome.from_cache);
    assert_eq!(outcome.source, Some(url.parse().expect("url")));
    assert!(!fg_net::engine::part_path(&dest).exists());
}

#[tokio::test]
async fn a_complete_destination_is_not_downloaded_again() {
    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(b"payload".to_vec()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");
    tokio::fs::write(&dest, b"payload").await.expect("seed");

    let outcome = engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &format!("{}/file.bin", server.uri()))
                .expect("url")
                .with_sha1(sha1(b"payload")),
        )
        .await
        .expect("download");

    assert_eq!(outcome.source, None);
    assert_eq!(server.received_requests().await.expect("requests").len(), 0);
}

#[tokio::test]
async fn content_cache_serves_the_second_download() {
    let server = MockServer::start().await;
    let payload = b"cached payload".to_vec();
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = engine(dir.path());
    let url = format!("{}/file.bin", server.uri());
    let first_dest = dir.path().join("first.bin");
    let second_dest = dir.path().join("second.bin");

    let request = || {
        DownloadRequest::from_url(&first_dest, &url)
            .expect("url")
            .with_sha1(sha1(&payload))
            .with_cache(CachePolicy::ContentAddressed)
    };

    let first = downloader
        .download(request())
        .await
        .expect("first download");
    assert!(!first.from_cache);

    let second = downloader
        .download({
            let mut request = request();
            request.dest = second_dest.clone();
            request
        })
        .await
        .expect("second download");

    assert!(second.from_cache);
    assert_eq!(tokio::fs::read(&second_dest).await.expect("read"), payload);
    assert_eq!(server.received_requests().await.expect("requests").len(), 1);

    // §5.1 layout: cache/sha1/<aa>/<hash>.
    let hash = sha1(&payload);
    let cached = dir
        .path()
        .join("cache")
        .join("sha1")
        .join(&hash[..2])
        .join(&hash);
    assert!(cached.exists(), "expected cache at {}", cached.display());
}

#[tokio::test]
async fn retries_a_server_error_before_succeeding() {
    let server = MockServer::start().await;
    let payload = b"eventually".to_vec();

    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(503))
        .up_to_n_times(2)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");

    engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &format!("{}/file.bin", server.uri()))
                .expect("url")
                .with_sha1(sha1(&payload)),
        )
        .await
        .expect("download");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
}

#[tokio::test]
async fn a_hash_mismatch_falls_back_to_the_next_candidate() {
    let bad = MockServer::start().await;
    let good = MockServer::start().await;
    let payload = b"correct payload".to_vec();

    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(b"corrupt".to_vec()))
        .mount(&bad)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&good)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");
    let candidates = vec![
        Candidate::parse(&format!("{}/file.bin", bad.uri())).expect("url"),
        Candidate::parse(&format!("{}/file.bin", good.uri())).expect("url"),
    ];

    let outcome = engine(dir.path())
        .download(
            DownloadRequest::new(&dest, candidates).with_integrity(Integrity::sha1(sha1(&payload))),
        )
        .await
        .expect("download");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(
        outcome.source.as_ref().map(|url| url.to_string()),
        Some(format!("{}/file.bin", good.uri()))
    );
    assert_eq!(bad.received_requests().await.expect("requests").len(), 1);
}

#[tokio::test]
async fn all_candidates_failing_reports_every_url() {
    let first = MockServer::start().await;
    let second = MockServer::start().await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&first)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&second)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");
    let candidates = vec![
        Candidate::parse(&format!("{}/file.bin", first.uri())).expect("url"),
        Candidate::parse(&format!("{}/file.bin", second.uri())).expect("url"),
    ];

    let error = engine(dir.path())
        .download(DownloadRequest::new(&dest, candidates).with_integrity(Integrity::Size(3)))
        .await
        .expect_err("both candidates fail");

    assert_eq!(error.kind, fg_net::fg_core::ErrorKind::Network);
    assert_eq!(error.message_key, "error.net.all_candidates");
    let detail = error.detail.unwrap_or_default();
    assert!(detail.contains(&first.uri()), "{detail}");
    assert!(detail.contains(&second.uri()), "{detail}");
    assert!(!dest.exists());
}

#[tokio::test]
async fn conditional_cache_turns_304_into_a_cache_hit() {
    let server = MockServer::start().await;
    let body = br#"{"versions":[]}"#.to_vec();

    Mock::given(method("GET"))
        .and(path("/manifest.json"))
        .and(header("if-none-match", "\"v1\""))
        .respond_with(ResponseTemplate::new(304))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/manifest.json"))
        .respond_with(
            ResponseTemplate::new(200)
                .insert_header("etag", "\"v1\"")
                .insert_header("last-modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                .set_body_bytes(body.clone()),
        )
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = engine(dir.path());
    let url = format!("{}/manifest.json", server.uri());
    let key = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    let first = downloader
        .fetch_bytes(vec![url.clone()], key)
        .await
        .expect("first fetch");
    assert!(!first.from_cache);
    assert_eq!(first.bytes.as_ref(), body.as_slice());

    let second = downloader
        .fetch_bytes(vec![url], key)
        .await
        .expect("second fetch");
    assert!(second.from_cache);
    assert_eq!(second.bytes.as_ref(), body.as_slice());
}

/// Serves byte ranges from a fixed body, letting the engine segment a file.
struct RangeResponder {
    body: Vec<u8>,
}

impl Respond for RangeResponder {
    fn respond(&self, request: &Request) -> ResponseTemplate {
        let total = self.body.len();

        match request
            .headers
            .get("range")
            .and_then(|value| value.to_str().ok())
        {
            Some(range) => {
                let range = range.trim_start_matches("bytes=");
                let (start, end) = range.split_once('-').expect("range format");
                let start: usize = start.parse().expect("start");
                let end: usize = if end.is_empty() {
                    total - 1
                } else {
                    end.parse().expect("end")
                };

                ResponseTemplate::new(206)
                    .insert_header("content-range", format!("bytes {start}-{end}/{total}"))
                    .insert_header("accept-ranges", "bytes")
                    .set_body_bytes(self.body[start..=end].to_vec())
            }
            None => ResponseTemplate::new(200)
                .insert_header("accept-ranges", "bytes")
                .set_body_bytes(self.body.clone()),
        }
    }
}

#[tokio::test]
async fn segmented_download_merges_parallel_ranges() {
    let server = MockServer::start().await;
    let payload: Vec<u8> = (0_u8..32).collect();

    Mock::given(method("GET"))
        .respond_with(RangeResponder {
            body: payload.clone(),
        })
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("big.bin");
    let url = format!("{}/big.bin", server.uri());

    let outcome = engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &url)
                .expect("url")
                .with_size_hint(payload.len() as u64)
                .with_sha1(sha1(&payload)),
        )
        .await
        .expect("download");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(outcome.bytes, 32);

    let requests = server.received_requests().await.expect("requests");
    assert!(
        requests
            .iter()
            .all(|request| request.headers.get("range").is_some()),
        "every segment must request a byte range"
    );
    assert_eq!(requests.len(), 4, "4 segments for a 32-byte file");
}

#[tokio::test]
async fn download_many_collects_failures_and_counts_bytes() {
    let good = MockServer::start().await;
    let bad = MockServer::start().await;
    let payload = b"batch payload".to_vec();

    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&good)
        .await;
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(404))
        .mount(&bad)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let downloader = engine(dir.path());
    let ok_dest = dir.path().join("ok.bin");
    let failed_dest = dir.path().join("missing.bin");

    let report = downloader
        .download_many(vec![
            DownloadRequest::from_url(&ok_dest, &format!("{}/ok.bin", good.uri()))
                .expect("url")
                .with_sha1(sha1(&payload)),
            DownloadRequest::from_url(&failed_dest, &format!("{}/missing.bin", bad.uri()))
                .expect("url"),
        ])
        .await;

    assert_eq!(report.succeeded, 1);
    assert_eq!(report.failed.len(), 1);
    assert_eq!(report.failed[0].0, failed_dest);
    assert_eq!(report.bytes, payload.len() as u64);
    assert!(!report.is_success());
}

// ---------------------------------------------------------------------------
// Raw TCP server: truncated bodies and 416 responses.
// ---------------------------------------------------------------------------

/// Spawns a single-endpoint HTTP server. `handler` receives the request index
/// and raw request text, and returns the raw bytes to write before closing.
async fn spawn_raw(
    handler: impl Fn(usize, &str) -> Vec<u8> + Send + Sync + 'static,
) -> (String, Arc<AtomicUsize>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let address = listener.local_addr().expect("address");
    let count = Arc::new(AtomicUsize::new(0));
    let counter = count.clone();
    let handler = Arc::new(handler);

    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else {
                break;
            };
            let counter = counter.clone();
            let handler = handler.clone();

            tokio::spawn(async move {
                let mut buffer = Vec::new();
                let mut chunk = [0_u8; 2048];

                loop {
                    match socket.read(&mut chunk).await {
                        Ok(0) => return,
                        Ok(read) => {
                            buffer.extend_from_slice(&chunk[..read]);
                            if buffer.windows(4).any(|window| window == b"\r\n\r\n") {
                                break;
                            }
                        }
                        Err(_) => return,
                    }
                }

                let request = String::from_utf8_lossy(&buffer).to_string();
                let index = counter.fetch_add(1, Ordering::SeqCst);
                let response = handler(index, &request);

                let _ = socket.write_all(&response).await;
                let _ = socket.shutdown().await;
            });
        }
    });

    (format!("http://{address}/file.bin"), count)
}

fn truncated_response(total: usize, body: &[u8]) -> String {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {total}\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n{}",
        String::from_utf8_lossy(body)
    )
}

fn partial_response(start: usize, total: usize, body: &[u8], etag: &str) -> String {
    format!(
        "HTTP/1.1 206 Partial Content\r\nContent-Type: application/octet-stream\r\nContent-Range: bytes {start}-{}/{total}\r\nContent-Length: {}\r\nETag: {etag}\r\nConnection: close\r\n\r\n{}",
        total - 1,
        body.len(),
        String::from_utf8_lossy(body)
    )
}

fn full_response(body: &[u8], etag: &str) -> String {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\nETag: {etag}\r\nConnection: close\r\n\r\n{}",
        body.len(),
        String::from_utf8_lossy(body)
    )
}

fn range_requests(count: &AtomicUsize) -> usize {
    count.load(Ordering::SeqCst)
}

#[tokio::test]
async fn resumes_after_a_dropped_connection() {
    let payload = b"helloworld";
    let (url, _count) = spawn_raw(move |index, request| {
        if index == 0 {
            assert!(!request.to_ascii_lowercase().contains("range:"));
            truncated_response(payload.len(), b"hello").into_bytes()
        } else {
            assert!(
                request.to_ascii_lowercase().contains("range: bytes=5-"),
                "expected a resume request, got: {request}"
            );
            partial_response(5, payload.len(), b"world", "\"v1\"").into_bytes()
        }
    })
    .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");

    let outcome = engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &url)
                .expect("url")
                .with_sha1(sha1(payload)),
        )
        .await
        .expect("resumed download");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(outcome.bytes, payload.len() as u64);
}

#[tokio::test]
async fn restarts_from_zero_after_a_416_response() {
    let payload = b"helloworld";
    let (url, count) = spawn_raw(move |index, request| match index {
        0 => truncated_response(payload.len(), b"hello").into_bytes(),
        1 => {
            assert!(request.to_ascii_lowercase().contains("range: bytes=5-"));
            format!(
                "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                payload.len()
            )
            .into_bytes()
        }
        _ => {
            // The engine dropped the partial file and starts over.
            assert!(!request.to_ascii_lowercase().contains("range:"));
            full_response(payload, "\"v1\"").into_bytes()
        }
    })
    .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");

    engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &url)
                .expect("url")
                .with_sha1(sha1(payload)),
        )
        .await
        .expect("download after 416");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(range_requests(&count), 3);
}

#[tokio::test]
async fn restarts_when_the_etag_changes_mid_resume() {
    let payload = b"helloworld";
    let (url, count) = spawn_raw(move |index, request| match index {
        0 => truncated_response(payload.len(), b"hello").into_bytes(),
        1 => {
            assert!(request.to_ascii_lowercase().contains("range: bytes=5-"));
            partial_response(5, payload.len(), b"world", "\"v2\"").into_bytes()
        }
        _ => {
            assert!(!request.to_ascii_lowercase().contains("range:"));
            full_response(payload, "\"v1\"").into_bytes()
        }
    })
    .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");

    engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &url)
                .expect("url")
                .with_sha1(sha1(payload)),
        )
        .await
        .expect("download after ETag change");

    assert_eq!(tokio::fs::read(&dest).await.expect("read"), payload);
    assert_eq!(range_requests(&count), 3);
}

#[tokio::test]
async fn abandoned_partial_files_are_not_left_behind() {
    let server = MockServer::start().await;
    let payload = b"complete".to_vec();
    Mock::given(method("GET"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(payload.clone()))
        .mount(&server)
        .await;

    let dir = tempfile::tempdir().expect("temp dir");
    let dest = dir.path().join("file.bin");

    engine(dir.path())
        .download(
            DownloadRequest::from_url(&dest, &format!("{}/file.bin", server.uri()))
                .expect("url")
                .with_sha1(sha1(&payload)),
        )
        .await
        .expect("download");

    let mut entries = tokio::fs::read_dir(dir.path()).await.expect("read dir");
    let mut names = Vec::new();
    while let Some(entry) = entries.next_entry().await.expect("entry") {
        names.push(entry.file_name().to_string_lossy().to_string());
    }
    names.sort();
    assert_eq!(names, vec!["cache", "file.bin"]);
}
