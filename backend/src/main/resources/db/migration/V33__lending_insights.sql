-- V33: lending insights (issue #153; expectation 7 of the pilot brief, ADR-030): the insights
-- permissions, the per-loan daily snapshot of chapter 6 (FR-ARR-02), the owner's daily digest
-- settings and the indexes the insights queries need.
--
-- Additive only (chapter 6 section 6.9). Flyway runs with outOfOrder off: this file takes the
-- next number free on main and on every open branch when the pull request is marked ready.
--
-- The snapshot table is the one chapter 6 defines for the arrears job of increment 6 (#109).
-- That job does not exist yet, so the insights snapshot job writes it now; when increment 6
-- lands, its job writes the same rows (a rerun upserts) and the insights job is retired.

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3.2). Reading insights is not money-moving. Without
-- lending.insights.all_officers a user sees only the loans they are the responsible officer for.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.insights.read', 'lending', 'Insights: read', false),
    ('lending.insights.all_officers', 'lending', 'Insights: every officer''s loans in scope', false),
    ('lending.insights.export', 'lending', 'Insights: export tables as CSV', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.insights.read'),
    ('branch_manager', 'lending.insights.read'),
    ('loan_officer', 'lending.insights.read'),
    ('accountant', 'lending.insights.read'),
    ('auditor', 'lending.insights.read'),
    ('tenant_admin', 'lending.insights.all_officers'),
    ('branch_manager', 'lending.insights.all_officers'),
    ('accountant', 'lending.insights.all_officers'),
    ('auditor', 'lending.insights.all_officers'),
    ('tenant_admin', 'lending.insights.export'),
    ('branch_manager', 'lending.insights.export'),
    ('loan_officer', 'lending.insights.export'),
    ('accountant', 'lending.insights.export'),
    ('auditor', 'lending.insights.export')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------------------------
-- lending_loan_daily_snapshots (chapter 6, FR-ARR-02): one row per loan active at the end of a
-- business date. History comes from here; today's numbers are read live. A rerun of a date
-- replaces that date's rows.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_daily_snapshots (
    tenant_id                   uuid        NOT NULL REFERENCES tenants (id),
    business_date               date        NOT NULL,
    loan_id                     uuid        NOT NULL,
    branch_id                   uuid        NOT NULL,
    officer_user_id             uuid        NOT NULL,
    product_id                  uuid        NOT NULL,
    principal_outstanding_minor bigint      NOT NULL CHECK (principal_outstanding_minor >= 0),
    interest_outstanding_minor  bigint      NOT NULL CHECK (interest_outstanding_minor >= 0),
    arrears_minor               bigint      NOT NULL CHECK (arrears_minor >= 0),
    days_past_due               integer     NOT NULL CHECK (days_past_due >= 0),
    par_bucket                  text        NOT NULL CHECK (par_bucket IN ('current', '1_30', '31_60', '61_90', 'over_90')),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, business_date, loan_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES lending_loan_products (tenant_id, id)
);
CREATE INDEX lending_loan_daily_snapshots_branch ON lending_loan_daily_snapshots (tenant_id, branch_id, business_date);
SELECT bms_apply_tenant_rls('lending_loan_daily_snapshots');
SELECT bms_grant_app('lending_loan_daily_snapshots', 'SELECT, INSERT, DELETE');

-- ---------------------------------------------------------------------------------------------
-- lending_insights_digest_settings: the owner's daily digest (plain text through the outbox, on
-- email and Telegram). Off by default: a tenant without a row, or with enabled false, gets
-- nothing. At most five email recipients; the Telegram chat is a numeric chat id.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_insights_digest_settings (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    version          integer     NOT NULL DEFAULT 1,
    enabled          boolean     NOT NULL DEFAULT false,
    email_recipients text[]      NOT NULL DEFAULT '{}' CHECK (cardinality(email_recipients) <= 5),
    telegram_chat_id varchar(21) CHECK (telegram_chat_id ~ '^-?[0-9]{1,20}$'),
    send_hour        smallint    NOT NULL DEFAULT 7 CHECK (send_hour BETWEEN 0 AND 23),
    last_sent_on     date,
    updated_by       uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id)
);
SELECT bms_apply_tenant_rls('lending_insights_digest_settings');
SELECT bms_grant_app('lending_insights_digest_settings', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- Indexes for the insights queries (measured on the fabricated seed scaled 20 times; see
-- docs/specs/lending-insights-metrics.md). The branch index of V29 serves a branch filter; these
-- serve "all branches" and the member and application counts by date.
-- ---------------------------------------------------------------------------------------------

-- Collected on due joins allocations to the schedule items they settle; without this index a plan
-- chosen on young statistics scans every allocation once per item (seen in the full test suite).
CREATE INDEX lending_repayment_allocations_item
    ON lending_repayment_allocations (tenant_id, schedule_item_id) WHERE schedule_item_id IS NOT NULL;
CREATE INDEX lending_loan_transactions_type_date
    ON lending_loan_transactions (tenant_id, txn_type, value_date) INCLUDE (loan_id, amount_minor);
CREATE INDEX lending_members_created ON lending_members (tenant_id, created_at);
CREATE INDEX lending_loans_submitted ON lending_loans (tenant_id, submitted_at) WHERE submitted_at IS NOT NULL;
CREATE INDEX journal_entries_by_date ON journal_entries (tenant_id, entry_date);
