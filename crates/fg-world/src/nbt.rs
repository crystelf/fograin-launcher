//! Minimal NBT reader/writer for gzip-compressed Minecraft data.
//!
//! M9 uses it for `level.dat` summaries (`Data.LevelName`, `LastPlayed`, …).
//! The reader understands every tag type so an unknown field never breaks a
//! world; the writer is only used by tests and small launcher-owned files.

use std::{
    collections::BTreeMap,
    fs::File,
    io::{Read, Write},
    path::Path,
};

use fg_core::{Error, ErrorKind, Result};
use flate2::{Compression, read::GzDecoder, write::GzEncoder};

/// Largest decompressed document; `level.dat` is normally a few KiB.
const MAX_BYTES: u64 = 16 * 1024 * 1024;
/// Maximum nesting depth accepted from a file.
const MAX_DEPTH: usize = 32;

/// One NBT tag payload.
#[derive(Debug, Clone, PartialEq)]
pub enum Nbt {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(Vec<u8>),
    String(String),
    List(Vec<Nbt>),
    Compound(BTreeMap<String, Nbt>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Nbt {
    /// Reads a named compound child.
    #[must_use]
    pub fn get(&self, key: &str) -> Option<&Nbt> {
        match self {
            Self::Compound(map) => map.get(key),
            _ => None,
        }
    }

    /// Reads a string child.
    #[must_use]
    pub fn string(&self, key: &str) -> Option<&str> {
        match self.get(key) {
            Some(Self::String(value)) => Some(value.as_str()),
            _ => None,
        }
    }

    /// Reads a list child.
    #[must_use]
    pub fn list(&self, key: &str) -> Option<&[Nbt]> {
        match self.get(key) {
            Some(Self::List(items)) => Some(items),
            _ => None,
        }
    }
}

/// Reads a gzip-compressed NBT document and returns the root compound.
///
/// # Errors
///
/// Returns [`ErrorKind::Parse`] for malformed NBT and [`ErrorKind::Io`] for
/// read failures.
pub fn read_gzip(path: &Path) -> Result<Nbt> {
    let file = File::open(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.nbt.read",
            format!("{}: {error}", path.display()),
        )
    })?;
    let decoder = GzDecoder::new(file);
    let mut buffer = Vec::new();
    decoder
        .take(MAX_BYTES + 1)
        .read_to_end(&mut buffer)
        .map_err(|error| {
            Error::with_detail(
                ErrorKind::Parse,
                "error.nbt.parse",
                format!("{}: {error}", path.display()),
            )
        })?;
    if buffer.len() as u64 > MAX_BYTES {
        return Err(Error::with_detail(
            ErrorKind::Parse,
            "error.nbt.parse",
            "NBT document is unreasonably large",
        ));
    }
    parse(&buffer)
}

/// Parses decompressed NBT bytes.
///
/// # Errors
///
/// Returns [`ErrorKind::Parse`] for truncated or unsupported input.
pub fn parse(bytes: &[u8]) -> Result<Nbt> {
    let mut reader = Reader {
        data: bytes,
        position: 0,
    };
    let tag = reader.u8()?;
    if tag != TAG_COMPOUND {
        return Err(parse_error("root tag is not a compound"));
    }
    let _name = reader.string()?;
    let root = reader.compound(1)?;
    Ok(root)
}

/// Writes a gzip-compressed NBT compound.
///
/// # Errors
///
/// Returns [`ErrorKind::Io`] when the destination cannot be written.
pub fn write_gzip(path: &Path, root: &Nbt) -> Result<()> {
    let mut payload = Vec::new();
    payload.push(TAG_COMPOUND);
    write_string(&mut payload, "");
    write_payload(&mut payload, root)?;
    let file = File::create(path).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.nbt.write",
            format!("{}: {error}", path.display()),
        )
    })?;
    let mut encoder = GzEncoder::new(file, Compression::default());
    encoder.write_all(&payload).map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.nbt.write",
            format!("{}: {error}", path.display()),
        )
    })?;
    encoder.finish().map_err(|error| {
        Error::with_detail(
            ErrorKind::Io,
            "error.nbt.write",
            format!("{}: {error}", path.display()),
        )
    })?;
    Ok(())
}

const TAG_END: u8 = 0;
const TAG_BYTE: u8 = 1;
const TAG_SHORT: u8 = 2;
const TAG_INT: u8 = 3;
const TAG_LONG: u8 = 4;
const TAG_FLOAT: u8 = 5;
const TAG_DOUBLE: u8 = 6;
const TAG_BYTE_ARRAY: u8 = 7;
const TAG_STRING: u8 = 8;
const TAG_LIST: u8 = 9;
const TAG_COMPOUND: u8 = 10;
const TAG_INT_ARRAY: u8 = 11;
const TAG_LONG_ARRAY: u8 = 12;

