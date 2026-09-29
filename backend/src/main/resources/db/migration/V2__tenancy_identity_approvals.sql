-- V2: tenancy, identity and approvals (MVP increment 1, issue #7).
--
-- Additive only (chapter 6 section 6.9): new reference tables, new tenant-owned tables, the
-- platform tables of chapter 6 section 6.4, and new SECURITY DEFINER functions. Nothing created by
-- V1 is altered.
--
-- Contents: the role and permission catalogue seeded from the chapter 8 matrix; subscriptions,
-- tenant_settings; users' credentials, recovery codes, invitations, sessions and role
-- assignments; approval_requests; the platform users, sessions, recovery codes and audit log; the
-- request-time tenant resolver that also returns suspended tenants (FR-TEN-06); the module-aware
-- job tenant list (FR-TEN-03); and the platform functions that create and change tenants
-- (FR-TEN-01, FR-TEN-03, FR-TEN-05, ADR-016).

-- ---------------------------------------------------------------------------------------------
-- Role and permission catalogue (chapter 6 section 6.4, chapter 8 section 8.3). Reference data:
-- no tenant_id, no RLS, bms_app reads only. The rows below are the chapter 8 matrix, cell for
-- cell; PermissionMatrixIT compares them with docs/sdd/08-security-design.md (FR-IAM-02).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE permissions (
    key             text    PRIMARY KEY CHECK (key ~ '^[a-z_]+\.[a-z_]+\.[a-z_]+$'),
    module          text    NOT NULL,
    description     text    NOT NULL,
    is_money_moving boolean NOT NULL
);
GRANT SELECT ON permissions TO bms_app;

CREATE TABLE roles (
    key          text    PRIMARY KEY,
    name         text    NOT NULL,
    kind         text    NOT NULL CHECK (kind IN ('staff', 'member')),
    -- FR-IAM-06: holders of this role must use a TOTP second factor.
    mfa_required boolean NOT NULL DEFAULT false
);
GRANT SELECT ON roles TO bms_app;

CREATE TABLE role_permissions (
    role_key       text NOT NULL REFERENCES roles (key),
    permission_key text NOT NULL REFERENCES permissions (key),
    PRIMARY KEY (role_key, permission_key)
);
GRANT SELECT ON role_permissions TO bms_app;

INSERT INTO roles (key, name, kind, mfa_required) VALUES
    ('tenant_admin', 'Tenant admin', 'staff', true),
    ('branch_manager', 'Branch manager', 'staff', false),
    ('loan_officer', 'Loan officer', 'staff', false),
    ('cashier', 'Cashier', 'staff', false),
    ('accountant', 'Accountant', 'staff', false),
    ('auditor', 'Auditor', 'staff', false),
    ('member', 'Member', 'member', false);

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.settings.read', 'core', 'Settings: read', false),
    ('core.settings.manage', 'core', 'Settings: manage', false),
    ('core.branches.read', 'core', 'Branches: read', false),
    ('core.branches.manage', 'core', 'Branches: manage', false),
    ('core.users.read', 'core', 'Users: read', false),
    ('core.users.manage', 'core', 'Users: manage', false),
    ('core.audit.read', 'core', 'Audit: read', false),
    ('core.audit.export', 'core', 'Audit: export', false),
    ('core.approvals.read', 'core', 'Approvals: read', false),
    ('core.ledger.read', 'core', 'Ledger: read', false),
    ('core.ledger_accounts.manage', 'core', 'Ledger accounts: manage', false),
    ('core.journals.create', 'core', 'Journals: create', true),
    ('core.journals.approve', 'core', 'Journals: approve', true),
    ('core.periods.close', 'core', 'Periods: close', false),
    ('core.periods.approve_close', 'core', 'Periods: approve close', false),
    ('core.payment_methods.manage', 'core', 'Payment methods: manage', false),
    ('core.notifications.read', 'core', 'Notifications: read', false),
    ('core.notification_templates.manage', 'core', 'Notification templates: manage', false),
    ('core.imports.manage', 'core', 'Imports: manage', false),
    ('core.imports.approve_commit', 'core', 'Imports: approve commit', true),
    ('core.payments.read', 'core', 'Payments: read', false),
    ('core.payments.collect', 'core', 'Payments: collect', true),
    ('core.payments.allocate', 'core', 'Payments: allocate', true),
    ('core.reports.financial', 'core', 'Reports: financial', false),
    ('core.reports.audit', 'core', 'Reports: audit', false),
    ('lending.members.read', 'lending', 'Members: read', false),
    ('lending.members.create', 'lending', 'Members: create', false),
    ('lending.members.update', 'lending', 'Members: update', false),
    ('lending.members.verify_kyc', 'lending', 'Members: verify kyc', false),
    ('lending.members.blacklist', 'lending', 'Members: blacklist', false),
    ('lending.members.transfer_approve', 'lending', 'Members: transfer approve', false),
    ('lending.products.read', 'lending', 'Products: read', false),
    ('lending.products.manage', 'lending', 'Products: manage', false),
    ('lending.loans.read', 'lending', 'Loans: read', false),
    ('lending.loans.create', 'lending', 'Loans: create', false),
    ('lending.loans.appraise', 'lending', 'Loans: appraise', false),
    ('lending.loans.approve', 'lending', 'Loans: approve', false),
    ('lending.loans.cancel', 'lending', 'Loans: cancel', false),
    ('lending.disbursements.request', 'lending', 'Disbursements: request', true),
    ('lending.disbursements.authorise', 'lending', 'Disbursements: authorise', true),
    ('lending.repayments.create', 'lending', 'Repayments: create', true),
    ('lending.repayments.reverse_request', 'lending', 'Repayments: reverse request', true),
    ('lending.repayments.reverse_approve', 'lending', 'Repayments: reverse approve', true),
    ('lending.credits.refund_approve', 'lending', 'Credits: refund approve', true),
    ('lending.charges.waive_request', 'lending', 'Charges: waive request', true),
    ('lending.charges.waive_approve', 'lending', 'Charges: waive approve', true),
    ('lending.loans.write_off_request', 'lending', 'Loans: write off request', true),
    ('lending.loans.write_off_approve', 'lending', 'Loans: write off approve', true),
    ('lending.loans.restructure_request', 'lending', 'Loans: restructure request', false),
    ('lending.loans.restructure_approve', 'lending', 'Loans: restructure approve', false),
    ('lending.collateral.read', 'lending', 'Collateral: read', false),
    ('lending.collateral.manage', 'lending', 'Collateral: manage', false),
    ('lending.collateral.release_request', 'lending', 'Collateral: release request', false),
    ('lending.collateral.release_approve', 'lending', 'Collateral: release approve', false),
    ('lending.savings.read', 'lending', 'Savings: read', false),
    ('lending.savings.open', 'lending', 'Savings: open', false),
    ('lending.savings.deposit', 'lending', 'Savings: deposit', true),
    ('lending.savings.withdraw', 'lending', 'Savings: withdraw', true),
    ('lending.savings.withdraw_approve', 'lending', 'Savings: withdraw approve', true),
    ('lending.savings_products.manage', 'lending', 'Savings products: manage', false),
    ('lending.investments.read', 'lending', 'Investments: read', false),
    ('lending.investments.open', 'lending', 'Investments: open', false),
    ('lending.investments.fund', 'lending', 'Investments: fund', true),
    ('lending.investments.payout', 'lending', 'Investments: payout', true),
    ('lending.investments.early_withdraw_approve', 'lending', 'Investments: early withdraw approve', true),
    ('lending.investment_products.manage', 'lending', 'Investment products: manage', false),
    ('lending.collections.read', 'lending', 'Collections: read', false),
    ('lending.collections.log_action', 'lending', 'Collections: log action', false),
    ('lending.collections.assign', 'lending', 'Collections: assign', false),
    ('lending.reports.portfolio', 'lending', 'Reports: portfolio', false),
    ('lending.reports.collections', 'lending', 'Reports: collections', false),
    ('lending.reports.members', 'lending', 'Reports: members', false),
    ('lending.reports.compliance', 'lending', 'Reports: compliance', false),
    ('member.self.read', 'member', 'Self: read', false),
    ('member.self.apply', 'member', 'Self: apply', false),
    ('member.self.pay', 'member', 'Self: pay', true),
    ('platform.tenants.read', 'platform', 'Tenants: read (super admin only)', false),
    ('platform.tenants.manage', 'platform', 'Tenants: manage (super admin only)', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.settings.read'),
    ('branch_manager', 'core.settings.read'),
    ('accountant', 'core.settings.read'),
    ('auditor', 'core.settings.read'),
    ('tenant_admin', 'core.settings.manage'),
    ('tenant_admin', 'core.branches.read'),
    ('branch_manager', 'core.branches.read'),
    ('loan_officer', 'core.branches.read'),
    ('cashier', 'core.branches.read'),
    ('accountant', 'core.branches.read'),
    ('auditor', 'core.branches.read'),
    ('tenant_admin', 'core.branches.manage'),
    ('tenant_admin', 'core.users.read'),
    ('branch_manager', 'core.users.read'),
    ('auditor', 'core.users.read'),
    ('tenant_admin', 'core.users.manage'),
    ('tenant_admin', 'core.audit.read'),
    ('branch_manager', 'core.audit.read'),
    ('accountant', 'core.audit.read'),
    ('auditor', 'core.audit.read'),
    ('tenant_admin', 'core.audit.export'),
    ('auditor', 'core.audit.export'),
    ('tenant_admin', 'core.approvals.read'),
    ('branch_manager', 'core.approvals.read'),
    ('loan_officer', 'core.approvals.read'),
    ('cashier', 'core.approvals.read'),
    ('accountant', 'core.approvals.read'),
    ('auditor', 'core.approvals.read'),
    ('tenant_admin', 'core.ledger.read'),
    ('branch_manager', 'core.ledger.read'),
    ('accountant', 'core.ledger.read'),
    ('auditor', 'core.ledger.read'),
    ('tenant_admin', 'core.ledger_accounts.manage'),
    ('accountant', 'core.ledger_accounts.manage'),
    ('tenant_admin', 'core.journals.create'),
    ('accountant', 'core.journals.create'),
    ('tenant_admin', 'core.journals.approve'),
    ('accountant', 'core.journals.approve'),
    ('tenant_admin', 'core.periods.close'),
    ('accountant', 'core.periods.close'),
    ('tenant_admin', 'core.periods.approve_close'),
    ('accountant', 'core.periods.approve_close'),
    ('tenant_admin', 'core.payment_methods.manage'),
    ('accountant', 'core.payment_methods.manage'),
    ('tenant_admin', 'core.notifications.read'),
    ('branch_manager', 'core.notifications.read'),
    ('auditor', 'core.notifications.read'),
    ('tenant_admin', 'core.notification_templates.manage'),
    ('tenant_admin', 'core.imports.manage'),
    ('accountant', 'core.imports.manage'),
    ('tenant_admin', 'core.imports.approve_commit'),
    ('accountant', 'core.imports.approve_commit'),
    ('tenant_admin', 'core.payments.read'),
    ('branch_manager', 'core.payments.read'),
    ('cashier', 'core.payments.read'),
    ('accountant', 'core.payments.read'),
    ('auditor', 'core.payments.read'),
    ('tenant_admin', 'core.payments.collect'),
    ('branch_manager', 'core.payments.collect'),
    ('cashier', 'core.payments.collect'),
    ('tenant_admin', 'core.payments.allocate'),
    ('cashier', 'core.payments.allocate'),
    ('accountant', 'core.payments.allocate'),
    ('tenant_admin', 'core.reports.financial'),
    ('branch_manager', 'core.reports.financial'),
    ('accountant', 'core.reports.financial'),
    ('auditor', 'core.reports.financial'),
    ('tenant_admin', 'core.reports.audit'),
    ('auditor', 'core.reports.audit'),
    ('tenant_admin', 'lending.members.read'),
    ('branch_manager', 'lending.members.read'),
    ('loan_officer', 'lending.members.read'),
    ('cashier', 'lending.members.read'),
    ('accountant', 'lending.members.read'),
    ('auditor', 'lending.members.read'),
    ('tenant_admin', 'lending.members.create'),
    ('branch_manager', 'lending.members.create'),
    ('loan_officer', 'lending.members.create'),
    ('tenant_admin', 'lending.members.update'),
    ('branch_manager', 'lending.members.update'),
    ('loan_officer', 'lending.members.update'),
    ('tenant_admin', 'lending.members.verify_kyc'),
    ('branch_manager', 'lending.members.verify_kyc'),
    ('tenant_admin', 'lending.members.blacklist'),
    ('branch_manager', 'lending.members.blacklist'),
    ('tenant_admin', 'lending.members.transfer_approve'),
    ('tenant_admin', 'lending.products.read'),
    ('branch_manager', 'lending.products.read'),
    ('loan_officer', 'lending.products.read'),
    ('cashier', 'lending.products.read'),
    ('accountant', 'lending.products.read'),
    ('auditor', 'lending.products.read'),
    ('tenant_admin', 'lending.products.manage'),
    ('tenant_admin', 'lending.loans.read'),
    ('branch_manager', 'lending.loans.read'),
    ('loan_officer', 'lending.loans.read'),
    ('cashier', 'lending.loans.read'),
    ('accountant', 'lending.loans.read'),
    ('auditor', 'lending.loans.read'),
    ('tenant_admin', 'lending.loans.create'),
    ('branch_manager', 'lending.loans.create'),
    ('loan_officer', 'lending.loans.create'),
    ('tenant_admin', 'lending.loans.appraise'),
    ('branch_manager', 'lending.loans.appraise'),
    ('loan_officer', 'lending.loans.appraise'),
    ('tenant_admin', 'lending.loans.approve'),
    ('branch_manager', 'lending.loans.approve'),
    ('tenant_admin', 'lending.loans.cancel'),
    ('branch_manager', 'lending.loans.cancel'),
    ('loan_officer', 'lending.loans.cancel'),
    ('tenant_admin', 'lending.disbursements.request'),
    ('branch_manager', 'lending.disbursements.request'),
    ('cashier', 'lending.disbursements.request'),
    ('tenant_admin', 'lending.disbursements.authorise'),
    ('branch_manager', 'lending.disbursements.authorise'),
    ('accountant', 'lending.disbursements.authorise'),
    ('tenant_admin', 'lending.repayments.create'),
    ('branch_manager', 'lending.repayments.create'),
    ('cashier', 'lending.repayments.create'),
    ('tenant_admin', 'lending.repayments.reverse_request'),
    ('branch_manager', 'lending.repayments.reverse_request'),
    ('cashier', 'lending.repayments.reverse_request'),
    ('accountant', 'lending.repayments.reverse_request'),
    ('tenant_admin', 'lending.repayments.reverse_approve'),
    ('branch_manager', 'lending.repayments.reverse_approve'),
    ('accountant', 'lending.repayments.reverse_approve'),
    ('tenant_admin', 'lending.credits.refund_approve'),
    ('branch_manager', 'lending.credits.refund_approve'),
    ('accountant', 'lending.credits.refund_approve'),
    ('tenant_admin', 'lending.charges.waive_request'),
    ('branch_manager', 'lending.charges.waive_request'),
    ('accountant', 'lending.charges.waive_request'),
    ('tenant_admin', 'lending.charges.waive_approve'),
    ('branch_manager', 'lending.charges.waive_approve'),
    ('accountant', 'lending.charges.waive_approve'),
    ('tenant_admin', 'lending.loans.write_off_request'),
    ('accountant', 'lending.loans.write_off_request'),
    ('tenant_admin', 'lending.loans.write_off_approve'),
    ('tenant_admin', 'lending.loans.restructure_request'),
    ('branch_manager', 'lending.loans.restructure_request'),
    ('tenant_admin', 'lending.loans.restructure_approve'),
    ('accountant', 'lending.loans.restructure_approve'),
    ('tenant_admin', 'lending.collateral.read'),
    ('branch_manager', 'lending.collateral.read'),
    ('loan_officer', 'lending.collateral.read'),
    ('cashier', 'lending.collateral.read'),
    ('accountant', 'lending.collateral.read'),
    ('auditor', 'lending.collateral.read'),
    ('tenant_admin', 'lending.collateral.manage'),
    ('branch_manager', 'lending.collateral.manage'),
    ('loan_officer', 'lending.collateral.manage'),
    ('tenant_admin', 'lending.collateral.release_request'),
    ('branch_manager', 'lending.collateral.release_request'),
    ('loan_officer', 'lending.collateral.release_request'),
    ('tenant_admin', 'lending.collateral.release_approve'),
    ('branch_manager', 'lending.collateral.release_approve'),
    ('tenant_admin', 'lending.savings.read'),
    ('branch_manager', 'lending.savings.read'),
    ('loan_officer', 'lending.savings.read'),
    ('cashier', 'lending.savings.read'),
    ('accountant', 'lending.savings.read'),
    ('auditor', 'lending.savings.read'),
    ('tenant_admin', 'lending.savings.open'),
    ('branch_manager', 'lending.savings.open'),
    ('loan_officer', 'lending.savings.open'),
    ('tenant_admin', 'lending.savings.deposit'),
    ('branch_manager', 'lending.savings.deposit'),
    ('cashier', 'lending.savings.deposit'),
    ('tenant_admin', 'lending.savings.withdraw'),
    ('branch_manager', 'lending.savings.withdraw'),
    ('cashier', 'lending.savings.withdraw'),
    ('tenant_admin', 'lending.savings.withdraw_approve'),
    ('branch_manager', 'lending.savings.withdraw_approve'),
    ('accountant', 'lending.savings.withdraw_approve'),
    ('tenant_admin', 'lending.savings_products.manage'),
    ('tenant_admin', 'lending.investments.read'),
    ('branch_manager', 'lending.investments.read'),
    ('loan_officer', 'lending.investments.read'),
    ('cashier', 'lending.investments.read'),
    ('accountant', 'lending.investments.read'),
    ('auditor', 'lending.investments.read'),
    ('tenant_admin', 'lending.investments.open'),
    ('branch_manager', 'lending.investments.open'),
    ('loan_officer', 'lending.investments.open'),
    ('tenant_admin', 'lending.investments.fund'),
    ('branch_manager', 'lending.investments.fund'),
    ('cashier', 'lending.investments.fund'),
    ('tenant_admin', 'lending.investments.payout'),
    ('branch_manager', 'lending.investments.payout'),
    ('cashier', 'lending.investments.payout'),
    ('tenant_admin', 'lending.investments.early_withdraw_approve'),
    ('branch_manager', 'lending.investments.early_withdraw_approve'),
    ('accountant', 'lending.investments.early_withdraw_approve'),
    ('tenant_admin', 'lending.investment_products.manage'),
    ('tenant_admin', 'lending.collections.read'),
    ('branch_manager', 'lending.collections.read'),
    ('loan_officer', 'lending.collections.read'),
    ('cashier', 'lending.collections.read'),
    ('accountant', 'lending.collections.read'),
    ('auditor', 'lending.collections.read'),
    ('tenant_admin', 'lending.collections.log_action'),
    ('branch_manager', 'lending.collections.log_action'),
    ('loan_officer', 'lending.collections.log_action'),
    ('cashier', 'lending.collections.log_action'),
    ('tenant_admin', 'lending.collections.assign'),
    ('branch_manager', 'lending.collections.assign'),
    ('tenant_admin', 'lending.reports.portfolio'),
    ('branch_manager', 'lending.reports.portfolio'),
    ('accountant', 'lending.reports.portfolio'),
    ('auditor', 'lending.reports.portfolio'),
    ('tenant_admin', 'lending.reports.collections'),
    ('branch_manager', 'lending.reports.collections'),
    ('loan_officer', 'lending.reports.collections'),
    ('cashier', 'lending.reports.collections'),
    ('accountant', 'lending.reports.collections'),
    ('auditor', 'lending.reports.collections'),
    ('tenant_admin', 'lending.reports.members'),
    ('branch_manager', 'lending.reports.members'),
    ('auditor', 'lending.reports.members'),
    ('tenant_admin', 'lending.reports.compliance'),
    ('accountant', 'lending.reports.compliance'),
    ('auditor', 'lending.reports.compliance'),
    ('member', 'member.self.read'),
    ('member', 'member.self.apply'),
    ('member', 'member.self.pay');

-- ---------------------------------------------------------------------------------------------
-- Tenancy (chapter 6 section 6.5): subscriptions and settings
-- ---------------------------------------------------------------------------------------------

CREATE TABLE subscriptions (
    id                          uuid        NOT NULL PRIMARY KEY,
    tenant_id                   uuid        NOT NULL REFERENCES tenants (id),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz,
    plan_id                     uuid        NOT NULL REFERENCES plans (id),
    status                      text        NOT NULL CHECK (status IN ('trial', 'active', 'past_due', 'suspended', 'cancelled')),
    current_period_start        date,
    current_period_end          date,
    trial_ends_on               date,
    next_status_change_on       date,
    updated_by_platform_user_id uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id)
);
SELECT bms_apply_tenant_rls('subscriptions');
SELECT bms_grant_app('subscriptions', 'SELECT');

CREATE TABLE tenant_settings (
    id         uuid        NOT NULL PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    version    integer     NOT NULL DEFAULT 1,
    -- Validated against a typed schema in the service (FR-TEN-08); absent keys take the defaults
    -- of chapter 6 section 6.5.
    settings   jsonb       NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id)
);
SELECT bms_apply_tenant_rls('tenant_settings');
SELECT bms_grant_app('tenant_settings', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Identity (chapter 6 section 6.5, chapter 8 section 8.2, ADR-014)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE user_credentials (
    user_id                 uuid        NOT NULL PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenants (id),
    -- argon2id in PHC string form (staff).
    password_hash           text,
    -- argon2id (members, FR-IAM-09, phase 2).
    pin_hash                text,
    -- AES-256-GCM with the application data key; the key id is part of the ciphertext.
    totp_secret_enc         bytea,
    -- A secret issued by enrolment and not yet confirmed with a code.
    totp_pending_secret_enc bytea,
    -- Last accepted TOTP time step: a code is accepted at most once.
    totp_last_step          bigint,
    password_changed_at     timestamptz,
    pin_failed_count        integer     NOT NULL DEFAULT 0,
    pin_locked_until        timestamptz,
    updated_at              timestamptz,
    UNIQUE (tenant_id, user_id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
SELECT bms_apply_tenant_rls('user_credentials');
SELECT bms_grant_app('user_credentials', 'SELECT, INSERT, UPDATE');

-- Single-use MFA recovery codes (FR-IAM-11), stored as SHA-256 hashes only.
CREATE TABLE user_recovery_codes (
    id         uuid        NOT NULL PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    user_id    uuid        NOT NULL,
    code_hash  char(64)    NOT NULL,
    used_at    timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
CREATE INDEX user_recovery_codes_by_user ON user_recovery_codes (tenant_id, user_id) WHERE used_at IS NULL;
SELECT bms_apply_tenant_rls('user_recovery_codes');
SELECT bms_grant_app('user_recovery_codes', 'SELECT, INSERT, UPDATE, DELETE');

CREATE TABLE user_invitations (
    id          uuid        NOT NULL PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    user_id     uuid        NOT NULL,
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    -- Set when a newer invitation replaces this one.
    revoked_at  timestamptz,
    -- A staff user, or the platform user who created the tenant (FR-TEN-01): no foreign key.
    invited_by  uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
CREATE INDEX user_invitations_by_user ON user_invitations (tenant_id, user_id);
SELECT bms_apply_tenant_rls('user_invitations');
SELECT bms_grant_app('user_invitations', 'SELECT, INSERT, UPDATE');

-- One row per refresh token. Rotation inserts the next row of the family and marks this one
-- rotated; presenting a rotated token revokes the family (FR-IAM-07). The access token's sid is
-- the row id; the per-request check refuses a revoked row (FR-IAM-08).
CREATE TABLE auth_sessions (
    id                 uuid        NOT NULL PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    user_id            uuid        NOT NULL,
    family_id          uuid        NOT NULL,
    refresh_token_hash char(64)    NOT NULL UNIQUE,
    expires_at         timestamptz NOT NULL,
    idle_expires_at    timestamptz NOT NULL,
    rotated_at         timestamptz,
    revoked_at         timestamptz,
    revoked_reason     text        CHECK (revoked_reason IN ('sign_out', 'rotation_reuse', 'user_deactivated', 'admin', 'mfa_reset')),
    ip                 inet,
    user_agent         text,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
CREATE INDEX auth_sessions_open_by_user ON auth_sessions (tenant_id, user_id) WHERE revoked_at IS NULL;
CREATE INDEX auth_sessions_by_family ON auth_sessions (tenant_id, family_id);
SELECT bms_apply_tenant_rls('auth_sessions');
SELECT bms_grant_app('auth_sessions', 'SELECT, INSERT, UPDATE');

CREATE TABLE user_role_assignments (
    id         uuid        NOT NULL PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz,
    version    integer     NOT NULL DEFAULT 1,
    user_id    uuid        NOT NULL,
    role_key   text        NOT NULL REFERENCES roles (key),
    -- NULL: every branch of the tenant.
    branch_id  uuid,
    granted_by uuid        NOT NULL,
    revoked_at timestamptz,
    revoked_by uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id)
);
CREATE UNIQUE INDEX user_role_assignments_active ON user_role_assignments
    (tenant_id, user_id, role_key, coalesce(branch_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE revoked_at IS NULL;
SELECT bms_apply_tenant_rls('user_role_assignments');
SELECT bms_grant_app('user_role_assignments', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Approvals (chapter 6 section 6.5, chapter 8 section 8.4, FR-APR). Action types are contributed
-- by the modules that own the actions and checked by the approval registry (ADR-015), so the
-- core does not enumerate a vertical's actions; the database checks their shape only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE approval_requests (
    id              uuid        NOT NULL PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz,
    version         integer     NOT NULL DEFAULT 1,
    branch_id       uuid        NOT NULL,
    action_type     text        NOT NULL CHECK (action_type ~ '^[a-z][a-z0-9_]{2,62}$'),
    subject_type    text        NOT NULL,
    subject_id      uuid        NOT NULL,
    amount_minor    bigint      CHECK (amount_minor >= 0),
    currency        char(3)     REFERENCES currencies (code),
    payload         jsonb       NOT NULL,
    subject_version integer,
    status          text        NOT NULL CHECK (status IN ('pending', 'approved', 'rejected', 'cancelled', 'expired', 'stale')),
    requested_by    uuid        NOT NULL,
    requested_at    timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    decided_by      uuid,
    decided_at      timestamptz,
    decision_note   text,
    execution_error text,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    -- FR-APR-02: the checker is never the maker, whatever the application does.
    CONSTRAINT approval_checker_is_not_maker CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CHECK (status <> 'rejected' OR (decision_note IS NOT NULL AND length(trim(decision_note)) > 0)),
    CHECK ((amount_minor IS NULL) = (currency IS NULL))
);
CREATE UNIQUE INDEX approval_requests_one_pending ON approval_requests (tenant_id, action_type, subject_id)
    WHERE status = 'pending';
CREATE INDEX approval_requests_queue ON approval_requests (tenant_id, status, branch_id, requested_at);
SELECT bms_apply_tenant_rls('approval_requests');
SELECT bms_grant_app('approval_requests', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Platform tables (chapter 6 section 6.4): no tenant_id, no RLS. Until the bms_platform role and
-- its connection pool exist, the platform endpoints run as bms_app (ADR-016).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE platform_users (
    id                      uuid         NOT NULL PRIMARY KEY,
    email                   varchar(320) NOT NULL,
    full_name               text         NOT NULL,
    password_hash           text,
    totp_secret_enc         bytea,
    totp_pending_secret_enc bytea,
    totp_last_step          bigint,
    mfa_enabled             boolean      NOT NULL DEFAULT false,
    is_active               boolean      NOT NULL DEFAULT true,
    failed_login_count      integer      NOT NULL DEFAULT 0,
    locked_until            timestamptz,
    -- One-time password setup token (SHA-256), issued by deploy/sql/create-platform-user.sql.
    setup_token_hash        char(64)     UNIQUE,
    setup_token_expires_at  timestamptz,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz,
    last_login_at           timestamptz
);
CREATE UNIQUE INDEX platform_users_email ON platform_users (lower(email));
GRANT SELECT, INSERT, UPDATE ON platform_users TO bms_app;

CREATE TABLE platform_user_recovery_codes (
    id               uuid        NOT NULL PRIMARY KEY,
    platform_user_id uuid        NOT NULL REFERENCES platform_users (id),
    code_hash        char(64)    NOT NULL,
    used_at          timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX platform_user_recovery_codes_by_user ON platform_user_recovery_codes (platform_user_id) WHERE used_at IS NULL;
GRANT SELECT, INSERT, UPDATE, DELETE ON platform_user_recovery_codes TO bms_app;

CREATE TABLE platform_sessions (
    id                 uuid        NOT NULL PRIMARY KEY,
    created_at         timestamptz NOT NULL DEFAULT now(),
    platform_user_id   uuid        NOT NULL REFERENCES platform_users (id),
    family_id          uuid        NOT NULL,
    refresh_token_hash char(64)    NOT NULL UNIQUE,
    expires_at         timestamptz NOT NULL,
    idle_expires_at    timestamptz NOT NULL,
    rotated_at         timestamptz,
    revoked_at         timestamptz,
    revoked_reason     text        CHECK (revoked_reason IN ('sign_out', 'rotation_reuse', 'user_deactivated', 'admin', 'mfa_reset')),
    ip                 inet,
    user_agent         text
);
CREATE INDEX platform_sessions_by_family ON platform_sessions (family_id);
GRANT SELECT, INSERT, UPDATE ON platform_sessions TO bms_app;

-- Survives tenant deletion: tenant_id is deliberately not a foreign key.
CREATE TABLE platform_audit_log (
    id               uuid         NOT NULL PRIMARY KEY,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    platform_user_id uuid,
    action           varchar(100) NOT NULL,
    tenant_id        uuid,
    data             jsonb        NOT NULL DEFAULT '{}',
    request_id       varchar(64),
    ip               inet
);
CREATE INDEX platform_audit_log_by_time ON platform_audit_log (occurred_at DESC);
GRANT SELECT, INSERT ON platform_audit_log TO bms_app;
SELECT bms_make_append_only('platform_audit_log');

-- ---------------------------------------------------------------------------------------------
-- Resolvers (chapter 6 section 6.3.2). Owned by bms_owner, fixed search_path, ids only.
-- ---------------------------------------------------------------------------------------------

-- Request-time resolution: a suspended tenant is still served, read only (FR-TEN-06). The jobs
-- keep using app_list_active_tenants, which excludes it.
CREATE FUNCTION app_resolve_tenant_status(p_slug text) RETURNS TABLE (id uuid, status text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT id, status FROM tenants WHERE slug = p_slug AND status IN ('active', 'suspended')
$$;

-- Active tenants with a module switched on: a vertical's scheduled jobs skip every other tenant
-- (FR-TEN-03).
CREATE FUNCTION app_list_active_tenants_with_module(p_module text) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id FROM tenants t
     WHERE t.status = 'active'
       AND EXISTS (SELECT 1 FROM tenant_modules m WHERE m.tenant_id = t.id AND m.module_key = p_module)
     ORDER BY t.id
$$;

REVOKE ALL ON FUNCTION app_resolve_tenant_status(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_list_active_tenants_with_module(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_tenant_status(text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_list_active_tenants_with_module(text) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Platform operations (FR-TEN-01, FR-TEN-03, FR-TEN-05, ADR-016). tenants, tenant_modules and
-- subscriptions are SELECT-only for bms_app; these functions are the only way the application
-- changes them. Each validates its input and returns nothing a tenant could not already see.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION platform_create_tenant(
    p_tenant_id uuid, p_slug text, p_name text, p_plan_code text, p_currency text, p_timezone text,
    p_modules text[], p_branch_id uuid, p_branch_code text, p_branch_name text, p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_plan plans%ROWTYPE;
BEGIN
    SELECT * INTO v_plan FROM plans WHERE code = p_plan_code AND is_active;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown plan %', p_plan_code USING ERRCODE = 'check_violation';
    END IF;
    IF NOT (coalesce(p_modules, '{}') <@ v_plan.allowed_modules) THEN
        RAISE EXCEPTION 'module not allowed by plan %', p_plan_code USING ERRCODE = 'check_violation';
    END IF;

    INSERT INTO tenants (id, slug, name, currency, timezone, plan_id)
    VALUES (p_tenant_id, p_slug, p_name, p_currency, p_timezone, v_plan.id);
    INSERT INTO subscriptions (id, tenant_id, plan_id, status, updated_by_platform_user_id)
    VALUES (gen_random_uuid(), p_tenant_id, v_plan.id, 'trial', p_platform_user_id);
    INSERT INTO tenant_settings (id, tenant_id) VALUES (gen_random_uuid(), p_tenant_id);
    INSERT INTO branches (id, tenant_id, code, name, is_head_office)
    VALUES (p_branch_id, p_tenant_id, p_branch_code, p_branch_name, true);
    INSERT INTO tenant_modules (tenant_id, module_key, enabled_by_platform_user_id)
    SELECT p_tenant_id, m, p_platform_user_id FROM unnest(coalesce(p_modules, '{}')) AS m;
    IF 'lending' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_lending_chart(p_tenant_id);
    END IF;
END
$$;

-- Replaces the tenant's enabled modules. Disabling keeps every row of data (FR-TEN-03).
CREATE FUNCTION platform_set_tenant_modules(p_tenant_id uuid, p_modules text[], p_platform_user_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_allowed text[];
BEGIN
    SELECT p.allowed_modules INTO v_allowed FROM tenants t JOIN plans p ON p.id = t.plan_id WHERE t.id = p_tenant_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
    END IF;
    IF NOT (coalesce(p_modules, '{}') <@ v_allowed) THEN
        RAISE EXCEPTION 'module not allowed by plan' USING ERRCODE = 'check_violation';
    END IF;
    DELETE FROM tenant_modules WHERE tenant_id = p_tenant_id AND NOT (module_key = ANY (coalesce(p_modules, '{}')));
    INSERT INTO tenant_modules (tenant_id, module_key, enabled_by_platform_user_id)
    SELECT p_tenant_id, m, p_platform_user_id FROM unnest(coalesce(p_modules, '{}')) AS m
    ON CONFLICT (tenant_id, module_key) DO NOTHING;
    IF 'lending' = ANY (coalesce(p_modules, '{}')) THEN
        PERFORM bms_seed_lending_chart(p_tenant_id);
    END IF;
END
$$;

-- Moves the subscription (FR-TEN-05). A suspended or cancelled subscription makes the tenant
-- read only (tenants.status = 'suspended', FR-TEN-06); any other status makes it active.
-- Returns the previous status for the platform audit row.
CREATE FUNCTION platform_set_subscription_status(
    p_tenant_id uuid, p_status text, p_next_status_change_on date, p_platform_user_id uuid)
RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous text;
BEGIN
    SELECT status INTO v_previous FROM subscriptions WHERE tenant_id = p_tenant_id FOR UPDATE;
    IF NOT FOUND THEN
        INSERT INTO subscriptions (id, tenant_id, plan_id, status, updated_by_platform_user_id)
        SELECT gen_random_uuid(), t.id, t.plan_id, 'active', p_platform_user_id FROM tenants t WHERE t.id = p_tenant_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'unknown tenant' USING ERRCODE = 'no_data_found';
        END IF;
        v_previous := 'active';
    END IF;
    UPDATE subscriptions
       SET status = p_status, next_status_change_on = p_next_status_change_on,
           updated_by_platform_user_id = p_platform_user_id, updated_at = now()
     WHERE tenant_id = p_tenant_id;
    UPDATE tenants
       SET status = CASE WHEN p_status IN ('suspended', 'cancelled') THEN 'suspended' ELSE 'active' END,
           updated_at = now()
     WHERE id = p_tenant_id;
    RETURN v_previous;
END
$$;

CREATE FUNCTION platform_list_tenants() RETURNS TABLE (
    id uuid, slug text, name text, status text, plan_code text, currency text, timezone text,
    subscription_status text, next_status_change_on date, modules text[], created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id, t.slug::text, t.name, t.status, p.code, t.currency::text, t.timezone,
           s.status, s.next_status_change_on,
           coalesce((SELECT array_agg(m.module_key ORDER BY m.module_key) FROM tenant_modules m WHERE m.tenant_id = t.id), '{}'),
           t.created_at
      FROM tenants t
      JOIN plans p ON p.id = t.plan_id
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     ORDER BY t.slug
$$;

REVOKE ALL ON FUNCTION platform_create_tenant(uuid, text, text, text, text, text, text[], uuid, text, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_set_tenant_modules(uuid, text[], uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_set_subscription_status(uuid, text, date, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_list_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_create_tenant(uuid, text, text, text, text, text, text[], uuid, text, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_set_tenant_modules(uuid, text[], uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_set_subscription_status(uuid, text, date, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_list_tenants() TO bms_app;
