#!/usr/bin/env bash
# Android build helper for the 2-core / 8 GB dev box.
#
#   ./scripts/android-build.sh fast    # Kotlin only (fastest loop)
#   ./scripts/android-build.sh apk     # debug APK
#   ./scripts/android-build.sh check   # unit tests + debug APK + fatal lint
#   ./scripts/android-build.sh full    # tests + full lint + debug + R8 release, staged
#   ./scripts/android-build.sh dist    # debug + R8 release, staged (no tests/lint)
#   ./scripts/android-build.sh run     # build + install on the attached device
#
# Gradle's daemon, configuration cache and build cache stay warm between runs.
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
  full)
    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain "$@"
    stage_apks
    ;;
  dist)
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
    echo "usage: $0 {fast|apk|check|full|dist|run}" >&2
    exit 2
    ;;
esac

end=$(date +%s)
echo "android-build.sh: $mode finished in $((end - start))s"
