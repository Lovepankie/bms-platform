-- V97: buy stock on credit from a supplier, see what is owed and pay it, without the purchasing capability (issue #557,
-- owner decision D15, ADR-084).
--
-- Expand only, safe on a populated database: one check of retail_purchases is replaced. V12 let a restock be paid
-- in cash, by bank or on credit; Buy stock now also offers mobile money, the way most suppliers are paid. A mobile money
-- restock credits the mobile_money account (V11) and, like a bank one, never touches the till. Nothing else changes in the
-- schema: a credit restock already credits trade creditors with the supplier as the subledger, and V47's supplier
-- payments, allocations and the payables report are reused as they are (ADR-084 decision 1).
--
-- Flyway runs this file in one transaction; SET LOCAL lock_timeout makes it fail after 5 seconds, to be retried,
-- rather than queue Buy stock behind a long report.
--
-- Flyway runs with outOfOrder off: V97 is the next number after V96 (item codes from the category, #493, merged). The
-- payments pull requests (#315 and #318) take the numbers after it.

SET LOCAL lock_timeout = '5s';

-- The V12 check has no name of its own; find it by what it says, so the credit-needs-a-supplier check next to it can never be
-- the one dropped.
DO $$
DECLARE
    v_name text;
BEGIN
    SELECT conname INTO STRICT v_name
      FROM pg_constraint
     WHERE conrelid = 'retail_purchases'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%payment_method%'
       AND pg_get_constraintdef(oid) LIKE '%ANY%';
    EXECUTE format('ALTER TABLE retail_purchases DROP CONSTRAINT %I', v_name);
END
$$;

ALTER TABLE retail_purchases ADD CONSTRAINT retail_purchases_payment_method_chk
    CHECK (payment_method IN ('cash', 'mobile_money', 'bank', 'credit'));
