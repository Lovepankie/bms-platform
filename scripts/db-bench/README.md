# db-bench: query plans at 25 times the data

A throwaway PostgreSQL benchmark (issue #107, ADR-028, chapter 15 section 15.4.4). It never
touches a real database: `run.sh` creates its own container (label `bms.bench=throwaway`) and
refuses to act on any other.

What it does: applies the repository's migrations with psql, loads random data (`seed.sql`; 50
tenants of mixed size, 25 times the staging retail tenant by default), and runs every statement
in `queries/` under `EXPLAIN (ANALYZE, BUFFERS)` as `bms_app` in a transaction bound to a tenant,
so row-level security applies exactly as in the API. `write/record-sale.sql` replays the
statements of one three-line sale for pgbench.

```bash
scripts/db-bench/run.sh up                 # postgres:17-alpine; pass another image to compare
scripts/db-bench/run.sh migrate 22         # V1 to V22; omit the number for every migration
scripts/db-bench/run.sh seed 25            # about 7 minutes and 3.6 GB on a cloud machine
scripts/db-bench/run.sh profile pi         # the staging limits: 176 MB, one CPU, 48 MB buffers
scripts/db-bench/run.sh measure before-pi  # results/before-pi.md and one .plan file per query
scripts/db-bench/run.sh apply backend/src/main/resources/db/migration/V26__database_optimisation.sql
QUERIES="queries queries-after" scripts/db-bench/run.sh measure after-pi
scripts/db-bench/run.sh write after-pi 60  # pgbench the sale path for 60 s
scripts/db-bench/run.sh down
```

`queries-after/` holds the statements whose SQL the code changed in the same release (batched line
loads, the movements date range); a file there replaces the same name in `queries/`. Keep a copy of
the migrated-but-unchanged database with `CREATE DATABASE bms_v22 TEMPLATE bms` to measure several
variants without reloading.

Each query file starts with a comment naming the code it copies and a `-- tenant:` line naming the
parameter to bind (`tenant`, `small_tenant`, `lending_tenant`). When a repository's SQL changes,
change its copy here in the same pull request.

On the staging host itself (ARM64, SD card), the same commands work with Docker and enough free
space; run them out of hours, since the load competes with the stack for the host's CPU and card.
Results are not committed (`results/` is ignored); the numbers that matter go in the ADR or the
pull request.
