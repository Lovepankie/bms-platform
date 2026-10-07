# Runbook: database health check

**Applies to:** staging (ARM64 host) and production · **Decisions:** ADR-003, ADR-028 · **Issue:** #107

A read-only check of the PostgreSQL database: what is slow, what is bloated, which indexes are
never used and which tables are read without one. Every query here only reads the statistics
views; none changes data or settings. Run it monthly, after a large import, and whenever a screen
feels slow. Write what you found in the issue that prompted the check; change nothing on the host
from this runbook alone (schema changes go through a migration, settings through
`docs/runbooks/database-tuning.md`).

## 1. Open a session

On the host, as the `postgres` superuser inside the database container (statistics views show
every role's activity only to a superuser or `pg_read_all_stats`):

```bash
cd /opt/bms
C=(docker compose --project-name bms -f compose.yml)
"${C[@]}" exec postgres psql -U postgres -d bms
```

Statistics are cumulative since the last reset or server restart. Note when that was, because an
index that looks unused may simply not have been needed since a restart:

```sql
SELECT stats_reset FROM pg_stat_database WHERE datname = 'bms';
SELECT pg_postmaster_start_time();
```

## 2. Size and the largest tables

```sql
SELECT pg_size_pretty(pg_database_size('bms')) AS database;

SELECT c.relname AS table_name,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       pg_size_pretty(pg_relation_size(c.oid)) AS heap,
       pg_size_pretty(pg_indexes_size(c.oid)) AS indexes,
       s.n_live_tup AS live_rows
  FROM pg_class c JOIN pg_stat_user_tables s ON s.relid = c.oid
 ORDER BY pg_total_relation_size(c.oid) DESC
 LIMIT 15;
```

On the staging host the whole database should stay well inside the SD card's free space; compare
with the last check.

## 3. Slow queries

Needs `pg_stat_statements` (off by default; `docs/runbooks/database-tuning.md` section 2 says how
to switch it on within the memory cap). Without it, skip to the next query.

```sql
-- Where the time goes: total time first, then the mean of the worst.
SELECT round(total_exec_time::numeric, 0) AS total_ms,
       calls,
       round(mean_exec_time::numeric, 2) AS mean_ms,
       round(max_exec_time::numeric, 2) AS max_ms,
       rows,
       round(100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0), 1) AS hit_pct,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
  FROM pg_stat_statements
 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'bms')
 ORDER BY total_exec_time DESC
 LIMIT 20;

SELECT round(mean_exec_time::numeric, 2) AS mean_ms, calls,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) AS query
  FROM pg_stat_statements
 WHERE calls >= 20
 ORDER BY mean_exec_time DESC
 LIMIT 20;
```

What is slow: a screen query (lists, search, picker) with a mean above 50 ms on production or
500 ms on the staging host; a report above 2 s on production. Compare with the measured numbers in
ADR-028 before calling anything a regression.

Statements running now, and who is waiting on whom:

```sql
SELECT pid, usename, state, now() - xact_start AS in_transaction, now() - query_start AS running,
       wait_event_type, wait_event, pg_blocking_pids(pid) AS blocked_by,
       left(regexp_replace(query, '\s+', ' ', 'g'), 100) AS query
  FROM pg_stat_activity
 WHERE datname = 'bms' AND state <> 'idle'
 ORDER BY xact_start NULLS LAST;
```

A session `idle in transaction` for more than a few seconds is a bug in the caller (the API closes
its transactions; `idle_in_transaction_session_timeout` of section 9 ends a leaked one).

## 4. Look at one plan as the application sees it

The application connects as `bms_app` with row-level security on, so a plan taken as `postgres`
(which bypasses the policy) can be wrong. Take it as `bms_app`, inside a transaction bound to the
tenant, and roll back:

```bash
"${C[@]}" exec postgres psql -U bms_app -d bms
```

```sql
BEGIN;
SELECT set_config('app.tenant_id', '<tenant uuid>', true);
EXPLAIN (ANALYZE, BUFFERS) <the statement, with literal values>;
ROLLBACK;
```

The tenant's id comes from `SELECT id FROM tenants WHERE slug = '<slug>'` in the `postgres`
session. `EXPLAIN ANALYZE` runs the statement: always inside `BEGIN ... ROLLBACK` for anything
that writes. Read the plan for a `Seq Scan` on a large table, a `Rows Removed by Filter` much larger
than the rows returned, and `shared read` (blocks that came from disk, not the cache).

A predicate built from a function PostgreSQL does not mark leakproof, such as `lower(col) = ...`
or `col ILIKE ...`, cannot be used as an index condition under row-level security, because it
would run before the tenant policy (ADR-028). It is applied as a filter after the tenant's rows are
found. That is expected; it only matters if the tenant has many rows on that table.

## 5. Bloat and vacuum

Dead rows per table, and when autovacuum last ran:

```sql
SELECT relname, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / nullif(n_tup_upd, 0), 1) AS hot_pct,
       last_autovacuum, last_autoanalyze, autovacuum_count
  FROM pg_stat_user_tables
 ORDER BY n_dead_tup DESC
 LIMIT 15;
```

What to look for:

- `dead_pct` above 20 on a table larger than a few MB, with an old `last_autovacuum`: autovacuum
  is not keeping up. On the staging host it runs with two workers (section 9); check the log for
  `canceling autovacuum task` and for long transactions (section 3) holding the horizon back.
- `hot_pct` on the update-heavy tables (`retail_stock_balances`, `retail_sales`,
  `idempotency_keys`, `tenant_sequences`, `auth_sessions`, `lending_loans`): after V26 these have
  free space on each page (fillfactor) so most updates are HOT (no index change). Below 50 percent
  means the free space is used up or an index now covers an updated column.

For an exact figure on one table, `pgstattuple` ships with the image but is not installed; install
it only for the check and drop it afterwards (it reads the whole table, so run it out of hours on
the staging host):

```sql
CREATE EXTENSION IF NOT EXISTS pgstattuple;
SELECT * FROM pgstattuple_approx('retail_stock_balances');
SELECT * FROM pgstatindex('retail_stock_balances_pkey');
DROP EXTENSION pgstattuple;
```

`pgstatindex` `avg_leaf_density` below 50 on a large index means it is bloated; a
`REINDEX INDEX CONCURRENTLY <name>` outside a migration fixes it without blocking writes.

Transaction ID age (wraparound is far away at this size; this is the early warning):

```sql
SELECT datname, age(datfrozenxid) AS xid_age FROM pg_database ORDER BY 2 DESC;
```

Above 500 million, open an issue; autovacuum's anti-wraparound runs start at 200 million.

## 6. Unused indexes

```sql
SELECT s.relname AS table_name, s.indexrelname AS index_name, s.idx_scan,
       pg_size_pretty(pg_relation_size(s.indexrelid)) AS size,
       i.indisunique OR i.indisprimary AS enforces_a_constraint
  FROM pg_stat_user_indexes s JOIN pg_index i ON i.indexrelid = s.indexrelid
 WHERE s.idx_scan = 0
 ORDER BY pg_relation_size(s.indexrelid) DESC;
```

An index with `idx_scan = 0` is a candidate for removal only if:

1. it does not enforce a constraint (`enforces_a_constraint` false), and is not the only index
   leading with the columns of a foreign key (deleting or updating the parent would then scan the
   child; most parents here are never deleted, but check);
2. the statistics cover at least a month of normal use including a month end (reports and
   nightly jobs use some indexes rarely);
3. it is unused on production too, not only on staging.

Removal is a migration (`DROP INDEX`), recorded in chapter 6 section 6.12.

## 7. Missing indexes

Tables read by sequential scan, largest reads first:

```sql
SELECT relname, seq_scan, seq_tup_read,
       seq_tup_read / nullif(seq_scan, 0) AS rows_per_scan,
       idx_scan, n_live_tup
  FROM pg_stat_user_tables
 WHERE seq_scan > 0
 ORDER BY seq_tup_read DESC
 LIMIT 15;
```

A table with many rows, a high `rows_per_scan` and a growing `seq_scan` is read without an index
somewhere. Small tables (categories, units, roles, periods, accounts) are read by sequential scan on
purpose; ignore them. Find the statement in `pg_stat_statements` (section 3), take its plan as
`bms_app` (section 4) and propose an index that starts with `tenant_id` followed by the equality
columns, then the range or sort column (chapter 6 section 6.12). Prove it with
`scripts/db-bench` before adding it.

## 8. Cache and connections

```sql
SELECT round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) AS cache_hit_pct,
       xact_commit, xact_rollback, deadlocks, temp_files, pg_size_pretty(temp_bytes) AS temp
  FROM pg_stat_database WHERE datname = 'bms';

SELECT count(*) AS connections, current_setting('max_connections') AS max
  FROM pg_stat_activity WHERE datname = 'bms';
```

`cache_hit_pct` below 95 on production means `shared_buffers` and the OS cache no longer hold the
working set (the staging host, with 48 MB of buffers, is expected to sit lower). `temp_files`
growing means sorts or hashes spill past `work_mem`; find which statement with
`pg_stat_statements.temp_blks_written`. `deadlocks` above zero needs an issue with the log lines.

## 9. Write it down

Record in the issue: the date, the statistics window (section 1), the top five statements of
section 3 with their means, any table above 20 percent dead rows, unused and missing index
candidates, and the cache hit ratio. Nothing in this runbook changes the database; proposals go
through a migration or `docs/runbooks/database-tuning.md`.
