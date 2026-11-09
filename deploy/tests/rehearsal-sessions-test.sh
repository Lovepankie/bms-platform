#!/usr/bin/env bash
# Test for issue #138 (review of 1ebe9e4): against a real PostgreSQL 16, the live sessions the import
# rehearsal opens carry its name in pg_stat_activity, are bounded by its statement timeout, and its
# terminate query ends them and nothing else. It runs the script's own rh_pgoptions, rh_live and
# rh_terminate_live, copied out of deploy/restore-drill.sh, against a postgres:16-alpine container with
# fabricated roles and no data. Needs Docker and nothing else.
# Run: bash deploy/tests/rehearsal-sessions-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
PG="rhsessions$$"
FAILURES=0

ok() { echo "ok   $1"; }
fail() { echo "FAIL $1"; FAILURES=$(( FAILURES + 1 )); }
# check <got> <want> <what>
check() { if [[ "$1" == "$2" ]]; then ok "$3"; else fail "$3: got '$1', want '$2'"; fi; }

cleanup() { docker rm -f "$PG" > /dev/null 2>&1 || true; }
trap cleanup EXIT

# shellcheck disable=SC1090
source <(sed -n -e '/^rh_live() {/,/^}/p' -e '/^rh_pgoptions() {/,/^}/p' -e '/^rh_terminate_live() {/,/^}/p' \
    "$REPO/deploy/restore-drill.sh")
for fn in rh_live rh_pgoptions rh_terminate_live; do
    declare -F "$fn" > /dev/null || { echo "FAIL $fn is not in deploy/restore-drill.sh"; exit 1; }
done

docker run -d --name "$PG" -e POSTGRES_PASSWORD=fabricated-test-only -e POSTGRES_DB=bms postgres:16-alpine > /dev/null \
    || { echo "FAIL could not start postgres:16-alpine"; exit 1; }
# Over TCP only: the image's first-start server listens on the socket alone, so this waits for the real one.
for _ in $(seq 90); do
    docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres -d bms > /dev/null 2>&1 && break
    sleep 1
done
super() { docker exec "$PG" psql -U postgres -d bms -v ON_ERROR_STOP=1 -tAc "$1"; }
super "CREATE ROLE bms_owner LOGIN; CREATE ROLE bms_app LOGIN; CREATE ROLE someone_else LOGIN;" > /dev/null \
    || { echo "FAIL postgres did not come up"; exit 1; }

# What the script sets before it touches live.
RH_APP="bms-rehearsal-4242"
# shellcheck disable=SC2034 # read by the copied functions
RH_LIVE_DB="$PG"
RH_STARTED_EPOCH=$(( $(date +%s) - 60 ))

# 1. rh_live's session shows the rehearsal's name, and its server-side limits.
got="$(rh_live "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")"
check "$got" "$RH_APP" "rh_live's session is named $RH_APP in pg_stat_activity"
got="$(rh_live "SELECT current_setting('statement_timeout') || ' ' || current_setting('idle_in_transaction_session_timeout')")"
check "$got" "1min 1min" "rh_live's session has statement and idle-in-transaction timeouts of 60s"

# 2. Why PGAPPNAME: an application_name in PGOPTIONS loses to the one psql sends itself.
got="$(docker exec -e "PGOPTIONS=-c application_name=$RH_APP" "$PG" psql -U bms_owner -d bms -tAc \
    "SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()")"
check "$got" "psql" "an application_name in PGOPTIONS is overridden by psql's own"

# 3. The statement timeout ends a long statement on the server.
started=$SECONDS
out="$(docker exec -e "PGAPPNAME=$RH_APP" -e "PGOPTIONS=$(rh_pgoptions 2)" "$PG" \
    psql -U bms_owner -d bms -v ON_ERROR_STOP=1 -tAc "SELECT pg_sleep(30)" 2>&1)"
status=$?
if (( status != 0 )) && [[ "$out" == *"statement timeout"* ]] && (( SECONDS - started < 20 )); then
    ok "a statement past rh_pgoptions' limit is cancelled by the server"
else
    fail "a 30s statement under a 2s limit: status $status after $(( SECONDS - started ))s: $out"
fi

# 4. rh_terminate_live ends the rehearsal's sessions and leaves every other one.
sleeper() { # sleeper <role> <name>
    docker exec -d -e "PGAPPNAME=$2" -e "PGOPTIONS=$(rh_pgoptions 120)" "$PG" \
        psql -U "$1" -d bms -c "SELECT pg_sleep(100)"
}
sleeper bms_owner "$RH_APP"
sleeper bms_app "$RH_APP"
sleeper bms_owner bms-rehearsal-9999
sleeper someone_else "$RH_APP"
sleeping() { super "SELECT string_agg(usename || '/' || application_name, ' ' ORDER BY usename, application_name)
    FROM pg_stat_activity WHERE state = 'active' AND query = 'SELECT pg_sleep(100)'"; }
all="bms_app/$RH_APP bms_owner/$RH_APP bms_owner/bms-rehearsal-9999 someone_else/$RH_APP"
for _ in $(seq 30); do [[ "$(sleeping)" == "$all" ]] && break; sleep 1; done
[[ "$(sleeping)" == "$all" ]] || fail "the four sleeping sessions did not start: $(sleeping)"

# Sessions opened before the rehearsal started are not its own.
saved=$RH_STARTED_EPOCH
RH_STARTED_EPOCH=$(( $(date +%s) + 3600 ))
rh_terminate_live
sleep 2
check "$(sleeping)" "$all" "rh_terminate_live leaves sessions older than the rehearsal"
RH_STARTED_EPOCH=$saved

rh_terminate_live
left="bms_owner/bms-rehearsal-9999 someone_else/$RH_APP"
for _ in $(seq 10); do [[ "$(sleeping)" == "$left" ]] && break; sleep 1; done
check "$(sleeping)" "$left" "rh_terminate_live ends bms_owner and bms_app sessions named $RH_APP and no other"

if (( FAILURES > 0 )); then
    echo "$FAILURES rehearsal session check(s) failed"
    exit 1
fi
echo "all rehearsal session checks passed"
