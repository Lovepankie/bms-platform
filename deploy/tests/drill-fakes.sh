#!/usr/bin/env bash
# A fake host for deploy/restore-drill.sh (issue #138), sourced by restore-drill-test.sh and rehearse-import-test.sh.
# No network, no Docker, no PostgreSQL: docker, openssl, date and sleep are stubs on PATH, and every docker call is
# appended to $FAKE/calls.log, one line each, so a test can compare what the script did step by step.
#
#   fake_host <work dir>   makes <work dir>/opt (the host's /opt/bms, with .env, state/current_tag and an initdb
#                          folder), <work dir>/bin (the stubs) and <work dir>/fake (the stub's state); sets HOST and FAKE.
#   run_script <args...>   runs $HOST/restore-drill.sh with the stubs first on PATH; sets STATUS and OUTPUT.
#
# The stub answers as a healthy staging host by default. Switches (environment variables) turn one step bad:
#   FAKE_DECRYPT_FAIL=1     openssl enc fails;          FAKE_RESTORE_FAIL=1   pg_restore fails;
#   FAKE_SAME_SYSID=1       the throwaway database answers the live cluster's system identifier;
#   FAKE_SAME_ID=1          docker inspect gives the throwaway the live container's id;
#   FAKE_RUN_ROW=<t|s|f|o>  the live run's tenant, status, fabricated flag and origin ("" for no such run);
#   FAKE_API_EXIT=<n>       the rehearsal API container's exit status;  FAKE_MIGRATE_FAIL=1  migrate fails;
#   FAKE_ENV_DB_URL=<url>   an extra SPRING_DATASOURCE_URL in the live API's environment;
#   FAKE_LIVE_READ_FAIL=1   the live read of the run's rows dies part way; FAKE_COUNT_MISMATCH=1  the copy lost rows;
#   FAKE_API_SIGNAL=<sig>   the commit sends the script that signal; FAKE_API_HANG=<s>  and then runs on for s seconds;
#   FAKE_ADVANCE_TO=<epoch> the clock jumps to that moment while the commit runs;
#   FAKE_COPY_ADVANCE_TO=<epoch> it jumps there while the copy into the throwaway database hangs;
#   FAKE_LIVE_HANG_ADVANCE_TO=<epoch> it jumps there while the live read of the run hangs;
#   FAKE_WRITE_SIGNAL=<sig> the live write sends the script that signal once the row is stored;
#   FAKE_LIVE_SILENT=1      the live database does not answer the read-back of the rehearsal's row.
# JUSTIFICATION-A2: the fake host is shared by two test files and is not a tiny helper.

FAKE_EPOCH=1793000000
FAKE_LIVE_SYSID=7400000000000000001
FAKE_TENANT=0b5e7a2c-0000-4000-8000-00000000a001

fake_host() {
    WORK_DIR="$1"
    HOST="$WORK_DIR/opt"
    FAKE="$WORK_DIR/fake"
    FAKE_HOST="$HOST"
    REAL_SLEEP="$(command -v sleep)"
    export FAKE FAKE_EPOCH FAKE_LIVE_SYSID FAKE_TENANT FAKE_HOST REAL_SLEEP
    mkdir -p "$HOST/state" "$HOST/postgres/initdb" "$WORK_DIR/bin" "$FAKE"
    : > "$FAKE/calls.log"
    : > "$FAKE/containers"
    : > "$FAKE/networks"
    : > "$FAKE/volumes"
    cp "$TESTS_HERE/../restore-drill.sh" "$HOST/restore-drill.sh"
    chmod 755 "$HOST/restore-drill.sh"
    printf '%s\n' 'fake-tag' > "$HOST/state/current_tag"
    printf '%s\n' '#!/bin/sh' > "$HOST/postgres/initdb/01-roles.sh"
    cat > "$HOST/.env" <<'ENV'
BMS_ENVIRONMENT=staging
BACKUP_ENCRYPTION_KEY=fabricated-backup-key
R2_ENDPOINT=https://fake-r2.invalid
R2_ACCESS_KEY_ID=fake-access
R2_SECRET_ACCESS_KEY=fake-secret
R2_BACKUP_BUCKET=fake-bucket
RCLONE_IMAGE=rclone/rclone:fake
POSTGRES_IMAGE=postgres:fake
ENV
    fake_stubs "$WORK_DIR/bin"
}

# shellcheck disable=SC2034 # OUTPUT and STATUS are read by the sourcing test.
run_script() {
    OUTPUT="$(PATH="$WORK_DIR/bin:$PATH" bash "$HOST/restore-drill.sh" "$@" 2>&1)"
    STATUS=$?
}

