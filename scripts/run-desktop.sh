#!/usr/bin/env bash
# Builds the host JNI library and starts the Compose Desktop P0 shell.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" && -d "/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home"
fi

"$ROOT/scripts/build-jni-desktop.sh"
exec "$ROOT/apps/compose/gradlew" -p "$ROOT/apps/compose" :desktop:run "$@"
