#!/usr/bin/env bash
# Deploy one release to this host (ADR-006, docs/sdd/10-cicd-pipeline.md, docs/runbooks/deploy.md).
#
#   deploy.sh <image-tag>        tag is sha-<short> (staging) or vX.Y.Z (production)
#
# Idempotent: running it again with the tag that is already live pulls nothing new, applies no
# migration, recreates nothing, passes the health gate and records the tag again.
#
# Order, and why:
#   1. pull the three pinned images for the tag if not present (nothing is built here);
#   2. start PostgreSQL if it is not running and wait until it is healthy;
#   3. run migrations as a one-shot container, as bms_owner, BEFORE any application container
#      changes. A failed migration stops the deploy with the old version still serving;
#   4. switch api, web and proxy to the new tag;
#   5. wait for the API's readiness (database reachable, migrations at head) and for web behind
#      the proxy, up to READY_TIMEOUT seconds;
#   6. on failure, switch back to the previously recorded tag and exit non-zero. Migrations are
#      not reversed: they are expand and contract, so the previous release runs on the new schema
#      (chapter 6 section 6.9);
#   7. on success, record the tag and the time in state/.
#
# Runs from /opt/bms (the directory holding this script, compose.yml and .env). Registry login is
# done by the caller before this script runs (the deploy workflow logs in with a short-lived token).
set -euo pipefail
# Docker commands below never read the caller's stdin (it may be the SSH session).
exec < /dev/null

TAG="${1:-}"
if [[ ! "$TAG" =~ ^(sha-[0-9a-f]{7,40}|v[0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    echo "usage: deploy.sh <sha-<short>|vX.Y.Z>" >&2
    exit 2
fi

cd "$(dirname "$0")"
mkdir -p state
READY_TIMEOUT="${READY_TIMEOUT:-240}"
COMPOSE=(docker compose --project-name bms --file compose.yml)

log() { echo "[deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
record() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1 $2" >> state/history.log; }

# One deploy at a time on this host.
exec 9> state/deploy.lock
if ! flock --nonblock 9; then
    log "another deploy is running; refusing to start"
    exit 3
fi

PREVIOUS="$(cat state/current_tag 2>/dev/null || true)"
log "deploying ${TAG} (previous: ${PREVIOUS:-none})"

switch_to() {
    IMAGE_TAG="$1" "${COMPOSE[@]}" up --detach --no-deps --remove-orphans api web proxy
}

wait_ready() {
    local tag="$1" deadline=$((SECONDS + READY_TIMEOUT)) id status
    while (( SECONDS < deadline )); do
        id="$(IMAGE_TAG="$tag" "${COMPOSE[@]}" ps --quiet api || true)"
        status="$( [[ -n "$id" ]] && docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"
        if [[ "$status" == "healthy" ]] \
            && IMAGE_TAG="$tag" "${COMPOSE[@]}" exec -T proxy wget -q --spider http://web:8080/ < /dev/null \
            && IMAGE_TAG="$tag" "${COMPOSE[@]}" exec -T proxy wget -q -O /dev/null http://api:8080/readyz < /dev/null; then
            return 0
        fi
        if [[ "$status" == "unhealthy" ]]; then
            log "api reports unhealthy"
            return 1
        fi
        sleep 3
    done
    log "not ready after ${READY_TIMEOUT}s (api health: ${status:-unknown})"
    return 1
}

export IMAGE_TAG="$TAG"

# Tags are immutable (sha-<short>, vX.Y.Z), so an image already present is the same content;
# only missing ones are pulled.
log "pulling images"
"${COMPOSE[@]}" --profile migrate pull --quiet --policy missing api web proxy migrate

log "starting postgres"
"${COMPOSE[@]}" up --detach --wait postgres

log "running migrations"
if ! "${COMPOSE[@]}" --profile migrate run --rm -T migrate < /dev/null; then
    log "migration failed; application containers were not changed"
    record "$TAG" "failed-migration"
    exit 1
fi

log "switching application containers to ${TAG}"
switch_to "$TAG"

if wait_ready "$TAG"; then
    echo "$TAG" > state/current_tag
    record "$TAG" "deployed"
    log "deployed ${TAG}"
    exit 0
fi

log "health gate failed for ${TAG}; last api log lines:"
"${COMPOSE[@]}" logs --no-color --tail 40 api || true
record "$TAG" "failed-health"

if [[ -z "$PREVIOUS" || "$PREVIOUS" == "$TAG" ]]; then
    log "no previous release to roll back to"
    exit 1
fi

log "rolling back to ${PREVIOUS}"
switch_to "$PREVIOUS"
if wait_ready "$PREVIOUS"; then
    record "$PREVIOUS" "rolled-back-to"
    log "rolled back to ${PREVIOUS}; ${TAG} is NOT live"
else
    record "$PREVIOUS" "rollback-unhealthy"
    log "rollback to ${PREVIOUS} is not healthy either; see docs/runbooks/rollback.md"
fi
exit 1
