-- V101: two partial indexes for the Controls page's sale line metrics (issue #357, ADR-087; docs/sdd/06-database-design.md
-- section 6.11.3b).
--
-- Indexes only: no table, column, constraint, policy, grant or row changes, so the previous release runs unchanged on this
-- schema. ControlsRepository reads three metrics from sale lines: a changed price (price_source = 'override'), a discount (an
-- override sold below the resolved price) and a line below cost (line_total_minor < line_cost_minor). Every other metric
-- has an index on its own day, but no index on retail_sale_lines picked these lines out, so each of the three statements
-- read the tenant's whole line history and joined it to the window's sales. ControlsQueryPlansIT (one tenant, 1,020,030
-- sales and 2,040,060 lines over two years and ten shops) measured the cards of a month for every shop at 956 ms, three
-- parallel sequential scans of retail_sale_lines making most of it, and of a year at 6,039 ms. These lines are a small part
-- of all lines, so a partial index on (tenant_id, sale_id) for each keeps them small and lets the planner join only them to
-- the window's sales; the plans after the change are in docs/specs/controls-query-plans-357.md.
--
-- Locks. Flyway runs this file in one transaction. lock_timeout bounds only the wait for the SHARE lock on retail_sale_lines
-- (behind a transaction that is writing sale lines at that moment; SHARE does not conflict with readers, so a report does
-- not hold it up). While the request waits, new writers queue behind it, for up to those 5 seconds; then the migration
-- fails, nothing is changed, and the deploy stops, to be retried by hand (nothing re-runs it). It does not bound the
-- builds: once they start, the SHARE lock is held to the commit and every writer (a sale) waits for both full scans of
-- the table. Staging, measured by the maintainer on 2026-10-25: 22,110 rows of retail_sale_lines (11 MB), so both builds
-- take well under a second there. ControlsQueryPlansIT measured each build at 229 to 440 ms on 2,040,060 rows (three runs, a cloud machine;
-- PostgreSQL 16 in Testcontainers). A plain CREATE INDEX, not CONCURRENTLY, which cannot run inside Flyway's transaction
-- (ADR-028). The hold grows with the table and on a slower host: at about 2 million lines on the small hosts
-- this runs on (the staging Pi reads an SD card), it can reach seconds of held sales, so such a database needs a deploy
-- window or a separate migration that builds the indexes CONCURRENTLY outside Flyway's transaction.
--
-- Flyway runs with outOfOrder off: V98 is one staff phone per business, V99 is the placeholder that placeholder-migration-guard refuses and
-- V100 is the shop Home takings index (pull request #572), so this is V101.

SET LOCAL lock_timeout = '5s';

CREATE INDEX retail_sale_lines_price_changed ON retail_sale_lines (tenant_id, sale_id) WHERE price_source = 'override';
CREATE INDEX retail_sale_lines_below_cost ON retail_sale_lines (tenant_id, sale_id) WHERE line_total_minor < line_cost_minor;
