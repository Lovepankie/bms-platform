-- V7: loan products, their immutable versions and fees (MVP increment 4, issue #40).
--
-- Additive only (chapter 6 section 6.9). A product's terms live in versions: editing a product
-- inserts a new version and loans keep the version they were created with (FR-PRD-04), so versions
-- and fees are insert-only for bms_app.

-- ---------------------------------------------------------------------------------------------
-- lending_loan_products (chapter 6 section 6.7; FR-PRD-01, FR-PRD-05)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_products (
    id                 uuid         NOT NULL PRIMARY KEY,
    tenant_id          uuid         NOT NULL REFERENCES tenants (id),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz,
    version            integer      NOT NULL DEFAULT 1,
    code               varchar(20)  NOT NULL,
    name               varchar(100) NOT NULL,
    status             text         NOT NULL CHECK (status IN ('active', 'archived')),
    current_version_id uuid,
    created_by         uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);
SELECT bms_apply_tenant_rls('lending_loan_products');
SELECT bms_grant_app('lending_loan_products', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- lending_loan_product_versions (FR-PRD-04). The CHECKs mirror rules R-TERM, R-RATE and R-PEN of
-- chapter 3 section 3.4 as far as a row can express them; the service enforces the rest.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_product_versions (
    id                           uuid        NOT NULL PRIMARY KEY,
    tenant_id                    uuid        NOT NULL REFERENCES tenants (id),
    created_at                   timestamptz NOT NULL DEFAULT now(),
    product_id                   uuid        NOT NULL,
    version_no                   integer     NOT NULL CHECK (version_no >= 1),
    currency                     char(3)     NOT NULL REFERENCES currencies (code),
    interest_method              text        NOT NULL CHECK (interest_method IN ('flat', 'declining')),
    interest_rate_bp             integer     NOT NULL CHECK (interest_rate_bp BETWEEN 0 AND 100000),
    rate_unit                    text        NOT NULL
                                             CHECK (rate_unit IN ('per_term', 'per_day', 'per_week', 'per_month', 'per_year')),
    term_unit                    text        NOT NULL CHECK (term_unit IN ('day', 'week', 'month')),
    min_term_count               integer     NOT NULL,
    max_term_count               integer     NOT NULL,
    default_term_count           integer     NOT NULL,
    repayment_pattern            text        NOT NULL CHECK (repayment_pattern IN ('bullet', 'instalments')),
    instalment_frequency         text        CHECK (instalment_frequency IN ('daily', 'weekly', 'fortnightly', 'monthly')),
    min_principal_minor          bigint      NOT NULL CHECK (min_principal_minor > 0),
    max_principal_minor          bigint      NOT NULL,
    allocation_order             text[]      NOT NULL DEFAULT '{penalty,fee,interest,principal}',
    penalty_method               text        NOT NULL DEFAULT 'none'
                                             CHECK (penalty_method IN ('none', 'flat_per_period', 'percent_of_overdue_per_period')),
    penalty_grace_days           integer     NOT NULL DEFAULT 0 CHECK (penalty_grace_days >= 0),
    penalty_period_unit          text        CHECK (penalty_period_unit IN ('day', 'week', 'month')),
    penalty_flat_minor           bigint      CHECK (penalty_flat_minor > 0),
    penalty_rate_bp              integer     CHECK (penalty_rate_bp > 0),
    penalty_cap_bp               integer     CHECK (penalty_cap_bp > 0),
    flat_early_settlement_rebate boolean     NOT NULL DEFAULT false,
    requires_collateral          boolean     NOT NULL DEFAULT false,
    min_collateral_cover_bp      integer     CHECK (min_collateral_cover_bp > 0),
    requires_guarantor           boolean     NOT NULL DEFAULT false,
    created_by                   uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    -- The target of the product's current-version key, so a product can only point at its own version.
    UNIQUE (tenant_id, product_id, id),
    UNIQUE (product_id, version_no),
    FOREIGN KEY (tenant_id, product_id) REFERENCES lending_loan_products (tenant_id, id),
    CHECK (1 <= min_term_count AND min_term_count <= default_term_count AND default_term_count <= max_term_count),
    CHECK (min_principal_minor <= max_principal_minor),
    CHECK ((repayment_pattern = 'instalments') = (instalment_frequency IS NOT NULL)),
    CHECK (allocation_order @> ARRAY['penalty', 'fee', 'interest', 'principal']
           AND cardinality(allocation_order) = 4),
    -- FR-ORG-07 compares the pledged cover with this at approval, so a secured product must state it.
    CHECK (NOT requires_collateral OR min_collateral_cover_bp IS NOT NULL),
    CHECK ((penalty_method = 'none') = (penalty_period_unit IS NULL)),
    CHECK ((penalty_method = 'flat_per_period') = (penalty_flat_minor IS NOT NULL)),
    CHECK ((penalty_method = 'percent_of_overdue_per_period') = (penalty_rate_bp IS NOT NULL))
);
CREATE INDEX lending_loan_product_versions_product ON lending_loan_product_versions (tenant_id, product_id, version_no);
SELECT bms_apply_tenant_rls('lending_loan_product_versions');
SELECT bms_grant_app('lending_loan_product_versions', 'SELECT, INSERT');

ALTER TABLE lending_loan_products
    ADD FOREIGN KEY (tenant_id, id, current_version_id)
        REFERENCES lending_loan_product_versions (tenant_id, product_id, id);

-- ---------------------------------------------------------------------------------------------
-- lending_loan_product_fees (FR-PRD-02)
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_product_fees (
    id                 uuid        NOT NULL PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenants (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz,
    product_version_id uuid        NOT NULL,
    name               varchar(60) NOT NULL,
    fee_type           text        NOT NULL CHECK (fee_type IN ('application', 'processing', 'insurance', 'other')),
    calc_method        text        NOT NULL CHECK (calc_method IN ('flat', 'percent_of_principal')),
    amount_minor       bigint      CHECK (amount_minor > 0),
    rate_bp            integer     CHECK (rate_bp BETWEEN 1 AND 10000),
    timing             text        NOT NULL CHECK (timing IN ('deducted_at_disbursement', 'added_to_loan', 'paid_upfront')),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES lending_loan_product_versions (tenant_id, id),
    CHECK ((calc_method = 'flat' AND amount_minor IS NOT NULL AND rate_bp IS NULL)
        OR (calc_method = 'percent_of_principal' AND rate_bp IS NOT NULL AND amount_minor IS NULL))
);
CREATE INDEX lending_loan_product_fees_version ON lending_loan_product_fees (tenant_id, product_version_id);
SELECT bms_apply_tenant_rls('lending_loan_product_fees');
SELECT bms_grant_app('lending_loan_product_fees', 'SELECT, INSERT');
