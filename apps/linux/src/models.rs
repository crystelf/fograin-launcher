//! View models for the M4 data the launcher core feeds the GNOME client.
//!
//! The M1 fixtures are gone: instances, downloads, versions and settings arrive
//! from `fg-ffi`'s service through [`crate::state::AppState`]. Everything here is
//! presentation data and pure helpers, with no GTK dependency, so the grouping,
//! formatting and naming rules stay unit-testable.

use std::collections::HashSet;

use serde::Deserialize;
use serde_json::Value;

/// Mod loader families the launcher knows about.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum LoaderKind {
    Vanilla,
    Optifine,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

impl LoaderKind {
    pub const ALL: [Self; 6] = [
        Self::Vanilla,
        Self::Optifine,
        Self::Fabric,
        Self::Quilt,
        Self::Forge,
        Self::NeoForge,
    ];

    #[must_use]
    pub fn from_wire(value: &str) -> Self {
        match value.to_ascii_lowercase().as_str() {
            "optifine" => Self::Optifine,
            "fabric" => Self::Fabric,
            "quilt" => Self::Quilt,
            "forge" => Self::Forge,
            "neoforge" => Self::NeoForge,
            _ => Self::Vanilla,
        }
    }

    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Vanilla => "原版",
            Self::Optifine => "OptiFine",
            Self::Fabric => "Fabric",
            Self::Quilt => "Quilt",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
        }
    }

    /// Library grouping follows the pen: OptiFine sits with vanilla, Quilt with
    /// Fabric, keeping the four native groups of the design.
    #[must_use]
    pub const fn library_group(self) -> LibraryGroup {
        match self {
            Self::Fabric | Self::Quilt => LibraryGroup::Fabric,
            Self::Forge => LibraryGroup::Forge,
            Self::NeoForge => LibraryGroup::NeoForge,
            Self::Vanilla | Self::Optifine => LibraryGroup::Vanilla,
        }
    }

    #[must_use]
    pub const fn glyph(self) -> &'static str {
        match self {
            Self::Vanilla => "M",
            Self::Optifine => "O",
            Self::Fabric => "F",
            Self::Quilt => "Q",
            Self::Forge => "F",
            Self::NeoForge => "N",
        }
    }

    #[must_use]
    pub const fn chip_class(self) -> &'static str {
        match self {
            Self::Vanilla => "fograin-chip-vanilla",
            Self::Optifine => "fograin-chip-optifine",
            Self::Fabric => "fograin-chip-fabric",
            Self::Quilt => "fograin-chip-quilt",
            Self::Forge => "fograin-chip-forge",
            Self::NeoForge => "fograin-chip-neoforge",
        }
    }
}

/// Instance-library grouping headers, in the order used by the pen.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum LibraryGroup {
    Fabric,
    Forge,
    NeoForge,
    Vanilla,
}

impl LibraryGroup {
    pub const ALL: [Self; 4] = [Self::Fabric, Self::Forge, Self::NeoForge, Self::Vanilla];

    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Fabric => "Fabric",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
            Self::Vanilla => "原版",
        }
    }
}

/// Per-instance icon accent from `design/tokens.json` `content.instancePalette`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Palette {
    Forest,
    Copper,
    Slate,
    Violet,
    Clay,
    Lagoon,
    Sand,
    Moss,
    Ash,
}

impl Palette {
    pub const ALL: [Self; 9] = [
        Self::Forest,
        Self::Copper,
        Self::Slate,
        Self::Violet,
        Self::Clay,
        Self::Lagoon,
        Self::Sand,
        Self::Moss,
        Self::Ash,
    ];

    #[must_use]
    pub const fn class(self) -> &'static str {
        match self {
            Self::Forest => "fograin-tile-forest",
            Self::Copper => "fograin-tile-copper",
            Self::Slate => "fograin-tile-slate",
            Self::Violet => "fograin-tile-violet",
            Self::Clay => "fograin-tile-clay",
            Self::Lagoon => "fograin-tile-lagoon",
            Self::Sand => "fograin-tile-sand",
            Self::Moss => "fograin-tile-moss",
            Self::Ash => "fograin-tile-ash",
        }
    }
}

