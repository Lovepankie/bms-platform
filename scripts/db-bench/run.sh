#!/usr/bin/env bash
# Database benchmark harness (issue #107, ADR-023). Starts a THROWAWAY PostgreSQL container, applies
# the repository's migrations, loads synthetic data (seed.sql) and runs EXPLAIN (ANALYZE, BUFFERS)
# for every hot query as bms_app with row-level security on. Never point it at a real database:
# every step talks only to the container this script names and creates.
#
#   scripts/db-bench/run.sh up [image]          start the container (default postgres:17-alpine)
#   scripts/db-bench/run.sh migrate [to]        apply V1.. up to version <to> (default: all)
#   scripts/db-bench/run.sh seed [scale]        load seed.sql (default scale 25)
#   scripts/db-bench/run.sh profile pi|vm       restart with the staging (Pi) or production (VM) settings
#   scripts/db-bench/run.sh measure <label>     run queries/*.sql, write results/<label>.md and plans
#   scripts/db-bench/run.sh apply <file.sql>    apply one migration as bms_owner, timing each statement
#   scripts/db-bench/run.sh write <label> [s]   pgbench the record-a-sale write path (default 60 s)
#   scripts/db-bench/run.sh down                remove the container and its volume
#
# On the Pi: copy this directory and backend/src/main/resources/db/migration to the host and run the
# same commands; the container is separate from the bms stack and is removed by `down`.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
MIGRATIONS="${MIGRATIONS:-$REPO/backend/src/main/resources/db/migration}"
NAME="${BENCH_CONTAINER:-bms-db-bench}"
RUNS="${RUNS:-7}"
OWNER_PW=bench-owner
APP_PW=bench-app

psql_as() { # user, then psql arguments
    local user="$1"
    shift
    local pw="$APP_PW"
    [ "$user" = bms_owner ] && pw="$OWNER_PW"
    [ "$user" = postgres ] && pw=bench-superuser
    docker exec -i -e PGPASSWORD="$pw" "$NAME" psql -X -q -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "$user" -d bms "$@"
}

require_ours() {
    if [ "$(docker inspect -f '{{index .Config.Labels "bms.bench"}}' "$NAME" 2>/dev/null)" != "throwaway" ]; then
        echo "run.sh: container $NAME was not created by this script; refusing to touch it" >&2
        exit 2
    fi
}

wait_ready() {
    for _ in $(seq 1 60); do
        if docker exec "$NAME" pg_isready -q -h 127.0.0.1 -U postgres -d bms 2>/dev/null; then
            sleep 1
            return 0
        fi
        sleep 1
    done
    echo "run.sh: postgres did not become ready" >&2
    exit 1
}

cmd_up() {
    local image="${1:-postgres:17-alpine}"
    # No -c flags: settings go through ALTER SYSTEM so `profile` can change them later (command-line
    # settings would override postgresql.auto.conf).
    docker run -d --name "$NAME" --label bms.bench=throwaway \
        -e POSTGRES_DB=bms -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=bench-superuser \
        -e BMS_OWNER_PASSWORD="$OWNER_PW" -e BMS_APP_PASSWORD="$APP_PW" \
        -v "$REPO/deploy/postgres/initdb:/docker-entrypoint-initdb.d:ro" \
        -v "$HERE:/bench:ro" \
        --shm-size=256m ${BENCH_VOLUMES_FROM:+--volumes-from "$BENCH_VOLUMES_FROM"} "$image" >/dev/null
    wait_ready
    # Generous settings for the load; `profile` sets the measured ones.
    psql_as postgres -c "ALTER SYSTEM SET shared_buffers = '1GB'" -c "ALTER SYSTEM SET maintenance_work_mem = '512MB'" \
        -c "ALTER SYSTEM SET max_wal_size = '8GB'" -c "ALTER SYSTEM SET checkpoint_timeout = '30min'" >/dev/null
    docker restart "$NAME" >/dev/null
    wait_ready
    psql_as postgres -At -c "SELECT version()"
}

