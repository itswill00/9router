#!/usr/bin/env bash
# Android build helper for the 2-core / 8 GB dev box.
#
# Fast loop (seconds):
#   ./scripts/android-build.sh fast    # Kotlin only
#   ./scripts/android-build.sh apk     # debug APK
#   ./scripts/android-build.sh check   # tests + debug APK + fatal lint  <- default gate
#
# Before a release (minutes, R8 is the cost):
#   ./scripts/android-build.sh lint    # full lintDebug only (slow; run when touching UI)
#   ./scripts/android-build.sh release # debug + R8 release, staged
#   ./scripts/android-build.sh full    # tests + fatal lint + debug + R8, staged
#   ./scripts/android-build.sh dist    # debug + R8 release, staged (no tests/lint)
#   ./scripts/android-build.sh run     # build + install on the attached device
#
# `full` deliberately uses lintVitalRelease instead of a full lintDebug pass,
# which roughly doubles a cold release build on 2 cores. Run `lint` separately
# before a release when the change touched lint-sensitive Compose code.
#
# Use `check` while iterating and `full` once before publishing. Gradle's daemon,
# configuration cache and build cache stay warm between runs; killing the daemon
# makes the next build cold and slow.
#
# Staged artifacts live in android/dist/ with release-friendly names:
#   9Router-<version>-debug.apk
#   9Router-<version>-release.apk
# Both variants share the debug signing identity, so they are installable and
# update each other in place. The release build is R8-minified/shrunk.
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

stage_apks() {
  local version out
  version="$(artifact_version)"
  out="$ROOT/android/dist"
  mkdir -p "$out"
  # Drop stale artifacts so dist/ never mixes versions.
  rm -f "$out"/9Router-*.apk
  for variant in debug release; do
    local built="app/build/outputs/apk/$variant/app-$variant.apk"
    if [[ -f "$built" ]]; then
      cp -f "$built" "$out/9Router-$version-$variant.apk"
    fi
  done
  echo "staged artifacts:"
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
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintVitalRelease --console=plain "$@"
    ;;
  lint)
    ./gradlew :app:lintDebug --console=plain "$@"
    ;;
  full)
    ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintVitalRelease --console=plain "$@"
    stage_apks
    ;;
  release|dist)
    ./gradlew :app:assembleDebug :app:assembleRelease --console=plain "$@"
    stage_apks
    ;;
  run)
    ./gradlew :app:assembleDebug --console=plain "$@"
    apk="$(find app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print -quit)"
    adb install -r "$apk"
    adb shell am start -n com.ninerouter.android/.MainActivity
    ;;
  *)
    echo "usage: $0 {fast|apk|check|lint|full|release|dist|run}" >&2
    exit 2
    ;;
esac

end=$(date +%s)
echo "android-build.sh: $mode finished in $((end - start))s"
