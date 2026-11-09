#!/usr/bin/env bash
# Restore drill (NFR-BAK-04, issue #46, docs/runbooks/restore-from-backup.md section 5).
#
# Proves the newest backup in R2 can actually be restored, without touching the live database:
#   1. fetch the newest <environment>/daily/ dump from R2 and decrypt it on this host;
#   2. start a THROWAWAY PostgreSQL (no network, memory capped inside bms.slice on staging), created
#      by the same postgres/initdb/01-roles.sh as the real one, and pg_restore into it;
#   3. check it: Flyway history is intact and not ahead of the live database, the row counts are read,
#      and the application role can read a tenant's rows under row-level security;
#   4. record the result as one line with its time in seconds against the 2 hour RTO: state/last_restore_drill
#      on success, state/last_restore_drill_error on failure, every run appended to
#      state/restore-drill-history.log and the latest uploaded to <environment>/drill/last_restore_drill.txt
#      in R2, so the record outlives the host. monitor.sh alerts on a failed, late or slow drill.
# Everything it created, including the decrypted dump, is removed whatever the outcome.
#
# Schedule: bms-restore-drill.timer, Sunday 03:30 host time, as the deploy user (docs/runbooks/monitoring-and-alerts.md).
# Reads /opt/bms/.env like backup.sh, plus POSTGRES_IMAGE.
#
# Rehearsal mode (issue #138, ADR-027 decision 9, spec docs/specs/customer-data-onboarding.md section 5.5, runbook
# docs/runbooks/restore-from-backup.md section 5.2): `restore-drill.sh --rehearse-import <run id>`, by hand, as the deploy
# user. Opt in by the flag only: with no argument none of it runs and the drill below is unchanged, line for line
# (deploy/tests/restore-drill-test.sh compares it with a golden transcript of the drill before this mode). It never writes
# the drill's state files, so monitor.sh never takes a rehearsal for a drill.
#   1. reads the run on the live database, read only: a run of the import screens, in review or ready, and on staging
#      marked fabricated by its uploader;
#   2. holds its own lock and the puller's and deploy's locks to the end, so no deploy runs beside it; starts nothing
#      from Saturday 21:30 to Sunday 07:00 (the weekly drill) or while a drill runs; ends, nothing stored, at its
#      deadline, six hours after its start or Sunday 03:20 if sooner (a watchdog, rh_watchdog); and on staging goes on
#      only while bms.slice has room for its two containers by their limits (RH_NEEDED_MB);
#   3. fetches and decrypts the newest backup as the drill does, into a mode-0700 temp dir under backups/;
#   4. starts a throwaway PostgreSQL on an internal network of its own, restores into it, and goes on only while that
#      database is provably not the live one: another name, container and cluster, this run's label, this network only;
#   5. migrates it with the live API's image, copies the run's staged rows into it from the live database in one read-only
#      transaction (the run, its batches, rows, issues, resolutions and mappings; the encrypted source comes as its object
#      keys and the run's wrapped data key, and is never fetched or decrypted: the commit reads the staged rows only), and
#      marks it with this rehearsal's one-time token;
#   6. runs `rehearse-import` in a short-lived API container (the live API's image and settings, the database switched to
#      the throwaway one, on that internal network only): the real dry run and the real commit, without the checker's
#      approval and the pre-import dump, which the application waives only in a database that holds the token
#      (core.imports.ImportRehearsals);
#   7. stores the rehearsal (report, reconciliation, timings) on the live run: one import_rehearsals row inserted as bms_app
#      under row-level security, the only write the rehearsal makes on the live database;
#   8. removes every container, the network, the volume and the temp dir it made, on success, failure and interrupt.
# Limits, inside bms.slice on staging (SDD chapter 9 section 9.13): the throwaway PostgreSQL 256 MB and 1 CPU, the API
# 448 MB and 1 CPU with the staging API's 224 MB heap, 704 MB together; rclone 64 MB while it downloads.
# Exit status: 0 rehearsed and stored, 1 failed, 2 usage, 3 refused (the run, the target or the room).
# JUSTIFICATION-A3: the rehearsal is a new mode placed above the drill so that the drill's own lines stay untouched.
set -uo pipefail

RH_LIVE_DB=bms-postgres-1
RH_NEEDED_MB=704
RH_MAX_SECONDS=21600
# The staging API's JVM (compose.pi-staging.yml) with its 224 MB heap, on which the big file test commits a batch of
# 2,500 rows (SDD chapter 15 section 15.7), so a rehearsal holds the batches the live API holds.
RH_JAVA_OPTS="-Xmx224m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:TieredStopAtLevel=1 -XX:+UseSerialGC"
RH_JAVA_OPTS+=" -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
# The run's tables, in the order they are copied.
RH_TABLES=(import_mappings import_runs import_batches import_rows import_issue_resolutions import_issues)
RH_UUID='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

