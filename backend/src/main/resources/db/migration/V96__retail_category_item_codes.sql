-- V96: item codes made from the category (issue #493, owner decisions D14 and D16, ADR-081; docs/specs/units-packs-and-costing.md
-- section 6).
--
-- Each category gets a short prefix of 2 to 4 capital letters, unique in the business and editable, suggested from its name
-- (Bulbs BLB, TVs TV, Phone accessories PHA, Extensions EXT), and a counter, code_next, the next number to try in it. An item
-- added inside a category without a typed code gets PREFIX-0001, the next number no item of the business holds as its code,
-- taken under the category row's lock (bms_next_item_code), so two people adding at once get different numbers. A typed code is
-- kept and the unique code index (V10) is unchanged. Existing items and their codes are not touched: only categories change.
--
-- Expand only, safe on a populated database. ADD COLUMN with a constant default (code_next) or none (code_prefix) is a
-- catalogue change in PostgreSQL 16, with no table rewrite. The backfill updates every category row once (staging: tens of rows).
-- The first statement sets lock_timeout to five seconds as V94 does, so the migration fails and is retried rather than every
-- writer of the catalogue queueing behind it.

SET LOCAL lock_timeout = '5s';

ALTER TABLE retail_categories ADD COLUMN code_prefix varchar(4);
ALTER TABLE retail_categories ADD COLUMN code_next integer NOT NULL DEFAULT 1 CHECK (code_next >= 1);

COMMENT ON COLUMN retail_categories.code_prefix IS
    'The 2 to 4 capital letters that start the item codes the app makes in this category (ADR-081). Unique in the business.';
COMMENT ON COLUMN retail_categories.code_next IS
    'The next number bms_next_item_code tries in this category; it only rises, and is not part of the version (ADR-081).';

-- The prefix a name suggests before uniqueness: letters only, accents folded (bms_fold, V40), small joining words left out.
-- One word: its first letter and the consonants after it, up to three (Bulbs BLB, Extensions EXT). A short or mixed case
-- acronym keeps its capitals (TVs TV, LED LED). Two or more words: the first two letters of the first word and the first letter
-- of each next one, up to four (Phone accessories PHA). Fewer than two letters are padded with X; no letters at all is CAT.
CREATE FUNCTION bms_category_prefix_base(p_name text) RETURNS text
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE AS $$
DECLARE
    w text;
    letters text;
    caps text;
    words text[] := ARRAY[]::text[];
    acronyms text[] := ARRAY[]::text[];
    result text := '';
    i integer;
BEGIN
    FOREACH w IN ARRAY pg_catalog.regexp_split_to_array(coalesce(p_name, ''), '[^[:alpha:]]+') LOOP
        letters := pg_catalog.upper(pg_catalog.regexp_replace(public.bms_fold(w), '[^a-z]', '', 'g'));
        CONTINUE WHEN letters = '' OR letters IN ('AND', 'OF', 'THE', 'FOR', 'WITH', 'NA');
        caps := pg_catalog.regexp_replace(w, '[^A-Z]', '', 'g');
        words := words || letters;
        -- TVs and LED are acronyms; BULBS, a whole word typed in capitals, is not.
        acronyms := acronyms || CASE
            WHEN pg_catalog.length(caps) >= 2 AND (w ~ '[a-z]' OR pg_catalog.length(w) <= 3) THEN caps
            ELSE NULL END;
    END LOOP;
    IF pg_catalog.cardinality(words) = 0 THEN
        RETURN 'CAT';
    END IF;
    IF pg_catalog.cardinality(words) = 1 THEN
        IF acronyms[1] IS NOT NULL THEN
            result := pg_catalog.left(acronyms[1], 4);
        ELSE
            result := pg_catalog.left(words[1], 1)
                || pg_catalog.left(pg_catalog.regexp_replace(pg_catalog.substr(words[1], 2), '[AEIOU]', '', 'g'), 2);
            IF pg_catalog.length(result) < 2 THEN
                result := pg_catalog.left(words[1], 2);
            END IF;
        END IF;
    ELSE
        result := coalesce(pg_catalog.left(acronyms[1], 3), pg_catalog.left(words[1], 2));
        FOR i IN 2..pg_catalog.cardinality(words) LOOP
            EXIT WHEN pg_catalog.length(result) >= 4;
            result := result || pg_catalog.left(words[i], 1);
        END LOOP;
    END IF;
    -- rpad also cuts a longer text, so only a short one is padded.
    RETURN CASE WHEN pg_catalog.length(result) < 2 THEN pg_catalog.rpad(result, 2, 'X') ELSE result END;
END
$$;

