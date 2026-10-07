# ADR-028: Database performance measured at 25 times the data: covering indexes, the plain tenant policy kept, connection limits

## Status

Proposed (2026-10-06, issue #107). Builds on ADR-003 (row-level security), ADR-010 (Flyway) and
ADR-018 (the staging host's memory cap). Specification: chapter 6 section 6.12; runbooks
`database-health-check.md` and `database-tuning.md`.

## Context

The staging host runs the whole stack in 900 MB, PostgreSQL in 176 MB on one CPU; one retail tenant
there holds about 21,000 sales with their lines, stock movements and journals, and lending tenants
are starting. Production will be a 4 GB VM. Every query runs as `bms_app` under the tenant policy.
Nobody had looked at a query plan at a size the platform will reach, so it was not known which
screens and reports would degrade first, nor whether the policy itself cost anything.

What was measured, and how: `scripts/db-bench` loads random data at 25 times the staging size into
a throwaway PostgreSQL container (50 tenants of mixed size, the largest holding 40 percent of the
retail rows: 500,000 sales, 1.5 million sale lines, 1.6 million stock movements, 504,000 journal
lines, 150,000 loans, 2.1 million audit rows; 3.6 GB) and runs `EXPLAIN (ANALYZE, BUFFERS)` for 64
statements copied from the repositories, as `bms_app` in a transaction bound to a tenant. Two
profiles: **staging** (176 MB container, one CPU, `shared_buffers` 48 MB, `work_mem` 2 MB, as
`compose.pi-staging.yml`) and **production** (1280 MB, two CPUs, `shared_buffers` 384 MB, as
`compose.yml`). Median of seven runs (five on the production profile) after one warm-up run.
PostgreSQL 17.11 on an x86_64 cloud machine with NVMe storage. The servers' `.env.example`
pins PostgreSQL 16 and the integration tests run 16, so the staging profile was repeated on
PostgreSQL 16 (three runs per statement): the same changes win by similar factors there (sales first
page 459 to 0.21 ms, reconciliation 2,030 to 248 ms, valuation as of 2,075 to 214 ms, year profit
1,731 to 370 ms) and V26 took 9.0 s. The one difference: the import's positions took 3.0 s before
on 16, against 28.5 s on 17, where the planner chose 12,000 index probes; 0.8 s after on both.

What the plans showed:

1. **Lists ordered by a column no index leads with.** The sales and loans lists (`ORDER BY
   created_at, id`) sorted every row of the tenant for each page: 523 ms for the first page of
   sales on the staging profile.
2. **Reports reading the heap row by row.** Reconciliation (nightly, every retail tenant),
   valuation as of a date and the profit reports scanned the large tables in parallel and sorted to
   disk; the import's positions query probed the movements 12,000 times, 28.5 s.
3. **A date filter no index could use.** The stock movements list compared the local date of
   `occurred_at`, an expression: 146 to 624 ms.
4. **One statement per row of a page.** Sales, transfers and purchases loaded their lines one
   parent at a time; a purchases page also read the branch quantities one line at a time with a
   predicate no index served (153 statements of 10.7 ms each).
5. **A balance update that could never be HOT.** `retail_stock_balances`, updated by every sale,
   had a partial index on `qty < 0` that no query used; it made `qty` an indexed column, so 0 percent
   of balance updates were HOT and every one added index entries.
6. **The policy's own cost.** `current_setting(...)` in the policy is re-evaluated for every row a
   sequential scan reads.

## Decision

1. **Measure before changing.** A change to an index, a hot query or a server setting is measured
   with `scripts/db-bench` at 25 times the data on both profiles, and the numbers go in the pull
   request. Only changes the plans prove are made.
2. **One migration, V26, indexes and a storage setting only**: `(tenant_id, created_at, id)` on
   `retail_sales` and `lending_loans`; `(tenant_id, sale_date) INCLUDE (id, branch_id, status)` on
   `retail_sales`; covering indexes for the reports on `retail_sale_lines`,
   `retail_stock_movements` and `journal_lines`, replacing four indexes with the same leading
   columns that no query needs once they exist; the unused partial index on
   `retail_stock_balances` dropped and that table's `fillfactor` set to 80. Index rules in chapter 6
   section 6.12: `tenant_id` first, equality then range or sort, covering where a report aggregates,
   nothing on a hot-updated column.
3. **Batched child loads and a sargable date range** in the code, same results: one statement per
   page for the lines of sales, transfers and purchases (purchase movements read through the
   existing source index), and an instant range a day wider than any zone offset next to the
   unchanged local-date test of the movements list.
4. **The tenant policy stays `tenant_id = current_setting('app.tenant_id')::uuid`.** Wrapping the
   setting in a sub-select (an init plan, evaluated once per statement) was measured: before the
   indexes it cut the sales first page from 584 to 203 ms and the year profit from 1,895 to
   1,007 ms; after them it saved about a quarter on three aggregates (reconciliation 286 to 215 ms,
   valuation as of 273 to 210 ms, ledger balance 199 to 154 ms), but it hides the tenant from the
   planner's statistics, and with the tenant skew of real data the current valuation report went
   from 57 to 744 ms. Predictable plans are worth more than a quarter on three reports.
5. **Indexes are built inside the migration's transaction**, not `CONCURRENTLY`, which Flyway could
   only run in a separate non-transactional file. Measured on the staging profile at 25 times the
   data, V26 took 9.4 s in all (largest build 3.5 s, each drop under 3 ms; 6.8 s on the production
   profile); two clients recording sales throughout waited for those 9.4 s and none failed. Today's
   staging data is a twenty-fifth of that. The migration sets `lock_timeout = '5s'`, so behind a
   long report it fails and the deploy stops before the swap, instead of queueing every request.
   A future build that would block writes for more than about 30 s on production gets its own
   non-transactional migration with `CONCURRENTLY` (chapter 6 section 6.9).
6. **Connection limits**: every pool connection starts with `statement_timeout` and
   `idle_in_transaction_session_timeout` of 60 s, configurable per host. Pool sizes stay 5 on the
   staging host and 10 on production.
7. **Server settings are recommended, not applied**: `deploy/postgres/recommended/` records the
   measured values (unchanged from the compose files) and two unmeasured recommendations for the
   staging host's memory budget (two autovacuum workers with 16 MB each).
   `pg_stat_statements` is documented for both hosts and recommended on production; `auto_explain`
   per session only on the staging host (`database-tuning.md`).

Not done, measured: JIT and parallel query off (no consistent difference on the staging profile);
`work_mem` 16 MB for the year profit report (418 to 335 ms, not worth the memory on the staging
host); extended statistics on the movements' branch and product (no difference once the covering
index exists); a pre-aggregated profit table (the year report takes 472 ms at 25 times the data on
the staging profile); `fillfactor` on `retail_sales`, `idempotency_keys` and `tenant_sequences`
(already 100 percent HOT). Not done, not needed: JSON indexes (no query filters on JSON). Not
measured: `notification_outbox` (V23, #89), which merged after these measurements (its claim reads a
partial index on pending rows), and the schedule tables, which do not exist yet.

## Consequences

**Better:** every list and report measured is bounded at 25 times today's data on the staging
profile: lists, pickers and searches under 25 ms except the member duplicate check (121 ms, a
similarity filter), reports under 0.5 s except the import's positions (0.9 s, once per import). The sales first page went from 523 ms to 0.15 ms, the nightly reconciliation from 2.3 s to
0.24 s, valuation as of a date from 2.3 s to 0.23 s, the year profit from 1.9 s to 0.47 s, the
import's positions from 28.5 s to 0.9 s, and a purchases page from about 1.65 s of statements (20 ms for the
headers, 51 line reads, 153 movement reads of 10.7 ms) to about 21 ms in three statements. Balance updates are HOT and no longer grow the indexes. A runaway statement
or a leaked transaction frees its connection within a minute. There is a repeatable way to answer
"is this faster" before merging.

