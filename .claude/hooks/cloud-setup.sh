#!/usr/bin/env bash
# SessionStart hook. In Claude cloud sessions only, prepare the toolchain the build needs:
#   1. a Java 25 JDK (the sandbox ships OpenJDK 21; ADR-010 requires 25), from the Ubuntu
#      archive, because the sandbox's egress policy blocks Adoptium, java.net and GitHub
#      release downloads while the Ubuntu archive and Docker Hub are allowed;
#   2. a running Docker daemon, so the Testcontainers integration tests can start PostgreSQL.
# On a developer machine it does nothing. Every step is idempotent and never fails the
# session: a problem is reported and the session continues.
set -uo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

SUDO=""
[ "$(id -u)" -eq 0 ] || SUDO="sudo -n"

jdk_home() { find /usr/lib/jvm -maxdepth 1 -name "java-25-openjdk-*" 2>/dev/null | head -1; }

if [ -z "$(jdk_home)" ]; then
  # Some preinstalled PPAs return 403 under the egress policy; the Ubuntu archive still updates.
  $SUDO apt-get update -qq >/dev/null 2>&1 || true
  if ! $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-25-jdk-headless >/dev/null 2>&1; then
    echo "cloud-setup: apt could not install openjdk-25-jdk-headless; the build will not compile on Java 21" >&2
  fi
fi

JAVA25="$(jdk_home)"
if [ -n "$JAVA25" ]; then
  if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    {
      echo "export JAVA_HOME=\"$JAVA25\""
      echo "export PATH=\"$JAVA25/bin:\$PATH\""
    } >> "$CLAUDE_ENV_FILE"
  fi
  echo "cloud-setup: $("$JAVA25/bin/java" -version 2>&1 | grep -m1 version) at $JAVA25"
fi

if ! docker info >/dev/null 2>&1; then
  ($SUDO dockerd >/tmp/dockerd.log 2>&1 &)
  for _ in $(seq 1 15); do docker info >/dev/null 2>&1 && break; sleep 1; done
fi
if docker info >/dev/null 2>&1; then
  echo "cloud-setup: docker daemon running"
else
  echo "cloud-setup: docker daemon not reachable; integration tests will fail (see /tmp/dockerd.log)" >&2
fi
exit 0