rh_log() { echo "[rehearsal $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
rh_ms() { date -u +%s%3N; }
rh_fail() {
    rh_log "FAILED: $*"
    exit 1
}
rh_refuse() {
    rh_log "REFUSED: $*"
    exit 3
}
# The live database, as the drill reads it: bms_owner, one statement, read only.
rh_live() {
    docker exec -e "PGAPPNAME=${RH_APP}" -e "PGOPTIONS=$(rh_pgoptions 60)" "$RH_LIVE_DB" \
        psql -U bms_owner -d bms -v ON_ERROR_STOP=1 -tAc "$1"
}
# rh_pgoptions <seconds>: every session the rehearsal opens on the live database is bounded on the server, whatever
# happens to its client (killing a docker exec client never ends the psql inside the container), and is named by
# PGAPPNAME, which libpq sends as application_name in place of psql's own (a -c application_name in PGOPTIONS would be
# overridden by it), so the deadline and the clean-up can end it (reviews of d70e30f and 1ebe9e4).
rh_pgoptions() {
    echo "-c statement_timeout=${1}s -c idle_in_transaction_session_timeout=60s"
}
# Ends every live session the rehearsal still has: its name, one of the three roles it connects as, opened since it
# started; as the superuser, itself bounded by a statement timeout.
rh_terminate_live() {
    local sql="SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '${RH_APP}'"
    sql+=" AND usename IN ('bms_owner', 'bms_app', 'postgres') AND pid <> pg_backend_pid()"
    sql+=" AND backend_start >= to_timestamp(${RH_STARTED_EPOCH})"
    docker exec -e "PGOPTIONS=-c statement_timeout=10s" "$RH_LIVE_DB" psql -U postgres -d bms -tAc "$sql" \
        > /dev/null 2>&1 || true
}
# The throwaway database as its superuser: only ever the container this rehearsal started.
rh_scratch() { docker exec "$RH_DB" psql -U postgres -d bms -v ON_ERROR_STOP=1 -tAc "$1"; }
rh_columns() {
    printf '%s' "SELECT string_agg(quote_ident(column_name), ',' ORDER BY ordinal_position) FROM information_schema.columns"
    printf '%s' " WHERE table_schema = 'public' AND table_name = '$1'"
}
# rh_where <table>: the run's rows of that table. The mappings are the ones the live run's batches name.
rh_where() {
    case "$1" in
        import_mappings) echo "id IN (${RH_MAPPINGS:-NULL})" ;;
        import_runs) echo "id = '${RH_RUN}'" ;;
        import_rows | import_issues) echo "batch_id IN (SELECT id FROM import_batches WHERE run_id = '${RH_RUN}')" ;;
        *) echo "run_id = '${RH_RUN}'" ;;
    esac
}

# The drill's pick_newest_backup (below), under another name because the drill defines it only after this mode has run;
# deploy/tests/rehearse-import-test.sh checks that the two bodies are the same.
rh_pick_newest_backup() {
    local limit name stamp best="" best_stamp=""
    limit="$(date -u -d "@$(( $1 + 3600 ))" +%Y%m%dT%H%M%SZ)"
    while IFS= read -r name; do
        [[ "$name" =~ ^bms-[a-z0-9-]+-([0-9]{8}T[0-9]{6}Z)\.dump\.enc$ ]] || continue
        stamp="${BASH_REMATCH[1]}"
        [[ "$stamp" > "$limit" ]] && continue
        if [[ -z "$best_stamp" || "$stamp" > "$best_stamp" ]]; then
            best="$name"
            best_stamp="$stamp"
        fi
    done
    printf '%s\n' "$best"
}

rh_cleanup() {
    local status=$?
    trap - EXIT INT TERM USR1
    IFS=$' \t\n'
    if [[ -n "${RH_WATCHDOG:-}" ]]; then
        kill "$RH_WATCHDOG" 2> /dev/null
        rh_end_children
        rh_terminate_live
    fi
    docker rm -f -v "$RH_API" "$RH_MIGRATE" "$RH_RCLONE" "$RH_DB" > /dev/null 2>&1 || true
    docker volume rm "$RH_VOL" > /dev/null 2>&1 || true
    docker network rm "$RH_NET" > /dev/null 2>&1 || true
    if [[ -n "${RH_TMP:-}" && -d "$RH_TMP" ]]; then
        rm -rf "$RH_TMP"
    fi
    rh_log "removed what the rehearsal made: its containers, network ${RH_NET}, volume ${RH_VOL} and temp dir"
    exit "$status"
}

# On staging the slice must hold the throwaway database and API by their limits, beside the stack as it is now:
# memory.high (else memory.max) less what the slice uses, page cache that can be dropped aside (as docker stats counts).
# rh_require_room [MB]: before the first container, both (RH_NEEDED_MB); before the API, the API's own 448 MB, since the
# throwaway database is then counted in what the slice uses.
rh_require_room() {
    local dir="${BMS_REHEARSAL_SLICE_DIR:-/sys/fs/cgroup/bms.slice}" need="${1:-$RH_NEEDED_MB}" limit current inactive free_mb
    limit="$(cat "$dir/memory.high" 2> /dev/null)"
    [[ "$limit" =~ ^[0-9]+$ ]] || limit="$(cat "$dir/memory.max" 2> /dev/null)"
    current="$(cat "$dir/memory.current" 2> /dev/null)"
    inactive="$(awk '$1 == "inactive_file" { print $2 }' "$dir/memory.stat" 2> /dev/null)"
    [[ "$limit" =~ ^[0-9]+$ && "$current" =~ ^[0-9]+$ ]] || rh_refuse "cannot read the memory of bms.slice at ${dir}"
    free_mb=$(( (limit - current + ${inactive:-0}) / 1048576 ))
    (( free_mb >= need )) \
        || rh_refuse "bms.slice has ${free_mb} MB free under its limit and the rehearsal needs ${need} MB; wait until no heavy job runs"
    rh_log "bms.slice has ${free_mb} MB free; the rehearsal needs ${need} MB of it"
}

