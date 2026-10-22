#!/usr/bin/env bash
# Host checks that post to the Telegram operator chat (NFR-OBS-04, NFR-BAK-01, NFR-BAK-04, issue #425,
# ADR-058, docs/runbooks/monitoring-and-alerts.md).
#
# Every run checks, on this host:
#   site    https://<platform host>/healthz and, with MONITOR_TENANT_SLUG, that tenant's sign-in page;
#   jobs    db-scheduler executions that failed (consecutive_failures), are stuck (picked, no
#           heartbeat for 30 minutes: db-scheduler's default heartbeat is 5 minutes and it declares an
#           execution dead at about 20, so the API's own restart never alerts) or are overdue (not picked 30 minutes after they were due);
#   disk    every path in MONITOR_DISK_PATHS above MONITOR_DISK_LIMIT percent (70);
#   tls     the certificate of the platform, callback and tenant hosts expiring within 14 days;
#   backup  state/last_backup_error newer than the last good backup, the last good backup older
#           than 26 hours, and once an hour the newest object under <environment>/daily/ in R2;
#   drill   state/last_restore_drill_error, no good drill in 9 days, or a drill over the 2 hour RTO.
#
# A problem is sent once when it starts, again every MONITOR_REPEAT_HOURS (6) while it lasts, and
# once when it clears; state/alerts/<check> remembers what was sent. A message that could not be sent
# is not remembered, so the next run tries again. Messages carry check names, hosts and numbers only,
# never personal data. With MONITOR_HEARTBEAT_URL set, every completed run pings it, so the external
# monitor notices when this host or its timer goes quiet; a run that could not send an alert does not ping.
# site and jobs alert only after two bad runs in a row, so a deploy recreating the containers is not a page.
#
# Schedule: bms-monitor.timer, every five minutes, as the deploy user.
# Reads /opt/bms/.env: BMS_ENVIRONMENT, BMS_PLATFORM_HOST, BMS_TELEGRAM_BOT_TOKEN,
# BMS_TELEGRAM_OPERATOR_CHAT_ID, and optionally BMS_CALLBACK_HOST, BMS_TENANT_HOST_PATTERN, the R2
# values of backup.sh and the MONITOR_* settings below.
set -uo pipefail

MONITOR_DISK_LIMIT="${MONITOR_DISK_LIMIT:-70}"
MONITOR_DISK_PATHS="${MONITOR_DISK_PATHS:-/ /opt/bms}"
MONITOR_TLS_DAYS="${MONITOR_TLS_DAYS:-14}"
MONITOR_BACKUP_HOURS="${MONITOR_BACKUP_HOURS:-26}"
MONITOR_DRILL_DAYS="${MONITOR_DRILL_DAYS:-9}"
MONITOR_RTO_SECONDS="${MONITOR_RTO_SECONDS:-7200}"
MONITOR_REPEAT_HOURS="${MONITOR_REPEAT_HOURS:-6}"
MONITOR_STUCK_MINUTES="${MONITOR_STUCK_MINUTES:-30}"
MONITOR_OVERDUE_MINUTES="${MONITOR_OVERDUE_MINUTES:-30}"
MONITOR_POSTGRES_CONTAINER="${MONITOR_POSTGRES_CONTAINER:-bms-postgres-1}"
STATE="${MONITOR_STATE_DIR:-state}"

log() { echo "[monitor $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*" >&2; }
now() { date -u +%s; }
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
to_epoch() { date -u -d "$1" +%s 2> /dev/null; }

# The probes, one function each so the tests can replace them.
http_status() {
    curl --silent --output /dev/null --max-time 15 --retry 1 --retry-delay 5 --write-out '%{http_code}' "$1" 2> /dev/null || true
}
cert_end() {
    timeout 20 openssl s_client -connect "$1:443" -servername "$1" < /dev/null 2> /dev/null \
        | timeout 20 openssl x509 -noout -enddate 2> /dev/null | sed 's/^notAfter=//'
}
disk_percent() { df -P "$1" 2> /dev/null | awk 'NR == 2 { sub("%", "", $5); print $5 }'; }
sql() { timeout 30 docker exec "$MONITOR_POSTGRES_CONTAINER" psql -U bms_owner -d bms -tAF ' ' -c "$1"; }
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
newest_r2_backup() {
    local cgroup=()
    [[ "$BMS_ENVIRONMENT" == "staging" ]] && cgroup=(--cgroup-parent bms.slice)
    timeout 60 docker run --rm "${cgroup[@]}" --memory 64m \
        --env RCLONE_CONFIG_R2_TYPE=s3 \
        --env RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
        --env RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" \
        --env RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
        --env RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
        --env RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
        "$RCLONE_IMAGE" lsf --files-only "r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}/daily/" 2> /dev/null | pick_newest_backup "$(now)"
}
# The token goes to curl on stdin, never on its command line, where any user of the host could read it.
telegram_send() {
    [[ -n "${BMS_TELEGRAM_BOT_TOKEN:-}" && -n "${BMS_TELEGRAM_OPERATOR_CHAT_ID:-}" ]] || {
        log "Telegram is not configured; not sent: $1"
        return 1
    }
    printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$BMS_TELEGRAM_BOT_TOKEN" \
        | curl --silent --show-error --fail --max-time 20 --config - \
            --data-urlencode "chat_id=${BMS_TELEGRAM_OPERATOR_CHAT_ID}" \
            --data-urlencode "text=$1" > /dev/null
}

