-- V40: search helpers (issue #241, ADR-039; docs/specs/search.md).
--
-- Expand only: two immutable functions. No table, column, index, constraint, policy, grant or row
-- changes, so the previous release runs unchanged on this schema (expand and contract, chapter 6
-- section 6.9).
--
-- The number is the next free one above the highest on main when the pull request was marked ready;
-- the coordinator renumbers at merge (AGENTS.md, Flyway runs with outOfOrder off).
--
-- No trigram index is created, on purpose. Every tenant table is read as bms_app under forced
-- row-level security, and the planner may use a user qual as an index condition only when it is
-- leakproof (section 6.3.2). No text search operator is: textlike, texticlike, lower and translate
-- are all marked not leakproof, so a trigram index (the existing ones of V1 and V10 included) is
-- read by the owner role and never by the application. Measured through the API as bms_app on a busy
-- tenant (25,000 sales, 12,000 members, 6,000 products), a search that matches nothing and so walks
-- every row takes under 60 ms (SearchScaleIT). The cost to keep low is the cost per row, which is why
-- bms_fold is written as an inlinable expression with an ASCII fast path (not STRICT, which would stop
-- the planner inlining it), and why a column in another table is reached through a subquery that runs
-- once per statement, never one that runs per row.
--
-- Accents. unaccent is not used: it is not immutable, needs a second extension, and its table is far
-- larger than East African names need. bms_fold carries its own table of the Latin accents (French,
-- Portuguese, Arabic and Nordic spellings), listed in both cases so it does not depend on the
-- server's locale, and spells out the ligatures (sharp s as ss, ae and oe). A text of one byte per
-- character is lower-cased only. The browser folds with the same table (frontend/src/app/ui/fold.ts)
-- and a test keeps the two equal. Every name in the bodies is schema qualified, so a search_path
-- cannot change what they do; a SET clause would stop the planner inlining them, so none is used.

-- Lower case with Latin accents removed: "Jose" with an acute accent and "JOSE" both fold to "jose".
CREATE OR REPLACE FUNCTION bms_fold(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT CASE WHEN pg_catalog.octet_length(t) OPERATOR(pg_catalog.=) pg_catalog.char_length(t)
        THEN pg_catalog.lower(t)
        ELSE pg_catalog.lower(pg_catalog.translate(
            pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(pg_catalog.replace(t,
                U&'\00DF', 'ss'), U&'\1E9E', 'ss'), U&'\00E6', 'ae'), U&'\00C6', 'ae'), U&'\0153', 'oe'), U&'\0152', 'oe'),
            U&'\00E0\00C0\00E1\00C1\00E2\00C2\00E3\00C3\00E4\00C4\00E5\00C5\0101\0100\0103\0102\0105\0104\00E7\00C7\0107\0106\010D\010C\010F\010E\0111\0110\00E8\00C8\00E9\00C9\00EA\00CA\00EB\00CB\0113\0112\0117\0116\0119\0118\011B\011A\011F\011E\00EC\00CC\00ED\00CD\00EE\00CE\00EF\00CF\012B\012A\012F\012E\0131\0049\0142\0141\013E\013D\00F1\00D1\0144\0143\0148\0147\00F2\00D2\00F3\00D3\00F4\00D4\00F5\00D5\00F6\00D6\00F8\00D8\014D\014C\0151\0150\0155\0154\0159\0158\015B\015A\0161\0160\015F\015E\0219\0218\0165\0164\0163\0162\021B\021A\00F9\00D9\00FA\00DA\00FB\00DB\00FC\00DC\016B\016A\016F\016E\0171\0170\0173\0172\00FD\00DD\00FF\0178\017A\0179\017C\017B\017E\017D',
            'aaaaaaaaaaaaaaaaaaccccccddddeeeeeeeeeeeeeeeeggiiiiiiiiiiiiiillllnnnnnnoooooooooooooooorrrrssssssssttttttuuuuuuuuuuuuuuuuyyyyzzzzzz')) END $$;

-- The digits of a phone number as written: "+256 700 000 011" and "0700-000-011" lose the separators.
-- The inner translate leaves the characters that are not digits; the outer one deletes them.
CREATE OR REPLACE FUNCTION bms_digits(t text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    AS $$ SELECT pg_catalog.translate(t, pg_catalog.translate(t, '0123456789', ''), '') $$;
