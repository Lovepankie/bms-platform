#!/usr/bin/env bash
# Restore drill (NFR-BAK-04, issue #46, docs/runbooks/restore-from-backup.md section 5).
#
# Proves the newest backup in R2 can actually be restored, without touching the live database:
#   1. fetch the newest <environment>/daily/ dump from R2 and decrypt it on this host;
#   2. start a THROWAWAY PostgreSQL (no network, memory capped inside bms.slice on staging), created
#      by the same postgres/initdb/01-roles.sh as the real one, and pg_restore into it;
#   3. check it: Flyway history is intact and not ahead of the live database, the row counts are read,
#      and the application role can read a tenant's rows under row-level security;
#   4. record state/last_restore_drill (one line) on success, state/last_restore_drill_error on failure.
# Everything it created, including the decrypted dump, is removed whatever the outcome.
#
# Schedule (cron, as the deploy user):  30 3 * * 0  /opt/bms/restore-drill.sh >> /opt/bms/state/restore-drill.log 2>&1
# Reads /opt/bms/.env like backup.sh, plus POSTGRES_IMAGE.
set -uo pipefail

cd "$(dirname "$0")" || exit 1
mkdir -p state backups
set -a
# shellcheck disable=SC1091
. ./.env
set +a
: "${BMS_ENVIRONMENT:?}" "${BACKUP_ENCRYPTION_KEY:?}" "${R2_ENDPOINT:?}" "${R2_ACCESS_KEY_ID:?}"
: "${R2_SECRET_ACCESS_KEY:?}" "${R2_BACKUP_BUCKET:?}" "${RCLONE_IMAGE:?}" "${POSTGRES_IMAGE:?}"

IMAGE_TAG="$(cat state/current_tag)"
INITDB="$PWD/releases/${IMAGE_TAG}/postgres/initdb"
PREFIX="r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}"
DRILL="bms-restore-drill-$$"
ENC="backups/drill-$$.dump.enc"
DUMP="backups/drill-$$.dump"

log() { echo "[drill $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
fail() {
    log "FAILED: $*"
    printf '%s FAILED %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" > state/last_restore_drill_error
    exit 1
}
cleanup() {
    docker rm -f -v "$DRILL" > /dev/null 2>&1 || true
    rm -f "$ENC" "$DUMP"
}
trap cleanup EXIT

[[ -d "$INITDB" ]] || fail "no initdb folder at ${INITDB}"

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

NAME="$(rclone lsf --files-only "${PREFIX}/daily/" 2> /dev/null | sort | tail -n 1)"
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
    || fail "pg_restore failed: $(head -c 300 state/restore-drill-pg_restore.err | tr '\n' ' ')"

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

printf '%s OK %s flyway=%s live_flyway=%s tenants=%s users=%s audit=%s app_visible_users=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$NAME" "$restored_ver" "$live_ver" "$tenants" "$users" "$audit" "$app_users" > state/last_restore_drill
rm -f state/last_restore_drill_error state/restore-drill-pg_restore.err
log "OK: $(cat state/last_restore_drill)"
