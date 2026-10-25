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
#      application deploy, whatever else changed in the compose file or .env (ADR-018 finding M4).
#      Just before each switch, the outgoing web build's hashed files are copied into the web_assets
#      volume, which the new web container keeps serving, so a phone on the previous app copy still
#      loads its screens (ADR-078). api and web switch without a gap (ADR-083): the new container
#      starts beside the old one, and the old one stops only once the new one is healthy; a new one
#      that never gets healthy is removed and the old one keeps serving. The proxy is recreated only
#      when what it runs changed (its binary, its Caddyfiles or its compose settings);
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
# Seconds the keep-assets step may take before a switch (ADR-078); the rollback allows 20.
KEEP_ASSETS_TIMEOUT="${BMS_KEEP_ASSETS_TIMEOUT:-60}"
# ADR-083: a second API JVM runs beside the old one only while enough memory is free, both on the host
# and below bms.slice's MemoryHigh where that cgroup exists (the staging Pi); otherwise the api restarts
# in place, with the short gap that Caddy's retries and the app's own retry cover. Enough is, by default,
# the api container's own memory limit plus ROLL_MARGIN_MB: the new JVM can never use more than its limit
# (its own cgroup stops it first, killing only the new container), so the overlap cannot bring the slice's
# OOM killer onto the old api or postgres whatever the JVM's start-up peak (it can still cause reclaim, since
# the inactive file cache counts as free). BMS_ROLL_MIN_FREE_MB replaces the limit
# with a measured peak (memory.peak of a new api container), at the operator's risk.
ROLL_MIN_FREE_MB="${BMS_ROLL_MIN_FREE_MB:-}"
ROLL_MARGIN_MB="${BMS_ROLL_MARGIN_MB:-64}"
SLICE_DIR="${BMS_SLICE_DIR:-/sys/fs/cgroup/bms.slice}"
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

# The cosign image runs as uid 65532 and cannot read this user's private Docker login (mode 600), so
# the registry pull fails with UNAUTHORIZED. Give it a readable copy inside the state directory,
# whose parent only this user can enter.
install -d -m 755 "$STATE_DIR/dockercfg"
if [[ -f "${DOCKER_CONFIG:-$HOME/.docker}/config.json" ]]; then
    install -m 644 "${DOCKER_CONFIG:-$HOME/.docker}/config.json" "$STATE_DIR/dockercfg/config.json"
fi

verify_signature() {
    local digest_ref="$1"
    docker run --rm \
        --volume "$COSIGN_PUBKEY:/cosign.pub:ro" \
        --volume "$STATE_DIR/dockercfg:/home/nonroot/.docker:ro" \
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

# ADR-078: run the outgoing web image once as keep-assets, which copies its hashed files into the
# web_assets volume and prunes the old ones. It runs by local image ID, and its service has
# pull_policy: never (Compose 2.26 on the staging Pi has no run --pull), so only the image web was
# created from (verified by its own deploy) can run, never a pull of a moved tag. A
# failure or a run past the time limit (the first argument, seconds) costs a phone on the old copy a
# reload, never the deploy or the rollback.
keep_outgoing_assets() {
    local limit="$1" id image="" name="bms-keep-assets-$$"
    id="$("${COMPOSE[@]}" ps --quiet web 2>/dev/null | head -n 1 || true)"
    if [[ -n "$id" ]]; then
        image="$(docker inspect --format '{{.Image}}' "$id" 2>/dev/null || true)"
    fi
    if [[ -z "$image" ]]; then
        return 0
    fi
    if ! KEEP_ASSETS_IMAGE="$image" timeout --kill-after=5 "$limit" "${COMPOSE[@]}" --profile keep-assets \
            run --rm -T --name "$name" keep-assets < /dev/null; then
        docker rm --force "$name" > /dev/null 2>&1 || true
        log "warning: the outgoing web files were not kept; a phone on that build may need a reload"
    fi
}

# Free memory in MB: the host's MemAvailable, or what bms.slice still allows below its MemoryHigh (where
# throttling and reclaim begin; MemoryMax when no MemoryHigh is set) when that is less. The slice's
# inactive file cache counts as free, since the kernel drops it before it would kill anything.
free_memory_mb() {
    local free limit current cache
    free="$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo 2>/dev/null || true)"
    free="${free:-0}"
    if [[ -r "$SLICE_DIR/memory.current" ]]; then
        limit="$(cat "$SLICE_DIR/memory.high" 2>/dev/null || true)"
        [[ "$limit" =~ ^[0-9]+$ ]] || limit="$(cat "$SLICE_DIR/memory.max" 2>/dev/null || true)"
        current="$(cat "$SLICE_DIR/memory.current")"
        cache="$(awk '$1 == "inactive_file" { print $2 }' "$SLICE_DIR/memory.stat" 2>/dev/null || true)"
        current=$(( current - ${cache:-0} ))
        if [[ "$limit" =~ ^[0-9]+$ ]] && (( (limit - current) / 1048576 < free )); then
            free=$(( (limit - current) / 1048576 ))
        fi
    fi
    echo "$free"
}

# MB a second api needs free: BMS_ROLL_MIN_FREE_MB when set, else the running api container's memory limit
# (the new one gets the same) plus ROLL_MARGIN_MB, for the second web and the old containers' growth.
roll_need_mb() {
    local limit
    if [[ -n "$ROLL_MIN_FREE_MB" ]]; then
        echo "$ROLL_MIN_FREE_MB"
        return
    fi
    limit="$(docker inspect --format '{{.HostConfig.Memory}}' "$1" 2>/dev/null || echo 0)"
    (( limit > 0 )) || limit=$(( 2048 * 1048576 ))
    echo $(( limit / 1048576 + ROLL_MARGIN_MB ))
}

# Waits until a container's health check passes; fails at once if it reports unhealthy or stops.
wait_healthy() {
    local id="$1" deadline=$((SECONDS + READY_TIMEOUT)) status
    while (( SECONDS < deadline )); do
        status="$(docker inspect --format '{{if .State.Running}}{{if .State.Health}}{{.State.Health.Status}}{{else}}healthy{{end}}{{else}}stopped{{end}}' "$id" 2>/dev/null || echo stopped)"
        case "$status" in
            healthy) return 0 ;;
            unhealthy | stopped) return 1 ;;
        esac
        sleep 2
    done
    return 1
}