/// One instance loaded from `instances.list`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Instance {
    pub id: String,
    pub name: String,
    pub minecraft: String,
    pub loader: LoaderKind,
    pub loader_version: String,
    pub favorite: bool,
    pub installed: bool,
    pub mod_count: Option<usize>,
    pub size_bytes: u64,
    pub play_seconds: u64,
    pub last_played_ms: Option<i64>,
}

/// Wire row from `instances.list`, camelCase like every fg-ffi payload.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstanceRow {
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    minecraft: String,
    #[serde(default)]
    loader_kind: String,
    #[serde(default)]
    loader_version: String,
    #[serde(default)]
    favorite: bool,
    #[serde(default)]
    installed: bool,
    #[serde(default)]
    mod_count: Option<usize>,
    #[serde(default)]
    size_bytes: u64,
    #[serde(default)]
    play_seconds: u64,
    #[serde(default)]
    last_played_ms: Option<i64>,
}

impl Instance {
    /// Decodes one `instances.list` row. `None` means the payload was malformed,
    /// which the caller reports instead of silently dropping an instance.
    #[must_use]
    pub fn from_row(value: &Value) -> Option<Self> {
        let row: InstanceRow = serde_json::from_value(value.clone()).ok()?;
        if row.id.is_empty() {
            return None;
        }
        Some(Self {
            name: if row.name.is_empty() {
                row.id.clone()
            } else {
                row.name
            },
            id: row.id,
            minecraft: row.minecraft,
            loader: LoaderKind::from_wire(&row.loader_kind),
            loader_version: row.loader_version,
            favorite: row.favorite,
            installed: row.installed,
            mod_count: row.mod_count,
            size_bytes: row.size_bytes,
            play_seconds: row.play_seconds,
            last_played_ms: row.last_played_ms,
        })
    }

    /// Decodes the whole `instances.list` array.
    #[must_use]
    pub fn list_from_value(value: &Value) -> Vec<Self> {
        value
            .as_array()
            .map(|rows| rows.iter().filter_map(Self::from_row).collect())
            .unwrap_or_default()
    }

    #[must_use]
    pub fn monogram(&self) -> String {
        let Some(first) = self.name.trim().chars().next() else {
            return "?".to_owned();
        };
        // Latin names read better uppercased; CJK names keep their glyph. Version
        // numbers ("26.3") fall back to the loader glyph so the tile is not a
        // lonely digit.
        if first.is_ascii_alphabetic() {
            return first.to_ascii_uppercase().to_string();
        }
        if !first.is_ascii_digit() {
            return first.to_string();
        }
        self.loader.glyph().to_owned()
    }

    /// Stable palette derived from the id, exactly like the macOS client.
    #[must_use]
    pub fn palette(&self) -> Palette {
        Palette::ALL[palette_index(&self.id)]
    }

    /// `1.21.4 · Fabric 0.16.9`, or just the Minecraft version for vanilla.
    #[must_use]
    pub fn display_version(&self) -> String {
        if self.loader_version.is_empty() {
            self.minecraft.clone()
        } else {
            format!(
                "{} · {} {}",
                self.minecraft,
                self.loader.title(),
                self.loader_version
            )
        }
    }

    /// `1.21.4 · Fabric · 42 模组`, used by home rows.
    #[must_use]
    pub fn home_subtitle(&self) -> String {
        let mut parts = vec![self.minecraft.clone(), self.loader.title().to_owned()];
        if let Some(count) = self.mod_count
            && count > 0
        {
            parts.push(format!("{count} 模组"));
        }
        parts.join(" · ")
    }

    /// `1.21.4 · 42 模组 · 3.1 GB`, used by library rows.
    #[must_use]
    pub fn library_subtitle(&self) -> String {
        let mut parts = vec![self.minecraft.clone()];
        if let Some(count) = self.mod_count
            && count > 0
        {
            parts.push(format!("{count} 模组"));
        }
        parts.push(format_size(self.size_bytes));
        parts.join(" · ")
    }

    /// `Java 版 1.21.4 · Fabric 0.16.9 · 42 个模组`.
    #[must_use]
    pub fn banner_meta(&self) -> String {
        let loader = if self.loader_version.is_empty() {
            self.loader.title().to_owned()
        } else {
            format!("{} {}", self.loader.title(), self.loader_version)
        };
        match self.mod_count {
            Some(count) if count > 0 => {
                format!("Java 版 {} · {loader} · {count} 个模组", self.minecraft)
            }
            _ => format!("Java 版 {} · {loader}", self.minecraft),
        }
    }

