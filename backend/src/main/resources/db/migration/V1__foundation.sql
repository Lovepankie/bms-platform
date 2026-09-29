-- V1: foundation schema (docs/sdd/06-database-design.md).
--
-- Runs as bms_owner, which owns every object created here. The application connects as bms_app
-- (NOSUPERUSER, NOBYPASSRLS, owns nothing) and gets only the privileges granted below.
-- Both roles are created before the first migration by deploy/postgres/initdb/01-roles.sh.
--
-- Contents: the RLS, grant and append-only helpers every later migration uses; reference
-- tables; the tenancy tables; audit_log; tenant_sequences; idempotency_keys; the general ledger
-- with its deferred balance trigger; users; lending_members; the db-scheduler table (ADR-008);
-- the SECURITY DEFINER tenant resolvers; the default lending chart of accounts seeder.
-- Tables of chapter 6 not listed here arrive with the feature that first uses them
-- (expand and contract, chapter 6 section 6.9).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------------------------
-- Helpers (chapter 6 section 6.3.2). Every tenant-owned table calls bms_apply_tenant_rls and
-- bms_grant_app; no migration writes a policy or a grant by hand.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION bms_apply_tenant_rls(p_table regclass, p_key text DEFAULT 'tenant_id') RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    -- current_setting without a default raises when app.tenant_id was never set in the session,
    -- and ''::uuid raises when a pooled session has it set to empty: a query that forgot to bind
    -- a tenant fails loudly and never returns rows (ADR-003).
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s '
        'USING (%I = current_setting(''app.tenant_id'')::uuid) '
        'WITH CHECK (%I = current_setting(''app.tenant_id'')::uuid)',
        p_table, p_key, p_key);
END
$$;

CREATE FUNCTION bms_grant_app(p_table regclass, p_privileges text) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF p_privileges !~ '^(SELECT|INSERT|UPDATE|DELETE)(, (SELECT|INSERT|UPDATE|DELETE))*$' THEN
        RAISE EXCEPTION 'bms_grant_app: unexpected privilege list %', p_privileges;
    END IF;
    EXECUTE format('GRANT %s ON %s TO bms_app', p_privileges, p_table);
END
$$;

-- Append-only tables (chapter 6 section 6.2.4). bms_app is granted SELECT and INSERT only, and
-- this trigger rejects UPDATE and DELETE for every role, bms_owner included, unless a migration
-- running as bms_owner opts in with: SET LOCAL bms.allow_mutation = 'on';
CREATE FUNCTION reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION '% on append-only table % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE FUNCTION bms_make_append_only(p_table regclass) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format(
        'CREATE TRIGGER reject_mutation BEFORE UPDATE OR DELETE ON %s FOR EACH ROW EXECUTE FUNCTION reject_mutation()',
        p_table);
END
$$;

