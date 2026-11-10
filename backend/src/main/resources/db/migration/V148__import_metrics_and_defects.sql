-- V148: customer data onboarding 14/16, the metrics of a run and the post-cutover defect log (issue #140, ADR-027, spec
-- docs/specs/customer-data-onboarding.md section 8, SDD chapter 13 section 13.14.1, chapter 14 section 14.6). Directly
-- after V147. Expand only, safe on a populated database: six new nullable columns, one new table with its guard, four new
-- functions, one new trigger on import_runs, and imports_erase_personal_data replaced by its own body with one more table.
--
-- Metrics come from stored run data, never from logs. Most of what spec section 8 asks for is stored already (the stage
-- stamps of V127, the batches' counts and report parts of V142, the issues, the resolutions, the mappings, the rehearsals);
-- what was not stored is the time each pipeline step took, so the application now writes it where the step ends:
--   import_batches.upload_ms   the upload request from its first byte to the stored batch (UploadService)
--   import_batches.parse_ms    reading the stored file into import_rows (UploadService)
--   import_batches.commit_ms   the batch's commit transaction, written with its counts (ImportRunService)
--   import_runs.validated_at, validate_ms   the run's last full validation (ValidationService)
--   import_runs.dry_run_ms     the last stored dry run (ImportRunService)
-- All NULL for what ran before this migration; the metrics leave such a step out.
--
-- `import_run_metrics(run)` computes the metrics of one run as one JSON object. It is a plain (invoker) function: called
-- by bms_app in a business's transaction it reads under forced row security, so another business's run is not found
-- (NULL). It reads only columns the purge of V147 keeps (codes, counts, statuses, ids, timestamps and the reports), so a
-- purged run has the same metrics as before its purge: issues by code, severity and status; resolutions by kind;
-- questions from import_issues.ask_customer and status with the times of the resolutions (never their values, which the
-- purge hashes); rows from row_count, the batches' report parts and import_rows.excluded_by.
--
-- `platform_import_runs_report(since, limit)` is the platform report across businesses: a definer function that refuses a
-- call bound to a business (the platform operator's route alone calls it), returns each run's business by its slug only
-- (never its name) with the run's metrics, newest first.
--
-- `import_defects`: the defect log of a run during hypercare (spec section 2 stage 10 and section 8): a problem found after
-- the cutover, the issue code it should have had (a code of the run's issue-code catalogue, checked by the application;
-- or none and new_code_needed, the code proposed in the description, Lead on #888), and once fixed the fix's reference. Logged and closed by a holder of core.imports.manage, both audited by the application. A
-- defect is logged only against an imported run (committed, signed off or purged), is logged open, closes once with its
-- fix reference, who and when, and never opens again or changes its run, code, author or time (trigger
-- import_defects_kept). bms_app may update only status, fix_ref, closed_by, closed_at and version (a column grant).
--
-- Personal data. The description is the operator's free text, so it follows the V147 rule for every free-text column of a
-- run (Lead on #886): it is hashed with a salt kept nowhere when the run is purged (trigger import_defects_purged, inside
-- import_purge_run's transaction), open or closed, and a defect logged against a run already purged is stored with its
-- description hashed at once (import_defects_kept), so a purged run never holds a defect's words. Before that the application
-- refuses a description that names a personal value of the run's staged rows or holds a phone-like run of digits or an
-- email address (MetricsService). The removal of a closed business writes "Removed" (imports_erase_personal_data, below).
-- expected_code is only ever a code of the platform's own issue-code catalogue (the application refuses any other, so
-- no business types it), which is why the purge keeps it and the platform report shows it. fix_ref is held by a CHECK to
-- an issue or pull request number, a commit hash or a GitHub link.
--
-- Locks, said plainly. Each ADD COLUMN takes ACCESS EXCLUSIVE on its table for milliseconds (nullable, no default, no
-- rewrite; the CHECKs scan the import tables, which are small); the new table takes SHARE ROW EXCLUSIVE on `tenants` and
-- `import_runs` (its foreign keys) until the commit, reads go on; the new trigger takes SHARE ROW EXCLUSIVE on
-- `import_runs`. CREATE OR REPLACE FUNCTION takes no table lock. The only indexes are the new table's keys and one plain
-- index, built empty inside Flyway's transaction (never CONCURRENTLY), behind the lock_timeout below.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_batches
    ADD COLUMN upload_ms integer CHECK (upload_ms >= 0),
    ADD COLUMN parse_ms  integer CHECK (parse_ms >= 0),
    ADD COLUMN commit_ms integer CHECK (commit_ms >= 0);

ALTER TABLE import_runs
    ADD COLUMN validated_at timestamptz,
    ADD COLUMN validate_ms  integer CHECK (validate_ms >= 0),
    ADD COLUMN dry_run_ms   integer CHECK (dry_run_ms >= 0);

CREATE TABLE import_defects (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    version       integer      NOT NULL DEFAULT 0,
    run_id        uuid         NOT NULL,
    -- The member of staff who logged it.
    logged_by     uuid         NOT NULL,
    -- What went wrong, without personal data (see the header); its hash once the run is purged.
    description   varchar(500) NOT NULL CHECK (btrim(description) <> ''),
    -- The issue code the row should have had: a code of the issue-code catalogue of the run's template, which the
    -- application checks when the defect is logged (Lead on #888: never a code the business typed). NULL when the code
    -- does not exist yet: then new_code_needed is true and the code the operator proposes goes in the description.
    expected_code text         CHECK (expected_code ~ '^[A-Z][A-Z0-9_]{1,59}$'),
    new_code_needed boolean    NOT NULL,
    status        text         NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed')),
    -- The fix: an issue or pull request number (#123), a commit hash, or a GitHub link to one.
    fix_ref       varchar(200) CHECK (fix_ref ~ '^(#[0-9]{1,7}|[0-9a-f]{7,40}|https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(pull|issues|commit)/[0-9a-f]{1,40})$'),
    closed_by     uuid,
    closed_at     timestamptz,
    UNIQUE (tenant_id, id),
    -- A catalogue code, or none and a new code needed: never both, never neither.
    CONSTRAINT import_defects_code_or_new CHECK ((expected_code IS NULL) = new_code_needed),
    CONSTRAINT import_defects_closed_whole
        CHECK ((status = 'closed') = (closed_at IS NOT NULL)
               AND (closed_at IS NULL) = (closed_by IS NULL)
               AND (closed_at IS NULL) = (fix_ref IS NULL)),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id)
);
SELECT bms_apply_tenant_rls('import_defects');
SELECT bms_grant_app('import_defects', 'SELECT, INSERT');
GRANT UPDATE (status, fix_ref, closed_by, closed_at, version) ON import_defects TO bms_app;
CREATE INDEX import_defects_by_run ON import_defects (tenant_id, run_id, created_at);

-- The rules of a defect (see the header), for every role. A migration's opt-in and the removal of a closed business keep
-- V102's ways through.
CREATE FUNCTION import_defects_kept() RETURNS trigger
LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    v_purged boolean;
    v_salt   bytea;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM import_runs r
                        WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.run_id
                          AND r.status IN ('committed', 'signed_off', 'purged')) THEN
            RAISE EXCEPTION 'a defect is logged only against an imported run (committed, signed off or purged)'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'import_defects_run_imported';
        END IF;
        IF NEW.status <> 'open' THEN
            RAISE EXCEPTION 'a defect is logged open and closed later with its fix'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'import_defects_logged_open';
        END IF;
        -- Logged against a run already purged: the description is stored as its hash at once, so a purged run never
        -- holds a defect's words, open or closed (review of #888). The code and, later, the fix reference stay.
        IF EXISTS (SELECT 1 FROM import_runs r
                    WHERE r.tenant_id = NEW.tenant_id AND r.id = NEW.run_id AND r.status = 'purged')
           AND NEW.description !~ '^sha256:[0-9a-f]{64}$' THEN
            v_salt := decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex');
            NEW.description := 'sha256:' || encode(sha256(v_salt || convert_to(NEW.description, 'UTF8')), 'hex');
        END IF;
        RETURN NEW;
    END IF;
    IF (session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on')
       OR bms_erasing(to_jsonb(OLD)) THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status', 'fix_ref', 'closed_by', 'closed_at', 'version', 'description'])
           <> (to_jsonb(OLD) - ARRAY['status', 'fix_ref', 'closed_by', 'closed_at', 'version', 'description']) THEN
        RAISE EXCEPTION 'a defect keeps its run, code, author and time'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_defects_kept';
    END IF;
    SELECT r.status = 'purged' INTO v_purged FROM import_runs r WHERE r.tenant_id = OLD.tenant_id AND r.id = OLD.run_id;
    IF NEW.description IS DISTINCT FROM OLD.description THEN
        -- Only the hash import_defects_purged writes as the owner, of a purged run's defect, and nothing else with it.
        IF NOT coalesce(v_purged, false) OR current_user <> 'bms_owner'
           OR NEW.description !~ '^sha256:[0-9a-f]{64}$'
           OR (to_jsonb(NEW) - 'description') <> (to_jsonb(OLD) - 'description') THEN
            RAISE EXCEPTION 'a defect''s description changes only to its hash when its run is purged'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'import_defects_kept';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'closed' THEN
        RAISE EXCEPTION 'a closed defect stays closed with its fix'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_defects_kept';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER import_defects_kept BEFORE INSERT OR UPDATE ON import_defects
    FOR EACH ROW EXECUTE FUNCTION import_defects_kept();

-- When a run becomes purged (only inside import_purge_run, V147), the descriptions of its defects become their hash, with a
-- salt made here and kept nowhere, in the purge's own transaction.
CREATE FUNCTION import_defects_purged() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_salt bytea := decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex');
BEGIN
    UPDATE import_defects d
       SET description = 'sha256:' || encode(sha256(v_salt || convert_to(d.description, 'UTF8')), 'hex')
     WHERE d.tenant_id = NEW.tenant_id AND d.run_id = NEW.id AND d.description !~ '^sha256:[0-9a-f]{64}$';
    RETURN NULL;
END
$$;
REVOKE ALL ON FUNCTION import_defects_purged() FROM PUBLIC;

CREATE TRIGGER import_defects_purged AFTER UPDATE OF status ON import_runs
    FOR EACH ROW WHEN (NEW.status = 'purged' AND OLD.status IS DISTINCT FROM 'purged')
    EXECUTE FUNCTION import_defects_purged();

-- The removal of a closed business (ADR-090): the V144 body, and every defect's description becomes "Removed" as well.
CREATE OR REPLACE FUNCTION imports_erase_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant  uuid := bms_removing_tenant();
    v_rows    integer;
    v_defects integer;
BEGIN
    UPDATE import_signoffs SET signed_name = 'Removed' WHERE tenant_id = v_tenant AND signed_name <> 'Removed';
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    UPDATE import_defects SET description = 'Removed' WHERE tenant_id = v_tenant AND description <> 'Removed';
    GET DIAGNOSTICS v_defects = ROW_COUNT;
    RETURN v_rows + v_defects;
END
$$;

-- A moment as the metrics write it: UTC, ISO 8601, microseconds, or NULL.
CREATE FUNCTION bms_import_utc(p timestamptz) RETURNS text
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT CASE WHEN p IS NULL THEN NULL
                ELSE to_char(p AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END
$$;
REVOKE ALL ON FUNCTION bms_import_utc(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_utc(timestamptz) TO bms_app;

-- One stage of spec section 2 as the metrics write it: its number, key, start, end and seconds (NULL unless both ends are
-- known and the end is not before the start).
CREATE FUNCTION bms_import_stage(p_number integer, p_key text, p_start timestamptz, p_end timestamptz) RETURNS jsonb
LANGUAGE sql STABLE SET search_path = public, pg_temp AS $$
    SELECT jsonb_build_object(
               'number', p_number,
               'key', p_key,
               'started_at', bms_import_utc(p_start),
               'ended_at', bms_import_utc(p_end),
               'seconds', CASE WHEN p_start IS NULL OR p_end IS NULL OR p_end < p_start THEN NULL
                               ELSE floor(extract(epoch FROM p_end - p_start))::bigint END)
$$;
REVOKE ALL ON FUNCTION bms_import_stage(integer, text, timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION bms_import_stage(integer, text, timestamptz, timestamptz) TO bms_app;

-- The metrics of one run (see the header), or NULL when no such run is visible. Every statement names the run's business.
CREATE FUNCTION import_run_metrics(p_run uuid) RETURNS jsonb
LANGUAGE plpgsql STABLE SET search_path = public, pg_temp AS $$
DECLARE
    v_r            import_runs%ROWTYPE;
    v_last         import_rehearsals%ROWTYPE;
    v_first_upload timestamptz;
    v_rehearsed    timestamptz;
    v_reconciled   timestamptz;
    v_hypercare    timestamptz;
    v_stages       jsonb;
    v_steps        jsonb;
    v_timings      jsonb;
    v_batches      jsonb;
    v_templates    jsonb;
    v_issues       jsonb;
    v_resolutions  jsonb;
    v_questions    jsonb;
    v_mappings     jsonb;
    v_made         bigint;
    v_rehearsals   jsonb;
    v_differences  jsonb := '[]'::jsonb;
    v_defects      jsonb;
BEGIN
    SELECT * INTO v_r FROM import_runs WHERE id = p_run;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    SELECT coalesce(v_r.uploaded_at, min(b.uploaded_at)) INTO v_first_upload
      FROM import_batches b WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id;
    SELECT max(h.created_at) INTO v_rehearsed
      FROM import_rehearsals h
     WHERE h.tenant_id = v_r.tenant_id AND h.run_id = v_r.id
       AND (v_r.committing_at IS NULL OR h.created_at <= v_r.committing_at);
    SELECT min(c.created_at) INTO v_reconciled
      FROM import_reconciliations c WHERE c.tenant_id = v_r.tenant_id AND c.run_id = v_r.id;
    -- Hypercare ends when every defect logged is closed (none logged: not recorded).
    SELECT CASE WHEN count(*) > 0 AND bool_and(d.status = 'closed') THEN max(d.closed_at) END INTO v_hypercare
      FROM import_defects d WHERE d.tenant_id = v_r.tenant_id AND d.run_id = v_r.id;

    -- The stages of spec section 2. Discovery happens before the run opens and is not recorded on it.
    v_stages := jsonb_build_array(
        bms_import_stage(1, 'discovery', NULL, NULL),
        bms_import_stage(2, 'field_mapping', v_r.created_at, v_first_upload),
        bms_import_stage(3, 'cleaning_and_review', v_first_upload, v_r.ready_at),
        bms_import_stage(4, 'rehearsal', v_r.ready_at, v_rehearsed),
        bms_import_stage(5, 'dry_run', coalesce(v_rehearsed, v_r.ready_at), v_r.dry_run_at),
        bms_import_stage(6, 'cutover', v_r.committing_at, v_r.committed_at),
        bms_import_stage(7, 'reconciliation', v_r.committed_at, v_r.signed_off_at),
        bms_import_stage(8, 'sign_off', v_r.signed_off_at, v_r.signed_off_at),
        bms_import_stage(9, 'source_deletion', v_r.signed_off_at, v_r.purged_at),
        bms_import_stage(10, 'hypercare', v_r.signed_off_at, v_hypercare));

    -- The pipeline steps as stored where each ended: upload and parse per batch, the run's last validation and dry run,
    -- and the commit per batch.
    SELECT coalesce(jsonb_agg(x.j ORDER BY x.ord, x.seq), '[]'::jsonb) INTO v_steps
      FROM (SELECT 1 AS ord, b.seq,
                   jsonb_build_object('step', 'upload', 'batch_seq', b.seq, 'millis', b.upload_ms) AS j
              FROM import_batches b
             WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id AND b.upload_ms IS NOT NULL
            UNION ALL
            SELECT 2, b.seq, jsonb_build_object('step', 'parse', 'batch_seq', b.seq, 'millis', b.parse_ms)
              FROM import_batches b
             WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id AND b.parse_ms IS NOT NULL
            UNION ALL
            SELECT 3, 0, jsonb_build_object('step', 'validate', 'batch_seq', NULL::integer, 'millis', v_r.validate_ms)
             WHERE v_r.validate_ms IS NOT NULL
            UNION ALL
            SELECT 4, 0, jsonb_build_object('step', 'dry_run', 'batch_seq', NULL::integer, 'millis', v_r.dry_run_ms)
             WHERE v_r.dry_run_ms IS NOT NULL
            UNION ALL
            SELECT 5, b.seq, jsonb_build_object('step', 'commit', 'batch_seq', b.seq, 'millis', b.commit_ms)
              FROM import_batches b
             WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id AND b.status = 'committed'
               AND b.commit_ms IS NOT NULL) x;

    -- Rows by outcome per batch: the rows read from the file, those left out in the review, and the commit's counts of
    -- the batch's report part (NULL until the batch is committed).
    SELECT coalesce(jsonb_agg(jsonb_build_object(
               'seq', b.seq,
               'status', b.status,
               'template_key', b.template_key,
               'template_version', b.template_version,
               'mapping_source', b.mapping_source,
               'rows', b.row_count,
               'excluded', (SELECT count(*) FROM import_rows w
                             WHERE w.tenant_id = b.tenant_id AND w.batch_id = b.id AND w.excluded_by IS NOT NULL),
               'read', s.read_n,
               'written', s.written_n,
               'existing', s.existing_n,
               'skipped', s.skipped_n) ORDER BY b.seq), '[]'::jsonb) INTO v_batches
      FROM import_batches b
      LEFT JOIN LATERAL (
          SELECT sum((e ->> 'read')::bigint) AS read_n, sum((e ->> 'written')::bigint) AS written_n,
                 sum((e ->> 'existing')::bigint) AS existing_n, sum((e ->> 'skipped')::bigint) AS skipped_n
            FROM jsonb_array_elements(CASE WHEN b.status = 'committed' AND jsonb_typeof(b.report -> 'sources') = 'array'
                                           THEN b.report -> 'sources' ELSE '[]'::jsonb END) e) s ON true
     WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id;

    -- And per template, over the batches not failed or cancelled.
    SELECT coalesce(jsonb_agg(jsonb_build_object(
               'template_key', t.k, 'template_version', t.v, 'batches', t.batches, 'rows', t.rows_n,
               'excluded', t.excluded_n, 'read', t.read_n, 'written', t.written_n, 'existing', t.existing_n,
               'skipped', t.skipped_n) ORDER BY t.k, t.v), '[]'::jsonb) INTO v_templates
      FROM (SELECT x ->> 'template_key' AS k, (x ->> 'template_version')::integer AS v, count(*) AS batches,
                   sum((x ->> 'rows')::bigint) AS rows_n, sum((x ->> 'excluded')::bigint) AS excluded_n,
                   sum((x ->> 'read')::bigint) AS read_n, sum((x ->> 'written')::bigint) AS written_n,
                   sum((x ->> 'existing')::bigint) AS existing_n, sum((x ->> 'skipped')::bigint) AS skipped_n
              FROM jsonb_array_elements(v_batches) x
             WHERE x ->> 'template_key' IS NOT NULL AND x ->> 'status' NOT IN ('failed', 'cancelled')
             GROUP BY 1, 2) t;

    -- Issues by code and severity, with how many are still open.
    SELECT coalesce(jsonb_agg(jsonb_build_object('code', g.code, 'severity', g.severity, 'total', g.total_n,
                                                 'open', g.open_n) ORDER BY g.code, g.severity), '[]'::jsonb)
      INTO v_issues
      FROM (SELECT i.code, i.severity, count(*) AS total_n, count(*) FILTER (WHERE i.status = 'open') AS open_n
              FROM import_issues i JOIN import_batches b ON b.tenant_id = i.tenant_id AND b.id = i.batch_id
             WHERE i.tenant_id = v_r.tenant_id AND b.run_id = v_r.id
             GROUP BY i.code, i.severity) g;

    -- How the review resolved them, by kind.
    SELECT coalesce(jsonb_agg(jsonb_build_object('kind', g.resolution, 'count', g.n) ORDER BY g.resolution), '[]'::jsonb)
      INTO v_resolutions
      FROM (SELECT x.resolution, count(*) AS n FROM import_issue_resolutions x
             WHERE x.tenant_id = v_r.tenant_id AND x.run_id = v_r.id GROUP BY x.resolution) g;

    -- Questions to the customer: the issues marked to ask; asked at the first ask_customer resolution of the same line,
    -- code and field of the same source, answered when the issue was resolved, at that resolution's time.
    SELECT jsonb_build_object(
               'asked', count(*),
               'answered', count(*) FILTER (WHERE q.answered_at IS NOT NULL),
               'open', count(*) FILTER (WHERE q.answered_at IS NULL),
               'median_days', round(CAST(percentile_cont(0.5) WITHIN GROUP (ORDER BY q.days) AS numeric), 1),
               'max_days', round(CAST(max(q.days) AS numeric), 1))
      INTO v_questions
      FROM (SELECT y.answered_at,
                   CASE WHEN y.answered_at IS NOT NULL AND y.asked_at IS NOT NULL AND y.answered_at >= y.asked_at
                        THEN CAST(extract(epoch FROM y.answered_at - y.asked_at) AS double precision) / 86400 END AS days
              FROM (SELECT CASE WHEN i.status = 'resolved' THEN res.created_at END AS answered_at,
                           (SELECT min(a.created_at) FROM import_issue_resolutions a
                              JOIN import_batches ab ON ab.tenant_id = a.tenant_id AND ab.id = a.batch_id
                             WHERE a.tenant_id = v_r.tenant_id AND a.run_id = v_r.id
                               AND a.resolution = 'ask_customer' AND a.code = i.code
                               AND a.field IS NOT DISTINCT FROM i.field AND a.source_row_no = i.source_row_no
                               AND ab.mapping_source IS NOT DISTINCT FROM b.mapping_source) AS asked_at
                      FROM import_issues i
                      JOIN import_batches b ON b.tenant_id = i.tenant_id AND b.id = i.batch_id
                      LEFT JOIN import_issue_resolutions res ON res.tenant_id = i.tenant_id AND res.id = i.resolution_id
                     WHERE i.tenant_id = v_r.tenant_id AND b.run_id = v_r.id AND i.ask_customer) y) q;

    -- The mapping versions the run's batches were read with, and how many versions of those mappings were saved while
    -- the run was open (from its opening to its commit or cancel).
    SELECT coalesce(jsonb_agg(jsonb_build_object('name', m.name, 'version', m.version, 'template_key', m.template_key,
                                                 'template_version', m.template_version) ORDER BY m.name, m.version),
                    '[]'::jsonb)
      INTO v_mappings
      FROM import_mappings m
     WHERE m.tenant_id = v_r.tenant_id
       AND m.id IN (SELECT b.mapping_id FROM import_batches b WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id);
    SELECT count(*) INTO v_made
      FROM import_mappings m
     WHERE m.tenant_id = v_r.tenant_id
       AND m.name IN (SELECT m2.name FROM import_mappings m2
                        JOIN import_batches b ON b.tenant_id = m2.tenant_id AND b.mapping_id = m2.id
                       WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id)
       AND m.created_at > v_r.created_at
       AND m.created_at <= coalesce(v_r.committing_at, v_r.cancelled_at, now());

    -- The rehearsals, and the newest committed one against the cutover: each source's counts that differ.
    SELECT coalesce(jsonb_agg(jsonb_build_object('at', bms_import_utc(h.created_at), 'outcome', h.outcome,
                                                 'matches_dry_run', h.matches_dry_run) ORDER BY h.created_at, h.id),
                    '[]'::jsonb)
      INTO v_rehearsals
      FROM import_rehearsals h WHERE h.tenant_id = v_r.tenant_id AND h.run_id = v_r.id;
    SELECT * INTO v_last FROM import_rehearsals h
     WHERE h.tenant_id = v_r.tenant_id AND h.run_id = v_r.id AND h.outcome = 'committed'
     ORDER BY h.created_at DESC, h.id DESC LIMIT 1;
    SELECT coalesce(jsonb_agg(jsonb_build_object('step', e.key, 'millis', CAST(CAST(e.value AS text) AS numeric)::bigint)
                              ORDER BY e.key), '[]'::jsonb)
      INTO v_timings
      FROM jsonb_each(CASE WHEN jsonb_typeof(v_last.timings) = 'object' THEN v_last.timings ELSE '{}'::jsonb END) e
     WHERE jsonb_typeof(e.value) = 'number';
    IF v_last.id IS NOT NULL AND v_r.committed_at IS NOT NULL THEN
        WITH reh AS (
            SELECT e ->> 'name' AS source, sum((e ->> 'read')::bigint) AS read_n,
                   sum((e ->> 'written')::bigint) AS written_n, sum((e ->> 'existing')::bigint) AS existing_n,
                   sum((e ->> 'skipped')::bigint) AS skipped_n
              FROM jsonb_array_elements(CASE WHEN jsonb_typeof(v_last.report -> 'sources') = 'array'
                                             THEN v_last.report -> 'sources' ELSE '[]'::jsonb END) e
             GROUP BY 1),
        cut AS (
            SELECT e ->> 'name' AS source, sum((e ->> 'read')::bigint) AS read_n,
                   sum((e ->> 'written')::bigint) AS written_n, sum((e ->> 'existing')::bigint) AS existing_n,
                   sum((e ->> 'skipped')::bigint) AS skipped_n
              FROM import_batches b
             CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(b.report -> 'sources') = 'array'
                                                          THEN b.report -> 'sources' ELSE '[]'::jsonb END) e
             WHERE b.tenant_id = v_r.tenant_id AND b.run_id = v_r.id AND b.status = 'committed'
             GROUP BY 1),
        paired AS (
            SELECT coalesce(reh.source, cut.source) AS source,
                   reh.read_n AS r_read, reh.written_n AS r_written, reh.existing_n AS r_existing,
                   reh.skipped_n AS r_skipped, cut.read_n AS c_read, cut.written_n AS c_written,
                   cut.existing_n AS c_existing, cut.skipped_n AS c_skipped
              FROM reh FULL JOIN cut ON cut.source = reh.source)
        SELECT coalesce(jsonb_agg(jsonb_build_object('source', p.source, 'measure', m.measure,
                                                     'rehearsal', m.rehearsal_n, 'cutover', m.cutover_n)
                                  ORDER BY p.source, m.ord), '[]'::jsonb)
          INTO v_differences
          FROM paired p
         CROSS JOIN LATERAL (VALUES (1, 'read', p.r_read, p.c_read), (2, 'written', p.r_written, p.c_written),
                                    (3, 'existing', p.r_existing, p.c_existing),
                                    (4, 'skipped', p.r_skipped, p.c_skipped)) AS m(ord, measure, rehearsal_n, cutover_n)
         WHERE m.rehearsal_n IS DISTINCT FROM m.cutover_n;
    END IF;

    -- The defects logged against the run during hypercare, by the catalogue code they should have had, and how many need
    -- a code the catalogue does not have yet.
    SELECT jsonb_build_object(
               'open', count(*) FILTER (WHERE d.status = 'open'),
               'closed', count(*) FILTER (WHERE d.status = 'closed'),
               'new_codes_needed', count(*) FILTER (WHERE d.new_code_needed),
               'codes', coalesce((SELECT jsonb_agg(jsonb_build_object('code', c.expected_code, 'count', c.n)
                                                   ORDER BY c.expected_code)
                                    FROM (SELECT d2.expected_code, count(*) AS n FROM import_defects d2
                                           WHERE d2.tenant_id = v_r.tenant_id AND d2.run_id = v_r.id
                                             AND d2.expected_code IS NOT NULL
                                           GROUP BY d2.expected_code) c), '[]'::jsonb))
      INTO v_defects
      FROM import_defects d WHERE d.tenant_id = v_r.tenant_id AND d.run_id = v_r.id;

    RETURN jsonb_build_object(
        'run_id', v_r.id,
        'status', v_r.status,
        'origin', v_r.origin,
        'fabricated', v_r.fabricated,
        'stamps', jsonb_build_object(
            'created_at', bms_import_utc(v_r.created_at),
            'uploaded_at', bms_import_utc(v_r.uploaded_at),
            'in_review_at', bms_import_utc(v_r.in_review_at),
            'validated_at', bms_import_utc(v_r.validated_at),
            'ready_at', bms_import_utc(v_r.ready_at),
            'dry_run_at', bms_import_utc(v_r.dry_run_at),
            'committing_at', bms_import_utc(v_r.committing_at),
            'committed_at', bms_import_utc(v_r.committed_at),
            'reconciled_at', bms_import_utc(v_reconciled),
            'signed_off_at', bms_import_utc(v_r.signed_off_at),
            'purged_at', bms_import_utc(v_r.purged_at),
            'cancelled_at', bms_import_utc(v_r.cancelled_at)),
        'stages', v_stages,
        'steps', v_steps,
        'rehearsal_timings', v_timings,
        'batches', v_batches,
        'templates', v_templates,
        'issues', v_issues,
        'resolutions', v_resolutions,
        'questions', v_questions,
        'mappings', v_mappings,
        'mapping_versions_made', v_made,
        'rehearsals', v_rehearsals,
        'rehearsal_differences', v_differences,
        'defects', v_defects);
END
$$;
REVOKE ALL ON FUNCTION import_run_metrics(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION import_run_metrics(uuid) TO bms_app;

-- The metrics as the platform may see them (review of #888, N2): the business's own labels left out, here as well as in
-- the application, so no caller of the report can read them. A mapping's name, a batch's mapping source and a rehearsal
-- source's file name are typed by the business and may carry its trading name; only the slug names the business.
CREATE FUNCTION bms_import_platform_view(p jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE SET search_path = public, pg_temp AS $$
    SELECT p || jsonb_build_object(
        'mappings', coalesce((SELECT jsonb_agg(e.v || jsonb_build_object('name', NULL::text) ORDER BY e.n)
                                FROM jsonb_array_elements(p -> 'mappings') WITH ORDINALITY AS e(v, n)), '[]'::jsonb),
        'batches', coalesce((SELECT jsonb_agg(e.v || jsonb_build_object('mapping_source', NULL::text) ORDER BY e.n)
                               FROM jsonb_array_elements(p -> 'batches') WITH ORDINALITY AS e(v, n)), '[]'::jsonb),
        'rehearsal_differences',
            coalesce((SELECT jsonb_agg(e.v || jsonb_build_object('source', NULL::text) ORDER BY e.n)
                        FROM jsonb_array_elements(p -> 'rehearsal_differences') WITH ORDINALITY AS e(v, n)), '[]'::jsonb))
$$;
REVOKE ALL ON FUNCTION bms_import_platform_view(jsonb) FROM PUBLIC;

-- The platform report: every business's runs, newest first, each business by its slug only, with the run's metrics without
-- its labels. Read across businesses by the platform operator's route alone: a call bound to a business is refused.
CREATE FUNCTION platform_import_runs_report(p_since timestamptz, p_limit integer)
RETURNS TABLE (tenant_slug text, run_id uuid, metrics jsonb)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'import runs are summarised across businesses by the platform operator only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT CAST(t.slug AS text), r.id, bms_import_platform_view(import_run_metrics(r.id))
      FROM import_runs r
      JOIN tenants t ON t.id = r.tenant_id
     WHERE p_since IS NULL OR r.created_at >= p_since
     ORDER BY r.created_at DESC, r.id
     LIMIT least(greatest(coalesce(p_limit, 100), 1), 500);
END
$$;
REVOKE ALL ON FUNCTION platform_import_runs_report(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_import_runs_report(timestamptz, integer) TO bms_app;