    /// `Fabric 0.16.9 · Java 版 1.21.4`.
    #[must_use]
    pub fn detail_meta(&self) -> String {
        if self.loader_version.is_empty() {
            format!("Java 版 {}", self.minecraft)
        } else {
            format!(
                "{} {} · Java 版 {}",
                self.loader.title(),
                self.loader_version,
                self.minecraft
            )
        }
    }

    #[must_use]
    pub fn loader_chip(&self) -> String {
        if self.loader_version.is_empty() {
            self.loader.title().to_owned()
        } else {
            format!("{} {}", self.loader.title(), self.loader_version)
        }
    }

    #[must_use]
    pub fn mods_label(&self) -> String {
        match self.mod_count {
            Some(count) => format!("{count} 个已启用"),
            None => "未安装".to_owned(),
        }
    }

    #[must_use]
    pub fn mods_text(&self) -> String {
        self.mod_count
            .map_or_else(|| "—".to_owned(), |count| count.to_string())
    }

    #[must_use]
    pub fn last_played_label(&self) -> String {
        relative_date(self.last_played_ms)
    }

    #[must_use]
    pub fn play_time_label(&self) -> String {
        format_play_time(self.play_minutes())
    }

    #[must_use]
    pub fn play_minutes(&self) -> u64 {
        self.play_seconds / 60
    }

    /// Case-insensitive match over the visible list metadata.
    #[must_use]
    pub fn matches_query(&self, query: &str) -> bool {
        let needle = query.trim().to_lowercase();
        if needle.is_empty() {
            return true;
        }
        let haystack = format!(
            "{} {} {} {} {} {}",
            self.name,
            self.minecraft,
            self.loader.title(),
            self.loader_version,
            self.mods_label(),
            format_size(self.size_bytes)
        );
        haystack.to_lowercase().contains(&needle)
    }
}

fn palette_index(id: &str) -> usize {
    let mut hash: i32 = 5381;
    for scalar in id.chars() {
        hash = hash.wrapping_mul(33).wrapping_add(scalar as i32);
    }
    usize::try_from(hash.unsigned_abs()).unwrap_or(0) % Palette::ALL.len()
}

/// Library sort options; the list is always grouped by loader first.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SortOrder {
    Recent,
    Name,
    Size,
}

impl SortOrder {
    pub const ALL: [Self; 3] = [Self::Recent, Self::Name, Self::Size];

    #[must_use]
    pub const fn id(self) -> &'static str {
        match self {
            Self::Recent => "recent",
            Self::Name => "name",
            Self::Size => "size",
        }
    }

    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Recent => "最近游玩",
            Self::Name => "名称",
            Self::Size => "占用空间",
        }
    }

    #[must_use]
    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|order| order.id() == id)
    }
}

/// Sorts one group by the selected order.
pub fn sort_instances(items: &mut [Instance], order: SortOrder) {
    match order {
        SortOrder::Recent => items.sort_by(|left, right| {
            right
                .last_played_ms
                .unwrap_or(i64::MIN)
                .cmp(&left.last_played_ms.unwrap_or(i64::MIN))
                .then_with(|| left.name.cmp(&right.name))
        }),
        SortOrder::Name => items.sort_by(|left, right| left.name.cmp(&right.name)),
        SortOrder::Size => items.sort_by_key(|instance| std::cmp::Reverse(instance.size_bytes)),
    }
}

/// Loader-grouped sections after filtering and sorting.
#[must_use]
pub fn grouped_instances(
    instances: &[Instance],
    query: &str,
    order: SortOrder,
) -> Vec<(LibraryGroup, Vec<Instance>)> {
    LibraryGroup::ALL
        .into_iter()
        .filter_map(|group| {
            let mut items: Vec<Instance> = instances
                .iter()
                .filter(|instance| instance.loader.library_group() == group)
                .filter(|instance| instance.matches_query(query))
                .cloned()
                .collect();
            sort_instances(&mut items, order);
            (!items.is_empty()).then_some((group, items))
        })
        .collect()
}

