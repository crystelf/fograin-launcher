//! Launch argument construction, natives extraction and process management
//! (§9).
//!
//! [`LaunchPlan::build`] is pure with respect to the network: everything it
//! needs must already be installed. That makes the command preview (`--dry-run`)
//! safe in CI and keeps launch fast. Process spawning and log streaming are in
//! [`GameProcess`].

use std::{
    collections::{BTreeMap, HashMap, HashSet},
    fs,
    io::{Read as _, Write as _},
    path::{Path, PathBuf},
    process::Stdio,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use fg_auth::OfflineAccount;
use fg_core::{Error, ErrorKind, Result};
use fg_java::{JavaArch, JavaRuntime};
use fg_net::Url;
use sha1::{Digest, Sha1};
use tokio::{
    io::{AsyncBufReadExt, BufReader},
    process::Child,
    sync::broadcast,
};
use tracing::warn;
use zip::{ZipArchive, ZipWriter, write::SimpleFileOptions};

use crate::install::{LOG4J_FIX_FILE, LOG4J_FIX_XML, needs_log4j_fix};
use crate::instance::Instance;
use crate::layout::{Layout, write_atomic};
use crate::rules::{Features, RuleContext};
use crate::version::{AssetIndex, LibraryKind, ResolvedLibrary, ResolvedVersion};

/// Default argument preview: `Fograin` appears in `-Dminecraft.launcher.brand`.
pub const LAUNCHER_NAME: &str = "Fograin";
/// Classpath separator for the host.
pub const CLASSPATH_SEPARATOR: &str = if cfg!(target_os = "windows") {
    ";"
} else {
    ":"
};

/// Build options that are not part of `instance.toml`.
#[derive(Debug, Clone)]
pub struct LaunchOptions {
    pub launcher_name: String,
    pub launcher_version: String,
    pub demo: bool,
    /// Extra JVM arguments appended after the instance's own.
    pub extra_jvm_args: Vec<String>,
    /// Overrides the rule context; tests use it to preview other platforms.
    pub rule_context: Option<RuleContext>,
    /// Forces the argfile/pathing-jar path regardless of the host limit.
    pub force_argfile: bool,
    /// Command-line byte limit; defaults to 32767 on Windows and unlimited
    /// elsewhere.
    pub command_length_limit: Option<usize>,
}

impl Default for LaunchOptions {
    fn default() -> Self {
        Self {
            launcher_name: LAUNCHER_NAME.to_string(),
            launcher_version: env!("CARGO_PKG_VERSION").to_string(),
            demo: false,
            extra_jvm_args: Vec::new(),
            rule_context: None,
            force_argfile: false,
            command_length_limit: None,
        }
    }
}

/// A fully resolved command, ready to spawn or preview.
#[derive(Debug, Clone)]
pub struct LaunchPlan {
    pub program: PathBuf,
    pub args: Vec<String>,
    pub cwd: PathBuf,
    pub env: BTreeMap<String, String>,
    pub main_class: String,
    pub classpath: Vec<PathBuf>,
    pub natives_dir: PathBuf,
    pub game_dir: PathBuf,
    pub asset_index: Option<String>,
    pub log_config: Option<PathBuf>,
    pub argfile: Option<PathBuf>,
    pub pathing_jar: Option<PathBuf>,
    pub features: BTreeMap<String, bool>,
}

impl LaunchPlan {
    /// Builds the launch command.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::NotFound`] when the version is not installed,
    /// [`ErrorKind::InvalidInput`] for unsupported Java/command combinations
    /// and I/O errors while extracting natives or writing an argfile.
    pub fn build(
        layout: &Layout,
        instance: &Instance,
        resolved: &ResolvedVersion,
        java: &JavaRuntime,
        account: &OfflineAccount,
        options: &LaunchOptions,
    ) -> Result<Self> {
        let context = options
            .rule_context
            .clone()
            .unwrap_or_else(RuleContext::current);
        let resolved_libraries = resolved.libraries(&context)?;
        if context.os.name == "osx"
            && context.os.arch == JavaArch::Arm64
            && java.arch == JavaArch::Arm64
            && needs_x86_64_java(&resolved.base_id)
            && resolved_libraries
                .iter()
                .any(|library| matches!(library.kind, LibraryKind::Native { .. }))
        {
            return Err(Error::with_detail(
                ErrorKind::Unsupported,
                "error.launch.arm64_natives",
                format!(
                    "{} ships only x86_64 LWJGL natives; use an x86_64 Java 8 under Rosetta or wait for the M6 runtime manager",
                    resolved.base_id
                ),
            ));
        }
        let classpath_libraries = resolved.classpath_libraries(&resolved_libraries);

        let client_jar = layout.version_jar(&resolved.id);
        require_file(&client_jar, "error.launch.client_missing")?;

        let classpath: Vec<PathBuf> = classpath_libraries
            .iter()
            .map(|library| layout.libraries().join(&library.artifact.path))
            .chain(std::iter::once(client_jar.clone()))
            .collect();
        let missing: Vec<&PathBuf> = classpath.iter().filter(|path| !path.is_file()).collect();
        if !missing.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::NotFound,
                "error.launch.incomplete",
                format!(
                    "{} missing file(s), first: {}",
                    missing.len(),
                    missing[0].display()
                ),
            ));
        }
        let classpath_string = join_classpath(&classpath);

        let game_dir = instance.game_dir(layout);
        let natives_dir = layout.natives(&instance.id);
        prepare_natives(
            layout,
            resolved,
            &resolved_libraries,
            context.os.arch,
            &natives_dir,
        )?;

        let asset_index = resolved.asset_index().cloned();
        if let Some(index) = &asset_index {
            prepare_legacy_assets(layout, instance, index)?;
        }

        let features = Features::new()
            .with("has_custom_resolution", instance.resolution().is_some())
            .with("is_demo_user", options.demo)
            .with("has_quick_plays_support", false)
            .with("is_quick_play_singleplayer", false)
            .with("is_quick_play_multiplayer", false)
            .with("is_quick_play_realms", false);
        let argument_context = RuleContext::new(context.os.clone(), features.clone());

        let mut placeholders = Placeholders::new();
        placeholders.insert("auth_player_name", account.name());
        placeholders.insert("auth_uuid", account.uuid_hex());
        placeholders.insert("auth_access_token", account.access_token());
        placeholders.insert("auth_session", account.session());
        placeholders.insert("user_type", "legacy");
        placeholders.insert("user_properties", "{}");
        placeholders.insert("clientid", "");
        placeholders.insert("auth_xuid", "");
        placeholders.insert("version_name", resolved.id.clone());
        placeholders.insert(
            "version_type",
            resolved
                .raw
                .version_type
                .clone()
                .unwrap_or_else(|| "release".to_string()),
        );
        placeholders.insert("game_directory", game_dir.display().to_string());
        placeholders.insert("game_dir", game_dir.display().to_string());
        placeholders.insert("assets_root", layout.assets().display().to_string());
        placeholders.insert(
            "assets_index_name",
            asset_index.as_ref().map_or_else(
                || resolved.raw.assets.clone().unwrap_or_default(),
                |index| index.id.clone(),
            ),
        );
        placeholders.insert(
            "game_assets",
            game_assets_dir(layout, instance, asset_index.as_ref())
                .display()
                .to_string(),
        );
        placeholders.insert("natives_directory", natives_dir.display().to_string());
        placeholders.insert("classpath", classpath_string.clone());
        placeholders.insert("classpath_separator", CLASSPATH_SEPARATOR.to_string());
        placeholders.insert(
            "library_directory",
            layout.libraries().display().to_string(),
        );
        placeholders.insert("launcher_name", options.launcher_name.clone());
        placeholders.insert("launcher_version", options.launcher_version.clone());
        if let Some((width, height)) = instance.resolution() {
            placeholders.insert("resolution_width", width.to_string());
            placeholders.insert("resolution_height", height.to_string());
        }
        placeholders.insert("quickPlayPath", "");
        placeholders.insert("quickPlaySingleplayer", "");
        placeholders.insert("quickPlayMultiplayer", "");
        placeholders.insert("quickPlayRealms", "");

        let mut jvm_args = vec![
            format!("-Xmx{}M", instance.memory_mb()),
            format!("-Xms{}M", instance.memory_mb().min(2048)),
            "-Dfile.encoding=UTF-8".to_string(),
            "-Dstdout.encoding=UTF-8".to_string(),
            "-Dstderr.encoding=UTF-8".to_string(),
            "-Dlog4j2.formatMsgNoLookups=true".to_string(),
            format!("-Dminecraft.launcher.brand={}", options.launcher_name),
            format!("-Dminecraft.launcher.version={}", options.launcher_version),
        ];

        let default_jvm: Vec<String> = resolved
            .default_user_jvm_arguments(&argument_context)
            .into_iter()
            .map(|argument| placeholders.substitute(&argument))
            .collect();
        let version_jvm: Vec<String> = resolved
            .jvm_arguments(&argument_context)
            .into_iter()
            .map(|argument| placeholders.substitute(&argument))
            .collect();

        if !has_flag(&version_jvm, "-Djava.library.path") {
            jvm_args.push(format!("-Djava.library.path={}", natives_dir.display()));
        }

        // The logging config is injected by the launcher, not by the version
        // json. Use the patched config for the Log4Shell window.
        let log4j_fix = needs_log4j_fix(&resolved.base_id);
        let log_config = if log4j_fix {
            let path = layout.log_configs().join(LOG4J_FIX_FILE);
            if !path.is_file() {
                write_atomic(&path, LOG4J_FIX_XML.as_bytes())?;
            }
            Some(path)
        } else {
            resolved
                .raw
                .logging
                .as_ref()
                .and_then(|logging| logging.client.as_ref())
                .map(|client| layout.log_configs().join(&client.file.id))
                .filter(|path| path.is_file())
        };
        if let Some(log_config) = &log_config
            && !has_flag(&version_jvm, "-Dlog4j.configurationFile")
        {
            jvm_args.push(format!(
                "-Dlog4j.configurationFile={}",
                log_config.display()
            ));
        }

        jvm_args.extend(default_jvm);
        jvm_args.extend(instance.jvm_args());
        jvm_args.extend(options.extra_jvm_args.iter().cloned());
        jvm_args.extend(version_jvm);

        if cfg!(target_os = "macos") && !jvm_args.iter().any(|arg| arg == "-XstartOnFirstThread") {
            jvm_args.push("-XstartOnFirstThread".to_string());
        }

        if !has_flag(&jvm_args, "-cp") && !has_flag(&jvm_args, "-classpath") {
            jvm_args.push("-cp".to_string());
            jvm_args.push(classpath_string);
        }

        let mut game_args: Vec<String> = match resolved.legacy_game_arguments() {
            Some(arguments) => arguments,
            None => resolved.game_arguments(&argument_context),
        }
        .into_iter()
        .map(|argument| placeholders.substitute(&argument))
        .collect();
        if game_args.is_empty() {
            return Err(Error::with_detail(
                ErrorKind::Parse,
                "error.launch.no_game_arguments",
                format!("version {}", resolved.id),
            ));
        }
        if instance.overrides.fullscreen.unwrap_or(false)
            && !game_args.iter().any(|argument| argument == "--fullscreen")
        {
            game_args.push("--fullscreen".to_string());
        }

        let main_class = resolved.main_class()?.to_string();
        let mut args = jvm_args;
        args.push(main_class.clone());
        args.extend(game_args);
        args = dedupe_flags(args);

        let cwd = game_dir;
        fs::create_dir_all(&cwd)?;

        let mut plan = Self {
            program: java.executable.clone(),
            args,
            cwd,
            env: BTreeMap::new(),
            main_class,
            classpath,
            natives_dir,
            game_dir: instance.game_dir(layout),
            asset_index: asset_index.map(|index| index.id),
            log_config,
            argfile: None,
            pathing_jar: None,
            features: features.0,
        };

        plan.apply_command_length_limit(
            layout,
            instance,
            java,
            options.force_argfile,
            options.command_length_limit,
        )?;
        Ok(plan)
    }

    /// Human-readable command line for logs and `--dry-run`.
    #[must_use]
    pub fn display(&self) -> String {
        let mut parts = vec![quote_for_display(&self.program.display().to_string())];
        parts.extend(self.args.iter().map(|arg| quote_for_display(arg)));
        parts.join(" ")
    }

    /// Approximate command-line length in bytes, matching the Windows limit.
    #[must_use]
    pub fn command_length(&self) -> usize {
        self.program.display().to_string().len()
            + self
                .args
                .iter()
                .map(|argument| argument.len() + 1)
                .sum::<usize>()
    }

    fn apply_command_length_limit(
        &mut self,
        layout: &Layout,
        instance: &Instance,
        java: &JavaRuntime,
        force: bool,
        configured_limit: Option<usize>,
    ) -> Result<()> {
        let limit = configured_limit.or(if cfg!(target_os = "windows") {
            Some(32767)
        } else {
            None
        });
        if !force && limit.is_none_or(|limit| self.command_length() <= limit) {
            return Ok(());
        }
        let limit = limit.unwrap_or(0);

        // Java 8 has no @argfile. A pathing jar keeps the classpath out of the
        // command line and works on every Java version.
        if java.major().is_none_or(|major| major < 9) {
            let jar = layout
                .instance_dir(&instance.id)
                .join("fograin-classpath.jar");
            write_pathing_jar(&jar, &self.classpath)?;
            let mut replaced = false;
            let mut args = Vec::with_capacity(self.args.len());
            let mut index = 0;
            while index < self.args.len() {
                let argument = &self.args[index];
                if argument == "-cp" || argument == "-classpath" {
                    args.push(argument.clone());
                    if index + 1 < self.args.len() {
                        args.push(jar.display().to_string());
                        index += 2;
                        replaced = true;
                        continue;
                    }
                }
                args.push(argument.clone());
                index += 1;
            }
            if !replaced {
                args.push("-cp".to_string());
                args.push(jar.display().to_string());
            }
            self.args = args;
            self.pathing_jar = Some(jar);
            if limit != 0 && self.command_length() > limit {
                // With the classpath removed this should be impossible for
                // real versions; report it rather than truncating.
                return Err(Error::with_detail(
                    ErrorKind::Unsupported,
                    "error.launch.command_too_long",
                    format!(
                        "{} bytes after pathing jar, limit {limit}",
                        self.command_length()
                    ),
                ));
            }
            return Ok(());
        }

        let argfile = layout.instance_dir(&instance.id).join("launch-args.txt");
        write_argfile(&argfile, &self.args)?;
        self.args = vec![format!("@{}", argfile.display())];
        self.argfile = Some(argfile);
        Ok(())
    }
}

