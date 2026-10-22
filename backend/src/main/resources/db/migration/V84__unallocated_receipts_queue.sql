-- V84: the unallocated receipts queue (extras slice A5/7, first part, issue #224; ADR-035 and its amendment of 2026-10-19 for
-- slice A5; docs/specs/client-extras.md sections 3.7, 3.8 and 3.16; FR-PAY-04). Flyway runs with outOfOrder off: V79 is the
-- last on main, the lending pull requests #270, #271, #272 and #290 take V80 to V83, and this one takes V84, the number after them.
--
-- Expand only, safe on a populated database. Six things and nothing else (4 to 6 belong to the governed reversal of a gateway
-- payment, the same slice; the nightly reconciliation of the second part of #224 takes V85):
--
--   1. `unallocated_receipts` stops being append-only: a receipt that is `open` may end exactly once, as `allocated` (the money
--      became a loan repayment or a savings deposit through the servicing ports) or as `refunded` (the money went back out),
--      both through a maker-checker request. New columns say how: who asked and who decided, which approval, the transaction the
--      allocation created, the journal entry that cleared the liability, and how a refund was paid. `version` rises with every
--      change, so a second request on the same receipt is stale (FR-APR-08) the moment the first is executed. Every new column
--      is nullable or has a default, so every existing row (all `open`) satisfies the new checks.
--   2. A guard trigger: an open receipt may move to `allocated`, `refunded` or `reversed` only, with its version one higher; a receipt that is
--      no longer open never changes; and what the payment was (who, how much, which order, the journal entry that booked it) never
--      changes. The application role gets UPDATE on the settlement columns only, never on those identity columns, and no DELETE.
--      A unique index on the transaction an allocation created, besides the existing one-booking-per-intent index on
--      `payment_intents`, makes a second allocation of the same receipt impossible even if a bug skipped the row lock.
--   3. The permission `core.payments.allocate_approve`: the checker of an allocation or a refund. `core.payments.allocate` (V2)
--      is the maker; the two are separate permissions so a cashier can ask and an accountant, a branch manager or the
--      administrator decide, and the approval mechanism already refuses a checker who is the maker.
--
--   4. `users.kind` accepts `system`: the tenant's non-login payments user, the maker of the reversal requests the platform raises
--      when the provider reports a booked payment reversed (ADR-035 decision 16). It has no credentials, no role and no session; every
--      staff query filters on kind = 'staff', so it is listed nowhere and can sign in nowhere. One per tenant (unique index).
--   5. A receipt can also end `reversed`: the provider reported the payment reversed while its money still sat in the queue, and a
--      checker approved clearing it. Same guard, same exactly-once rules.
--   6. The state machine of `payment_intents` (the guard function is replaced, V54 had it) gains two moves, `reversed_pending_review`
--      to `booked` and to `booked_unallocated`: a payment the provider reported reversed and then reports completed again goes back
--      to what it was, since nothing in the books was reversed. And one is narrowed: `needs_attention` to `reversed` is refused for
--      an order that carries a booked transaction (an allocated receipt stores it too), because that order's money stands in the
--      subledger and only the approved reversal may take it out. Nothing else in the function changes.
--
-- Locks, said plainly. The new columns have no volatile default, so no row is rewritten. Two foreign keys point at
-- `approval_requests` and `journal_entries`: each takes SHARE ROW EXCLUSIVE on its target until the commit, which blocks
-- writes (not reads) to it for the few milliseconds this file takes, and the table being altered holds only the receipts of the
-- first lenders, so the validation scan is trivial. `lock_timeout` makes the migration fail and be retried rather than queue every
-- writer behind it. Flyway runs the file in one transaction, so adding the keys NOT VALID and validating later would gain nothing.
-- The swap of the `users` kind check takes ACCESS EXCLUSIVE on `users` to the commit and scans it once to validate the superset (every
-- row is `staff` or `member`); users is small (a lender's staff and members) and the same `lock_timeout` applies.
--
-- PRIVACY: nothing here holds the payer's phone number or a provider body; the refund's reference is a bank or wallet reference
-- of the lender's own, typed by staff, capped at 100 printable characters.

SET LOCAL lock_timeout = '5s';

ALTER TABLE unallocated_receipts
    ADD COLUMN version               integer      NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD COLUMN updated_at            timestamptz  NOT NULL DEFAULT now(),
    -- Who asked (the maker), who decided (the checker), which approval request, and when. The two user ids are not keys, as
    -- on approval_requests itself: the approval request they came from is.
    ADD COLUMN requested_by          uuid,
    ADD COLUMN decided_by            uuid,
    ADD COLUMN approval_id           uuid,
    ADD COLUMN decided_at            timestamptz,
    -- The maker's reason, shown to the checker and kept.
    ADD COLUMN note                  varchar(300),
    -- Allocated: the purpose (the registered handler), the subject and the transaction the servicing port created.
    ADD COLUMN allocated_purpose     text         CHECK (allocated_purpose ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    ADD COLUMN allocated_subject_type text        CHECK (allocated_subject_type ~ '^[a-z][a-z0-9_]{1,39}$'),
    ADD COLUMN allocated_subject_id  uuid,
    ADD COLUMN allocated_txn_id      uuid,
    -- The journal entry that cleared the liability: for an allocation the reversal of the receipt's own entry (the money is now
    -- in the repayment's or deposit's own posting), for a refund through the provider the same reversal, for a refund paid out
    -- another way the entry that debits `unallocated_receipts` and credits that method's account.
    ADD COLUMN cleared_entry_id      uuid,
    -- Refunded: how the money went back (a payment method key) and the lender's own reference for it.
    ADD COLUMN refund_method_key     text         CHECK (refund_method_key IN ('gateway', 'cash', 'bank', 'mtn_momo', 'airtel_money')),
    ADD COLUMN refund_reference      varchar(100) CHECK (refund_reference ~ '^[\x20-\x7E]{1,100}$'),
    ADD CONSTRAINT unallocated_receipts_approval_fk
        FOREIGN KEY (tenant_id, approval_id) REFERENCES approval_requests (tenant_id, id),
    ADD CONSTRAINT unallocated_receipts_cleared_entry_fk
        FOREIGN KEY (tenant_id, cleared_entry_id) REFERENCES journal_entries (tenant_id, id),
    -- An open receipt has none of it; a settled one has all of its kind and none of the other kind.
    ADD CONSTRAINT unallocated_receipts_open_is_bare CHECK (status <> 'open' OR (
        requested_by IS NULL AND decided_by IS NULL AND approval_id IS NULL AND decided_at IS NULL
        AND allocated_txn_id IS NULL AND cleared_entry_id IS NULL AND refund_method_key IS NULL
        AND refund_reference IS NULL AND allocated_purpose IS NULL AND allocated_subject_id IS NULL)),
    ADD CONSTRAINT unallocated_receipts_allocated_complete CHECK (status <> 'allocated' OR (
        requested_by IS NOT NULL AND decided_by IS NOT NULL AND approval_id IS NOT NULL AND decided_at IS NOT NULL
        AND allocated_purpose IS NOT NULL AND allocated_subject_type IS NOT NULL AND allocated_subject_id IS NOT NULL
        AND allocated_txn_id IS NOT NULL AND cleared_entry_id IS NOT NULL
        AND refund_method_key IS NULL AND refund_reference IS NULL)),
    ADD CONSTRAINT unallocated_receipts_refunded_complete CHECK (status <> 'refunded' OR (
        requested_by IS NOT NULL AND decided_by IS NOT NULL AND approval_id IS NOT NULL AND decided_at IS NOT NULL
        AND refund_method_key IS NOT NULL AND refund_reference IS NOT NULL AND cleared_entry_id IS NOT NULL
        AND allocated_txn_id IS NULL AND allocated_purpose IS NULL AND allocated_subject_id IS NULL)),
    -- The maker never decides their own request: the same rule the approval mechanism enforces, kept as a backstop.
    ADD CONSTRAINT unallocated_receipts_checker_is_not_maker CHECK (requested_by IS DISTINCT FROM decided_by OR decided_by IS NULL);

-- `reversed` joins the endings. The status check is replaced by a superset (every existing row is `open`).
ALTER TABLE unallocated_receipts DROP CONSTRAINT unallocated_receipts_status_check;
ALTER TABLE unallocated_receipts ADD CONSTRAINT unallocated_receipts_status_check
    CHECK (status IN ('open', 'allocated', 'refunded', 'reversed'));
ALTER TABLE unallocated_receipts ADD CONSTRAINT unallocated_receipts_reversed_complete CHECK (status <> 'reversed' OR (
    requested_by IS NOT NULL AND decided_by IS NOT NULL AND approval_id IS NOT NULL AND decided_at IS NOT NULL
    AND cleared_entry_id IS NOT NULL AND allocated_txn_id IS NULL AND allocated_purpose IS NULL
    AND allocated_subject_id IS NULL AND refund_method_key IS NULL AND refund_reference IS NULL));

-- The tenant's payments system user (kind `system`): see the header. The check is replaced by a superset.
ALTER TABLE users DROP CONSTRAINT users_kind_check;
ALTER TABLE users ADD CONSTRAINT users_kind_check CHECK (kind IN ('staff', 'member', 'system'));
CREATE UNIQUE INDEX users_system_email ON users (tenant_id, lower(email)) WHERE kind = 'system';

-- One receipt per allocation transaction, however the code is called.
CREATE UNIQUE INDEX unallocated_receipts_allocated_txn
    ON unallocated_receipts (tenant_id, allocated_txn_id) WHERE allocated_txn_id IS NOT NULL;

-- The queue and its history, newest settled first, by branch.
CREATE INDEX unallocated_receipts_by_branch ON unallocated_receipts (tenant_id, branch_id, created_at, id);

-- Row-level security is already forced (V59). The application role may now update the settlement columns and nothing else;
-- in particular never the columns that say what the payment was.
GRANT UPDATE (status, version, updated_at, requested_by, decided_by, approval_id, decided_at, note, allocated_purpose,
              allocated_subject_type, allocated_subject_id, allocated_txn_id, cleared_entry_id, refund_method_key,
              refund_reference)
    ON unallocated_receipts TO bms_app;

CREATE FUNCTION unallocated_receipts_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'open' THEN
        RAISE EXCEPTION 'unallocated_receipts: a settled receipt never changes'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status NOT IN ('allocated', 'refunded', 'reversed') THEN
        RAISE EXCEPTION 'unallocated_receipts: an open receipt ends as allocated, refunded or reversed'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'unallocated_receipts: the version rises by one with every change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- What the payment was never changes.
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.branch_id, NEW.intent_id, NEW.purpose, NEW.subject_type, NEW.subject_id,
        NEW.amount_minor, NEW.currency, NEW.value_date, NEW.confirmation_code, NEW.refusal_code, NEW.journal_entry_id)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.branch_id, OLD.intent_id, OLD.purpose, OLD.subject_type, OLD.subject_id,
        OLD.amount_minor, OLD.currency, OLD.value_date, OLD.confirmation_code, OLD.refusal_code, OLD.journal_entry_id)
    THEN
        RAISE EXCEPTION 'unallocated_receipts: what the payment was never changes'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER unallocated_receipts_guard BEFORE UPDATE ON unallocated_receipts
    FOR EACH ROW EXECUTE FUNCTION unallocated_receipts_guard();

