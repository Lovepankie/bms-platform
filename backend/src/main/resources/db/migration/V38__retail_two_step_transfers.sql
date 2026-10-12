-- V38: retail two-step transfers (ADR-033, issue #193; docs/specs/trade-capabilities.md sections
-- 9.3 and 10). A transfer may now be dispatched (stock leaves the source, status in_transit) and
-- later received, or cancelled, in place of the one-step transfer of V22. The one-step transfer
-- stays the default: nothing here is switched on by the migration, the flow is behind the
-- transfer_receipt capability, and every existing row (all completed or voided) satisfies every
-- replaced rule.
--
-- V22 is not just extended. Its status check admitted only completed and voided; its entry-pair
-- check forced out_entry_id and in_entry_id to be both null or both set; its two void-columns
-- checks tied voided_at, voided_by and void_reason to the status voided alone; and its update
-- guard allowed one change, the void. All five are replaced here. The transfer table is small
-- (a handful of rows a day), so each new check is added NOT VALID, validated and only then is the old one dropped: at no point is a rule missing. Flyway runs the file in one transaction, so
-- the table locks are held until it commits; the table is small, so that is brief.
-- Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one.

-- ---------------------------------------------------------------------------------------------
-- Expand: the receipt columns. received_qty is on the line (null means "not received yet", and
-- for a one-step transfer "all of it"); shortage_entry_id holds the destination's shrinkage entry
-- for a receipt of less than was dispatched (valued at the SOURCE cost, so the clearing legs of
-- the transfer net to zero).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_transfers
    ADD COLUMN received_at       timestamptz,
    ADD COLUMN received_by       uuid,
    ADD COLUMN shortage_entry_id uuid;
ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_shortage_entry_fk FOREIGN KEY (tenant_id, shortage_entry_id)
        REFERENCES journal_entries (tenant_id, id);

ALTER TABLE retail_transfer_lines ADD COLUMN received_qty numeric(14,3);
ALTER TABLE retail_transfer_lines
    ADD CONSTRAINT retail_transfer_lines_received_qty_chk
        CHECK (received_qty IS NULL OR (received_qty >= 0 AND received_qty <= qty)) NOT VALID;
ALTER TABLE retail_transfer_lines VALIDATE CONSTRAINT retail_transfer_lines_received_qty_chk;

-- ---------------------------------------------------------------------------------------------
-- Replace (a): the status check. in_transit: dispatched, not yet received. received: arrived,
-- possibly less than dispatched. cancelled: taken back by the source before it was received.
-- voided: undone after completion or receipt, as before.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_status_chk
        CHECK (status IN ('completed', 'in_transit', 'received', 'cancelled', 'voided')) NOT VALID;
ALTER TABLE retail_transfers VALIDATE CONSTRAINT retail_transfers_status_chk;
ALTER TABLE retail_transfers DROP CONSTRAINT retail_transfers_status_check;

-- ---------------------------------------------------------------------------------------------
-- Replace (b): the entry-pair check. A completed transfer keeps the V22 rule (both ids or neither,
-- neither only for a zero-cost transfer). While in_transit only the source has posted, so in_entry_id
-- is null and out_entry_id is set unless the cost is zero. cancelled keeps out_entry_id (now
-- reversed) and never has an in_entry_id. received has the source entry (unless zero cost); its
-- destination entry is null only when nothing of value arrived (everything received rounds to
-- zero, or nothing was received and the whole value is the shortage). voided has no rule: it can
-- follow a completed or a received transfer, which differ.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_entries_chk
        CHECK (CASE status
                   WHEN 'completed' THEN (out_entry_id IS NULL) = (in_entry_id IS NULL)
                   WHEN 'in_transit' THEN in_entry_id IS NULL AND shortage_entry_id IS NULL
                                          AND (out_entry_id IS NOT NULL OR cost_total_minor = 0)
                   WHEN 'cancelled' THEN in_entry_id IS NULL AND shortage_entry_id IS NULL
                   WHEN 'received' THEN out_entry_id IS NOT NULL OR cost_total_minor = 0
                   ELSE true
               END) NOT VALID;
ALTER TABLE retail_transfers VALIDATE CONSTRAINT retail_transfers_entries_chk;
ALTER TABLE retail_transfers DROP CONSTRAINT retail_transfers_check1;

-- ---------------------------------------------------------------------------------------------
-- Replace (c): the void columns. voided_at, voided_by and void_reason now belong to both ways an
-- open or finished transfer is undone: voided and cancelled. received_at and received_by are set
-- together, and only on a transfer that was received (it may be voided afterwards).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_void_at_chk
        CHECK ((status IN ('voided', 'cancelled')) = (voided_at IS NOT NULL)) NOT VALID;
ALTER TABLE retail_transfers VALIDATE CONSTRAINT retail_transfers_void_at_chk;
ALTER TABLE retail_transfers DROP CONSTRAINT retail_transfers_check2;

ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_void_by_chk
        CHECK (status IN ('completed', 'in_transit', 'received')
               OR (voided_by IS NOT NULL AND btrim(void_reason) <> '')) NOT VALID;