# Write-then-rename, so a reader or a killed run never sees half a state file (ADR-018).
put_state() { printf '%s\n' "$2" > "$1.tmp.$$" && mv -f "$1.tmp.$$" "$1"; }

# report <check> <problem text, empty when healthy> [bad runs in a row needed before the first PROBLEM, default 1]
# The count lives in state/alerts/<check>.bad. A deploy recreating the containers must not page anyone.
SEND_FAILED=0
report() {
    local check="$1" problem="$2" need="${3:-1}" file="$STATE/alerts/$1" bad="$STATE/alerts/$1.bad" t prefix since sent n
    t="$(now)"
    prefix="[BMS ${BMS_ENVIRONMENT:-unknown}]"
    if [[ -z "$problem" ]]; then
        rm -f "$bad"
        [[ -f "$file" ]] || return 0
        read -r since _ < "$file"
        if telegram_send "${prefix} RESOLVED ${check}: healthy again (problem since $(iso "$since"))"; then
            rm -f "$file"
        else
            SEND_FAILED=1
        fi
        return 0
    fi
    if [[ -f "$file" ]]; then
        read -r since sent < "$file"
        (( t - sent >= MONITOR_REPEAT_HOURS * 3600 )) || return 0
        telegram_send "${prefix} STILL FAILING ${check}: ${problem} (since $(iso "$since"))" || { SEND_FAILED=1; return 0; }
    else
        n="$(cat "$bad" 2> /dev/null || echo 0)"
        [[ "$n" =~ ^[0-9]+$ ]] || n=0
        n=$(( n + 1 ))
        put_state "$bad" "$n"
        (( n >= need )) || return 0
        since="$t"
        telegram_send "${prefix} PROBLEM ${check}: ${problem}" || { SEND_FAILED=1; return 0; }
    fi
    put_state "$file" "$since $t"
}

join() {
    local out="" p
    for p in "$@"; do out+="${out:+; }${p}"; done
    printf '%s' "$out"
}

tenant_host() {
    [[ -n "${MONITOR_TENANT_SLUG:-}" && -n "${BMS_TENANT_HOST_PATTERN:-}" ]] || return 0
    printf '%s\n' "${BMS_TENANT_HOST_PATTERN//\{slug\}/$MONITOR_TENANT_SLUG}"
}

check_site() {
    local problems=() code tenant
    code="$(http_status "https://${BMS_PLATFORM_HOST}/healthz")"
    [[ "$code" == "200" ]] || problems+=("https://${BMS_PLATFORM_HOST}/healthz answered ${code:-nothing}")
    tenant="$(tenant_host)"
    if [[ -n "$tenant" ]]; then
        code="$(http_status "https://${tenant}/")"
        [[ "$code" == "200" ]] || problems+=("the sign-in page https://${tenant}/ answered ${code:-nothing}")
    fi
    report site "$(join "${problems[@]}")" 2
}

check_jobs() {
    local out
    if ! out="$(sql "SELECT task_name, 'failed_' || consecutive_failures || '_times' FROM scheduled_tasks
                     WHERE consecutive_failures > 0
                     UNION
                     SELECT task_name, 'stuck' FROM scheduled_tasks
                     WHERE picked AND last_heartbeat < now() - interval '${MONITOR_STUCK_MINUTES} minutes'
                     UNION
                     SELECT task_name, 'overdue' FROM scheduled_tasks
                     WHERE NOT picked AND execution_time < now() - interval '${MONITOR_OVERDUE_MINUTES} minutes'
                     ORDER BY 1")"; then
        report jobs "cannot read scheduled_tasks from ${MONITOR_POSTGRES_CONTAINER}" 2
        return
    fi
    report jobs "$(printf '%s' "$out" | awk 'NF { printf "%s%s (%s)", sep, $1, $2; sep = ", " }')" 2
}

check_disk() {
    local problems=() path pct
    for path in $MONITOR_DISK_PATHS; do
        pct="$(disk_percent "$path")"
        if [[ ! "$pct" =~ ^[0-9]+$ ]]; then
            problems+=("${path} unreadable")
        elif (( pct > MONITOR_DISK_LIMIT )); then
            problems+=("${path} at ${pct} percent (limit ${MONITOR_DISK_LIMIT})")
        fi
    done
    report disk "$(join "${problems[@]}")"
}

