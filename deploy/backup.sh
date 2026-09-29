#!/usr/bin/env bash
# Nightly database backup to Cloudflare R2 (NFR-BAK-01 to NFR-BAK-03, docs/runbooks/restore-from-backup.md).
#
#   1. pg_dump (custom format) as bms_owner, which has BYPASSRLS, so every tenant's rows are in it;
#   2. encrypted on this host with AES-256 (openssl, PBKDF2) before anything leaves it;
#   3. uploaded with rclone to r2:<bucket>/<environment>/daily/, and on the first day of a month
#      also to monthly/;
#   4. daily copies older than 30 days and monthly copies older than 365 days are deleted;
#   5. state/last_backup_ok records the time, for the missing-backup alert (26 hours).
#
# Schedule (cron, as the deploy user):  15 1 * * *  /opt/bms/backup.sh >> /opt/bms/state/backup.log 2>&1
# Reads /opt/bms/.env: BMS_ENVIRONMENT, BACKUP_ENCRYPTION_KEY, R2_ENDPOINT, R2_ACCESS_KEY_ID,
# R2_SECRET_ACCESS_KEY, R2_BACKUP_BUCKET, RCLONE_IMAGE.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p state backups
set -a
# shellcheck disable=SC1091
. ./.env
set +a

: "${BMS_ENVIRONMENT:?}" "${BACKUP_ENCRYPTION_KEY:?}" "${R2_ENDPOINT:?}" "${R2_ACCESS_KEY_ID:?}"
: "${R2_SECRET_ACCESS_KEY:?}" "${R2_BACKUP_BUCKET:?}" "${RCLONE_IMAGE:?}"

IMAGE_TAG="$(cat state/current_tag)"
export IMAGE_TAG
COMPOSE=(docker compose --project-name bms --file compose.yml)
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
NAME="bms-${BMS_ENVIRONMENT}-${STAMP}.dump.enc"
PREFIX="r2:${R2_BACKUP_BUCKET}/${BMS_ENVIRONMENT}"

log() { echo "[backup $(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"; }
trap 'rm -f "backups/${NAME}"' EXIT

log "dumping and encrypting to backups/${NAME}"
"${COMPOSE[@]}" exec -T postgres pg_dump --username bms_owner --dbname bms --format custom --compress 6 \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_KEY \
    > "backups/${NAME}"

rclone() {
    docker run --rm \
        --volume "$PWD/backups:/backups:ro" \
        --env RCLONE_CONFIG_R2_TYPE=s3 \
        --env RCLONE_CONFIG_R2_PROVIDER=Cloudflare \
        --env RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT" \
        --env RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
        --env RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" \
        --env RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true \
        "$RCLONE_IMAGE" "$@"
}

log "uploading to ${PREFIX}/daily/"
rclone copyto "/backups/${NAME}" "${PREFIX}/daily/${NAME}"
if [[ "$(date -u +%d)" == "01" ]]; then
    log "first of the month: keeping a monthly copy"
    rclone copyto "/backups/${NAME}" "${PREFIX}/monthly/${NAME}"
fi

log "applying retention: daily 30 days, monthly 365 days"
rclone delete --min-age 30d "${PREFIX}/daily/"
rclone delete --min-age 365d "${PREFIX}/monthly/"

date -u +%Y-%m-%dT%H:%M:%SZ > state/last_backup_ok
log "backup ${NAME} complete"