/// One running download shown by the home queue and the downloads page.
#[derive(Clone, Debug, PartialEq)]
pub struct DownloadRow {
    pub id: String,
    pub title: String,
    pub fraction: f64,
    pub speed: f64,
    pub state: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct TaskRow {
    id: String,
    #[serde(default)]
    name: String,
    #[serde(default)]
    state: String,
    #[serde(default)]
    fraction: f64,
    #[serde(default)]
    speed: f64,
}

impl DownloadRow {
    #[must_use]
    pub fn list_from_value(value: &Value) -> Vec<Self> {
        let Some(rows) = value.as_array() else {
            return Vec::new();
        };
        rows.iter()
            .filter_map(|row| serde_json::from_value::<TaskRow>(row.clone()).ok())
            .map(|row| Self {
                id: row.id,
                title: task_title(&row.name),
                fraction: row.fraction.clamp(0.0, 1.0),
                speed: row.speed.max(0.0),
                state: row.state,
            })
            .collect()
    }

    #[must_use]
    pub fn is_active(&self) -> bool {
        self.state == "running" || self.state == "paused"
    }

    /// Applies one progress tick. Terminal states are preserved: a final tick
    /// can arrive after `task.finished`, and resetting the row to "running"
    /// would keep the sidebar badge and queue alive forever.
    pub fn update_progress(&mut self, fraction: f64, speed: f64) {
        self.fraction = fraction.clamp(0.0, 1.0);
        self.speed = speed.max(0.0);
    }

    #[must_use]
    pub fn speed_text(&self) -> String {
        format_speed(self.speed)
    }

    #[must_use]
    pub fn state_label(&self) -> String {
        match self.state.as_str() {
            "succeeded" => "已完成".to_owned(),
            "failed" => "失败".to_owned(),
            "paused" => "已暂停".to_owned(),
            "cancelled" => "已取消".to_owned(),
            _ => format!("{}%", (self.fraction * 100.0).round() as i64),
        }
    }
}

#[must_use]
pub fn task_title(name: &str) -> String {
    match name.strip_prefix("install:") {
        Some(version) => format!("安装 {version}"),
        None => name.to_owned(),
    }
}

/// `app.snapshot` aggregate.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AppSnapshot {
    pub platform: String,
    pub app_version: String,
    pub data_root: String,
    pub java_count: usize,
    pub active_tasks: usize,
    pub disk_free_bytes: u64,
    pub total_memory_bytes: u64,
    pub instance_count: usize,
}

impl AppSnapshot {
    #[must_use]
    pub fn from_value(value: &Value) -> Option<Self> {
        serde_json::from_value(value.clone()).ok()
    }
}

/// `[game]` as the core serializes it.
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct GameConfigView {
    pub username: String,
    pub memory_mb: u32,
    pub java: Option<String>,
    pub jvm_args: String,
    pub resolution: Option<[u32; 2]>,
    pub fullscreen: bool,
    pub show_log: bool,
    pub auto_complete_resources: bool,
}

impl Default for GameConfigView {
    fn default() -> Self {
        Self {
            username: "Steve".to_owned(),
            memory_mb: 0,
            java: None,
            jvm_args: String::new(),
            resolution: None,
            fullscreen: false,
            show_log: false,
            auto_complete_resources: true,
        }
    }
}

impl GameConfigView {
    #[must_use]
    pub fn from_value(value: &Value) -> Option<Self> {
        serde_json::from_value(value.clone()).ok()
    }
}

/// Live game session state, driven by the `game.*` events.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SessionState {
    pub running: bool,
    pub loading: bool,
    pub pid: Option<u32>,
    pub instance_id: Option<String>,
}

/// One entry of `versions.list`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VersionEntry {
    pub id: String,
    pub kind: String,
    pub released: Option<String>,
}

impl VersionEntry {
    #[must_use]
    pub fn list_from_value(value: &Value) -> Vec<Self> {
        let Some(rows) = value.get("versions").and_then(Value::as_array) else {
            return Vec::new();
        };
        rows.iter()
            .filter_map(|row| {
                let object = row.as_object()?;
                Some(Self {
                    id: object.get("id")?.as_str()?.to_owned(),
                    kind: object
                        .get("kind")
                        .and_then(Value::as_str)
                        .unwrap_or("unknown")
                        .to_owned(),
                    released: object
                        .get("released")
                        .and_then(Value::as_str)
                        .map(str::to_owned),
                })
            })
            .collect()
    }

    #[must_use]
    pub fn released_date(&self) -> Option<String> {
        self.released
            .as_deref()
            .filter(|released| released.len() >= 10)
            .map(|released| released[..10].to_owned())
    }

