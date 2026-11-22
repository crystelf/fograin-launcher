//! Executes a [`LoaderPlan`] after the vanilla files are in place.

use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

use fg_core::{Error, ErrorKind, Result};
use fg_task::TaskCx;
use sha1::{Digest, Sha1};

use crate::plan::{InstallStep, LoaderPlan, OutputCheck, ZipCopy};

/// Runtime inputs for [`execute`].
#[derive(Debug, Clone, Copy)]
pub struct ExecuteOptions<'a> {
    pub root: &'a Path,
    /// Java executable used by Forge processors and the OptiFine patcher.
    pub java: Option<&'a Path>,
}

/// Runs every step, then verifies declared processor outputs.
///
/// # Errors
///
/// Returns [`ErrorKind::Unsupported`] when a Java-requiring step has no runtime,
/// [`ErrorKind::Io`] for archive copies and process failures, and
/// [`ErrorKind::Parse`] for malformed jars/manifests.
pub async fn execute(plan: &LoaderPlan, options: &ExecuteOptions<'_>, cx: &TaskCx) -> Result<()> {
    if plan.schema_version != crate::plan::LOADER_PLAN_SCHEMA_VERSION {
        return Err(Error::with_detail(
            ErrorKind::Unsupported,
            "error.loader.plan_version",
            format!(
                "plan schema {}, expected {}",
                plan.schema_version,
                crate::plan::LOADER_PLAN_SCHEMA_VERSION
            ),
        ));
    }
    cx.set_total(plan.steps.len().max(1) as u64);
    tracing::info!(
        loader = plan.kind.as_str(),
        version = plan.loader_version,
        steps = plan.steps.len(),
        "installing loader"
    );

    for step in &plan.steps {
        cx.check()?;
        match step {
            InstallStep::Extract { archive, entries } => {
                extract_entries(options.root, archive, entries)?;
            }
            InstallStep::ExtractPrefix {
                archive,
                prefix,
                destination,
            } => {
                extract_prefix(options.root, archive, prefix, destination)?;
            }
            InstallStep::RunProcessor {
                label,
                jar,
                classpath,
                args,
                outputs,
            } => {
                if !outputs.is_empty() && outputs_valid(options.root, outputs) {
                    tracing::debug!(label, "processor outputs already match; skipping");
                } else {
                    run_processor(options, cx, label, jar, classpath, args).await?;
                    verify_outputs(options.root, outputs)?;
                }
            }
            InstallStep::PatchOptiFine {
                input,
                installer,
                patcher_class,
                output,
            } => {
                run_patcher(options, cx, input, installer, patcher_class, output).await?;
            }
            InstallStep::CopyFile { source, target } => {
                let source = options.root.join(source);
                let target = options.root.join(target);
                copy_file(&source, &target)?;
            }
        }
        cx.advance(1);
    }
    Ok(())
}

fn extract_entries(root: &Path, archive: &Path, entries: &[ZipCopy]) -> Result<()> {
    let archive_path = root.join(archive);
    let file = File::open(&archive_path)?;
    let mut zip = zip::ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.installer_zip",
            format!("{}: {error}", archive_path.display()),
        )
    })?;
    for copy in entries {
        let mut entry = zip.by_name(&copy.entry).map_err(|error| {
            Error::with_detail(
                ErrorKind::NotFound,
                "error.loader.installer_entry",
                format!("{}: {error}", copy.entry),
            )
        })?;
        if entry.is_dir() {
            continue;
        }
        let target = root.join(&copy.target);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut output = File::create(&target)?;
        std::io::copy(&mut entry, &mut output)?;
    }
    Ok(())
}

fn extract_prefix(root: &Path, archive: &Path, prefix: &str, destination: &Path) -> Result<()> {
    let archive_path = root.join(archive);
    let file = File::open(&archive_path)?;
    let mut zip = zip::ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.installer_zip",
            format!("{}: {error}", archive_path.display()),
        )
    })?;
    let prefix = prefix.trim_start_matches('/');
    for index in 0..zip.len() {
        let mut entry = zip.by_index(index).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.installer_zip",
                format!("{}: {error}", archive_path.display()),
            )
        })?;
        let Some(name) = entry.enclosed_name() else {
            continue;
        };
        let name = name.to_string_lossy().replace('\\', "/");
        let Some(relative) = name.strip_prefix(prefix) else {
            continue;
        };
        if relative.is_empty() || entry.is_dir() {
            continue;
        }
        let target = root.join(destination).join(relative);
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut output = File::create(&target)?;
        std::io::copy(&mut entry, &mut output)?;
    }
    Ok(())
}