**Worse:** five more indexes to maintain on writes (the sale path measured the same: 43.0 ms
before and 44.2 ms after per three-line sale on the staging profile, 60 s of pgbench, two clients);
covering indexes are larger than the ones they replaced; V26 blocks writes to the tables it
indexes while it runs; a statement that legitimately needs more than 60 s now fails unless the
host raises `BMS_DB_STATEMENT_TIMEOUT`. Search by `ILIKE` or similarity cannot use an index under
row-level security (only leakproof predicates can be index conditions), so member and product search
stay filters over the tenant's rows: 19 ms and 6 ms here.

**Watch for:** the numbers come from an x86_64 cloud machine with NVMe storage, not the staging
host's ARM64 CPU and SD card, and from random data whose distribution is not a real tenant's;
concurrency beyond two writers, autovacuum over weeks and the JVM beside PostgreSQL inside 900 MB
were not measured. Run `scripts/db-bench` on the staging host itself out of hours to confirm, and
`database-health-check.md` monthly. A loans list filtered to one status walks the
creation-order index past the other statuses (14 ms at worst here, where every active loan is
recent); if a real tenant's list is slow, measure `(tenant_id, status, created_at, id)`. A tenant far
larger than the largest here, or the first lending schedules and a busy outbox, are the next reasons to
measure again.

