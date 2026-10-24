-- V93: the shop's own barcodes are EAN-13 (issue #524, owner decision D25, ADR-070 decision 12).
--
-- Expand only, safe on a populated database: one check of retail_product_barcodes is replaced, nothing
-- else changes. V87 let a made code (generated) only be Code 128, the IN codes of before. A code the
-- system makes now is an EAN-13 in GS1's restricted-circulation range: 210, nine digits from the same
-- tenant_sequences key ('retail_barcode', no new key) and the GS1 check digit, which the catalogue
-- service computes. The IN codes already made keep their rows, their lookup and their labels.
--
-- Flyway runs this file in one transaction; SET LOCAL lock_timeout makes it fail after 5 seconds, to be
-- retried, rather than queue the shop's barcode reads behind a long report.
--
-- Flyway runs with outOfOrder off: V93 is the next number after V92 on main. #529 (average cost) claims
-- V93 too; whichever merges second renumbers to V94 before it merges (AGENTS.md ledger). The payments
-- pull requests (#315 and #318) take the numbers after both.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_product_barcodes
    ADD CONSTRAINT retail_product_barcodes_generated_chk
        CHECK (NOT generated OR format = 'code128' OR (format = 'ean13' AND code ~ '^2[0-9]{12}$')) NOT VALID;
ALTER TABLE retail_product_barcodes VALIDATE CONSTRAINT retail_product_barcodes_generated_chk;
-- V87's check was unnamed, so it is found by what it says rather than by the name PostgreSQL gave it,
-- and the migration stops if it is not there exactly once. The text is pg_get_constraintdef's for a
-- text column; a database where it reads otherwise (a hand-patched check, a varchar column) aborts here
-- on purpose, so the wrong check is never dropped.
DO $$
DECLARE
    old_check text;
BEGIN
    SELECT string_agg(conname, ',') INTO STRICT old_check
      FROM pg_constraint
     WHERE conrelid = 'retail_product_barcodes'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) = 'CHECK (((NOT generated) OR (format = ''code128''::text)))';
    IF old_check IS NULL OR old_check LIKE '%,%' THEN
        RAISE EXCEPTION 'V93: expected one V87 check on generated, found %', coalesce(old_check, 'none');
    END IF;
    EXECUTE format('ALTER TABLE retail_product_barcodes DROP CONSTRAINT %I', old_check);
END
$$;
