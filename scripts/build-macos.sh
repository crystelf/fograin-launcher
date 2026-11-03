#!/usr/bin/env bash
# Build (and optionally test or run) the macOS client.
#
#   scripts/build-macos.sh [debug|release] [--test] [--run]
#
# The macOS client links the Rust core as a static library, so a plain
# `swift build` is not enough. This script builds fg-ffi, copies the archive
# into apps/macos/.build/rust/lib, then hands off to SwiftPM.
#
# SwiftUI macros need a full Xcode toolchain. When `xcode-select` points at
# CommandLineTools, the script prefers /Applications/Xcode.app or
# /Applications/Xcode-beta.app if either exists.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROFILE="debug"
RUN_TESTS=0
RUN_APP=0

usage() {
    sed -n '2,14p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

for argument in "$@"; do
    case "$argument" in
        debug|release) PROFILE="$argument" ;;
        --test) RUN_TESTS=1 ;;
        --run) RUN_APP=1 ;;
        -h|--help) usage; exit 0 ;;
        *)
            echo "error: unknown argument '$argument'" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -z "${DEVELOPER_DIR:-}" ]]; then
    active_developer_dir="$(xcode-select -p 2>/dev/null || true)"
    if [[ "$active_developer_dir" == /Library/Developer/CommandLineTools* ]]; then
        for candidate in /Applications/Xcode.app /Applications/Xcode-beta.app; do
            if [[ -d "$candidate/Contents/Developer" ]]; then
                export DEVELOPER_DIR="$candidate/Contents/Developer"
                echo "==> Using $candidate for the Swift toolchain"
                break
            fi
        done
        if [[ -z "${DEVELOPER_DIR:-}" ]]; then
            echo "warning: CommandLineTools cannot provide the SwiftUI macro plugin; install Xcode." >&2
        fi
    fi
fi

# SwiftPM otherwise records the deployment target as the SDK in the linked
# binary. Pass the real SDK so macOS enables the current design (Liquid Glass).
FOGRAIN_MACOS_SDK_VERSION="$(xcrun --sdk macosx --show-sdk-version 2>/dev/null || echo "14.0")"
export FOGRAIN_MACOS_SDK_VERSION
echo "==> macOS SDK $FOGRAIN_MACOS_SDK_VERSION"

cargo_arguments=(build --manifest-path "$ROOT/Cargo.toml" -p fg-ffi)
if [[ "$PROFILE" == "release" ]]; then
    cargo_arguments+=(--release)
fi

echo "==> Building fg-ffi ($PROFILE)"
cargo "${cargo_arguments[@]}"

rust_library="$ROOT/target/$PROFILE/libfg_ffi.a"
if [[ ! -f "$rust_library" ]]; then
    echo "error: expected $rust_library after cargo build" >&2
    exit 1
fi

vendor_directory="$ROOT/apps/macos/.build/rust/lib"
mkdir -p "$vendor_directory"
cp "$rust_library" "$vendor_directory/libfg_ffi.a"

if [[ "$RUN_TESTS" == "1" ]]; then
    echo "==> swift test"
    swift test --package-path "$ROOT/apps/macos"
elif [[ "$RUN_APP" == "1" ]]; then
    echo "==> swift run ($PROFILE)"
    swift run --package-path "$ROOT/apps/macos" -c "$PROFILE" Fograin
else
    echo "==> swift build ($PROFILE)"
    swift build --package-path "$ROOT/apps/macos" -c "$PROFILE"
fi
