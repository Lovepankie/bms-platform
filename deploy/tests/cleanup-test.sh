#!/usr/bin/env bash
# Tests for two rules of deploy/pull-staging.sh (issue #580). No network, no Docker: the script's own functions are copied
# out of it and run against a stub docker on PATH and a stub crontab.
#   - remove_old_images keeps the two kept release tags and every image a container uses (under ADR-083 the proxy may run
#     an older tag), so Docker never refuses a removal; it removes the other release images and touches nothing else.
#   - unit_drift_notices names a unit that differs from the installed copy, but not one left uninstalled because the
#     bms user's crontab runs its job (the restore drill, Sunday 03:30).
#   - deploy/systemd/bms.slice cites ADR-083 as merged.
#   - postgres mounts the host's fixed postgres/initdb, so a deploy never recreates it.
# Run: bash deploy/tests/cleanup-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0

ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILURES=$(( FAILURES + 1 )); }
log() { echo "[pull] $*"; }

RELEASE_PREFIX="ghcr.io/rincoltech-solutions-ltd/bms-platform"
sed -n -e '/^remove_old_images() {/,/^}/p' -e '/^unit_drift_notices() {/,/^}/p' "$HERE/../pull-staging.sh" > "$WORK/functions.sh"
# shellcheck source=/dev/null
. "$WORK/functions.sh"
if ! declare -F remove_old_images > /dev/null || ! declare -F unit_drift_notices > /dev/null; then
    echo "FAIL could not copy remove_old_images and unit_drift_notices out of pull-staging.sh"
    exit 1
fi

# The stub docker: images are "ref id" lines in $STUB/images, containers "container-id image-id" lines in $STUB/containers.
# Like Docker, it refuses to remove an image a container uses, and records each removal and refusal.
export STUB="$WORK/stub"
mkdir -p "$WORK/bin" "$STUB"
cat > "$WORK/bin/docker" <<'STUB_DOCKER'
#!/usr/bin/env bash
case "$1 ${2:-}" in
    "ps --all") cut -d' ' -f1 "$STUB/containers" ;;
    "image ls") cat "$STUB/images" ;;
    "image rm")
        id="$(awk -v r="$3" '$1 == r { print $2 }' "$STUB/images")"
        if awk -v i="$id" '$2 == i { found = 1 } END { exit !found }' "$STUB/containers"; then
            echo "conflict: unable to remove repository reference \"$3\": container is using its referenced image" >&2
            echo "$3" >> "$STUB/refused"
            exit 1
        fi
        echo "$3" >> "$STUB/removed" ;;
    "inspect --format")
        shift 3
        for c in "$@"; do awk -v c="$c" '$1 == c { print $2 }' "$STUB/containers"; done ;;
    *) echo "stub docker: unexpected $*" >&2; exit 2 ;;
esac
STUB_DOCKER
chmod +x "$WORK/bin/docker"
PATH="$WORK/bin:$PATH"

images() {
    : > "$STUB/images"
    local sha
    for sha in "$@"; do
        for service in api web proxy; do
            echo "${RELEASE_PREFIX}-${service}:sha-${sha} sha256:${service}${sha}" >> "$STUB/images"
        done
    done
    echo "postgres:16-alpine sha256:postgres16" >> "$STUB/images"
    rm -f "$STUB/removed" "$STUB/refused"
    touch "$STUB/removed" "$STUB/refused"
}
removed() { sort "$STUB/removed" | tr '\n' ' '; }

# 1. The proxy still runs the oldest release's image (ADR-083): it is kept, the other old images go, Docker never refuses.
images 1111111 2222222 3333333
printf '%s\n' "c-proxy sha256:proxy1111111" "c-api sha256:api3333333" "c-web sha256:web3333333" "c-db sha256:postgres16" \
    > "$STUB/containers"
out="$(remove_old_images sha-3333333 sha-2222222 2>&1)"
expected="${RELEASE_PREFIX}-api:sha-1111111 ${RELEASE_PREFIX}-web:sha-1111111 "
if [[ "$(removed)" == "$expected" ]]; then ok "keeps the image the proxy runs and removes the other old release images"; else fail "removed '$(removed)', expected '$expected'"; fi
if [[ ! -s "$STUB/refused" ]]; then ok "Docker refuses no removal"; else fail "Docker refused: $(cat "$STUB/refused")"; fi
if grep -q "keeping ${RELEASE_PREFIX}-proxy:sha-1111111: a container uses it" <<< "$out"; then
    ok "says which image it keeps and why"
else
    fail "no keep line in: $out"
fi

