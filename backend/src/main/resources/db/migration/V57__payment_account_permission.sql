-- V57: the permission to change a lender's payment account, and the check of the rows already in payment_intents
-- (extras slice A7, issue #233; ADR-035 amendment of 2026-10-18).
--
-- 1. `core.payment_account.manage`: save, test and remove the business's own payment provider account. Changing it
--    redirects where every later repayment is paid, so it is held by the tenant admin only (not by branch managers or
--    anyone with the general `core.settings.manage`) and every change asks for a second-factor code and tells the other
--    holders. Two inserts into the catalogue; no table is touched.
-- 2. Validates `payment_intents_scope_purpose` (added NOT VALID by V56) in this migration, apart from V56's ACCESS EXCLUSIVE
--    ALTER: VALIDATE CONSTRAINT holds only SHARE UPDATE EXCLUSIVE, so writes to payment_intents continue during the scan.
--    (Flyway runs this file in one transaction with the permission inserts above, which take no table lock of note.) If a row already breaks the
--    rule (a platform-scope row with a lender purpose, or the reverse) the constraint is left NOT VALID, which still binds
--    every new row, and a warning names the count; the migration never fails a deploy over the history of a table that
--    held no real payment when this was written.

SET LOCAL lock_timeout = '5s';

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.payment_account.manage', 'core', 'Payments: connect, test and remove the business''s own payment provider account', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.payment_account.manage')
ON CONFLICT DO NOTHING;

DO $$
DECLARE
    bad bigint;
BEGIN
    SELECT count(*) INTO bad FROM payment_intents WHERE (scope = 'platform') <> (purpose LIKE 'platform.%');
    IF bad = 0 THEN
        ALTER TABLE payment_intents VALIDATE CONSTRAINT payment_intents_scope_purpose;
    ELSE
        RAISE WARNING 'payment_intents_scope_purpose left NOT VALID: % existing row(s) break it', bad;
    END IF;
END
$$;
