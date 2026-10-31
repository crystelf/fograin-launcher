use std::fmt;

use serde::{Deserialize, Serialize};

/// Coarse category of a launcher error.
///
/// UI clients key off [`Error::message_key`] for localized copy; `kind` is only
/// used for branching and diagnostics.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum ErrorKind {
    Io,
    Network,
    Parse,
    NotFound,
    InvalidInput,
    Conflict,
    Unsupported,
    Auth,
    Cancelled,
    Internal,
}

/// Unified launcher error (§3 of `docs/design.md`).
///
/// `message_key` is an i18n key, never user-facing prose. `detail` carries the
/// technical cause for logs and the diagnostics bundle, and should not contain
/// credentials or access tokens.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Error {
    pub kind: ErrorKind,
    pub message_key: String,
    pub detail: Option<String>,
}

impl Error {
    #[must_use]
    pub fn new(kind: ErrorKind, message_key: impl Into<String>) -> Self {
        Self {
            kind,
            message_key: message_key.into(),
            detail: None,
        }
    }

    #[must_use]
    pub fn with_detail(
        kind: ErrorKind,
        message_key: impl Into<String>,
        detail: impl Into<String>,
    ) -> Self {
        Self {
            kind,
            message_key: message_key.into(),
            detail: Some(detail.into()),
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message_key)?;
        if let Some(detail) = &self.detail {
            write!(f, ": {detail}")?;
        }
        Ok(())
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        Self::with_detail(ErrorKind::Io, "error.io", error.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serializes_to_the_camel_case_wire_shape() {
        let error = Error::with_detail(ErrorKind::NotFound, "error.not_found", "missing version");
        let json = serde_json::to_value(&error).expect("error should serialize");

        assert_eq!(
            json,
            serde_json::json!({
                "kind": "notFound",
                "messageKey": "error.not_found",
                "detail": "missing version",
            })
        );
    }
}