struct Reader<'a> {
    data: &'a [u8],
    position: usize,
}

impl Reader<'_> {
    fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.position)
    }

    fn take(&mut self, count: usize) -> Result<&[u8]> {
        let end = self
            .position
            .checked_add(count)
            .filter(|end| *end <= self.data.len())
            .ok_or_else(|| parse_error("unexpected end of NBT data"))?;
        let slice = &self.data[self.position..end];
        self.position = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    fn i16(&mut self) -> Result<i16> {
        let bytes = self.take(2)?;
        Ok(i16::from_be_bytes([bytes[0], bytes[1]]))
    }

    fn i32(&mut self) -> Result<i32> {
        let bytes = self.take(4)?;
        Ok(i32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
    }

    fn i64(&mut self) -> Result<i64> {
        let bytes = self.take(8)?;
        Ok(i64::from_be_bytes(
            bytes.try_into().map_err(|_| parse_error("invalid long"))?,
        ))
    }

    fn f32(&mut self) -> Result<f32> {
        Ok(f32::from_bits(self.i32()? as u32))
    }

    fn f64(&mut self) -> Result<f64> {
        Ok(f64::from_bits(self.i64()? as u64))
    }

    fn string(&mut self) -> Result<String> {
        let length = u16::from_be_bytes(
            self.take(2)?
                .try_into()
                .map_err(|_| parse_error("invalid string length"))?,
        ) as usize;
        let bytes = self.take(length)?;
        Ok(String::from_utf8_lossy(bytes).into_owned())
    }

    fn payload(&mut self, tag: u8, depth: usize) -> Result<Nbt> {
        if depth > MAX_DEPTH {
            return Err(parse_error("NBT nesting is too deep"));
        }
        Ok(match tag {
            TAG_BYTE => Nbt::Byte(self.u8()? as i8),
            TAG_SHORT => Nbt::Short(self.i16()?),
            TAG_INT => Nbt::Int(self.i32()?),
            TAG_LONG => Nbt::Long(self.i64()?),
            TAG_FLOAT => Nbt::Float(self.f32()?),
            TAG_DOUBLE => Nbt::Double(self.f64()?),
            TAG_BYTE_ARRAY => {
                let length = self.i32()?;
                if length < 0 || length as usize > self.remaining() {
                    return Err(parse_error("invalid byte array length"));
                }
                Nbt::ByteArray(self.take(length as usize)?.to_vec())
            }
            TAG_STRING => Nbt::String(self.string()?),
            TAG_LIST => {
                let element = self.u8()?;
                let length = self.i32()?;
                if length < 0 || length as usize > 1_000_000 {
                    return Err(parse_error("invalid list length"));
                }
                let mut items = Vec::with_capacity(length as usize);
                for _ in 0..length {
                    items.push(self.payload(element, depth + 1)?);
                }
                Nbt::List(items)
            }
            TAG_COMPOUND => self.compound(depth + 1)?,
            TAG_INT_ARRAY => {
                let length = self.i32()?;
                if length < 0 || length as usize > self.remaining() / 4 {
                    return Err(parse_error("invalid int array length"));
                }
                let mut values = Vec::with_capacity(length as usize);
                for _ in 0..length {
                    values.push(self.i32()?);
                }
                Nbt::IntArray(values)
            }
            TAG_LONG_ARRAY => {
                let length = self.i32()?;
                if length < 0 || length as usize > self.remaining() / 8 {
                    return Err(parse_error("invalid long array length"));
                }
                let mut values = Vec::with_capacity(length as usize);
                for _ in 0..length {
                    values.push(self.i64()?);
                }
                Nbt::LongArray(values)
            }
            other => {
                return Err(parse_error(&format!("unsupported NBT tag {other}")));
            }
        })
    }

    fn compound(&mut self, depth: usize) -> Result<Nbt> {
        if depth > MAX_DEPTH {
            return Err(parse_error("NBT nesting is too deep"));
        }
        let mut map = BTreeMap::new();
        loop {
            let tag = self.u8()?;
            if tag == TAG_END {
                break;
            }
            let name = self.string()?;
            let value = self.payload(tag, depth + 1)?;
            map.insert(name, value);
        }
        Ok(Nbt::Compound(map))
    }
}

fn parse_error(detail: &str) -> Error {
    Error::with_detail(ErrorKind::Parse, "error.nbt.parse", detail.to_string())
}

fn write_string(out: &mut Vec<u8>, value: &str) {
    let bytes = value.as_bytes();
    let length = u16::try_from(bytes.len()).unwrap_or(u16::MAX);
    out.extend_from_slice(&length.to_be_bytes());
    out.extend_from_slice(&bytes[..length as usize]);
}

fn write_payload(out: &mut Vec<u8>, value: &Nbt) -> Result<()> {
    match value {
        Nbt::Byte(value) => out.push(*value as u8),
        Nbt::Short(value) => out.extend_from_slice(&value.to_be_bytes()),
        Nbt::Int(value) => out.extend_from_slice(&value.to_be_bytes()),
        Nbt::Long(value) => out.extend_from_slice(&value.to_be_bytes()),
        Nbt::Float(value) => out.extend_from_slice(&value.to_bits().to_be_bytes()),
        Nbt::Double(value) => out.extend_from_slice(&value.to_bits().to_be_bytes()),
        Nbt::ByteArray(bytes) => {
            out.extend_from_slice(&i32::try_from(bytes.len()).unwrap_or(i32::MAX).to_be_bytes());
            out.extend_from_slice(bytes);
        }
        Nbt::String(value) => write_string(out, value),
        Nbt::List(items) => {
            let element = items.first().map_or(TAG_END, tag_of);
            out.push(element);
            out.extend_from_slice(&i32::try_from(items.len()).unwrap_or(i32::MAX).to_be_bytes());
            for item in items {
                write_payload(out, item)?;
            }
        }
        Nbt::Compound(map) => {
            for (name, child) in map {
                out.push(tag_of(child));
                write_string(out, name);
                write_payload(out, child)?;
            }
            out.push(TAG_END);
        }
        Nbt::IntArray(values) => {
            out.extend_from_slice(
                &i32::try_from(values.len())
                    .unwrap_or(i32::MAX)
                    .to_be_bytes(),
            );
            for value in values {
                out.extend_from_slice(&value.to_be_bytes());
            }
        }
        Nbt::LongArray(values) => {
            out.extend_from_slice(
                &i32::try_from(values.len())
                    .unwrap_or(i32::MAX)
                    .to_be_bytes(),
            );
            for value in values {
                out.extend_from_slice(&value.to_be_bytes());
            }
        }
    }
    Ok(())
}

const fn tag_of(value: &Nbt) -> u8 {
    match value {
        Nbt::Byte(_) => TAG_BYTE,
        Nbt::Short(_) => TAG_SHORT,
        Nbt::Int(_) => TAG_INT,
        Nbt::Long(_) => TAG_LONG,
        Nbt::Float(_) => TAG_FLOAT,
        Nbt::Double(_) => TAG_DOUBLE,
        Nbt::ByteArray(_) => TAG_BYTE_ARRAY,
        Nbt::String(_) => TAG_STRING,
        Nbt::List(_) => TAG_LIST,
        Nbt::Compound(_) => TAG_COMPOUND,
        Nbt::IntArray(_) => TAG_INT_ARRAY,
        Nbt::LongArray(_) => TAG_LONG_ARRAY,
    }
}

/// Parses an uncompressed NBT document from a cursor (used by tests).
#[cfg(test)]
fn round_trip(root: &Nbt) -> Nbt {
    let mut payload = Vec::new();
    payload.push(TAG_COMPOUND);
    write_string(&mut payload, "");
    write_payload(&mut payload, root).expect("write");
    parse(&payload).expect("parse")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_a_level_compound() {
        let mut server = BTreeMap::new();
        server.insert("name".to_string(), Nbt::String("Hypixel".to_string()));
        server.insert(
            "ip".to_string(),
            Nbt::String("mc.hypixel.net:25565".to_string()),
        );
        server.insert("acceptTextures".to_string(), Nbt::Byte(1));
        server.insert(
            "icon".to_string(),
            Nbt::String("data:image/png;base64,AAAA".to_string()),
        );
        let mut root = BTreeMap::new();
        root.insert(
            "servers".to_string(),
            Nbt::List(vec![Nbt::Compound(server)]),
        );
        let root = Nbt::Compound(root);

        let parsed = round_trip(&root);
        let servers = parsed.list("servers").expect("servers");
        assert_eq!(servers.len(), 1);
        assert_eq!(servers[0].string("name"), Some("Hypixel"));
        assert_eq!(servers[0].string("ip"), Some("mc.hypixel.net:25565"));
    }

    #[test]
    fn writes_and_reads_gzip_files() {
        let temp = tempfile::tempdir().expect("tempdir");
        let path = temp.path().join("servers.dat");
        let mut root = BTreeMap::new();
        root.insert("servers".to_string(), Nbt::List(Vec::new()));
        write_gzip(&path, &Nbt::Compound(root)).expect("write");
        let parsed = read_gzip(&path).expect("read");
        assert!(parsed.list("servers").expect("servers").is_empty());
    }

    #[test]
    fn rejects_truncated_input() {
        let error = parse(&[TAG_COMPOUND, 0, 0]).expect_err("truncated");
        assert_eq!(error.kind, ErrorKind::Parse);
        assert_eq!(
            parse(&[TAG_STRING]).expect_err("wrong root").kind,
            ErrorKind::Parse
        );
    }
}
