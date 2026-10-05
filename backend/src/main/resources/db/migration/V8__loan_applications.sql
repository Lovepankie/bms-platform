-- V8: loans from application onwards, status history, guarantors and collateral pledges
-- (MVP increment 4, issue #41).
--
-- Additive only (chapter 6 section 6.9). lending_loans carries every column of chapter 6 section
-- 6.7 now, so later increments (appraisal, approval, disbursement, repayments) add behaviour, not
-- columns. The approver CHECK of FR-APR-03 is in force from the start.

CREATE TABLE lending_loans (
    id                          uuid         NOT NULL PRIMARY KEY,
    tenant_id                   uuid         NOT NULL REFERENCES tenants (id),
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz,
    version                     integer      NOT NULL DEFAULT 1,
    branch_id                   uuid         NOT NULL,
    loan_no                     varchar(20)  NOT NULL,
    member_id                   uuid         NOT NULL,
    product_version_id          uuid         NOT NULL,
    officer_user_id             uuid         NOT NULL,
    status                      text         NOT NULL
                                             CHECK (status IN ('draft', 'submitted', 'appraised', 'approved', 'rejected',
                                                               'cancelled', 'active', 'closed', 'written_off', 'restructured')),
    channel                     text         NOT NULL CHECK (channel IN ('staff', 'portal', 'import')),
    purpose_category            text         NOT NULL
                                             CHECK (purpose_category IN ('business', 'school_fees', 'medical', 'agriculture',
                                                                         'household', 'construction', 'other')),
    purpose_text                varchar(300),
    currency                    char(3)      NOT NULL REFERENCES currencies (code),
    requested_principal_minor   bigint       NOT NULL CHECK (requested_principal_minor > 0),
    requested_term_count        integer      NOT NULL CHECK (requested_term_count > 0),
    approved_principal_minor    bigint,
    approved_term_count         integer,
    term_unit                   text         NOT NULL CHECK (term_unit IN ('day', 'week', 'month')),
    interest_method             text         NOT NULL CHECK (interest_method IN ('flat', 'declining')),
    interest_rate_bp            integer      NOT NULL CHECK (interest_rate_bp BETWEEN 0 AND 100000),
    rate_unit                   text         NOT NULL
                                             CHECK (rate_unit IN ('per_term', 'per_day', 'per_week', 'per_month', 'per_year')),
    repayment_pattern           text         NOT NULL CHECK (repayment_pattern IN ('bullet', 'instalments')),
    instalment_frequency        text         CHECK (instalment_frequency IN ('daily', 'weekly', 'fortnightly', 'monthly')),
    proposed_disbursement_date  date,
    submitted_by                uuid,
    submitted_at                timestamptz,
    appraised_by                uuid,
    approved_by                 uuid,
    approved_at                 timestamptz,
    rejected_reason             text,
    cancelled_reason            text,
    disbursed_on                date,
    maturity_date               date,
    closed_on                   date,
    written_off_on              date,
    restructured_from_loan_id   uuid,
    restructured_to_loan_id     uuid,
    principal_disbursed_minor   bigint       NOT NULL DEFAULT 0,
    principal_outstanding_minor bigint       NOT NULL DEFAULT 0,
    interest_outstanding_minor  bigint       NOT NULL DEFAULT 0,
    fees_outstanding_minor      bigint       NOT NULL DEFAULT 0,
    penalties_outstanding_minor bigint       NOT NULL DEFAULT 0,
    arrears_minor               bigint       NOT NULL DEFAULT 0,
    days_past_due               integer      NOT NULL DEFAULT 0,
    next_due_date               date,
    last_repayment_on           date,
    total_paid_minor            bigint       NOT NULL DEFAULT 0,
    credit_balance_minor        bigint       NOT NULL DEFAULT 0,
    import_row_id               uuid,
    created_by                  uuid,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, loan_no),
    FOREIGN KEY (tenant_id, branch_id) REFERENCES branches (tenant_id, id),
    FOREIGN KEY (tenant_id, member_id) REFERENCES lending_members (tenant_id, id),
    FOREIGN KEY (tenant_id, product_version_id) REFERENCES lending_loan_product_versions (tenant_id, id),
    FOREIGN KEY (tenant_id, restructured_from_loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, restructured_to_loan_id) REFERENCES lending_loans (tenant_id, id),
    CHECK (approved_principal_minor IS NULL OR approved_principal_minor <= requested_principal_minor),
    CHECK ((repayment_pattern = 'instalments') = (instalment_frequency IS NOT NULL)),
    CONSTRAINT lending_loans_approver_is_not_submitter_or_appraiser CHECK (
        approved_by IS NULL
        OR (approved_by <> submitted_by
            AND approved_by <> coalesce(appraised_by, '00000000-0000-0000-0000-000000000000'::uuid)))
);
CREATE INDEX lending_loans_member ON lending_loans (tenant_id, member_id);
CREATE INDEX lending_loans_branch_status ON lending_loans (tenant_id, branch_id, status);
CREATE INDEX lending_loans_officer_status ON lending_loans (tenant_id, officer_user_id, status);
CREATE INDEX lending_loans_due ON lending_loans (tenant_id, status, next_due_date);
CREATE INDEX lending_loans_arrears ON lending_loans (tenant_id, status, days_past_due) WHERE status = 'active';
SELECT bms_apply_tenant_rls('lending_loans');
SELECT bms_grant_app('lending_loans', 'SELECT, INSERT, UPDATE');

