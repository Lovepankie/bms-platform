#!/usr/bin/env bash
# Test for issue #496 (ADR-083): requests through the proxy keep getting 200 while deploy.sh switches api and web to a
# new release, while it rolls back, and while a release whose api never starts is refused. Needs Docker and nothing else:
# no registry, no cosign, no database. It builds small stand-ins: an api that, like the JVM, opens its port only a few
# seconds after it starts and closes it at once on a graceful stop; web builds from frontend/Caddyfile; and proxy images
# that run the real deploy/caddy/Caddyfile.tunnel and upstreams.caddy. It then runs deploy.sh's own switch_to (copied out
# of the script) against the real compose.yml and compose.pi-staging.yml while a client on the proxy's network sends GET
# and POST requests every 50 ms and records every status. It also checks that an unchanged proxy is kept running, a
# changed one is recreated, and that with too little free memory the api restarts in place instead. The staging Pi's
# file runs under Compose 2.26.1, the version on the Pi (#533), downloaded and checked against its published SHA-256;
# the production file runs under the runner's own Compose. A static check first proves both databases accept two API
# pools at once.
# Run: bash deploy/tests/no-gap-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
ID="nogap$$"
FAILURES=0

log() { echo "[test] $*"; }
ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILURES=$(( FAILURES + 1 )); }

# compose.yml and compose.pi-staging.yml get projects of their own.
project_of() { local name="${1%.yml}"; echo "${name//./-}"; }

cleanup() {
    docker rm --force "$ID-client" > /dev/null 2>&1 || true
    for file in compose.yml compose.pi-staging.yml; do
        docker compose --project-name "$ID-$(project_of "$file")" --file "$REPO/deploy/$file" \
            --env-file "$WORK/env" down --volumes --remove-orphans --timeout 1 > /dev/null 2>&1 || true
    done
    docker image rm "$ID-api:v1" "$ID-api:v2" "$ID-api:v3" "$ID-web:v1" "$ID-web:v2" \
        "$ID-proxy:p1" "$ID-proxy:p2" "$ID-proxy:p3" > /dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# Static checks first: every route of both Caddyfiles goes through the shared upstream, and the production Caddyfile
# still adapts (without its DNS-01 TLS block, whose Cloudflare module only the release image has).
for file in Caddyfile Caddyfile.tunnel; do
    if grep -nE '^[^#]*reverse_proxy +(api|web):' "$REPO/deploy/caddy/$file"; then
        echo "FAIL $file proxies to a fixed address instead of importing upstream (ADR-083)"
        exit 1
    fi
done
mkdir -p "$WORK/caddy"
cp "$REPO/deploy/caddy/Caddyfile.tunnel" "$REPO/deploy/caddy/upstreams.caddy" "$WORK/caddy/"
grep -v 'import wildcard_tls' "$REPO/deploy/caddy/Caddyfile" > "$WORK/caddy/Caddyfile"
chmod -R a+rX "$WORK"
if docker run --rm -v "$WORK/caddy:/c:ro" -w /c -e BMS_DNS_ZONE=example.test -e BMS_CALLBACK_HOST=callbacks.example.test \
        -e ACME_EMAIL=ops@example.test caddy:2.11-alpine \
        sh -c 'caddy adapt --config Caddyfile --adapter caddyfile && caddy adapt --config Caddyfile.tunnel --adapter caddyfile' \
        > /dev/null 2> "$WORK/adapt.log"; then
    ok "both Caddyfiles adapt with the shared upstream"
else
    fail "a Caddyfile does not adapt:"; sed 's/^/       /' "$WORK/adapt.log"
fi

# Two APIs run at once during a switch, each with its own pool (BMS_DB_POOL_SIZE): max_connections must hold both, plus
# PostgreSQL's 3 reserved superuser connections and 5 for the backup, the monitor, a migrate and an operator's psql.
for file in compose.yml compose.pi-staging.yml; do
    pool="$(grep -oE 'BMS_DB_POOL_SIZE:-[0-9]+' "$REPO/deploy/$file" | head -n 1 | cut -d- -f2)"
    max="$(grep -oE 'max_connections=[0-9]+' "$REPO/deploy/$file" | head -n 1 | cut -d= -f2)"
    if [[ -n "$pool" && -n "$max" ]] && (( 2 * pool + 3 + 5 <= max )); then
        ok "$file: max_connections $max holds two API pools of $pool"
    else
        fail "$file: max_connections ${max:-?} does not hold two API pools of ${pool:-?} plus 8"
    fi
done

# Compose 2.26.1 for the staging file, as on the Pi.
case "$(uname -m)" in
    x86_64) arch=x86_64 sum=2f61856d1b8c9de29ffdaedaa1c6d0a5fc5c79da45068f1f4310feed8d3a3f61 ;;
    aarch64 | arm64) arch=aarch64 sum=c86efb0d6347b72af6690f06fbd30ed17023fe67e23e28647cacb4b3f6bfb451 ;;
    *) echo "FAIL no pinned Compose 2.26.1 for $(uname -m)"; exit 1 ;;