fn join_classpath(paths: &[PathBuf]) -> String {
    paths
        .iter()
        .map(|path| path.display().to_string())
        .collect::<Vec<_>>()
        .join(CLASSPATH_SEPARATOR)
}

fn require_file(path: &Path, message_key: &str) -> Result<()> {
    if path.is_file() {
        Ok(())
    } else {
        Err(Error::with_detail(
            ErrorKind::NotFound,
            message_key,
            path.display().to_string(),
        ))
    }
}

fn has_flag(args: &[String], flag: &str) -> bool {
    args.iter()
        .any(|argument| argument == flag || argument.starts_with(&format!("{flag}=")))
}

/// Removes exact duplicate flags while preserving the first occurrence.
///
/// Only flags with no value are considered; `-p a -p b` must stay intact.
fn dedupe_flags(args: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut output = Vec::with_capacity(args.len());
    let mut index = 0;
    while index < args.len() {
        let argument = &args[index];
        let takes_value = matches!(
            argument.as_str(),
            "-cp"
                | "-classpath"
                | "--username"
                | "--version"
                | "--gameDir"
                | "--assetsDir"
                | "--assetIndex"
                | "--uuid"
                | "--accessToken"
                | "--userType"
                | "--versionType"
                | "--width"
                | "--height"
                | "--demo"
                | "--clientId"
                | "--xuid"
                | "--quickPlayPath"
                | "--quickPlaySingleplayer"
                | "--quickPlayMultiplayer"
                | "--quickPlayRealms"
        );
        if !argument.starts_with('-') || takes_value {
            output.push(argument.clone());
            index += 1;
            continue;
        }
        if seen.insert(argument.clone()) {
            output.push(argument.clone());
        }
        index += 1;
    }
    output
}

