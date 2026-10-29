-- V115: the ADR-033 branch role 'store' is folded into the ADR-048 flag branches.sells_goods (issue #371, ADR-104). A Store is
-- now one thing, the meaning chosen for #371: a branch with sells_goods = false keeps stock and takes no money at its till. The
-- retail role keeps only the selling kinds, 'shop' and 'both', which the stock-by-location report groups by; a Store reads as
-- 'store' there from the flag.
--
-- The role was in force while the business had the retail module on and its branch_roles switch on. It refused a sale, a
-- quotation, a price resolution, an order and an offline sale report; the till kept working. A Store refuses those and also the
-- till: payments on credit sales and on account, refunds paid out, savings, bankings, withdrawals and advance repayments.
--
-- This migration never stops: a stopped migration stops the automatic staging and production deploy. Instead:
--
--   1. A 'store' branch in force with no blocker becomes a Store (sells_goods = false). retail_store_fold_blockers(tenant,
--      branch, since) names the blockers: an open branch that keeps no goods (it cannot be a Store), a credit sale still owing
--      (counted as BranchTillCheck counts it: written-off and imported sales left out, a delivery fee owed on the account in),
--      and any till money taken since the role was set (a later payment on a credit sale booked at that branch, a payment on
--      account, a refund paid out, savings, a banking, a withdrawal, an advance repayment).
--   2. A branch with any blocker is skipped: it keeps sells_goods true and can do everything it does today with its money. A
--      branch that keeps no goods is still refused quotations and offline sale reports by the application (ADR-104), as it is
--      refused sales. An administrator settles what is open and marks it a Store on Branches later.
--   3. Every 'store' row then becomes 'both'. A 'store' role that was not in force was never read, so that branch keeps selling.
--      No branch and no role row is deleted.
--   4. Each folded branch gets a system audit row, and each role row gets one with what it was and why: now a Store, skipped
--      with the blockers named, already a Store, keeps no goods, or not in force.
--   5. The role check refuses 'store' from now on, so the role and the flag can never disagree.
--
-- The function stays in the schema, owned by bms_owner and not granted to the application, so RetailStoreRoleFoldIT can check
-- it against till activity made through the API. The block between the fold markers is run again by that test on a scratch
-- database migrated to the migration before this one; a second run changes nothing. Flyway runs with outOfOrder off, so this
-- merges after V114 (#127), renumbered at merge time above the highest one on main if that moves.
--
-- The tables have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V103, bms_owner has BYPASSRLS, and an owner
-- without it drops FORCE for this transaction only.
--
-- Locks, all taken in one retried step before anything else. The deploy is zero downtime: the previous release keeps serving
-- while this runs, locks branch rows FOR SHARE and then reads retail_branch_roles on every sale, and may write a role. So, in
-- that same order:
--   - branches in EXCLUSIVE mode, so no transaction holds a branch row lock the fold's updates would wait on (plain reads go on);
--   - retail_branch_roles in ACCESS EXCLUSIVE mode. SHARE ROW EXCLUSIVE would already keep a role write from landing between
--     the fold and the narrowed check, but the constraint swap needs ACCESS EXCLUSIVE, and taking that later would be a second
--     lock step that could wait or deadlock behind traffic; taking it once here covers the fold and the swap, and the check
--     stays a plain validated constraint (no NOT VALID left for a later migration);
--   - only with an owner that lacks BYPASSRLS, every table whose FORCE is toggled below, in ACCESS EXCLUSIVE mode, so the
--     NO FORCE and FORCE statements take no lock of their own. Staging and production run as bms_owner with BYPASSRLS (V103),
--     where no toggle runs.
-- Each attempt waits at most two seconds, so a shop never queues long behind the request. On lock_not_available or
-- deadlock_detected the attempt's subtransaction is undone (no lock is kept while waiting), a notice names the attempt, and
-- the migration sleeps a second and tries again, for about two minutes (40 attempts). Only then does it stop, with a message
-- that says traffic held the locks and nothing was changed. statement_timeout is off during the loop so it cannot cut it
-- short; both timeouts go back to the session's own values once the locks are held, and no statement after that waits on
-- another transaction for these tables.

-- store-lock:begin
DO $$
DECLARE
    bypass      boolean := (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user);
    -- 40 in a deploy; RetailStoreRoleFoldIT sets a smaller number to reach the end of the loop quickly.
    attempts    integer := coalesce(nullif(current_setting('bms.store_fold_lock_attempts', true), '')::integer, 40);
    attempt     integer := 0;
    prior_lock  text := current_setting('lock_timeout');
    prior_limit text := current_setting('statement_timeout');
BEGIN
    -- The loop may run for about two minutes; a statement_timeout set for the session must not cut it short.
    PERFORM set_config('statement_timeout', '0', true);
    LOOP
        BEGIN
            PERFORM set_config('lock_timeout', '2s', true);
            -- branches before retail_branch_roles: the order the previous release's sale takes them (the branch row FOR
            -- SHARE, then the role read), so the two never wait on each other in a circle.
            LOCK TABLE branches IN EXCLUSIVE MODE;
            LOCK TABLE retail_branch_roles IN ACCESS EXCLUSIVE MODE;
            IF NOT bypass THEN
                LOCK TABLE tenant_settings, tenant_modules, branches, audit_log, retail_sales, retail_sale_payments,
                           journal_entries, retail_sale_writeoffs, retail_customer_orders, retail_account_payments,
                           retail_sale_returns, retail_daily_savings, retail_cash_bankings, retail_cash_withdrawals,
                           retail_advance_repayments
                    IN ACCESS EXCLUSIVE MODE;
            END IF;
            EXIT;
        EXCEPTION WHEN lock_not_available OR deadlock_detected THEN
            -- A deadlock is retried like a timeout: this attempt is undone and its locks released, so the other side goes on.
            attempt := attempt + 1;
            RAISE NOTICE 'V115: lock attempt % of % did not get the locks (%), trying again', attempt, attempts, SQLSTATE;
            IF attempt >= attempts THEN
                RAISE EXCEPTION USING
                    ERRCODE = '55P03',
                    MESSAGE = 'V115: other transactions held retail_branch_roles or branches for about two minutes, so the'
                              || ' branch role store was not folded. Nothing was changed.',
                    HINT = 'Run the deploy again when traffic is lower (issue #371, ADR-104).';
            END IF;
            PERFORM pg_sleep(1);
        END;
    END LOOP;
    -- The locks are held to the end of the transaction; the operator's own timeouts apply again from here.
    PERFORM set_config('lock_timeout', prior_lock, true);
    PERFORM set_config('statement_timeout', prior_limit, true);
END
$$;
-- store-lock:end

CREATE FUNCTION retail_store_fold_blockers(p_tenant uuid, p_branch uuid, p_since timestamptz) RETURNS text[]
    LANGUAGE sql STABLE
AS $$
    SELECT array_remove(ARRAY[
        CASE WHEN NOT b.stocks_goods AND b.status = 'active' THEN 'keeps_no_goods' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sales s
                           WHERE s.tenant_id = b.tenant_id AND s.branch_id = b.id AND s.payment_method = 'credit'
                             AND s.status = 'completed' AND NOT s.historical
                             AND ((s.paid_minor < s.total_minor
                                   AND NOT EXISTS (SELECT 1 FROM retail_sale_writeoffs w WHERE w.sale_id = s.id))
                                  OR EXISTS (SELECT 1 FROM retail_customer_orders o WHERE o.sale_id = s.id
                                              AND o.fee_method = 'account' AND o.fee_paid_at IS NULL)))
             THEN 'credit_sales_owing' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sale_payments p
                            JOIN retail_sales s ON s.tenant_id = p.tenant_id AND s.id = p.sale_id
                            JOIN journal_entries j ON j.tenant_id = p.tenant_id AND j.id = p.journal_entry_id
                           WHERE p.tenant_id = b.tenant_id AND j.branch_id = b.id AND p.created_at >= p_since
                             AND p.created_at > s.created_at)
             THEN 'credit_payments' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_account_payments x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'account_payments' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_sale_returns x
                           WHERE x.tenant_id = b.tenant_id AND x.refund_branch_id = b.id AND x.refund_minor > 0
                             AND x.created_at >= p_since)
             THEN 'refunds' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_daily_savings x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'savings' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_cash_bankings x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'bankings' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_cash_withdrawals x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'withdrawals' END,
        CASE WHEN EXISTS (SELECT 1 FROM retail_advance_repayments x
                           WHERE x.tenant_id = b.tenant_id AND x.branch_id = b.id AND x.created_at >= p_since)
             THEN 'advance_repayments' END
    ], NULL)
      FROM branches b
     WHERE b.tenant_id = p_tenant AND b.id = p_branch
