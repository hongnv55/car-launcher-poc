#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
SDK="$(find_sdk)"
ADB="$SDK/platform-tools/adb"
APK="$PROJECT_ROOT/dist/StackLauncherPoc-platform.apk"

[[ -f "$APK" ]] || "$PROJECT_ROOT/scripts/build-platform-apk.sh"
"$PROJECT_ROOT/scripts/verify-emulator-platform-cert.sh" "${PLATFORM_KEY_DIR:-$PROJECT_ROOT/signing/aosp-test-platform}"

"$ADB" install -r "$APK"

echo "Requested/granted privileged permissions:"
"$ADB" shell dumpsys package com.example.stacklauncherpoc \
  | grep -E 'MANAGE_ACTIVITY_STACKS|INTERNAL_SYSTEM_WINDOW|INJECT_EVENTS|granted=true' || true

echo
echo "Launching..."
"$ADB" shell am start -n com.example.stacklauncherpoc/.MainActivity