    #[must_use]
    pub fn kind_label(&self) -> String {
        match self.kind.as_str() {
            "release" => "正式版".to_owned(),
            "snapshot" => "快照".to_owned(),
            other => other.to_owned(),
        }
    }
}

/// Download preview from `version.plan`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlanPreview {
    pub version_id: String,
    pub base_id: String,
    pub java_major: u32,
    pub files: usize,
    pub total_bytes: u64,
}

impl PlanPreview {
    #[must_use]
    pub fn from_value(value: &Value) -> Option<Self> {
        let object = value.as_object()?;
        Some(Self {
            version_id: object.get("versionId")?.as_str()?.to_owned(),
            base_id: object
                .get("baseId")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_owned(),
            java_major: object
                .get("javaMajor")
                .and_then(Value::as_u64)
                .unwrap_or(21) as u32,
            files: object.get("files").and_then(Value::as_u64).unwrap_or(0) as usize,
            total_bytes: object
                .get("totalBytes")
                .and_then(Value::as_u64)
                .unwrap_or(0),
        })
    }
}

/// Version families shown by the downloads titlebar dropdown.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DownloadChannel {
    Release,
    Snapshot,
    AprilFools,
    Old,
}

impl DownloadChannel {
    pub const ALL: [Self; 4] = [Self::Release, Self::Snapshot, Self::AprilFools, Self::Old];

    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Release => "正式版",
            Self::Snapshot => "快照",
            Self::AprilFools => "愚人节",
            Self::Old => "远古版本",
        }
    }

    /// `kind` argument understood by the Rust `versions.list` RPC.
    #[must_use]
    pub const fn kind_value(self) -> &'static str {
        match self {
            Self::Release => "release",
            Self::Snapshot => "snapshot",
            Self::AprilFools => "april_fools",
            Self::Old => "old",
        }
    }

    #[must_use]
    pub const fn empty_message(self) -> &'static str {
        match self {
            Self::Release => "暂无正式版",
            Self::Snapshot => "暂无快照",
            Self::AprilFools => "暂无愚人节版本",
            Self::Old => "暂无远古版本",
        }
    }
}

/// Top-level categories of the download page. Only 游戏 is wired in M4.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DownloadsCategory {
    Game,
    Mod,
    Modpack,
    Resourcepack,
    Shaderpack,
    Datapack,
}

impl DownloadsCategory {
    pub const ALL: [Self; 6] = [
        Self::Game,
        Self::Mod,
        Self::Modpack,
        Self::Resourcepack,
        Self::Shaderpack,
        Self::Datapack,
    ];

    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Game => "游戏版本",
            Self::Mod => "模组",
            Self::Modpack => "整合包",
            Self::Resourcepack => "资源包",
            Self::Shaderpack => "光影包",
            Self::Datapack => "数据包",
        }
    }

    #[must_use]
    pub const fn short_title(self) -> &'static str {
        match self {
            Self::Game => "游戏",
            Self::Mod => "模组",
            Self::Modpack => "整合包",
            Self::Resourcepack => "资源包",
            Self::Shaderpack => "光影",
            Self::Datapack => "数据包",
        }
    }

    #[must_use]
    pub const fn icon(self) -> &'static str {
        match self {
            Self::Game => "applications-games-symbolic",
            Self::Mod => "application-x-addon-symbolic",
            Self::Modpack => "package-x-generic-symbolic",
            Self::Resourcepack => "applications-graphics-symbolic",
            Self::Shaderpack => "weather-clear-symbolic",
            Self::Datapack => "text-x-generic-symbolic",
        }
    }

    #[must_use]
    pub const fn milestone(self) -> Option<&'static str> {
        match self {
            Self::Game => None,
            Self::Mod | Self::Resourcepack | Self::Shaderpack | Self::Datapack => Some("M8"),
            Self::Modpack => Some("M9"),
        }
    }

    #[must_use]
    pub const fn description(self) -> &'static str {
        match self {
            Self::Game => "选择 Minecraft 版本并安装",
            Self::Mod => "在 Modrinth 与 CurseForge 中搜索模组，解析依赖后安装到实例。",
            Self::Modpack => "导入与更新 Modrinth、CurseForge、MultiMC 与 HMCL 整合包。",
            Self::Resourcepack => "浏览资源包并按实例的 MC 版本过滤兼容性。",
            Self::Shaderpack => "为已安装光影加载器的实例选择光影包。",
            Self::Datapack => "把数据包放进存档或世界的 datapacks 目录。",
        }
    }
}

