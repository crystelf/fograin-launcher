//! Streaming hash helpers shared by the cache and the download engine.

use std::path::Path;

use fg_core::{Error, ErrorKind, Result};
use sha1::{Digest as _, Sha1};
use sha2::{Sha256, Sha512};

/// Hash algorithms the launcher verifies against.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HashAlgorithm {
    Sha1,
    Sha256,
    Sha512,
}

impl HashAlgorithm {
    /// Lowercase name used in logs and cache layouts.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Sha1 => "sha1",
            Self::Sha256 => "sha256",
            Self::Sha512 => "sha512",
        }
    }

    /// Length of the hexadecimal digest.
    #[must_use]
    pub const fn hex_len(self) -> usize {
        match self {
            Self::Sha1 => 40,
            Self::Sha256 => 64,
            Self::Sha512 => 128,
        }
    }

    /// Parses a manifest-style algorithm name.
    #[must_use]
    pub fn parse(name: &str) -> Option<Self> {
        match name.to_ascii_lowercase().as_str() {
            "sha1" => Some(Self::Sha1),
            "sha256" => Some(Self::Sha256),
            "sha512" => Some(Self::Sha512),
            _ => None,
        }
    }
}

enum Inner {
    Sha1(Sha1),
    Sha256(Sha256),
    Sha512(Sha512),
}

/// Incremental hasher with a runtime-selected algorithm.
pub struct Hasher {
    algorithm: HashAlgorithm,
    inner: Inner,
}

impl Hasher {
    #[must_use]
    pub fn new(algorithm: HashAlgorithm) -> Self {
        let inner = match algorithm {
            HashAlgorithm::Sha1 => Inner::Sha1(Sha1::new()),
            HashAlgorithm::Sha256 => Inner::Sha256(Sha256::new()),
            HashAlgorithm::Sha512 => Inner::Sha512(Sha512::new()),
        };
        Self { algorithm, inner }
    }

    pub fn update(&mut self, bytes: &[u8]) {
        match &mut self.inner {
            Inner::Sha1(hasher) => hasher.update(bytes),
            Inner::Sha256(hasher) => hasher.update(bytes),
            Inner::Sha512(hasher) => hasher.update(bytes),
        }
    }

    /// Finishes the digest as lowercase hexadecimal.
    #[must_use]
    pub fn finish(self) -> String {
        match self.inner {
            Inner::Sha1(hasher) => hex(&hasher.finalize()),
            Inner::Sha256(hasher) => hex(&hasher.finalize()),
            Inner::Sha512(hasher) => hex(&hasher.finalize()),
        }
    }

    #[must_use]
    pub fn algorithm(&self) -> HashAlgorithm {
        self.algorithm
    }
}

/// Hashes a byte slice with the given algorithm.
#[must_use]
pub fn hash_bytes(bytes: &[u8], algorithm: HashAlgorithm) -> String {
    let mut hasher = Hasher::new(algorithm);
    hasher.update(bytes);
    hasher.finish()
}

/// Hashes a file with a 64 KiB read buffer.
pub async fn hash_file(path: &Path, algorithm: HashAlgorithm) -> Result<String> {
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|error| io_error(path, &error))?;
    let mut hasher = Hasher::new(algorithm);
    let mut buffer = vec![0_u8; 64 * 1024];

    loop {
        use tokio::io::AsyncReadExt as _;

        let read = file
            .read(&mut buffer)
            .await
            .map_err(|error| io_error(path, &error))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }

    Ok(hasher.finish())
}

/// Seeds a hasher with the contents of a partially downloaded file.
pub(crate) async fn feed_file(hasher: &mut Hasher, path: &Path) -> Result<()> {
    let mut file = tokio::fs::File::open(path)
        .await
        .map_err(|error| io_error(path, &error))?;
    let mut buffer = vec![0_u8; 64 * 1024];

    loop {
        use tokio::io::AsyncReadExt as _;

        let read = file
            .read(&mut buffer)
            .await
            .map_err(|error| io_error(path, &error))?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }

    Ok(())
}

pub(crate) fn io_error(path: &Path, error: &std::io::Error) -> Error {
    Error::with_detail(
        ErrorKind::Io,
        "error.io",
        format!("{}: {error}", path.display()),
    )
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write as _;

    let mut output = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(output, "{byte:02x}");
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_known_digests() {
        assert_eq!(
            hash_bytes(b"abc", HashAlgorithm::Sha1),
            "a9993e364706816aba3e25717850c26c9cd0d89d"
        );
        assert_eq!(
            hash_bytes(b"abc", HashAlgorithm::Sha256),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            hash_bytes(b"abc", HashAlgorithm::Sha512),
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a\
             2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
                .replace(' ', "")
        );
    }

    #[test]
    fn algorithm_names_round_trip() {
        for algorithm in [
            HashAlgorithm::Sha1,
            HashAlgorithm::Sha256,
            HashAlgorithm::Sha512,
        ] {
            assert_eq!(HashAlgorithm::parse(algorithm.as_str()), Some(algorithm));
            assert_eq!(algorithm.hex_len(), hash_bytes(b"", algorithm).len());
        }
        assert_eq!(HashAlgorithm::parse("md5"), None);
    }

    #[tokio::test]
    async fn hashes_files_from_disk() {
        let dir = tempfile::tempdir().expect("temp dir");
        let path = dir.path().join("payload.bin");
        tokio::fs::write(&path, b"abc").await.expect("write");

        let digest = hash_file(&path, HashAlgorithm::Sha1).await.expect("hash");
        assert_eq!(digest, "a9993e364706816aba3e25717850c26c9cd0d89d");
    }
}