esac
curl -fsSL -o "$WORK/docker-compose-2.26.1" \
    "https://github.com/docker/compose/releases/download/v2.26.1/docker-compose-linux-$arch" \
    || { echo "FAIL could not download Compose 2.26.1"; exit 1; }
echo "$sum  $WORK/docker-compose-2.26.1" | sha256sum --check --quiet || { echo "FAIL Compose 2.26.1 checksum"; exit 1; }
chmod +x "$WORK/docker-compose-2.26.1"
"$WORK/docker-compose-2.26.1" version | grep -q 'v2.26.1' || { echo "FAIL Compose 2.26.1 does not run"; exit 1; }

# The api stand-in: the port opens START_DELAY (8) seconds after start, as the JVM's does, and /readyz answers 200 from
# then on. CRASH=1 is a release that never starts. Caddy closes its port the moment a graceful stop begins, as
# Spring Boot's graceful shutdown does.
build_api() {
    local v="$1" crash="$2" dir="$WORK/api$1"
    mkdir -p "$dir"
    cat > "$dir/Caddyfile" <<'EOF'
{
	admin off
	auto_https off
}
:8080 {
	respond /readyz "ready" 200
	# Takes the request, then closes the connection with no answer, as an api stopped mid-request does.
	@abort path /api/abort
	abort @abort
	respond "api {$BUILD}" 200
}
EOF
    cat > "$dir/Dockerfile" <<EOF
FROM caddy:2.11-alpine
COPY Caddyfile /etc/caddy/Caddyfile
ENV BUILD=$v CRASH=$crash START_DELAY=8
ENTRYPOINT ["sh", "-c", "sleep \"\$START_DELAY\"; [ \"\$CRASH\" = 1 ] && exit 1; exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]
EOF
    docker build --quiet --tag "$ID-api:$v" "$dir" > /dev/null
}

# The runtime stage of frontend/Dockerfile with a fabricated one-page build.
build_web() {
    local v="$1" dir="$WORK/web$1"
    mkdir -p "$dir/dist/assets"
    cp "$REPO/frontend/Caddyfile" "$dir/Caddyfile"
    printf '<!doctype html><title>web %s</title><script type="module" src="/assets/index-%s.js"></script>\n' "$v" "$v" > "$dir/dist/index.html"
    printf 'export const build = "%s"\n' "$v" > "$dir/dist/assets/index-$v.js"
    cat > "$dir/Dockerfile" <<'EOF'
FROM caddy:2.11-alpine
COPY --chmod=644 Caddyfile /etc/caddy/Caddyfile
COPY --chown=10001:10001 dist /srv
USER 10001:10001
EOF
    docker build --quiet --tag "$ID-web:$v" "$dir" > /dev/null
}

# Proxy images: p2 holds the same caddy and Caddyfiles as p1 under a new image ID (as every release's proxy does); p3
# adds a file to /etc/caddy, as a Caddyfile change would. The test mounts the real Caddyfiles over /etc/caddy/bms.
build_proxy() {
    local v="$1" dir="$WORK/proxy$1"
    mkdir -p "$dir"
    printf 'FROM caddy:2.11-alpine\nLABEL test.release=%s\n' "$v" > "$dir/Dockerfile"
    if [[ "$v" == p3 ]]; then
        echo '# changed' > "$dir/changed.caddy"
        echo 'COPY changed.caddy /etc/caddy/changed.caddy' >> "$dir/Dockerfile"
    fi
    docker build --quiet --tag "$ID-proxy:$v" "$dir" > /dev/null
}

cat > "$WORK/env" <<'EOF'
API_IMAGE=unused.invalid/api:sha-0000000
WEB_IMAGE=unused.invalid/web:sha-0000000
PROXY_IMAGE=unused.invalid/proxy:sha-0000000
POSTGRES_IMAGE=unused.invalid/postgres:16
CLOUDFLARED_IMAGE=unused.invalid/cloudflared:1
POSTGRES_SUPERUSER_PASSWORD=test-placeholder
BMS_OWNER_PASSWORD=test-placeholder
BMS_APP_PASSWORD=test-placeholder
BMS_TENANT_HOST_PATTERN={slug}.bms.example.test
BMS_PLATFORM_HOST=bms.example.test
BMS_DNS_ZONE=example.test
BMS_CALLBACK_HOST=callbacks.bms.example.test
ACME_EMAIL=ops@bms.example.test
CLOUDFLARE_API_TOKEN=test-placeholder
CLOUDFLARE_TUNNEL_TOKEN=test-placeholder
BMS_TOKEN_SIGNING_JWK=test-placeholder
BMS_DATA_KEY=test-placeholder
R2_ENDPOINT=https://r2.example.test
R2_DOCUMENTS_ACCESS_KEY_ID=test-placeholder
R2_DOCUMENTS_SECRET_ACCESS_KEY=test-placeholder
R2_DOCUMENTS_BUCKET=test-placeholder
EOF

# The functions under test, exactly as deploy.sh defines them. The staging Pi runs Compose 2.26.1 (#533): everything
# they call exists there (up --scale --no-recreate, ps --all), and the Pi's file below runs under that version.
eval "$(sed -n '/^log() {/p; /^\(keep_outgoing_assets\|keep_incoming_assets\|free_memory_mb\|roll_need_mb\|wait_healthy\|roll\|switch_proxy\|proxy_fingerprint\|switch_to\)() {/,/^}/p' "$REPO/deploy/deploy.sh")"
for fn in log roll switch_proxy switch_to; do
    declare -F "$fn" > /dev/null || { echo "FAIL could not read $fn out of deploy/deploy.sh"; exit 1; }
done
# shellcheck disable=SC2034 # read by the functions copied out of deploy.sh above
READY_TIMEOUT=60 KEEP_ASSETS_TIMEOUT=20 ROLL_MIN_FREE_MB=0 ROLL_MARGIN_MB=64 SLICE_DIR=/nonexistent APP_SERVICES=(api web proxy)

log "building the stand-in images"
{ build_api v1 0 && build_api v2 0 && build_api v3 1 && build_web v1 && build_web v2 \
    && build_proxy p1 && build_proxy p2 && build_proxy p3; } || { echo "FAIL docker build"; exit 1; }

# release <api> <web> <proxy>: what deploy.sh exports after it verifies a release.
release() { export API_IMAGE="$ID-api:$1" WEB_IMAGE="$ID-web:$2" PROXY_IMAGE="$ID-proxy:$3"; }

# One request through the proxy from the client network; prints the body.
ask() {
    docker run --rm --network "$NET" caddy:2.11-alpine wget -q -O - "http://proxy:8080$1" 2> /dev/null
}
wait_answer() {
    local _
    for _ in $(seq 1 40); do
        ask "$1" | grep -q "$2" && return 0
        sleep 1
    done
    return 1
}

# The status line and headers of one POST through the proxy from the client network (curl: busybox wget hides the
# headers of an error answer).
headers_of() {
    docker run --rm --network "$NET" curlimages/curl:8.10.1 -s -o /dev/null -D - -X POST -d x "http://proxy:8080$1" 2>&1 || true
}

# A client that sends a GET to the api, a GET to the PWA, a GET of the hashed file that page names and a POST to the api
# every 50 ms and prints each status.
start_client() {
    docker rm --force "$ID-client" > /dev/null 2>&1 || true
    docker run -d --name "$ID-client" --network "$NET" caddy:2.11-alpine sh -c '
        status() {
            started="$(date +%s)"
            code="$(wget -S -q -O /dev/null "$@" 2>&1 | grep -o "HTTP/1\.[01] [0-9]*" | tail -n 1 | cut -d " " -f 2)"
            echo "${code:-none} $(( $(date +%s) - started ))s"
        }
        while :; do
            echo "GET /api/v1/me $(status http://proxy:8080/api/v1/me)"
            echo "GET / $(status http://proxy:8080/)"
            # The hashed file the page just served names, which during a web switch may come from the other container.
            asset="$(wget -q -O - http://proxy:8080/ 2> /dev/null | grep -o "/assets/index-v[0-9]*\.js" | head -n 1)"
            echo "GET the asset of the page $(status "http://proxy:8080${asset:-/assets/missing.js}")"
            echo "POST /api/v1/retail/transfers $(status --post-data=x http://proxy:8080/api/v1/retail/transfers)"
            sleep 0.05
        done' > /dev/null
}
# stop_client <name>: every request the client sent since start_client answered 200 within 3 seconds, and there were
# enough of them. The time matters as much as the status: with no api up, Caddy holds a request for up to 30 seconds
# and may still answer 200 once one comes back, which is a gap all the same. The api stand-in takes 8 seconds to open.
stop_client() {
    local name="$1" out total bad
    out="$(docker logs "$ID-client" 2>&1)"
    docker rm --force "$ID-client" > /dev/null 2>&1
    total="$(grep -c . <<< "$out")"
    bad="$(grep -vE ' 200 [0-3]s$' <<< "$out" || true)"
    if [[ -z "$bad" && "$total" -ge 30 ]]; then
        ok "$name: all $total requests answered 200 within 3 seconds"
    else
        fail "$name: $(grep -c . <<< "$bad") of $total requests did not answer 200 within 3 seconds:"
        sort <<< "$bad" | uniq -c | sed 's/^/       /'
    fi
}
ids() { "${COMPOSE[@]}" ps --all --quiet "$1" | sort | tr '\n' ' '; }

for file in compose.yml compose.pi-staging.yml; do
    project="$ID-$(project_of "$file")"
    if [[ "$file" == compose.pi-staging.yml ]]; then
        COMPOSE=("$WORK/docker-compose-2.26.1" --project-name "$project" --file "$REPO/deploy/$file" --env-file "$WORK/env")
        log "$file under Compose $("$WORK/docker-compose-2.26.1" version --short)"
    else
        COMPOSE=(docker compose --project-name "$project" --file "$REPO/deploy/$file" --env-file "$WORK/env")
        log "$file under Compose $(docker compose version --short)"
    fi
    # The api stand-in has no bash for the real probe; the probe asks the same /readyz. The proxy runs the real tunnel
    # Caddyfile and upstreams.caddy, mounted beside the image's own /etc/caddy so the fingerprint still reads the image.
    if [[ "$file" == compose.pi-staging.yml ]]; then
        NET="${project}_edge"
        reset='    cgroup_parent: !reset ""'
        # The stand-in is caddy, whose binary carries a file capability that cap_drop: [ALL] refuses to exec.
        api_extra='    cap_add: [NET_BIND_SERVICE]'
        proxy_extra=''
    else
        NET="${project}_default"
        reset=''
        api_extra=''
        proxy_extra='    ports: !reset []
    environment: !reset {}'
    fi
    cat > "$WORK/override.yml" <<EOF
services:
  api:
$reset
$api_extra
    healthcheck: !override
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/readyz"]
      interval: 1s
      timeout: 2s
      retries: 3
      start_period: 30s
  web:
$reset
  keep-assets:
$reset
  proxy:
$reset
$proxy_extra
    command: ["caddy", "run", "--config", "/etc/caddy/bms/Caddyfile.tunnel", "--adapter", "caddyfile"]
    volumes: !override
      - $WORK/caddy:/etc/caddy/bms:ro
EOF
    # An empty line where nothing is reset is still valid YAML; drop it for readability in failures.
    sed -i '/^$/d' "$WORK/override.yml"
    COMPOSE+=(--file "$WORK/override.yml")

    release v1 v1 p1
    switch_to 20 > "$WORK/switch.log" 2>&1 || { fail "$file: the first release did not start"; cat "$WORK/switch.log"; continue; }
    wait_answer /api/v1/me 'api v1' || { fail "$file: the first release does not answer"; continue; }
    # A request the api took and then dropped may have been saved: a bare 502, never the not-delivered marker.
    out="$(headers_of /api/abort)"
    if grep -q ' 502' <<< "$out" && ! grep -qi 'Bms-Not-Delivered' <<< "$out"; then
        ok "$file: a request the api took and dropped answers a bare 502"
    else
        fail "$file: a request the api took and dropped:"; printf '       %s\n' "$out"
    fi
    proxy_id="$(ids proxy)"

    # A new release: api and web switch beside the old ones, the proxy (same content, new image ID) stays.
    start_client
    sleep 2
    release v2 v2 p2
    switch_to 20 > "$WORK/switch.log" 2>&1 || { fail "$file: the switch to release 2 failed"; cat "$WORK/switch.log"; }
    sleep 3
    stop_client "$file: during the switch to release 2"
    if [[ "$(ask /api/v1/me)" == 'api v2' ]] && ask / | grep -q 'web v2'; then ok "$file: release 2 is live"; else fail "$file: release 2 is not live"; fi
    for service in api web; do
        n="$("${COMPOSE[@]}" ps --all --quiet "$service" | wc -l)"
        if (( n == 1 )); then ok "$file: one $service container is left"; else fail "$file: $n $service containers are left"; fi
    done
    if [[ "$(ids proxy)" == "$proxy_id" ]] && grep -q 'proxy unchanged; kept running' "$WORK/switch.log"; then
        ok "$file: an unchanged proxy is kept running"
    else
        fail "$file: the unchanged proxy was recreated:"; sed 's/^/       /' "$WORK/switch.log"
    fi

    # The same release again (an idempotent re-run) recreates nothing.
    before="$(ids api)$(ids web)$(ids proxy)"
    switch_to 20 > "$WORK/switch.log" 2>&1 || fail "$file: re-running release 2 failed"
    if [[ "$(ids api)$(ids web)$(ids proxy)" == "$before" ]]; then ok "$file: a re-run recreates nothing"; else fail "$file: a re-run recreated a container"; fi

    # A release whose api never starts: refused, its container removed, release 2 serves throughout.
    start_client
    sleep 2
    release v3 v2 p2
    if switch_to 20 > "$WORK/switch.log" 2>&1; then
        fail "$file: a release whose api never starts was switched to"
    else
        ok "$file: a release whose api never starts is refused"
    fi
    stop_client "$file: while a broken release is refused"
    if [[ "$(ask /api/v1/me)" == 'api v2' ]] && (( $("${COMPOSE[@]}" ps --all --quiet api | wc -l) == 1 )); then
        ok "$file: the old api keeps serving and the broken one is removed"
    else
        fail "$file: after the refused release the api is not release 2 alone"
    fi

    # The rollback switch (deploy.sh's, with the shorter keep-assets limit) back to release 1, also without a gap.
    start_client
    sleep 2
    release v1 v1 p1
    switch_to 20 > "$WORK/switch.log" 2>&1 || { fail "$file: the rollback switch failed"; cat "$WORK/switch.log"; }
    sleep 3
    stop_client "$file: during the rollback to release 1"
    if [[ "$(ask /api/v1/me)" == 'api v1' ]]; then ok "$file: release 1 is live again"; else fail "$file: the rollback did not take"; fi

    # A proxy whose content changed is recreated.
    release v1 v1 p3
    switch_to 20 > "$WORK/switch.log" 2>&1 || fail "$file: the switch to a changed proxy failed"
    if [[ "$(ids proxy)" != "$proxy_id" ]] && wait_answer /api/v1/me 'api v1'; then
        ok "$file: a changed proxy is recreated and serves"
    else
        fail "$file: a changed proxy was not recreated"
    fi

    # Enough memory, as the staging Pi has it since decision D37: a fake bms.slice with MemoryHigh 1500 MB, MemoryMax
    # 1600 MB and about 830 MB in use (more than the 790 MB measured, on purpose: 670 MB free), and the default need (the
    # api's own limit plus the margin, 448 + 64 on the Pi). The memory check itself runs here (every other switch above
    # sets ROLL_MIN_FREE_MB=0), and the api must overlap.
    if [[ "$file" == compose.pi-staging.yml ]]; then
        mkdir -p "$WORK/slice"
        echo 1677721600 > "$WORK/slice/memory.max"
        echo 1572864000 > "$WORK/slice/memory.high"
        echo 870318080 > "$WORK/slice/memory.current"
        printf 'anon 870318080\ninactive_file 0\n' > "$WORK/slice/memory.stat"
        SLICE_DIR="$WORK/slice" ROLL_MIN_FREE_MB=""
        start_client
        sleep 2
        release v2 v1 p3
        switch_to 20 > "$WORK/switch.log" 2>&1 || fail "$file: the switch with the D37 slice failed"
        sleep 3
        # shellcheck disable=SC2034 # read by roll, copied out of deploy.sh above
        SLICE_DIR=/nonexistent ROLL_MIN_FREE_MB=0
        stop_client "$file: during a switch with the D37 slice"
        # free_memory_mb takes the lesser of the slice's headroom and the host's MemAvailable; a runner with less than
        # 512 MB available would fall back for that reason, so say which.
        host_mb="$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)"
        if (( host_mb < 512 )); then
            fail "$file: the runner has only ${host_mb} MB available, so the D37 overlap case cannot run here"
        elif grep -qE "\\] $(( host_mb < 670 ? host_mb : 670 )) MB free, a second api needs 512 MB\$" "$WORK/switch.log" \
            && grep -q 'starting a new api container beside the running one' "$WORK/switch.log" \
            && ! grep -q 'restarting api in place' "$WORK/switch.log" && [[ "$(ask /api/v1/me)" == 'api v2' ]]; then
            ok "$file: with the D37 slice (about 670 MB free below MemoryHigh) the api overlaps"
        else
            fail "$file: with the D37 slice the api did not overlap:"; sed 's/^/       /' "$WORK/switch.log"
        fi
        release v1 v1 p3
        switch_to 20 > "$WORK/switch.log" 2>&1 || fail "$file: the switch back to release 1 failed"
    fi

    # Too little free memory for a second api: a fake bms.slice with 2 GB MemoryMax but 600 MB MemoryHigh, 100 MB in use,
    # so 500 MB below MemoryHigh, less than the api's own limit plus the margin (448 + 64 on the Pi, 1536 + 64 on the VM).
    # The default need comes from the running container's limit, and MemoryHigh, not MemoryMax, is the bound.
    mkdir -p "$WORK/slice"
    echo 2147483648 > "$WORK/slice/memory.max"
    echo 629145600 > "$WORK/slice/memory.high"
    echo 104857600 > "$WORK/slice/memory.current"
    printf 'anon 104857600\ninactive_file 0\n' > "$WORK/slice/memory.stat"
    if [[ "$file" == compose.pi-staging.yml ]]; then need=512; else need=1600; fi
    SLICE_DIR="$WORK/slice" ROLL_MIN_FREE_MB=""
    release v2 v1 p3
    switch_to 20 > "$WORK/switch.log" 2>&1 || fail "$file: the in-place switch failed"
    # shellcheck disable=SC2034 # read by roll, copied out of deploy.sh above
    SLICE_DIR=/nonexistent ROLL_MIN_FREE_MB=0
    free="$(grep -oE '^\[deploy [^]]*\] [0-9]+ MB free' "$WORK/switch.log" | grep -oE '[0-9]+ MB free' | cut -d' ' -f1)"
    if grep -q "a second api needs ${need} MB; restarting api in place" "$WORK/switch.log" && (( ${free:-9999} <= 500 )) \
        && wait_answer /api/v1/me 'api v2'; then
        ok "$file: below MemoryHigh less than the api's limit plus margin is free, so the api restarts in place"
    else
        fail "$file: the low-memory fallback did not run as expected:"; sed 's/^/       /' "$WORK/switch.log"
    fi

    # With no api up, after Caddy's 30 seconds of tries, the answer proves nothing reached it.
    "${COMPOSE[@]}" stop api > /dev/null 2>&1
    out="$(headers_of /api/v1/retail/transfers)"
    if grep -qE ' 50[23]' <<< "$out" && grep -qi 'Bms-Not-Delivered: 1' <<< "$out"; then
        ok "$file: with no api up the answer carries Bms-Not-Delivered"
    else
        fail "$file: with no api up:"; printf '       %s\n' "$out"
    fi
done

if (( FAILURES > 0 )); then
    echo "$FAILURES check(s) failed"
    exit 1
fi
echo "all checks passed"