/// Loader families offered by the install dialog. Only 原版 is installable in
/// M4; the rest are reserved slots until `fg-loader` lands in M5.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GameLoaderKind {
    Vanilla,
    Fabric,
    FabricApi,
    Quilt,
    Forge,
    NeoForge,
    Optifine,
    SqlApi,
}

impl GameLoaderKind {
    pub const ALL: [Self; 8] = [
        Self::Vanilla,
        Self::Fabric,
        Self::FabricApi,
        Self::Quilt,
        Self::Forge,
        Self::NeoForge,
        Self::Optifine,
        Self::SqlApi,
    ];

    #[must_use]
    pub const fn title(self) -> &'static str {
        match self {
            Self::Vanilla => "原版",
            Self::Fabric => "Fabric",
            Self::FabricApi => "Fabric API",
            Self::Quilt => "Quilt",
            Self::Forge => "Forge",
            Self::NeoForge => "NeoForge",
            Self::Optifine => "OptiFine",
            Self::SqlApi => "SQL API",
        }
    }

    #[must_use]
    pub const fn subtitle(self) -> &'static str {
        match self {
            Self::Vanilla => "纯净游戏，不需要额外加载器",
            Self::Fabric => "轻量、更新快的模组加载器",
            Self::FabricApi => "多数 Fabric 模组的前置",
            Self::Quilt => "Fabric 的社区分支",
            Self::Forge => "老牌模组加载器",
            Self::NeoForge => "Forge 的现代分支",
            Self::Optifine => "光影与性能优化",
            Self::SqlApi => "预留的扩展接口",
        }
    }

    #[must_use]
    pub const fn icon(self) -> &'static str {
        match self {
            Self::Vanilla => "applications-games-symbolic",
            Self::Fabric | Self::FabricApi => "application-x-addon-symbolic",
            Self::Quilt => "view-grid-symbolic",
            Self::Forge => "emblem-system-symbolic",
            Self::NeoForge => "system-run-symbolic",
            Self::Optifine => "weather-clear-symbolic",
            Self::SqlApi => "drive-harddisk-symbolic",
        }
    }

    #[must_use]
    pub const fn milestone(self) -> Option<&'static str> {
        match self {
            Self::Vanilla => None,
            _ => Some("M5"),
        }
    }

    #[must_use]
    pub const fn is_available(self) -> bool {
        matches!(self, Self::Vanilla)
    }

    /// Placeholder versions until `fg-loader` lands. Keeping them visible makes
    /// the eventual version picker a drop-in replacement.
    #[must_use]
    pub const fn placeholder_versions(self) -> &'static [&'static str] {
        match self {
            Self::Vanilla => &[],
            Self::Fabric => &["0.16.9（最新）", "0.16.10", "0.15.11"],
            Self::FabricApi => &["0.116.0（最新）", "0.115.6"],
            Self::Quilt => &["0.27.0（最新）", "0.26.11"],
            Self::Forge => &["47.2.0（最新）", "47.3.0"],
            Self::NeoForge => &["21.1.72（最新）", "21.1.80"],
            Self::Optifine => &["HD U M5（最新）", "HD U I7"],
            Self::SqlApi => &["预留"],
        }
    }
}

/// Turns a display name into a filesystem-safe instance id (empty when the name
/// carries no usable characters, e.g. pure CJK), matching the macOS helper.
#[must_use]
pub fn sanitized_instance_id(raw: &str) -> String {
    let filtered: String = raw
        .chars()
        .filter(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '-' | '_' | '.')
        })
        .collect();
    filtered.trim_matches('.').to_owned()
}

/// Safe directory id for a display name. Collisions get a numeric suffix so the
/// download page never fails because an instance already exists.
#[must_use]
pub fn unique_instance_id(name: &str, version: &str, taken: &[Instance]) -> String {
    let sanitized = sanitized_instance_id(name);
    let base = if sanitized.is_empty() {
        let version_id = sanitized_instance_id(version);
        if version_id.is_empty() {
            "instance".to_owned()
        } else {
            version_id
        }
    } else {
        sanitized
    };

    let existing: HashSet<&str> = taken.iter().map(|instance| instance.id.as_str()).collect();
    if !existing.contains(base.as_str()) {
        return base;
    }
    let mut suffix = 2;
    loop {
        let candidate = format!("{base}-{suffix}");
        if !existing.contains(candidate.as_str()) {
            return candidate;
        }
        suffix += 1;
    }
}

