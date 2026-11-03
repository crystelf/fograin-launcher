//! Static view models for the M1 shell.
//!
//! The fixtures mirror the pen screens so the native layouts can be reviewed
//! before the FFI wiring lands. M4 replaces [`INSTANCES`] with real
//! `instance.toml` data; every helper here already behaves like the eventual
//! implementation (grouping, sorting, filtering, formatting).

/// Mod loader families the launcher knows about.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
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
    pub const fn library_group(self) -> LibraryGroup {
        match self {
            Self::Fabric | Self::Quilt => LibraryGroup::Fabric,
            Self::Forge => LibraryGroup::Forge,
            Self::NeoForge => LibraryGroup::NeoForge,
            Self::Vanilla | Self::Optifine => LibraryGroup::Vanilla,
        }
    }

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
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LibraryGroup {
    Fabric,
    Forge,
    NeoForge,
    Vanilla,
}

impl LibraryGroup {
    pub const ALL: [Self; 4] = [Self::Fabric, Self::Forge, Self::NeoForge, Self::Vanilla];

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

/// Coarse "last played" fixture. The pen uses human labels, not timestamps, so
/// the shell stores exactly that and keeps the raw minutes for sorting.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Played {
    Never,
    Hours(u32),
    Days(u32),
    Weeks(u32),
    Months(u32),
}

impl Played {
    pub fn label(self) -> String {
        match self {
            Self::Never => "未游玩".to_owned(),
            Self::Hours(1) => "1 小时前".to_owned(),
            Self::Hours(hours) => format!("{hours} 小时前"),
            Self::Days(1) => "昨天".to_owned(),
            Self::Days(days) => format!("{days} 天前"),
            Self::Weeks(1) => "上周".to_owned(),
            Self::Weeks(weeks) => format!("{weeks} 周前"),
            Self::Months(1) => "上个月".to_owned(),
            Self::Months(months) => format!("{months} 个月前"),
        }
    }

    /// Minutes since the last launch; `None` sorts last.
    pub const fn minutes_ago(self) -> Option<u64> {
        match self {
            Self::Never => None,
            Self::Hours(hours) => Some(hours as u64 * 60),
            Self::Days(days) => Some(days as u64 * 1_440),
            Self::Weeks(weeks) => Some(weeks as u64 * 10_080),
            Self::Months(months) => Some(months as u64 * 43_200),
        }
    }
}

/// One instance in the M1 fixtures.
#[derive(Clone, Copy, Debug)]
pub struct Instance {
    pub id: &'static str,
    pub name: &'static str,
    pub monogram: &'static str,
    pub loader: LoaderKind,
    pub loader_version: &'static str,
    pub minecraft: &'static str,
    pub mod_count: Option<u32>,
    pub updates: u32,
    pub size_bytes: u64,
    pub played: Played,
    pub favorite: bool,
    pub palette: Palette,
    pub resource_pack: Option<&'static str>,
    pub shader: Option<&'static str>,
    pub world_count: u32,
    pub java: &'static str,
    pub play_minutes: u32,
}

impl Instance {
    /// Subtitle used by the home list, e.g. `1.21.4 · Fabric · 42 模组`.
    pub fn home_subtitle(&self) -> String {
        match self.mod_count {
            Some(count) => format!(
                "{} · {} · {count} 模组",
                self.minecraft,
                self.loader.title()
            ),
            None => format!("{} · {}", self.minecraft, self.loader.title()),
        }
    }

    /// Subtitle used by the library list, e.g. `1.21.4 · 42 模组 · 3.1 GB`.
    pub fn library_subtitle(&self) -> String {
        match self.mod_count {
            Some(count) => format!(
                "{} · {count} 模组 · {}",
                self.minecraft,
                format_size(self.size_bytes)
            ),
            None => format!("{} · {}", self.minecraft, format_size(self.size_bytes)),
        }
    }

    /// Banner meta line, e.g. `Java 版 1.21.4 · Fabric 0.16.9 · 42 个模组`.
    pub fn banner_meta(&self) -> String {
        let loader = match self.mod_count {
            Some(_) => format!("{} {}", self.loader.title(), self.loader_version),
            None => self.loader.title().to_owned(),
        };
        match self.mod_count {
            Some(count) => format!("Java 版 {} · {loader} · {count} 个模组", self.minecraft),
            None => format!("Java 版 {} · {loader}", self.minecraft),
        }
    }