async fn run_processor(
    options: &ExecuteOptions<'_>,
    cx: &TaskCx,
    label: &str,
    jar: &Path,
    classpath: &[PathBuf],
    args: &[String],
) -> Result<()> {
    let jar = options.root.join(jar);
    if !jar.is_file() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.loader.processor_jar",
            jar.display().to_string(),
        ));
    }
    let main_class = main_class(&jar)?;
    let mut entries = Vec::with_capacity(classpath.len() + 1);
    for item in classpath {
        let path = options.root.join(item);
        if !path.is_file() {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.loader.processor_classpath",
                path.display().to_string(),
            ));
        }
        entries.push(path);
    }
    entries.push(jar.clone());
    let classpath = std::env::join_paths(entries).map_err(|error| {
        Error::with_detail(
            ErrorKind::InvalidInput,
            "error.loader.processor_classpath",
            error.to_string(),
        )
    })?;

    let mut java_args = vec![
        std::ffi::OsString::from("-cp"),
        classpath,
        std::ffi::OsString::from(main_class),
    ];
    java_args.extend(args.iter().map(std::ffi::OsString::from));
    run_java(options, cx, label, java_args).await
}

async fn run_patcher(
    options: &ExecuteOptions<'_>,
    cx: &TaskCx,
    input: &Path,
    installer: &Path,
    patcher_class: &str,
    output: &Path,
) -> Result<()> {
    let input = options.root.join(input);
    let installer = options.root.join(installer);
    let output = options.root.join(output);
    if !input.is_file() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.loader.client_jar",
            input.display().to_string(),
        ));
    }
    if !installer.is_file() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.loader.installer_missing",
            installer.display().to_string(),
        ));
    }
    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let _ = std::fs::remove_file(&output);

    let java_args = vec![
        std::ffi::OsString::from("-cp"),
        installer.clone().into_os_string(),
        std::ffi::OsString::from(patcher_class),
        input.into_os_string(),
        installer.into_os_string(),
        output.clone().into_os_string(),
    ];
    run_java(options, cx, "optifine patcher", java_args).await?;
    if !output.is_file() {
        return Err(Error::with_detail(
            ErrorKind::Internal,
            "error.loader.patcher_failed",
            format!("patcher did not create {}", output.display()),
        ));
    }
    Ok(())
}

async fn run_java(
    options: &ExecuteOptions<'_>,
    cx: &TaskCx,
    label: &str,
    args: Vec<std::ffi::OsString>,
) -> Result<()> {
    let java = java(options)?;
    let mut command = tokio::process::Command::new(java);
    command
        .args(&args)
        .current_dir(options.root)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::piped());
    #[cfg(target_os = "windows")]
    {
        // Forge/NeoForge/OptiFine processors run from the GUI launcher; hide
        // their console window so an install does not flash a black box.
        command.creation_flags(fg_core::CREATE_NO_WINDOW);
    }
    let mut child = command.spawn().map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.loader.processor_spawn",
            format!("{}: {error}", java.display()),
        )
    })?;

    let stderr = child.stderr.take();
    let stderr_task = tokio::spawn(async move {
        let mut text = String::new();
        if let Some(mut reader) = stderr {
            use tokio::io::AsyncReadExt;
            let _ = reader.read_to_string(&mut text).await;
        }
        text
    });

    let cancel = cx.cancel_handle();
    let status = tokio::select! {
        status = child.wait() => status.map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.loader.processor_failed",
                format!("{label}: {error}"),
            )
        })?,
        () = cancel.cancelled() => {
            let _ = child.start_kill();
            let _ = child.wait().await;
            let _ = stderr_task.await;
            return Err(Error::new(ErrorKind::Cancelled, "error.task.cancelled"));
        }
    };
    let stderr = stderr_task.await.unwrap_or_default();
    if !status.success() {
        return Err(Error::with_detail(
            ErrorKind::Internal,
            "error.loader.processor_failed",
            format!("{label} exited with {status}: {}", tail(&stderr, 1200)),
        ));
    }
    Ok(())
}

fn java<'a>(options: &ExecuteOptions<'a>) -> Result<&'a Path> {
    options
        .java
        .ok_or_else(|| Error::new(ErrorKind::Unsupported, "error.loader.java_required"))
}