# 2. A stopped container still holds its image, the same way.
images 1111111 2222222 3333333
printf '%s\n' "c-old-migrate sha256:api1111111" > "$STUB/containers"
remove_old_images sha-3333333 sha-2222222 > /dev/null 2>&1
expected="${RELEASE_PREFIX}-proxy:sha-1111111 ${RELEASE_PREFIX}-web:sha-1111111 "
if [[ "$(removed)" == "$expected" && ! -s "$STUB/refused" ]]; then ok "keeps an image a stopped container uses"; else fail "removed '$(removed)', refused '$(cat "$STUB/refused")', expected '$expected'"; fi

# 3. With no containers every old release image goes, the two kept tags and other images stay.
images 1111111 2222222 3333333 4444444
: > "$STUB/containers"
remove_old_images sha-4444444 sha-3333333 > /dev/null 2>&1
expected="$(for s in 1111111 2222222; do for v in api proxy web; do echo "${RELEASE_PREFIX}-${v}:sha-${s}"; done; done | sort | tr '\n' ' ')"
if [[ "$(removed)" == "$expected" ]]; then ok "removes every old release image and nothing else when no container uses one"; else fail "removed '$(removed)', expected '$expected'"; fi

# 4. The first deploy has no previous tag; the new release's images stay.
images 3333333
: > "$STUB/containers"
remove_old_images sha-3333333 "" > /dev/null 2>&1
if [[ -z "$(removed)" ]]; then ok "keeps the only release when there is no previous one"; else fail "removed '$(removed)'"; fi