cmd_migrate() {
    require_ours
    local to="${1:-999999}"
    # V1 grants SELECT on Flyway's history table; the bench applies the files with psql, so a stub
    # stands in for it.
    psql_as bms_owner -c "CREATE TABLE IF NOT EXISTS flyway_schema_history (installed_rank int, version text)"
    for f in $(ls "$MIGRATIONS"/V*__*.sql | sort -t V -k2 -V); do
        local v
        v="$(basename "$f" | sed -E 's/^V([0-9]+)__.*/\1/')"
        [ "$v" -le "$to" ] || continue
        if [ "$(psql_as bms_owner -At -c "SELECT count(*) FROM flyway_schema_history WHERE version = '$v'")" = 0 ]; then
            echo "migrate: V$v"
            psql_as bms_owner -1 -f - <"$f" >/dev/null
            psql_as bms_owner -c "INSERT INTO flyway_schema_history VALUES ($v, '$v')"
        fi
    done
}

cmd_seed() {
    require_ours
    local scale="${1:-25}"
    local start
    start=$(date +%s)
    psql_as postgres -v scale="$scale" -f /bench/seed.sql
    psql_as postgres -c "VACUUM (ANALYZE)"
    echo "seed: scale $scale loaded and analysed in $(($(date +%s) - start)) s"
    psql_as postgres -c "SELECT pg_size_pretty(pg_database_size('bms')) AS database_size"
}

cmd_profile() {
    require_ours
    case "${1:?pi or vm}" in
    pi)
        # deploy/compose.pi-staging.yml: 176 MB container, one CPU.
        psql_as postgres -c "ALTER SYSTEM SET shared_buffers = '48MB'" -c "ALTER SYSTEM SET effective_cache_size = '128MB'" \
            -c "ALTER SYSTEM SET work_mem = '2MB'" -c "ALTER SYSTEM SET maintenance_work_mem = '32MB'" \
            -c "ALTER SYSTEM SET max_connections = 20" -c "ALTER SYSTEM SET wal_buffers = '4MB'" >/dev/null
        docker stop "$NAME" >/dev/null
        docker update --memory 176m --memory-swap 176m --cpus 1 "$NAME" >/dev/null
        ;;
    vm)
        # deploy/compose.yml: 1280 MB container on a 4 GB VM.
        psql_as postgres -c "ALTER SYSTEM SET shared_buffers = '384MB'" -c "ALTER SYSTEM SET effective_cache_size = '1GB'" \
            -c "ALTER SYSTEM SET work_mem = '8MB'" -c "ALTER SYSTEM SET maintenance_work_mem = '128MB'" \
            -c "ALTER SYSTEM SET max_connections = 60" -c "ALTER SYSTEM RESET wal_buffers" >/dev/null
        docker stop "$NAME" >/dev/null
        docker update --memory 1280m --memory-swap 1280m --cpus 2 "$NAME" >/dev/null
        ;;
    *) echo "profile: pi or vm" >&2; exit 2 ;;
    esac
    docker start "$NAME" >/dev/null
    wait_ready
    psql_as postgres -At -c "SELECT name || '=' || current_setting(name) FROM pg_settings WHERE name IN ('shared_buffers', 'work_mem', 'effective_cache_size', 'maintenance_work_mem', 'max_connections') ORDER BY name"
}

