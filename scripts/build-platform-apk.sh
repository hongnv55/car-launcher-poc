#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

SDK="$(find_sdk)"
export ANDROID_SDK_ROOT="$SDK"

if [[ ! -f "$SDK/platforms/android-28/android.jar" ]]; then
  echo "Missing platforms;android-28 under $SDK" >&2
  echo "Install it with sdkmanager 'platforms;android-28' 'build-tools;30.0.3' 'platform-tools'" >&2
  exit 2
fi

KEY_DIR="${PLATFORM_KEY_DIR:-$PROJECT_ROOT/signing/aosp-test-platform}"
if [[ ! -f "$KEY_DIR/platform.pk8" || ! -f "$KEY_DIR/platform.x509.pem" ]]; then
  echo "No platform keys found; fetching the public Android 9 AOSP TEST platform key..." >&2
  "$PROJECT_ROOT/scripts/fetch-aosp-test-platform-keys.sh"
fi

# If an emulator/device is connected, fail early on a certificate mismatch.
ADB="$SDK/platform-tools/adb"
if [[ -x "$ADB" ]] && "$ADB" get-state >/dev/null 2>&1; then
  "$PROJECT_ROOT/scripts/verify-emulator-platform-cert.sh" "$KEY_DIR"
fi

cd "$PROJECT_ROOT"
./gradlew --no-daemon :app:assembleRelease

UNSIGNED="$PROJECT_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ ! -f "$UNSIGNED" ]]; then
  # AGP naming can vary slightly; pick the unsigned release artifact.
  UNSIGNED="$(find "$PROJECT_ROOT/app/build/outputs/apk/release" -maxdepth 1 -type f -name '*release*.apk' | head -n1)"
fi
[[ -f "$UNSIGNED" ]] || { echo "Unsigned release APK not found." >&2; exit 3; }

APKSIGNER="$(latest_build_tool "$SDK" apksigner)"
ZIPALIGN="$(latest_build_tool "$SDK" zipalign)"
mkdir -p "$PROJECT_ROOT/dist"
ALIGNED="$PROJECT_ROOT/dist/StackLauncherPoc-aligned.apk"
SIGNED="$PROJECT_ROOT/dist/StackLauncherPoc-platform.apk"
rm -f "$ALIGNED" "$SIGNED"

"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"
"$APKSIGNER" sign \
  --key "$KEY_DIR/platform.pk8" \
  --cert "$KEY_DIR/platform.x509.pem" \
  --out "$SIGNED" \
  "$ALIGNED"
"$APKSIGNER" verify --verbose --print-certs "$SIGNED"
rm -f "$ALIGNED"

echo
echo "APK: $SIGNED"
