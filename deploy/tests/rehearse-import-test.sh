#!/usr/bin/env bash
# The rehearsal mode of the restore drill, `restore-drill.sh --rehearse-import <run id>` (issue #138), on a fake host
# (deploy/tests/drill-fakes.sh): no network, no Docker, no PostgreSQL. Proven here:
#   - it is opt in: a wrong or missing argument is a usage error that starts nothing;
#   - a live database target is refused before anything is restored or committed: the throwaway container answering the
#     live cluster's system identifier, or the live container's id, or the live API's settings naming another database;
#   - on staging a run not marked fabricated is refused, and so are a run that is not in review or ready, a run of the
#     import-retail command, a missing run, a slice without room and a drill or rehearsal already running;
#   - the containers get their memory and CPU limits, inside bms.slice on staging, on an internal network of their own;
#   - the backup is decrypted only inside a mode-0700 temp dir;
#   - the live database is read in one read-only transaction and written once, the rehearsal's row, as bms_app;
#   - every container, the network, the volume and the temp dir are removed on success, on failure and on interrupt;
#   - rh_pick_newest_backup is the drill's pick_newest_backup.
# Run: bash deploy/tests/rehearse-import-test.sh
# The checks are strings eval'd when they run, so their variables are meant to stay unexpanded until then.
# shellcheck disable=SC2016
set -uo pipefail
# The drill's window is in host time; the fake clock's days are read in UTC.
export TZ=UTC

TESTS_HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=deploy/tests/drill-fakes.sh
. "$TESTS_HERE/drill-fakes.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
FAILURES=0
RUN=0b5e7a2c-0000-4000-8000-00000000c001

ok() { echo "ok   $1"; }
fail() {
    echo "FAIL $1"
    FAILURES=$(( FAILURES + 1 ))
}
check() {
    local what="$1"
    shift
    if "$@"; then ok "$what"; else fail "$what"; fi
}

# A slice with room: 1500 MB high, 800 MB used of which 100 MB is page cache, so 700 + 100 = 800 MB free.
slice() {
    mkdir -p "$1"
    echo $(( 1500 * 1048576 )) > "$1/memory.high"
    echo $(( ${2:-800} * 1048576 )) > "$1/memory.current"
    printf 'anon 1\ninactive_file %s\n' $(( 100 * 1048576 )) > "$1/memory.stat"
}

