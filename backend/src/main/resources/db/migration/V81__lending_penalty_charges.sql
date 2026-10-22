-- V81: lending increment 6 T1 (#264, part of #109): the penalty charges of the nightly arrears run and
-- the run's own record. The next free number after V80 (the in-app feedback of #447, on main); the
-- waivers of T2 (#265) take V82, the collection actions of T3 (#266) V83 and the arrears notices of #227
-- V84, each in its own pull request. Additive only.

-- ---------------------------------------------------------------------------------------------
-- lending_loan_charges (chapter 6 section 6.7; R-PEN, FR-ARR-01, FR-ARR-04). One row per penalty
-- period of a schedule item (charge_type penalty), keyed by (item, period) so the nightly job never
-- charges a period twice; the item's penalties due are the sum of its rows less what was cancelled,
-- and the documents date each penalty by its row (ADR-034). Penalties are memorandum items: nothing
-- is posted when a charge is made (ADR-004 cash basis). Two amounts change, each only upwards:
-- waived_minor (a waiver, T2) and cancelled_minor (a back-dated repayment showed the period was not
-- owed, or owed less on a smaller base; each cancel is a dated row of lending_charge_cancels). When a
-- reversal shows a cancelled amount was owed after all, it is charged again as a new row
-- (charge_type penalty_recharge, recharge_of the period's charge), never by undoing the cancel.
-- created_at is the clock time of the insert, or the time of the transaction that caused it, so a
-- receipt places a charge exactly among the loan's transactions.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_charges (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id          uuid        NOT NULL,
    schedule_item_id uuid        NOT NULL,
    charge_type      text        NOT NULL CHECK (charge_type IN ('penalty', 'penalty_recharge')),
    period_no        integer     NOT NULL CHECK (period_no >= 1),
    recharge_of      uuid,
    business_date    date        NOT NULL,
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    waived_minor     bigint      NOT NULL DEFAULT 0 CHECK (waived_minor >= 0),
    cancelled_minor  bigint      NOT NULL DEFAULT 0 CHECK (cancelled_minor >= 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, id),
    FOREIGN KEY (tenant_id, recharge_of) REFERENCES lending_loan_charges (tenant_id, id),
    CHECK (waived_minor + cancelled_minor <= amount_minor),
    CHECK ((charge_type = 'penalty_recharge') = (recharge_of IS NOT NULL))
);
CREATE UNIQUE INDEX lending_loan_charges_one_per_period
    ON lending_loan_charges (tenant_id, schedule_item_id, period_no) WHERE charge_type = 'penalty';
CREATE INDEX lending_loan_charges_loan ON lending_loan_charges (tenant_id, loan_id, business_date);
SELECT bms_apply_tenant_rls('lending_loan_charges');
SELECT bms_grant_app('lending_loan_charges', 'SELECT, INSERT');
GRANT UPDATE (waived_minor, cancelled_minor) ON lending_loan_charges TO bms_app;

-- Like reject_mutation() (section 6.2.4) for every role, bms_owner included, except raising the two
-- amounts; the column grant alone would not bind the owner.
CREATE FUNCTION lending_loan_charges_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'waived_minor' - 'cancelled_minor') = (to_jsonb(OLD) - 'waived_minor' - 'cancelled_minor')
       AND NEW.waived_minor >= OLD.waived_minor AND NEW.cancelled_minor >= OLD.cancelled_minor THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '% on lending_loan_charges may only raise waived_minor or cancelled_minor', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER lending_loan_charges_guard BEFORE UPDATE OR DELETE ON lending_loan_charges
    FOR EACH ROW EXECUTE FUNCTION lending_loan_charges_guard();

-- ---------------------------------------------------------------------------------------------
-- lending_charge_cancels (FR-ARR-01, ADR-034 addendum). One row per amount cancelled off a charge,
-- with the business date and the repayment or reversal that caused it (cause_txn_id): the whole
-- charge when a back-dated repayment shows the item was paid up before the period's date
-- (paid_before_charged), the excess when it shows a percent penalty's base was smaller on that date
-- (base_reduced). The charge's cancelled_minor is the sum of its rows. Append-only; nothing posted.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_charge_cancels (
    id            uuid        NOT NULL PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    created_at    timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id       uuid        NOT NULL,
    charge_id     uuid        NOT NULL,
    amount_minor  bigint      NOT NULL CHECK (amount_minor > 0),
    business_date date        NOT NULL,
    reason        text        NOT NULL CHECK (reason IN ('paid_before_charged', 'base_reduced')),
    cause_txn_id  uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, charge_id) REFERENCES lending_loan_charges (tenant_id, id),
    FOREIGN KEY (tenant_id, cause_txn_id) REFERENCES lending_loan_transactions (tenant_id, id)
);
CREATE INDEX lending_charge_cancels_loan ON lending_charge_cancels (tenant_id, loan_id, business_date);
SELECT bms_apply_tenant_rls('lending_charge_cancels');
SELECT bms_grant_app('lending_charge_cancels', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_charge_cancels');

-- ---------------------------------------------------------------------------------------------
-- lending_arrears_runs (FR-ARR-01, ADR-008). One row per tenant and business date: the claim of the
-- nightly run, held by a time-bounded lease (lease_until) that the run renews as it goes, so a run
-- that crashed is taken over once its lease has lapsed, and a finished run (finished_at) is never
-- run again for that date. The first row's business date is where penalties start for the tenant:
-- a period dated before it is never charged, so switching the run on (or a product's rule on a long
-- overdue loan) does not charge every period since the due date in one night. The counts are those
-- of the run that finished; failed_loan_ids lets the next night alert when a loan fails twice running.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_arrears_runs (
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    business_date  date        NOT NULL,
    started_at     timestamptz NOT NULL,
    lease_until    timestamptz NOT NULL,
    attempts       integer     NOT NULL DEFAULT 1 CHECK (attempts >= 1),
    finished_at    timestamptz,
    loans          integer     CHECK (loans >= 0),
    charges        integer     CHECK (charges >= 0),
    charged_minor  bigint      CHECK (charged_minor >= 0),
    failed_loans   integer     CHECK (failed_loans >= 0),
    failed_loan_ids uuid[]     NOT NULL DEFAULT '{}',
    PRIMARY KEY (tenant_id, business_date)
);
SELECT bms_apply_tenant_rls('lending_arrears_runs');
SELECT bms_grant_app('lending_arrears_runs', 'SELECT, INSERT, UPDATE');
