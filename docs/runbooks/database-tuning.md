# Runbook: database tuning and diagnostics

**Applies to:** staging (ARM64 host) and production · **Decisions:** ADR-018, ADR-028 · **Issue:** #107

How to change a PostgreSQL setting safely, and how to switch on the two diagnostic extensions,
`pg_stat_statements` and `auto_explain`, without breaking the staging host's memory cap. The
measured basis is ADR-028; the recommended values are in `deploy/postgres/recommended/`. Nothing in
this runbook is applied automatically: the compose files stay the source of truth for every
setting, and a change to them goes through a pull request.

## 1. Changing a setting

1. Measure first. Run `scripts/db-bench` (its `README.md`) with the current and the proposed value,
   on the staging profile (`run.sh profile pi`) and the production one (`run.sh profile vm`). Keep
   the change only if a query you care about gets faster and none gets slower.
2. Change the `-c` line in `deploy/compose.pi-staging.yml` or `deploy/compose.yml` and the
   matching file in `deploy/postgres/recommended/`, in one pull request that quotes the numbers.
3. After the deploy, confirm on the host:

   ```bash
   cd /opt/bms
   C=(docker compose --project-name bms -f compose.yml)
   "${C[@]}" exec postgres psql -U postgres -d bms -c "SHOW <setting>"
   ```

4. On the staging host, watch the stack's memory for a day: `systemctl status bms.slice` shows
   `Memory:` against `MemoryMax=900M`, and `docker stats --no-stream` per container. PostgreSQL's
   container must stay under its 176 MB limit through a nightly backup and an autovacuum of the
   largest table.

Settings that need a server restart (`shared_buffers`, `max_connections`,
`shared_preload_libraries`, `autovacuum_max_workers`) restart the `postgres` container, which the
API survives with a few failed requests; do it out of hours.

## 2. pg_stat_statements (which statements take the time)

What it costs, measured in the benchmark container (ADR-028): shared memory grew by about
440 kB with `pg_stat_statements.max = 1000` (from 59,736,064 to 60,186,624 bytes in
`pg_shmem_allocations`), and the record-a-sale write path showed no measurable cost (60 s of
pgbench, two clients, staging limits: 45.1 ms per sale without it, 43.2 ms with it). The container
peaked at 116 MB of its 176 MB during that run. The query texts are kept in a file under the data directory, not in memory.

Staging host: `pg_stat_statements.max = 1000`, `track = top`. Production VM: `max = 5000`.

To switch it on, add to the `postgres` service's `command` in the compose file:

```yaml
      - -c
      - shared_preload_libraries=pg_stat_statements
      - -c
      - pg_stat_statements.max=1000
      - -c
      - pg_stat_statements.track=top
```

then, after the restart, create the extension once as the superuser (the views are then readable
by `postgres`; `bms_app` gets nothing):

```bash
"${C[@]}" exec postgres psql -U postgres -d bms -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"
```

Read it with `docs/runbooks/database-health-check.md` section 3. Reset after a deploy that changes
hot queries, so the numbers describe the new code:

```sql
SELECT pg_stat_statements_reset();
```

Switching it off is the reverse: remove the three lines, restart, and
`DROP EXTENSION pg_stat_statements`.

## 3. auto_explain (the plan of a slow statement, in the log)

Do not preload `auto_explain` on the staging host. With `log_analyze` on it instruments every
statement, which costs CPU on the one core PostgreSQL has there, and plans in the log fill the
host's 2 x 5 MB container log quickly. Use it per session instead, as the superuser, to see the
plan of something a screen does:

```sql
LOAD 'auto_explain';
SET auto_explain.log_min_duration = '200ms';
SET auto_explain.log_analyze = on;
SET auto_explain.log_buffers = on;
SET auto_explain.log_timing = off;     -- row counts and buffers without the per-node clock cost
SET auto_explain.log_nested_statements = on;
-- run the statement, then read the plan:
-- docker compose --project-name bms -f compose.yml logs --tail 200 postgres
```

That session's statements only are explained. For a statement the application runs, prefer taking
its plan as `bms_app` with `EXPLAIN (ANALYZE, BUFFERS)` (`database-health-check.md` section 4),
which needs no log at all.

