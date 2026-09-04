#!/usr/bin/env bash
# Builds app/libs/framework-stubs.jar from framework-stubs/src.
#
# These are compile-time-only declarations for @hide framework classes that are
# missing from the public API-28 android.jar but present at runtime on the target
# image. The jar is wired in as `compileOnly`, so nothing here lands in the APK.
set -euo pipefail
source "$(dirname "$0")/common.sh"

SDK="$(find_sdk)"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
[[ -f "$ANDROID_JAR" ]] || { echo "Missing $ANDROID_JAR" >&2; exit 2; }

SRC_DIR="$PROJECT_ROOT/framework-stubs/src"
OUT_JAR="$PROJECT_ROOT/app/libs/framework-stubs.jar"
BUILD_DIR="$PROJECT_ROOT/framework-stubs/build/classes"

# Needs a full JDK, not a JRE: the java-17 install here ships no javac. JDK 8 is
# preferred because -bootclasspath and -target 8 are native there.
JAVAC=""
JAR=""
for candidate in \
    "${STUBS_JDK_HOME:-}" \
    /usr/lib/jvm/java-8-openjdk-amd64 \
    /usr/lib/jvm/java-11-openjdk-amd64 \
    /usr/lib/jvm/java-17-openjdk-amd64 \
    /usr/lib/jvm/java-21-openjdk-amd64; do
  if [[ -n "$candidate" && -x "$candidate/bin/javac" && -x "$candidate/bin/jar" ]]; then
    JAVAC="$candidate/bin/javac"
    JAR="$candidate/bin/jar"
    break
  fi
done
[[ -n "$JAVAC" ]] || { echo "No JDK with javac+jar found; set STUBS_JDK_HOME." >&2; exit 2; }

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$(dirname "$OUT_JAR")"

mapfile -t SOURCES < <(find "$SRC_DIR" -name '*.java')
[[ ${#SOURCES[@]} -gt 0 ]] || { echo "No stub sources under $SRC_DIR" >&2; exit 2; }

# -source/-target 8 to match the app's compileOptions.
"$JAVAC" \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD_DIR" \
  "${SOURCES[@]}" 2>&1 | grep -v 'bootstrap class path' || true

"$JAR" cf "$OUT_JAR" -C "$BUILD_DIR" .

echo "Stub jar: $OUT_JAR"
"$JAR" tf "$OUT_JAR"