# Nothing else that needs the slice's room runs beside a rehearsal, for its whole length (review of #882, finding 1):
#   - a rehearsal lock of its own, and the puller's and the deploy's locks (pull-staging.sh, deploy.sh), all held until
#     the script exits, so no deploy (a migrate and a second api) starts meanwhile: the puller just ends its run;
#   - the weekly drill (unchanged, so it takes no lock) is kept out by time: no rehearsal starts from Saturday 21:30 to
#     Sunday 07:00 host time, the drill's 03:30 start, its 10 minute random delay and its 3 hour limit with room for a
#     rehearsal of up to 6 hours (RH_MAX_SECONDS, which the watchdog enforces) started just before; and one already
#     running, seen by its process or its container, is refused too.
# JUSTIFICATION-A3: the review's finding 1 asks for the locks, the drill's window and the process check together.
# Asked again before the migrations and before the commit, so a long rehearsal stops rather than run into the drill.
rh_require_drill_clear() {
    local day hhmm
    day="$(date +%u)"
    hhmm=$(( 10#$(date +%H%M) ))
    if (( (day == 6 && hhmm >= 2130) || (day == 7 && hhmm < 700) )); then
        rh_refuse "the weekly restore drill runs Sunday 03:30; no rehearsal runs from Saturday 21:30 to Sunday 07:00"
    fi
}
rh_require_alone() {
    exec 7> state/rehearsal.lock
    flock --nonblock 7 || rh_refuse "another rehearsal is running"
    exec 8> state/pull.lock
    flock --nonblock 8 || rh_refuse "the puller is running (state/pull.lock); try again when it ends"
    exec 9> state/deploy.lock
    flock --nonblock 9 || rh_refuse "a deploy is running (state/deploy.lock); try again when it ends"
    rh_require_drill_clear
    if pgrep -f 'restore-drill\.sh$' > /dev/null 2>&1 \
        || docker ps --all --format '{{.Names}}' | grep -Eq '^(bms-restore-drill|bms-rehearsal-db)-'; then
        rh_refuse "a restore drill or another rehearsal is running, or one left its containers behind; try again when it ends, or remove what is left (docs/runbooks/restore-from-backup.md section 5.2)"
    fi
}

# Every write but the last goes to the throwaway database. Asked before the restore and again before the commit.
rh_require_scratch() {
    local id live_id label networks sysid live_sysid
    [[ "$RH_DB" =~ ^bms-rehearsal-db-[0-9]+$ && "$RH_DB" != "$RH_LIVE_DB" ]] || rh_refuse "${RH_DB} is not a rehearsal database"
    id="$(docker inspect -f '{{.Id}}' "$RH_DB" 2> /dev/null)"
    live_id="$(docker inspect -f '{{.Id}}' "$RH_LIVE_DB" 2> /dev/null)"
    [[ -n "$id" && "$id" != "$live_id" ]] || rh_refuse "the target container is the live database's"
    label="$(docker inspect -f '{{index .Config.Labels "bms.rehearsal"}}' "$RH_DB" 2> /dev/null)"
    [[ -n "$label" && "$label" == "$RH_LABEL" ]] || rh_refuse "the target container does not carry this rehearsal's label"
    networks="$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$RH_DB" 2> /dev/null)"
    [[ "$networks" == "${RH_NET} " ]] || rh_refuse "the target container is on other networks: ${networks}"
    sysid="$(rh_scratch "SELECT system_identifier FROM pg_control_system()" 2> /dev/null)"
    live_sysid="$(docker exec -e "PGAPPNAME=${RH_APP}" -e "PGOPTIONS=$(rh_pgoptions 60)" "$RH_LIVE_DB" psql -U postgres -d bms -tAc "SELECT system_identifier FROM pg_control_system()" 2> /dev/null)"
    [[ "$sysid" =~ ^[0-9]+$ && "$live_sysid" =~ ^[0-9]+$ && "$sysid" != "$live_sysid" ]] \
        || rh_refuse "the target database is the live cluster, or the two cannot be told apart (${sysid:-none} and ${live_sysid:-none})"
}

# rh_bg <command...>: runs it in the background and waits for it, so INT or TERM reaches the trap at once and the clean-up
# removes the container while it still runs, instead of after it (review of #882, finding 5). Its exit status.
rh_bg() {
    "$@" 0<&0 &
    wait "$!"
}

# The deadline (reviews of d98fd0b and 305673f): six hours after the start (RH_MAX_SECONDS, the length the drill's
# window leaves room for), or the next Sunday 03:20 host time, ten minutes before the weekly drill, whichever comes first
# (Uganda keeps no summer time). So the locks a rehearsal holds keep a deploy out for six hours at most.
rh_deadline() {
    local now since_midnight days sunday
    now="$(date +%s)"
    since_midnight=$(( 10#$(date +%H) * 3600 + 10#$(date +%M) * 60 + 10#$(date +%S) ))
    days=$(( (7 - $(date +%u)) % 7 ))
    (( days > 0 )) || days=7
    sunday=$(( now - since_midnight + days * 86400 + 3 * 3600 + 20 * 60 ))
    echo $(( now + RH_MAX_SECONDS < sunday ? now + RH_MAX_SECONDS : sunday ))
}

# The watchdog, a child of the script for its whole run, without the lock fds: at the deadline it sends the script USR1
# (handled as soon as its current command ends), removes the rehearsal's containers, ends the script's other children,
# so a step in the foreground that hangs (a docker exec, the copy, a decryption) ends too, and ends every live session
# the rehearsal opened, which killing a docker exec client alone would leave open (review of d70e30f). Once the one live
# write has begun it stands down: that write is bounded by its own statement timeout.
rh_watchdog() {
    local child
    trap - EXIT INT TERM
    exec 7>&- 8>&- 9>&-
    while kill -0 "$$" 2> /dev/null; do
        [[ -e "$RH_TMP/storing" ]] && return
        if (( $(date +%s) >= RH_DEADLINE )); then
            # Asked again at the last moment, so a write that has just begun is never killed for nothing.
            [[ -e "$RH_TMP/storing" ]] && return
            kill -USR1 "$$" 2> /dev/null
            docker rm -f -v "$RH_API" "$RH_MIGRATE" "$RH_RCLONE" "$RH_DB" > /dev/null 2>&1
            rh_end_children
            rh_terminate_live
            return
        fi
        sleep 2
    done
}

# Ends the script's other children and theirs (a docker client, a subshell of the copy), never the caller itself.
rh_end_children() {
    local child grandchild
    for child in $(pgrep -P "$$"); do
        [[ "$child" == "$BASHPID" ]] && continue
        for grandchild in $(pgrep -P "$child"); do
            pkill -TERM -P "$grandchild" 2> /dev/null
            kill -TERM "$grandchild" 2> /dev/null
        done
        kill -TERM "$child" 2> /dev/null
    done
}

# rh_stopped <what> <exit status>: a stop (the deadline, TERM or INT) reports what the live database holds. Before the
# one live write began, nothing; once it began, the rehearsal's row is looked up by its id: stored, not stored, or, if
# the live database does not answer, unknown, with the query that tells (reviews of d70e30f and 1ebe9e4).
rh_stopped() {
    local found query
    IFS=$' \t\n'
    if [[ -z "${RH_REHEARSAL_ID:-}" ]]; then
        rh_log "${1}; nothing is stored"
        exit "$2"
    fi
    query="SELECT count(*) FROM import_rehearsals WHERE id = '${RH_REHEARSAL_ID}'"
    found="$(rh_live "$query" 2> /dev/null)"
    case "$found" in
        1)
            rh_log "${1}, as the rehearsal was stored; it is stored on live run ${RH_RUN} (${RH_REHEARSAL_ID})"
            [[ "$2" == 1 ]] && exit "${RH_COMMIT_STATUS:-1}"
            exit "$2" ;;
        0)
            rh_log "${1}; nothing is stored" ;;
        *)
            rh_log "${1}; whether the rehearsal was stored is unknown (the live database did not answer); check with: ${query}" ;;
    esac
    exit "$2"
}