    /// Detail-pane heading, e.g. `Fabric 0.16.9 · Java 版 1.21.4`.
    pub fn detail_meta(&self) -> String {
        match self.mod_count {
            Some(_) => format!(
                "{} {} · Java 版 {}",
                self.loader.title(),
                self.loader_version,
                self.minecraft
            ),
            None => format!("Java 版 {}", self.minecraft),
        }
    }

    pub fn loader_chip(&self) -> String {
        match self.mod_count {
            Some(_) => format!("{} {}", self.loader.title(), self.loader_version),
            None => self.loader.title().to_owned(),
        }
    }

    pub fn mods_label(&self) -> String {
        match self.mod_count {
            Some(count) => format!("{count} 个已启用"),
            None => "未安装".to_owned(),
        }
    }

    pub fn mods_update_label(&self) -> String {
        format!("{} 个可更新", self.updates)
    }

    pub fn resource_pack_label(&self) -> &'static str {
        self.resource_pack.unwrap_or("未启用")
    }

    pub fn shader_label(&self) -> &'static str {
        self.shader.unwrap_or("未启用")
    }

    pub fn worlds_label(&self) -> String {
        format!("{} 个存档", self.world_count)
    }

    pub fn play_time_label(&self) -> String {
        format_play_time(self.play_minutes)
    }
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

    pub const fn id(self) -> &'static str {
        match self {
            Self::Recent => "recent",
            Self::Name => "name",
            Self::Size => "size",
        }
    }

    pub const fn label(self) -> &'static str {
        match self {
            Self::Recent => "最近游玩",
            Self::Name => "名称",
            Self::Size => "占用空间",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|order| order.id() == id)
    }
}

/// One active download, mirroring the pen's download card.
#[derive(Clone, Copy, Debug)]
pub struct Download {
    pub title: &'static str,
    pub detail: &'static str,
    pub fraction: f64,
}

/// Environment summary for the home status column.
#[derive(Clone, Debug)]
pub struct Environment {
    pub java: String,
    pub memory: &'static str,
    pub graphics: &'static str,
}