fake_stubs() {
    local bin="$1" real_date
    real_date="$(command -v date)"
    # A fixed clock, except when the caller converts a given time (-d).
    cat > "$bin/date" <<STUB
#!/usr/bin/env bash
for a in "\$@"; do [[ "\$a" == -d* ]] && exec $real_date "\$@"; done
# A test moves the clock by writing \$FAKE/epoch.
now="\${FAKE_EPOCH}"
if [[ -s "\$FAKE/epoch" ]]; then now="\$(cat "\$FAKE/epoch")"; fi
exec $real_date -d "@\${now}" "\$@"
STUB
    cat > "$bin/sleep" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
    cat > "$bin/openssl" <<'STUB'
#!/usr/bin/env bash
case "$1" in
    rand) [[ "$2" == "-hex" ]] && echo "f00dfeedf00dfeedf00dfeedf00dfeedf00dfeedf00dfeed" || echo "ZmFicmljYXRlZHJhbmRvbXBhc3M=" ;;
    enc)
        [[ -n "${FAKE_DECRYPT_FAIL:-}" ]] && exit 1
        in="" out=""
        while (( $# )); do
            case "$1" in -in) in="$2"; shift ;; -out) out="$2"; shift ;; esac
            shift
        done
        echo "decrypted $(basename "$in")" > "$out"
        stat -c '%a %n' "$(dirname "$out")" >> "$FAKE/decrypt-dirs" ;;
    *) echo "stub openssl: unexpected $*" >&2; exit 2 ;;
esac
STUB
    cat > "$bin/docker" <<'STUB'