$$;
REVOKE ALL ON FUNCTION retail_store_fold_blockers(uuid, uuid, timestamptz) FROM PUBLIC;

SET LOCAL row_security = off;
DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'tenant_modules', 'branches', 'retail_branch_roles', 'audit_log',
                                 'retail_sales', 'retail_sale_payments', 'journal_entries', 'retail_sale_writeoffs',
                                 'retail_customer_orders', 'retail_account_payments', 'retail_sale_returns',
                                 'retail_daily_savings', 'retail_cash_bankings', 'retail_cash_withdrawals',
                                 'retail_advance_repayments'] LOOP
            EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    END IF;
END
$$;
-- store-fold:begin
-- One statement: every part reads the rows as they were before it, so the reasons describe the branch as it was.
WITH store_fold AS (
SELECT r.tenant_id, r.branch_id, b.sells_goods, b.stocks_goods, x.in_force,
       CASE WHEN x.in_force AND b.sells_goods THEN retail_store_fold_blockers(r.tenant_id, r.branch_id, r.changed_at)
            ELSE ARRAY[]::text[] END AS blockers
  FROM retail_branch_roles r
  JOIN branches b ON b.tenant_id = r.tenant_id AND b.id = r.branch_id
 CROSS JOIN LATERAL (
       SELECT EXISTS (SELECT 1 FROM tenant_settings ts
                       WHERE ts.tenant_id = r.tenant_id
                         AND ts.settings #> '{module_groups,retail,branch_roles}' = 'true'::jsonb)
              AND EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = r.tenant_id AND m.module_key = 'retail')
              AS in_force) x
 WHERE r.role = 'store'
), folded AS (
    UPDATE branches b
       SET sells_goods = false, updated_at = now(), version = b.version + 1
      FROM store_fold f
     WHERE f.tenant_id = b.tenant_id AND f.branch_id = b.id
       AND f.in_force AND f.sells_goods AND f.stocks_goods AND cardinality(f.blockers) = 0
 RETURNING b.tenant_id, b.id
), renamed AS (
    UPDATE retail_branch_roles r
       SET role = 'both', changed_at = now()
      FROM store_fold f
     WHERE f.tenant_id = r.tenant_id AND f.branch_id = r.branch_id AND r.role = 'store'
 RETURNING f.*
)
INSERT INTO audit_log (id, tenant_id, actor_kind, branch_id, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), f.tenant_id, 'system', f.id, 'core.branch.updated', 'core.branch', f.id,
       jsonb_build_object(
           'before', jsonb_build_object('sells_goods', true),
           'after', jsonb_build_object('sells_goods', false),
           'reason', 'V115: the branch role store is now the Store flag; it keeps stock and takes no money at its till (ADR-104)')
  FROM folded f
