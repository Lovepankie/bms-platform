-- V82: lending increment 6 T2 (#265, part of #109): the waivers of penalty charges, directly after
-- V81 (the charges of T1, #264). Additive only.

-- ---------------------------------------------------------------------------------------------
-- lending_charge_waivers (chapter 6 section 6.7; FR-ARR-04). One row per approved waiver of all or
-- part of a charge, with the business date it took effect, so the statement can date it (ADR-034)
-- and a reversal's replay keeps it (ADR-026). The charge's waived_minor is the sum of its rows.
-- Append-only; nothing is posted (the charge itself never was, ADR-004). The maker and the checker
-- are staff of the business (composite foreign keys to users); a waiver under the threshold has no
-- checker.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_charge_waivers (
    id                  uuid        NOT NULL PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
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
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, approved_by) REFERENCES users (tenant_id, id)
);
CREATE INDEX lending_charge_waivers_loan ON lending_charge_waivers (tenant_id, loan_id, business_date);
CREATE INDEX lending_charge_waivers_charge ON lending_charge_waivers (tenant_id, charge_id);
SELECT bms_apply_tenant_rls('lending_charge_waivers');
SELECT bms_grant_app('lending_charge_waivers', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_charge_waivers');
