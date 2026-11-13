#!/usr/bin/env bash
# Builds the JNI shim for the host desktop and stages it for Compose Desktop.
#
#   scripts/build-jni-desktop.sh [--debug]
#
# The Rust crate is the same one Android uses (`bindings/fg-ffi-jni`); only the
# output name and the staging directory change. The library is packed as a
# classpath resource and extracted by `NativeLoader.desktop.kt` before
# `System.load`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="release"
CARGO_FLAG="--release"
DEST_ROOT="$ROOT/apps/compose/desktop/src/main/resources/native"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)
      PROFILE="debug"
      CARGO_FLAG=""
      shift
      ;;
    -h|--help)
      sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

HOST_TRIPLE="$(rustc -vV | sed -n 's/^host: //p')"
case "$HOST_TRIPLE" in
  aarch64-apple-darwin)      TRIPLE="darwin-aarch64"; LIB="libfg_ffi_jni.dylib" ;;
  x86_64-apple-darwin)       TRIPLE="darwin-x86_64";  LIB="libfg_ffi_jni.dylib" ;;
  aarch64-unknown-linux-gnu) TRIPLE="linux-aarch64";  LIB="libfg_ffi_jni.so" ;;
  x86_64-unknown-linux-gnu)  TRIPLE="linux-x86_64";   LIB="libfg_ffi_jni.so" ;;
  aarch64-pc-windows-msvc)   TRIPLE="windows-aarch64"; LIB="fg_ffi_jni.dll" ;;
  x86_64-pc-windows-msvc)    TRIPLE="windows-x86_64";  LIB="fg_ffi_jni.dll" ;;
  *)
    echo "unsupported host triple: $HOST_TRIPLE" >&2
    exit 1
    ;;
esac

echo "==> $TRIPLE ($PROFILE)"
cargo build \
  --manifest-path "$ROOT/Cargo.toml" \
  -p fg-ffi-jni \
  --locked \
  ${CARGO_FLAG}

SOURCE="$ROOT/target/$PROFILE/$LIB"
if [[ ! -f "$SOURCE" ]]; then
  echo "error: expected $SOURCE after cargo build" >&2
  exit 1
fi

DEST="$DEST_ROOT/$TRIPLE"
mkdir -p "$DEST"
cp "$SOURCE" "$DEST/$LIB"

# Cargo's strip on macOS corrupts the cdylib's LINKEDIT section on some
# toolchains; strip with the platform tool instead (see Cargo.toml profile).
if [[ "$PROFILE" == "release" ]]; then
  case "$(uname -s)" in
    Darwin) strip -x "$DEST/$LIB" ;;
    Linux) strip --strip-unneeded "$DEST/$LIB" ;;
  esac
fi

echo "    staged $DEST/$LIB"
