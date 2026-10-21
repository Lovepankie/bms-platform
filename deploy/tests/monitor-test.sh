#!/usr/bin/env bash
# Tests for deploy/monitor.sh (issue #425). No network, no Docker: every probe is replaced by a stub
# and Telegram by a file. Run: bash deploy/tests/monitor-test.sh
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

export MONITOR_STATE_DIR="$WORK/state"
# shellcheck source=deploy/monitor.sh
. "$HERE/../monitor.sh"

export BMS_ENVIRONMENT="test"
export BMS_PLATFORM_HOST="bms.example.test"
export BMS_CALLBACK_HOST=""
export BMS_TENANT_HOST_PATTERN="{slug}-bms.example.test"
export MONITOR_TENANT_SLUG="demo"
export R2_BACKUP_BUCKET=""
SENT="$WORK/sent"
NOW=1800000000

# The stubs, driven by the variables each test sets.
now() { echo "$NOW"; }
http_status() { case "$1" in *healthz) echo "$HEALTHZ" ;; *) echo "$SIGNIN" ;; esac; }
cert_end() { echo "$CERT_END"; }
disk_percent() { echo "$DISK"; }
sql() { [[ "$SQL_OK" == "yes" ]] || return 1; printf '%s' "$SQL_OUT"; }
newest_r2_backup() { echo "$R2_NEWEST"; }
TELEGRAM_UP=yes
telegram_send() { [[ "$TELEGRAM_UP" == "yes" ]] || return 1; printf '%s\n' "$1" >> "$SENT"; }

fresh() {
    rm -rf "$MONITOR_STATE_DIR" "$SENT"
    mkdir -p "$MONITOR_STATE_DIR/alerts"
    : > "$SENT"
    SEND_FAILED=0
    HEALTHZ=200 SIGNIN=200 DISK=40 SQL_OK=yes SQL_OUT="" R2_NEWEST="" TELEGRAM_UP=yes
    CERT_END="$(date -u -d "@$(( NOW + 60 * 86400 ))" '+%b %e %H:%M:%S %Y GMT')"
}

FAILURES=0
check() {
    local name="$1" pattern="$2"
    if grep -qE -- "$pattern" "$SENT"; then
        echo "ok   $name"
    else
        echo "FAIL $name: no message matching '$pattern' in:"
        sed 's/^/       /' "$SENT"
        FAILURES=$(( FAILURES + 1 ))
    fi
}
assert() {
    local name="$1"
    shift
    if "$@"; then
        echo "ok   $name"
    else
        echo "FAIL $name"
        FAILURES=$(( FAILURES + 1 ))
    fi
}
check_silent() {
    local name="$1"
    if [[ ! -s "$SENT" ]]; then
        echo "ok   $name"
    else
        echo "FAIL $name: expected no message, got:"
        sed 's/^/       /' "$SENT"
        FAILURES=$(( FAILURES + 1 ))
    fi
}

# --- site ---
fresh; check_site; check_silent "site healthy sends nothing"
fresh; HEALTHZ=502; check_site; check_silent "one bad site run sends nothing (a deploy is not an outage)"
check_site
check "site down two runs in a row sends one problem" '^\[BMS test\] PROBLEM site: https://bms.example.test/healthz answered 502$'
: > "$SENT"; check_site; check_silent "site still down within the repeat window sends nothing"
NOW=$(( NOW + 6 * 3600 )); check_site; check "site still down after six hours reminds" 'STILL FAILING site'
: > "$SENT"; HEALTHZ=200; check_site; check "site back sends one resolved" '^\[BMS test\] RESOLVED site: healthy again'
fresh; HEALTHZ=502; check_site; HEALTHZ=200; check_site; HEALTHZ=502; check_site; check_silent "a good run resets the bad count"
assert "resolved clears the alert state" test ! -e "$MONITOR_STATE_DIR/alerts/site"
NOW=1800000000
fresh; SIGNIN=000; check_site; check_site; check "tenant sign-in page down" 'the sign-in page https://demo-bms.example.test/ answered 000'

# --- an alert that could not be sent is tried again ---
fresh; TELEGRAM_UP=no; HEALTHZ=503; check_site; check_site
assert "unsent alert is flagged" test "$SEND_FAILED" = 1
assert "unsent alert is not remembered" test ! -e "$MONITOR_STATE_DIR/alerts/site"
TELEGRAM_UP=yes; check_site; check "unsent alert goes on the next run" 'PROBLEM site'