# rehearse <name> [args...]: a new fake host, then the script with the arguments (default: --rehearse-import RUN).
rehearse() {
    local name="$1"
    shift
    fake_host "$WORK/$name"
    slice "$WORK/$name/slice" "${USED_MB:-800}"
    export BMS_REHEARSAL_SLICE_DIR="$WORK/$name/slice"
    if [[ -n "${PRODUCTION:-}" ]]; then
        sed -i 's/^BMS_ENVIRONMENT=staging$/BMS_ENVIRONMENT=production/' "$HOST/.env"
    fi
    if (( $# == 0 )); then
        set -- --rehearse-import "$RUN"
    fi
    run_script "$@"
}

calls() { grep -c -- "$1" "$FAKE/calls.log"; }
said() { grep -q -- "$1" <<< "$OUTPUT"; }
nothing_started() { ! grep -q '^run ' "$FAKE/calls.log"; }
nothing_restored() { ! grep -q 'pg_restore' "$FAKE/calls.log"; }
no_commit() { ! grep -q 'rehearse-import' "$FAKE/calls.log"; }
no_live_write() {
    [[ ! -s "$FAKE/live-writes" ]] && ! grep -Eq 'bms-postgres-1 (timeout -s KILL [0-9]+ )?psql -U bms_app' "$FAKE/calls.log"
}
# Everything the rehearsal made is gone: containers, networks, volumes and the temp dir; and no drill state was written.
all_removed() {
    [[ ! -s "$FAKE/containers" && ! -s "$FAKE/networks" && ! -s "$FAKE/volumes" ]] \
        && [[ -z "$(find "$HOST" -path '*backups/rehearsal.*' -print -quit)" ]] \
        && [[ -z "$(find "$HOST/state" -mindepth 1 -not -name current_tag -not -name '*.lock' -print -quit)" ]]
}

# --- Opt in only: usage errors start nothing --------------------------------------------------------------------------
for args in "--rehearse-import" "--rehearse-import not-a-uuid" "--rehearse $RUN" "--rehearse-import $RUN extra"; do
    # shellcheck disable=SC2086
    rehearse usage $args
    check "'restore-drill.sh ${args}' is a usage error (2) and calls docker never" \
        eval '[[ "$STATUS" == 2 && ! -s "$FAKE/calls.log" ]] && said "usage\|UUID"'
done

# --- A healthy rehearsal on staging -------------------------------------------------------------------------------------
rehearse ok-staging
check "a fabricated ready run on staging is rehearsed and stored (exit 0)" eval '[[ "$STATUS" == 0 ]]'
check "it says it stored the rehearsal on the live run" said "stored the rehearsal on live run ${RUN}"
check "the throwaway postgres is capped at 256 MB and 1 CPU inside bms.slice, on the rehearsal's network" \
    grep -Eq '^run --detach --name bms-rehearsal-db-[0-9]+ --cgroup-parent bms\.slice --memory 256m --memory-swap 256m --cpus 1\.0 --network bms-rehearsal-net-[0-9]+ ' "$FAKE/calls.log"
check "the API container is capped at 448 MB and 1 CPU inside bms.slice, on the rehearsal's network" \
    grep -Eq '^run --rm --name bms-rehearsal-api-[0-9]+ --cgroup-parent bms\.slice --memory 448m --memory-swap 448m --cpus 1\.0 --network bms-rehearsal-net-[0-9]+ ' "$FAKE/calls.log"
check "the migrate container is capped the same way" \
    grep -Eq '^run --rm --name bms-rehearsal-migrate-[0-9]+ --cgroup-parent bms\.slice --memory 448m --memory-swap 448m --cpus 1\.0 ' "$FAKE/calls.log"
check "the network is internal: no route out of it, and the live database is not on it" \
    grep -Eq '^network create --internal bms-rehearsal-net-[0-9]+$' "$FAKE/calls.log"
# Review of #890, Lead on point 3: the provider hosts the startup guards pin are kept, so this network is what keeps the
# API container from them. isolated <calls log>: the network is created --internal; the throwaway database, the migrate
# and the API containers each run with exactly one --network, that one; nothing is connected to another network later.
isolated() {
    local net line
    net="$(sed -nE 's/^network create --internal (bms-rehearsal-net-[0-9]+)$/\1/p' "$1")"
    [[ -n "$net" ]] && grep -Eq '^run .*--name bms-rehearsal-api-[0-9]+ ' "$1" || return 1
    while IFS= read -r line; do
        [[ "$(grep -o -- '--network [^ ]*' <<< "$line")" == "--network $net" ]] || return 1
    done < <(grep -E '^run .*--name bms-rehearsal-(db|migrate|api)-[0-9]+ ' "$1")
    ! grep -q '^network connect' "$1"
}
check "the database, migrate and API containers join the internal rehearsal network only" isolated "$FAKE/calls.log"
# The check fails the other way: each way of giving the API a route out is caught.
sed 's/^network create --internal /network create /' "$FAKE/calls.log" > "$WORK/no-internal.log"
sed -E 's/^(run .*--name bms-rehearsal-api-[0-9]+ .*--network [^ ]+)/\1 --network bridge/' "$FAKE/calls.log" > "$WORK/two-networks.log"
{ cat "$FAKE/calls.log"; echo "network connect bridge bms-rehearsal-api-4242"; } > "$WORK/connected.log"
check "... and it fails without --internal, with a second --network on the API, or with a network connect" \
    eval '! isolated "$WORK/no-internal.log" && ! isolated "$WORK/two-networks.log" && ! isolated "$WORK/connected.log"'
check "the API runs the live API's image" grep -q 'ghcr.io/fake/bms-platform-api@sha256:fake rehearse-import --tenant' "$FAKE/calls.log"
check "the API's only database is the throwaway one, its scheduler off, with the one-time token" \
    eval '[[ "$(grep -E "jdbc:|postgres(ql)?://" "$FAKE/api-env.seen")" =~ ^BMS_DB_URL=jdbc:postgresql://bms-rehearsal-db-[0-9]+:5432/bms$ ]] \
        && grep -qx BMS_SCHEDULER_ENABLED=false "$FAKE/api-env.seen" && grep -q "^BMS_REHEARSAL_TOKEN=f00d" "$FAKE/api-env.seen" \
        && ! grep -q live-app-secret "$FAKE/api-env.seen"'
check "the backup is decrypted only inside a mode-0700 temp dir under backups/" \
    eval '[[ "$(wc -l < "$FAKE/decrypt-dirs")" == 1 ]] && grep -Eq "^700 .*/opt/backups/rehearsal\.[A-Za-z0-9]{6}$" "$FAKE/decrypt-dirs"'
check "the live run's rows are read in one read-only transaction, with no write" \
    eval 'head -n 1 "$FAKE/live-reads" | grep -q "READ ONLY" && ! grep -Eiq "^(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|GRANT)" "$FAKE/live-reads"'
check "the copy into the throwaway database holds the deletes, the token and every table's rows" \
    eval 'head -n 2 "$FAKE/copy-stream" | tr "\n" " " | grep -q "^BEGIN; SET LOCAL session_replication_role = replica; " \
        && [[ "$(tail -n 1 "$FAKE/copy-stream")" == "COMMIT;" ]] \
        && grep -q "^INSERT INTO bms_rehearsal_marker (token) VALUES (.f00d" "$FAKE/copy-stream" \
        && [[ "$(grep -c "^COPY import_" "$FAKE/copy-stream")" == 6 && "$(grep -c "^staged row" "$FAKE/copy-stream")" == 6 ]] \
        && [[ "$(grep -cx "\\\\." "$FAKE/copy-stream")" == 6 ]]'
check "the live database is written once: the rehearsal's row, as bms_app under the run's business" \
    eval '[[ "$(grep -cE "bms-postgres-1 (timeout -s KILL [0-9]+ )?psql -U bms_app" "$FAKE/calls.log")" == 1 ]] \
        && grep -q "set_config(.app.tenant_id., .${FAKE_TENANT}., false).*INSERT INTO import_rehearsals" "$FAKE/calls.log" \
        && [[ "$(cat "$FAKE/live-writes")" == "row of bms-rehearsal-db-"* ]]'
check "every other call on the live database only reads" \
    eval '! grep "bms-postgres-1" "$FAKE/calls.log" | grep -v "psql -U bms_app" | grep -Eiq "INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|GRANT"'
check "the throwaway database was checked twice before it was written: before the restore and before the commit" \
    eval '[[ "$(calls "bms-rehearsal-db-[0-9]* psql -U postgres -d bms -v ON_ERROR_STOP=1 -tAc SELECT system_identifier")" == 2 ]]'
check "everything it made is removed and no drill state is written" all_removed

PRODUCTION=1 FAKE_RUN_ROW="${FAKE_TENANT}|in_review|f|framework" rehearse ok-production
check "on production a run in review, not fabricated, is rehearsed, outside any slice" \
    eval '[[ "$STATUS" == 0 ]] && ! grep -q cgroup-parent "$FAKE/calls.log" && all_removed'

# --- Refusals: a live target, a run that may not be rehearsed, no room ---------------------------------------------------
FAKE_SAME_SYSID=1 rehearse live-cluster
check "a throwaway database that answers the live cluster's identifier is refused (3)" \
    eval '[[ "$STATUS" == 3 ]] && said "REFUSED: the target database is the live cluster"'
check "... before anything is restored, copied or committed, and nothing is written live" \
    eval 'nothing_restored && no_commit && no_live_write && [[ ! -s "$FAKE/copy-stream" ]]'
check "... and what it made is removed" all_removed

FAKE_SAME_ID=1 rehearse live-container
check "a target container that is the live database's is refused (3), nothing restored, all removed" \
    eval '[[ "$STATUS" == 3 ]] && said "the target container is the live database" && nothing_restored && no_live_write && all_removed'

FAKE_ENV_DB_URL="jdbc:postgresql://postgres:5432/bms" rehearse live-url
check "an API whose settings would still name the live database is refused (3) before the commit" \
    eval '[[ "$STATUS" == 3 ]] && said "would be given another database" && no_commit && no_live_write && all_removed'

FAKE_RUN_ROW="${FAKE_TENANT}|ready|f|framework" rehearse not-fabricated
check "on staging a run not marked fabricated is refused (3), before any container starts" \
    eval '[[ "$STATUS" == 3 ]] && said "marked fabricated" && nothing_started && no_live_write && all_removed'

FAKE_RUN_ROW="${FAKE_TENANT}|committed|t|framework" rehearse committed
check "a run already committed is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
FAKE_RUN_ROW="${FAKE_TENANT}|ready|t|import_retail" rehearse command-run
check "a run of the import-retail command is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
FAKE_RUN_ROW="" rehearse no-run
check "a run that is not on the live database is refused (3)" eval '[[ "$STATUS" == 3 ]] && nothing_started'
USED_MB=1000 rehearse no-room
check "on staging, a slice with less than 704 MB free is refused (3) before any container starts" \
    eval '[[ "$STATUS" == 3 ]] && said "needs 704 MB" && nothing_started'

# --- Clean-up on failure and on interrupt ---------------------------------------------------------------------------------
FAKE_RESTORE_FAIL=1 rehearse restore-fails
check "a failed restore exits 1, writes nothing live and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && no_commit && no_live_write && all_removed'
FAKE_DECRYPT_FAIL=1 rehearse decrypt-fails
check "a failed decryption exits 1 and removes the temp dir and everything else" \
    eval '[[ "$STATUS" == 1 ]] && no_live_write && all_removed'
FAKE_MIGRATE_FAIL=1 rehearse migrate-fails
check "a failed migration exits 1, commits nothing and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && no_commit && no_live_write && all_removed'
FAKE_API_EXIT=1 rehearse commit-fails
check "a commit that fails is stored on the run as such, exits 1 and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && [[ -s "$FAKE/live-writes" ]] && all_removed'
FAKE_API_EXIT=1 FAKE_NO_OUTCOME=1 rehearse api-never-starts
check "an API container that exits 1 without an outcome (#889) says nothing is stored, exits 1 and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && said "without recording an outcome" && ! said "is stored on the live run next" \
        && no_live_write && all_removed'
FAKE_API_EXIT=1 FAKE_NO_OUTCOME=fail rehearse outcome-unknown
check "... and when the throwaway database does not answer, it says so (not 'without recording an outcome'), exits 1" \
    eval '[[ "$STATUS" == 1 ]] && said "did not say whether it recorded an outcome" && ! said "without recording an outcome" \
        && no_live_write && all_removed'
FAKE_API_EXIT=3 rehearse command-refuses
check "a command that refuses stores nothing, exits 3 (refused) and removes everything" \
    eval '[[ "$STATUS" == 3 ]] && no_live_write && all_removed'
FAKE_API_SIGNAL=TERM rehearse terminated
check "a TERM during the commit exits 143, stores nothing and removes everything" \
    eval '[[ "$STATUS" == 143 ]] && said terminated && no_live_write && all_removed'
FAKE_API_SIGNAL=INT rehearse interrupted
check "a Ctrl-C (INT) during the commit exits 130, stores nothing and removes everything" \
    eval '[[ "$STATUS" == 130 ]] && said interrupted && no_live_write && all_removed'

rehearse busy
echo "bms-restore-drill-4242||none" >> "$FAKE/containers"
run_script --rehearse-import "$RUN"
check "a rehearsal is refused (3) while the weekly drill runs" eval '[[ "$STATUS" == 3 ]] && said "restore drill or another rehearsal"'

# --- Review of #882: nothing beside it, a whole copy or none, no secret in argv, a prompt stop, placeholder keys ------------
# JUSTIFICATION-A3: one test per finding of the review's FAIL verdict.
check "the puller's, the deploy's and its own locks are held while the commit runs" \
    eval 'rehearse locks && [[ "$(tr "\n" " " < "$FAKE/locks-during-commit")" == "pull held deploy held rehearsal held " ]]'

for lock in pull deploy rehearsal; do
    fake_host "$WORK/lock-$lock"
    slice "$WORK/lock-$lock/slice"
    export BMS_REHEARSAL_SLICE_DIR="$WORK/lock-$lock/slice"
    exec 5> "$HOST/state/$lock.lock"
    flock 5
    run_script --rehearse-import "$RUN"
    exec 5>&-
    check "while state/${lock}.lock is held (a ${lock} runs) the rehearsal is refused (3) and starts nothing" \
        eval '[[ "$STATUS" == 3 ]] && nothing_started && all_removed'
done

FAKE_EPOCH=1792897200 rehearse sunday-0300
check "Sunday 03:00, the weekly drill's hour, is refused (3) before any container starts" \
    eval '[[ "$STATUS" == 3 ]] && said "weekly restore drill" && nothing_started'
FAKE_EPOCH=1792879200 rehearse saturday-2200
check "Saturday 22:00, too close to the drill for a long rehearsal, is refused (3) too" \
    eval '[[ "$STATUS" == 3 ]] && said "weekly restore drill" && nothing_started'
# fake_host exports the clock, so the assignment outlives the call: back to the fixed Monday morning.
FAKE_EPOCH=1793000000

FAKE_LIVE_READ_FAIL=1 rehearse live-read-fails
check "a live read that dies part way ends the copy with ROLLBACK, exits 1 and commits nothing" \
    eval '[[ "$STATUS" == 1 ]] && [[ "$(tail -n 1 "$FAKE/copy-stream")" == "ROLLBACK;" ]] && ! grep -q "^COMMIT;" "$FAKE/copy-stream" \
        && said "reading the run" && no_commit && no_live_write && all_removed'
FAKE_COUNT_MISMATCH=1 rehearse copy-short
check "a copy that does not hold the live run's rows (count and ids) exits 1 before the commit" \
    eval '[[ "$STATUS" == 1 ]] && said "does not hold the same rows" && no_commit && no_live_write && all_removed'

rehearse no-secret-in-argv
check "no R2 key, password or token is on any command line; the label is the token's SHA-256" \
    eval '! grep -Eq "fake-secret|fake-access|f00dfeed" "$FAKE/calls.log" \
        && grep -q "bms.rehearsal=$(printf %s f00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeed | sha256sum | cut -c1-64)" "$FAKE/calls.log"'
check "outside services' keys reach the API container as placeholders; the data and signing keys are kept; no import key" \
    eval '! grep -Eq "live-(smtp|r2|resend|telegram|pay|vapid)-secret|live-import-key" "$FAKE/api-env.seen" \
        && grep -qx BMS_PAY_SECRET_HASH=rehearsal-placeholder "$FAKE/api-env.seen" \
        && grep -qx BMS_PUSH_VAPID_KEY=rehearsal-placeholder "$FAKE/api-env.seen" \
        && grep -qx BMS_SMTP_PASSWORD=rehearsal-placeholder "$FAKE/api-env.seen" \
        && grep -qx R2_DOCUMENTS_SECRET_ACCESS_KEY=rehearsal-placeholder "$FAKE/api-env.seen" \
        && grep -qx BMS_DATA_KEY=live-data-key "$FAKE/api-env.seen" && grep -qx BMS_TOKEN_SIGNING_JWK=live-jwk "$FAKE/api-env.seen" \
        && grep -qx BMS_IMPORT_KEY= "$FAKE/api-env.seen"'

# shellcheck disable=SC2034 # read by the eval'd check below.
started_at=$SECONDS
FAKE_API_SIGNAL=TERM FAKE_API_HANG=20 rehearse terminated-hanging
check "a TERM while the commit still runs cleans up at once (not after the commit) and exits 143" \
    eval '[[ "$STATUS" == 143 ]] && (( SECONDS - started_at < 10 )) && all_removed \
        && grep -q "^rm -f -v bms-rehearsal-api-" "$FAKE/calls.log"'

# --- Review of d98fd0b: the run is bounded; the locks are the ones the puller and deploy.sh take --------------------------
# Saturday 21:20, just before the window, and a commit still running at Sunday 03:21.
# (The stub cannot be stopped by docker rm -f as a real container is, so it hangs only 3 s.)
FAKE_EPOCH=1792876800 FAKE_ADVANCE_TO=1792898460 FAKE_API_HANG=3 rehearse past-deadline
FAKE_EPOCH=1793000000
check "a commit still running at the deadline (Sunday 03:20) is stopped, fails (1), stores nothing and removes everything" \
    eval '[[ "$STATUS" == 1 ]] && said "stopped at its deadline" && no_live_write && all_removed \
        && grep -q "^rm -f -v bms-rehearsal-api-" "$FAKE/calls.log"'
check "the run states its deadline, the next Sunday 03:20" said "it must end by 2026-10-25T03:20"

# Review of 305673f: six hours at most, whatever the day, and a step in the foreground that hangs ends too.
# shellcheck disable=SC2034 # read by the eval'd check below.
started_at=$SECONDS
FAKE_COPY_ADVANCE_TO=$(( 1793000000 + 21600 + 60 )) rehearse copy-past-six-hours
check "the deadline of a Monday 07:33 start is 13:33, six hours on" said "it must end by 2026-10-26T13:33"
check "a copy that hangs past six hours is stopped: exit 1, no commit, nothing stored, all removed, at once" \
    eval '[[ "$STATUS" == 1 ]] && said "stopped at its deadline" && no_commit && no_live_write && all_removed \
        && (( SECONDS - started_at < 15 ))'
check "... and the puller's and deploy's locks are free again" \
    eval 'flock --nonblock "$HOST/state/deploy.lock" true && flock --nonblock "$HOST/state/pull.lock" true'

fake_host "$WORK/odd-environment"
sed -i 's/^BMS_ENVIRONMENT=staging$/BMS_ENVIRONMENT=stage/' "$HOST/.env"
run_script --rehearse-import "$RUN"
check "a BMS_ENVIRONMENT other than staging or production is refused (3) before anything is read" \
    eval '[[ "$STATUS" == 3 ]] && ! grep -q "^exec\|^run " "$FAKE/calls.log"'

# Review of d70e30f: the live side ends too, and the deadline says what the live database holds.
# shellcheck disable=SC2034 # read by the eval'd check below.
started_at=$SECONDS
FAKE_LIVE_HANG_ADVANCE_TO=$(( 1793000000 + 21600 + 60 )) rehearse live-read-hangs
check "a live read that hangs past the deadline is stopped (1), its client ended at once, nothing stored, all removed" \
    eval '[[ "$STATUS" == 1 ]] && said "stopped at its deadline" && no_commit && no_live_write && all_removed \
        && (( SECONDS - started_at < 15 ))'
check "... the rehearsal's live sessions are ended on the server by their name, and the locks are free" \
    eval 'grep -Eq "bms-postgres-1 psql -U postgres .*pg_terminate_backend.*application_name = .bms-rehearsal-[0-9]+. +AND usename IN" "$FAKE/calls.log" \
        && flock --nonblock "$HOST/state/deploy.lock" true && flock --nonblock "$HOST/state/pull.lock" true'
check "every session on the live database is named by PGAPPNAME and bounded on the server (statement and idle timeouts)" \
    eval '! grep -E "bms-postgres-1 (timeout -s KILL [0-9]+ )?psql -U (bms_|postgres -d bms -tAc SELECT system)" "$FAKE/calls.log" \
        | grep -vq "PGAPPNAME=bms-rehearsal-[0-9]* -e PGOPTIONS=-c statement_timeout=[0-9]*s -c idle_in_transaction_session_timeout=60s"'
check "the copy's and the write's live psql are killed inside the container at their limit" \
    eval 'rehearse kill-limits && [[ "$(grep -cE "bms-postgres-1 timeout -s KILL [0-9]+ psql -U bms_(owner -d bms -q|app)" "$FAKE/calls.log")" == 2 ]]'

FAKE_WRITE_SIGNAL=USR1 rehearse deadline-at-store
check "a deadline that comes as the row is stored reports it stored (0), as the live database holds it" \
    eval '[[ "$STATUS" == 0 ]] && said "it is stored on live run" && ! said "nothing is stored" && [[ -s "$FAKE/live-writes" ]] && all_removed'
FAKE_WRITE_SIGNAL=TERM rehearse term-at-store
check "a TERM that comes as the row is stored says it is stored, and exits 143" \
    eval '[[ "$STATUS" == 143 ]] && said "terminated, as the rehearsal was stored" && all_removed'
FAKE_WRITE_SIGNAL=USR1 FAKE_LIVE_SILENT=1 rehearse deadline-unknown
check "if the live database does not answer the read-back, the state is unknown (1), with the query that tells" \
    eval '[[ "$STATUS" == 1 ]] && said "whether the rehearsal was stored is unknown" && said "check with: SELECT count" \
        && ! said "nothing is stored" && all_removed'

FAKE_MIGRATE_ADVANCE_TO=$(( 1793000000 + 21600 - 600 )) rehearse little-time-left
check "a commit is not started with 10 minutes left before the deadline (exit 1, nothing stored, all removed)" \
    eval '[[ "$STATUS" == 1 ]] && said "less than 30 minutes before its deadline" && no_commit && no_live_write && all_removed'

locks_match() {
    grep -q '^exec 8> state/pull.lock$' "$TESTS_HERE/../pull-staging.sh" \
        && grep -q 'BMS_STATE_DIR="$ROOT/state"' "$TESTS_HERE/../pull-staging.sh" \
        && grep -q '^STATE_DIR="${BMS_STATE_DIR:-$PWD/state}"$' "$TESTS_HERE/../deploy.sh" \
        && grep -q '^exec 9> "$STATE_DIR/deploy.lock"$' "$TESTS_HERE/../deploy.sh"
}
check "the locks it takes are the very files pull-staging.sh and deploy.sh lock (state/ under /opt/bms)" locks_match

same_picker() {
    diff <(sed -n '/^pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh" | tail -n +2) \
        <(sed -n '/^rh_pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh" | tail -n +2) > /dev/null \
        && [[ -n "$(sed -n '/^rh_pick_newest_backup() {/,/^}/p' "$TESTS_HERE/../restore-drill.sh")" ]]
}
check "the rehearsal picks the newest backup with the drill's own picker" same_picker

if (( FAILURES > 0 )); then
    echo "${FAILURES} failure(s)"
    exit 1
fi
echo "all rehearsal checks passed"