fn game_assets_dir(
    layout: &Layout,
    instance: &Instance,
    index: Option<&crate::version::AssetIndexRef>,
) -> PathBuf {
    let Some(index) = index else {
        return layout.assets();
    };
    let path = layout.asset_indexes().join(format!("{}.json", index.id));
    if let Ok(bytes) = fs::read(&path)
        && let Ok(parsed) = AssetIndex::parse(&bytes)
    {
        if parsed.map_to_resources.unwrap_or(false) {
            return instance.game_dir(layout).join("resources");
        }
        if parsed.virtual_assets.unwrap_or(false) {
            return layout.virtual_assets(&index.id);
        }
    }
    layout.assets()
}

fn prepare_natives(
    layout: &Layout,
    resolved: &ResolvedVersion,
    libraries: &[ResolvedLibrary],
    arch: fg_java::JavaArch,
    natives_dir: &Path,
) -> Result<()> {
    let natives: Vec<(&ResolvedLibrary, &str, &crate::version::ExtractRule)> = libraries
        .iter()
        .filter_map(|library| match &library.kind {
            LibraryKind::Native {
                classifier,
                extract,
            } => Some((library, classifier.as_str(), extract)),
            LibraryKind::Classpath => None,
        })
        .collect();

    if natives.is_empty() {
        fs::create_dir_all(natives_dir)?;
        return Ok(());
    }

    let digest = natives_digest(&resolved.id, arch, &natives);
    let marker = natives_dir.join(".fograin-natives");
    if fs::read_to_string(&marker)
        .ok()
        .is_some_and(|value| value.trim() == digest)
    {
        return Ok(());
    }

    if natives_dir.exists() {
        fs::remove_dir_all(natives_dir)?;
    }
    fs::create_dir_all(natives_dir)?;

    for (library, _, extract) in &natives {
        let jar = layout.libraries().join(&library.artifact.path);
        require_file(&jar, "error.launch.natives_missing")?;
        extract_zip(&jar, natives_dir, &extract.exclude)?;
    }
    write_atomic(&marker, digest.as_bytes())?;
    Ok(())
}

fn natives_digest(
    version_id: &str,
    arch: fg_java::JavaArch,
    natives: &[(&ResolvedLibrary, &str, &crate::version::ExtractRule)],
) -> String {
    let mut hasher = Sha1::new();
    hasher.update(version_id.as_bytes());
    hasher.update(arch.as_rule_str().as_bytes());
    for (library, classifier, _) in natives {
        hasher.update(classifier.as_bytes());
        hasher.update(library.artifact.path.as_bytes());
    }
    hasher
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

fn extract_zip(jar: &Path, destination: &Path, exclude: &[String]) -> Result<()> {
    let file = fs::File::open(jar)?;
    let mut archive = ZipArchive::new(file).map_err(|error| {
        Error::with_detail(
            ErrorKind::Parse,
            "error.launch.natives_zip",
            format!("{}: {error}", jar.display()),
        )
    })?;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.launch.natives_zip",
                format!("{}: {error}", jar.display()),
            )
        })?;
        if entry.is_dir() {
            continue;
        }
        let Some(name) = entry.enclosed_name() else {
            continue;
        };
        let name_string = name.to_string_lossy().replace('\\', "/");
        if exclude
            .iter()
            .any(|prefix| name_string.starts_with(prefix.trim_end_matches('*')))
        {
            continue;
        }
        let path = destination.join(&name);
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let mut output = fs::File::create(&path)?;
        std::io::copy(&mut entry, &mut output)?;
    }
    Ok(())
}