# ADR-083: switch one service without a gap. The new container starts beside the running one (Compose
# names it with the next number), and the proxy finds both through Docker's DNS (deploy/caddy/upstreams.caddy).
# Once the new one is healthy the old one gets its graceful stop and is removed. A new container that
# never gets healthy is removed and the old one keeps serving, so the deploy fails with nothing changed.
# A service whose running container already runs the image asked for (an idempotent re-run) is handed to
# Compose's own `up`, which leaves it alone unless .env changed its settings. Compose 2.26.1's `config --hash`
# does not match the label its own `up` compares (found by deploy/tests/no-gap-test.sh), so it is not used.
roll() {
    local service="$1" old count new id free need image_var
    old="$("${COMPOSE[@]}" ps --quiet "$service" 2>/dev/null || true)"
    count="$(wc -w <<< "$old")"
    image_var="$(tr '[:lower:]' '[:upper:]' <<< "$service")_IMAGE"
    if (( count == 0 )) || { (( count == 1 )) \
            && [[ "$(docker inspect --format '{{.Config.Image}}' "$old")" == "${!image_var:-}" ]]; }; then
        "${COMPOSE[@]}" up --detach --no-deps "$service" || return 1
        return 0
    fi
    if [[ "$service" == api ]]; then
        free="$(free_memory_mb)" need="$(roll_need_mb "$(head -n 1 <<< "$old")")"
        if (( free < need )); then
            log "${free} MB free, a second api needs ${need} MB; restarting api in place (a short gap)"
            "${COMPOSE[@]}" up --detach --no-deps "$service" || return 1
            # As below: the freed address must not reach the next new container while the proxy still lists it.
            sleep 3
            return 0
        fi
        log "${free} MB free, a second api needs ${need} MB"
    fi
    if [[ "$service" == web ]] && ! keep_incoming_assets "${KEEP_LIMIT:-60}"; then
        # Without them, a page from the new web could ask the old one for files it does not have.
        log "warning: the incoming web files were not kept; restarting web in place (a short gap)"
        "${COMPOSE[@]}" up --detach --no-deps "$service" || return 1
        sleep 3
        return 0
    fi
    # A stopped container of the service (left by an interrupted deploy) would be started again instead of
    # a new one, so it goes first.
    for id in $("${COMPOSE[@]}" ps --all --quiet "$service"); do
        grep -qx "$id" <<< "$old" || docker rm --force "$id" > /dev/null
    done
    log "starting a new ${service} container beside the running one"
    "${COMPOSE[@]}" up --detach --no-deps --no-recreate --scale "${service}=$((count + 1))" "$service"
    new=""
    for id in $("${COMPOSE[@]}" ps --all --quiet "$service"); do
        grep -qx "$id" <<< "$old" || new="$id"
    done
    if [[ -z "$new" ]]; then
        log "no new ${service} container was started"
        return 1
    fi
    if ! wait_healthy "$new"; then
        log "the new ${service} container is not healthy; removing it, the old one keeps serving. Its last log lines:"
        docker logs --tail 40 "$new" 2>&1 || true
        docker rm --force "$new" > /dev/null 2>&1 || true
        return 1
    fi
    for id in $old; do
        # docker stop gives the container its own stop_grace_period, as Compose would.
        docker stop "$id" > /dev/null && docker rm "$id" > /dev/null
    done
    # Docker may hand the freed address to the next container started (the new web, say) while the proxy still
    # lists it under this service for up to a second (refresh 1s in upstreams.caddy), so let that pass first.
    sleep 3
    log "${service} switched"
}

