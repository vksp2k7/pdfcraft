#!/usr/bin/env bash
set -euo pipefail
ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$ANDROID_DIR/.." && pwd)"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) to your Android SDK directory." >&2
  exit 1
fi
export ANDROID_SDK_ROOT ANDROID_HOME="$ANDROID_SDK_ROOT"
command -v java >/dev/null || { echo "A JDK is required (17 or newer)." >&2; exit 1; }

case "${1:-}" in
  "")
    source "$HOME/.cargo/env" 2>/dev/null || true
    command -v cargo >/dev/null || { echo "Rust is required (https://rustup.rs)." >&2; exit 1; }
    command -v trunk >/dev/null || { echo "Trunk is required (cargo install trunk --locked)." >&2; exit 1; }
    (cd "$ROOT/apps/pdfcraft-web" && trunk build --release)
    ;;
  --skip-web-build)
    test -f "$ROOT/dist/web/index.html" || {
      echo "No prebuilt web frontend found at $ROOT/dist/web/index.html." >&2
      exit 1
    }
    ;;
  *)
    echo "Usage: $0 [--skip-web-build]" >&2
    exit 2
    ;;
esac

cd "$ANDROID_DIR"
./gradlew --no-daemon assembleDebug

APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
OUT="$ROOT/dist/android/pdfcraft-android-0.9.0-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "Expected APK was not produced: $APK" >&2
  exit 1
fi
mkdir -p "$(dirname "$OUT")"
cp "$APK" "$OUT"
echo "APK ready: $OUT"
ls -lh "$OUT"
