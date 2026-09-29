#!/usr/bin/env bash
# SessionStart hook. In Claude cloud sessions only, make a Java 25 JDK available,
# because the cloud sandbox ships OpenJDK 21 and ADR-010 requires Java 25.
# On a developer machine it does nothing. It is idempotent: the JDK is downloaded
# once per sandbox and reused.
set -euo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

JDK_DIR="$HOME/.jdks/temurin-25"

if [ ! -x "$JDK_DIR/bin/java" ]; then
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64 | arm64) arch=aarch64 ;;
    *) echo "cloud-setup: unsupported architecture $(uname -m)" >&2; exit 0 ;;
  esac
  mkdir -p "$JDK_DIR"
  url="https://api.adoptium.net/v3/binary/latest/25/ga/linux/${arch}/jdk/hotspot/normal/eclipse"
  if ! curl -fsSL --retry 3 "$url" | tar -xz -C "$JDK_DIR" --strip-components=1; then
    echo "cloud-setup: JDK 25 download failed; the build will not compile on Java 21" >&2
    rm -rf "$JDK_DIR"
    exit 0
  fi
fi

# Persist for every later command in this session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JDK_DIR\""
    echo "export PATH=\"$JDK_DIR/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "cloud-setup: $("$JDK_DIR/bin/java" -version 2>&1 | head -1) at $JDK_DIR"
