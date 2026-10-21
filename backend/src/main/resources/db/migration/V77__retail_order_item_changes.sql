-- V77: change the items of an open customer order without cancelling it (issue #393, ADR-052 amendment 1). The order keeps its
-- number; the lines it had stay as written (append-only) under their revision, the new set is written under the next revision,
-- and the stock kept aside follows in the same transaction (StockLedger.rehold).
--
-- Numbered V77: main is at V75 and V76 (issue #412) merges first. Flyway runs with outOfOrder off, so the file is renamed
-- at merge to the next free number in merge order if that changes.
--
-- Expand only, safe on a populated database. Two columns with a constant default (every existing order and line is revision 1),
-- the unique line number widened to the revision, the guard trigger taught the item change, one more history step. No existing
-- row changes.
--
-- Locks, said plainly. Flyway runs this file in one transaction, so the locks are held to the commit. ADD COLUMN with a constant
-- default is a catalogue change in PostgreSQL 16 (no table rewrite) but takes ACCESS EXCLUSIVE on retail_customer_orders and
-- retail_customer_order_lines, which blocks taking and moving orders until the commit. The new unique index on the lines is built
-- under that lock; the checks are added NOT VALID and validated after. The three tables are small (orders came with V70).
--
-- The first statement sets lock_timeout to five seconds (as V70 does): if a lock cannot be had the migration fails and is
-- retried, rather than every writer on the tables queueing behind it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- items_revision is the revision of the order's current lines; it rises by one with each change of items, together with
-- version, and only while the order is ordered or packed (the guard below). items_total_minor follows the new lines.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_customer_orders ADD COLUMN items_revision integer NOT NULL DEFAULT 1;
ALTER TABLE retail_customer_orders ADD CONSTRAINT retail_customer_orders_items_revision_chk CHECK (items_revision > 0) NOT VALID;
ALTER TABLE retail_customer_orders VALIDATE CONSTRAINT retail_customer_orders_items_revision_chk;
GRANT UPDATE (items_revision, items_total_minor) ON retail_customer_orders TO bms_app;

-- A line belongs to one revision of its order; the current lines are those of the order's items_revision. Lines are still never
-- updated or deleted, so the history keeps what every revision held.
ALTER TABLE retail_customer_order_lines ADD COLUMN revision integer NOT NULL DEFAULT 1;
ALTER TABLE retail_customer_order_lines ADD CONSTRAINT retail_customer_order_lines_revision_chk CHECK (revision > 0) NOT VALID;
ALTER TABLE retail_customer_order_lines VALIDATE CONSTRAINT retail_customer_order_lines_revision_chk;
ALTER TABLE retail_customer_order_lines DROP CONSTRAINT retail_customer_order_lines_tenant_id_order_id_line_no_key;
ALTER TABLE retail_customer_order_lines
    ADD CONSTRAINT retail_customer_order_lines_revision_line_key UNIQUE (tenant_id, order_id, revision, line_no);

-- The history step of a change of items. Its note lists what changed.
ALTER TABLE retail_customer_order_events DROP CONSTRAINT retail_customer_order_events_step_check;
ALTER TABLE retail_customer_order_events
    ADD CONSTRAINT retail_customer_order_events_step_check
    CHECK (step IN ('ordered', 'changed', 'items_changed', 'packed', 'out_for_delivery', 'delivered', 'cancelled',
                    'fee_paid', 'fee_written_off', 'sale_voided')) NOT VALID;
ALTER TABLE retail_customer_order_events VALIDATE CONSTRAINT retail_customer_order_events_step_check;

-- As in V70, with one rule added first: the items (items_revision and items_total_minor) change only on an order that is ordered
-- or packed, without a change of step, and the revision rises by exactly one.
CREATE OR REPLACE FUNCTION retail_customer_orders_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'retail_customer_orders: the version rises by one per change';
    END IF;
    IF NEW.items_revision <> OLD.items_revision OR NEW.items_total_minor <> OLD.items_total_minor THEN
        IF NOT (OLD.status IN ('ordered', 'packed') AND NEW.status = OLD.status
                AND NEW.items_revision = OLD.items_revision + 1) THEN
            RAISE EXCEPTION 'retail_customer_orders: the items of a % order do not change', OLD.status;
        END IF;
    END IF;
    IF OLD.status = 'delivered' AND NEW.status = 'delivered' AND OLD.fee_method = 'account' AND OLD.fee_paid_at IS NULL
       AND NEW.fee_paid_at IS NOT NULL AND NEW.sale_id = OLD.sale_id AND NEW.fee_entry_id = OLD.fee_entry_id
       AND NEW.fee_method = OLD.fee_method AND NEW.delivery_fee_minor = OLD.delivery_fee_minor THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('delivered', 'cancelled') THEN
        RAISE EXCEPTION 'retail_customer_orders: a % order does not change', OLD.status;
    END IF;
    IF NOT ((OLD.status = NEW.status)
            OR (OLD.status = 'ordered' AND NEW.status IN ('packed', 'out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'packed' AND NEW.status IN ('out_for_delivery', 'delivered', 'cancelled'))
            OR (OLD.status = 'out_for_delivery' AND NEW.status IN ('delivered', 'cancelled'))) THEN
        RAISE EXCEPTION 'retail_customer_orders: % cannot become %', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END
$$;