# The deadline comes by USR1 rather than a file, so a full disk cannot turn it into a plain TERM.
rh_on_deadline() { rh_stopped "FAILED: stopped at its deadline, $(date -d "@${RH_DEADLINE}" +%Y-%m-%dT%H:%M%z)" 1; }
rh_on_term() { rh_stopped "terminated" 143; }
rh_on_int() { rh_stopped "interrupted" 130; }

# rh_require_env_target <env file>: the only database a container of the rehearsal is given is the throwaway one.
rh_require_env_target() {
    local urls
    urls="$(grep -E 'jdbc:|postgres(ql)?://' "$1")"
    [[ "$urls" == "BMS_DB_URL=jdbc:postgresql://${RH_DB}:5432/bms" ]] \
        || rh_refuse "a rehearsal container would be given another database: ${urls//$'\n'/, }"
}

rehearse_import() {
    if [[ $# -ne 2 || "$1" != "--rehearse-import" ]]; then
        echo "usage: restore-drill.sh --rehearse-import <run id>   (no argument: the weekly restore drill)" >&2
        return 2
    fi
    RH_RUN="$2"
    if [[ ! "$RH_RUN" =~ $RH_UUID ]]; then
        echo "restore-drill.sh: the run id must be a UUID in lower case" >&2
        return 2
    fi
    cd "$(dirname "$0")" || return 1
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
    : "${BMS_ENVIRONMENT:?}" "${BACKUP_ENCRYPTION_KEY:?}" "${R2_ENDPOINT:?}" "${R2_ACCESS_KEY_ID:?}"
    : "${R2_SECRET_ACCESS_KEY:?}" "${R2_BACKUP_BUCKET:?}" "${RCLONE_IMAGE:?}" "${POSTGRES_IMAGE:?}"
    # Exactly staging or production (review of 305673f): any other value would skip staging's fabricated check and slice.
    [[ "$BMS_ENVIRONMENT" == "staging" || "$BMS_ENVIRONMENT" == "production" ]] \
        || rh_refuse "BMS_ENVIRONMENT is ${BMS_ENVIRONMENT}; a rehearsal runs only on staging or production"
    umask 077
    local started step status row tag initdb prefix owner_pw app_pw live_ver scratch_ver table cols scratch_cols ready
    local prelude live_script old_ifs left
    local -a cgroup=() timings=()
    started="$(rh_ms)"
    RH_DB="bms-rehearsal-db-$$"
    RH_API="bms-rehearsal-api-$$"
    RH_MIGRATE="bms-rehearsal-migrate-$$"
    RH_RCLONE="bms-rehearsal-rclone-$$"
    RH_NET="bms-rehearsal-net-$$"
    RH_VOL="bms-rehearsal-data-$$"
    RH_TMP=""
    RH_WATCHDOG=""
    trap rh_cleanup EXIT
    trap rh_on_int INT
    trap rh_on_term TERM
    trap rh_on_deadline USR1
    RH_APP="bms-rehearsal-$$"
    RH_STARTED_EPOCH=$(( $(date +%s) - 60 ))
    RH_REHEARSAL_ID=""
    RH_COMMIT_STATUS=""

    # 1. The run, read on the live database.
    row="$(rh_live "SELECT tenant_id, status, fabricated, origin FROM import_runs WHERE id = '${RH_RUN}'" 2> /dev/null)" \
        || rh_fail "cannot read import_runs on the live database"
    [[ -n "$row" ]] || rh_refuse "no import run ${RH_RUN} on the live database"
    IFS='|' read -r RH_TENANT status RH_FABRICATED RH_ORIGIN <<< "$row"
    [[ "$RH_TENANT" =~ $RH_UUID ]] || rh_fail "unexpected answer for the run: ${row}"
    [[ "$RH_ORIGIN" == "framework" ]] || rh_refuse "run ${RH_RUN} is a run of ${RH_ORIGIN}, not of the import screens"
    [[ "$status" == "in_review" || "$status" == "ready" ]] \
        || rh_refuse "run ${RH_RUN} is ${status}; only a run in review or ready is rehearsed"
    if [[ "$BMS_ENVIRONMENT" == "staging" ]]; then
        [[ "$RH_FABRICATED" == "t" ]] \
            || rh_refuse "on staging only a run its uploader marked fabricated is rehearsed; run ${RH_RUN} is not"
        cgroup=(--cgroup-parent bms.slice)
    fi
    rh_log "rehearsing run ${RH_RUN} (${status}) of business ${RH_TENANT} on ${BMS_ENVIRONMENT}"
    rh_require_alone
    # From here no docker client inherits the lock fds, so one left running after a kill (a docker exec client does not
    # end with its process in the container) can never keep a deploy locked out after the script has gone (review of
    # d70e30f). Defined only here, in the rehearsal: the drill below never runs this function.
    docker() { command docker "$@" 7>&- 8>&- 9>&-; }
    RH_DEADLINE="$(rh_deadline)"
    rh_log "it must end by $(date -d "@${RH_DEADLINE}" +%Y-%m-%dT%H:%M%z): six hours at most, and before the weekly drill"
    mkdir -p backups
    RH_TMP="$(mktemp -d "$PWD/backups/rehearsal.XXXXXX")" || rh_fail "cannot make a temp dir under backups/"
    chmod 700 "$RH_TMP"
    rh_watchdog &
    RH_WATCHDOG=$!
    RH_LIVE_API="$(docker ps --filter label=com.docker.compose.project=bms --filter label=com.docker.compose.service=api \
        --format '{{.Names}}' | head -n 1)"
    [[ -n "$RH_LIVE_API" ]] || rh_fail "no running api container of the bms project: the rehearsal runs the live API's image"
    RH_IMAGE="$(docker inspect -f '{{.Config.Image}}' "$RH_LIVE_API")" || rh_fail "cannot read the live API's image"
    tag="$(cat state/current_tag)"
    initdb="$PWD/releases/${tag}/postgres/initdb"
    [[ -d "$initdb" ]] || initdb="$PWD/postgres/initdb"
    [[ -d "$initdb" ]] || rh_fail "no initdb folder at ${initdb}"

    # 2. Room in the slice.
    if [[ "$BMS_ENVIRONMENT" == "staging" ]]; then
        rh_require_room
    fi

    # 3. The newest backup, fetched and decrypted inside the mode-0700 temp dir only.
    prefix="r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}"
    # The R2 keys go in an env file in the temp dir, never on a command line `ps` shows (review of #882, finding 3).
    printf '%s\n' RCLONE_CONFIG_R2_TYPE=s3 RCLONE_CONFIG_R2_PROVIDER=Cloudflare "RCLONE_CONFIG_R2_ENDPOINT=${R2_ENDPOINT}" \
        "RCLONE_CONFIG_R2_ACCESS_KEY_ID=${R2_ACCESS_KEY_ID}" "RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=${R2_SECRET_ACCESS_KEY}" \
        RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true > "$RH_TMP/rclone.env"
    rh_rclone() {
        docker run --rm --name "$RH_RCLONE" "${cgroup[@]}" --memory 64m --volume "${RH_TMP}:/work" \
            --env-file "$RH_TMP/rclone.env" "$RCLONE_IMAGE" "$@"
    }
    step="$(rh_ms)"
    RH_NAME="$(rh_rclone lsf --files-only "${prefix}/daily/" 2> /dev/null | rh_pick_newest_backup "$(date -u +%s)")"
    [[ -n "$RH_NAME" ]] || rh_fail "no backup found under ${prefix}/daily/"
    rh_log "newest backup: ${RH_NAME}"
    rh_bg rh_rclone copyto "${prefix}/daily/${RH_NAME}" /work/backup.dump.enc > /dev/null 2>&1 \
        || rh_fail "download of ${RH_NAME} failed"
    [[ -s "$RH_TMP/backup.dump.enc" ]] || rh_fail "downloaded file is empty"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY \
        -in "$RH_TMP/backup.dump.enc" -out "$RH_TMP/backup.dump" 2> /dev/null \
        || rh_fail "decryption failed (wrong BACKUP_ENCRYPTION_KEY or a corrupt file)"
    rm -f "$RH_TMP/backup.dump.enc"
    [[ -s "$RH_TMP/backup.dump" ]] || rh_fail "decrypted dump is empty"
    timings+=("download_ms=$(( $(rh_ms) - step ))")

    # 4. The throwaway database, on an internal network of its own, then the restore.
    step="$(rh_ms)"
    RH_TOKEN="$(openssl rand -hex 24)"
    owner_pw="$(openssl rand -hex 24)"
    app_pw="$(openssl rand -hex 24)"
    docker network create --internal "$RH_NET" > /dev/null || rh_fail "cannot create the network ${RH_NET}"
    docker volume create "$RH_VOL" > /dev/null || rh_fail "cannot create the volume ${RH_VOL}"
    printf '%s\n' "POSTGRES_PASSWORD=$(openssl rand -hex 24)" POSTGRES_DB=bms "BMS_OWNER_PASSWORD=${owner_pw}" \
        "BMS_APP_PASSWORD=${app_pw}" > "$RH_TMP/db.env"
    rh_log "starting a throwaway postgres (${RH_DB})"
    # The label is the token's SHA-256, so `docker inspect` never shows the token itself.
    RH_LABEL="$(printf '%s' "$RH_TOKEN" | sha256sum | cut -c1-64)"
    docker run --detach --name "$RH_DB" "${cgroup[@]}" --memory 256m --memory-swap 256m --cpus 1.0 \
        --network "$RH_NET" --label "bms.rehearsal=${RH_LABEL}" --env-file "$RH_TMP/db.env" \
        --volume "${RH_VOL}:/var/lib/postgresql/data" --volume "${initdb}:/docker-entrypoint-initdb.d:ro" \
        "$POSTGRES_IMAGE" postgres -c shared_buffers=64MB -c max_connections=20 -c work_mem=4MB \
        -c maintenance_work_mem=64MB -c log_error_verbosity=terse > /dev/null \
        || rh_fail "could not start the throwaway postgres"
    ready=0
    for _ in $(seq 1 90); do
        if docker logs "$RH_DB" 2>&1 | grep -q "init process complete" \
            && docker exec "$RH_DB" pg_isready -U bms_owner -d bms > /dev/null 2>&1; then
            ready=1
            break
        fi
        sleep 2
    done
    [[ "$ready" == "1" ]] || rh_fail "the throwaway postgres did not become ready"
    rh_require_scratch
    rh_log "restoring"
    rh_bg docker exec -i "$RH_DB" pg_restore -U bms_owner -d bms --exit-on-error < "$RH_TMP/backup.dump" > /dev/null \
        2> "$RH_TMP/pg_restore.err" \
        || rh_fail "pg_restore failed: $(head -c 2000 "$RH_TMP/pg_restore.err" | tr '\n' ' ')"
    rm -f "$RH_TMP/backup.dump"
    [[ "$(rh_scratch "SELECT count(*) FROM tenants WHERE id = '${RH_TENANT}'")" == "1" ]] \
        || rh_fail "business ${RH_TENANT} is not in ${RH_NAME}; rehearse after the next nightly backup"
    timings+=("restore_ms=$(( $(rh_ms) - step ))")

    # 5. The live API's migrations, then the run's staged rows from the live database, then the token.
    step="$(rh_ms)"
    printf '%s\n' "BMS_DB_URL=jdbc:postgresql://${RH_DB}:5432/bms" BMS_OWNER_USER=bms_owner \
        "BMS_OWNER_PASSWORD=${owner_pw}" "JAVA_TOOL_OPTIONS=${RH_JAVA_OPTS}" > "$RH_TMP/migrate.env"
    rh_require_env_target "$RH_TMP/migrate.env"
    rh_require_drill_clear
    rh_bg docker run --rm --name "$RH_MIGRATE" "${cgroup[@]}" --memory 448m --memory-swap 448m --cpus 1.0 \
        --network "$RH_NET" --env-file "$RH_TMP/migrate.env" "$RH_IMAGE" migrate \
        || rh_fail "the live API's migrations failed on the throwaway database"
    live_ver="$(rh_live "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
    scratch_ver="$(rh_scratch "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
    [[ -n "$live_ver" && "$live_ver" == "$scratch_ver" ]] \
        || rh_fail "the throwaway database is at Flyway ${scratch_ver:-none}, the live one at ${live_ver:-none}"
    timings+=("migrate_ms=$(( $(rh_ms) - step ))")

    step="$(rh_ms)"
    RH_MAPPINGS="$(rh_live "SELECT string_agg(DISTINCT quote_literal(mapping_id::text), ',') FROM import_batches WHERE run_id = '${RH_RUN}' AND mapping_id IS NOT NULL")"
    # One transaction on the throwaway side, committed only when the live read ended well (review of #882, finding 2).
    prelude="BEGIN;"$'\n'"SET LOCAL session_replication_role = replica;"$'\n'
    for table in import_issues import_rows import_issue_resolutions import_refs import_signoffs import_reconciliations \
        import_rehearsals import_mappings import_batches import_runs; do
        prelude+="DELETE FROM ${table} WHERE $(rh_where "$table");"$'\n'
    done
    prelude+="CREATE TABLE bms_rehearsal_marker (token text NOT NULL);"$'\n'
    prelude+="INSERT INTO bms_rehearsal_marker (token) VALUES ('${RH_TOKEN}');"$'\n'
    prelude+="GRANT SELECT ON bms_rehearsal_marker TO bms_app;"$'\n'
    live_script="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"$'\n'
    for table in "${RH_TABLES[@]}"; do
        cols="$(rh_live "$(rh_columns "$table")")"
        scratch_cols="$(rh_scratch "$(rh_columns "$table")")"
        [[ -n "$cols" && "$cols" == "$scratch_cols" ]] \
            || rh_fail "the columns of ${table} differ between the live and the throwaway database"
        live_script+="\\echo 'COPY ${table} (${cols}) FROM STDIN;'"$'\n'
        live_script+="COPY (SELECT ${cols} FROM ${table} WHERE $(rh_where "$table")) TO STDOUT;"$'\n'
        live_script+="\\echo '\\\\.'"$'\n'
    done
    live_script+="COMMIT;"$'\n'
    rh_log "copying the run's staged rows into the throwaway database"
    left=$(( RH_DEADLINE - $(date +%s) > 1 ? RH_DEADLINE - $(date +%s) : 1 ))
    {
        printf '%s' "$prelude"
        # A live read that fails part way ends the stream with ROLLBACK, never COMMIT, and leaves its mark for below.
        # The live session ends by the deadline whatever its client does: psql is killed inside the container then, and
        # each statement is held to at most 30 minutes on the server.
        if docker exec -i -e "PGAPPNAME=${RH_APP}" -e "PGOPTIONS=$(rh_pgoptions $(( left < 1800 ? left : 1800 )))" \
            "$RH_LIVE_DB" timeout -s KILL "$left" psql -U bms_owner -d bms -q -v ON_ERROR_STOP=1 <<< "$live_script"; then
            printf '%s\n' "COMMIT;"
        else
            : > "$RH_TMP/live-read.failed"
            printf '%s\n' "ROLLBACK;"
        fi
    } | docker exec -i "$RH_DB" psql -U postgres -d bms -q -v ON_ERROR_STOP=1 > /dev/null \
        || rh_fail "copying the run's staged rows failed"
    [[ ! -e "$RH_TMP/live-read.failed" ]] || rh_fail "reading the run's staged rows on the live database failed"
    # Every table holds the run's rows both sides do: the same number and the same ids.
    for table in "${RH_TABLES[@]}"; do
        cols="SELECT count(*) || ':' || coalesce(md5(string_agg(id::text, ',' ORDER BY id)), '-') FROM ${table}"
        cols+=" WHERE $(rh_where "$table")"
        [[ "$(rh_live "$cols")" == "$(rh_scratch "$cols")" ]] \
            || rh_fail "${table} does not hold the same rows of the run in the live and the throwaway database"
    done
    timings+=("copy_ms=$(( $(rh_ms) - step ))")

    # 6. The real commit, in a short-lived API container that reaches the throwaway database only.
    # The live API's settings, with the database switched, the scheduler and clean-ups off, no import key (the source is
    # never read) and every key of an outside service (object storage, mail, SMS, payments, push, AI, chat) replaced by a
    # placeholder (review of #882, finding 6). Kept: the data key and the signing key, which the application needs to
    # start and to read its own sealed columns. The container has no route out either way.
    docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$RH_LIVE_API" \
        | grep -Ev '^(BMS_DB_URL|BMS_APP_USER|BMS_APP_PASSWORD|BMS_SCHEDULER_ENABLED|BMS_IMPORTS_CLEANUP_ENABLED|BMS_DOCUMENTS_ORPHAN_SWEEP_ENABLED|BMS_REHEARSAL_TOKEN|BMS_IMPORT_KEY|JAVA_TOOL_OPTIONS)=|^$' \
        | awk -F= '$1 != "BMS_DATA_KEY" && $1 ~ /^R2_|SECRET|PASSWORD|PRIVATE|WEBHOOK|ACCESS_KEY|_TOKEN$|_KEY$/ {
                print $1 "=rehearsal-placeholder"; next } { print }' \
            > "$RH_TMP/api.env" || rh_fail "cannot read the live API's settings"
    printf '%s\n' "BMS_DB_URL=jdbc:postgresql://${RH_DB}:5432/bms" BMS_APP_USER=bms_app "BMS_APP_PASSWORD=${app_pw}" \
        BMS_SCHEDULER_ENABLED=false BMS_IMPORTS_CLEANUP_ENABLED=false BMS_DOCUMENTS_ORPHAN_SWEEP_ENABLED=false \
        BMS_IMPORT_KEY= "BMS_REHEARSAL_TOKEN=${RH_TOKEN}" "JAVA_TOOL_OPTIONS=${RH_JAVA_OPTS}" >> "$RH_TMP/api.env"
    rh_require_env_target "$RH_TMP/api.env"
    rh_require_scratch
    rh_require_drill_clear
    [[ "$BMS_ENVIRONMENT" != "staging" ]] || rh_require_room 448
    # A commit that cannot end before the deadline is not started: it would only be thrown away (review of d70e30f).
    (( RH_DEADLINE - $(date +%s) >= 1800 )) \
        || rh_fail "less than 30 minutes before its deadline; the commit is not started and nothing is stored"
    rh_log "running the real commit in ${RH_API}"
    old_ifs="$IFS"
    IFS=,
    rh_bg docker run --rm --name "$RH_API" "${cgroup[@]}" --memory 448m --memory-swap 448m --cpus 1.0 \
        --network "$RH_NET" --env-file "$RH_TMP/api.env" "$RH_IMAGE" \
        rehearse-import --tenant "$RH_TENANT" --run "$RH_RUN" --backup "$RH_NAME" --timings "${timings[*]}"
    status=$?
    IFS="$old_ifs"
    case "$status" in
        0) ;;
        1) rh_log "the rehearsal's commit did not complete; its outcome is stored on the run" ;;
        3) rh_refuse "rehearse-import refused the run (its reason is above); nothing is stored on the live run" ;;
        *) rh_fail "rehearse-import exited ${status}; nothing is stored on the live run" ;;
    esac

    # 7. The one write on the live database: the rehearsal's row, as bms_app under the business's row-level security.
    [[ "$(rh_scratch "SELECT count(*) FROM import_rehearsals WHERE run_id = '${RH_RUN}'")" == "1" ]] \
        || rh_fail "the throwaway database does not hold one rehearsal of the run"
    cols="$(rh_scratch "$(rh_columns import_rehearsals)")"
    [[ -n "$cols" ]] || rh_fail "the throwaway database has no import_rehearsals"
    # From here a deadline (USR1) is answered by what the live database holds, the row's id known first; the watchdog
    # stands down, and the write is bounded by its own statement timeout.
    RH_REHEARSAL_ID="$(rh_scratch "SELECT id FROM import_rehearsals WHERE run_id = '${RH_RUN}'")"
    RH_COMMIT_STATUS="$status"
    : > "$RH_TMP/storing"
    docker exec "$RH_DB" psql -U postgres -d bms -v ON_ERROR_STOP=1 \
        -c "COPY (SELECT ${cols} FROM import_rehearsals WHERE run_id = '${RH_RUN}') TO STDOUT" \
        | docker exec -i -e "PGAPPNAME=${RH_APP}" -e "PGOPTIONS=$(rh_pgoptions 120)" "$RH_LIVE_DB" \
            timeout -s KILL 150 psql -U bms_app -d bms -q -v ON_ERROR_STOP=1 \
            -c "SELECT set_config('app.tenant_id', '${RH_TENANT}', false)" \
            -c "CREATE TEMP TABLE rehearsal_in AS SELECT ${cols} FROM import_rehearsals WITH NO DATA" \
            -c "COPY rehearsal_in FROM STDIN" \
            -c "INSERT INTO import_rehearsals (${cols}) SELECT ${cols} FROM rehearsal_in" > /dev/null \
        || rh_fail "could not store the rehearsal on the live run"
    rh_log "stored the rehearsal on live run ${RH_RUN}; $(( ($(rh_ms) - started) / 1000 )) s in all"
    [[ "$status" == "0" ]] || exit 1
    exit 0
}

