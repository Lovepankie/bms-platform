-- V145: the references of the import-retail command move into import_refs (issue #135, ADR-027 decision 4, chapter 6
-- section 6.9): the expand step and its backfill. Directly after V144 (#134); Flyway runs with outOfOrder off, so this is
-- renumbered at merge time above the highest number on main if that moves.
--
-- Expand only, safe on a populated database while the previous release serves:
--
--   1. import_runs gains `origin`: `framework` (every run before this one, and every run the import screens open),
--      `import_retail` (a run of the import-retail command, which now writes through the framework's batches, import_refs
--      and opening journals, and is not reconciled or signed in the app) and `retail_import_refs` (the one run per business
--      that holds the references backfilled below). A plain partial unique index keeps that last run to one per business.
--   2. bms_retail_import_ref_key(tenant, source_file, source_ref) is the key in import_refs of a row of retail_import_refs:
--      a history row keeps the key a JSON Lines upload of the same export with the retail_v1 mapping derives
--      (`sales.jsonl|-|ref:SAL-00011`, ImportRefs.sourceKey); an opening journal's row is
--      `opening|inventory|legacy:branch:<branch id>` (the branch found by its code, or `legacy:code:<code>` when none is),
--      and a cash book opening's `opening|cash_book|legacy:branch:<branch id>`.
--   3. bms_backfill_retail_import_refs(limit) copies at most `limit` rows of retail_import_refs that import_refs does not
--      hold yet into import_refs, each under its key with the marker `#by-key` in place of a content hash (the old command
--      never hashed a row; the command and the framework recognise such a key whatever the row's content,
--      ImportRefs.BY_KEY), under the business's one backfill run and one committed batch per old source_file. A journal's
--      target type becomes `ledger.journal_entry`. A row without a target is left out, since import_refs needs one; the
--      command always wrote one (RetailImporter's remember). It is idempotent: a row whose record import_refs already
--      holds, under any key (the command now registers each record it makes there as it writes the old row), is skipped.
--      It returns the rows it selected, and fails on a source_file it has no batch for. The migration calls it in
--      batches of 5,000 until it selects nothing.
--
-- retail_import_refs stays, unchanged, and the command still writes it beside import_refs, so the previous release keeps
-- reading it after a rollback and the cash book's first live day keeps its read. Dropping it is the contract step of a
-- later release, once no deployed release reads it (chapter 6 section 6.9).
--
-- Locks, said plainly. The new column, its CHECK and the partial index take ACCESS EXCLUSIVE and SHARE on import_runs for
-- milliseconds (a small table; the column has a constant default, so no rewrite); the index import_refs_by_run takes
-- SHARE on import_refs, which only imports write. The backfill reads retail_import_refs and branches
-- without locking a row and inserts new rows only (import_runs, import_batches, import_refs); its foreign keys take FOR KEY
-- SHARE on the business's tenants row and on the rows it just made. It updates or locks no row of a trading business's
-- sales, stock or ledger, so a shop's requests never wait on it. Each batch of 5,000 bounds a statement, not the
-- transaction: every batch runs in this migration's one transaction, which holds only the locks above. Every
-- lock is taken under lock_timeout 5s, so the migration fails fast behind a long transaction instead of queueing requests
-- behind it. The tables have FORCE ROW LEVEL SECURITY and no app.tenant_id is bound here: as in V103 and V115, bms_owner
-- has BYPASSRLS (deploy/postgres/initdb/01-roles.sh), every statement names the business it reads or writes, and an owner
-- without it drops FORCE on the six tables for this transaction only (ACCESS EXCLUSIVE on each, in a database that has
-- no such owner in production).

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    ADD COLUMN origin varchar(40) NOT NULL DEFAULT 'framework'
        CONSTRAINT import_runs_origin_check CHECK (origin IN ('framework', 'import_retail', 'retail_import_refs'));

-- One run of backfilled references per business.
CREATE UNIQUE INDEX import_runs_one_backfill ON import_runs (tenant_id) WHERE origin = 'retail_import_refs';

CREATE FUNCTION bms_retail_import_ref_key(p_tenant uuid, p_file text, p_ref text) RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT CASE p_file
               WHEN 'opening' THEN 'opening|inventory|legacy:'
                   || coalesce((SELECT 'branch:' || b.id::text FROM branches b
                                 WHERE b.tenant_id = p_tenant AND upper(b.code) = upper(btrim(p_ref))
                                 ORDER BY b.id LIMIT 1),
                               'code:' || upper(btrim(p_ref)))
               WHEN 'cash_opening' THEN 'opening|cash_book|legacy:branch:' || btrim(p_ref)
               ELSE p_file || '.jsonl|-|ref:' || btrim(p_ref)
           END
$$;
REVOKE ALL ON FUNCTION bms_retail_import_ref_key(uuid, text, text) FROM PUBLIC;

CREATE FUNCTION bms_backfill_retail_import_refs(p_limit integer) RETURNS integer
LANGUAGE plpgsql AS $$
DECLARE
    v_left   integer := p_limit;
    v_count  integer;
    v_tenant uuid;
    v_run    uuid;
    v_file   text;
BEGIN
    IF p_limit IS NULL OR p_limit <= 0 THEN
        RAISE EXCEPTION 'the backfill limit must be above zero';
    END IF;
    PERFORM set_config('lock_timeout', '5s', true);
    FOR v_tenant IN SELECT t.id FROM tenants t
                     WHERE EXISTS (SELECT 1 FROM retail_import_refs o WHERE o.tenant_id = t.id)
                     ORDER BY t.id LOOP
        EXIT WHEN v_left <= 0;
        CREATE TEMPORARY TABLE IF NOT EXISTS bms_backfill_refs (
            source_file varchar(30),
            source_key  varchar(330),
            target_type varchar(60),
            target_id   uuid,
            created_at  timestamptz
        ) ON COMMIT DROP;
        TRUNCATE bms_backfill_refs;
        INSERT INTO bms_backfill_refs
        SELECT k.source_file, k.source_key, k.target_type, k.target_id, k.created_at
          FROM (SELECT o.source_file,
                       bms_retail_import_ref_key(v_tenant, o.source_file, o.source_ref) AS source_key,
                       CASE WHEN o.target_type IN ('journal', 'core.journal_entry') THEN 'ledger.journal_entry'
                            ELSE o.target_type END AS target_type,
                       o.target_id,
                       o.created_at
                  FROM retail_import_refs o
                 WHERE o.tenant_id = v_tenant AND o.target_id IS NOT NULL) k
         WHERE NOT EXISTS (SELECT 1 FROM import_refs n WHERE n.tenant_id = v_tenant AND n.target_id = k.target_id)
         ORDER BY k.source_file, k.source_key
         LIMIT v_left;
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM bms_backfill_refs);

        SELECT id INTO v_run FROM import_runs WHERE tenant_id = v_tenant AND origin = 'retail_import_refs';
        IF v_run IS NULL THEN
            v_run := gen_random_uuid();
            -- No person opened it: created_by is the nil id, as for the import-retail command's own runs.
            INSERT INTO import_runs (id, tenant_id, created_by, status, committing_at, committed_at, origin)
            VALUES (v_run, v_tenant, '00000000-0000-0000-0000-000000000000', 'committed', now(), now(),
                    'retail_import_refs');
        END IF;
        -- One committed batch per old source_file, in the order the command writes them.
        INSERT INTO import_batches (id, tenant_id, run_id, seq, status, committed_at, source_filename)
        SELECT gen_random_uuid(), v_tenant, v_run, f.seq, 'committed', now(), f.file
          FROM (VALUES ('purchases', 1), ('sales', 2), ('usage', 3), ('opening', 4), ('savings', 5), ('expenses', 6),
                       ('withdrawals', 7), ('advances', 8), ('advance_payments', 9), ('banking', 10),
                       ('cash_opening', 11)) AS f (file, seq)
         WHERE EXISTS (SELECT 1 FROM bms_backfill_refs r WHERE r.source_file = f.file)
        ON CONFLICT (run_id, seq) DO NOTHING;
        -- A source_file without a batch would never be copied, and the loop below would stop with rows left behind:
        -- the migration fails instead, naming it.
        SELECT r.source_file INTO v_file FROM bms_backfill_refs r
         WHERE NOT EXISTS (SELECT 1 FROM import_batches b
                            WHERE b.tenant_id = v_tenant AND b.run_id = v_run AND b.source_filename = r.source_file)
         LIMIT 1;
        IF v_file IS NOT NULL THEN
            RAISE EXCEPTION 'retail_import_refs of business % has source_file %, which the backfill does not know',
                v_tenant, v_file;
        END IF;

        INSERT INTO import_refs (id, tenant_id, created_at, run_id, batch_id, source_key, source_ref, target_type,
                                 target_id)
        SELECT gen_random_uuid(), v_tenant, r.created_at, v_run, b.id, r.source_key, r.source_key || '#by-key',
               r.target_type, r.target_id
          FROM bms_backfill_refs r
          JOIN import_batches b ON b.tenant_id = v_tenant AND b.run_id = v_run AND b.source_filename = r.source_file
        ON CONFLICT DO NOTHING;
        -- The rows selected, not those inserted, count against the limit: a row skipped by the conflict (a second old
        -- row of the same record) is held after this batch, so the next selection leaves it out and the loop ends.
        SELECT count(*) INTO v_count FROM bms_backfill_refs;
        v_left := v_left - v_count;
    END LOOP;
    -- The rows selected: zero only when import_refs holds every record retail_import_refs names.
    RETURN p_limit - v_left;
END
$$;
-- Owned by bms_owner, run by this migration and by MigrationOrderIT and RetailImportRefsBackfillIT; never granted to the
-- application.
REVOKE ALL ON FUNCTION bms_backfill_retail_import_refs(integer) FROM PUBLIC;

-- As in V103: an owner without BYPASSRLS drops FORCE on the tables the backfill reads and writes for this transaction
-- only, and puts it back after (staging and production run as bms_owner with BYPASSRLS, where no toggle runs).
DO $$
DECLARE
    copied integer;
    bypass boolean := (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user);
BEGIN
    IF NOT bypass THEN
        ALTER TABLE tenants NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE branches NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_import_refs NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_runs NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_batches NO FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_refs NO FORCE ROW LEVEL SECURITY;
    END IF;
    LOOP
        copied := bms_backfill_retail_import_refs(5000);
        EXIT WHEN copied = 0;
    END LOOP;
    IF NOT bypass THEN
        ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
        ALTER TABLE branches FORCE ROW LEVEL SECURITY;
        ALTER TABLE retail_import_refs FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_runs FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_batches FORCE ROW LEVEL SECURITY;
        ALTER TABLE import_refs FORCE ROW LEVEL SECURITY;
    END IF;
END
$$;

-- The retail_v1 loader reads a run's own records by type for each branch's opening journal (the stock the run put on
-- hand, what it already journalled), and the reconciliation reads them for its books. A plain index, built in this
-- transaction under the lock_timeout above: import_refs holds only what imports registered, so it is small, and its
-- build blocks only writes to import_refs, which only an import makes.
CREATE INDEX import_refs_by_run ON import_refs (tenant_id, run_id, target_type);