-- The suggested prefix, free in the business: the base, else its first three letters and one more, else its first two and two
-- more, else its first letter and three more. Runs as the caller, so under the application it sees only its own business.
CREATE FUNCTION bms_suggest_category_prefix(p_tenant uuid, p_name text, p_self uuid) RETURNS text
LANGUAGE plpgsql STABLE AS $$
DECLARE
    base text := public.bms_category_prefix_base(p_name);
    candidate text;
    a integer;
    b integer;
    c integer;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.retail_categories
                    WHERE tenant_id = p_tenant AND code_prefix = base AND id IS DISTINCT FROM p_self) THEN
        RETURN base;
    END IF;
    FOR a IN 0..25 LOOP
        candidate := pg_catalog.left(base, 3) || pg_catalog.chr(65 + a);
        IF NOT EXISTS (SELECT 1 FROM public.retail_categories
                        WHERE tenant_id = p_tenant AND code_prefix = candidate AND id IS DISTINCT FROM p_self) THEN
            RETURN candidate;
        END IF;
    END LOOP;
    FOR a IN 0..25 LOOP
        FOR b IN 0..25 LOOP
            candidate := pg_catalog.left(base, 2) || pg_catalog.chr(65 + a) || pg_catalog.chr(65 + b);
            IF NOT EXISTS (SELECT 1 FROM public.retail_categories
                            WHERE tenant_id = p_tenant AND code_prefix = candidate AND id IS DISTINCT FROM p_self) THEN
                RETURN candidate;
            END IF;
        END LOOP;
    END LOOP;
    FOR a IN 0..25 LOOP
        FOR b IN 0..25 LOOP
            FOR c IN 0..25 LOOP
                candidate := pg_catalog.left(base, 1) || pg_catalog.chr(65 + a) || pg_catalog.chr(65 + b) || pg_catalog.chr(65 + c);
                IF NOT EXISTS (SELECT 1 FROM public.retail_categories
                                WHERE tenant_id = p_tenant AND code_prefix = candidate AND id IS DISTINCT FROM p_self) THEN
                    RETURN candidate;
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;
    RAISE EXCEPTION 'bms_suggest_category_prefix: no free prefix for %', p_name;
END
$$;

-- Every existing category of every business gets its suggested prefix, the oldest first so it keeps the plainest one.
-- retail_categories has FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V94, bms_owner has BYPASSRLS, and
-- when the migrating owner cannot bypass row security the table drops FORCE for this transaction only.
SET LOCAL row_security = off;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_categories NO FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT id, tenant_id, name FROM retail_categories ORDER BY tenant_id, created_at, id LOOP
        UPDATE retail_categories
           SET code_prefix = public.bms_suggest_category_prefix(r.tenant_id, r.name, r.id)
         WHERE id = r.id;
    END LOOP;
END
$$;
DO $$
BEGIN
    IF NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user) THEN
        ALTER TABLE retail_categories FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;
SET LOCAL row_security = on;

-- A category inserted without a prefix (an application instance from before this migration during a deploy, the import, a
-- seed) gets its suggested one; a writer that names a prefix keeps it.
CREATE FUNCTION retail_categories_prefix_default() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code_prefix IS NULL THEN
        NEW.code_prefix := public.bms_suggest_category_prefix(NEW.tenant_id, NEW.name, NEW.id);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER retail_categories_prefix_default BEFORE INSERT ON retail_categories
    FOR EACH ROW EXECUTE FUNCTION retail_categories_prefix_default();

ALTER TABLE retail_categories ALTER COLUMN code_prefix SET NOT NULL;
ALTER TABLE retail_categories ADD CONSTRAINT retail_categories_code_prefix_letters CHECK (code_prefix ~ '^[A-Z]{2,4}$');
CREATE UNIQUE INDEX retail_categories_code_prefix ON retail_categories (tenant_id, code_prefix);

-- The next item code of a category: PREFIX-0001, four digits at least, the first number from code_next that no item of the
-- business holds as its code (a typed or imported code is skipped, ignoring case as the unique index does). The category row is
-- locked to the commit, so a second add in the same category waits and then takes the next number. FOR NO KEY UPDATE, not FOR
-- UPDATE: it still queues the next add in the category, but not the FOR KEY SHARE that the foreign key check of every item insert or
-- move into the category takes, so an add with a typed code never waits for it and two imports cannot deadlock through it.
CREATE FUNCTION bms_next_item_code(p_category uuid) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    v_tenant uuid;
    v_prefix text;
    n integer;
    candidate text;
BEGIN
    SELECT tenant_id, code_prefix, code_next INTO v_tenant, v_prefix, n
      FROM public.retail_categories WHERE id = p_category FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'bms_next_item_code: no category %', p_category USING ERRCODE = 'no_data_found';
    END IF;
    LOOP
        candidate := v_prefix || '-' || CASE WHEN n < 10000 THEN pg_catalog.lpad(n::text, 4, '0') ELSE n::text END;
        EXIT WHEN NOT EXISTS (SELECT 1 FROM public.retail_products
                               WHERE tenant_id = v_tenant AND pg_catalog.lower(code) = pg_catalog.lower(candidate));
        n := n + 1;
    END LOOP;
    UPDATE public.retail_categories SET code_next = n + 1 WHERE id = p_category;
    RETURN candidate;
END
$$;

GRANT EXECUTE ON FUNCTION bms_category_prefix_base(text) TO bms_app;
GRANT EXECUTE ON FUNCTION bms_suggest_category_prefix(uuid, text, uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION bms_next_item_code(uuid) TO bms_app;
