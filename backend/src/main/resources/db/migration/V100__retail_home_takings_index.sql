-- V100: the index for the shop Home takings (issue #488, ADR-076 decision 4; docs/sdd/06-database-design.md section 6.12).
--
-- One index and nothing else: no table, column, constraint, policy, grant or row changes, so the previous release runs
-- unchanged on this schema. HomeRepository.takings (bench query k17) and the cash book (JdbcCashSales) choose a day's
-- payments on credit sales by paid_on alone, and no index led on it (retail_sale_payments_sale is
-- (tenant_id, sale_id, paid_on)), so every Home load read the tenant's whole payment history. With
-- (tenant_id, paid_on) that part reads the day's rows only. Measured with scripts/db-bench at 25 times the staging data
-- (74,000 payments: the seed's credit sales paid in one or two payments), staging settings, PostgreSQL 17: the payments
-- part of k17 read the large tenant's whole range of retail_sale_payments_sale (300 buffers, 0.8 ms) and now reads the
-- day (14 buffers, 0.05 ms); k17 went from 2.25 to 1.33 ms median, and the old cost grows with the history. The other day
-- columns k17 reads (reversed_on, returned_on, voided_on, fee_paid_on, delivered_on) are on tables of one row per receipt,
-- return or order, which the bench does not seed yet; they get no index without a plan that shows one used (ADR-028).
--
-- Locks. Flyway runs this file in one transaction. CREATE INDEX takes a SHARE lock on retail_sale_payments until the
-- commit: reads go on, a new payment on a credit sale waits for the build. On the 25 times copy the build took 62 ms
-- (index 1.4 MB); staging holds a twenty-fifth of that, so a plain CREATE INDEX, not CONCURRENTLY (which cannot run
-- inside Flyway's transaction and would need a migration of its own run outside it, ADR-028). SET LOCAL lock_timeout
-- makes the file fail after 5 seconds, to be re-run, rather than queue the sales behind a long report.
--
-- Flyway runs with outOfOrder off: V98 is claimed by pull request #567 (one staff phone per business) and V99 is the
-- placeholder that placeholder-migration-guard refuses, so this is V100.

SET LOCAL lock_timeout = '5s';

CREATE INDEX retail_sale_payments_by_day ON retail_sale_payments (tenant_id, paid_on);
