-- V73: retail roles, slice 1 (issue #399, ADR-055): the Storekeeper and Purchasing roles, and the rule that an administrator
-- always covers every branch. The number is the next one after V72 on main; if another migration lands on main first, this
-- file is renamed to the next free number at merge (Flyway runs with outOfOrder off).
--
-- Additive only, safe on a populated database. Roles and their permissions are global rows (chapter 8 section 8.3), so every
-- tenant gets the two new roles from this migration; nobody holds them until an administrator gives them on the Users screen.
-- No existing assignment loses anything: the Sales role and the administrator keep exactly their permissions, and the only
-- assignments touched are administrator assignments limited to one branch, which are widened to every branch (an
-- administrator is never limited by branch, ADR-055 decision 4). The API has refused a branch on the administrator role since
-- V2, so on a database written only through the API there is none to widen.
--
-- The first statement sets lock_timeout to five seconds (as V26 and V48 do): if a sign-in holds the assignments table the
-- migration fails and is retried instead of queueing every writer behind it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- Storekeeper: moves stock between the stores and shops it works in, receives it, counts it and reports usage and damage.
-- No selling, no prices, no money, no cost: it holds no sale, price, purchase, cash book or profit permission. Each
-- assignment carries the branch it works in, as every role does (ADR-017), so a storekeeper of two stores has two rows.
--
-- Purchasing: records purchases and restocks (and, with the purchasing capability, purchase orders and goods received) at
-- its branches. It is given on top of another role, to chosen people; by default only administrators hold
-- retail.purchase.create. It sees the buying prices it types, as every holder of retail.purchase.create does, and nothing
-- more: supplier invoices and payments stay with retail.payable.manage.
--
-- PermissionMatrixIT compares these rows with the matrix in chapter 8 section 8.3.2.
-- ---------------------------------------------------------------------------------------------

INSERT INTO roles (key, name, kind, mfa_required) VALUES
    ('retail_storekeeper', 'Storekeeper', 'staff', false),
    ('retail_purchasing', 'Purchasing', 'staff', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('retail_storekeeper', 'retail.stock.read'),
    ('retail_storekeeper', 'retail.stock.transfer'),
    ('retail_storekeeper', 'retail.stocktake.commit'),
    ('retail_storekeeper', 'retail.usage.report'),
    ('retail_purchasing', 'retail.stock.read'),
    ('retail_purchasing', 'retail.purchase.create');

-- ---------------------------------------------------------------------------------------------
-- An administrator covers every branch. An active administrator assignment limited to a branch is widened: where the same
-- person already holds the administrator role for every branch the limited row is revoked (the unique index allows one active
-- row per role and branch), otherwise it is changed to every branch. Then a check keeps it so. Flyway runs the file in one
-- transaction, so the check is added in one step: its ACCESS EXCLUSIVE lock is held until commit either way, over the scan of
-- a table of a few rows per staff member (the lock_timeout above bounds the wait for it). The number of rows widened and
-- revoked is reported as a NOTICE in the migration log, and each one gets an audit row by the system (below).
-- ---------------------------------------------------------------------------------------------

DO $$
DECLARE
    limited integer;
BEGIN
    SELECT count(*) INTO limited FROM user_role_assignments
     WHERE role_key = 'tenant_admin' AND branch_id IS NOT NULL AND revoked_at IS NULL;
    RAISE NOTICE 'V73: % active administrator assignment(s) limited to a branch are widened or revoked', limited;
END
$$;

-- One audit row per administrator assignment widened or revoked, by the system, with the branch it had, so the owner can see
-- in the audit log that no person made the change and what it was.
INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type, entity_id, data)
SELECT gen_random_uuid(), a.tenant_id, 'system', 'core.user.roles_changed', 'core.user', a.user_id,
       jsonb_build_object(
           'before', jsonb_build_object('roles', jsonb_build_array('tenant_admin@' || a.branch_id::text)),
           'after', jsonb_build_object('roles', jsonb_build_array('tenant_admin@all')),
           'reason', 'V73: an administrator covers every branch (ADR-055)')
  FROM user_role_assignments a
 WHERE a.role_key = 'tenant_admin' AND a.branch_id IS NOT NULL AND a.revoked_at IS NULL;

UPDATE user_role_assignments a
   SET revoked_at = now(), updated_at = now(), version = version + 1
 WHERE a.role_key = 'tenant_admin' AND a.branch_id IS NOT NULL AND a.revoked_at IS NULL
   AND EXISTS (SELECT 1 FROM user_role_assignments o
                WHERE o.tenant_id = a.tenant_id AND o.user_id = a.user_id AND o.role_key = 'tenant_admin'
                  AND o.branch_id IS NULL AND o.revoked_at IS NULL);

-- Two limited rows of one person: the first becomes every branch and the others are revoked.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY tenant_id, user_id ORDER BY created_at, id) AS n
      FROM user_role_assignments
     WHERE role_key = 'tenant_admin' AND branch_id IS NOT NULL AND revoked_at IS NULL
)
UPDATE user_role_assignments a
   SET revoked_at = now(), updated_at = now(), version = version + 1
  FROM ranked r
 WHERE a.id = r.id AND r.n > 1;

UPDATE user_role_assignments
   SET branch_id = NULL, updated_at = now(), version = version + 1
 WHERE role_key = 'tenant_admin' AND branch_id IS NOT NULL AND revoked_at IS NULL;

ALTER TABLE user_role_assignments
    ADD CONSTRAINT user_role_assignments_admin_every_branch
    CHECK (role_key <> 'tenant_admin' OR branch_id IS NULL OR revoked_at IS NOT NULL);
