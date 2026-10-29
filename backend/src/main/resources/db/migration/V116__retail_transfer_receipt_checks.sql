-- V116: a transfer receipt is checked against its lines (issue #239), after V115 (#371).
--
-- V38 let a transfer move from in_transit to received with no look at its lines: the entry check asked only for the
-- source entry, so a receipt that left a line without received_qty, or that brought stock of value into the destination
-- without the destination's journal entry, would have been saved. The receipt itself writes every received_qty first and
-- posts the destination entry whenever something of value arrived (TransferService.receiveTransfer), so this states in the
-- database what the code already does, as the specification asks (docs/specs/trade-capabilities.md, the retail_transfers
-- row of section 10; chapter 6, retail_transfers):
--   every line of the transfer has received_qty when it is received, and
--   in_entry_id is null only when nothing of value arrived: the received quantities at the dispatch cost, rounded half up
--   per line as Quantities.value does, sum to zero.
-- A CHECK constraint cannot read the lines, so both rules join the in_transit to received branch of the update guard.
--
-- No table change and no data change: the guard function is replaced in place and its trigger stays. The function body is
-- V38's with the two checks added; every other transition is unchanged. Existing received rows are not re-read (the guard
-- runs on UPDATE only), and every receipt written so far came through the code above.

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
        IF EXISTS (SELECT 1 FROM retail_transfer_lines l
                    WHERE l.tenant_id = OLD.tenant_id AND l.transfer_id = OLD.id AND l.received_qty IS NULL) THEN
            RAISE EXCEPTION 'retail_transfers: a receipt records what arrived on every line'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.in_entry_id IS NULL
           AND (SELECT coalesce(sum(round(l.received_qty * l.unit_cost_minor)), 0) FROM retail_transfer_lines l
                 WHERE l.tenant_id = OLD.tenant_id AND l.transfer_id = OLD.id) <> 0 THEN
            RAISE EXCEPTION 'retail_transfers: a receipt that brought stock of value has the destination entry'
                USING ERRCODE = 'check_violation';
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
