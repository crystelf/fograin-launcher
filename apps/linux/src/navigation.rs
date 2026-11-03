//! Top-level sections of the GNOME client and their sidebar metadata.

/// One destination in the content pane.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Section {
    Home,
    Library,
    Servers,
    Downloads,
    Java,
    Accounts,
    Settings,
}

/// Copy for a page that is scheduled for a later milestone.
#[derive(Clone, Copy, Debug)]
pub struct Milestone {
    pub name: &'static str,
    pub detail: &'static str,
}

impl Section {
    /// Every section; the index drives the `Ctrl+1`…`Ctrl+7` accelerators.
    pub const ALL: [Self; 7] = [
        Self::Home,
        Self::Library,
        Self::Servers,
        Self::Downloads,
        Self::Java,
        Self::Accounts,
        Self::Settings,
    ];

    /// Sidebar rows above the separator, in pen order.
    pub const LAUNCH: [Self; 3] = [Self::Home, Self::Library, Self::Servers];

    /// Sidebar rows below the separator.
    pub const RESOURCES: [Self; 2] = [Self::Downloads, Self::Java];

    pub const fn title(self) -> &'static str {
        match self {
            Self::Home => "主页",
            Self::Library => "实例库",
            Self::Servers => "服务器",
            Self::Downloads => "下载",
            Self::Java => "Java 运行时",
            Self::Accounts => "账户",
            Self::Settings => "设置",
        }
    }

    /// Header-bar subtitle; also the one-line summary of the page.
    pub const fn subtitle(self) -> &'static str {
        match self {
            Self::Home => "启动上次游玩的实例",
            Self::Library => "管理所有实例、模组与存档",
            Self::Servers => "收藏、局域网发现与状态 Ping",
            Self::Downloads => "下载队列与资源搜索",
            Self::Java => "扫描、安装与测试运行",
            Self::Accounts => "离线、外置登录与皮肤",
            Self::Settings => "外观与全局设置",
        }
    }

    pub const fn icon(self) -> &'static str {
        match self {
            Self::Home => "go-home-symbolic",
            Self::Library => "view-grid-symbolic",
            Self::Servers => "network-server-symbolic",
            Self::Downloads => "folder-download-symbolic",
            Self::Java => "applications-utilities-symbolic",
            Self::Accounts => "avatar-default-symbolic",
            Self::Settings => "emblem-system-symbolic",
        }
    }

    /// Action id without the `app.` prefix, used for actions and accelerators.
    pub const fn action_id(self) -> &'static str {
        match self {
            Self::Home => "goto-home",
            Self::Library => "goto-library",
            Self::Servers => "goto-servers",
            Self::Downloads => "goto-downloads",
            Self::Java => "goto-java",
            Self::Accounts => "goto-accounts",
            Self::Settings => "goto-settings",
        }
    }

    /// Full action name for `Gtk.Widget::action-name`.
    pub const fn action_name(self) -> &'static str {
        match self {
            Self::Home => "app.goto-home",
            Self::Library => "app.goto-library",
            Self::Servers => "app.goto-servers",
            Self::Downloads => "app.goto-downloads",
            Self::Java => "app.goto-java",
            Self::Accounts => "app.goto-accounts",
            Self::Settings => "app.goto-settings",
        }
    }

    /// Copy for sections that are still stubs in this milestone.
    pub const fn milestone(self) -> Option<Milestone> {
        match self {
            Self::Servers => Some(Milestone {
                name: "M9",
                detail: "服务器列表、SLP Ping 与局域网发现将在 M9 接入。",
            }),
            Self::Downloads => Some(Milestone {
                name: "M8",
                detail: "资源浏览、依赖解析与下载队列将在 M8 接入。",
            }),
            Self::Java => Some(Milestone {
                name: "M6",
                detail: "Java 扫描、运行时下载与测试运行将在 M6 接入。",
            }),
            Self::Accounts => Some(Milestone {
                name: "M7",
                detail: "离线与外置登录将在 M7 接入；微软正版登录在 M11。",
            }),
            Self::Home | Self::Library | Self::Settings => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_section_has_a_unique_action() {
        let mut ids: Vec<&str> = Section::ALL
            .iter()
            .map(|section| section.action_id())
            .collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), Section::ALL.len());
    }

    #[test]
    fn only_unimplemented_sections_carry_a_milestone() {
        assert!(Section::Home.milestone().is_none());
        assert!(Section::Library.milestone().is_none());
        assert_eq!(
            Section::Servers.milestone().map(|item| item.name),
            Some("M9")
        );
    }

    #[test]
    fn sidebar_groups_keep_pen_order() {
        assert_eq!(
            Section::LAUNCH,
            [Section::Home, Section::Library, Section::Servers]
        );
        assert_eq!(Section::RESOURCES, [Section::Downloads, Section::Java]);
    }
}
