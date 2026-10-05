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
