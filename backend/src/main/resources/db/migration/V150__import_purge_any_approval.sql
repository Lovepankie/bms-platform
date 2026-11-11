-- V150: customer data onboarding 13/16 follow-ups, the purge reaches every approval of the run (issue #887, review of #886,
-- ADR-027 decision 8, SDD chapter 13 section 13.14). Directly after V149. Expand only, safe on a populated database: one
-- function replaced by its own body with its approval statement widened (below). No table, column, index or grant changes.
--
-- `import_purge_run(run)` (V147) hashed the checker's note and the failed execution's words of the run's `import_commit`
-- request only. Any approval request whose subject is the run is the run's typed text whatever its action type (a trading
-- override approved by a second operator, spec #127, or any later import action): the purge now hashes `decision_note` and
-- `execution_error` of every request of the business with `subject_id = run`. A run's id is a fresh random uuid, so no
-- request of another subject shares it. The payload of every such request but `import_commit` is hashed too (review of
-- #907, MF1): only `import_commit`'s payload is known to hold ids, a count and a fixed notice (ImportRunService), and an
-- action added later may carry a typed reason for its checker. Today `import_commit` is the only approval action whose
-- subject is an import run, and none has a batch as its subject (ImportPurgeIT asserts both). A request of the run still
-- pending is cancelled first (review of #907, MF2): ApprovalService writes a note or an execution error only on a pending
-- request, so none is typed on a purged run afterwards; the function returns how many it cancelled, and the purge's audit
-- row says so. The status of a decided request, the people and the times stay. Every other statement of the function is
-- V147's, unchanged. Without the
-- action type no index has subject_id after tenant_id, so the update reads the business's requests through the tenant prefix
-- of approval_requests_queue: once per purge, over one business's requests.
--
-- Personal data, the one rule restated for the reviewer (Lead on #886, review of #886 nice-to-haves); ImportPurgeIT reads
-- this header and fails when a column it keeps readable is not named here with its reason.
-- Hashed or cleared by the purge: every free-text column of the run's tables, as V147 lists them, and of every approval
--   request whose subject is the run (decision_note, execution_error, and payload unless it is import_commit's).
-- Kept readable, each for one reason:
--   import_runs.pre_import_dump_file, import_batches.source_filename: the certificate names the files and the dump it deletes
--     (spec section 6.4); their sizes and SHA-256 (import_batches.source_sha256, import_runs.pre_import_dump_sha256) are
--     hashes, so codes, not kept text.
--   import_batches.source_headers: the sheet's column names, which the business's mapping document also holds.
--   import_refs.source_key, import_refs.source_ref: the file, tab and reference code a later run matches its rows on.
--   import_reconciliations.report: the signed document, codes, counts and amounts only.
--   import_runs.dry_run_report, import_batches.report, import_rehearsals.report, import_rehearsals.reconciliation: made by
--     the application and the restore script, codes, counts and amounts only.
--   import_deletion_certificates.body, import_deletion_certificates.document: the certificate itself, which names the
--     platform operator, the witness and the files, never a customer's person.
--   approval_requests.payload: of the run's import_commit request only, the run's id, its file count and the
--     application's fixed notice; every other request's payload whose subject is the run is hashed.
-- The audit rows hold the length of a trading override's reason (reason_length) and of a policy's confirmation
-- (confirmation_length), never the text: a length says nothing of a person (ADR-027 decision 8).
--
-- Locks, said plainly: CREATE OR REPLACE FUNCTION takes no table lock, and keeps the function's owner and grants (EXECUTE to
-- bms_app only, V147). Nothing is built CONCURRENTLY. The lock_timeout below bounds any wait on the catalogue.

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION import_purge_run(p_run uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant      uuid := nullif(current_setting('app.tenant_id', true), '')::uuid;
    v_status      text;
    v_salt        bytea := decode(replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''), 'hex');
    v_rows        integer;
    v_issues      integer;
    v_resolutions integer;
    v_cancelled   integer;
BEGIN
    IF v_tenant IS NULL THEN
        RAISE EXCEPTION 'an import run is purged inside its own business' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT status INTO v_status FROM import_runs WHERE tenant_id = v_tenant AND id = p_run FOR UPDATE;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'no import run % in this business', p_run USING ERRCODE = 'no_data_found';
    END IF;
    IF v_status IN ('purged', 'committing') THEN
        RAISE EXCEPTION 'an import run that is % is not purged', v_status
            USING ERRCODE = 'check_violation', CONSTRAINT = 'import_purge_run_state';
    END IF;
    -- The application deletes the files first and clears their keys; a run still naming one is not purged here.
    IF EXISTS (SELECT 1 FROM import_batches WHERE tenant_id = v_tenant AND run_id = p_run AND object_key IS NOT NULL) THEN
        RAISE EXCEPTION 'import_purge_run: a file of the run is still in storage; delete it first'
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM set_config('bms.purging_import_run', p_run::text, true);
    -- The run's own operator free text goes too (review of #886, round 3): the trading override's reason and each policy
    -- decision's confirmation reference become their hash, as a resolution's note does.
    UPDATE import_runs
       SET status = 'purged', purged_at = now(), data_key_wrapped = NULL, version = version + 1, updated_at = now(),
           allow_trading_reason = CASE WHEN allow_trading_reason IS NULL THEN NULL
                                       ELSE 'sha256:' || encode(sha256(v_salt || convert_to(allow_trading_reason, 'UTF8')),
                                                                'hex') END,
           policy = coalesce((SELECT jsonb_object_agg(
                                         e.key,
                                         CASE WHEN jsonb_typeof(e.value -> 'confirmation') = 'string'
                                              THEN jsonb_set(e.value, '{confirmation}', to_jsonb('sha256:' || encode(
                                                       sha256(v_salt || convert_to(e.value ->> 'confirmation', 'UTF8')),
                                                       'hex')))
                                              ELSE e.value END)
                                FROM jsonb_each(policy) AS e), '{}'::jsonb)
     WHERE tenant_id = v_tenant AND id = p_run;

    UPDATE import_rows w
       SET raw = '{}'::jsonb,
           normalised = NULL,
           source_ref = CASE WHEN w.source_ref IS NULL THEN NULL
                             ELSE 'sha256:' || encode(sha256(v_salt || convert_to(w.source_ref, 'UTF8')), 'hex') END
      FROM import_batches b
     WHERE w.tenant_id = v_tenant AND b.tenant_id = v_tenant AND b.id = w.batch_id AND b.run_id = p_run;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- An issue keeps its code, severity, field, line and status; its original cell, its suggestion and its message go.
    UPDATE import_issues i
       SET original_value = NULL,
           suggested_value = NULL,
           message = 'sha256:' || encode(sha256(v_salt || convert_to(i.message, 'UTF8')), 'hex')
      FROM import_batches b
     WHERE i.tenant_id = v_tenant AND b.tenant_id = v_tenant AND b.id = i.batch_id AND b.run_id = p_run;
    GET DIAGNOSTICS v_issues = ROW_COUNT;

    -- A batch keeps the file's name, size and SHA-256 for the certificate; the tab's name and the failure's words go.
    UPDATE import_batches
       SET sheet_name = CASE WHEN sheet_name IS NULL THEN NULL
                             ELSE 'sha256:' || encode(sha256(v_salt || convert_to(sheet_name, 'UTF8')), 'hex') END,
           failure_reason = CASE WHEN failure_reason IS NULL THEN NULL
                                 ELSE 'sha256:' || encode(sha256(v_salt || convert_to(failure_reason, 'UTF8')), 'hex') END
     WHERE tenant_id = v_tenant AND run_id = p_run;

    -- The name the tenant administrator typed on the sign-off (Lead on #886: every typed text of the run).
    UPDATE import_signoffs
       SET signed_name = 'sha256:' || encode(sha256(v_salt || convert_to(signed_name, 'UTF8')), 'hex')
     WHERE tenant_id = v_tenant AND run_id = p_run;

    UPDATE import_issue_resolutions x
       SET before_value = CASE WHEN x.before_value IS NULL THEN NULL
                               ELSE jsonb_build_object('sha256', encode(sha256(v_salt
                                        || convert_to(x.before_value::text, 'UTF8')), 'hex')) END,
           after_value = CASE WHEN x.after_value IS NULL THEN NULL
                              ELSE jsonb_build_object('sha256', encode(sha256(v_salt
                                       || convert_to(x.after_value::text, 'UTF8')), 'hex')) END,
           row_key = 'sha256:' || encode(sha256(v_salt || convert_to(x.row_key, 'UTF8')), 'hex'),
           -- The operator's free text can name a person or hold a phone number (review of #886, MF1).
           note = CASE WHEN x.note IS NULL THEN NULL
                       ELSE 'sha256:' || encode(sha256(v_salt || convert_to(x.note, 'UTF8')), 'hex') END
     WHERE x.tenant_id = v_tenant AND x.run_id = p_run;
    GET DIAGNOSTICS v_resolutions = ROW_COUNT;

    -- A request of the run still pending is cancelled first (review of #907, MF2): a note or an execution error is
    -- written only on a pending request (ApprovalService), so none can be typed on a purged run afterwards.
    UPDATE approval_requests
       SET status = 'cancelled', decided_at = now(), updated_at = now(), version = version + 1
     WHERE tenant_id = v_tenant AND subject_id = p_run AND status = 'pending';
    GET DIAGNOSTICS v_cancelled = ROW_COUNT;

    -- The checker's note and the words of a failed execution of every approval request whose subject is the run, whatever
    -- its action type (review of #886, MF2; issue #887), and the payload of every one but import_commit, whose payload the
    -- application writes from ids, a count and a fixed notice (review of #907, MF1). approval_requests is not append-only,
    -- so no guard needs a way through; its status, people and times stay.
    UPDATE approval_requests
       SET decision_note = CASE WHEN decision_note IS NULL THEN NULL
                                ELSE 'sha256:' || encode(sha256(v_salt || convert_to(decision_note, 'UTF8')), 'hex') END,
           execution_error = CASE WHEN execution_error IS NULL THEN NULL
                                  ELSE 'sha256:' || encode(sha256(v_salt || convert_to(execution_error, 'UTF8')), 'hex')
                                  END,
           payload = CASE WHEN action_type = 'import_commit' THEN payload
                          ELSE jsonb_build_object('sha256', encode(sha256(v_salt || convert_to(payload::text, 'UTF8')),
                                                                   'hex')) END
     WHERE tenant_id = v_tenant AND subject_id = p_run;

    PERFORM set_config('bms.purging_import_run', '', true);
    RETURN jsonb_build_object('rows', v_rows, 'issues', v_issues, 'resolutions', v_resolutions,
                              'approvals_cancelled', v_cancelled);
END
$$;
