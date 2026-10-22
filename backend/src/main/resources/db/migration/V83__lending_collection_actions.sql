-- V83: lending increment 6 T3 (#266, part of #109): collection actions and promises to pay, directly
-- after V82 (the waivers of T2, #265). Additive only.

-- ---------------------------------------------------------------------------------------------
-- lending_collection_actions (chapter 6 section 6.7; FR-CLN-04, FR-CLN-05). Append-only except a
-- promise's settlement: the nightly run moves a pending promise to kept or broken, and moves a
-- settled one again (back to pending too, before its date) when a later back-dated repayment or a
-- reversal changes what was paid in its window (promise_settled_on is the business date of the
-- latest settlement, null while pending). The person who acted
-- is staff of the business (a composite foreign key to users).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE lending_collection_actions (
    id                    uuid        NOT NULL PRIMARY KEY,
    tenant_id             uuid        NOT NULL REFERENCES tenants (id),
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    loan_id               uuid        NOT NULL,
    action_type           text        NOT NULL
                                      CHECK (action_type IN ('call', 'visit', 'sms', 'letter', 'promise_to_pay', 'other')),
    note                  text,
    promised_date         date,
    promised_amount_minor bigint      CHECK (promised_amount_minor > 0),
    promise_status        text        CHECK (promise_status IN ('pending', 'kept', 'broken')),
    promise_settled_on    date,
    performed_by          uuid        NOT NULL,
    performed_at          timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, loan_id) REFERENCES lending_loans (tenant_id, id),
    FOREIGN KEY (tenant_id, performed_by) REFERENCES users (tenant_id, id),
    CHECK ((action_type = 'promise_to_pay')
           = (promised_date IS NOT NULL AND promised_amount_minor IS NOT NULL AND promise_status IS NOT NULL)),
    CHECK ((promise_status = 'pending' OR promise_status IS NULL) = (promise_settled_on IS NULL))
);
CREATE INDEX lending_collection_actions_loan ON lending_collection_actions (tenant_id, loan_id, performed_at);
CREATE INDEX lending_collection_actions_open_promise
    ON lending_collection_actions (tenant_id, promised_date) WHERE action_type = 'promise_to_pay';
SELECT bms_apply_tenant_rls('lending_collection_actions');
SELECT bms_grant_app('lending_collection_actions', 'SELECT, INSERT');
GRANT UPDATE (promise_status, promise_settled_on) ON lending_collection_actions TO bms_app;

CREATE FUNCTION lending_collection_actions_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'UPDATE'
       AND (to_jsonb(NEW) - 'promise_status' - 'promise_settled_on')
           = (to_jsonb(OLD) - 'promise_status' - 'promise_settled_on')
       AND OLD.action_type = 'promise_to_pay' AND NEW.promise_status IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION '% on lending_collection_actions may only settle a promise', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;
CREATE TRIGGER lending_collection_actions_guard BEFORE UPDATE OR DELETE ON lending_collection_actions
    FOR EACH ROW EXECUTE FUNCTION lending_collection_actions_guard();

-- ---------------------------------------------------------------------------------------------
-- Permissions (chapter 8 section 8.3.2, ADR-064, the review of #272). Both are reads, not money
-- moving, scoped by branch; PermissionMatrixIT compares the seeded rows with the matrix.
-- lending.members.contact: see a borrower's whole phone number on the collection lists; the people
-- who call borrowers hold it (administrators, branch managers, loan officers), not the cashier, the
-- accountant or the auditor, who see it masked.
-- lending.collections.all_officers: see every officer's loans on the collection lists; without it
-- (the loan officer) a list covers only the caller's own portfolio.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.members.contact', 'lending', 'Members: see a borrower''s phone number on the collection lists', false),
    ('lending.collections.all_officers', 'lending', 'Collections: every officer''s loans in scope, not only one''s own', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.members.contact'),
    ('branch_manager', 'lending.members.contact'),
    ('loan_officer', 'lending.members.contact'),
    ('tenant_admin', 'lending.collections.all_officers'),
    ('branch_manager', 'lending.collections.all_officers'),
    ('cashier', 'lending.collections.all_officers'),
    ('accountant', 'lending.collections.all_officers'),
    ('auditor', 'lending.collections.all_officers')
ON CONFLICT DO NOTHING;
