#!/usr/bin/env bash
# Builds the Rust core as an Android JNI library and stages it for Gradle.
#
# The Compose UI talks to the core through `bindings/fg-ffi-jni`, which wraps
# the JSON contract in `bindings/fg-ffi`. Desktop uses the same JNI crate built
# for the host; only the `.so` below is Android-specific.
#
# Requirements:
#   - Rust targets: aarch64-linux-android and/or x86_64-linux-android
#   - Android NDK r26 or newer (r27+ is recommended)
#
# Usage:
#   ./scripts/build-android.sh                 # arm64-v8a + x86_64, release
#   ./scripts/build-android.sh --debug         # faster build, larger .so
#   ./scripts/build-android.sh --abi arm64-v8a # one ABI only
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_JNI_DIR="$ROOT/apps/compose/android/src/main/jniLibs"
API_LEVEL="${ANDROID_API_LEVEL:-26}"
PROFILE="release"
CARGO_PROFILE_FLAG="--release"
BASE_RUSTFLAGS="${RUSTFLAGS:-}"
ABIS=("arm64-v8a" "x86_64")

while [[ $# -gt 0 ]]; do
  case "$1" in
    --debug)
      PROFILE="debug"
      CARGO_PROFILE_FLAG=""
      shift
      ;;
    --abi)
      ABIS=("${2:?--abi requires a value}")
      shift 2
      ;;
    --api)
      API_LEVEL="${2:?--api requires a value}"
      shift 2
      ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

sdk_root() {
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_HOME"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
  elif [[ -f "$ROOT/apps/compose/local.properties" ]]; then
    sed -n 's/^sdk\.dir=//p' "$ROOT/apps/compose/local.properties" | tail -1
  else
    printf '%s\n' "$HOME/Library/Android/sdk"
  fi
}

SDK="$(sdk_root)"
if [[ ! -d "$SDK" ]]; then
  echo "Android SDK not found at $SDK; set ANDROID_HOME." >&2
  exit 1
fi

ndk_root() {
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return
  fi
  local candidate
  candidate="$(find "$SDK/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
  if [[ -n "$candidate" ]]; then
    printf '%s\n' "$candidate"
  fi
}

NDK="$(ndk_root)"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Android NDK not found under $SDK/ndk; install one with:" >&2
  echo "  android sdk install ndk/27.2.12479018" >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux) HOST_TAG="linux-x86_64" ;;
  *)
    echo "unsupported host: $(uname -s)" >&2
    exit 1
    ;;
esac

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain missing: $TOOLCHAIN" >&2
  exit 1
fi

target_for_abi() {
  case "$1" in
    arm64-v8a) printf '%s\n' "aarch64-linux-android" ;;
    x86_64) printf '%s\n' "x86_64-linux-android" ;;
    armeabi-v7a) printf '%s\n' "armv7-linux-androideabi" ;;
    x86) printf '%s\n' "i686-linux-android" ;;
    *)
      echo "unsupported ABI: $1" >&2
      exit 2
      ;;
  esac
}

env_key() {
  printf '%s\n' "$1" | tr '[:lower:]-' '[:upper:]_'
}

for abi in "${ABIS[@]}"; do
  target="$(target_for_abi "$abi")"
  if ! rustup target list --installed | grep -qx "$target"; then
    echo "missing Rust target $target; run: rustup target add $target" >&2
    exit 1
  fi

  clang="$TOOLCHAIN/bin/${target}${API_LEVEL}-clang"
  if [[ ! -x "$clang" ]]; then
    echo "missing NDK compiler: $clang" >&2
    exit 1
  fi

  key="$(env_key "$target")"
  target_key="${target//-/_}"
  export "CARGO_TARGET_${key}_LINKER=$clang"
  # cc-rs accepts the underscore form of the target in these variables, which
  # is the only spelling a POSIX shell can export (dashes are not valid in
  # environment variable names for `export`). The variable names are
  # case-sensitive and stay lowercase, unlike `CARGO_TARGET_*`.
  export "CC_${target_key}=$clang"
  export "CXX_${target_key}=$TOOLCHAIN/bin/${target}${API_LEVEL}-clang++"
  export "AR_${target_key}=$TOOLCHAIN/bin/llvm-ar"
  export "RANLIB_${target_key}=$TOOLCHAIN/bin/llvm-ranlib"

  # NDK r27 still needs the explicit 16 KB page-size flag for Android 15
  # devices that boot with 16 KB pages. r28+ makes it the default.
  if [[ "$abi" == "arm64-v8a" ]]; then
    export RUSTFLAGS="$BASE_RUSTFLAGS -C link-arg=-Wl,-z,max-page-size=16384"
  else
    export RUSTFLAGS="$BASE_RUSTFLAGS"
  fi

  echo "==> $abi ($target, API $API_LEVEL, $PROFILE)"
  cargo build \
    --manifest-path "$ROOT/Cargo.toml" \
    -p fg-ffi-jni \
    --target "$target" \
    --locked \
    ${CARGO_PROFILE_FLAG}

  mkdir -p "$APP_JNI_DIR/$abi"
  cp "$ROOT/target/$target/$PROFILE/libfg_ffi_jni.so" "$APP_JNI_DIR/$abi/libfg_ffi_jni.so"
  if [[ "$PROFILE" == "release" && -x "$TOOLCHAIN/bin/llvm-strip" ]]; then
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$APP_JNI_DIR/$abi/libfg_ffi_jni.so"
  fi
  echo "    staged $APP_JNI_DIR/$abi/libfg_ffi_jni.so"
done

echo "Done. Rebuild the APK with:"
echo "  ANDROID_HOME=\"$SDK\" ./apps/compose/gradlew -p apps/compose :android:assembleDebug"
