-- V114: customer data onboarding 1/16 (issue #127, ADR-027, spec docs/specs/customer-data-onboarding.md sections 3.7, 3.11
-- and 5.4, SDD chapter 13 section 13.14). Expand only, safe on a populated database: three new tables and one function. No
-- existing table, column or constraint is touched and no permission is added.
--
-- `import_runs`: one onboarding attempt for one business, the recoverable unit of the commit. Its states are the spec's
-- section 3.11; the service enforces the transitions. At most one run per business is `committing` (the partial unique
-- index), and every batch commit also takes an advisory lock on the business's import key, so two operators never
-- interleave. `halt_requested` stops a run between batches and every 1,000 rows; the trading tenant override is set by one
-- platform operator with a reason and approved by a different one (the CHECK), and a commit into a business that already
-- trades is refused without it.
--
-- `import_batches`: the batches of a run in commit order. Created here in its smallest form (the run, the order, whether
-- it is committed, its counts) because the lock, the halt and the resume work per batch; the upload columns of chapter 13
-- section 13.4 come with #128, which widens the status list.
--
-- `platform_import_settings`: one row, the platform's kill switch `imports.commit_enabled`, on by default. Read by the
-- application, changed only through platform_set_import_commit_enabled, as platform_billing_settings (V92).
--
-- No personal data is stored in any of them: the override reason is an operator's words about the business, not a person.
--
-- Locks, said plainly. The tables are created empty; the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and
-- `platform_users` (the foreign keys) until the commit, milliseconds, reads go on.

SET LOCAL lock_timeout = '5s';

CREATE TABLE platform_import_settings (
    id                          smallint    NOT NULL PRIMARY KEY CHECK (id = 1),
    commit_enabled              boolean     NOT NULL DEFAULT true,
    updated_at                  timestamptz,
    updated_by_platform_user_id uuid        REFERENCES platform_users (id)
);
INSERT INTO platform_import_settings (id, commit_enabled) VALUES (1, true);
GRANT SELECT ON platform_import_settings TO bms_app;

-- Sets the kill switch; returns the previous value. The service audits the change with the operator's reason.
CREATE FUNCTION platform_set_import_commit_enabled(p_enabled boolean, p_platform_user_id uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_previous boolean;
BEGIN
    SELECT commit_enabled INTO v_previous FROM platform_import_settings WHERE id = 1 FOR UPDATE;
    UPDATE platform_import_settings
       SET commit_enabled = p_enabled, updated_at = now(), updated_by_platform_user_id = p_platform_user_id
     WHERE id = 1;
    RETURN v_previous;
END
$$;
REVOKE ALL ON FUNCTION platform_set_import_commit_enabled(boolean, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_set_import_commit_enabled(boolean, uuid) TO bms_app;

CREATE TABLE import_runs (
    id                        uuid         NOT NULL PRIMARY KEY,
    tenant_id                 uuid         NOT NULL REFERENCES tenants (id),
    created_at                timestamptz  NOT NULL DEFAULT now(),
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    version                   integer      NOT NULL DEFAULT 0,
    -- The staff user or platform operator who opened the run, as journal_entries.created_by.
    created_by                uuid         NOT NULL,
    status                    text         NOT NULL DEFAULT 'draft'
                              CHECK (status IN ('draft', 'uploaded', 'in_review', 'ready', 'committing', 'committed',
                                                'signed_off', 'purged', 'cancelled')),
    -- The moment the source was frozen; a source row dated after it is refused (DATE_AFTER_CUTOVER).
    cutover_at                timestamptz,
    -- Customer-confirmed policy decisions (spec section 3.8), keyed by decision.
    policy                    jsonb        NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(policy) = 'object'),
    -- The verified dump taken just before the commit; restoring it is the only whole-run rollback. No dump, no commit.
    pre_import_dump_file      varchar(255),
    pre_import_dump_at        timestamptz,
    pre_import_dump_sha256    char(64)     CHECK (pre_import_dump_sha256 ~ '^[0-9a-f]{64}$'),
    halt_requested            boolean      NOT NULL DEFAULT false,
    allow_trading_tenant      boolean      NOT NULL DEFAULT false,
    allow_trading_reason      varchar(500),
    allow_trading_set_by      uuid         REFERENCES platform_users (id),
    allow_trading_approved_by uuid         REFERENCES platform_users (id),
    uploaded_at               timestamptz,
    in_review_at              timestamptz,
    ready_at                  timestamptz,
    committing_at             timestamptz,
    committed_at              timestamptz,
    signed_off_at             timestamptz,
    purged_at                 timestamptz,
    cancelled_at              timestamptz,
    UNIQUE (tenant_id, id),
    CHECK ((pre_import_dump_file IS NULL) = (pre_import_dump_at IS NULL)
           AND (pre_import_dump_file IS NULL) = (pre_import_dump_sha256 IS NULL)),
    CHECK (allow_trading_tenant = (allow_trading_set_by IS NOT NULL)),
    CHECK (NOT allow_trading_tenant OR btrim(coalesce(allow_trading_reason, '')) <> ''),
    CHECK (allow_trading_approved_by IS NULL OR allow_trading_tenant),
    -- The checker is never the maker (AGENTS.md rule 4).
    CHECK (allow_trading_approved_by IS NULL OR allow_trading_approved_by <> allow_trading_set_by)
);
-- One committing run per business (spec section 5.4).
CREATE UNIQUE INDEX import_runs_one_committing ON import_runs (tenant_id) WHERE status = 'committing';
CREATE INDEX import_runs_by_status ON import_runs (tenant_id, status);
SELECT bms_apply_tenant_rls('import_runs');
SELECT bms_grant_app('import_runs', 'SELECT, INSERT, UPDATE');

CREATE TABLE import_batches (
    id           uuid         NOT NULL PRIMARY KEY,
    tenant_id    uuid         NOT NULL REFERENCES tenants (id),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    run_id       uuid         NOT NULL,
    -- Commit order within the run (dependency order, spec section 3.7).
    seq          integer      NOT NULL CHECK (seq > 0),
    status       text         NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'committed')),
    counts       jsonb        NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(counts) = 'object'),
    committed_at timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (run_id, seq),
    CHECK ((status = 'committed') = (committed_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_batches');
SELECT bms_grant_app('import_batches', 'SELECT, INSERT, UPDATE');