ALTER TABLE retail_transfers VALIDATE CONSTRAINT retail_transfers_void_by_chk;
ALTER TABLE retail_transfers DROP CONSTRAINT retail_transfers_check3;

ALTER TABLE retail_transfers
    ADD CONSTRAINT retail_transfers_received_chk
        CHECK ((received_at IS NULL) = (received_by IS NULL)
               AND (status <> 'received' OR received_at IS NOT NULL)
               AND (received_at IS NULL OR status IN ('received', 'voided'))) NOT VALID;
ALTER TABLE retail_transfers VALIDATE CONSTRAINT retail_transfers_received_chk;

-- ---------------------------------------------------------------------------------------------
-- Replace (d): the update guard. Exactly these changes are allowed, and each only from the state
-- named; every other UPDATE is refused (a row left unchanged is not a change):
--   in_transit -> received   sets in_entry_id (may stay null), shortage_entry_id (may stay null),
--                            received_at and received_by, once
--   in_transit -> cancelled  sets voided_at, voided_by and void_reason, once
--   completed  -> voided     sets voided_at, voided_by and void_reason, once (as V22)
--   received   -> voided     sets voided_at, voided_by and void_reason, once
-- Everything else about the row, the entry ids it already has included, never changes. The
-- migration owner with bms.allow_mutation on keeps its bypass (data repair), as before.
-- ---------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION retail_transfers_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.from_branch_id, NEW.to_branch_id, NEW.transfer_date, NEW.note,
        NEW.currency, NEW.cost_total_minor, NEW.out_entry_id, NEW.created_by)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.from_branch_id, OLD.to_branch_id, OLD.transfer_date, OLD.note,
        OLD.currency, OLD.cost_total_minor, OLD.out_entry_id, OLD.created_by)
    THEN
        RAISE EXCEPTION 'retail_transfers: the transfer itself never changes'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF OLD.status = 'in_transit' AND NEW.status = 'received' THEN
        IF OLD.in_entry_id IS NOT NULL OR OLD.shortage_entry_id IS NOT NULL OR OLD.received_at IS NOT NULL
           OR OLD.voided_at IS NOT NULL THEN
            RAISE EXCEPTION 'retail_transfers: a receipt is recorded once'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF NEW.received_at IS NULL OR NEW.received_by IS NULL
           OR (NEW.voided_at, NEW.voided_by, NEW.void_reason) IS DISTINCT FROM (NULL::timestamptz, NULL::uuid, NULL::varchar)
        THEN
            RAISE EXCEPTION 'retail_transfers: a receipt sets the receipt columns only'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;

    IF (OLD.status = 'in_transit' AND NEW.status = 'cancelled')
       OR (OLD.status IN ('completed', 'received') AND NEW.status = 'voided') THEN
        IF OLD.voided_at IS NOT NULL
           OR (NEW.in_entry_id, NEW.shortage_entry_id, NEW.received_at, NEW.received_by)
              IS DISTINCT FROM (OLD.in_entry_id, OLD.shortage_entry_id, OLD.received_at, OLD.received_by)
        THEN
            RAISE EXCEPTION 'retail_transfers: only the void columns change, once'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'retail_transfers: % to % is not a transition a transfer makes', OLD.status, NEW.status
        USING ERRCODE = 'insufficient_privilege';
END
$$;

-- ---------------------------------------------------------------------------------------------
-- Lines: append-only in V22. A receipt records what arrived on each line, so one column may be set
-- once (null to a value), and only while the transfer is still in_transit. Deletes stay refused.
-- ---------------------------------------------------------------------------------------------

DROP TRIGGER reject_mutation ON retail_transfer_lines;

CREATE FUNCTION retail_transfer_lines_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.transfer_id, NEW.line_no, NEW.product_id, NEW.qty,
        NEW.unit_cost_minor, NEW.line_cost_minor)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.transfer_id, OLD.line_no, OLD.product_id, OLD.qty,
        OLD.unit_cost_minor, OLD.line_cost_minor)
       OR OLD.received_qty IS NOT NULL
       OR NEW.received_qty IS NULL
       OR NOT EXISTS (SELECT 1 FROM retail_transfers t
                       WHERE t.tenant_id = OLD.tenant_id AND t.id = OLD.transfer_id AND t.status = 'in_transit')
    THEN
        RAISE EXCEPTION 'retail_transfer_lines: only received_qty may be set, once, while the transfer is in transit'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_transfer_lines_guard_update BEFORE UPDATE ON retail_transfer_lines
    FOR EACH ROW EXECUTE FUNCTION retail_transfer_lines_guard_update();
CREATE TRIGGER retail_transfer_lines_reject_delete BEFORE DELETE ON retail_transfer_lines
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
GRANT UPDATE (received_qty) ON retail_transfer_lines TO bms_app;

-- The transfers still on the way, newest first: the in-flight report and the "switch off" check.
CREATE INDEX retail_transfers_in_transit ON retail_transfers (tenant_id, created_at DESC, id DESC)
    WHERE status = 'in_transit';
