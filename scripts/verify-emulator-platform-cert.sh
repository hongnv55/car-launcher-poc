#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"

SDK="$(find_sdk)"
ADB="$SDK/platform-tools/adb"
APKSIGNER="$(latest_build_tool "$SDK" apksigner)"
KEY_DIR="${1:-$PROJECT_ROOT/signing/aosp-test-platform}"
CERT="$KEY_DIR/platform.x509.pem"

[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 2; }
[[ -f "$CERT" ]] || { echo "Missing $CERT" >&2; exit 2; }

"$ADB" get-state >/dev/null
APK_PATH="$("$ADB" shell pm path android | sed -n 's/^package://p' | head -n1 | tr -d '\r')"
if [[ -z "$APK_PATH" ]]; then
  echo "Cannot resolve package android/framework-res.apk" >&2
  exit 3
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
"$ADB" pull "$APK_PATH" "$TMP/framework-res.apk" >/dev/null

DEVICE_DIGEST="$("$APKSIGNER" verify --print-certs "$TMP/framework-res.apk" 2>/dev/null \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 | tr '[:upper:]' '[:lower:]')"
LOCAL_DIGEST="$(openssl x509 -in "$CERT" -outform DER 2>/dev/null | sha256sum | awk '{print $1}')"

echo "Device platform cert : $DEVICE_DIGEST"
echo "Local signing cert   : $LOCAL_DIGEST"

if [[ -z "$DEVICE_DIGEST" || "$DEVICE_DIGEST" != "$LOCAL_DIGEST" ]]; then
  echo "ERROR: platform certificate mismatch." >&2
  echo "Use the platform.pk8/platform.x509.pem that signed this system image." >&2
  exit 4
fi

echo "OK: certificate matches emulator platform certificate."