# The rehearsal only with an argument; with none, the weekly drill below.
if (( $# > 0 )); then
    rehearse_import "$@"
    exit $?
fi

cd "$(dirname "$0")" || exit 1
mkdir -p state backups
set -a
# shellcheck disable=SC1091
. ./.env
set +a
: "${BMS_ENVIRONMENT:?}" "${BACKUP_ENCRYPTION_KEY:?}" "${R2_ENDPOINT:?}" "${R2_ACCESS_KEY_ID:?}"
: "${R2_SECRET_ACCESS_KEY:?}" "${R2_BACKUP_BUCKET:?}" "${RCLONE_IMAGE:?}" "${POSTGRES_IMAGE:?}"

IMAGE_TAG="$(cat state/current_tag)"
# Staging stages each release under releases/<tag>/; production untars straight into /opt/bms.
INITDB="$PWD/releases/${IMAGE_TAG}/postgres/initdb"
[[ -d "$INITDB" ]] || INITDB="$PWD/postgres/initdb"
PREFIX="r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}"
DRILL="bms-restore-drill-$$"
ENC="backups/drill-$$.dump.enc"
DUMP="backups/drill-$$.dump"

STARTED="$(date -u +%s)"

log() { echo "[drill $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
elapsed() { echo $(( $(date -u +%s) - STARTED )); }
# record <state file> <line>: the line starts with the time, which monitor.sh reads.
# Write-then-rename for the state file (ADR-018); the history and the log keep their last 200 lines.
record() {
    printf '%s\n' "$2" > "$1.tmp.$$" && mv -f "$1.tmp.$$" "$1"
    printf '%s\n' "$2" >> state/restore-drill-history.log
    trim state/restore-drill-history.log
    trim state/restore-drill.log
    printf '%s\n' "$2" > "backups/drill-result-$$.txt"
    if declare -F rclone > /dev/null; then
        rclone copyto "/backups/drill-result-$$.txt" "${PREFIX}/drill/last_restore_drill.txt" > /dev/null 2>&1 \
            || log "could not upload the result to ${PREFIX}/drill/"
    fi
    rm -f "backups/drill-result-$$.txt"
}
# trim <file>: keep the last 200 lines.
trim() {
    [[ -f "$1" ]] || return 0
    tail -n 200 "$1" > "$1.tmp.$$" && cat "$1.tmp.$$" > "$1"
    rm -f "$1.tmp.$$"
}
fail() {
    log "FAILED: $*"
    record state/last_restore_drill_error "$(date -u +%Y-%m-%dT%H:%M:%SZ) FAILED seconds=$(elapsed) $*"
    exit 1
}
cleanup() {
    docker rm -f -v "$DRILL" > /dev/null 2>&1 || true
    rm -f "$ENC" "$DUMP"
}
trap cleanup EXIT

[[ -d "$INITDB" ]] || fail "no initdb folder at ${INITDB}"

# Staging runs inside bms.slice; everywhere else the memory caps on each container (64 MB rclone, 384 MB
# postgres) are the limit, so the drill cannot starve the live stack on the 4 GB production VM.
CGROUP_ARGS=()
if [[ "$BMS_ENVIRONMENT" == "staging" ]]; then
    CGROUP_ARGS=(--cgroup-parent bms.slice)
fi

rclone() {
    docker run --rm "${CGROUP_ARGS[@]}" --memory 64m \
        --volume "$PWD/backups:/backups" \
        --env RCLONE_CONFIG_R2_TYPE=s3 \
        --env RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
        --env RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" \
        --env RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
        --env RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
        --env RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
        "$RCLONE_IMAGE" "$@"
}

# The newest backup name whose timestamp is not in the future (one hour of clock skew allowed). An object
# under daily/ with a far-future name, planted or mistaken, would otherwise sort last forever and keep the
# freshness check green and the drill restoring the same file; names that are not backup.sh's are skipped too.
# Reads names on stdin, takes the current time in epoch seconds. Kept identical in monitor.sh and
# restore-drill.sh (deploy/tests/monitor-test.sh checks that).
pick_newest_backup() {
    local limit name stamp best="" best_stamp=""
    limit="$(date -u -d "@$(( $1 + 3600 ))" +%Y%m%dT%H%M%SZ)"
    while IFS= read -r name; do
        [[ "$name" =~ ^bms-[a-z0-9-]+-([0-9]{8}T[0-9]{6}Z)\.dump\.enc$ ]] || continue
        stamp="${BASH_REMATCH[1]}"
        [[ "$stamp" > "$limit" ]] && continue
        if [[ -z "$best_stamp" || "$stamp" > "$best_stamp" ]]; then
            best="$name"
            best_stamp="$stamp"
        fi
    done
    printf '%s\n' "$best"
}

NAME="$(rclone lsf --files-only "${PREFIX}/daily/" 2> /dev/null | pick_newest_backup "$(date -u +%s)")"
[[ -n "$NAME" ]] || fail "no backup found under ${PREFIX}/daily/"
log "newest backup: ${NAME}"
rclone copyto "${PREFIX}/daily/${NAME}" "/backups/$(basename "$ENC")" > /dev/null 2>&1 || fail "download of ${NAME} failed"
[[ -s "$ENC" ]] || fail "downloaded file is empty"

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY -in "$ENC" -out "$DUMP" 2> /dev/null \
    || fail "decryption failed (wrong BACKUP_ENCRYPTION_KEY or a corrupt file)"
[[ -s "$DUMP" ]] || fail "decrypted dump is empty"

rand() { openssl rand -base64 24 | tr -d '/+=' ; }
log "starting a throwaway postgres (${DRILL})"
docker run --detach --name "$DRILL" "${CGROUP_ARGS[@]}" --memory 384m --network none \
    --env POSTGRES_PASSWORD="$(rand)" --env POSTGRES_DB=bms \
    --env BMS_OWNER_PASSWORD="$(rand)" --env BMS_APP_PASSWORD="$(rand)" \
    --volume "${INITDB}:/docker-entrypoint-initdb.d:ro" \
    "$POSTGRES_IMAGE" > /dev/null || fail "could not start the throwaway postgres"

ready=0
for _ in $(seq 1 90); do
    if docker logs "$DRILL" 2>&1 | grep -q "init process complete" && docker exec "$DRILL" pg_isready -U bms_owner -d bms > /dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 2
done
[[ "$ready" == "1" ]] || fail "the throwaway postgres did not become ready"

log "restoring"
docker exec -i "$DRILL" pg_restore -U bms_owner -d bms --exit-on-error < "$DUMP" > /dev/null 2> state/restore-drill-pg_restore.err \
    || { log "pg_restore stderr (host only, never sent or uploaded): $(head -c 2000 state/restore-drill-pg_restore.err | tr '\n' ' ')"; false; } \
    || fail "pg_restore failed, see state/restore-drill.log"

q() { docker exec "$DRILL" psql -U bms_owner -d bms -tAc "$1"; }
live() { docker exec bms-postgres-1 psql -U bms_owner -d bms -tAc "$1"; }

restored_ver="$(q "SELECT max(version::int) FROM flyway_schema_history WHERE success")" || fail "no Flyway history in the restore"
live_ver="$(live "SELECT max(version::int) FROM flyway_schema_history WHERE success")" || fail "cannot read the live Flyway version"
[[ -n "$restored_ver" ]] || fail "empty Flyway history in the restore"
(( restored_ver <= live_ver )) || fail "restored Flyway version ${restored_ver} is ahead of live ${live_ver}"
tenants="$(q "SELECT count(*) FROM tenants")"
users="$(q "SELECT count(*) FROM users")"
audit="$(q "SELECT count(*) FROM audit_log")"
(( tenants > 0 )) || fail "the restore holds no tenants"

# The application role must still read a tenant's rows under row-level security: grants and RLS
# survived the dump, not only the data.
first_tenant="$(q "SELECT id FROM tenants ORDER BY created_at LIMIT 1")"
app_users="$(docker exec "$DRILL" psql -U bms_app -d bms -tAc "SELECT set_config('app.tenant_id', '${first_tenant}', false); SELECT count(*) FROM users" 2> /dev/null | tail -n 1)"
[[ "$app_users" =~ ^[0-9]+$ ]] || fail "bms_app cannot read users under row-level security"

seconds="$(elapsed)"
rto_ok=yes
(( seconds <= 7200 )) || rto_ok=no
record state/last_restore_drill "$(printf '%s OK %s seconds=%s rto_ok=%s flyway=%s live_flyway=%s tenants=%s users=%s audit=%s app_visible_users=%s' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NAME" "$seconds" "$rto_ok" "$restored_ver" "$live_ver" "$tenants" "$users" "$audit" "$app_users")"
rm -f state/last_restore_drill_error state/restore-drill-pg_restore.err
log "OK: $(cat state/last_restore_drill)"