# --- jobs ---
fresh; check_jobs; check_silent "no failed jobs sends nothing"
fresh; SQL_OUT=$'core.payments.poll failed_3_times\nlending.arrears stuck\n'; check_jobs; check_silent "one bad jobs run sends nothing"; check_jobs
check "failed and stuck jobs listed" 'PROBLEM jobs: core.payments.poll \(failed_3_times\), lending.arrears \(stuck\)$'
fresh; SQL_OK=no; check_jobs; check_jobs; check "unreadable scheduler table" 'PROBLEM jobs: cannot read scheduled_tasks'

# --- disk ---
fresh; DISK=70; check_disk; check_silent "disk at the limit sends nothing"
fresh; DISK=71; check_disk; check "disk over 70 percent" 'PROBLEM disk: / at 71 percent \(limit 70\); /opt/bms at 71 percent'

# --- tls ---
fresh; check_tls; check_silent "certificate with 60 days left sends nothing"
fresh; CERT_END="$(date -u -d "@$(( NOW + 13 * 86400 ))" '+%b %e %H:%M:%S %Y GMT')"; check_tls
check "certificate within 14 days" 'PROBLEM tls: bms.example.test expires in 13 days'
fresh; CERT_END=""; check_tls; check "no certificate read" 'bms.example.test: no certificate read'

# --- backup ---
stamp() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; check_backup; check_silent "fresh backup sends nothing"
fresh; stamp $(( NOW - 27 * 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; check_backup
check "backup older than 26 hours" 'the last good backup is 27 hours old \(limit 26\)'
fresh; stamp $(( NOW - 7200 )) > "$MONITOR_STATE_DIR/last_backup_ok"
printf '%s FAILED exit 1\n' "$(stamp $(( NOW - 60 )))" > "$MONITOR_STATE_DIR/last_backup_error"; check_backup
check "failed backup" 'PROBLEM backup: the last backup failed: .* FAILED exit 1'
fresh; check_backup; check "no backup ever" 'no good backup recorded on this host'
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; R2_BACKUP_BUCKET=bucket R2_ENDPOINT=https://r2.test R2_ACCESS_KEY_ID=k R2_SECRET_ACCESS_KEY=s RCLONE_IMAGE=rclone:test
R2_NEWEST="bms-test-$(date -u -d "@$(( NOW - 30 * 3600 ))" +%Y%m%dT%H%M%SZ).dump.enc"; check_backup
check "stale newest object in R2" 'the newest backup in R2 is 30 hours old'
: > "$SENT"; R2_NEWEST="bms-test-$(date -u -d "@$NOW" +%Y%m%dT%H%M%SZ).dump.enc"; check_backup
check_silent "R2 is not asked again within the hour (problem kept)"
NOW=$(( NOW + 3600 )); check_backup; check "R2 fresh again resolves" 'RESOLVED backup'
NOW=1800000000
fresh; stamp $(( NOW - 3600 )) > "$MONITOR_STATE_DIR/last_backup_ok"; R2_BACKUP_BUCKET=bucket RCLONE_IMAGE=""; R2_NEWEST=""; check_backup; check_silent "R2 not asked when one of its five settings is missing"
R2_BACKUP_BUCKET="" R2_ENDPOINT="" R2_ACCESS_KEY_ID="" R2_SECRET_ACCESS_KEY="" RCLONE_IMAGE=""

# --- drill ---
fresh; printf '%s OK f seconds=900 rto_ok=yes flyway=76\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check_silent "recent quick drill sends nothing"
fresh; printf '%s OK f seconds=8000 rto_ok=no\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check "drill over the RTO" 'took 8000 seconds, over the 7200 second RTO'
fresh; printf '%s OK f seconds=900\n' "$(stamp $(( NOW - 10 * 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
check_drill; check "drill older than 9 days" 'the last good restore drill is 10 days old'
fresh; printf '%s OK f seconds=900\n' "$(stamp $(( NOW - 86400 )))" > "$MONITOR_STATE_DIR/last_restore_drill"
printf '%s FAILED seconds=40 decryption failed\n' "$(stamp $(( NOW - 60 )))" > "$MONITOR_STATE_DIR/last_restore_drill_error"
check_drill; check "failed drill" 'the last restore drill failed: .*decryption failed'

if (( FAILURES > 0 )); then
    echo "${FAILURES} test(s) failed"
    exit 1
fi
echo "all monitor tests passed"
