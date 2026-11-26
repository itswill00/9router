#!/usr/bin/env bash
# Android build helper for the 2-core / 8 GB dev box.
#
# Fast loop:
#   ./scripts/android-build.sh fast    # Kotlin only
#   ./scripts/android-build.sh check   # unit tests + fatal lint (no APK)
#
# Release (single R8 variant):
#   ./scripts/android-build.sh release # R8 release APK + stage
#   ./scripts/android-build.sh full    # tests + fatal lint + R8 release + stage
#   ./scripts/android-build.sh lint    # full lintDebug only (slow, optional)
#   ./scripts/android-build.sh dist    # alias for release
#
# Device-only debug builds:
#   ./scripts/android-build.sh apk     # debug APK (not published)
#   ./scripts/android-build.sh run     # build + install debug on the device
#
# Releases ship ONE artifact in android/dist/:
#   9Router-<version>-release.apk
# It keeps the shared sideloading signing identity so in-app updates install
# in place. The debug variant is a local development tool only and is never
# attached to a GitHub release.
#
# Use `check` while iterating and `release` when cutting a release. Gradle's
# daemon, configuration cache and build cache stay warm between runs; killing
# the daemon makes the next build cold and slow.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"

mode="${1:-fast}"
shift || true

start=$(date +%s)

artifact_version() {
  grep -oE 'versionName = "[^"]+"' app/build.gradle.kts \
    | awk 'NR==1' \
    | sed -E 's/.*"([^"]+)".*/\1/' \
    | sed 's/-android$//'
}

stage_release() {
  local version out
  version="$(artifact_version)"
  out="$ROOT/android/dist"
  mkdir -p "$out"
  # Drop stale artifacts so dist/ never mixes versions or variants.
  rm -f "$out"/9Router-*.apk
  local built="app/build/outputs/apk/release/app-release.apk"
  if [[ -f "$built" ]]; then
    cp -f "$built" "$out/9Router-$version-release.apk"
  fi
  echo "staged artifact:"
  find "$out" -maxdepth 1 -name '9Router-*.apk' -printf '  %p\n' 2>/dev/null | sort
}

case "$mode" in
  fast)
    ./gradlew :app:compileDebugKotlin --console=plain "$@"
    ;;
  apk)
    ./gradlew :app:assembleDebug --console=plain "$@"
    ;;
  check)
    ./gradlew :app:testDebugUnitTest :app:lintVitalRelease --console=plain "$@"
    ;;
  lint)
    ./gradlew :app:lintDebug --console=plain "$@"
    ;;
  release|dist)
    ./gradlew :app:assembleRelease --console=plain "$@"
    stage_release
    ;;
  full)
    ./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease --console=plain "$@"
    stage_release
    ;;
  run)
    ./gradlew :app:assembleDebug --console=plain "$@"
    apk="$(find app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print -quit)"
    adb install -r "$apk"
    adb shell am start -n com.ninerouter.android/.MainActivity
    ;;
  *)
    echo "usage: $0 {fast|check|lint|release|dist|full|apk|run}" >&2
    exit 2
    ;;
esac

end=$(date +%s)
echo "android-build.sh: $mode finished in $((end - start))s"
