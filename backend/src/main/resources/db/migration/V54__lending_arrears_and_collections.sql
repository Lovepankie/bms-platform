-- V54: lending increment 6 (#109): penalty charges (T1, #264), their waivers (T2, #265) and
-- collection actions (T3, #266). V54 is claimed for lending on issue #50. Additive only.

-- ---------------------------------------------------------------------------------------------
-- lending_loan_charges (chapter 6 section 6.7; R-PEN, FR-ARR-01, FR-ARR-04). One row per penalty
-- period of a schedule item, keyed by (item, period) so the nightly job never charges twice; the
-- item's penalties due are the sum of its rows, and the documents date each penalty by its row
-- (ADR-034). Penalties are memorandum items: nothing is posted when a charge is made (ADR-004 cash
-- basis). waived_minor is the only column that changes, and only upwards (a waiver, T2).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_loan_charges (
    id               uuid        NOT NULL PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    loan_id          uuid        NOT NULL,
    schedule_item_id uuid        NOT NULL,
    charge_type      text        NOT NULL CHECK (charge_type IN ('penalty', 'fee')),
    fee_id           uuid,
    period_no        integer     CHECK (period_no >= 1),
    business_date    date        NOT NULL,
    amount_minor     bigint      NOT NULL CHECK (amount_minor > 0),
    waived_minor     bigint      NOT NULL DEFAULT 0 CHECK (waived_minor >= 0),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, id),
    CHECK (waived_minor <= amount_minor),
    CHECK ((charge_type = 'penalty') = (period_no IS NOT NULL))
);
CREATE UNIQUE INDEX lending_loan_charges_one_per_period
    ON lending_loan_charges (tenant_id, schedule_item_id, period_no) WHERE charge_type = 'penalty';
CREATE INDEX lending_loan_charges_loan ON lending_loan_charges (tenant_id, loan_id, business_date);
SELECT bms_apply_tenant_rls('lending_loan_charges');
SELECT bms_grant_app('lending_loan_charges', 'SELECT, INSERT');
GRANT UPDATE (waived_minor) ON lending_loan_charges TO bms_app;

-- Like reject_mutation() (section 6.2.4) for every role, bms_owner included, except the one change
-- the table allows; the column grant alone would not bind the owner.
CREATE FUNCTION lending_loan_charges_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'waived_minor') = (to_jsonb(OLD) - 'waived_minor')
       AND NEW.waived_minor >= OLD.waived_minor THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '% on lending_loan_charges may only raise waived_minor', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER lending_loan_charges_guard BEFORE UPDATE OR DELETE ON lending_loan_charges
    FOR EACH ROW EXECUTE FUNCTION lending_loan_charges_guard();

-- ---------------------------------------------------------------------------------------------
-- lending_charge_waivers (chapter 6 section 6.7; FR-ARR-04). One row per approved waiver of all or
-- part of a charge, with the business date it took effect, so the statement can date it (ADR-034)
-- and a reversal's replay keeps it (ADR-026). The charge's waived_minor is the sum of its rows.
-- Append-only; nothing is posted (the charge itself never was, ADR-004).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_charge_waivers (
    id                  uuid        NOT NULL PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    created_at          timestamptz NOT NULL DEFAULT now(),
    loan_id             uuid        NOT NULL,
    charge_id           uuid        NOT NULL,
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    business_date       date        NOT NULL,
    reason              text        NOT NULL CHECK (length(btrim(reason)) > 0),
    approval_request_id uuid,
    requested_by        uuid        NOT NULL,
    approved_by         uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, charge_id) REFERENCES lending_loan_charges (tenant_id, id),
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id)
);
CREATE INDEX lending_charge_waivers_loan ON lending_charge_waivers (tenant_id, loan_id, business_date);
CREATE INDEX lending_charge_waivers_charge ON lending_charge_waivers (tenant_id, charge_id);
SELECT bms_apply_tenant_rls('lending_charge_waivers');
SELECT bms_grant_app('lending_charge_waivers', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_charge_waivers');

-- ---------------------------------------------------------------------------------------------
-- lending_collection_actions (chapter 6 section 6.7; FR-CLN-04, FR-CLN-05). Append-only except
-- the promise's status, which the nightly job moves from pending to kept or broken.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collection_actions (
    id                    uuid        NOT NULL PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenants (id),
    created_at            timestamptz NOT NULL DEFAULT now(),
    loan_id               uuid        NOT NULL,
    action_type           text        NOT NULL
                                      CHECK (action_type IN ('call', 'visit', 'sms', 'letter', 'promise_to_pay', 'other')),
    note                  text,
    promised_date         date,
    promised_amount_minor bigint      CHECK (promised_amount_minor > 0),
    promise_status        text        CHECK (promise_status IN ('pending', 'kept', 'broken')),
    performed_by          uuid        NOT NULL,
    performed_at          timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    CHECK ((action_type = 'promise_to_pay')
           = (promised_date IS NOT NULL AND promised_amount_minor IS NOT NULL AND promise_status IS NOT NULL))
);
CREATE INDEX lending_collection_actions_loan ON lending_collection_actions (tenant_id, loan_id, performed_at);
CREATE INDEX lending_collection_actions_pending_promise
    ON lending_collection_actions (tenant_id, promised_date) WHERE promise_status = 'pending';
SELECT bms_apply_tenant_rls('lending_collection_actions');
SELECT bms_grant_app('lending_collection_actions', 'SELECT, INSERT');
GRANT UPDATE (promise_status) ON lending_collection_actions TO bms_app;

CREATE FUNCTION lending_collection_actions_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'promise_status') = (to_jsonb(OLD) - 'promise_status')
       AND OLD.promise_status = 'pending' AND NEW.promise_status IN ('kept', 'broken') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '% on lending_collection_actions may only settle a pending promise', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER lending_collection_actions_guard BEFORE UPDATE OR DELETE ON lending_collection_actions
    FOR EACH ROW EXECUTE FUNCTION lending_collection_actions_guard();
