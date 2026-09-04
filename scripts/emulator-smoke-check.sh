#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
SDK="$(find_sdk)"
ADB="$SDK/platform-tools/adb"

"$ADB" get-state >/dev/null

echo "Android: $("$ADB" shell getprop ro.build.version.release | tr -d '\r') / API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
echo "ABI:     $("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "Tags:    $("$ADB" shell getprop ro.build.tags | tr -d '\r')"
echo
echo "Displays:"
"$ADB" shell dumpsys display | grep -E 'DisplayDeviceInfo|mDisplayId|StackLauncherPanel' | head -n 40 || true

echo
echo "Activity stacks:"
"$ADB" shell dumpsys activity activities | grep -E 'Stack #|Stack id=|mStackId|StackLauncher|displayId=' | head -n 80 || true