On production, if a slow statement cannot be reproduced by hand, `auto_explain` may be preloaded for
a limited time with `auto_explain.log_min_duration = '2s'`, `log_analyze = off` (the estimated plan
only, no instrumentation cost) and `auto_explain.sample_rate = 1`, then removed again.

## 4. The application's limits

Every connection of the API's pool starts with:

| Setting | Default | Variable | Why |
|---|---|---|---|
| `statement_timeout` | 60s | `BMS_DB_STATEMENT_TIMEOUT` | The slowest report measured at 25 times the staging data on the staging settings took under 1 s; a statement past 60 s is a runaway and frees its connection |
| `idle_in_transaction_session_timeout` | 60s | `BMS_DB_IDLE_IN_TRANSACTION_TIMEOUT` | A transaction left open holds row locks (stock balances, sequences) that block every sale |
| Pool size | 5 staging, 10 production | `BMS_DB_POOL_SIZE` | PostgreSQL has one CPU on the staging host; more connections queue on that CPU instead of in the pool |

Set a variable in `/opt/bms/.env` only after measuring why; the defaults come from
`application.yml`. Migrations run as `bms_owner` through their own connection and are not limited
by these; a migration that touches existing tables sets its own `lock_timeout` (chapter 6 section
6.9).

## 5. Autovacuum and fillfactor

Defaults everywhere, except `retail_stock_balances` (`fillfactor = 80`, V26), the table every sale
updates. Recommended on the staging host and not yet applied: `autovacuum_max_workers = 2` and
`autovacuum_work_mem = 16MB` (`deploy/postgres/recommended/pi-staging.conf`), so autovacuum cannot
take more than 32 MB beside the 48 MB of buffers. Check that autovacuum keeps up with
`database-health-check.md` section 5 before and after any change.

## 6. The retail analytics reports (issue #149)

The analytics reports read existing tables only and add no index. Each statement is a file in `scripts/db-bench/queries`
(`k01` to `k15`, the hot path list of ADR-028), bounded by a date range or window, the branch scope and a row limit; the
two exceptions are the open credit debts (`k10`, `k11`, bounded by the predicate that the debt is open, as the credit sales
list is) and the current stock position (`k15`, the balances of the branches in scope, one row a branch comes back).

Measured with `scripts/db-bench` at 10 times the data (not 25) on PostgreSQL 16 with the default settings of a cloud machine
(the Pi profile was not applied), seven runs, median in milliseconds, the largest tenant, a year for `k02` and a month or 30
days for the rest:

| Query | Median ms | Plan head |
|---|---:|---|
| `k01` sales by day, a month | 16 | GroupAggregate |
| `k02` top ten items by sales, a year | 302 | Limit over a sort of the aggregate |
| `k03` slow movers, 30 days | 48 | Limit |
| `k04` items with no sale, a month | 42 | Limit |
| `k05` items below the margin target, a month | 27 | Limit |
| `k07` days of cover, 30 days | 45 | Limit |
| `k08` dead stock totals, 90 days | 170 | Sort |
| `k10` credit ageing by buyer | 39 | Limit |
| `k11` overdue list | 34 | Limit |
| `k13` business evaluation | 113 | Incremental Sort |
| `k14` dashboard sales, 30 days | 21 | GroupAggregate |
| `k15` dashboard stock position | 33 | HashAggregate |

`k06` (price change impact), `k09` (shrinkage) and `k12` (credit payments) read tables the bench seed leaves empty or
undated for the range (`retail_price_history`, `retail_sale_payments`, `retail_usage_reports`), so their rows are not a
measurement. Their access is a filter over the tenant's rows of a table that grows with price events and credit payments, not with
sales; `retail_sale_payments_sale` and `retail_price_history_product` lead with the sale and the product, so a date range
reads the tenant's rows. If a real tenant's payments or price history make either slow, measure an index on
`(tenant_id, paid_on)` or `(tenant_id, created_at)` then, in its own migration; none is added speculatively (ADR-028
decision 1). Run `scripts/db-bench/run.sh measure` on the staging host out of hours to confirm the numbers on the Pi, as ADR-028
asks.