/// Fixture instances, ordered like the pen's recent list.
pub const INSTANCES: &[Instance] = &[
    Instance {
        id: "fog-forest",
        name: "雾林生存",
        monogram: "雾",
        loader: LoaderKind::Fabric,
        loader_version: "0.16.9",
        minecraft: "1.21.4",
        mod_count: Some(42),
        updates: 3,
        size_bytes: 3_100_000_000,
        played: Played::Hours(2),
        favorite: true,
        palette: Palette::Forest,
        resource_pack: Some("Faithful 32x"),
        shader: Some("Complementary Reimagined"),
        world_count: 3,
        java: "Temurin 21 · 6 GB",
        play_minutes: 7_560,
    },
    Instance {
        id: "create-6",
        name: "机械动力 6",
        monogram: "机",
        loader: LoaderKind::Forge,
        loader_version: "47.2.0",
        minecraft: "1.20.1",
        mod_count: Some(186),
        updates: 0,
        size_bytes: 4_800_000_000,
        played: Played::Days(1),
        favorite: true,
        palette: Palette::Copper,
        resource_pack: Some("Faithful 32x"),
        shader: Some("BSL Shaders"),
        world_count: 2,
        java: "Temurin 17 · 8 GB",
        play_minutes: 5_280,
    },
    Instance {
        id: "vanilla",
        name: "原版纯净",
        monogram: "原",
        loader: LoaderKind::Vanilla,
        loader_version: "",
        minecraft: "1.21.4",
        mod_count: None,
        updates: 0,
        size_bytes: 410_000_000,
        played: Played::Days(3),
        favorite: false,
        palette: Palette::Slate,
        resource_pack: None,
        shader: None,
        world_count: 1,
        java: "Temurin 21 · 4 GB",
        play_minutes: 720,
    },
    Instance {
        id: "sky-tech",
        name: "空岛科技",
        monogram: "空",
        loader: LoaderKind::NeoForge,
        loader_version: "21.1.72",
        minecraft: "1.19.2",
        mod_count: Some(97),
        updates: 1,
        size_bytes: 3_600_000_000,
        played: Played::Weeks(1),
        favorite: true,
        palette: Palette::Violet,
        resource_pack: Some("Faithful 32x"),
        shader: Some("Complementary Reimagined"),
        world_count: 1,
        java: "Temurin 17 · 6 GB",
        play_minutes: 3_240,
    },
    Instance {
        id: "nostalgia",
        name: "怀旧 1.8.9",
        monogram: "怀",
        loader: LoaderKind::Optifine,
        loader_version: "HD U M5",
        minecraft: "1.8.9",
        mod_count: None,
        updates: 0,
        size_bytes: 620_000_000,
        played: Played::Weeks(3),
        favorite: false,
        palette: Palette::Clay,
        resource_pack: None,
        shader: None,
        world_count: 2,
        java: "Temurin 8 · 3 GB",
        play_minutes: 1_860,
    },
    Instance {
        id: "deep-sea",
        name: "深海探索",
        monogram: "深",
        loader: LoaderKind::Fabric,
        loader_version: "0.16.9",
        minecraft: "1.21.1",
        mod_count: Some(28),
        updates: 0,
        size_bytes: 1_900_000_000,
        played: Played::Months(1),
        favorite: false,
        palette: Palette::Lagoon,
        resource_pack: Some("Faithful 32x"),
        shader: None,
        world_count: 2,
        java: "Temurin 21 · 6 GB",
        play_minutes: 1_200,
    },
    Instance {
        id: "architecture",
        name: "建筑灵感",
        monogram: "建",
        loader: LoaderKind::Fabric,
        loader_version: "0.16.9",
        minecraft: "1.21.4",
        mod_count: Some(12),
        updates: 0,
        size_bytes: 860_000_000,
        played: Played::Months(2),
        favorite: false,
        palette: Palette::Sand,
        resource_pack: Some("Faithful 32x"),
        shader: None,
        world_count: 4,
        java: "Temurin 21 · 4 GB",
        play_minutes: 480,
    },
    Instance {
        id: "twilight",
        name: "暮色森林",
        monogram: "暮",
        loader: LoaderKind::Forge,
        loader_version: "47.2.0",
        minecraft: "1.20.1",
        mod_count: Some(64),
        updates: 0,
        size_bytes: 2_200_000_000,
        played: Played::Months(2),
        favorite: false,
        palette: Palette::Moss,
        resource_pack: None,
        shader: Some("Complementary Reimagined"),
        world_count: 3,
        java: "Temurin 17 · 6 GB",
        play_minutes: 2_520,
    },
    Instance {
        id: "snapshot",
        name: "快照 25w14a",
        monogram: "快",
        loader: LoaderKind::Vanilla,
        loader_version: "",
        minecraft: "25w14a",
        mod_count: None,
        updates: 0,
        size_bytes: 380_000_000,
        played: Played::Never,
        favorite: false,
        palette: Palette::Ash,
        resource_pack: None,
        shader: None,
        world_count: 0,
        java: "Temurin 21 · 4 GB",
        play_minutes: 0,
    },
];

pub const DOWNLOADS: &[Download] = &[
    Download {
        title: "Sodium 0.6.5",
        detail: "3.1 / 4.2 MB",
        fraction: 0.74,
    },
    Download {
        title: "机械动力 6 · 资源补全",
        detail: "312 / 1 240",
        fraction: 0.25,
    },
];

pub const DOWNLOAD_SPEED: &str = "18.4 MB/s";

/// Every fixture instance.
pub fn all() -> &'static [Instance] {
    INSTANCES
}

/// The instance the home banner launches.
pub fn current() -> &'static Instance {
    &INSTANCES[0]
}

pub fn by_id(id: &str) -> Option<&'static Instance> {
    INSTANCES.iter().find(|instance| instance.id == id)
}

/// Five most recently played instances, newest first.
pub fn recent() -> Vec<&'static Instance> {
    let mut items: Vec<&'static Instance> = INSTANCES.iter().collect();
    items.sort_by_key(|instance| instance.played.minutes_ago().unwrap_or(u64::MAX));
    items.truncate(5);
    items
}

pub fn favorites() -> Vec<&'static Instance> {
    INSTANCES
        .iter()
        .filter(|instance| instance.favorite)
        .collect()
}

/// Instances of one loader group, filtered and ordered by the library controls.
pub fn group_items(query: &str, group: LibraryGroup, order: SortOrder) -> Vec<&'static Instance> {
    let mut items: Vec<&'static Instance> = INSTANCES
        .iter()
        .filter(|instance| instance.loader.library_group() == group)
        .filter(|instance| matches_query(instance, query))
        .collect();
    sort(&mut items, order);
    items
}

