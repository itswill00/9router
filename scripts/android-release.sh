#!/usr/bin/env bash
# Android release helper.
#
#   ./scripts/android-release.sh              # build + verify + print the plan (no publish)
#   ./scripts/android-release.sh --yes        # publish the GitHub release
#   ./scripts/android-release.sh --yes --prerelease
#   ./scripts/android-release.sh --skip-build  # reuse android/dist artifacts (no rebuild)
#   ./scripts/android-release.sh --allow-dirty  # skip the clean-tree check
#
# Publishes both variants (see android/API_COMPATIBILITY.md):
#   9Router-<version>-debug.apk    (debug signing, verbose)
#   9Router-<version>-release.apk  (R8-minified; same debug signing identity)
#
# Enforces the release conventions:
#   - each staged APK's embedded versionCode/versionName match build.gradle.kts
#   - the release title is "9Router Android <version>"
#   - the notes body drops its leading "# ..." heading, so the release page does
#     not show the name twice
#   - the notes record both artifact names and their SHA-256
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PUBLISH=0
PRERELEASE=0
ALLOW_DIRTY=0
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y) PUBLISH=1 ;;
    --prerelease) PRERELEASE=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

GRADLE="$ROOT/android/app/build.gradle.kts"
NOTES_SRC="$ROOT/android/RELEASE_NOTES.md"
DIST="$ROOT/android/dist"

[[ -f "$GRADLE" ]] || { echo "missing $GRADLE" >&2; exit 1; }
[[ -f "$NOTES_SRC" ]] || { echo "missing $NOTES_SRC" >&2; exit 1; }

version_name="$(grep -oE 'versionName = "[^"]+"' "$GRADLE" | awk 'NR==1' | sed -E 's/.*"([^"]+)".*/\1/')"
version_code="$(grep -oE 'versionCode = [0-9]+' "$GRADLE" | awk 'NR==1' | grep -oE '[0-9]+')"
[[ -n "$version_name" && -n "$version_code" ]] || { echo "could not read version from $GRADLE" >&2; exit 1; }

# 0.1.16-android -> 0.1.16
version="${version_name%-android}"
tag="android-v$version"
title="9Router Android $version"
debug_apk="$DIST/9Router-$version-debug.apk"
release_apk="$DIST/9Router-$version-release.apk"

echo "version: $version_name (code $version_code)"
echo "tag:     $tag"
echo "title:   $title"

if [[ $ALLOW_DIRTY -eq 0 ]]; then
  if [[ -n "$(git status --porcelain --untracked-files=normal | grep -v '^?? android/dist/')" ]]; then
    echo "working tree is not clean; commit first or pass --allow-dirty" >&2
    git status --short >&2
    exit 1
  fi
fi

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  if [[ $PUBLISH -eq 1 ]]; then
    echo "tag $tag already exists" >&2
    exit 1
  fi
  echo "note: tag $tag already exists; dry run continues" >&2
fi

command -v gh >/dev/null || { echo "gh CLI not found" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh is not authenticated" >&2; exit 1; }

# --- locate aapt2 for embedded-metadata verification ---------------------------
aapt2="$(command -v aapt2 || true)"
if [[ -z "$aapt2" ]]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk; do
    [[ -n "$sdk" ]] || continue
    candidate="$(ls -1 "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
    if [[ -n "$candidate" ]]; then aapt2="$candidate"; break; fi
  done
fi

if [[ $SKIP_BUILD -eq 1 ]]; then
  echo "==> using staged artifacts (--skip-build)"
  [[ -f "$debug_apk" && -f "$release_apk" ]] || {
    echo "missing staged artifacts; run ./scripts/android-build.sh release first" >&2
    exit 1
  }
else
  echo "==> building both variants (tests + full lint + debug + R8 release)"
  "$ROOT/scripts/android-build.sh" full
fi

verify_apk() {
  local apk="$1"
  [[ -f "$apk" ]] || { echo "missing built artifact: $apk" >&2; return 1; }
  if [[ -n "$aapt2" ]]; then
    local badging apk_code apk_name
    badging="$("$aapt2" dump badging "$apk" 2>/dev/null | awk 'NR==1')"
    apk_code="$(echo "$badging" | grep -oE "versionCode='[0-9]+'" | grep -oE '[0-9]+')"
    apk_name="$(echo "$badging" | grep -oE "versionName='[^']+'" | sed -E "s/.*'([^']+)'.*/\1/")"
    if [[ "$apk_code" != "$version_code" || "$apk_name" != "$version_name" ]]; then
      echo "APK metadata mismatch for $(basename "$apk"): apk=$apk_name/$apk_code source=$version_name/$version_code" >&2
      return 1
    fi
    echo "  $(basename "$apk"): $apk_name (code $apk_code) OK" >&2
  else
    echo "  $(basename "$apk"): aapt2 not found; skipping metadata check" >&2
  fi
  sha256sum "$apk" | awk '{print $1}'
}

echo "==> verifying artifacts"
sha_debug="$(verify_apk "$debug_apk")" || exit 1
sha_release="$(verify_apk "$release_apk")" || exit 1
echo "  debug sha256:   $sha_debug"
echo "  release sha256: $sha_release"

# --- build the release notes ---------------------------------------------------
# Drop leading blank lines and the first "# ..." heading (GitHub already shows
# the title), normalize CRLF, then append an artifact/checksum footer.
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
tr -d '\r' < "$NOTES_SRC" | awk '
  BEGIN { body = 0 }
  {
    if (!body) {
      if ($0 ~ /^[[:space:]]*$/) next
      if ($0 ~ /^# /) { next }
      body = 1
    }
    print
  }
' > "$tmp"
if ! grep -q "$version" "$tmp"; then
  echo "warning: release notes do not mention version $version" >&2
fi
{
  echo
  echo "---"
  echo
  echo "**Artifacts** (both signed with the same key; install either, switch in place)"
  echo
  echo "- \`$(basename "$debug_apk")\` — SHA-256 \`$sha_debug\`"
  echo "- \`$(basename "$release_apk")\` — SHA-256 \`$sha_release\`"
  echo
  echo "**Version:** \`$version_name\` (code $version_code)"
} >> "$tmp"

echo "==> notes preview"
sed -n '1,12p' "$tmp"
echo "..."

if [[ $PUBLISH -eq 0 ]]; then
  echo
  echo "dry run: nothing published. Re-run with --yes to create the release."
  exit 0
fi

echo "==> tagging $tag"
git tag "$tag"
git push origin "$tag"

echo "==> creating GitHub release"
prerelease_flag=()
[[ $PRERELEASE -eq 1 ]] && prerelease_flag=(--prerelease)
gh release create "$tag" \
  "$debug_apk#$(basename "$debug_apk")" \
  "$release_apk#$(basename "$release_apk")" \
  --title "$title" \
  --notes-file "$tmp" \
  "${prerelease_flag[@]}"

echo "done: $(gh release view "$tag" --json url -q .url)"
