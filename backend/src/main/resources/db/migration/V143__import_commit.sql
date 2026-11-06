-- V143: customer data onboarding 7/16, the commit (issue #133, ADR-027 decisions 4 and 7, spec
-- docs/specs/customer-data-onboarding.md sections 3.7, 3.8 and 10.2, FR-IMP-07, SDD chapter 13 sections 13.10 and 13.14).
-- Directly after V142. Expand only, safe on a populated database: new nullable columns and named CHECKs on import_runs, the
-- commit order of import_batches, one new table, one trigger function and four triggers. Import runs are not yet reachable from a screen, so import_runs holds
-- few rows on every server; its new CHECKs are validated by a scan of those rows.
--
-- `import_runs` gains:
--   * the `import_commit` approval (chapter 8 section 8.4, ADR-015): who asked for it and when, the approval request, who
--     approved it and when, and the run's version just after the approval. The commit starts only while the run is still at
--     that version, so a change after the approval (a new dump, a trading override) needs a new approval. The CHECK
--     `import_runs_commit_checker_not_maker` refuses a checker who is the maker, whatever the application does, besides
--     `approval_checker_is_not_maker` on the request itself (V2).
--   * the verification of the pre-import dump: when it was checked and how many entries `pg_restore --list` listed. No
--     verified dump, no commit (spec section 3.11).
--
-- `import_refs` generalises `retail_import_refs` (V20) to every template: one row per record a commit created, by its
-- `source_ref` (the source file, its tab, the row and a hash of the row's mapped content, made by one framework function,
-- `ImportRefs.sourceRef`) and the target's type and id, written in the transaction of the batch that created the record.
-- `source_key` is the reference without the content hash, so a row already imported with other content is found
-- (CHANGED_SINCE_IMPORT) and never written twice. The opening journals of a run are registered here in the transaction that
-- posts them, so a resume never posts one twice. Append-only, under forced row-level security, read and inserted by bms_app.
-- `retail_import_refs` stays the `import-retail` command's own table until the retail template of #135 replaces the command.
--
-- No side effects from imported records (spec section 3.7): the batch transaction sets `app.import_commit` to 'on' with
-- SET LOCAL, and a trigger refuses, while it is set, any new row of `notification_outbox` (emails, Telegram, SMS),
-- `sms_deliveries`, `approval_requests` and `scheduled_tasks` (db-scheduler's tasks, the workflows). The setting dies with
-- the transaction; no other transaction sees it.
--
-- Personal data: none. A source_ref holds a file and tab name from the mapping, a line number or the row's reference code,
-- and a hash; no name, phone or ID number.
--
-- Locks, said plainly: ADD COLUMN without a default is a catalogue change under ACCESS EXCLUSIVE on import_runs; the CHECKs
-- scan its few rows under the same lock; CREATE TRIGGER takes SHARE ROW EXCLUSIVE on each guarded table until the commit,
-- milliseconds, and waits for in-flight writes behind the lock_timeout below. The new table's indexes are plain indexes on an
-- empty table.

SET LOCAL lock_timeout = '5s';

ALTER TABLE import_runs
    -- The staff user who requested import_commit (the maker), as approval_requests.requested_by.
    ADD COLUMN commit_requested_by         uuid,
    ADD COLUMN commit_requested_at         timestamptz,
    ADD COLUMN commit_approval_id          uuid,
    -- The staff user who approved it (the checker), as approval_requests.decided_by.
    ADD COLUMN commit_approved_by          uuid,
    ADD COLUMN commit_approved_at          timestamptz,
    -- The run's version just after the approval: the commit starts only at this version.
    ADD COLUMN commit_approved_version     integer,
    ADD COLUMN pre_import_dump_verified_at timestamptz,
    -- The number of entries `pg_restore --list` listed for the dump: a dump that lists nothing is no dump.
    ADD COLUMN pre_import_dump_entries     integer      CHECK (pre_import_dump_entries > 0),
    ADD CONSTRAINT import_runs_commit_request_whole
        CHECK ((commit_requested_by IS NULL) = (commit_requested_at IS NULL)),
    ADD CONSTRAINT import_runs_commit_approval_whole
        CHECK ((commit_approved_by IS NULL) = (commit_approval_id IS NULL)
               AND (commit_approved_by IS NULL) = (commit_approved_at IS NULL)
               AND (commit_approved_by IS NULL) = (commit_approved_version IS NULL)
               AND (commit_approved_by IS NULL OR commit_requested_by IS NOT NULL)),
    -- The checker is never the maker (AGENTS.md rule 4, FR-APR-02). Named, so a refusal says which rule it broke.
    ADD CONSTRAINT import_runs_commit_checker_not_maker
        CHECK (commit_approved_by IS NULL OR commit_approved_by <> commit_requested_by),
    ADD CONSTRAINT import_runs_dump_verified_whole
        CHECK ((pre_import_dump_verified_at IS NULL) = (pre_import_dump_entries IS NULL)
               AND (pre_import_dump_verified_at IS NULL OR pre_import_dump_file IS NOT NULL));

-- A committed batch's place in its template's dependency order, the index of its entity set (0, 1, ...): the run's report
-- lists its parts by it, then by `seq`, as the commit wrote them, and not by the upload order or a time (Lead ruling 10
-- on #872). NULL until the batch commits.
ALTER TABLE import_batches
    ADD COLUMN dependency_rank integer CHECK (dependency_rank >= 0);

CREATE TABLE import_refs (
    id          uuid         NOT NULL PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenants (id),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    run_id      uuid         NOT NULL,
    batch_id    uuid         NOT NULL,
    -- The source file, tab and row, without the content hash: what a re-run of the same row matches on.
    source_key  varchar(330) NOT NULL CHECK (btrim(source_key) <> ''),
    -- source_key, '#', and the hash of the row's mapped content.
    source_ref  varchar(400) NOT NULL,
    -- What was created, for example `retail.product` or `ledger.journal_entry`: a code, never data.
    target_type varchar(60)  NOT NULL CHECK (target_type ~ '^[a-z][a-z0-9_.]{1,59}$'),
    target_id   uuid         NOT NULL,
    CONSTRAINT import_refs_ref_of_key CHECK (left(source_ref, length(source_key) + 1) = source_key || '#'
                                             AND length(source_ref) > length(source_key) + 1),
    -- One row of a source may create several records of one type (a loan's collateral items): each is registered once.
    UNIQUE (tenant_id, source_ref, target_type, target_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES import_runs (tenant_id, id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES import_batches (tenant_id, id)
);
-- The validation looks up the keys of one source file and tab by prefix; the commit looks up one key.
CREATE INDEX import_refs_by_key ON import_refs (tenant_id, source_key text_pattern_ops);
-- "Was this record created by an import?": the trading guard and the reconciliation ask it of a journal.
CREATE INDEX import_refs_by_target ON import_refs (tenant_id, target_id);
SELECT bms_apply_tenant_rls('import_refs');
SELECT bms_grant_app('import_refs', 'SELECT, INSERT');
SELECT bms_make_append_only('import_refs');

-- Refuses a message, a task or an approval request made inside an import batch's transaction.
CREATE FUNCTION bms_refuse_during_import_commit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF coalesce(current_setting('app.import_commit', true), '') = 'on' THEN
        RAISE EXCEPTION 'an import batch may not add a row to %: imported records send no message, start no task and'
                        ' raise no approval', TG_TABLE_NAME
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
REVOKE ALL ON FUNCTION bms_refuse_during_import_commit() FROM PUBLIC;

CREATE TRIGGER refuse_during_import_commit BEFORE INSERT ON notification_outbox
    FOR EACH ROW EXECUTE FUNCTION bms_refuse_during_import_commit();
CREATE TRIGGER refuse_during_import_commit BEFORE INSERT ON sms_deliveries
    FOR EACH ROW EXECUTE FUNCTION bms_refuse_during_import_commit();
CREATE TRIGGER refuse_during_import_commit BEFORE INSERT ON approval_requests
    FOR EACH ROW EXECUTE FUNCTION bms_refuse_during_import_commit();
CREATE TRIGGER refuse_during_import_commit BEFORE INSERT ON scheduled_tasks
    FOR EACH ROW EXECUTE FUNCTION bms_refuse_during_import_commit();