# Parameters for the queries: fixed rows of the large tenant (bench-1), a lending tenant (bench-26)
# and a small retail tenant (bench-25), chosen once by the superuser.
params() {
    psql_as postgres -At -F ' ' <<'SQL'
SELECT 'tenant', id::text FROM tenants WHERE slug = 'bench-1'
UNION ALL SELECT 'small_tenant', id::text FROM tenants WHERE slug = 'bench-25'
UNION ALL SELECT 'lending_tenant', id::text FROM tenants WHERE slug = 'bench-26'
UNION ALL SELECT 'branch', b.id::text FROM branches b JOIN tenants t ON t.id = b.tenant_id WHERE t.slug = 'bench-1' AND b.code = 'B01'
UNION ALL SELECT 'small_branch', b.id::text FROM branches b JOIN tenants t ON t.id = b.tenant_id WHERE t.slug = 'bench-25' AND b.code = 'B01'
UNION ALL (SELECT 'product', m.product_id::text FROM retail_stock_movements m JOIN tenants t ON t.id = m.tenant_id
            WHERE t.slug = 'bench-1' GROUP BY m.product_id ORDER BY count(*) DESC, m.product_id LIMIT 1)
UNION ALL (SELECT 'sale', s.id::text FROM retail_sales s JOIN tenants t ON t.id = s.tenant_id
            WHERE t.slug = 'bench-1' AND s.status = 'completed' ORDER BY s.created_at DESC LIMIT 1)
UNION ALL (SELECT 'entry', s.sale_entry_id::text FROM retail_sales s JOIN tenants t ON t.id = s.tenant_id
            WHERE t.slug = 'bench-1' AND s.sale_entry_id IS NOT NULL ORDER BY s.created_at DESC LIMIT 1)
UNION ALL (SELECT 'customer', s.customer_id::text FROM retail_sales s JOIN tenants t ON t.id = s.tenant_id
            WHERE t.slug = 'bench-1' AND s.customer_id IS NOT NULL GROUP BY s.customer_id ORDER BY count(*) DESC, 2 LIMIT 1)
UNION ALL (SELECT 'stocktake', s.id::text FROM retail_stocktakes s JOIN tenants t ON t.id = s.tenant_id
            WHERE t.slug = 'bench-1' ORDER BY s.created_at DESC LIMIT 1)
UNION ALL (SELECT 'user', u.id::text FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE t.slug = 'bench-1' AND u.full_name = 'Bench Staff 2')
UNION ALL (SELECT 'session', s.id::text FROM auth_sessions s JOIN users u ON u.id = s.user_id JOIN tenants t ON t.id = s.tenant_id
            WHERE t.slug = 'bench-1' AND u.full_name = 'Bench Staff 2' AND s.revoked_at IS NULL ORDER BY s.created_at DESC LIMIT 1)
UNION ALL (SELECT 'lending_user', u.id::text FROM users u JOIN tenants t ON t.id = u.tenant_id WHERE t.slug = 'bench-26' AND u.full_name = 'Bench Staff 1')
UNION ALL (SELECT 'member', l.member_id::text FROM lending_loans l JOIN tenants t ON t.id = l.tenant_id
            WHERE t.slug = 'bench-26' GROUP BY l.member_id ORDER BY count(*) DESC, 2 LIMIT 1)
UNION ALL (SELECT 'loan', l.id::text FROM lending_loans l JOIN tenants t ON t.id = l.tenant_id
            WHERE t.slug = 'bench-26' ORDER BY l.created_at DESC LIMIT 1)
UNION ALL SELECT 'sale_page', string_agg(quote_literal(id), ',') FROM (
    SELECT s.id FROM retail_sales s JOIN tenants t ON t.id = s.tenant_id WHERE t.slug = 'bench-1'
     ORDER BY s.created_at DESC, s.id LIMIT 51) p
UNION ALL SELECT 'purchase_page', string_agg(quote_literal(id), ',') FROM (
    SELECT p.id FROM retail_purchases p JOIN tenants t ON t.id = p.tenant_id WHERE t.slug = 'bench-1'
     ORDER BY p.created_at DESC, p.id LIMIT 51) p
UNION ALL (SELECT 'purchase', p.id::text FROM retail_purchases p JOIN tenants t ON t.id = p.tenant_id
            WHERE t.slug = 'bench-1' ORDER BY p.created_at DESC LIMIT 1)
UNION ALL (SELECT 'purchase_line', l.id::text FROM retail_purchase_lines l JOIN retail_purchases p ON p.id = l.purchase_id
            JOIN tenants t ON t.id = p.tenant_id WHERE t.slug = 'bench-1' ORDER BY p.created_at DESC, l.line_no LIMIT 1);
SQL
}