/// Case-insensitive match over the visible list metadata (name, version,
/// loader, size). Chinese has no case, so `to_lowercase` is a cheap no-op there.
pub fn matches_query(instance: &Instance, query: &str) -> bool {
    let needle = query.trim().to_lowercase();
    if needle.is_empty() {
        return true;
    }
    let haystack = format!(
        "{} {} {} {} {} {}",
        instance.name,
        instance.minecraft,
        instance.loader.title(),
        instance.loader_version,
        instance.mods_label(),
        format_size(instance.size_bytes)
    );
    haystack.to_lowercase().contains(&needle)
}

fn sort(items: &mut [&'static Instance], order: SortOrder) {
    match order {
        SortOrder::Recent => {
            items.sort_by_key(|instance| instance.played.minutes_ago().unwrap_or(u64::MAX))
        }
        SortOrder::Name => items.sort_by(|left, right| left.name.cmp(right.name)),
        SortOrder::Size => items.sort_by_key(|instance| std::cmp::Reverse(instance.size_bytes)),
    }
}

/// Decimal units, like the pen (`3.1 GB`, `410 MB`).
pub fn format_size(bytes: u64) -> String {
    const GIB: f64 = 1_000_000_000.0;
    const MIB: f64 = 1_000_000.0;

    if bytes as f64 >= GIB {
        format!("{:.1} GB", bytes as f64 / GIB)
    } else {
        format!("{:.0} MB", (bytes as f64 / MIB).max(1.0))
    }
}

pub fn total_size(instances: &[&'static Instance]) -> u64 {
    instances.iter().map(|instance| instance.size_bytes).sum()
}

pub fn format_play_time(minutes: u32) -> String {
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

pub fn environment() -> Environment {
    Environment {
        java: format!("Temurin 21.0.5 · {}", std::env::consts::ARCH),
        memory: "6 GB / 32 GB",
        graphics: "Wayland · Mesa 24.2 · AMD RX 7800",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_use_decimal_units_and_skip_sub_megabyte_rounding() {
        assert_eq!(format_size(3_100_000_000), "3.1 GB");
        assert_eq!(format_size(410_000_000), "410 MB");
        assert_eq!(format_size(1_000_000), "1 MB");
    }

    #[test]
    fn play_time_formats_hours_and_minutes() {
        assert_eq!(format_play_time(0), "—");
        assert_eq!(format_play_time(45), "45 分钟");
        assert_eq!(format_play_time(7_560), "126 小时");
        assert_eq!(format_play_time(130), "2 小时 10 分钟");
    }

    #[test]
    fn every_instance_belongs_to_exactly_one_group() {
        let grouped = LibraryGroup::ALL
            .into_iter()
            .map(|group| group_items("", group, SortOrder::Recent).len())
            .sum::<usize>();
        assert_eq!(grouped, all().len());
    }

    #[test]
    fn query_matches_loader_version_and_filters_groups() {
        let matches = group_items("forge", LibraryGroup::Forge, SortOrder::Recent);
        assert_eq!(matches.len(), 2);

        let fabric = group_items("0.16.9", LibraryGroup::Fabric, SortOrder::Recent);
        assert_eq!(fabric.len(), 3);

        assert!(group_items("sodium", LibraryGroup::Vanilla, SortOrder::Recent).is_empty());
    }

    #[test]
    fn recent_order_puts_unplayed_instances_last() {
        let recent = recent();
        assert_eq!(recent.len(), 5);
        assert_eq!(recent[0].id, "fog-forest");
        assert!(
            recent
                .iter()
                .all(|instance| !matches!(instance.played, Played::Never))
        );
    }

    #[test]
    fn sorting_by_size_is_descending() {
        let items = group_items("", LibraryGroup::Fabric, SortOrder::Size);
        let sizes: Vec<u64> = items.iter().map(|instance| instance.size_bytes).collect();
        let mut expected = sizes.clone();
        expected.sort_unstable_by(|left, right| right.cmp(left));
        assert_eq!(sizes, expected);
    }

    #[test]
    fn played_labels_match_the_pen_copy() {
        assert_eq!(Played::Hours(2).label(), "2 小时前");
        assert_eq!(Played::Days(1).label(), "昨天");
        assert_eq!(Played::Never.label(), "未游玩");
        assert_eq!(Played::Months(2).label(), "2 个月前");
    }
}