REVOKE ALL ON FUNCTION bms_apply_tenant_rls(regclass, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION bms_grant_app(regclass, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION bms_make_append_only(regclass) FROM PUBLIC;

-- The readiness check compares the applied schema version with the image's newest migration.
GRANT SELECT ON flyway_schema_history TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Reference tables (chapter 6 section 6.4): no tenant_id, no RLS, bms_app reads only.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE currencies (
    code     char(3)  PRIMARY KEY,
    exponent smallint NOT NULL CHECK (exponent BETWEEN 0 AND 4),
    name     text     NOT NULL
);
INSERT INTO currencies (code, exponent, name) VALUES
    ('UGX', 0, 'Uganda shilling'),
    ('KES', 2, 'Kenya shilling'),
    ('TZS', 2, 'Tanzania shilling'),
    ('USD', 2, 'US dollar');
GRANT SELECT ON currencies TO bms_app;

CREATE TABLE plans (
    id                 uuid    PRIMARY KEY,
    code               text    NOT NULL UNIQUE,
    name               text    NOT NULL,
    max_branches       integer,
    max_staff_users    integer,
    max_active_members integer,
    allowed_modules    text[]  NOT NULL,
    is_active          boolean NOT NULL DEFAULT true
);
-- Limits are NULL (unlimited) until the platform sets them. Prices are commercial and are never
-- stored in this repository.
INSERT INTO plans (id, code, name, allowed_modules) VALUES
    ('00000000-0000-4000-8000-000000000001', 'starter', 'Starter', '{lending}'),
    ('00000000-0000-4000-8000-000000000002', 'growth', 'Growth', '{lending}'),
    ('00000000-0000-4000-8000-000000000003', 'institution', 'Institution', '{lending}');
GRANT SELECT ON plans TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Tenancy (chapter 6 section 6.5)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE tenants (
    id         uuid        PRIMARY KEY,
    slug       varchar(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]([a-z0-9-]{1,61}[a-z0-9])$'),
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended')),
    currency   char(3)     NOT NULL DEFAULT 'UGX' REFERENCES currencies (code),
    timezone   text        NOT NULL DEFAULT 'Africa/Kampala',
    plan_id    uuid        NOT NULL REFERENCES plans (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz
);
SELECT bms_apply_tenant_rls('tenants', 'id');
SELECT bms_grant_app('tenants', 'SELECT');

CREATE TABLE tenant_modules (
    tenant_id                   uuid        NOT NULL REFERENCES tenants (id),
    module_key                  text        NOT NULL CHECK (module_key IN ('lending', 'retail')),
    enabled_at                  timestamptz NOT NULL DEFAULT now(),
    enabled_by_platform_user_id uuid,
    PRIMARY KEY (tenant_id, module_key)
);
SELECT bms_apply_tenant_rls('tenant_modules');
SELECT bms_grant_app('tenant_modules', 'SELECT');

CREATE TABLE branches (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz,
    version        integer     NOT NULL DEFAULT 1,
    code           varchar(10) NOT NULL CHECK (code ~ '^[A-Z0-9]{2,10}$'),
    name           text        NOT NULL,
    location       text,
    is_head_office boolean     NOT NULL DEFAULT false,
    status         text        NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive')),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
CREATE UNIQUE INDEX branches_one_head_office ON branches (tenant_id) WHERE is_head_office;
SELECT bms_apply_tenant_rls('branches');
SELECT bms_grant_app('branches', 'SELECT, INSERT, UPDATE');

CREATE TABLE users (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz,
    version            integer      NOT NULL DEFAULT 1,
    kind               text         NOT NULL CHECK (kind IN ('staff', 'member')),
    full_name          text         NOT NULL,
    email              varchar(320),
    phone_e164         varchar(16),
    status             text         NOT NULL CHECK (status IN ('invited', 'active', 'deactivated')),
    failed_login_count integer      NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    mfa_enabled        boolean      NOT NULL DEFAULT false,
    last_login_at      timestamptz,
    UNIQUE (tenant_id, id),
    CHECK (email IS NOT NULL OR phone_e164 IS NOT NULL)
);
CREATE UNIQUE INDEX users_staff_email ON users (tenant_id, lower(email)) WHERE email IS NOT NULL AND kind = 'staff';
CREATE UNIQUE INDEX users_member_phone ON users (tenant_id, phone_e164) WHERE kind = 'member';
SELECT bms_apply_tenant_rls('users');
SELECT bms_grant_app('users', 'SELECT, INSERT, UPDATE');

CREATE TABLE tenant_sequences (
    tenant_id    uuid   NOT NULL REFERENCES tenants (id),
    sequence_key text   NOT NULL,
    next_value   bigint NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, sequence_key)
);
SELECT bms_apply_tenant_rls('tenant_sequences');
SELECT bms_grant_app('tenant_sequences', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Audit (append-only)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE audit_log (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    actor_user_id uuid,
    actor_kind    text         NOT NULL CHECK (actor_kind IN ('staff', 'member', 'system', 'platform')),
    branch_id     uuid,
    action        varchar(100) NOT NULL,
    entity_type   varchar(100) NOT NULL,
    entity_id     uuid,
    request_id    varchar(64),
    ip            inet,
    data          jsonb        NOT NULL DEFAULT '{}'
);
CREATE INDEX audit_log_by_time ON audit_log (tenant_id, created_at DESC);
CREATE INDEX audit_log_by_entity ON audit_log (tenant_id, entity_type, entity_id);
CREATE INDEX audit_log_by_actor ON audit_log (tenant_id, actor_user_id, created_at DESC);
SELECT bms_apply_tenant_rls('audit_log');
SELECT bms_grant_app('audit_log', 'SELECT, INSERT');
SELECT bms_make_append_only('audit_log');

-- ---------------------------------------------------------------------------------------------
-- Idempotency keys (chapter 7 section 7.8)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE idempotency_keys (
    tenant_id       uuid         NOT NULL REFERENCES tenants (id),
    principal_id    uuid         NOT NULL,
    key             varchar(100) NOT NULL,
    method          text         NOT NULL,
    path            text         NOT NULL,
    request_hash    char(64)     NOT NULL,
    status          text         NOT NULL CHECK (status IN ('in_progress', 'completed')),
    response_status smallint,
    response_body   jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    expires_at      timestamptz  NOT NULL DEFAULT now() + interval '7 days',
    PRIMARY KEY (tenant_id, principal_id, key)
);
CREATE INDEX idempotency_keys_expiry ON idempotency_keys (tenant_id, expires_at);
SELECT bms_apply_tenant_rls('idempotency_keys');
SELECT bms_grant_app('idempotency_keys', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- General ledger (chapter 6 section 6.6, ADR-004)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE gl_accounts (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    version              integer      NOT NULL DEFAULT 1,
    code                 varchar(20)  NOT NULL,
    name                 varchar(200) NOT NULL,
    account_type         text         NOT NULL CHECK (account_type IN ('asset', 'liability', 'equity', 'income', 'expense')),
    normal_balance       text         NOT NULL CHECK (normal_balance IN ('debit', 'credit')),
    parent_id            uuid,
    currency             char(3)      NOT NULL REFERENCES currencies (code),
    system_key           text,
    is_postable          boolean      NOT NULL,
    is_system_controlled boolean      NOT NULL DEFAULT false,
    is_active            boolean      NOT NULL DEFAULT true,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES gl_accounts (tenant_id, id)
);
CREATE UNIQUE INDEX gl_accounts_system_key ON gl_accounts (tenant_id, system_key) WHERE system_key IS NOT NULL;
SELECT bms_apply_tenant_rls('gl_accounts');
SELECT bms_grant_app('gl_accounts', 'SELECT, INSERT, UPDATE');

CREATE TABLE gl_periods (
    id            uuid        NOT NULL PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz,
    version       integer     NOT NULL DEFAULT 1,
    year          smallint    NOT NULL,
    month         smallint    NOT NULL CHECK (month BETWEEN 1 AND 12),
    status        text        NOT NULL CHECK (status IN ('open', 'closed')),
    closed_by     uuid,
    closed_at     timestamptz,
    reopened_by   uuid,
    reopened_at   timestamptz,
    reopen_reason text,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, year, month)
);
SELECT bms_apply_tenant_rls('gl_periods');
SELECT bms_grant_app('gl_periods', 'SELECT, INSERT, UPDATE');

CREATE TABLE journal_entries (
    id                uuid         NOT NULL PRIMARY KEY,
    tenant_id         uuid         NOT NULL REFERENCES tenants (id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    branch_id         uuid         NOT NULL,
    entry_no          varchar(20)  NOT NULL,
    entry_date        date         NOT NULL,
    period_id         uuid         NOT NULL,
    reference         varchar(100) NOT NULL,
    memo              text,
    source_module     varchar(50)  NOT NULL,
    source_type       varchar(50)  NOT NULL,
    source_id         uuid,
    reverses_entry_id uuid,
    idempotency_key   varchar(100),
    created_by        uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, entry_no),
    UNIQUE (tenant_id, reverses_entry_id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, period_id) REFERENCES gl_periods (tenant_id, id),
    FOREIGN KEY (tenant_id, reverses_entry_id) REFERENCES journal_entries (tenant_id, id)
);
CREATE UNIQUE INDEX journal_entries_idempotency ON journal_entries (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX journal_entries_by_branch_date ON journal_entries (tenant_id, branch_id, entry_date);
CREATE INDEX journal_entries_by_source ON journal_entries (tenant_id, source_type, source_id);
SELECT bms_apply_tenant_rls('journal_entries');
SELECT bms_grant_app('journal_entries', 'SELECT, INSERT');
SELECT bms_make_append_only('journal_entries');

CREATE TABLE journal_lines (
    id             uuid        NOT NULL PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    entry_id       uuid        NOT NULL,
    line_no        smallint    NOT NULL,
    account_id     uuid        NOT NULL,
    debit          bigint      NOT NULL DEFAULT 0,
    credit         bigint      NOT NULL DEFAULT 0,
    currency       char(3)     NOT NULL REFERENCES currencies (code),
    subledger_type varchar(50),
    subledger_id   uuid,
    memo           text,
    UNIQUE (tenant_id, id),
    UNIQUE (entry_id, line_no),
    CHECK (debit >= 0 AND credit >= 0),
    CHECK ((debit > 0) <> (credit > 0)),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES journal_entries (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES gl_accounts (tenant_id, id)
);
CREATE INDEX journal_lines_by_account ON journal_lines (tenant_id, account_id);
CREATE INDEX journal_lines_by_subledger ON journal_lines (tenant_id, subledger_type, subledger_id);
SELECT bms_apply_tenant_rls('journal_lines');
SELECT bms_grant_app('journal_lines', 'SELECT, INSERT');
SELECT bms_make_append_only('journal_lines');

-- Balance enforcement at commit (ADR-004): every entry has at least two lines and, per currency,
-- debits equal credits. Deferred, so an entry and its lines can be inserted in any order inside
-- one transaction; checked again for every line and for the entry itself.
CREATE FUNCTION journal_entry_balance_check() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entry_id  uuid;
    v_lines     integer;
    v_off_currs integer;
BEGIN
    -- NEW has a different shape on each table, so read the field inside the matching branch.
    IF TG_TABLE_NAME = 'journal_entries' THEN
        v_entry_id := NEW.id;
    ELSE
        v_entry_id := NEW.entry_id;
    END IF;
    SELECT count(*) INTO v_lines FROM journal_lines WHERE entry_id = v_entry_id;
    IF v_lines < 2 THEN
        RAISE EXCEPTION 'journal entry % has % line(s); at least two are required', v_entry_id, v_lines
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO v_off_currs FROM (
        SELECT currency FROM journal_lines WHERE entry_id = v_entry_id
         GROUP BY currency HAVING sum(debit) <> sum(credit)) unbalanced;
    IF v_off_currs > 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced', v_entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER journal_entry_balance_check
    AFTER INSERT ON journal_lines DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balance_check();
CREATE CONSTRAINT TRIGGER journal_entry_has_lines_check
    AFTER INSERT ON journal_entries DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balance_check();

-- ---------------------------------------------------------------------------------------------
-- Lending: members (chapter 6 section 6.7)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_members (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    updated_at           timestamptz,
    version              integer      NOT NULL DEFAULT 1,
    branch_id            uuid         NOT NULL,
    member_no            varchar(20)  NOT NULL,
    full_name            varchar(200) NOT NULL,
    first_name           varchar(100),
    last_name            varchar(100),
    phone_e164           varchar(16)  NOT NULL,
    alt_phone_e164       varchar(16),
    id_type              text         NOT NULL CHECK (id_type IN ('nin', 'passport', 'refugee_id', 'other', 'none')),
    national_id          varchar(14)  CHECK (national_id ~ '^C[MF][A-Z0-9]{12}$'),
    other_id_number      varchar(40),
    date_of_birth        date,
    gender               text         CHECK (gender IN ('female', 'male', 'other', 'unspecified')),
    marital_status       text         NOT NULL DEFAULT 'unknown'
                                      CHECK (marital_status IN ('single', 'married', 'divorced', 'widowed', 'separated', 'unknown')),
    district             varchar(100),
    sub_county           varchar(100),
    village              varchar(100),
    location             varchar(200),
    occupation           varchar(120),
    other_income_source  varchar(200),
    monthly_income_minor bigint       CHECK (monthly_income_minor >= 0),
    currency             char(3)      NOT NULL REFERENCES currencies (code),
    kyc_status           text         NOT NULL CHECK (kyc_status IN ('incomplete', 'pending_verification', 'verified', 'rejected')),
    kyc_verified_by      uuid,
    kyc_verified_at      timestamptz,
    status               text         NOT NULL CHECK (status IN ('active', 'inactive', 'exited')),
    is_blacklisted       boolean      NOT NULL DEFAULT false,
    blacklist_reason     text,
    officer_user_id      uuid,
    portal_user_id       uuid         UNIQUE,
    source               text         NOT NULL CHECK (source IN ('staff', 'import', 'portal')),
    -- FK to import_rows is added by the migration that creates the import tables (chapter 13).
    import_row_id        uuid,
    created_by           uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, member_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, portal_user_id) REFERENCES users (tenant_id, id),
    CHECK (NOT is_blacklisted OR blacklist_reason IS NOT NULL)
);
CREATE UNIQUE INDEX lending_members_nin ON lending_members (tenant_id, national_id) WHERE national_id IS NOT NULL;
CREATE INDEX lending_members_phone ON lending_members (tenant_id, phone_e164);
CREATE INDEX lending_members_branch_status ON lending_members (tenant_id, branch_id, status);
CREATE INDEX lending_members_name_trgm ON lending_members USING gin (full_name gin_trgm_ops);
SELECT bms_apply_tenant_rls('lending_members');
SELECT bms_grant_app('lending_members', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Background jobs (ADR-008): db-scheduler's own table, schema as published by db-scheduler for
-- PostgreSQL. Not tenant-owned: a task that works on tenant data binds each tenant itself.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE scheduled_tasks (
    task_name            text                     NOT NULL,
    task_instance        text                     NOT NULL,
    task_data            bytea,
    execution_time       timestamp with time zone NOT NULL,
    picked               boolean                  NOT NULL,
    picked_by            text,
    last_success         timestamp with time zone,
    last_failure         timestamp with time zone,
    consecutive_failures integer,
    last_heartbeat       timestamp with time zone,
    version              bigint                   NOT NULL,
    priority             smallint,
    PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
SELECT bms_grant_app('scheduled_tasks', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- The only sanctioned ways around the tenant policy (chapter 6 section 6.3.2). Owned by
-- bms_owner, fixed search_path, return ids only.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION app_resolve_tenant(p_slug text) RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT id FROM tenants WHERE slug = p_slug AND status = 'active'
$$;

CREATE FUNCTION app_list_active_tenants() RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT id FROM tenants WHERE status = 'active' ORDER BY id
$$;

REVOKE ALL ON FUNCTION app_resolve_tenant(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION app_list_active_tenants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_resolve_tenant(text) TO bms_app;
GRANT EXECUTE ON FUNCTION app_list_active_tenants() TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Default chart of accounts for a lending tenant (chapter 6 section 6.6.2). Called by the
-- onboarding script as bms_owner (docs/runbooks/onboard-tenant.md); not callable by bms_app.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION bms_seed_lending_chart(p_tenant_id uuid) RETURNS integer
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_currency char(3);
    v_count    integer;
BEGIN
    SELECT currency INTO STRICT v_currency FROM tenants WHERE id = p_tenant_id;

    INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency,
                             system_key, is_postable, is_system_controlled)
    SELECT gen_random_uuid(), p_tenant_id, c.code, c.name, c.account_type,
           CASE WHEN c.account_type IN ('asset', 'expense') THEN 'debit' ELSE 'credit' END,
           v_currency, c.system_key, c.postable, c.system_controlled
      FROM (VALUES
        ('1000', 'Assets',                        'asset',     NULL,                         false, false),
        ('1010', 'Cash on hand',                  'asset',     'cash_on_hand',               true,  false),
        ('1020', 'Bank',                          'asset',     'bank',                       true,  false),
        ('1030', 'Mobile money: MTN',             'asset',     'mobile_money_mtn',           true,  false),
        ('1031', 'Mobile money: Airtel',          'asset',     'mobile_money_airtel',        true,  false),
        ('1040', 'Payment gateway clearing',      'asset',     'gateway_clearing',           true,  false),
        ('1100', 'Loans receivable',              'asset',     'loans_receivable',           true,  true),
        ('1190', 'Inter-branch clearing',         'asset',     'interbranch_clearing',       true,  true),
        ('2000', 'Liabilities',                   'liability', NULL,                         false, false),
        ('2010', 'Member savings',                'liability', 'member_savings',             true,  true),
        ('2020', 'Member investments payable',    'liability', 'investments_payable',        true,  true),
        ('2021', 'Investment returns payable',    'liability', 'investment_returns_payable', true,  true),
        ('2030', 'Member overpayments',           'liability', 'member_overpayments',        true,  true),
        ('2040', 'Unallocated receipts',          'liability', 'unallocated_receipts',       true,  true),
        ('3000', 'Equity',                        'equity',    NULL,                         false, false),
        ('3010', 'Capital',                       'equity',    'capital',                    true,  false),
        ('3020', 'Retained earnings',             'equity',    'retained_earnings',          true,  false),
        ('3030', 'Opening balance equity',        'equity',    'opening_balance_equity',     true,  false),
        ('4000', 'Income',                        'income',    NULL,                         false, false),
        ('4010', 'Loan interest income',          'income',    'loan_interest_income',       true,  false),
        ('4020', 'Loan penalty income',           'income',    'loan_penalty_income',        true,  false),
        ('4030', 'Loan fee income',               'income',    'loan_fee_income',            true,  false),
        ('4040', 'Bad debt recovered',            'income',    'bad_debt_recovered',         true,  false),
        ('4050', 'Savings fee income',            'income',    'savings_fee_income',         true,  false),
        ('5000', 'Expenses',                      'expense',   NULL,                         false, false),
        ('5010', 'Savings interest expense',      'expense',   'savings_interest_expense',   true,  false),
        ('5020', 'Investment return expense',     'expense',   'investment_return_expense',  true,  false),
        ('5030', 'Loan write-off expense',        'expense',   'loan_write_off_expense',     true,  false),
        ('5040', 'Payment gateway charges',       'expense',   'gateway_charges',            true,  false),
        ('5900', 'Operating expenses',            'expense',   'operating_expenses',         true,  false)
      ) AS c (code, name, account_type, system_key, postable, system_controlled)
    ON CONFLICT (tenant_id, code) DO NOTHING;
    GET DIAGNOSTICS v_count = ROW_COUNT;

    -- Every postable account hangs under the header of its class (1000, 2000, ...).
    UPDATE gl_accounts child
       SET parent_id = header.id
      FROM gl_accounts header
     WHERE child.tenant_id = p_tenant_id
       AND header.tenant_id = p_tenant_id
       AND child.is_postable
       AND child.parent_id IS NULL
       AND header.code = left(child.code, 1) || '000';
    RETURN v_count;
END
$$;
REVOKE ALL ON FUNCTION bms_seed_lending_chart(uuid) FROM PUBLIC;
