#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_sdk() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    printf '%s\n' "$ANDROID_HOME"
    return
  fi
  local candidate
  for candidate in "$HOME/Android/Sdk" "$HOME/Android/sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
    if [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return
    fi
  done
  echo "Android SDK not found. Export ANDROID_SDK_ROOT." >&2
  exit 2
}

latest_build_tool() {
  local sdk="$1" tool="$2"
  local dir
  dir="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" -printf '%h\n' 2>/dev/null | sort -V | tail -n1)"
  if [[ -z "$dir" ]]; then
    echo "$tool not found under $sdk/build-tools" >&2
    exit 2
  fi
  printf '%s/%s\n' "$dir" "$tool"
}
