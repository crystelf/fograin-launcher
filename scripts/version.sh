#!/usr/bin/env bash
# Single source of truth for the launcher version.
#
# The release tag is the canonical version: a release is published only when
# the pushed tag (vMAJOR.MINOR.PATCH) matches the version checked into
# Cargo.toml and apps/compose/gradle.properties. `set` writes both files (and
# refreshes Cargo.lock); `tag` refuses to tag a tree whose manifests disagree.
#
#   ./scripts/version.sh current
#   ./scripts/version.sh check
#   ./scripts/version.sh check v0.1.1
#   ./scripts/version.sh set 0.1.1
#   ./scripts/version.sh tag
#   ./scripts/version.sh tag --push
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CARGO_TOML="$ROOT/Cargo.toml"
GRADLE_PROPS="$ROOT/apps/compose/gradle.properties"

usage() {
  sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

fail() {
  echo "version: $*" >&2
  exit 1
}

normalize() {
  local value="$1"
  value="${value#v}"
  value="${value#V}"
  printf '%s' "$value"
}

validate_version() {
  local value="$1"
  [[ "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] ||
    fail "invalid version '$value' (expected MAJOR.MINOR.PATCH[-PRERELEASE])"
}

cargo_toml_version() {
  awk '
    /^\[workspace\.package\]/ { section = 1; next }
    /^\[/ { section = 0 }
    section && !done && /^version[[:space:]]*=/ {
      sub(/^[^=]*=[[:space:]]*"/, "")
      sub(/".*$/, "")
      print
      done = 1
    }
  ' "$CARGO_TOML"
}

gradle_version() {
  sed -n 's/^fograin\.version=//p' "$GRADLE_PROPS" | tail -1
}

gradle_package_version() {
  sed -n 's/^fograin\.packageVersion=//p' "$GRADLE_PROPS" | tail -1
}

# jpackage rejects a version whose major component is 0. While the app is on
# 0.x the installer uses 1.MINOR.PATCH, so every release still upgrades the
# previously installed package (0.1.0 -> 1.1.0, 0.2.0 -> 1.2.0).
package_version_for() {
  local value="$1"
  local numeric="${value%%[-+]*}"
  local major minor patch
  IFS=. read -r major minor patch <<< "$numeric"
  if [[ "$major" == "0" ]]; then
    printf '1.%s.%s\n' "$minor" "$patch"
  else
    printf '%s.%s.%s\n' "$major" "$minor" "$patch"
  fi
}

current_version() {
  local value
  value="$(cargo_toml_version)"
  [[ -n "$value" ]] || fail "could not read [workspace.package] version from Cargo.toml"
  printf '%s\n' "$value"
}

refresh_lockfile() {
  if ! command -v cargo >/dev/null 2>&1; then
    echo "version: warning: cargo not found; skipped Cargo.lock refresh" >&2
    return
  fi
  # `cargo update --workspace` rewrites only the versions of workspace
  # members; third-party crates stay pinned by the lock file.
  ( cd "$ROOT" && cargo update --workspace >/dev/null )
}

check_lockfile() {
  if ! command -v cargo >/dev/null 2>&1; then
    echo "version: warning: cargo not found; skipped Cargo.lock check" >&2
    return
  fi
  ( cd "$ROOT" && cargo metadata --locked --no-deps --format-version 1 >/dev/null ) ||
    fail "Cargo.lock is out of date; run ./scripts/version.sh set $(current_version)"
  local pending
  pending="$( cd "$ROOT" && cargo update --workspace --dry-run 2>&1 )" || true
  if printf '%s\n' "$pending" | grep -Eq '(Updating|Downgrading) fg-'; then
    fail "Cargo.lock has stale workspace versions; run ./scripts/version.sh set $(current_version)"
  fi
}

check_versions() {
  local tag="${1:-}"
  local current gradle package expected

  current="$(current_version)"
  validate_version "$current"

  gradle="$(gradle_version)"
  [[ -n "$gradle" ]] || fail "fograin.version is missing from apps/compose/gradle.properties"
  [[ "$gradle" == "$current" ]] ||
    fail "Cargo.toml says $current but gradle.properties says $gradle; run ./scripts/version.sh set $current"

  expected="$(package_version_for "$current")"
  package="$(gradle_package_version)"
  [[ -n "$package" ]] || fail "fograin.packageVersion is missing from apps/compose/gradle.properties"
  [[ "$package" == "$expected" ]] ||
    fail "fograin.packageVersion is $package but this version expects $expected; run ./scripts/version.sh set $current"

  check_lockfile

  if [[ -n "$tag" ]]; then
    [[ "$tag" == v* ]] || fail "release tag '$tag' must start with v (for example v$current)"
    [[ "$(normalize "$tag")" == "$current" ]] ||
      fail "release tag $tag does not match the checked-in version $current"
  fi

  echo "version: OK ($current, installer $package${tag:+, tag $tag})"
}

set_versions() {
  local next="$1"
  validate_version "$next"
  local previous package
  previous="$(current_version)"
  package="$(package_version_for "$next")"

  local tmp
  tmp="$(mktemp "${CARGO_TOML}.XXXXXX")"
  awk -v value="$next" '
    /^\[workspace\.package\]/ { section = 1; print; next }
    /^\[/ { section = 0 }
    section && !done && /^version[[:space:]]*=/ {
      sub(/"[^"]*"/, "\"" value "\"")
      done = 1
    }
    { print }
  ' "$CARGO_TOML" > "$tmp"
  cat "$tmp" > "$CARGO_TOML"
  rm -f "$tmp"

  tmp="$(mktemp "${GRADLE_PROPS}.XXXXXX")"
  awk -v app="$next" -v pkg="$package" '
    /^fograin\.version=/ { print "fograin.version=" app; next }
    /^fograin\.packageVersion=/ { print "fograin.packageVersion=" pkg; next }
    { print }
  ' "$GRADLE_PROPS" > "$tmp"
  cat "$tmp" > "$GRADLE_PROPS"
  rm -f "$tmp"

  refresh_lockfile
  check_versions
  echo "version: $previous -> $next (installer package $package)"
  echo "version: next: ./scripts/version.sh tag --push"
}

create_tag() {
  local push=false
  case "${1:-}" in
    "") ;;
    --push) push=true ;;
    *) fail "unknown argument for tag: $1" ;;
  esac

  check_versions
  local version tag
  version="$(current_version)"
  tag="v$version"

  if [[ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]]; then
    fail "working tree has tracked changes; commit them before tagging $tag"
  fi
  if git -C "$ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    fail "tag $tag already exists"
  fi

  git -C "$ROOT" tag -a "$tag" -m "Fograin Launcher $version"
  echo "version: created $tag"
  if [[ "$push" == true ]]; then
    git -C "$ROOT" push origin "$tag"
    echo "version: pushed $tag; the Release workflow will build every platform"
  else
    echo "version: push it with: git push origin $tag"
  fi
}

command="${1:-}"
case "$command" in
  current)
    current_version
    ;;
  check)
    shift || true
    check_versions "${1:-}"
    ;;
  set)
    shift || true
    [[ $# -eq 1 ]] || fail "set requires exactly one version"
    set_versions "$1"
    ;;
  tag)
    shift || true
    create_tag "${1:-}"
    ;;
  -h|--help|help|"")
    usage
    ;;
  *)
    fail "unknown command '$command' (try --help)"
    ;;
esac