-- The checker of an allocation or a refund. Money moving, so catalogued as such (the maker permission of V2 is too).
INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.payments.allocate_approve', 'core', 'Payments: approve the allocation, refund or reversal of gateway money', true);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.payments.allocate_approve'),
    ('branch_manager', 'core.payments.allocate_approve'),
    ('accountant', 'core.payments.allocate_approve')
ON CONFLICT DO NOTHING;

-- (6) The guard of `payment_intents` with two added moves and one narrowed (see item 6 of the header); the trigger that calls it is
-- V54's and is not touched.
CREATE OR REPLACE FUNCTION payment_intents_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    moves text[][] := ARRAY[
        ARRAY['created', 'pending'], ARRAY['created', 'failed'], ARRAY['created', 'cancelled'], ARRAY['created', 'expired'],
        ARRAY['pending', 'paid'], ARRAY['pending', 'failed'], ARRAY['pending', 'cancelled'], ARRAY['pending', 'expired'],
        ARRAY['pending', 'needs_attention'],
        ARRAY['expired', 'paid'], ARRAY['expired', 'failed'], ARRAY['expired', 'cancelled'], ARRAY['expired', 'needs_attention'],
        -- A card payer may retry on the same hosted order after a failure, and a cancel can lose a race with a payment.
        ARRAY['failed', 'paid'], ARRAY['failed', 'needs_attention'],
        ARRAY['cancelled', 'paid'], ARRAY['cancelled', 'needs_attention'],
        ARRAY['paid', 'booked'], ARRAY['paid', 'booked_unallocated'], ARRAY['paid', 'needs_attention'], ARRAY['paid', 'reversed'],
        ARRAY['needs_attention', 'booked'], ARRAY['needs_attention', 'booked_unallocated'], ARRAY['needs_attention', 'reversed'],
        ARRAY['booked', 'reversed_pending_review'], ARRAY['booked', 'reversed'],
        ARRAY['booked_unallocated', 'reversed_pending_review'], ARRAY['booked_unallocated', 'reversed'],
        ARRAY['reversed_pending_review', 'reversed'],
        -- V84: a payment reported reversed and then completed again goes back to what it was.
        ARRAY['reversed_pending_review', 'booked'], ARRAY['reversed_pending_review', 'booked_unallocated']];
    i integer;
    ok boolean := false;
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    -- What was asked for never changes: who, what for, how much, which reference, and when it lapses.
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.branch_id, NEW.purpose, NEW.subject_type, NEW.subject_id,
        NEW.payer_phone_e164, NEW.amount_minor, NEW.currency, NEW.provider, NEW.scope, NEW.merchant_reference,
        NEW.initiated_by, NEW.idempotency_key, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.branch_id, OLD.purpose, OLD.subject_type, OLD.subject_id,
        OLD.payer_phone_e164, OLD.amount_minor, OLD.currency, OLD.provider, OLD.scope, OLD.merchant_reference,
        OLD.initiated_by, OLD.idempotency_key, OLD.expires_at)
    THEN
        RAISE EXCEPTION 'payment_intents: the request columns of an intent never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    -- What the provider told us and what was booked is written once.
    IF (OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference)
       OR (OLD.redirect_url IS NOT NULL AND NEW.redirect_url IS DISTINCT FROM OLD.redirect_url)
       OR (OLD.paid_amount_minor IS NOT NULL AND NEW.paid_amount_minor IS DISTINCT FROM OLD.paid_amount_minor)
       OR (OLD.paid_currency IS NOT NULL AND NEW.paid_currency IS DISTINCT FROM OLD.paid_currency)
       OR (OLD.confirmation_code IS NOT NULL AND NEW.confirmation_code IS DISTINCT FROM OLD.confirmation_code)
       OR (OLD.reported_method IS NOT NULL AND NEW.reported_method IS DISTINCT FROM OLD.reported_method)
       OR (OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at)
       OR (OLD.verified_at IS NOT NULL AND NEW.verified_at IS DISTINCT FROM OLD.verified_at)
       OR (OLD.booked_subject_txn_id IS NOT NULL AND NEW.booked_subject_txn_id IS DISTINCT FROM OLD.booked_subject_txn_id)
       OR (OLD.booked_at IS NOT NULL AND NEW.booked_at IS DISTINCT FROM OLD.booked_at)
    THEN
        RAISE EXCEPTION 'payment_intents: what the provider reported and what was booked is written once'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        -- V84: an order whose money stands in a loan or account is taken out only by the approved reversal, never by this move.
        IF OLD.status = 'needs_attention' AND NEW.status = 'reversed' AND OLD.booked_subject_txn_id IS NOT NULL THEN
            RAISE EXCEPTION 'payment_intents: needs_attention to reversed is refused for an order with a booked transaction'
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        FOR i IN 1 .. array_length(moves, 1) LOOP
            IF moves[i][1] = OLD.status AND moves[i][2] = NEW.status THEN
                ok := true;
            END IF;
        END LOOP;
        IF NOT ok THEN
            RAISE EXCEPTION 'payment_intents: % to % is not an allowed transition', OLD.status, NEW.status
                USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