/// Materializes `map_to_resources` / `virtual` assets if install did not.
fn prepare_legacy_assets(
    layout: &Layout,
    instance: &Instance,
    index: &crate::version::AssetIndexRef,
) -> Result<()> {
    let path = layout.asset_indexes().join(format!("{}.json", index.id));
    let bytes = match fs::read(&path) {
        Ok(bytes) => bytes,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error.into()),
    };
    let index_file = AssetIndex::parse(&bytes)?;
    let map_to_resources = index_file.map_to_resources.unwrap_or(false);
    let virtual_assets = index_file.virtual_assets.unwrap_or(false);
    if !map_to_resources && !virtual_assets {
        return Ok(());
    }

    let destination = if map_to_resources {
        instance.game_dir(layout).join("resources")
    } else {
        layout.virtual_assets(&index.id)
    };
    for (name, object) in &index_file.objects {
        let source = layout
            .asset_objects()
            .join(&object.hash[..2])
            .join(&object.hash);
        let target = destination.join(name);
        link_or_copy_sync(&source, &target)?;
    }
    Ok(())
}

fn link_or_copy_sync(source: &Path, destination: &Path) -> Result<()> {
    if destination.is_file() {
        let destination_len = fs::metadata(destination)
            .ok()
            .map(|metadata| metadata.len());
        let source_len = fs::metadata(source).ok().map(|metadata| metadata.len());
        if destination_len.is_some() && destination_len == source_len {
            return Ok(());
        }
    }
    if let Some(parent) = destination.parent() {
        fs::create_dir_all(parent)?;
    }
    match fs::hard_link(source, destination) {
        Ok(()) => Ok(()),
        Err(_) => fs::copy(source, destination)
            .map(|_| ())
            .map_err(Error::from),
    }
}

/// Substitution table for `${...}` placeholders.
#[derive(Debug, Clone, Default)]
struct Placeholders(HashMap<String, String>);

impl Placeholders {
    fn new() -> Self {
        Self::default()
    }

    fn insert(&mut self, key: impl Into<String>, value: impl Into<String>) {
        self.0.insert(key.into(), value.into());
    }

    fn substitute(&self, input: &str) -> String {
        substitute(input, &self.0)
    }
}

/// Replaces `${name}` placeholders. Unknown names become empty strings, which
/// is safer than leaking a literal `${...}` into the game command line.
#[must_use]
pub fn substitute(input: &str, values: &HashMap<String, String>) -> String {
    let mut output = String::with_capacity(input.len());
    let mut rest = input;
    while let Some(start) = rest.find("${") {
        output.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        let Some(end) = after.find('}') else {
            output.push_str(&rest[start..]);
            return output;
        };
        let key = &after[..end];
        if let Some(value) = values.get(key) {
            output.push_str(value);
        }
        rest = &after[end + 1..];
    }
    output.push_str(rest);
    output
}

fn quote_for_display(value: &str) -> String {
    if value.is_empty() || value.contains([' ', '\t', '"', '\'']) {
        format!("\"{}\"", value.replace('"', "\\\""))
    } else {
        value.to_string()
    }
}

fn write_argfile(path: &Path, args: &[String]) -> Result<()> {
    let mut text = String::new();
    for argument in args {
        if argument.contains([' ', '\t', '"', '\'', '#']) {
            text.push('"');
            text.push_str(&argument.replace('\\', "\\\\").replace('"', "\\\""));
            text.push('"');
        } else {
            text.push_str(argument);
        }
        text.push('\n');
    }
    write_atomic(path, text.as_bytes())
}

/// Writes a pathing jar: a manifest-only jar whose `Class-Path` lists the real
/// jars. Used for Java 8 when the Windows command line would overflow.
fn write_pathing_jar(path: &Path, classpath: &[PathBuf]) -> Result<()> {
    let mut urls = Vec::with_capacity(classpath.len());
    for entry in classpath {
        let url = Url::from_file_path(entry).map_err(|()| {
            Error::with_detail(
                ErrorKind::InvalidInput,
                "error.launch.classpath_url",
                entry.display().to_string(),
            )
        })?;
        urls.push(url.to_string());
    }
    let manifest = manifest_with_class_path(&urls);
    let bytes = zip_with_manifest(&manifest)?;
    write_atomic(path, &bytes)
}

fn manifest_with_class_path(urls: &[String]) -> String {
    // JAR manifests wrap at 72 bytes; continuation lines start with one space.
    let mut lines = vec![
        "Manifest-Version: 1.0".to_string(),
        "Created-By: Fograin".to_string(),
    ];
    let mut current = "Class-Path:".to_string();
    for url in urls {
        let separator = " ";
        if current.len() + separator.len() + url.len() > 70 && !current.ends_with(':') {
            lines.push(current);
            current = format!(" {url}");
        } else {
            current.push_str(separator);
            current.push_str(url);
        }
    }
    lines.push(current);
    let mut manifest = lines.join("\r\n");
    manifest.push_str("\r\n\r\n");
    manifest
}

fn zip_with_manifest(manifest: &str) -> Result<Vec<u8>> {
    let mut buffer = std::io::Cursor::new(Vec::new());
    {
        let mut zip = ZipWriter::new(&mut buffer);
        let options =
            SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
        zip.start_file("META-INF/MANIFEST.MF", options)
            .map_err(zip_error)?;
        zip.write_all(manifest.as_bytes()).map_err(Error::from)?;
        zip.finish().map_err(zip_error)?;
    }
    Ok(buffer.into_inner())
}

fn zip_error(error: zip::result::ZipError) -> Error {
    Error::with_detail(ErrorKind::Internal, "error.launch.zip", error.to_string())
}

/// One line of game output.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogStream {
    Stdout,
    Stderr,
}

/// Parsed `<log4j:Event>` block (§9 item 8).
///
/// Minecraft 1.7+ writes XML events to stdout. Keeping the fields structured
/// lets the UI filter by level/logger and lets the CLI print a readable line.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Log4jEvent {
    pub logger: String,
    pub timestamp_ms: Option<i64>,
    pub level: String,
    pub thread: String,
    pub message: String,
}

impl Log4jEvent {
    /// Parses one complete `<log4j:Event>...</log4j:Event>` block.
    #[must_use]
    pub fn parse(xml: &str) -> Option<Self> {
        if !xml.contains("<log4j:Event") {
            return None;
        }
        let message = extract_element(xml, "log4j:Message")?;
        Some(Self {
            logger: attribute(xml, "logger").unwrap_or_default().to_string(),
            timestamp_ms: attribute(xml, "timestamp").and_then(|value| value.parse().ok()),
            level: attribute(xml, "level").unwrap_or_default().to_string(),
            thread: attribute(xml, "thread").unwrap_or_default().to_string(),
            message: unescape_xml(&message),
        })
    }

