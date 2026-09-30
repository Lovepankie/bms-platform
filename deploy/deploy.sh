#!/usr/bin/env bash
# Deploy one release to this host (ADR-006, docs/sdd/10-cicd-pipeline.md, docs/runbooks/deploy.md).
#
#   deploy.sh <image-tag>        tag is sha-<short> (staging) or vX.Y.Z (production)
#
# Idempotent: running it again with the tag that is already live pulls nothing new, applies no
# migration, recreates nothing, passes the health gate and records the tag again.
#
# Order, and why:
#   1. resolve api, web and proxy to their manifest digests and verify each one's cosign signature
#      against the committed deploy/cosign.pub (ADR-018 finding H2); refuse to go further if any
#      signature does not verify, so registry write access alone is never enough to run code here;
#   2. start PostgreSQL if it is not running and wait until it is healthy;
#   3. run migrations as a one-shot container, as bms_owner, BEFORE any application container
#      changes. A failed migration stops the deploy with the old version still serving;
#   4. switch api, web and proxy (and cloudflared, where BMS_EDGE_SERVICES names it) to the verified
#      digests. Only these named services are touched: postgres is never recreated by an
#      application deploy, whatever else changed in the compose file or .env (ADR-018 finding M4);
#   5. wait for the API's readiness (database reachable, migrations at head) and for web behind
#      the proxy, up to READY_TIMEOUT seconds;
#   6. on failure, switch back to the previously recorded tag and exit non-zero. Migrations are
#      not reversed: they are expand and contract, so the previous release runs on the new schema
#      (chapter 6 section 6.9);
#   7. on success, record the tag, its build time and the resolved digests in the state directory.
#
# Runs from /opt/bms (the directory holding this script, compose.yml and .env), or from a release
# directory named by the staging puller (deploy/pull-staging.sh), which passes BMS_STATE_DIR so the
# tag history stays shared across releases (ADR-018 finding M3). Registry login, when the packages
# are private, is done by the caller before this script runs (the production workflow logs in with
# a short-lived token; the staging host's bms user logs in once with a read:packages token,
# docs/runbooks/provision-host.md).
set -euo pipefail
# Docker commands below never read the caller's stdin (it may be the SSH session).
exec < /dev/null

