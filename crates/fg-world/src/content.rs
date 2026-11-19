//! Small file-name conventions shared by worlds, data packs and servers.
//!
//! The `.disabled` suffix is the de-facto standard used by HMCL, Prism and
//! Minecraft itself for resource packs; keeping the helper here avoids a
//! dependency from `fg-world` back to `fg-content`.

/// Splits an optional `.disabled` suffix.
#[must_use]
pub fn strip_disabled(name: &str) -> (bool, &str) {
    match name.strip_suffix(".disabled") {
        Some(stripped) => (true, stripped),
        None => (false, name),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_suffix_is_transparent() {
        assert_eq!(strip_disabled("a.zip.disabled"), (true, "a.zip"));
        assert_eq!(strip_disabled("a.zip"), (false, "a.zip"));
    }
}
