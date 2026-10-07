-- V26: database optimisation (issue #107, ADR-028; docs/sdd/06-database-design.md section 6.12).
--
-- Indexes and storage settings only: no table, column, constraint, policy, grant or row changes, so
-- the previous release runs unchanged on this schema (expand and contract, section 6.9). Every
-- change below was measured with scripts/db-bench on a copy of the schema holding 25 times the
-- staging data, as bms_app with row-level security on; ADR-028 has the before and after timings.
--
-- Locks. Flyway runs this file in one transaction, so every lock is held until it commits. The
-- builds take SHARE locks (reads continue, writes to that table wait); the drops at the end take
-- ACCESS EXCLUSIVE for the moment before the commit. Measured on the 25x copy with the staging
-- settings (32 MB maintenance_work_mem, one CPU, 176 MB), the whole file took 9.4 s, the largest
-- build (stock movements, 1.6 million rows) 3.5 s, each drop under 3 ms; concurrent sales waited
-- for those 9.4 s and none failed. Today's staging data is a twenty-fifth of that. Plain
-- CREATE INDEX, not CONCURRENTLY, which cannot run inside the transaction (ADR-028).
--
-- lock_timeout: if a long report holds a table, this migration fails after 5 seconds instead of
-- queueing every request behind it; the deploy stops before the swap and is simply re-run.
SET LOCAL lock_timeout = '5s';

-- Sales list in its default order, newest page and cursor pages (SalesRepository.page).
CREATE INDEX retail_sales_by_created ON retail_sales (tenant_id, created_at, id);

-- Daily profit report: the sales of a date range across branches (ReportsRepository.daily).
CREATE INDEX retail_sales_by_date ON retail_sales (tenant_id, sale_date) INCLUDE (id, branch_id, status);

-- Daily profit report: the line totals without reading the heap. Replaces retail_sale_lines_sale,
-- which has the same key columns and still serves the foreign key and the per-sale lookups.
CREATE INDEX retail_sale_lines_by_sale ON retail_sale_lines (tenant_id, sale_id)
    INCLUDE (line_total_minor, line_cost_minor);

-- Reconciliation, valuation as of a date and the import's positions read the movements per branch
-- and product from this index alone. Replaces retail_stock_movements_balance and
-- retail_stock_movements_business_date, which no query uses once it exists.
CREATE INDEX retail_stock_movements_position ON retail_stock_movements (tenant_id, branch_id, product_id, business_date)
    INCLUDE (qty, kind, historical);

-- The ledger balance of one account per branch (JdbcLedgerAccounts.balanceByBranch) without
-- reading the lines' heap. Replaces journal_lines_by_account (same key columns).
CREATE INDEX journal_lines_by_account_totals ON journal_lines (tenant_id, account_id)
    INCLUDE (entry_id, debit, credit);

-- Loans list in its default order (LoanRepository.page).
CREATE INDEX lending_loans_by_created ON lending_loans (tenant_id, created_at, id);

-- Every sale, usage, void and stock-take updates a balance row. retail_stock_balances_negative
-- (WHERE qty < 0) made qty an indexed column, so none of those updates could be HOT; no query
-- uses it (the negative-stock filter is driven from the products). Free space on each page keeps
-- the updated row on its page. Measured: 0 percent HOT updates before, 100 percent after.
ALTER TABLE retail_stock_balances SET (fillfactor = 80);

DROP INDEX retail_stock_balances_negative;
DROP INDEX retail_sale_lines_sale;
DROP INDEX retail_stock_movements_balance;
DROP INDEX retail_stock_movements_business_date;
DROP INDEX journal_lines_by_account;