TAG="${1:-}"
if [[ ! "$TAG" =~ ^(sha-[0-9a-f]{7,40}|v[0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    echo "usage: deploy.sh <sha-<short>|vX.Y.Z>" >&2
    exit 2
fi

cd "$(dirname "$0")"
IMAGE_PREFIX="ghcr.io/rincoltech-solutions-ltd/bms-platform"
STATE_DIR="${BMS_STATE_DIR:-$PWD/state}"
EDGE_SERVICES="${BMS_EDGE_SERVICES:-}"
COSIGN_IMAGE="${BMS_COSIGN_IMAGE:-ghcr.io/sigstore/cosign/cosign:v2.4.1}"
# The trusted public key never comes from the release being verified (ADR-018 finding H2): on
# staging, the puller passes the path of the copy it provisioned once by hand
# (docs/runbooks/provision-host.md), outside every release directory; on production, this default
# (next to this script) is the copy the workflow just checked out of the reviewed git history.
COSIGN_PUBKEY="${BMS_COSIGN_PUBKEY:-$PWD/cosign.pub}"
# The staging puller runs this script from releases/<tag>/, next to a compose.yml copied for this
# release only; .env is not (secrets are provisioned once, not shipped in an image), so it stays at
# the fixed path the puller passes. The flat layout (production, manual runs) needs no override.
ENV_FILE="${BMS_ENV_FILE:-$PWD/.env}"
mkdir -p "$STATE_DIR"
READY_TIMEOUT="${READY_TIMEOUT:-240}"
COMPOSE=(docker compose --project-name bms --file compose.yml --env-file "$ENV_FILE")

log() { echo "[deploy $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
record() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1 $2" >> "$STATE_DIR/history.log"; }
# Write-then-rename so a power loss never leaves a truncated or half-written state file (ADR-018
# finding L7): the old content stays readable until the new file is fully synced to disk.
write_state() {
    local file="$1" content="$2"
    printf '%s\n' "$content" > "$file.tmp"
    sync "$file.tmp" 2>/dev/null || true
    mv -f "$file.tmp" "$file"
}

# One deploy at a time on this host.
exec 9> "$STATE_DIR/deploy.lock"
if ! flock --nonblock 9; then
    log "another deploy is running; refusing to start"
    exit 3
fi

PREVIOUS="$(cat "$STATE_DIR/current_tag" 2>/dev/null || true)"
log "deploying ${TAG} (previous: ${PREVIOUS:-none})"

# ADR-018 finding H2: resolve each image to the digest actually pulled and verify its cosign
# signature before anything runs. A tag can be re-pointed at any time; a digest cannot, so
# everything downstream uses the digest, never the tag.
resolve_digest() {
    local tag_ref="$1"
    docker pull --quiet "$tag_ref" > /dev/null
    docker image inspect --format '{{ index .RepoDigests 0 }}' "$tag_ref"
}

verify_signature() {
    local digest_ref="$1"
    docker run --rm \
        --volume "$COSIGN_PUBKEY:/cosign.pub:ro" \
        --volume "${DOCKER_CONFIG:-$HOME/.docker}:/home/nonroot/.docker:ro" \
        --env DOCKER_CONFIG=/home/nonroot/.docker \
        "$COSIGN_IMAGE" verify --key /cosign.pub --insecure-ignore-tlog=true "$digest_ref" \
        > /dev/null 2>&1
}

log "resolving and verifying image signatures"
API_DIGEST="$(resolve_digest "${IMAGE_PREFIX}-api:${TAG}")"
WEB_DIGEST="$(resolve_digest "${IMAGE_PREFIX}-web:${TAG}")"
PROXY_DIGEST="$(resolve_digest "${IMAGE_PREFIX}-proxy:${TAG}")"
for ref in "$API_DIGEST" "$WEB_DIGEST" "$PROXY_DIGEST"; do
    if ! verify_signature "$ref"; then
        log "cosign signature did not verify for ${ref}; refusing to deploy"
        record "$TAG" "failed-signature"
        exit 1
    fi
done
export API_IMAGE="$API_DIGEST"
export WEB_IMAGE="$WEB_DIGEST"
export PROXY_IMAGE="$PROXY_DIGEST"

# ADR-018 finding L1: the pointer has no ordering of its own (it is just a tag someone moved), so
# refuse a release built before the one already live. This is a deliberate deploy of an older
# build, for example a hotfix branch, until an operator creates state/allow_downgrade.
API_CREATED="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.created" }}' "$API_DIGEST")"
CURRENT_CREATED="$(cat "$STATE_DIR/current_created" 2>/dev/null || true)"
if [[ -n "$CURRENT_CREATED" && -n "$API_CREATED" && "$API_CREATED" < "$CURRENT_CREATED" \
      && ! -e "$STATE_DIR/allow_downgrade" ]]; then
    log "refusing ${TAG} (built ${API_CREATED}): older than the live release (built ${CURRENT_CREATED})"
    log "touch ${STATE_DIR}/allow_downgrade to deploy it anyway"
    record "$TAG" "refused-downgrade"
    exit 1
fi

# The named application services this deploy switches; postgres is never in this list, so an
# application deploy never recreates it, whatever else changed in compose.yml or .env (ADR-018
# finding M4). Rollback uses the same list.
APP_SERVICES=(api web proxy)
if [ -n "$EDGE_SERVICES" ]; then
    # BMS_EDGE_SERVICES is a host-controlled, space-separated list of extra services (for example
    # cloudflared on the ARM64 staging host) that this deploy also switches.
    # shellcheck disable=SC2206
    APP_SERVICES+=($EDGE_SERVICES)
fi

switch_to() {
    "${COMPOSE[@]}" up --detach --no-deps "${APP_SERVICES[@]}"
}

wait_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT)) id status
    while (( SECONDS < deadline )); do
        id="$("${COMPOSE[@]}" ps --quiet api || true)"
        status="$( [[ -n "$id" ]] && docker inspect --format '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)"
        if [[ "$status" == "healthy" ]] \
            && "${COMPOSE[@]}" exec -T proxy wget -q --spider http://web:8080/ < /dev/null \
            && "${COMPOSE[@]}" exec -T proxy wget -q -O /dev/null http://api:8080/readyz < /dev/null; then
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

log "starting postgres"
"${COMPOSE[@]}" up --detach --wait postgres

log "running migrations"
if ! "${COMPOSE[@]}" --profile migrate run --rm -T migrate < /dev/null; then
    log "migration failed; application containers were not changed"
    record "$TAG" "failed-migration"
    exit 1
fi

log "switching application containers to ${TAG}"
switch_to

if wait_ready; then
    write_state "$STATE_DIR/current_tag" "$TAG"
    write_state "$STATE_DIR/current_created" "$API_CREATED"
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
# The previous release's own digests: re-resolving and re-verifying here would also re-pull, which
# is unnecessary for images already live; the previous deploy already verified them.
previous_api_digest="$(docker image inspect --format '{{ index .RepoDigests 0 }}' "${IMAGE_PREFIX}-api:${PREVIOUS}" 2>/dev/null || true)"
if [[ -z "$previous_api_digest" ]]; then
    log "the previous release's images are no longer on this host; cannot roll back automatically"
    record "$PREVIOUS" "rollback-unavailable"
    exit 1
fi
previous_web_digest="$(docker image inspect --format '{{ index .RepoDigests 0 }}' "${IMAGE_PREFIX}-web:${PREVIOUS}")"
previous_proxy_digest="$(docker image inspect --format '{{ index .RepoDigests 0 }}' "${IMAGE_PREFIX}-proxy:${PREVIOUS}")"
export API_IMAGE="$previous_api_digest"
export WEB_IMAGE="$previous_web_digest"
export PROXY_IMAGE="$previous_proxy_digest"
switch_to
if wait_ready; then
    record "$PREVIOUS" "rolled-back-to"
    log "rolled back to ${PREVIOUS}; ${TAG} is NOT live"
else
    record "$PREVIOUS" "rollback-unhealthy"
    log "rollback to ${PREVIOUS} is not healthy either; see docs/runbooks/rollback.md"
fi
exit 1