    /// `[13:41:48] [Render thread/INFO]: Setting user: Steve`
    #[must_use]
    pub fn format(&self) -> String {
        format!(
            "[{}] [{}/{}]: {}",
            self.timestamp_ms
                .map_or_else(|| "-".to_string(), format_timestamp),
            if self.thread.is_empty() {
                "?"
            } else {
                &self.thread
            },
            if self.level.is_empty() {
                "INFO"
            } else {
                &self.level
            },
            self.message
        )
    }
}

fn attribute<'a>(xml: &'a str, name: &str) -> Option<&'a str> {
    let needle = format!("{name}=\"");
    let start = xml.find(&needle)? + needle.len();
    let rest = &xml[start..];
    let end = rest.find('"')?;
    Some(&rest[..end])
}

fn extract_element(xml: &str, name: &str) -> Option<String> {
    let open = format!("<{name}>");
    let close = format!("</{name}>");
    let start = xml.find(&open)? + open.len();
    let rest = &xml[start..];
    let end = rest.find(&close)?;
    let value = rest[..end].trim();
    let value = value
        .strip_prefix("<![CDATA[")
        .and_then(|value| value.strip_suffix("]]>"))
        .unwrap_or(value);
    Some(value.to_string())
}

fn unescape_xml(value: &str) -> String {
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

fn format_timestamp(milliseconds: i64) -> String {
    let seconds = milliseconds.div_euclid(1000);
    let Ok(datetime) = time::OffsetDateTime::from_unix_timestamp(seconds) else {
        return "?".to_string();
    };
    let offset = time::UtcOffset::current_local_offset().unwrap_or(time::UtcOffset::UTC);
    let local = datetime.to_offset(offset);
    format!(
        "{:02}:{:02}:{:02}",
        local.hour(),
        local.minute(),
        local.second()
    )
}

/// Events emitted while the game runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GameEvent {
    Started {
        pid: Option<u32>,
    },
    Line {
        stream: LogStream,
        /// Formatted, human-readable line written to the log file.
        text: String,
        /// Structured event when the line came from a log4j XML block.
        log4j: Option<Log4jEvent>,
    },
    /// First line that implies the game window/loop is up.
    WindowReady,
    Exited {
        code: Option<i32>,
        success: bool,
    },
}

/// Result of a finished game process.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GameExit {
    pub code: Option<i32>,
    pub success: bool,
    pub duration: Duration,
    pub log_file: PathBuf,
    pub window_ready: bool,
}

/// Running game process with log streaming.
pub struct GameProcess {
    child: Child,
    pid: Option<u32>,
    events: broadcast::Sender<GameEvent>,
    stdout_task: tokio::task::JoinHandle<()>,
    stderr_task: tokio::task::JoinHandle<()>,
    started: Instant,
    log_file: PathBuf,
    window_ready: Arc<AtomicBool>,
}

impl GameProcess {
    /// Spawns the command from `plan`.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the process cannot be started or the log
    /// file cannot be created.
    pub fn spawn(plan: &LaunchPlan) -> Result<Self> {
        let log_dir = plan.game_dir.join("logs");
        fs::create_dir_all(&log_dir)?;
        let log_file = log_dir.join(format!(
            "fograin-{}.log",
            time::OffsetDateTime::now_utc().unix_timestamp()
        ));
        let file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&log_file)?;
        let file = Arc::new(Mutex::new(file));

        let mut command = tokio::process::Command::new(&plan.program);
        command
            .args(&plan.args)
            .current_dir(&plan.cwd)
            .envs(&plan.env)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(target_os = "windows")]
        {
            // CREATE_NO_WINDOW; the method is inherent on tokio's Command.
            command.creation_flags(0x0800_0000);
        }

        let mut child = command.spawn().map_err(|error| {
            Error::with_detail(
                ErrorKind::Io,
                "error.launch.spawn",
                format!("{}: {error}", plan.program.display()),
            )
        })?;
        let pid = child.id();
        let stdout = child.stdout.take();
        let stderr = child.stderr.take();

        let (events, _) = broadcast::channel(1024);
        let window_ready = Arc::new(AtomicBool::new(false));
        let started = Instant::now();
        let stdout_task = tokio::spawn(read_stream(
            stdout,
            LogStream::Stdout,
            events.clone(),
            file.clone(),
            window_ready.clone(),
        ));
        let stderr_task = tokio::spawn(read_stream(
            stderr,
            LogStream::Stderr,
            events.clone(),
            file,
            window_ready.clone(),
        ));
        let _ = events.send(GameEvent::Started { pid });

        Ok(Self {
            child,
            pid,
            events,
            stdout_task,
            stderr_task,
            started,
            log_file,
            window_ready,
        })
    }

    /// Process id, when the OS reported one.
    #[must_use]
    pub fn pid(&self) -> Option<u32> {
        self.pid
    }

    /// File receiving the merged stdout/stderr stream.
    #[must_use]
    pub fn log_file(&self) -> &Path {
        &self.log_file
    }

    /// Subscribes to log lines and lifecycle events.
    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<GameEvent> {
        self.events.subscribe()
    }

    /// Waits for the game to exit and flushes the log readers.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when waiting fails.
    pub async fn wait(mut self) -> Result<GameExit> {
        let status = self.child.wait().await.map_err(|error| {
            Error::with_detail(ErrorKind::Io, "error.launch.wait", error.to_string())
        })?;
        let _ = self.stdout_task.await;
        let _ = self.stderr_task.await;
        let code = status.code();
        let success = status.success();
        let _ = self.events.send(GameEvent::Exited { code, success });
        Ok(GameExit {
            code,
            success,
            duration: self.started.elapsed(),
            log_file: self.log_file,
            window_ready: self.window_ready.load(Ordering::Relaxed),
        })
    }

    /// Requests termination.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorKind::Io`] when the OS rejects the request.
    pub fn kill(&mut self) -> Result<()> {
        self.child.start_kill().map_err(|error| {
            Error::with_detail(ErrorKind::Io, "error.launch.kill", error.to_string())
        })
    }
}

