#!/usr/bin/env bash
# Android build helper for the 2-core / 8 GB dev box.
#
#   ./scripts/android-build.sh fast    # Kotlin only (fastest loop)
#   ./scripts/android-build.sh apk     # debug APK
#   ./scripts/android-build.sh check   # unit tests + debug APK + fatal lint
#   ./scripts/android-build.sh full    # tests + full lint + debug + R8 release
#   ./scripts/android-build.sh run     # build + install on the attached device
#
# Gradle's daemon, configuration cache and build cache stay warm between runs.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"

mode="${1:-fast}"
shift || true

start=$(date +%s)

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
    ;;
  run)
    ./gradlew :app:assembleDebug --console=plain "$@"
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n com.ninerouter.android/.MainActivity
    ;;
  *)
    echo "usage: $0 {fast|apk|check|full|run}" >&2
    exit 2
    ;;
esac

end=$(date +%s)
echo "android-build.sh: $mode finished in $((end - start))s"
