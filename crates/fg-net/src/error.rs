//! Failure classification for a single download attempt.
//!
//! Public callers only ever see [`fg_core::Error`]; this module keeps the
//! retry decision explicit and testable.

use fg_core::{Error, ErrorKind};
use reqwest::StatusCode;

/// Why one attempt against one candidate URL failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum AttemptError {
    /// DNS, TLS or socket failure.
    Connect(String),
    /// Connect/read timeout, including stalled body streams.
    Timeout(String),
    /// The server answered with an unexpected status.
    Status { status: StatusCode, detail: String },
    /// The bytes did not match the expected hash or size.
    Integrity(String),
    /// Local filesystem failure.
    Io(String),
    /// The response violated the HTTP contract (bad `Content-Range`, …).
    Protocol(String),
    /// The partial download is unusable; retry this candidate from scratch.
    Restart(String),
    /// The server does not support range requests for this URL.
    RangeUnsupported,
    /// The caller cancelled the download.
    Cancelled,
}

impl AttemptError {
    /// Whether repeating the same candidate URL can plausibly succeed.
    pub(crate) fn retryable(&self) -> bool {
        match self {
            Self::Status { status, .. } => {
                status.is_server_error()
                    || matches!(
                        *status,
                        StatusCode::REQUEST_TIMEOUT | StatusCode::TOO_MANY_REQUESTS
                    )
            }
            // A mismatch means the source served different bytes; retrying the
            // same source only wastes bandwidth. §6.2: switch candidates.
            Self::Integrity(_) | Self::Protocol(_) | Self::RangeUnsupported => false,
            Self::Cancelled => false,
            // Stale partial data is dropped before the retry, so a restart can
            // succeed even though the previous attempt failed.
            Self::Restart(_) | Self::Connect(_) | Self::Timeout(_) | Self::Io(_) => true,
        }
    }

    /// Human-readable cause used in aggregated error details and logs.
    pub(crate) fn detail(&self) -> String {
        match self {
            Self::Connect(detail)
            | Self::Timeout(detail)
            | Self::Integrity(detail)
            | Self::Io(detail)
            | Self::Protocol(detail)
            | Self::Restart(detail) => detail.clone(),
            Self::Status { status, detail } => format!("HTTP {status}: {detail}"),
            Self::RangeUnsupported => "server does not support byte ranges".to_string(),
            Self::Cancelled => "cancelled".to_string(),
        }
    }
}

/// Maps a `reqwest` transport failure onto [`AttemptError`].
pub(crate) fn from_reqwest(error: reqwest::Error) -> AttemptError {
    if error.is_timeout() {
        return AttemptError::Timeout(error.to_string());
    }
    if error.is_connect() || error.is_request() || error.is_body() || error.is_decode() {
        return AttemptError::Connect(error.to_string());
    }
    AttemptError::Connect(error.to_string())
}

/// Public error for a cancelled download.
pub(crate) fn cancelled() -> Error {
    Error::new(ErrorKind::Cancelled, "error.net.cancelled")
}

/// Public error raised when a request carries no candidate URL at all.
pub(crate) fn no_candidates(dest: &std::path::Path) -> Error {
    Error::with_detail(
        ErrorKind::InvalidInput,
        "error.net.no_candidates",
        dest.display().to_string(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn client_errors_are_retryable_but_client_statuses_are_not() {
        assert!(AttemptError::Timeout("read timeout".into()).retryable());
        assert!(AttemptError::Connect("dns".into()).retryable());

        let not_found = AttemptError::Status {
            status: StatusCode::NOT_FOUND,
            detail: "missing".into(),
        };
        assert!(!not_found.retryable());

        let server_error = AttemptError::Status {
            status: StatusCode::SERVICE_UNAVAILABLE,
            detail: "down".into(),
        };
        assert!(server_error.retryable());
    }

    #[test]
    fn integrity_failures_are_terminal_for_the_candidate() {
        let error = AttemptError::Integrity("sha1 mismatch".into());
        assert!(!error.retryable());
        assert_eq!(error.detail(), "sha1 mismatch");

        // A 416 drops the partial file, so one more try is productive.
        assert!(AttemptError::Restart("range not satisfiable".into()).retryable());
    }

    #[test]
    fn status_errors_carry_the_status_in_the_detail() {
        let error = AttemptError::Status {
            status: StatusCode::FORBIDDEN,
            detail: "blocked".into(),
        };
        assert_eq!(error.detail(), "HTTP 403 Forbidden: blocked");
    }
}