fn main_class(jar: &Path) -> Result<String> {
    let file = File::open(jar)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.loader.processor_jar",
            format!("{}: {error}", jar.display()),
        )
    })?;
    let mut manifest = String::new();
    archive
        .by_name("META-INF/MANIFEST.MF")
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.loader.processor_manifest",
                format!("{}: {error}", jar.display()),
            )
        })?
        .read_to_string(&mut manifest)?;
    for line in manifest.lines() {
        if let Some(value) = line.strip_prefix("Main-Class:") {
            let value = value.trim();
            if !value.is_empty() {
                return Ok(value.to_string());
            }
        }
    }
    Err(Error::with_detail(
        ErrorKind::Parse,
        "error.loader.processor_manifest",
        format!("{} has no Main-Class", jar.display()),
    ))
}

fn outputs_valid(root: &Path, outputs: &[OutputCheck]) -> bool {
    outputs.iter().all(|output| {
        root.join(&output.path).is_file()
            && sha1_file(&root.join(&output.path))
                .is_ok_and(|actual| actual.eq_ignore_ascii_case(&output.sha1))
    })
}

fn verify_outputs(root: &Path, outputs: &[OutputCheck]) -> Result<()> {
    for output in outputs {
        let path = root.join(&output.path);
        let actual = sha1_file(&path)?;
        if !actual.eq_ignore_ascii_case(&output.sha1) {
            return Err(Error::with_detail(
                ErrorKind::Internal,
                "error.loader.output_hash",
                format!("{}: expected {}, got {actual}", path.display(), output.sha1),
            ));
        }
    }
    Ok(())
}

fn sha1_file(path: &Path) -> Result<String> {
    let mut file = File::open(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::NotFound,
            "error.loader.output_missing",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut hasher = Sha1::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

fn copy_file(source: &Path, target: &Path) -> Result<()> {
    if source == target {
        return Ok(());
    }
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::copy(source, target)?;
    Ok(())
}

fn tail(value: &str, limit: usize) -> String {
    let trimmed = value.trim();
    if trimmed.len() <= limit {
        return trimmed.to_string();
    }
    let mut start = trimmed.len().saturating_sub(limit);
    while start < trimmed.len() && !trimmed.is_char_boundary(start) {
        start += 1;
    }
    format!("…{}", &trimmed[start..])
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::plan::{LOADER_PLAN_SCHEMA_VERSION, LoaderFile};
    use std::io::Write;

    fn plan_with_steps(steps: Vec<InstallStep>) -> LoaderPlan {
        LoaderPlan {
            schema_version: LOADER_PLAN_SCHEMA_VERSION,
            kind: crate::kind::LoaderKind::Forge,
            loader_version: "1".into(),
            version_id: "v".into(),
            base_id: "1.21.4".into(),
            main_class: "x".into(),
            java_major: 21,
            version_json: "{}".into(),
            files: Vec::<LoaderFile>::new(),
            steps,
        }
    }

    #[tokio::test]
    async fn extracts_entries_from_an_installer() {
        let directory = tempfile::tempdir().expect("tempdir");
        let archive = directory.path().join("installer.jar");
        let file = File::create(&archive).expect("file");
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::SimpleFileOptions::default();
        zip.start_file("data/client.lzma", options).expect("entry");
        zip.write_all(b"payload").expect("write");
        zip.finish().expect("finish");

        let plan = plan_with_steps(vec![InstallStep::Extract {
            archive: PathBuf::from("installer.jar"),
            entries: vec![ZipCopy {
                entry: "data/client.lzma".into(),
                target: PathBuf::from("cache/client.lzma"),
            }],
        }]);
        let mut builder = fg_task::TaskGraph::builder("test");
        let root = directory.path().to_path_buf();
        builder.task("execute", 1, move |cx| async move {
            execute(
                &plan,
                &ExecuteOptions {
                    root: &root,
                    java: None,
                },
                &cx,
            )
            .await
        });
        let report = builder
            .build()
            .run(&fg_task::Control::new(), &fg_task::RunOptions::default())
            .await;
        assert!(report.is_success(), "{:?}", report.failed);
        assert_eq!(
            std::fs::read(directory.path().join("cache/client.lzma")).expect("read"),
            b"payload"
        );
    }

    #[test]
    fn tails_long_output() {
        assert_eq!(tail("abc", 10), "abc");
        assert!(tail(&"x".repeat(40), 10).starts_with('…'));
    }
}