# The unit check: the release's units against an installed copy, with the bms user's crontab from $WORK/crontab.
crontab() { [[ "$1" == "-l" ]] && cat "$WORK/crontab"; }
units="$WORK/units"
installed="$WORK/installed"
setup_units() {
    rm -rf "$units" "$installed"
    mkdir -p "$units" "$installed"
    cp "$HERE/../systemd/bms.slice" "$HERE/../systemd/bms-restore-drill.service" "$HERE/../systemd/bms-restore-drill.timer" \
        "$HERE/../systemd/bms-pull.service" "$HERE/../systemd/bms-pull.timer" "$units/"
    cp "$units"/* "$installed/"
    rm "$installed/bms-restore-drill.timer"
    printf '%s\n' "15 1 * * * /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1" \
        "30 3 * * 0 /opt/bms/restore-drill.sh >> /opt/bms/state/restore-drill.log 2>&1" > "$WORK/crontab"
}

# 5. The staging host as it is: the drill and the backup run from cron, the drill's timer is not installed.
setup_units
out="$(unit_drift_notices "$units" "$installed")"
if [[ -z "$out" ]]; then ok "accepts the restore drill from the bms user's cron instead of its timer"; else fail "notices: $out"; fi

# 6. Without the cron line the missing timer is named.
setup_units
grep -v restore-drill "$WORK/crontab" > "$WORK/crontab.new" && mv "$WORK/crontab.new" "$WORK/crontab"
out="$(unit_drift_notices "$units" "$installed")"
if grep -q "systemd/bms-restore-drill.timer differs" <<< "$out"; then ok "names the timer when nothing runs the drill"; else fail "no notice for the timer in: '$out'"; fi

# 7. A commented-out cron line does not count.
setup_units
sed -i 's|^30 3|# 30 3|' "$WORK/crontab"
out="$(unit_drift_notices "$units" "$installed")"
if grep -q "systemd/bms-restore-drill.timer differs" <<< "$out"; then ok "a commented-out cron line does not count"; else fail "no notice for the timer in: '$out'"; fi

# 8. An installed unit that differs is still named, cron or not; the others stay quiet.
setup_units
echo "# changed" >> "$installed/bms.slice"
out="$(unit_drift_notices "$units" "$installed")"
if [[ "$out" == *"systemd/bms.slice differs"* && "$(grep -c notice <<< "$out")" == 1 ]]; then ok "names an installed unit that differs, and only it"; else fail "notices: '$out'"; fi

# 9. A systemd prefix and a script run through bash still name the script; a cron line must run that path as a whole word.
setup_units
sed -i 's|^ExecStart=.*|ExecStart=-/bin/bash /opt/bms/restore-drill.sh|' "$units/bms-restore-drill.service"
cp "$units/bms-restore-drill.service" "$installed/"
out="$(unit_drift_notices "$units" "$installed")"
if [[ -z "$out" ]]; then ok "reads the script past a systemd prefix and a bash wrapper"; else fail "notices: $out"; fi
printf '%s\n' "30 3 * * 0 /bin/bash /opt/bms/restore-drill.sh.disabled" > "$WORK/crontab"
out="$(unit_drift_notices "$units" "$installed")"
if grep -q "systemd/bms-restore-drill.timer differs" <<< "$out"; then ok "a cron line for another path that only starts the same does not count"; else fail "no notice for the timer in: '$out'"; fi
printf '%s\n' "30 3 * * 0 /bin/bash /opt/bms/monitor.sh" > "$WORK/crontab"
out="$(unit_drift_notices "$units" "$installed")"
if grep -q "systemd/bms-restore-drill.timer differs" <<< "$out"; then ok "a cron line that only shares the interpreter does not count"; else fail "no notice for the timer in: '$out'"; fi

# 10. pull-staging.sh runs under set -euo pipefail: a new unit with no .sh in its ExecStart, or a new slice with no service
# beside it, is named and never ends the run (the image clean-up comes after this check).
setup_units
printf '[Slice]\nMemoryMax=1M\n' > "$units/bms-new.slice"
printf '[Service]\nExecStart=/usr/bin/docker ps\n' > "$units/bms-foo.service"
printf '[Timer]\nOnCalendar=daily\n' > "$units/bms-foo.timer"
cp "$units/bms-foo.service" "$installed/"
out="$(set -euo pipefail; unit_drift_notices "$units" "$installed"; echo "reached the end")"
if [[ "$out" == *"systemd/bms-new.slice differs"* && "$out" == *"systemd/bms-foo.timer differs"* && "$out" == *"reached the end"* ]]; then
    ok "a unit with no script to match is named and does not end the run under set -e"
else
    fail "under set -e: '$out'"
fi

# 11. The slice cites ADR-083 as merged, so it matches the host's copy.
if grep -q "pending ADR-083" "$HERE/../systemd/bms.slice"; then
    fail "deploy/systemd/bms.slice still says pending ADR-083"
else
    ok "bms.slice cites ADR-083 as merged"
fi

# 12. postgres mounts the host's fixed initdb, never a path inside the release: a per-release bind source changes the
#     service's config hash and made every deploy recreate (restart) postgres.
mount="$(grep -E '/docker-entrypoint-initdb\.d' "$HERE/../compose.pi-staging.yml")"
if grep -qE '^\s*-\s*\$\{BMS_HOST_ROOT:-/opt/bms\}/postgres/initdb:' <<< "$mount"; then
    ok "postgres mounts the host's fixed postgres/initdb"
else
    fail "postgres initdb mount is not the host's fixed path: $mount"
fi
# Run pull-staging.sh's own sync_initdb against a scratch host: it seeds an empty folder, refreshes in place (the
# folder postgres has mounted stays the same folder), drops files a release no longer has, and leaves it readable.
sandbox="$(mktemp -d)"
eval "$(sed -n '/^sync_initdb() {/,/^}/p' "$HERE/../pull-staging.sh")"
mkdir -p "$sandbox/rel/postgres/initdb" "$sandbox/host/postgres/initdb"
echo roles > "$sandbox/rel/postgres/initdb/01-roles.sh"
inode() { stat -c %i "$1" 2> /dev/null || stat -f %i "$1"; }
before="$(inode "$sandbox/host/postgres/initdb")"
touch "$sandbox/host/postgres/initdb/00-stale.sh"
# shellcheck disable=SC2034 # ROOT is read by the sync_initdb loaded above
(umask 0077 && ROOT="$sandbox/host" && sync_initdb "$sandbox/rel")
after="$(inode "$sandbox/host/postgres/initdb")"
mode="$(stat -c %a "$sandbox/host/postgres/initdb/01-roles.sh" 2> /dev/null || stat -f %Lp "$sandbox/host/postgres/initdb/01-roles.sh")"
if [[ -f "$sandbox/host/postgres/initdb/01-roles.sh" && ! -e "$sandbox/host/postgres/initdb/00-stale.sh" \
    && "$before" == "$after" && $(( 8#$mode & 8#004 )) -ne 0 ]]; then
    ok "sync_initdb seeds and refreshes postgres/initdb in place, readable by postgres"
else
    fail "sync_initdb: roles=$(ls "$sandbox/host/postgres/initdb") inode $before->$after mode $mode"
fi
rm -rf "$sandbox"
# shellcheck disable=SC2016 # the literal $ROOT is what the script must contain
if grep -qF 'BMS_HOST_ROOT="$ROOT" ' "$HERE/../pull-staging.sh"; then
    ok "deploy.sh gets BMS_HOST_ROOT from the same ROOT the seed uses"
else
    fail "pull-staging.sh does not pass BMS_HOST_ROOT=\$ROOT to deploy.sh"
fi

echo
if (( FAILURES > 0 )); then
    echo "$FAILURES failed"
    exit 1
fi
echo "all passed"
