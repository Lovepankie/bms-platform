-- V42: a reversal that reopens a closed loan restores that loan's own pledges (issue #176).
-- V41 (the retail price lists, #247) is on main; this follows it (outOfOrder is off).
--
-- Closing a loan releases its pledges (FR-COL-04, the #45 rule). A repayment reversal that
-- reopens it (FR-REP-05, for example a bounced payoff) left it active with no collateral,
-- because V21's guard lets released_at move only from NULL to a time once the loan is not a
-- draft. The service now restores the pledges itself, under the items' row locks, after checking
-- each item is still held and pledged nowhere else; the one-open-pledge index stays the
-- backstop. The guard allows that single change: released_at back to NULL, nothing else on the
-- row, on an active loan, and only for the loan the transaction names in
-- bms.restore_pledges_loan (set with set_config(..., true), so it ends with the transaction).

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
    IF TG_OP = 'UPDATE' AND NEW.loan_id = OLD.loan_id AND NEW.collateral_id = OLD.collateral_id
            AND NEW.pledged_value_minor = OLD.pledged_value_minor
            AND OLD.released_at IS NOT NULL AND NEW.released_at IS NULL
            AND current_setting('bms.restore_pledges_loan', true) = NEW.loan_id::text
            AND (SELECT status FROM lending_loans WHERE id = NEW.loan_id) = 'active' THEN
        RETURN NEW;
    END IF;
    PERFORM lending_loan_require_draft(NEW.loan_id);
    RETURN NEW;
END;
$$;
