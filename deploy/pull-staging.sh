#!/usr/bin/env bash
# Pull-based deploy for the ARM64 staging host (ADR-018, docs/runbooks/deploy.md). GitHub cannot
# reach this host, so the host asks: bms-pull.timer runs this script every two minutes as the
# unprivileged bms user.
#
#   1. Pull ghcr.io/rincoltech-solutions-ltd/bms-platform-api:staging, the pointer tag CI moves after
#      every green build of main, and read two labels of its image: org.opencontainers.image.version
#      (the immutable sha-<short> tag) and org.opencontainers.image.revision (the full commit).
#      An unchanged pointer is a manifest check only; nothing is downloaded.
#   2. If that tag is already live (state/current_tag), stop. If it failed before
#      (state/last_failed_tag), stop too: a broken release is not retried every two minutes.
#   3. Copy that release's host files out of its proxy image (/usr/share/bms-deploy, built from the
#      repository's deploy/ at the same commit) and install them here: compose.pi-staging.yml as
#      compose.yml, the scripts, postgres/, sql/ and systemd/. The host's files therefore always
#      match the release, as they did with the SSH deploy, and the host needs no GitHub access
#      (the repository is private).
#   4. Run deploy.sh <sha-tag>: migrations before the swap, readiness gate, automatic rollback.
#   5. On success, keep the images of this and the previous release and remove older ones (the SD
#      card is small).
#
# Exit status: 0 nothing to do or deployed; non-zero a failed deploy (see state/history.log).
# To retry a failed tag: rm state/last_failed_tag, or run ./deploy.sh <tag> by hand.
set -euo pipefail
exec < /dev/null

# The release images compose.yml runs; the pointer lives in the same repositories unless overridden.
RELEASE_PREFIX="ghcr.io/rincoltech-solutions-ltd/bms-platform"
POINTER_PREFIX="${BMS_POINTER_PREFIX:-$RELEASE_PREFIX}"
POINTER="${BMS_POINTER_TAG:-staging}"
COMPOSE_SOURCE="${BMS_COMPOSE_SOURCE:-compose.pi-staging.yml}"

cd "$(dirname "$0")"
mkdir -p state

log() { echo "[pull $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }

# One run at a time; a run that finds the previous one still deploying just ends.
exec 8> state/pull.lock
if ! flock --nonblock 8; then
    log "previous run still in progress"
    exit 0
fi

pointer_image="${POINTER_PREFIX}-api:${POINTER}"
docker pull --quiet "$pointer_image" > /dev/null
label() { docker image inspect --format "{{ index .Config.Labels \"$1\" }}" "$pointer_image"; }
tag="$(label org.opencontainers.image.version)"
revision="$(label org.opencontainers.image.revision)"

if [[ ! "$revision" =~ ^[0-9a-f]{40}$ || "$tag" != "sha-${revision:0:7}" ]]; then
    log "pointer ${pointer_image} has unexpected labels (version '${tag}', revision '${revision}')"
    exit 1
fi

current="$(cat state/current_tag 2>/dev/null || true)"
if [[ "$tag" == "$current" ]]; then
    exit 0
fi
if [[ "$tag" == "$(cat state/last_failed_tag 2>/dev/null || true)" ]]; then
    exit 0
fi
log "pointer ${POINTER} is ${tag} (live: ${current:-none}); installing host files of ${revision}"

proxy_image="${RELEASE_PREFIX}-proxy:${tag}"
docker image inspect "$proxy_image" > /dev/null 2>&1 || docker pull --quiet "$proxy_image" > /dev/null
work="$(mktemp -d)"
container="$(docker create "$proxy_image")"
trap 'docker rm "$container" > /dev/null 2>&1 || true; rm -rf "$work"' EXIT
docker cp "$container:/usr/share/bms-deploy" "$work/deploy" > /dev/null
src="$work/deploy"

# Replace files by rename, so a running script (this one included) keeps its old copy.
install -m 644 "$src/$COMPOSE_SOURCE" compose.yml.new && mv -f compose.yml.new compose.yml
for script in deploy.sh backup.sh pull-staging.sh; do
    install -m 755 "$src/$script" "$script.new" && mv -f "$script.new" "$script"
done
# Copied over in place: postgres/initdb is bind-mounted into the running database container.
for dir in postgres sql systemd; do
    mkdir -p "$dir" && cp -R "$src/$dir/." "$dir/"
done

status=0
./deploy.sh "$tag" || status=$?
case "$status" in
    0)
        rm -f state/last_failed_tag
        ;;
    3)
        log "another deploy holds the lock; trying again on the next run"
        exit 0
        ;;
    *)
        echo "$tag" > state/last_failed_tag
        log "deploy of ${tag} failed (exit ${status}); not retried until state/last_failed_tag is removed"
        exit "$status"
        ;;
esac

log "removing release images other than ${tag} and ${current:-none}"
docker image ls --format '{{.Repository}}:{{.Tag}}' \
    | grep -E "^${RELEASE_PREFIX//./\\.}-(api|web|proxy):sha-[0-9a-f]+$" \
    | while read -r ref; do
        if [[ "${ref##*:}" != "$tag" && "${ref##*:}" != "$current" ]]; then
            docker image rm "$ref" > /dev/null || true
        fi
    done
docker image prune --force > /dev/null || true