async fn read_stream(
    stream: Option<impl tokio::io::AsyncRead + Unpin>,
    which: LogStream,
    events: broadcast::Sender<GameEvent>,
    file: Arc<Mutex<fs::File>>,
    window_ready: Arc<AtomicBool>,
) {
    let Some(stream) = stream else {
        return;
    };
    let mut reader = BufReader::new(stream);
    let mut buffer = Vec::new();
    let mut pending = String::new();

    loop {
        buffer.clear();
        match reader.read_until(b'\n', &mut buffer).await {
            Ok(0) | Err(_) => break,
            Ok(_) => {}
        }
        while matches!(buffer.last(), Some(b'\n' | b'\r')) {
            buffer.pop();
        }
        let line = String::from_utf8_lossy(&buffer).to_string();

        if !pending.is_empty() {
            pending.push('\n');
            pending.push_str(&line);
            if pending.contains("</log4j:Event>") {
                emit_log4j(&pending, which, &events, &file, &window_ready);
                pending.clear();
            }
            continue;
        }

        if let Some(start) = line.find("<log4j:Event") {
            let prefix = line[..start].trim();
            if !prefix.is_empty() {
                emit_line(prefix, which, &events, &file, &window_ready, None);
            }
            pending = line[start..].to_string();
            if pending.contains("</log4j:Event>") {
                emit_log4j(&pending, which, &events, &file, &window_ready);
                pending.clear();
            }
            continue;
        }

        emit_line(&line, which, &events, &file, &window_ready, None);
    }

    if !pending.trim().is_empty() {
        emit_line(&pending, which, &events, &file, &window_ready, None);
    }
}

fn emit_log4j(
    xml: &str,
    which: LogStream,
    events: &broadcast::Sender<GameEvent>,
    file: &Arc<Mutex<fs::File>>,
    window_ready: &Arc<AtomicBool>,
) {
    match Log4jEvent::parse(xml) {
        Some(event) => {
            let text = event.format();
            emit_line(&text, which, events, file, window_ready, Some(event));
        }
        None => emit_line(xml, which, events, file, window_ready, None),
    }
}

fn emit_line(
    text: &str,
    which: LogStream,
    events: &broadcast::Sender<GameEvent>,
    file: &Arc<Mutex<fs::File>>,
    window_ready: &Arc<AtomicBool>,
    log4j: Option<Log4jEvent>,
) {
    if let Ok(mut file) = file.lock() {
        let _ = writeln!(file, "{text}");
        let _ = file.flush();
    }
    let _ = events.send(GameEvent::Line {
        stream: which,
        text: text.to_string(),
        log4j,
    });
    if is_window_ready_line(text) && !window_ready.swap(true, Ordering::Relaxed) {
        let _ = events.send(GameEvent::WindowReady);
    }
}

/// Heuristic used by §9 step 9 to hide the launcher after the game window is up.
#[must_use]
pub fn is_window_ready_line(line: &str) -> bool {
    line.contains("Setting user:")
        || line.contains("LWJGL Version")
        || line.contains("Backend library: LWJGL")
}

/// Whether this Minecraft version predates the arm64-capable LWJGL layout.
///
/// 1.18.2 and older use `natives`/`natives-osx` jars that contain only x86_64
/// dylibs; 1.19 moved to separate `natives-macos-arm64` libraries.
#[must_use]
pub fn needs_x86_64_java(version_id: &str) -> bool {
    let Some(rest) = version_id.strip_prefix("1.") else {
        return false;
    };
    let mut parts = rest.split('.').filter_map(|part| part.parse::<u32>().ok());
    let Some(minor) = parts.next() else {
        return false;
    };
    minor < 19
}

/// Checks that all installed files for a launch exist.
///
/// # Errors
///
/// Returns [`ErrorKind::NotFound`] with a list of missing files.
pub fn verify_installed(
    layout: &Layout,
    resolved: &ResolvedVersion,
    context: &RuleContext,
) -> Result<()> {
    require_file(
        &layout.version_jar(&resolved.id),
        "error.launch.client_missing",
    )?;
    let libraries = resolved.libraries(context)?;
    let missing: Vec<String> = libraries
        .iter()
        .map(|library| layout.libraries().join(&library.artifact.path))
        .filter(|path| !path.is_file())
        .map(|path| path.display().to_string())
        .take(5)
        .collect();
    if !missing.is_empty() {
        return Err(Error::with_detail(
            ErrorKind::NotFound,
            "error.launch.incomplete",
            missing.join(", "),
        ));
    }
    Ok(())
}

/// Small helper for the CLI: ensures the fixed log4j config exists.
///
/// # Errors
///
/// Returns I/O errors when the config cannot be written.
pub fn ensure_log4j_fix(layout: &Layout) -> Result<PathBuf> {
    let path = layout.log_configs().join(LOG4J_FIX_FILE);
    if !path.is_file() {
        write_atomic(&path, LOG4J_FIX_XML.as_bytes())?;
    }
    Ok(path)
}

/// Removes a stale argfile/pathing jar after a successful start.
pub fn cleanup_launch_artifacts(plan: &LaunchPlan) {
    for path in [plan.argfile.as_ref(), plan.pathing_jar.as_ref()]
        .into_iter()
        .flatten()
    {
        if let Err(error) = fs::remove_file(path)
            && error.kind() != std::io::ErrorKind::NotFound
        {
            warn!(path = %path.display(), %error, "cannot remove launch artifact");
        }
    }
}