check_tls() {
    local problems=() host end end_epoch days
    for host in "$BMS_PLATFORM_HOST" "${BMS_CALLBACK_HOST:-}" "$(tenant_host)"; do
        [[ -n "$host" ]] || continue
        end="$(cert_end "$host")"
        if ! end_epoch="$(to_epoch "$end")" || [[ -z "$end" ]]; then
            problems+=("${host}: no certificate read")
            continue
        fi
        days=$(( (end_epoch - $(now)) / 86400 ))
        (( days >= MONITOR_TLS_DAYS )) || problems+=("${host} expires in ${days} days (${end})")
    done
    report tls "$(join "${problems[@]}")"
}

# The first field of a state file is its UTC time (backup.sh and restore-drill.sh write it so).
stamp_of() { [[ -s "$1" ]] && to_epoch "$(awk 'NR == 1 { print $1 }' "$1")"; }

check_backup() {
    local problems=() ok err t name stamp
    t="$(now)"
    ok="$(stamp_of "$STATE/last_backup_ok")" || ok=0
    err="$(stamp_of "$STATE/last_backup_error")" || err=0
    if (( err > ok )); then
        problems+=("the last backup failed: $(head -c 300 "$STATE/last_backup_error")")
    fi
    if (( ok == 0 )); then
        problems+=("no good backup recorded on this host")
    elif (( t - ok > MONITOR_BACKUP_HOURS * 3600 )); then
        problems+=("the last good backup is $(( (t - ok) / 3600 )) hours old (limit ${MONITOR_BACKUP_HOURS})")
    fi
    # R2 is asked at most once an hour: a container start every five minutes is not worth it.
    if [[ -n "${R2_BACKUP_BUCKET:-}" && -n "${R2_ENDPOINT:-}" && -n "${R2_ACCESS_KEY_ID:-}" && -n "${R2_SECRET_ACCESS_KEY:-}" && -n "${RCLONE_IMAGE:-}" ]]; then
        local checked
        checked="$(cat "$STATE/monitor_r2_checked" 2> /dev/null || echo 0)"
        if (( t - checked >= 3600 )); then
            name="$(newest_r2_backup)"
            stamp="$(printf '%s' "$name" | sed -nE 's/.*-([0-9]{8})T([0-9]{2})([0-9]{2})([0-9]{2})Z\.dump\.enc$/\1 \2:\3:\4/p')"
            stamp="$(to_epoch "${stamp:-none}")" || stamp=0
            if (( stamp == 0 )); then
                put_state "$STATE/monitor_r2_problem" "no readable backup under ${BMS_ENVIRONMENT}/daily/ in R2"
            elif (( t - stamp > MONITOR_BACKUP_HOURS * 3600 )); then
                put_state "$STATE/monitor_r2_problem" "the newest backup in R2 is $(( (t - stamp) / 3600 )) hours old"
            else
                rm -f "$STATE/monitor_r2_problem"
            fi
            put_state "$STATE/monitor_r2_checked" "$t"
        fi
        [[ -s "$STATE/monitor_r2_problem" ]] && problems+=("$(cat "$STATE/monitor_r2_problem")")
    fi
    report backup "$(join "${problems[@]}")"
}

check_drill() {
    local problems=() ok err t seconds
    t="$(now)"
    ok="$(stamp_of "$STATE/last_restore_drill")" || ok=0
    err="$(stamp_of "$STATE/last_restore_drill_error")" || err=0
    (( err > ok )) && problems+=("the last restore drill failed: $(head -c 300 "$STATE/last_restore_drill_error")")
    if (( ok == 0 )); then
        problems+=("no good restore drill recorded on this host")
    elif (( t - ok > MONITOR_DRILL_DAYS * 86400 )); then
        problems+=("the last good restore drill is $(( (t - ok) / 86400 )) days old (limit ${MONITOR_DRILL_DAYS})")
    else
        seconds="$(sed -nE 's/.* seconds=([0-9]+).*/\1/p' "$STATE/last_restore_drill")"
        if [[ -n "$seconds" ]] && (( seconds > MONITOR_RTO_SECONDS )); then
            problems+=("the last restore drill took ${seconds} seconds, over the ${MONITOR_RTO_SECONDS} second RTO")
        fi
    fi
    report drill "$(join "${problems[@]}")"
}

main() {
    cd "$(dirname "$0")" || exit 1
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
    : "${BMS_ENVIRONMENT:?}" "${BMS_PLATFORM_HOST:?}"
    mkdir -p "$STATE/alerts"
    exec 9> "$STATE/monitor.lock"
    flock --nonblock 9 || { log "previous run still in progress"; exit 0; }

    check_site
    check_jobs
    check_disk
    check_tls
    check_backup
    check_drill

    # An unreachable Telegram must not tell the external monitor all is well.
    if [[ -n "${MONITOR_HEARTBEAT_URL:-}" ]] && (( SEND_FAILED == 0 )); then
        curl --silent --output /dev/null --max-time 10 --retry 2 "$MONITOR_HEARTBEAT_URL" || log "heartbeat ping failed"
    fi
    (( SEND_FAILED == 0 )) || { log "at least one alert could not be sent"; exit 1; }
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
