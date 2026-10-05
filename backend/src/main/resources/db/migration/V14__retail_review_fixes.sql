-- V14: fixes from the adversarial review of the retail backend (issue #68,
-- docs/reviews/retail-r1-r4-adversarial-review.md on review/retail-r1-r4).
--
-- Additive except for replaced CHECK constraints (chapter 6 section 6.9). The next Flyway
-- version free above main and every open pull request: V9 is lending #47, V10 to V12 retail,
-- V13 the price floor (#64). Lending #47 must merge before the retail migrations deploy, or
-- Flyway validation fails (review F6); outOfOrder stays off.

-- ---------------------------------------------------------------------------------------------
-- F9: a product code has no whitespace at the ends (any Unicode space, not only U+0020), no
-- control character and no space other than U+0020 inside. The application normalises with
-- strip() and NFKC first (RetailCatalogue.normaliseCode), so this only catches a writer that
-- bypasses it.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_products DROP CONSTRAINT retail_products_code_check;
ALTER TABLE retail_products ADD CONSTRAINT retail_products_code_check CHECK (
    code ~ '^\S(.*\S)?$'
    AND code !~ '[[:cntrl:]]'
    AND code !~ '[ ­؜ ᠎ -‏ -  -⁤　﻿]'
);

-- ---------------------------------------------------------------------------------------------
-- F7: every unit amount a request can set is at most 10^13 minor units, the bound the API
-- validates (RetailCatalogue.MAX_AMOUNT_MINOR). Totals are not bounded here: they are sums of
-- bounded values and the application refuses one that does not fit (amount_out_of_range).
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_products
    ADD CONSTRAINT retail_products_cost_minor_range CHECK (cost_minor <= 10000000000000),
    ADD CONSTRAINT retail_products_sell_minor_range CHECK (sell_minor <= 10000000000000);
ALTER TABLE retail_price_history
    ADD CONSTRAINT retail_price_history_amount_range CHECK (
        new_cost_minor <= 10000000000000 AND new_sell_minor <= 10000000000000
        AND coalesce(old_cost_minor, 0) <= 10000000000000 AND coalesce(old_sell_minor, 0) <= 10000000000000);
ALTER TABLE retail_purchase_lines
    ADD CONSTRAINT retail_purchase_lines_amount_range CHECK (
        cost_minor <= 10000000000000 AND coalesce(sell_minor, 0) <= 10000000000000);
ALTER TABLE retail_sale_lines
    ADD CONSTRAINT retail_sale_lines_amount_range CHECK (
        unit_price_minor <= 10000000000000 AND unit_cost_minor <= 10000000000000);
ALTER TABLE retail_sale_payments
    ADD CONSTRAINT retail_sale_payments_amount_range CHECK (amount_minor <= 10000000000000);
ALTER TABLE retail_stock_movements
    ADD CONSTRAINT retail_stock_movements_cost_range CHECK (unit_cost_minor <= 10000000000000);
ALTER TABLE retail_usage_lines
    ADD CONSTRAINT retail_usage_lines_cost_range CHECK (unit_cost_minor <= 10000000000000);
ALTER TABLE retail_stocktake_lines
    ADD CONSTRAINT retail_stocktake_lines_cost_range CHECK (coalesce(unit_cost_minor, 0) <= 10000000000000);

-- ---------------------------------------------------------------------------------------------
-- F4: a stock movement carries the business date of its event (the purchase's purchased_on, the
-- sale's sale_date, the usage report's occurred_on, the void's and the stock-take commit's date),
-- the same date its journal entry carries, so a valuation as_of a past date reads quantities and
-- the inventory account on one basis. Rows written before this migration take the date they
-- were recorded on in the pilot's zone. The append-only trigger is lifted for the backfill only.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE retail_stock_movements ADD COLUMN business_date date;
ALTER TABLE retail_stock_movements DISABLE TRIGGER reject_mutation;
UPDATE retail_stock_movements SET business_date = (occurred_at AT TIME ZONE 'Africa/Kampala')::date;
ALTER TABLE retail_stock_movements ENABLE TRIGGER reject_mutation;
ALTER TABLE retail_stock_movements ALTER COLUMN business_date SET NOT NULL;
CREATE INDEX retail_stock_movements_business_date
    ON retail_stock_movements (tenant_id, business_date, branch_id, product_id);

-- ---------------------------------------------------------------------------------------------
-- F10: profit is computed from the append-only sale lines. The sale header keeps UPDATE for the
-- columns that legitimately change, and only those: paid_minor (up, by a payment), the void
-- columns (once, completed to voided), updated_at, version, and the two entry ids (once, from
-- null). A committed stock-take and its lines no longer change. The owner's maintenance escape
-- hatch of reject_mutation (bms.allow_mutation) applies here too.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION retail_sales_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.created_at, NEW.branch_id, NEW.sale_no, NEW.sale_date, NEW.payment_method,
        NEW.customer_id, NEW.buyer_name, NEW.buyer_contact, NEW.due_date, NEW.currency, NEW.total_minor,
        NEW.cost_total_minor, NEW.historical, NEW.created_by)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.created_at, OLD.branch_id, OLD.sale_no, OLD.sale_date, OLD.payment_method,
        OLD.customer_id, OLD.buyer_name, OLD.buyer_contact, OLD.due_date, OLD.currency, OLD.total_minor,
        OLD.cost_total_minor, OLD.historical, OLD.created_by)
       OR NEW.paid_minor < OLD.paid_minor
       OR (OLD.sale_entry_id IS NOT NULL AND NEW.sale_entry_id IS DISTINCT FROM OLD.sale_entry_id)
       OR (OLD.cost_entry_id IS NOT NULL AND NEW.cost_entry_id IS DISTINCT FROM OLD.cost_entry_id)
       OR (OLD.status = 'voided' AND (NEW.status, NEW.voided_at, NEW.voided_by, NEW.void_reason)
                                     IS DISTINCT FROM (OLD.status, OLD.voided_at, OLD.voided_by, OLD.void_reason))
       OR (OLD.status = 'completed' AND NEW.status = 'completed'
           AND (NEW.voided_at, NEW.voided_by, NEW.void_reason) IS DISTINCT FROM (OLD.voided_at, OLD.voided_by, OLD.void_reason))
    THEN
        RAISE EXCEPTION 'retail_sales: only the paid amount, the void, the entry ids (once) and the version may change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_sales_guard_update BEFORE UPDATE ON retail_sales
    FOR EACH ROW EXECUTE FUNCTION retail_sales_guard_update();

CREATE FUNCTION retail_stocktake_guard_update() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_status text;
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN NEW;
    END IF;
    IF TG_TABLE_NAME = 'retail_stocktakes' THEN
        v_status := OLD.status;
    ELSE
        SELECT status INTO v_status FROM retail_stocktakes WHERE tenant_id = OLD.tenant_id AND id = OLD.stocktake_id;
    END IF;
    IF v_status = 'committed' THEN
        RAISE EXCEPTION '% of a committed stock-take is not allowed', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER retail_stocktake_guard_update BEFORE UPDATE ON retail_stocktakes
    FOR EACH ROW EXECUTE FUNCTION retail_stocktake_guard_update();
CREATE TRIGGER retail_stocktake_guard_update BEFORE UPDATE ON retail_stocktake_lines
    FOR EACH ROW EXECUTE FUNCTION retail_stocktake_guard_update();