CREATE TABLE lending_loan_status_history (
    id          uuid        NOT NULL PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenants (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    loan_id     uuid        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    changed_by  uuid,
    reason      text,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id)
);
CREATE INDEX lending_loan_status_history_loan ON lending_loan_status_history (tenant_id, loan_id, created_at);
SELECT bms_apply_tenant_rls('lending_loan_status_history');
SELECT bms_grant_app('lending_loan_status_history', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_loan_status_history');

CREATE TABLE lending_loan_guarantors (
    id                      uuid        NOT NULL PRIMARY KEY,
    tenant_id               uuid        NOT NULL REFERENCES tenants (id),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz,
    version                 integer     NOT NULL DEFAULT 1,
    loan_id                 uuid        NOT NULL,
    guarantor_member_id     uuid        NOT NULL,
    guaranteed_amount_minor bigint      NOT NULL CHECK (guaranteed_amount_minor > 0),
    relationship            text,
    status                  text        NOT NULL CHECK (status IN ('active', 'released')),
    UNIQUE (tenant_id, id),
    UNIQUE (loan_id, guarantor_member_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, guarantor_member_id) REFERENCES lending_members (tenant_id, id)
);
CREATE INDEX lending_loan_guarantors_member ON lending_loan_guarantors (tenant_id, guarantor_member_id);
SELECT bms_apply_tenant_rls('lending_loan_guarantors');
-- DELETE: a draft's guarantor list is replaced as a whole (PUT .../guarantors).
SELECT bms_grant_app('lending_loan_guarantors', 'SELECT, INSERT, UPDATE, DELETE');

CREATE TABLE lending_loan_collateral (
    id                  uuid        NOT NULL PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz,
    version             integer     NOT NULL DEFAULT 1,
    loan_id             uuid        NOT NULL,
    collateral_id       uuid        NOT NULL,
    pledged_value_minor bigint      NOT NULL CHECK (pledged_value_minor > 0),
    released_at         timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, loan_id, collateral_id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, collateral_id) REFERENCES lending_collateral_items (tenant_id, id)
);
-- One open loan per item (FR-COL-01, ADR-019): the service locks the item and checks first; this
-- index is the backstop. A pledge is released (released_at set) when its loan is cancelled,
-- rejected or closed; a written-off loan keeps its collateral for recovery.
CREATE UNIQUE INDEX lending_loan_collateral_one_open_pledge ON lending_loan_collateral (tenant_id, collateral_id)
    WHERE released_at IS NULL;
CREATE INDEX lending_loan_collateral_item ON lending_loan_collateral (tenant_id, collateral_id);
SELECT bms_apply_tenant_rls('lending_loan_collateral');
-- DELETE: a draft's pledge list is replaced as a whole (PUT .../collateral).
SELECT bms_grant_app('lending_loan_collateral', 'SELECT, INSERT, UPDATE, DELETE');

-- ---------------------------------------------------------------------------------------------
-- Guarantors and pledges change only while the loan is a draft (FR-ORG-03 freezes them at submit).
-- The service checks this; these triggers hold it for every writer. The one change allowed later
-- is the release: a pledge's released_at, or a guarantor's status, with the rest of the row intact.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION lending_loan_require_draft(p_loan_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_status text;
BEGIN
    SELECT status INTO v_status FROM lending_loans WHERE id = p_loan_id;
    IF v_status IS DISTINCT FROM 'draft' THEN
        RAISE EXCEPTION 'guarantors and pledges change only on a draft loan (loan is %)', coalesce(v_status, 'missing')
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION lending_loan_collateral_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lending_loan_require_draft(OLD.loan_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.loan_id = OLD.loan_id AND NEW.collateral_id = OLD.collateral_id
            AND NEW.pledged_value_minor = OLD.pledged_value_minor THEN
        RETURN NEW;
    END IF;
    PERFORM lending_loan_require_draft(NEW.loan_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_loan_collateral_guard BEFORE INSERT OR UPDATE OR DELETE ON lending_loan_collateral
    FOR EACH ROW EXECUTE FUNCTION lending_loan_collateral_guard();

CREATE FUNCTION lending_loan_guarantors_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lending_loan_require_draft(OLD.loan_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.loan_id = OLD.loan_id AND NEW.guarantor_member_id = OLD.guarantor_member_id
            AND NEW.guaranteed_amount_minor = OLD.guaranteed_amount_minor THEN
        RETURN NEW;
    END IF;
    PERFORM lending_loan_require_draft(NEW.loan_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER lending_loan_guarantors_guard BEFORE INSERT OR UPDATE OR DELETE ON lending_loan_guarantors
    FOR EACH ROW EXECUTE FUNCTION lending_loan_guarantors_guard();
