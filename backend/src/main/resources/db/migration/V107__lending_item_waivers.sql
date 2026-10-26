-- V107: fee and interest waivers on a loan's schedule (FR-ARR-04, second half; issue #265), directly after V106 (#596).
-- Additive only.
--
-- 1. `lending.charges.waive_fee_interest`: the accountant's permission. A fee or interest waiver is the `charge_waiver`
--    approval of V82, and its checker needs this permission besides `lending.charges.waive_approve`; a maker without it
--    always waits for such a checker, whatever the threshold. Held by the accountant and the tenant admin.
-- 2. `lending_item_waivers`: one row per approved waiver of all or part of an item's fees or interest, with the business
--    date it took effect, so the statement dates it (ADR-034) and a reversal's replay keeps it (ADR-026 Watch for). The
--    item's `fees_waived_minor` and `interest_waived_minor` carry it; nothing is posted, because fees and interest on the
--    schedule are income only when paid (ADR-004). Principal is never waived (that is a write-off). Append-only; the maker
--    and the checker are staff of the business (composite foreign keys to users); a waiver under the threshold has no
--    checker.

SET LOCAL lock_timeout = '5s';

-- The key a waiver row names its item by, so the item is always the loan's own (the review of #597).
CREATE UNIQUE INDEX lending_schedule_items_tenant_loan_id ON lending_schedule_items (tenant_id, loan_id, id);

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.charges.waive_fee_interest', 'lending', 'Charges: approve a fee or interest waiver (the accountant)', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.charges.waive_fee_interest'),
    ('accountant', 'lending.charges.waive_fee_interest')
ON CONFLICT DO NOTHING;

CREATE TABLE lending_item_waivers (
    id                  uuid        NOT NULL PRIMARY KEY,
    tenant_id           uuid        NOT NULL REFERENCES tenants (id),
    created_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id             uuid        NOT NULL,
    schedule_item_id    uuid        NOT NULL,
    component           text        NOT NULL CHECK (component IN ('fee', 'interest')),
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    business_date       date        NOT NULL,
    reason              text        NOT NULL CHECK (length(btrim(reason)) > 0),
    approval_request_id uuid,
    requested_by        uuid        NOT NULL,
    approved_by         uuid,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id, schedule_item_id) REFERENCES lending_schedule_items (tenant_id, loan_id, id),
    FOREIGN KEY (tenant_id, approval_request_id) REFERENCES approval_requests (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, approved_by) REFERENCES users (tenant_id, id)
);
CREATE INDEX lending_item_waivers_loan ON lending_item_waivers (tenant_id, loan_id, business_date);
CREATE INDEX lending_item_waivers_item ON lending_item_waivers (tenant_id, schedule_item_id);
SELECT bms_apply_tenant_rls('lending_item_waivers');
SELECT bms_grant_app('lending_item_waivers', 'SELECT, INSERT');
SELECT bms_make_append_only('lending_item_waivers');