cmd_measure() {
    require_ours
    local label="${1:?label}"
    local out="$HERE/results/$label"
    mkdir -p "$out"
    local vars=()
    while read -r k v; do vars+=(-v "$k=$v"); done < <(params)
    local table="$HERE/results/$label.md"
    {
        echo "# $label"
        echo
        echo "$(psql_as postgres -At -c "SELECT version()")"
        echo
        echo "Settings: $(psql_as postgres -At -c "SELECT string_agg(name || '=' || current_setting(name), ', ' ORDER BY name) FROM pg_settings WHERE name IN ('shared_buffers', 'work_mem', 'effective_cache_size', 'random_page_cost', 'jit')"), container $(docker inspect -f '{{.HostConfig.Memory}} bytes, {{.HostConfig.NanoCpus}} nano-CPUs' "$NAME")"
        echo
        echo "Median of $RUNS runs after one warm-up run; buffers are the top plan node of the median run."
        echo
        echo "| Query | Median ms | Min ms | Max ms | Plan ms | Shared hit | Shared read | Rows | Plan head |"
        echo "|---|---:|---:|---:|---:|---:|---:|---:|---|"
    } >"$table"
    # QUERIES lists directories in order; a file in a later one replaces the same name in an earlier
    # one (queries-after holds the statements as the code issues them after issue #107).
    local files
    files="$(for d in ${QUERIES:-queries}; do ls "$HERE/$d"/*.sql; done | awk -F/ '{n=$NF; f[n]=$0; if (!(n in o)) {o[n]=++c}} END {for (n in f) print o[n] " " f[n]}' | sort -n | cut -d' ' -f2)"
    for q in $files; do
        local name
        name="$(basename "$q" .sql)"
        local times=()
        local tvar
        tvar="$(sed -nE 's/^-- tenant: *([a-z_]+).*/\1/p' "$q")"
        tvar="${tvar:-tenant}"
        : >"$out/$name.plan"
        for i in $(seq 0 "$RUNS"); do
            local plan
            plan="$( { echo "BEGIN;"; echo "SELECT set_config('app.tenant_id', :'$tvar', true) \\g /dev/null"; \
                echo "EXPLAIN (ANALYZE, BUFFERS)"; grep -v '^--' "$q"; echo ";"; echo "ROLLBACK;"; } \
                | psql_as bms_app "${vars[@]}" -At 2>&1)"
            if [ "$i" -gt 0 ]; then
                times+=("$(echo "$plan" | sed -nE 's/^Execution Time: ([0-9.]+) ms/\1/p')")
                echo "== run $i" >>"$out/$name.plan"
                echo "$plan" >>"$out/$name.plan"
            fi
        done
        python3 - "$name" "$out/$name.plan" "${times[@]}" >>"$table" <<'PY'
import re, statistics, sys
name, path, times = sys.argv[1], sys.argv[2], [float(t) for t in sys.argv[3:] if t]
runs = open(path).read().split("== run ")[1:]
if not times:
    print(f"| {name} | error | | | | | | | {runs[-1].strip().splitlines()[-1][:80] if runs else ''} |")
    sys.exit()
med = statistics.median(times)
best = min(range(len(times)), key=lambda i: abs(times[i] - med))
run = runs[best].split("\n", 1)[1]
lines = [l for l in run.splitlines() if l.strip()]
head = lines[0].strip() if lines else ""
rows = re.search(r"actual time=[0-9.]+\.\.[0-9.]+ rows=(\d+)", head)
buf = next((l for l in lines if "Buffers:" in l), "")
hit = re.search(r"shared hit=(\d+)", buf)
read = re.search(r"read=(\d+)", buf)
short = re.sub(r"\s*\(cost=.*", "", head)[:70]
plans = [float(m) for m in re.findall(r"Planning Time: ([0-9.]+) ms", open(path).read())]
plan_ms = statistics.median(plans) if plans else 0
print(f"| {name} | {med:.2f} | {min(times):.2f} | {max(times):.2f} | {plan_ms:.2f} | {hit.group(1) if hit else 0} | "
      f"{read.group(1) if read else 0} | {rows.group(1) if rows else ''} | {short} |")
PY
        echo "measure: $name"
    done
    echo "measure: results in $table"
}

cmd_apply() {
    require_ours
    local file="${1:?migration file}"
    psql_as bms_owner -1 -e -v ON_ERROR_STOP=1 -c "\\timing on" -f - <"$file"
}

cmd_write() {
    require_ours
    local label="${1:?label}"
    local vars=()
    while read -r k v; do vars+=(-D "$k=$v"); done < <(params)
    mkdir -p "$HERE/results"
    docker exec -e PGPASSWORD="$APP_PW" "$NAME" pgbench -n -h 127.0.0.1 -U bms_app -c 2 -T "${2:-60}" -r -P 1 \
        "${vars[@]}" -f /bench/write/record-sale.sql bms | tee "$HERE/results/$label-write.txt"
}

cmd_down() {
    require_ours
    docker rm -f -v "$NAME" >/dev/null
    if [ -n "${BENCH_VOLUMES_FROM:-}" ]; then docker rm -f -v "$BENCH_VOLUMES_FROM" >/dev/null; fi
    echo "down: $NAME removed"
}

sub="${1:-}"
shift || true
case "$sub" in
up | migrate | seed | profile | measure | apply | write | down) "cmd_$sub" "$@" ;;
*) sed -n '2,20p' "$0"; exit 2 ;;
esac
