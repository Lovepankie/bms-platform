#!/usr/bin/env bash
# Test for issue #525 (ADR-078): after a deploy, the hashed files of the previous web build still
# answer 200, index.html and the service worker answer no-cache, and files that left the live build
# more than the keep period ago are pruned. Needs Docker and nothing else: no registry, no cosign,
# no api or database. It builds three small web images from frontend/Caddyfile with fabricated
# bundles, then runs deploy.sh's own keep_outgoing_assets and switch_to (copied out of the script,
# with APP_SERVICES set to web only) against the real compose.yml and compose.pi-staging.yml. It also
# switches back as a rollback does, and checks that a keep-assets run that fails or hangs never
# stops the switch.
# Run: bash deploy/tests/keep-assets-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="$(mktemp -d)"
ID="keepassets$$"
FAILURES=0

log() { echo "[test] $*"; }
ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILURES=$(( FAILURES + 1 )); }

cleanup() {
    for file in compose.yml compose.pi-staging.yml; do
        docker compose --project-name "$ID-${file%%.*}" --file "$REPO/deploy/$file" \
            --env-file "$WORK/env" down --volumes --remove-orphans > /dev/null 2>&1 || true
    done
    docker image rm "$ID:v1" "$ID:v2" "$ID:v3" > /dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# Three builds. Build 1 and build 2 share one chunk (same hash, as an unchanged chunk does); every
# other hashed name belongs to one build only, like a real Vite build.
build() {
    local v="$1" dir="$WORK/web$1"
    mkdir -p "$dir/dist/assets"
    cp "$REPO/frontend/Caddyfile" "$dir/Caddyfile"
    printf '<!doctype html><script type="module" src="/assets/index-v%s.js"></script>\n' "$v" > "$dir/dist/index.html"
    printf 'self.importScripts("/workbox-v%s.js")\n' "$v" > "$dir/dist/sw.js"
    printf 'export const build = %s\n' "$v" > "$dir/dist/assets/index-v$v.js"
    printf 'export const page = %s\n' "$v" > "$dir/dist/assets/page-v$v.js"
    printf '// workbox %s\n' "$v" > "$dir/dist/workbox-v$v.js"
    if [[ "$v" != 3 ]]; then
        printf 'export const shared = 1\n' > "$dir/dist/assets/shared-s1.js"
        mkdir -p "$dir/dist/assets/nested"
        printf 'export const deep = %s\n' "$v" > "$dir/dist/assets/nested/deep-v$v.js"
    fi
    # The runtime stage of frontend/Dockerfile, without the npm build.
    cat > "$dir/Dockerfile" <<'EOF'
FROM caddy:2.11-alpine
COPY --chmod=644 Caddyfile /etc/caddy/Caddyfile
COPY --chown=10001:10001 dist /srv
USER 10001:10001
EOF
    docker build --quiet --tag "$ID:v$v" "$dir" > /dev/null
}

# Placeholders only: every service the files name must interpolate, though only web runs.
cat > "$WORK/env" <<'EOF'
API_IMAGE=unused.invalid/api:sha-0000000
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

# The staging Pi runs Compose 2.26, which has no `run --pull` (it failed the first real deploy of
# ADR-078); never pulling is the service's pull_policy instead.
if grep -nE '^[^#]*run .*--pull' "$REPO/deploy/deploy.sh"; then
    echo "FAIL deploy.sh uses run --pull, which Compose 2.26 on the staging Pi does not have"
    exit 1
fi
for file in compose.yml compose.pi-staging.yml; do
    grep -A3 '^  keep-assets:' "$REPO/deploy/$file" | grep -q 'pull_policy: never' \
        || { echo "FAIL $file: keep-assets must have pull_policy: never"; exit 1; }
done

# The two functions under test, exactly as deploy.sh defines them.
eval "$(sed -n '/^keep_outgoing_assets() {/,/^}/p; /^switch_to() {/,/^}/p' "$REPO/deploy/deploy.sh")"
if ! declare -F keep_outgoing_assets > /dev/null || ! declare -F switch_to > /dev/null; then
    echo "FAIL could not read keep_outgoing_assets and switch_to out of deploy/deploy.sh"
    exit 1
fi

# GET a path inside the web container; prints the status line and headers, then the body.
fetch() {
    "${COMPOSE[@]}" exec -T web wget -S -q -O - "http://127.0.0.1:8080$1" 2>&1 < /dev/null || true
}
expect() {
    local name="$1" path="$2" pattern="$3" out
    out="$(fetch "$path")"
    if grep -qiE -- "$pattern" <<< "$out"; then
        ok "$name"
    else
        fail "$name: GET $path did not match '$pattern':"
        printf '       %s\n' "$out"
    fi
}
wait_web() {
    local _
    for _ in $(seq 1 30); do
        fetch / | grep -q 'HTTP/1.1 200' && return 0
        sleep 1
    done
    return 1
}
# deploy <build> [keep-assets time limit]: the second argument is what deploy.sh passes to switch_to.
deploy() {
    export WEB_IMAGE="$ID:v$1"
    switch_to "${2:-60}" > "$WORK/switch.log" 2>&1 || { cat "$WORK/switch.log"; return 1; }
    wait_web
}

log "building three web builds"
for v in 1 2 3; do build "$v" || { echo "FAIL docker build of build $v"; exit 1; }; done

for file in compose.yml compose.pi-staging.yml; do
    log "$file"
    project="$ID-${file%%.*}"
    COMPOSE=(docker compose --project-name "$project" --file "$REPO/deploy/$file" --env-file "$WORK/env")
    if [[ "$file" == compose.pi-staging.yml ]]; then
        # bms.slice exists only on the staging host; everything else of the staging file stands.
        cat > "$WORK/no-slice.yml" <<'EOF'
services:
  web:
    cgroup_parent: !reset ""
  keep-assets:
    cgroup_parent: !reset ""
EOF
        COMPOSE+=(--file "$WORK/no-slice.yml")
    fi
    # shellcheck disable=SC2034 # read by switch_to, copied out of deploy.sh above
    APP_SERVICES=(web)

    deploy 1 || fail "$file: build 1 did not start"
    expect "$file: build 1 serves its own chunk" /assets/page-v1.js 'HTTP/1.1 200'

    deploy 2 || fail "$file: build 2 did not start"
    expect "$file: build 2 index.html names its own chunk" / 'index-v2\.js'
    expect "$file: index.html answers no-cache" / 'Cache-Control: no-cache'
    expect "$file: sw.js answers no-cache" /sw.js 'Cache-Control: no-cache'
    expect "$file: a chunk of build 1 still answers 200" /assets/page-v1.js 'HTTP/1.1 200'
    expect "$file: ... with its own content" /assets/page-v1.js 'page = 1'
    expect "$file: ... as immutable" /assets/page-v1.js 'max-age=31536000, immutable'
    expect "$file: build 1's workbox runtime still answers 200" /workbox-v1.js 'HTTP/1.1 200'
    expect "$file: build 2's own chunk answers 200" /assets/page-v2.js 'HTTP/1.1 200'
    expect "$file: an unknown hash still answers 404" /assets/page-v0.js '404'
    expect "$file: a nested chunk of build 1 still answers 200" /assets/nested/deep-v1.js 'HTTP/1.1 200'
    expect "$file: the volume is not a public path" /previous/assets/page-v1.js '404'

    # Age build 1's own files past the keep period, as if build 1 had left 15 days ago. The shared
    # chunk is touched again by the next copy (build 2 still has it), so it stays.
    "${COMPOSE[@]}" run --rm -T --user 0:0 --entrypoint sh -v "${project}_web_assets:/previous" keep-assets \
        -c 'touch -d "2000-01-01 00:00:00" /previous/assets/* /previous/workbox-*.js' > /dev/null 2>&1 < /dev/null \
        || fail "$file: could not age the kept files"
    deploy 3 || fail "$file: build 3 did not start"
    expect "$file: a file older than the keep period is pruned" /assets/page-v1.js '404'
    expect "$file: build 2's chunk (just retired) answers 200" /assets/page-v2.js 'HTTP/1.1 200'
    expect "$file: a chunk build 3 dropped but build 2 had answers 200" /assets/shared-s1.js 'HTTP/1.1 200'
    if grep -q 'kept [0-9]* hashed files' "$WORK/switch.log"; then
        ok "$file: keep-assets ran before the switch"
    else
        fail "$file: keep-assets did not report:"; sed 's/^/       /' "$WORK/switch.log"
    fi

    # A rollback: switch back to build 2 with the rollback's shorter limit. Build 3's files are kept,
    # for a phone that loaded build 3 while it was live.
    deploy 2 20 || fail "$file: the switch back to build 2 did not start"
    expect "$file: after a rollback, index.html is build 2's" / 'index-v2\.js'
    expect "$file: after a rollback, the rolled-back build's chunk answers 200" /assets/page-v3.js 'HTTP/1.1 200'

    # keep-assets fails, then hangs: the switch still happens, the hang is cut off at the limit and
    # no run container is left behind.
    base=("${COMPOSE[@]}")
    for mode in fails hangs; do
        if [[ "$mode" == fails ]]; then entry='["false"]'; target=3; limit=60; else entry='["sleep", "300"]'; target=2; limit=3; fi
        printf 'services:\n  keep-assets:\n    entrypoint: %s\n    command: !reset []\n' "$entry" > "$WORK/keep-$mode.yml"
        COMPOSE=("${base[@]}" --file "$WORK/keep-$mode.yml")
        started=$SECONDS
        deploy "$target" "$limit" || fail "$file: build $target did not start when keep-assets $mode"
        expect "$file: when keep-assets $mode, the switch still happens" / "index-v$target\\.js"
        if grep -q 'warning: the outgoing web files were not kept' "$WORK/switch.log"; then
            ok "$file: when keep-assets $mode, the deploy logs a warning"
        else
            fail "$file: when keep-assets $mode, no warning:"; printf '       %s\n' "$(cat "$WORK/switch.log")"
        fi
        if [[ "$mode" == hangs ]]; then
            if (( SECONDS - started < 40 )); then ok "$file: a hanging keep-assets is cut off"; else fail "$file: a hanging keep-assets took $(( SECONDS - started ))s"; fi
            if [[ -z "$(docker ps --all --quiet --filter name=bms-keep-assets-)" ]]; then
                ok "$file: no keep-assets container is left behind"
            else
                fail "$file: a keep-assets container is left behind"
            fi
        fi
    done
    COMPOSE=("${base[@]}")

    # The volume is read-only inside web.
    if "${COMPOSE[@]}" exec -T web sh -c 'touch /srv/previous/x' > /dev/null 2>&1 < /dev/null; then
        fail "$file: web can write /srv/previous"
    else
        ok "$file: web mounts /srv/previous read-only"
    fi
done

if (( FAILURES > 0 )); then
    echo "$FAILURES check(s) failed"
    exit 1
fi
echo "all checks passed"
