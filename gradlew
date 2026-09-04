#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="7.5.1"
BASE="${GRADLE_USER_HOME:-$HOME/.gradle}/standalone-wrapper"
HOME_DIR="$BASE/gradle-$GRADLE_VERSION"
ZIP="$BASE/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [[ ! -x "$HOME_DIR/bin/gradle" ]]; then
  mkdir -p "$BASE"
  echo "Bootstrapping Gradle $GRADLE_VERSION..." >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$ZIP"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "Need curl or wget to bootstrap Gradle." >&2
    exit 2
  fi
  rm -rf "$HOME_DIR"
  unzip -q "$ZIP" -d "$BASE"
fi

# Gradle 7.5.1 cannot run on JDK 19+ (class file major version 65 = JDK 21).
# Prefer an explicitly configured JAVA_HOME; otherwise pick a compatible JDK.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-11-openjdk-amd64; do
    if [[ -x "$candidate/bin/java" ]]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
export JAVA_HOME

exec "$HOME_DIR/bin/gradle" "$@"