/// Decimal units, like the pen (`3.1 GB`, `410 MB`).
#[must_use]
pub fn format_size(bytes: u64) -> String {
    const GB: f64 = 1_000_000_000.0;
    const MB: f64 = 1_000_000.0;

    if bytes == 0 {
        return "0 MB".to_owned();
    }
    if bytes as f64 >= GB {
        format!("{:.1} GB", bytes as f64 / GB)
    } else {
        format!("{:.0} MB", (bytes as f64 / MB).max(1.0))
    }
}

#[must_use]
pub fn format_speed(bytes_per_second: f64) -> String {
    if bytes_per_second <= 0.0 {
        return "—".to_owned();
    }
    let megabytes = bytes_per_second / 1_000_000.0;
    if megabytes >= 1_000.0 {
        format!("{:.1} GB/s", megabytes / 1_000.0)
    } else {
        format!("{megabytes:.1} MB/s")
    }
}

#[must_use]
pub fn total_size(instances: &[Instance]) -> u64 {
    instances.iter().map(|instance| instance.size_bytes).sum()
}

#[must_use]
pub fn format_play_time(minutes: u64) -> String {
    if minutes == 0 {
        return "—".to_owned();
    }
    if minutes < 60 {
        return format!("{minutes} 分钟");
    }
    let hours = minutes / 60;
    let remainder = minutes % 60;
    if remainder == 0 {
        format!("{hours} 小时")
    } else {
        format!("{hours} 小时 {remainder} 分钟")
    }
}