/// Reads a file into memory for tests and diagnostics.
///
/// # Errors
///
/// Returns I/O errors.
pub fn read_file(path: &Path) -> Result<Vec<u8>> {
    let mut bytes = Vec::new();
    fs::File::open(path)?.read_to_end(&mut bytes)?;
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use std::collections::HashMap;

    use fg_core::DownloadSource;
    use fg_java::{JavaArch, JavaSource, JavaVersion};
    use fg_net::{Downloader, MirrorTable, Region};

    use super::*;
    use crate::layout::Layout;
    use crate::rules::OsInfo;

    fn layout() -> (tempfile::TempDir, Layout) {
        let directory = tempfile::tempdir().expect("tempdir");
        let layout = Layout::new(directory.path());
        layout.ensure_base_dirs().expect("dirs");
        (directory, layout)
    }

    fn fake_java_arch(arch: JavaArch) -> JavaRuntime {
        JavaRuntime {
            home: PathBuf::from("/jdk-21"),
            executable: PathBuf::from("/jdk-21/bin/java"),
            version: Some(JavaVersion {
                major: 21,
                full: "21.0.9".into(),
            }),
            arch,
            vendor: None,
            source: JavaSource::ExtraRoot,
        }
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

    fn install_fixture_version(layout: &Layout, instance: &Instance, id: &str, fixture: &str) {
        let version = crate::version::VersionJson::parse(fixture.as_bytes()).expect("parse");
        std::fs::create_dir_all(layout.version_dir(id)).expect("version dir");
        std::fs::write(
            layout.version_json(id),
            serde_json::to_vec(&version).expect("serialize"),
        )
        .expect("write json");
        std::fs::write(layout.version_jar(id), b"jar").expect("jar");

        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local(id)
        .expect("resolve");
        let libraries = resolved.libraries(&context()).expect("libraries");
        for library in libraries {
            let path = layout.libraries().join(&library.artifact.path);
            std::fs::create_dir_all(path.parent().expect("parent")).expect("lib dir");
            if matches!(library.kind, LibraryKind::Native { .. }) {
                write_native_jar(&path);
            } else {
                std::fs::write(&path, b"jar").expect("lib");
            }
        }

        let index_id = version
            .asset_index
            .as_ref()
            .map(|index| index.id.clone())
            .expect("index");
        let index_json = r#"{"objects":{"a":{"hash":"aa00000000000000000000000000000000000000","size":3}},"map_to_resources":true}"#;
        let index_path = layout.asset_indexes().join(format!("{index_id}.json"));
        std::fs::write(&index_path, index_json).expect("index");
        let object = "aa00000000000000000000000000000000000000";
        let path = layout.asset_objects().join(&object[..2]).join(object);
        std::fs::create_dir_all(path.parent().expect("parent")).expect("obj dir");
        std::fs::write(path, b"abc").expect("obj");
        instance.create_layout(layout).expect("instance dirs");
    }

    fn write_native_jar(path: &Path) {
        let file = fs::File::create(path).expect("native jar");
        let mut zip = ZipWriter::new(file);
        let options = SimpleFileOptions::default();
        zip.start_file("libfoo.dylib", options).expect("entry");
        zip.write_all(b"dylib").expect("write");
        zip.start_file("META-INF/MANIFEST.MF", options)
            .expect("entry");
        zip.write_all(b"manifest").expect("write");
        zip.start_file("nested/readme.txt", options).expect("entry");
        zip.write_all(b"readme").expect("write");
        zip.finish().expect("finish");
    }

    fn build_plan(layout: &Layout, instance: &Instance) -> LaunchPlan {
        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local("1.7.10")
        .expect("resolve");
        let account = OfflineAccount::new("Steve").expect("account");
        LaunchPlan::build(
            layout,
            instance,
            &resolved,
            &fake_java_arch(JavaArch::X86_64),
            &account,
            &LaunchOptions {
                rule_context: Some(context()),
                ..LaunchOptions::default()
            },
        )
        .expect("build")
    }

    #[test]
    fn substitutes_placeholders_and_drops_unknown_ones() {
        let values = HashMap::from([
            ("auth_player_name".to_string(), "Steve".to_string()),
            ("auth_uuid".to_string(), "abc".to_string()),
        ]);
        assert_eq!(
            substitute(
                "--username ${auth_player_name} --uuid ${auth_uuid}",
                &values
            ),
            "--username Steve --uuid abc"
        );
        assert_eq!(substitute("${unknown}", &values), "");
        assert_eq!(substitute("no placeholders", &values), "no placeholders");
        assert_eq!(substitute("${unterminated", &values), "${unterminated");
    }

    #[test]
    fn builds_legacy_command_with_classpath_and_natives() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "1.7.10", "1.7.10").expect("instance");
        install_fixture_version(
            &layout,
            &instance,
            "1.7.10",
            include_str!("../tests/fixtures/versions/1.7.10.json"),
        );
        let plan = build_plan(&layout, &instance);

        let joined = plan.args.join(" ");
        assert!(joined.contains("net.minecraft.client.main.Main"));
        assert!(joined.contains("--version 1.7.10"));
        assert!(joined.contains("--username Steve"));
        assert!(joined.contains("--uuid 5627dd98e6be3c21b8a8e92344183641"));
        assert!(joined.contains("--accessToken 0"));
        assert!(joined.contains("-Djava.library.path="));
        assert!(joined.contains("-cp "));
        assert!(
            plan.classpath
                .iter()
                .any(|path| path.ends_with("1.7.10.jar"))
        );
        assert!(
            plan.classpath
                .iter()
                .any(|path| path.to_string_lossy().contains("lwjgl"))
        );
        assert!(plan.classpath.iter().all(|path| path.is_file()));

        let natives_dir = plan.natives_dir.clone();
        assert!(natives_dir.join("libfoo.dylib").is_file());
        assert!(!natives_dir.join("META-INF/MANIFEST.MF").exists());
        assert!(natives_dir.join("nested/readme.txt").is_file());
    }

    #[test]
    fn modern_arguments_and_conditional_resolution() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "1.16.5", "1.16.5").expect("instance");
        let mut instance = instance;
        instance.overrides.resolution = Some([1920, 1080]);
        install_fixture_version(
            &layout,
            &instance,
            "1.16.5",
            include_str!("../tests/fixtures/versions/1.16.5.json"),
        );
        let plan = build_plan_for(&layout, &instance, "1.16.5");
        let joined = plan.args.join(" ");
        assert!(joined.contains("--width 1920"));
        assert!(joined.contains("--height 1080"));
        assert!(
            joined.contains("-XstartOnFirstThread"),
            "{}",
            plan.display()
        );
        assert!(!joined.contains("--demo"));
    }

    fn build_plan_for(layout: &Layout, instance: &Instance, id: &str) -> LaunchPlan {
        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local(id)
        .expect("resolve");
        let account = OfflineAccount::new("Steve").expect("account");
        LaunchPlan::build(
            layout,
            instance,
            &resolved,
            &fake_java_arch(JavaArch::X86_64),
            &account,
            &LaunchOptions {
                rule_context: Some(context()),
                ..LaunchOptions::default()
            },
        )
        .expect("build")
    }

    #[test]
    fn builds_a_launch_plan_for_every_acceptance_fixture() {
        let fixtures: [(&str, &str); 8] = [
            ("1.0", include_str!("../tests/fixtures/versions/1.0.json")),
            (
                "1.7.10",
                include_str!("../tests/fixtures/versions/1.7.10.json"),
            ),
            (
                "1.8.9",
                include_str!("../tests/fixtures/versions/1.8.9.json"),
            ),
            (
                "1.12.2",
                include_str!("../tests/fixtures/versions/1.12.2.json"),
            ),
            (
                "1.16.5",
                include_str!("../tests/fixtures/versions/1.16.5.json"),
            ),
            (
                "1.20.1",
                include_str!("../tests/fixtures/versions/1.20.1.json"),
            ),
            (
                "1.21.4",
                include_str!("../tests/fixtures/versions/1.21.4.json"),
            ),
            (
                "26.4-snapshot-7",
                include_str!("../tests/fixtures/versions/26.4-snapshot-7.json"),
            ),
        ];
        for (id, fixture) in fixtures {
            let (_directory, layout) = layout();
            let instance = Instance::create(&layout, id, id).expect("instance");
            install_fixture_version(&layout, &instance, id, fixture);
            let plan = build_plan_for(&layout, &instance, id);
            let joined = plan.args.join(" ");
            assert!(
                joined.contains("Steve") || joined.contains("5627dd98"),
                "{id}"
            );
            assert!(
                joined.contains("--accessToken 0") || joined.contains("token:0:"),
                "{id}"
            );
            assert!(!plan.main_class.is_empty(), "{id}");
            assert!(
                plan.classpath
                    .iter()
                    .any(|path| path.ends_with(format!("{id}.jar"))),
                "{id}"
            );
        }
    }

    #[test]
    fn argfile_fallback_replaces_the_command_line() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "1.7.10", "1.7.10").expect("instance");
        install_fixture_version(
            &layout,
            &instance,
            "1.7.10",
            include_str!("../tests/fixtures/versions/1.7.10.json"),
        );
        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local("1.7.10")
        .expect("resolve");
        let plan = LaunchPlan::build(
            &layout,
            &instance,
            &resolved,
            &fake_java_arch(JavaArch::X86_64),
            &OfflineAccount::new("Steve").expect("account"),
            &LaunchOptions {
                rule_context: Some(context()),
                force_argfile: true,
                ..LaunchOptions::default()
            },
        )
        .expect("build");
        assert_eq!(plan.args.len(), 1);
        assert!(plan.args[0].starts_with('@'));
        let argfile = plan.argfile.expect("argfile");
        assert!(argfile.is_file());
        let contents = std::fs::read_to_string(argfile).expect("read");
        assert!(contents.contains("net.minecraft.client.main.Main"));
    }

    #[test]
    fn pathing_jar_is_used_for_java_8_when_forced() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "1.7.10", "1.7.10").expect("instance");
        install_fixture_version(
            &layout,
            &instance,
            "1.7.10",
            include_str!("../tests/fixtures/versions/1.7.10.json"),
        );
        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local("1.7.10")
        .expect("resolve");
        let mut java = fake_java_arch(JavaArch::X86_64);
        java.version = Some(JavaVersion {
            major: 8,
            full: "1.8.0_402".into(),
        });
        let plan = LaunchPlan::build(
            &layout,
            &instance,
            &resolved,
            &java,
            &OfflineAccount::new("Steve").expect("account"),
            &LaunchOptions {
                rule_context: Some(context()),
                force_argfile: true,
                ..LaunchOptions::default()
            },
        )
        .expect("build");
        let jar = plan.pathing_jar.expect("pathing jar");
        assert!(jar.is_file());
        let entries = zip_entries(&jar);
        assert_eq!(entries, ["META-INF/MANIFEST.MF"]);
        assert!(
            plan.args
                .iter()
                .any(|arg| arg == &jar.display().to_string())
        );
    }

    fn zip_entries(path: &Path) -> Vec<String> {
        let file = fs::File::open(path).expect("open");
        let mut archive = ZipArchive::new(file).expect("zip");
        (0..archive.len())
            .map(|index| {
                archive
                    .by_index(index)
                    .expect("entry")
                    .name()
                    .expect("entry name")
                    .to_string()
            })
            .collect()
    }

    #[test]
    fn parses_log4j_xml_events() {
        let xml = r#"<log4j:Event logger="flk" timestamp="1793857308348" level="INFO" thread="Render thread">
    <log4j:Message><![CDATA[Setting user: Steve]]></log4j:Message>
  </log4j:Event>"#;
        let event = Log4jEvent::parse(xml).expect("event");
        assert_eq!(event.logger, "flk");
        assert_eq!(event.level, "INFO");
        assert_eq!(event.thread, "Render thread");
        assert_eq!(event.message, "Setting user: Steve");
        let formatted = event.format();
        assert!(formatted.contains("[Render thread/INFO]: Setting user: Steve"));
        assert!(formatted.starts_with('['));
        assert!(Log4jEvent::parse("plain line").is_none());
    }

    #[test]
    fn log4j_xml_entities_are_unescaped() {
        let event = Log4jEvent::parse(
            r#"<log4j:Event logger="x" timestamp="0" level="WARN" thread="main"><log4j:Message>a &amp; b &lt;c&gt;</log4j:Message></log4j:Event>"#,
        )
        .expect("event");
        assert_eq!(event.message, "a & b <c>");
    }

    #[test]
    fn refuses_arm64_java_for_pre_1_19_natives() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "1.8.9", "1.8.9").expect("instance");
        install_fixture_version(
            &layout,
            &instance,
            "1.8.9",
            include_str!("../tests/fixtures/versions/1.8.9.json"),
        );
        let resolved = crate::resolver::VersionResolver::new(
            Arc::new(Downloader::new(layout.root()).expect("downloader")),
            layout.clone(),
            MirrorTable::embedded().clone(),
            DownloadSource::Official,
            Region::RestOfWorld,
        )
        .resolve_local("1.8.9")
        .expect("resolve");
        let error = LaunchPlan::build(
            &layout,
            &instance,
            &resolved,
            &fake_java_arch(JavaArch::Arm64),
            &OfflineAccount::new("Steve").expect("account"),
            &LaunchOptions {
                rule_context: Some(context()),
                ..LaunchOptions::default()
            },
        )
        .expect_err("arm64 cannot load x86_64 LWJGL");
        assert_eq!(error.kind, ErrorKind::Unsupported);
        assert_eq!(error.message_key, "error.launch.arm64_natives");
    }

    #[test]
    fn window_marker_detection_matches_known_lines() {
        assert!(is_window_ready_line(
            "[Render thread/INFO]: Setting user: Steve"
        ));
        assert!(is_window_ready_line("[main/INFO]: LWJGL Version: 3.3.3"));
        assert!(!is_window_ready_line("Stopping!"));
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn process_streams_lines_into_a_log_file() {
        let (_directory, layout) = layout();
        let instance = Instance::create(&layout, "proc", "1.21.4").expect("instance");
        let plan = LaunchPlan {
            program: PathBuf::from("/bin/sh"),
            args: vec![
                "-c".to_string(),
                "echo 'Setting user: Steve'; echo 'oops' >&2".to_string(),
            ],
            cwd: instance.game_dir(&layout),
            env: BTreeMap::new(),
            main_class: String::new(),
            classpath: Vec::new(),
            natives_dir: layout.natives("proc"),
            game_dir: instance.game_dir(&layout),
            asset_index: None,
            log_config: None,
            argfile: None,
            pathing_jar: None,
            features: BTreeMap::new(),
        };
        let process = GameProcess::spawn(&plan).expect("spawn");
        let mut events = process.subscribe();
        let mut seen_window = false;
        let exit = process.wait().await.expect("wait");
        while let Ok(event) = events.try_recv() {
            if event == GameEvent::WindowReady {
                seen_window = true;
            }
        }
        assert!(exit.success);
        assert!(seen_window);
        let log = std::fs::read_to_string(&exit.log_file).expect("log");
        assert!(log.contains("Setting user: Steve"));
        assert!(log.contains("oops"));
        assert!(exit.duration > Duration::ZERO);
    }
}