# The proxy cannot run twice (it holds ports 80 and 443 on the production VM), so its image is replaced only
# when what it runs changed: its caddy binary or its Caddyfiles. Every release builds a new proxy image digest
# even when those are the same, and recreating it would stop all traffic for a moment (ADR-083). When they are
# the same, Compose's own `up` runs with the running image's reference, so it still applies a change of the
# proxy's settings in .env (recreating it, as before) and otherwise leaves it alone.
switch_proxy() {
    local id image ref running
    id="$("${COMPOSE[@]}" ps --quiet proxy 2>/dev/null | head -n 1 || true)"
    if [[ -n "$id" ]]; then
        image="$(docker inspect --format '{{.Image}}' "$id")"
        ref="$(docker inspect --format '{{.Config.Image}}' "$id")"
        running="$(proxy_fingerprint "$image")"
        if [[ -n "$running" && "$running" == "$(proxy_fingerprint "$PROXY_IMAGE")" ]]; then
            log "proxy unchanged; kept running"
            PROXY_IMAGE="$ref" "${COMPOSE[@]}" up --detach --no-deps proxy
            return
        fi
    fi
    "${COMPOSE[@]}" up --detach --no-deps proxy
}

# What the proxy runs, as one hash: its environment, command, entrypoint and user, its caddy binary and everything in
# /etc/caddy. Empty when the image cannot be read; switch_proxy never counts an empty hash as a match, so the proxy is
# then recreated as before.
proxy_fingerprint() {
    local config files
    config="$(docker image inspect --format '{{json .Config.Env}}{{json .Config.Cmd}}{{json .Config.Entrypoint}}{{.Config.User}}' "$1" 2>/dev/null)" || return 0
    files="$(docker run --rm --network none --entrypoint sh "$1" -c 'set -o pipefail; cat /usr/bin/caddy /etc/caddy/* | sha256sum' 2>/dev/null)" || return 0
    [[ -n "$config" && -n "$files" ]] || return 0
    printf '%s\n%s\n' "$config" "$files" | sha256sum | cut -d' ' -f1
}

# ADR-083: while the old and the new web container both take requests, a page loaded from the new one may ask the old
# one for the new build's hashed files. So, before the new container starts, the incoming image (WEB_IMAGE, verified
# and pulled by this deploy; pull_policy: never) copies its own hashed files into web_assets, which the old container
# also serves from /srv/previous. The same run as keep_outgoing_assets, by the incoming image instead of the outgoing.
keep_incoming_assets() {
    local limit="$1" name="bms-keep-incoming-$$"
    if ! KEEP_ASSETS_IMAGE="$WEB_IMAGE" timeout --kill-after=5 "$limit" "${COMPOSE[@]}" --profile keep-assets \
            run --rm -T --name "$name" keep-assets < /dev/null; then
        docker rm --force "$name" > /dev/null 2>&1 || true
        return 1
    fi
}

# The argument is the keep-assets steps' time limit: the rollback passes a shorter one.
switch_to() {
    local service
    KEEP_LIMIT="$1"
    keep_outgoing_assets "$1"
    for service in "${APP_SERVICES[@]}"; do
        case "$service" in
            api | web) roll "$service" || return 1 ;;
            proxy) switch_proxy || return 1 ;;
            *) "${COMPOSE[@]}" up --detach --no-deps "$service" || return 1 ;;
        esac
    done
}

wait_ready() {
    local deadline=$((SECONDS + READY_TIMEOUT)) id status
    while (( SECONDS < deadline )); do
        id="$("${COMPOSE[@]}" ps --quiet api | head -n 1 || true)"
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
if switch_to "$KEEP_ASSETS_TIMEOUT" && wait_ready; then
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
if switch_to 20 && wait_ready; then
    record "$PREVIOUS" "rolled-back-to"
    log "rolled back to ${PREVIOUS}; ${TAG} is NOT live"
else
    record "$PREVIOUS" "rollback-unhealthy"
    log "rollback to ${PREVIOUS} is not healthy either; see docs/runbooks/rollback.md"
fi
exit 1