## Appendix: timings (ms)

Median execution time of each statement in `scripts/db-bench/queries` (planning time excluded; it
was 1 to 7 ms throughout), before and after V26 and the code changes, on the staging and the
production profiles at 25 times the data. `e07` and `g07` after are one statement for a page of 51
parents, against one of 51 statements before; `g06` after is one statement for a page, against one
of 153 before. `i10` is the plan limit count on member creation.

| Query | Staging before | Staging after | Production before | Production after |
|---|---:|---:|---:|---:|
| `a01-signin-staff-by-email` | 0.25 | 0.20 | 0.17 | 0.18 |
| `a02-request-session-check` | 0.23 | 0.21 | 0.26 | 0.26 |
| `a03-request-scopes` | 0.26 | 0.25 | 0.29 | 0.24 |
| `a04-session-revoke-all` | 6.52 | 4.86 | 7.00 | 4.96 |
| `b01-product-picker-search` | 5.11 | 6.39 | 6.97 | 5.89 |
| `b02-product-picker-search-rare` | 4.22 | 3.80 | 4.03 | 3.98 |
| `b03-product-page` | 5.21 | 4.24 | 4.79 | 4.57 |
| `c01-stock-list` | 5.72 | 5.10 | 5.25 | 5.75 |
| `c02-stock-search` | 4.47 | 4.52 | 4.41 | 4.71 |
| `c03-stock-negative` | 3.45 | 4.39 | 4.84 | 4.54 |
| `c04-stock-movements-product` | 145.67 | 6.56 | 36.11 | 5.38 |
| `c05-stock-movements-week` | 623.84 | 10.54 | 372.66 | 6.84 |
| `c06-stock-mismatches` | 2292.14 | 235.14 | 532.24 | 213.56 |
| `c07-stock-positions` | 28503.01 | 918.82 | 839.58 | 728.09 |
| `c08-stocktake-lines` | 0.82 | 1.16 | 0.93 | 1.01 |
| `c09-movements-by-source` | 0.16 | 0.18 | 0.13 | 0.14 |
| `d01-sale-lock-balance` | 0.10 | 0.09 | 0.13 | 0.11 |
| `d02-sale-update-balance` | 0.36 | 0.34 | 0.37 | 0.30 |
| `d03-sale-insert-movement` | 6.70 | 5.80 | 5.27 | 5.08 |
| `d04-journal-balance-trigger` | 0.15 | 0.15 | 0.15 | 0.15 |
| `d05-journal-period` | 0.07 | 0.07 | 0.07 | 0.09 |
| `d06-sequence-next` | 0.27 | 0.28 | 0.21 | 0.27 |
| `d07-sale-find` | 0.13 | 0.18 | 0.14 | 0.16 |
| `e01-sales-list-first-page` | 522.57 | 0.15 | 232.80 | 0.16 |
| `e02-sales-list-branch-week` | 0.66 | 0.78 | 0.87 | 0.83 |
| `e03-sales-list-last-page` | 13.18 | 0.16 | 21.91 | 0.20 |
| `e04-sale-lines-one-sale` | 0.27 | 0.29 | 0.39 | 0.27 |
| `e05-open-credit-sales` | 0.26 | 0.23 | 0.36 | 0.28 |
| `e06-customers-search` | 1.43 | 1.46 | 1.46 | 1.40 |
| `e07-sale-lines-page` | 0.32 | 2.93 | 0.41 | 2.42 |
| `f01-valuation-now` | 41.85 | 49.98 | 43.72 | 49.88 |
| `f02-valuation-as-of` | 2267.89 | 226.67 | 562.22 | 197.66 |
| `f03-profit-day` | 12.40 | 2.51 | 19.78 | 1.74 |
| `f04-profit-month` | 213.42 | 35.38 | 66.00 | 34.12 |
| `f05-profit-year` | 1937.42 | 472.14 | 714.02 | 302.89 |
| `f06-profit-month-small-tenant` | 4.64 | 1.57 | 4.24 | 2.70 |
| `f07-ledger-balance-by-branch` | 375.17 | 164.49 | 170.96 | 145.48 |
| `f08-ledger-balance-small-tenant` | 11.31 | 12.90 | 16.12 | 15.91 |
| `g01-transfers-list` | 0.14 | 0.11 | 0.14 | 0.13 |
| `g02-transfers-list-branch` | 0.19 | 0.14 | 0.16 | 0.14 |
| `g03-transfers-list-product` | 0.27 | 0.20 | 0.22 | 0.20 |
| `g04-purchases-list-branch` | 18.90 | 20.01 | 16.27 | 22.32 |
| `g05-price-history` | 0.11 | 0.09 | 0.12 | 0.09 |
| `g06-purchase-line-movements` | 10.66 | 0.72 | 11.30 | 0.66 |
| `g07-purchase-lines` | 0.27 | 0.57 | 0.27 | 0.71 |
| `h01-audit-recent` | 0.26 | 0.28 | 0.29 | 0.24 |
| `h02-audit-entity` | 0.21 | 0.15 | 0.15 | 0.20 |
| `h03-audit-action-month` | 0.34 | 0.26 | 0.34 | 0.38 |
| `h04-audit-actor` | 0.25 | 0.24 | 0.26 | 0.29 |
| `h05-audit-branch-scoped` | 0.34 | 0.29 | 0.34 | 0.37 |
| `i01-members-page` | 0.22 | 0.16 | 0.15 | 0.21 |
| `i02-members-search` | 21.56 | 18.57 | 20.70 | 15.69 |
| `i03-member-duplicates` | 108.75 | 121.14 | 124.35 | 115.61 |
| `i04-loans-page` | 42.81 | 0.18 | 21.65 | 0.17 |
| `i05-loans-page-active` | 5.58 | 6.59 | 6.37 | 6.00 |
| `i06-loans-page-officer` | 4.96 | 3.59 | 7.56 | 5.08 |
| `i07-loan-exposure` | 0.16 | 0.14 | 0.19 | 0.16 |
| `i08-loan-status-history` | 0.06 | 0.06 | 0.08 | 0.08 |
| `i09-overdue-approvals` | 2.98 | 1.86 | 2.88 | 3.04 |
| `i10-member-plan-limit-count` | 1.58 | 2.18 | 1.89 | 1.86 |
| `j01-approvals-queue` | 0.28 | 0.22 | 0.29 | 0.30 |
| `j02-approvals-expire` | 0.33 | 0.28 | 0.33 | 0.26 |
| `j03-idempotency-claim` | 1.45 | 1.81 | 1.66 | 1.95 |
| `j04-idempotency-purge` | 0.91 | 0.92 | 1.27 | 0.92 |
