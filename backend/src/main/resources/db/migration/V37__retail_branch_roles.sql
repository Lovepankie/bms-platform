-- V37: retail branch roles (ADR-033, issue #191; docs/specs/trade-capabilities.md section 2).
--
-- Expand only, safe on a populated database: one new table, one new permission, and a backfill of
-- role 'both' for the branches of tenants that have the retail module. Nothing is dropped,
-- renamed or rewritten, and no tenant is switched on: the branch_roles capability lives in the
-- tenant settings group retail.capabilities and is off until an administrator turns it on. A
-- branch with no row reads as 'both' in code, so the backfill is a convenience and a branch
-- created at any moment is correct. Flyway runs with outOfOrder off, so the number is assigned
-- at merge time above the highest one on main.

-- ---------------------------------------------------------------------------------------------
-- retail_branch_roles: one row per branch at most. store never sells; shop and both do. The core
-- branches table is untouched (ADR-001: only retail needs the concept).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE retail_branch_roles (
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    branch_id  uuid        NOT NULL,
    role       text        NOT NULL CHECK (role IN ('store', 'shop', 'both')),
    changed_by uuid,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, branch_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
SELECT bms_apply_tenant_rls('retail_branch_roles');
SELECT bms_grant_app('retail_branch_roles', 'SELECT, INSERT, UPDATE');

-- Backfill: every existing branch of a tenant with retail gets role 'both', which is what every
-- branch is today. The migration runs as bms_owner (BYPASSRLS), so it sees every tenant.
INSERT INTO retail_branch_roles (tenant_id, branch_id, role)
SELECT b.tenant_id, b.id, 'both'
  FROM branches b
 WHERE EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = b.tenant_id AND m.module_key = 'retail')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- Permission (chapter 8 section 8.3.2). Administrators only: a role can narrow what a branch
-- accepts, so changing it is an administrator's act. It guards nothing that exists today.
-- PermissionMatrixIT compares this row with the matrix in chapter 8.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.branch_role.manage', 'retail', 'Set a retail branch as a store, a shop or both', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.branch_role.manage');
