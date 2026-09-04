#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/signing/aosp-test-platform"
mkdir -p "$OUT"

BASE="https://android.googlesource.com/platform/build/+/refs/tags/android-9.0.0_r1/target/product/security"

download_b64() {
  local name="$1"
  local out="$2"
  local tmp
  tmp="$(mktemp)"
  trap 'rm -f "$tmp"' RETURN
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$BASE/$name?format=TEXT" -o "$tmp"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$tmp" "$BASE/$name?format=TEXT"
  else
    echo "Need curl or wget." >&2
    exit 2
  fi
  base64 -d "$tmp" > "$out"
  rm -f "$tmp"
  trap - RETURN
}

download_b64 platform.pk8 "$OUT/platform.pk8"
download_b64 platform.x509.pem "$OUT/platform.x509.pem"
chmod 600 "$OUT/platform.pk8"

echo "Fetched Android 9 AOSP TEST platform key into: $OUT"
echo "These public test keys are for emulator/test images only, never production."
