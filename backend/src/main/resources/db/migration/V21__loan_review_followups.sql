-- V21: lending follow-ups from the #44 and #45 reviews (issue #49). V21 is claimed for lending
-- on issue #50.
--
-- 1. A released pledge or guarantee stays released.
--
-- V8's guards let any UPDATE through that kept the loan, the item (or guarantor) and the amount,
-- so on a frozen loan released_at could be set back to NULL, or a released guarantee reactivated.
-- Off draft, the only change now allowed is the release itself: released_at from NULL to a time,
-- status from active to released. Replacing the functions keeps the V8 triggers.

CREATE OR REPLACE FUNCTION lending_loan_collateral_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lending_loan_require_draft(OLD.loan_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.loan_id = OLD.loan_id AND NEW.collateral_id = OLD.collateral_id
            AND NEW.pledged_value_minor = OLD.pledged_value_minor
            AND (NEW.released_at IS NOT DISTINCT FROM OLD.released_at
                 OR (OLD.released_at IS NULL AND NEW.released_at IS NOT NULL)) THEN
        RETURN NEW;
    END IF;
    PERFORM lending_loan_require_draft(NEW.loan_id);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION lending_loan_guarantors_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lending_loan_require_draft(OLD.loan_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.loan_id = OLD.loan_id AND NEW.guarantor_member_id = OLD.guarantor_member_id
            AND NEW.guaranteed_amount_minor = OLD.guaranteed_amount_minor
            AND (NEW.status = OLD.status OR (OLD.status = 'active' AND NEW.status = 'released')) THEN
        RETURN NEW;
    END IF;
    PERFORM lending_loan_require_draft(NEW.loan_id);
    RETURN NEW;
END;
$$;

-- 2. A flat fee is at most 10^15 minor units, the API bound (ProductApi.MAX_MONEY_MINOR), so a
--    row written outside the API cannot hold an amount the schedule arithmetic cannot sum.
ALTER TABLE lending_loan_product_fees
    ADD CONSTRAINT lending_loan_product_fees_amount_bound CHECK (amount_minor <= 1000000000000000);