#!/usr/bin/env bash
# Logs the call, then answers it as a healthy host would.
printf '%s\n' "$*" >> "$FAKE/calls.log"
live_db=bms-postgres-1
# The script itself: the topmost ancestor still running restore-drill.sh (its subshells carry the same command line).
script_pid() {
    local p="$PPID" q
    while q="$(ps -o ppid= -p "$p" | tr -d ' ')" && grep -q restore-drill /proc/"$q"/cmdline 2> /dev/null; do
        p="$q"
    done
    echo "$p"
}
has() { local n; for n in "${@:2}"; do [[ "$n" == "$1" ]] && return 0; done; return 1; }
arg_after() { local want="$1"; shift; while (( $# )); do [[ "$1" == "$want" ]] && { echo "$2"; return; }; shift; done; }

case "$1" in
    run)
        image="" name="" network="" label="" envfile=""
        declare -A mounts=()
        shift
        while (( $# )); do
            case "$1" in
                --rm|--detach) ;;
                --name) name="$2"; shift ;;
                --network) network="$2"; shift ;;
                --label) label="$2"; shift ;;
                --env-file) envfile="$2"; shift ;;
                --volume) mounts["${2#*:}"]="${2%%:*}"; mounts["${2#*:}"]="${mounts["${2#*:}"]%:ro}"; shift ;;
                --cgroup-parent|--memory|--memory-swap|--cpus|--env|--user) shift ;;
                -*) ;;
                *) image="$1"; shift; break ;;
            esac
            shift
        done
        case "$image" in
            rclone/*)
                if [[ "$1" == "lsf" ]]; then
                    printf '%s\n' bms-staging-20261020T011500Z.dump.enc bms-staging-20261024T011500Z.dump.enc \
                        bms-staging-20991231T000000Z.dump.enc not-a-backup.txt
                elif [[ "$1" == "copyto" ]]; then
                    dest="$3"
                    for m in "${!mounts[@]}"; do
                        [[ "$dest" == "$m"/* ]] && { echo "encrypted $2" > "${mounts[$m]}${dest#"$m"}"; break; }
                    done
                fi
                exit 0 ;;
            postgres:*)
                echo "$name|${label#bms.rehearsal=}|$network" >> "$FAKE/containers"
                echo "container-id-$name" ;;
            *)
                [[ -n "$envfile" ]] && cp "$envfile" "$FAKE/api-env.seen"
                if has migrate "$@"; then
                    [[ -n "${FAKE_MIGRATE_FAIL:-}" ]] && { echo "migrate: FAILED: fabricated"; exit 1; }
                    # Migrations that take most of the rehearsal's time.
                    [[ -n "${FAKE_MIGRATE_ADVANCE_TO:-}" ]] && echo "$FAKE_MIGRATE_ADVANCE_TO" > "$FAKE/epoch"
                    echo "migrate: 1 migration(s) applied, schema now at 146"
                elif has rehearse-import "$@"; then
                    echo "rehearsal of the import run: committed (fabricated report)"
                    # An operator's Ctrl-C or a stop while the commit runs: the signal reaches the script.
                    # Whether the puller's and the deploy's locks are held while the commit runs.
                    for lock in pull deploy rehearsal; do
                        if flock --nonblock "$FAKE_HOST/state/$lock.lock" true; then echo "$lock free"; else echo "$lock held"; fi
                    done > "$FAKE/locks-during-commit"
                    [[ -n "${FAKE_API_SIGNAL:-}" ]] && kill -s "$FAKE_API_SIGNAL" "$(script_pid)"
                    # A commit that runs past the deadline: the clock moves on while it runs.
                    [[ -n "${FAKE_ADVANCE_TO:-}" ]] && echo "$FAKE_ADVANCE_TO" > "$FAKE/epoch"
                    # A commit that keeps running: the script must not wait for it before it cleans up.
                    if [[ -n "${FAKE_API_HANG:-}" ]]; then
                        exec > /dev/null 2>&1
                        "$REAL_SLEEP" "$FAKE_API_HANG"
                    fi
                    exit "${FAKE_API_EXIT:-0}"
                fi ;;
        esac ;;
    logs) echo "PostgreSQL init process complete; ready for start up." ;;
    ps)
        if [[ "$*" == *"com.docker.compose.service=api"* ]]; then
            echo "bms-api-1"
        else
            cut -d'|' -f1 "$FAKE/containers"
        fi ;;
    inspect)
        format="$(arg_after -f "$@")"; target="${*: -1}"
        case "$format" in
            *Config.Image*) echo "ghcr.io/fake/bms-platform-api@sha256:fake" ;;
            *Config.Env*)
                printf '%s\n' SPRING_PROFILES_ACTIVE=server BMS_DB_URL=jdbc:postgresql://postgres:5432/bms \
                    BMS_APP_USER=bms_app BMS_APP_PASSWORD=live-app-secret BMS_ENVIRONMENT=staging \
                    BMS_SCHEDULER_ENABLED=true "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50" \
                    BMS_DATA_KEY=live-data-key BMS_TOKEN_SIGNING_JWK=live-jwk BMS_IMPORT_KEY=live-import-key \
                    BMS_SMTP_PASSWORD=live-smtp-secret R2_DOCUMENTS_SECRET_ACCESS_KEY=live-r2-secret \
                    BMS_RESEND_API_KEY=live-resend-secret BMS_TELEGRAM_BOT_TOKEN=live-telegram-secret \
                    BMS_PAY_SECRET_HASH=live-pay-secret BMS_PUSH_VAPID_KEY=live-vapid-secret
                if [[ -n "${FAKE_ENV_DB_URL:-}" ]]; then echo "SPRING_DATASOURCE_URL=${FAKE_ENV_DB_URL}"; fi ;;
            *Id*)
                if [[ "$target" == "$live_db" || -n "${FAKE_SAME_ID:-}" ]]; then echo "live-container-id"; else echo "id-$target"; fi ;;
            *Labels*) awk -F'|' -v n="$target" '$1 == n { print $2 }' "$FAKE/containers" ;;
            *Networks*) awk -F'|' -v n="$target" '$1 == n { print $3 " " }' "$FAKE/containers" ;;
        esac ;;
    network)
        case "$2" in create) echo "${*: -1}" >> "$FAKE/networks" ;; rm) sed -i "/^$3\$/d" "$FAKE/networks" ;; esac ;;
    volume)
        case "$2" in create) echo "${*: -1}" >> "$FAKE/volumes" ;; rm) sed -i "/^$3\$/d" "$FAKE/volumes" ;; esac ;;
    rm)
        for c in "${@:2}"; do [[ "$c" == -* ]] || sed -i "/^$c|/d" "$FAKE/containers"; done ;;
    exec)
        shift
        interactive=""
        while [[ "$1" == -* ]]; do
            case "$1" in
                -i) interactive=1 ;;
                -e | --env) shift ;;
            esac
            shift
        done
        container="$1"; shift
        sql="$*"
        case "$sql" in
            pg_isready*) exit 0 ;;
            pg_restore*)
                cat > /dev/null
                [[ -n "${FAKE_RESTORE_FAIL:-}" ]] && { echo "pg_restore: error: fabricated failure" >&2; exit 1; }
                exit 0 ;;
        esac
        if [[ -n "$interactive" ]]; then
            if [[ "$container" == "$live_db" && "$(arg_after -U "$@")" == "bms_owner" ]]; then
                # The read of the run's rows: echo what psql would print, a row for each COPY.
                # A live read that stops answering while the clock passes the deadline: the client is killed, the
                # server session (here, nothing) is what the terminate must end.
                if [[ -n "${FAKE_LIVE_HANG_ADVANCE_TO:-}" ]]; then
                    echo "$FAKE_LIVE_HANG_ADVANCE_TO" > "$FAKE/epoch"
                    exec > /dev/null 2>&1
                    "$REAL_SLEEP" 30
                fi
                copies=0
                tee -a "$FAKE/live-reads" | while IFS= read -r line; do
                    case "$line" in
                        "\\echo '"*) line="${line#\\echo \'}"; printf '%s\n' "${line%\'}" | sed 's/\\\\/\\/g' ;;
                        "COPY ("*)
                            copies=$(( copies + 1 ))
                            # The live session dies part way through the second table's rows.
                            if [[ -n "${FAKE_LIVE_READ_FAIL:-}" && "$copies" == 2 ]]; then
                                echo "half a staged row"
                                exit 1
                            fi
                            echo "staged row of the live run" ;;
                    esac
                done
                exit "${PIPESTATUS[1]}"
            elif [[ "$container" == "$live_db" ]]; then
                cat >> "$FAKE/live-writes"
                # The deadline arrives just as the row is stored.
                [[ -n "${FAKE_WRITE_SIGNAL:-}" ]] && kill -s "$FAKE_WRITE_SIGNAL" "$(script_pid)"
            else
                cat >> "$FAKE/copy-stream"
                # A copy that hangs while the clock passes the deadline.
                if [[ -n "${FAKE_COPY_ADVANCE_TO:-}" ]]; then
                    echo "$FAKE_COPY_ADVANCE_TO" > "$FAKE/epoch"
                    exec > /dev/null 2>&1
                    "$REAL_SLEEP" 30
                fi
            fi
            exit 0
        fi
        case "$sql" in
            *"count(*) || ':'"*)
                # The run's rows in a table, live and copied: the same unless a test says the copy lost some.
                if [[ -n "${FAKE_COUNT_MISMATCH:-}" && "$container" != "$live_db" ]]; then echo "2:0f0f"; else echo "3:0f0f"; fi ;;
            *pg_terminate_backend*) echo 0 ;;
            *"SELECT id FROM import_rehearsals"*) echo "0b5e7a2c-0000-4000-8000-00000000d001" ;;
            *pg_control_system*)
                if [[ "$container" == "$live_db" || -n "${FAKE_SAME_SYSID:-}" ]]; then echo "$FAKE_LIVE_SYSID"; else echo "7400000000000000999"; fi ;;
            *"count(*) FROM import_runs WHERE id"*) echo 1 ;;
            *"FROM import_runs WHERE id"*) echo "${FAKE_RUN_ROW-$FAKE_TENANT|ready|t|framework}" ;;
            *flyway_schema_history*) echo 145 ;;
            *"count(*) FROM tenants WHERE id"*) echo 1 ;;
            *"count(*) FROM tenants"*) echo 2 ;;
            *"count(*) FROM users"*) [[ "$sql" == *set_config* ]] && printf '%s\n%s\n' "$FAKE_TENANT" 3 || echo 5 ;;
            *"count(*) FROM audit_log"*) echo 40 ;;
            *"SELECT id FROM tenants"*) echo "$FAKE_TENANT" ;;
            *information_schema.columns*) echo "id,tenant_id,run_id" ;;
            *"quote_literal(mapping_id"*) echo "'0b5e7a2c-0000-4000-8000-00000000b001'" ;;
            *"FROM import_rehearsals WHERE id"*)
                # The read-back after a stop: the live database may not answer.
                [[ -n "${FAKE_LIVE_SILENT:-}" && "$container" == "$live_db" ]] && exit 1
                echo 1 ;;
            *"count(*) FROM import_rehearsals"*) echo 1 ;;
            *"TO STDOUT"*) echo "row of ${container}" ;;
            *) echo "stub docker: unexpected exec $container $sql" >&2; exit 2 ;;
        esac ;;
    *) echo "stub docker: unexpected $*" >&2; exit 2 ;;
esac
STUB
    chmod +x "$bin/date" "$bin/sleep" "$bin/openssl" "$bin/docker"
}

# normalise: the drill names its files and containers after its PID; replace the number so two runs compare equal.
normalise() {
    sed -E 's/(bms-restore-drill-|drill-|drill-result-|bms-rehearsal-[a-z]+-|\.tmp\.)[0-9]+/\1PID/g; s/rehearsal\.[A-Za-z0-9]{6}/rehearsal.TMP/g'
}