UNION ALL
SELECT gen_random_uuid(), n.tenant_id, 'system', n.branch_id, 'retail.branch_role.changed', 'retail.branch_role', n.branch_id,
       jsonb_build_object(
           'before', jsonb_build_object('role', 'store'),
           'after', jsonb_build_object('role', 'both'),
           'blockers', to_jsonb(n.blockers),
           'reason', 'V115: ' || CASE
               WHEN NOT n.in_force THEN 'the role was not in force (retail or branch_roles off), so the branch keeps selling'
               WHEN NOT n.sells_goods THEN 'the branch was already a Store'
               WHEN cardinality(n.blockers) > 0 THEN 'not made a Store because of ' || array_to_string(n.blockers, ', ')
                   || '; the branch keeps selling and taking money as before. Settle what is open and mark it a Store on'
                   || ' Branches'
               WHEN NOT n.stocks_goods THEN 'the branch keeps no goods, so it cannot be a Store and still sells nothing'
               ELSE 'the branch is now a Store' END || ' (ADR-104)')
  FROM renamed n;
-- store-fold:end
DO $$
DECLARE
    t text;
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        FOREACH t IN ARRAY ARRAY['tenant_settings', 'tenant_modules', 'branches', 'retail_branch_roles', 'audit_log',
                                 'retail_sales', 'retail_sale_payments', 'journal_entries', 'retail_sale_writeoffs',
                                 'retail_customer_orders', 'retail_account_payments', 'retail_sale_returns',
                                 'retail_daily_savings', 'retail_cash_bankings', 'retail_cash_withdrawals',
                                 'retail_advance_repayments'] LOOP
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        END LOOP;
    END IF;
END
$$;
SET LOCAL row_security = on;

ALTER TABLE retail_branch_roles
    DROP CONSTRAINT retail_branch_roles_role_check,
    ADD CONSTRAINT retail_branch_roles_role_check CHECK (role IN ('shop', 'both'));

UPDATE permissions SET description = 'Set a selling retail branch as a shop or both' WHERE key = 'retail.branch_role.manage';