/// Chinese relative time, mirroring the macOS `RelativeDateTimeFormatter`
/// output used by the home and library rows.
#[must_use]
pub fn relative_date(epoch_ms: Option<i64>) -> String {
    let Some(epoch_ms) = epoch_ms else {
        return "从未游玩".to_owned();
    };
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0);
    let seconds = (now_ms - epoch_ms).max(0) / 1000;

    match seconds {
        0..60 => "刚刚".to_owned(),
        60..3_600 => format!("{} 分钟前", seconds / 60),
        3_600..86_400 => format!("{} 小时前", seconds / 3_600),
        86_400..172_800 => "昨天".to_owned(),
        172_800..604_800 => format!("{} 天前", seconds / 86_400),
        604_800..2_592_000 => format!("{} 周前", seconds / 604_800),
        2_592_000..31_536_000 => format!("{} 个月前", seconds / 2_592_000),
        _ => format!("{} 年前", seconds / 31_536_000),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn instance(id: &str, name: &str, loader: LoaderKind, minecraft: &str) -> Instance {
        Instance {
            id: id.to_owned(),
            name: name.to_owned(),
            minecraft: minecraft.to_owned(),
            loader,
            loader_version: String::new(),
            favorite: false,
            installed: true,
            mod_count: None,
            size_bytes: 1_000_000,
            play_seconds: 0,
            last_played_ms: None,
        }
    }

    #[test]
    fn sizes_use_decimal_units_and_skip_sub_megabyte_rounding() {
        assert_eq!(format_size(3_100_000_000), "3.1 GB");
        assert_eq!(format_size(410_000_000), "410 MB");
        assert_eq!(format_size(1_000_000), "1 MB");
        assert_eq!(format_size(0), "0 MB");
        assert_eq!(format_speed(18_400_000.0), "18.4 MB/s");
        assert_eq!(format_speed(0.0), "—");
    }

    #[test]
    fn play_time_formats_hours_and_minutes() {
        assert_eq!(format_play_time(0), "—");
        assert_eq!(format_play_time(45), "45 分钟");
        assert_eq!(format_play_time(7_560), "126 小时");
        assert_eq!(format_play_time(130), "2 小时 10 分钟");
    }

    #[test]
    fn decodes_the_instance_wire_row() {
        let row = json!({
            "id": "26.3",
            "name": "26.3",
            "minecraft": "26.3",
            "loaderKind": "vanilla",
            "loaderVersion": "",
            "favorite": true,
            "installed": true,
            "modCount": null,
            "sizeBytes": 410000000,
            "playSeconds": 44,
            "lastPlayedMs": 1762145767193_i64,
        });
        let instance = Instance::from_row(&row).expect("valid row");
        assert_eq!(instance.id, "26.3");
        assert_eq!(instance.loader, LoaderKind::Vanilla);
        assert!(instance.favorite);
        assert_eq!(instance.play_time_label(), "—");
        assert_eq!(instance.home_subtitle(), "26.3 · 原版");
        assert_eq!(instance.banner_meta(), "Java 版 26.3 · 原版");
    }

    #[test]
    fn decodes_task_version_and_plan_payloads() {
        let tasks = json!([{
            "id": "task-1",
            "name": "install:1.21.4",
            "state": "running",
            "fraction": 0.25,
            "speed": 2048.0
        }]);
        let rows = DownloadRow::list_from_value(&tasks);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].title, "安装 1.21.4");
        assert_eq!(rows[0].state_label(), "25%");
        assert!(rows[0].is_active());

        let mut finished = rows[0].clone();
        finished.state = "succeeded".to_owned();
        finished.update_progress(1.0, 0.0);
        assert_eq!(finished.state, "succeeded");
        assert!(!finished.is_active());

        let versions = json!({"versions": [{"id": "1.21.4", "kind": "release", "released": "2024-12-03T10:00:00Z"}]});
        let entries = VersionEntry::list_from_value(&versions);
        assert_eq!(entries[0].released_date().as_deref(), Some("2024-12-03"));
        assert_eq!(entries[0].kind_label(), "正式版");

        let plan = PlanPreview::from_value(&json!({
            "versionId": "26.3",
            "baseId": "26.3",
            "javaMajor": 21,
            "files": 120,
            "totalBytes": 420000000
        }))
        .expect("valid plan");
        assert_eq!(plan.files, 120);
        assert_eq!(plan.java_major, 21);
    }

    #[test]
    fn grouping_keeps_the_four_loader_sections_and_sorts() {
        let mut a = instance("a", "B 实例", LoaderKind::Fabric, "1.21.4");
        a.last_played_ms = Some(2);
        let mut b = instance("b", "A 实例", LoaderKind::Quilt, "1.21.1");
        b.last_played_ms = Some(1);
        let c = instance("c", "原版", LoaderKind::Vanilla, "1.21.4");
        let instances = vec![a, b, c];

        let groups = grouped_instances(&instances, "", SortOrder::Recent);
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].0, LibraryGroup::Fabric);
        assert_eq!(groups[0].1.len(), 2);
        assert_eq!(groups[0].1[0].id, "a");
        assert_eq!(groups[1].0, LibraryGroup::Vanilla);

        let groups = grouped_instances(&instances, "原版", SortOrder::Name);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].1[0].id, "c");
    }

    #[test]
    fn instance_ids_are_sanitized_and_collisions_get_a_suffix() {
        assert_eq!(sanitized_instance_id("雾林生存"), "");
        assert_eq!(sanitized_instance_id("my instance/1"), "myinstance1");
        assert_eq!(sanitized_instance_id("...hidden..."), "hidden");

        let taken = vec![instance("26.3", "26.3", LoaderKind::Vanilla, "26.3")];
        assert_eq!(unique_instance_id("雾林生存", "26.3", &taken), "26.3-2");
        assert_eq!(unique_instance_id("My Pack", "26.3", &taken), "MyPack");
        assert_eq!(unique_instance_id("", "", &[]), "instance");
    }

    #[test]
    fn palettes_are_stable_and_cover_every_instance() {
        let first = instance("26.3", "26.3", LoaderKind::Vanilla, "26.3").palette();
        let second = instance("26.3", "26.3", LoaderKind::Vanilla, "26.3").palette();
        assert_eq!(first, second);
        assert_eq!(
            instance("雾林生存", "雾林生存", LoaderKind::Fabric, "1.21.4").monogram(),
            "雾"
        );
    }

    #[test]
    fn relative_dates_use_the_macos_copy() {
        let now_ms = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock after epoch")
            .as_millis() as i64;
        assert_eq!(relative_date(None), "从未游玩");
        assert_eq!(relative_date(Some(now_ms - 10_000)), "刚刚");
        assert_eq!(relative_date(Some(now_ms - 7_200_000)), "2 小时前");
        assert_eq!(relative_date(Some(now_ms - 129_600_000)), "昨天");
    }
}
