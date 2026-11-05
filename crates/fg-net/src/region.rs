//! Machine region detection used by the "Auto" download source mode (§6.1).
//!
//! Only mainland China gets mirror-first ordering. The detection is deliberately
//! conservative: a matching locale wins, a matching time zone is the fallback,
//! and `FOGRAIN_REGION=cn|global` overrides both for portable installs and tests.

use std::env;

/// Coarse region that drives mirror ordering.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Region {
    /// Mainland China: prefer domestic mirrors.
    China,
    /// Everywhere else: prefer official sources, mirrors as fallback.
    RestOfWorld,
}

impl Region {
    /// Detects the region of the current machine.
    #[must_use]
    pub fn detect() -> Self {
        if let Some(forced) = env::var("FOGRAIN_REGION").ok().as_deref().and_then(parse) {
            return forced;
        }

        let locale = env::var("LC_ALL")
            .ok()
            .filter(|value| !value.is_empty())
            .or_else(|| env::var("LC_CTYPE").ok().filter(|value| !value.is_empty()))
            .or_else(|| env::var("LANG").ok().filter(|value| !value.is_empty()));
        let timezone = env::var("TZ")
            .ok()
            .filter(|value| !value.is_empty())
            .or_else(|| iana_time_zone::get_timezone().ok());

        Self::from_hints(locale.as_deref(), timezone.as_deref())
    }

    /// Pure detection used by [`Region::detect`] and unit tests.
    #[must_use]
    pub fn from_hints(locale: Option<&str>, timezone: Option<&str>) -> Self {
        if locale.is_some_and(is_mainland_locale) || timezone.is_some_and(is_mainland_timezone) {
            Self::China
        } else {
            Self::RestOfWorld
        }
    }

    #[must_use]
    pub const fn is_china(self) -> bool {
        matches!(self, Self::China)
    }

    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::China => "cn",
            Self::RestOfWorld => "global",
        }
    }
}

fn parse(value: &str) -> Option<Region> {
    match value.trim().to_ascii_lowercase().as_str() {
        "cn" | "china" | "zh-cn" => Some(Region::China),
        "global" | "world" | "intl" => Some(Region::RestOfWorld),
        _ => None,
    }
}

/// Recognises Simplified Chinese locales from mainland China.
///
/// Taiwan, Hong Kong and Macau locales intentionally do *not* match: they are
/// served well by official sources and should keep official-first ordering.
fn is_mainland_locale(locale: &str) -> bool {
    let normalized = locale.to_ascii_lowercase().replace('-', "_");
    normalized.starts_with("zh_cn")
        || normalized.starts_with("zh_hans_cn")
        || normalized.starts_with("zh_sg")
}

fn is_mainland_timezone(timezone: &str) -> bool {
    matches!(
        timezone,
        "Asia/Shanghai" | "Asia/Chongqing" | "Asia/Harbin" | "Asia/Urumqi" | "PRC"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mainland_locales_select_china() {
        assert!(is_mainland_locale("zh_CN.UTF-8"));
        assert!(is_mainland_locale("zh-Hans-CN"));
        assert!(is_mainland_locale("zh_SG"));
        assert!(!is_mainland_locale("zh_TW.UTF-8"));
        assert!(!is_mainland_locale("zh_HK"));
        assert!(!is_mainland_locale("en_US.UTF-8"));
    }

    #[test]
    fn mainland_timezones_select_china() {
        assert!(is_mainland_timezone("Asia/Shanghai"));
        assert!(is_mainland_timezone("Asia/Urumqi"));
        assert!(!is_mainland_timezone("Asia/Taipei"));
        assert!(!is_mainland_timezone("Europe/Berlin"));
    }

    #[test]
    fn locale_wins_over_an_ambiguous_timezone() {
        assert_eq!(
            Region::from_hints(Some("en_US.UTF-8"), Some("Asia/Shanghai")),
            Region::China
        );
        assert_eq!(
            Region::from_hints(Some("zh_TW.UTF-8"), Some("Asia/Taipei")),
            Region::RestOfWorld
        );
        assert_eq!(
            Region::from_hints(None, Some("Asia/Shanghai")),
            Region::China
        );
        assert_eq!(Region::from_hints(None, None), Region::RestOfWorld);
    }

    #[test]
    fn override_values_are_case_insensitive() {
        assert_eq!(parse("CN"), Some(Region::China));
        assert_eq!(parse(" Global "), Some(Region::RestOfWorld));
        assert_eq!(parse("mars"), None);
    }
}
