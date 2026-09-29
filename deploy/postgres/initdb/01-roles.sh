#!/usr/bin/env bash
# Creates the two database roles of docs/sdd/06-database-design.md section 6.3.1 and hands the
# database to the owner role. The official postgres image runs this once, when the data volume
# is first initialised; it is also what the integration tests run, so tests and servers start
# from the same roles.
#
#   bms_owner  LOGIN, NOSUPERUSER, BYPASSRLS. Owns every table and function; runs migrations,
#              backups and restores. BYPASSRLS is needed because every table is FORCE ROW LEVEL
#              SECURITY: without it pg_dump and the SECURITY DEFINER tenant resolvers would be
#              filtered by the policy too. Never used by the running application.
#   bms_app    LOGIN, NOSUPERUSER, NOBYPASSRLS, owns nothing. The API connects as this role, so
#              row-level security always applies to it (ADR-003).
#
# Required environment: POSTGRES_USER, POSTGRES_DB (set by the image), BMS_OWNER_PASSWORD,
# BMS_APP_PASSWORD.
set -euo pipefail

: "${BMS_OWNER_PASSWORD:?BMS_OWNER_PASSWORD is required}"
: "${BMS_APP_PASSWORD:?BMS_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$POSTGRES_DB" -v owner_pw="$BMS_OWNER_PASSWORD" -v app_pw="$BMS_APP_PASSWORD" <<'SQL'
CREATE ROLE bms_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS PASSWORD :'owner_pw';
CREATE ROLE bms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_pw';

ALTER DATABASE :"db" OWNER TO bms_owner;
ALTER SCHEMA public OWNER TO bms_owner;

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO bms_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO bms_app;
SQL

echo "01-roles.sh: bms_owner and bms_app created; database ${POSTGRES_DB} owned by bms_owner"
