-- V91: download my data (issue #334 step 1, ADR-074). An ordered claim (AGENTS.md): V80 to V90 are merged, this file is
-- V91 (#478) and V92 (billing, #482) is claimed after it. Flyway runs with outOfOrder off, so if the order changes this file
-- is renumbered to the next free number at merge time.
--
-- Expand only, safe on a populated database. One permission, two new tenant tables, and a new function so that the files
-- of a business that is not active still expire. The runner's functions stay as V90 left them: a business ended by its
-- Owner's cancellation still gets its own workbook made, a business the operator suspended never does. No existing table,
-- column, constraint, function or row changes.
--
-- Locks, said plainly. Flyway runs this file in one transaction. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE
-- on tenants, users and report_runs until the commit (reads go on; the new tables are empty, so the cost is the wait for the
-- lock). CREATE FUNCTION only changes catalogue rows. lock_timeout is five seconds as in V26 and V80: if a lock
-- cannot be had at once the migration fails and is retried, rather than every writer queueing behind it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- data_export_settings (ADR-074): the business's choice about the weekly email of its workbook. No row means the default,
-- on. One row per business, changed in place by the Owner only (ADR-074 decision 2; another administrator is refused with
-- 403 owner_only); every change is audited.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE data_export_settings (
    id           uuid        NOT NULL PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenants (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz,
    weekly_email boolean     NOT NULL DEFAULT true,
    updated_by   uuid        NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES users (tenant_id, id)
);
SELECT bms_apply_tenant_rls('data_export_settings');
SELECT bms_grant_app('data_export_settings', 'SELECT, INSERT, UPDATE');

-- ---------------------------------------------------------------------------------------------
-- data_export_weekly (ADR-074): one row per business per week (the Monday that starts it), written by the weekly job when
-- it queues that week's workbook for the Owner, so a job that runs twice, or on two instances, queues one workbook and sends
-- one email (the unique key). A week while the switch is off gets no row, so turning it on mid-week sends that week's copy.
-- status: preparing (the run is being made), queued (the email is written to the outbox), emailed (the outbox recorded it
-- sent), skipped (no active Owner with an email address, so no run; or the email switched off after the week was queued),
-- failed (the run did not finish ready, or the outbox gave the email up). reason is a stable code, never a message.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE data_export_weekly (
    id                uuid        NOT NULL PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenants (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    week_of           date        NOT NULL CHECK (extract(isodow FROM week_of) = 1),
    run_id            uuid,
    recipient_user_id uuid,
    status            text        NOT NULL CHECK (status IN ('preparing', 'queued', 'emailed', 'skipped', 'failed')),
    attached          boolean,
    reason            text        CHECK (reason ~ '^[a-z][a-z0-9_]{2,60}$'),
    finished_at       timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, week_of),
    FOREIGN KEY (tenant_id, run_id) REFERENCES report_runs (tenant_id, id),
    FOREIGN KEY (tenant_id, recipient_user_id) REFERENCES users (tenant_id, id),
    CHECK (status = 'skipped' OR run_id IS NOT NULL),
    CHECK ((status IN ('preparing', 'queued')) = (finished_at IS NULL))
);
CREATE INDEX data_export_weekly_open ON data_export_weekly (tenant_id) WHERE status IN ('preparing', 'queued');
SELECT bms_apply_tenant_rls('data_export_weekly');
SELECT bms_grant_app('data_export_weekly', 'SELECT, INSERT');
GRANT UPDATE (status, attached, reason, finished_at) ON data_export_weekly TO bms_app;

-- The businesses the weekly email goes to (ADR-074 decision 7): every active one, and one that ended by its Owner's
-- cancellation (V90: suspended, its subscription cancelled with ended_on) for 90 days after ended_on, so a business that
-- is leaving gets its copies while it decides. A business the operator suspended gets none, whatever its scheduled
-- cancellation. Ids only, executable by bms_app only.
CREATE FUNCTION app_data_export_weekly_tenants(p_now timestamptz) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT t.id FROM tenants t
      LEFT JOIN subscriptions s ON s.tenant_id = t.id
     WHERE t.status = 'active'
        OR (t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL
            AND s.ended_on > (p_now AT TIME ZONE t.timezone)::date - 90)
     ORDER BY t.id
$$;
REVOKE ALL ON FUNCTION app_data_export_weekly_tenants(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_data_export_weekly_tenants(timestamptz) TO bms_app;

-- What the outbox made of a week's email, for the bound tenant only: pending, sent or failed, or null when there is no row
-- (a sent row is purged after its days). The outbox is reached only through functions (V23); this one reads one row by the
-- week's own key and returns its status, nothing else.
CREATE FUNCTION app_data_export_weekly_email_status(p_week_id uuid) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT o.status FROM notification_outbox o
     WHERE o.idempotency_key = 'core.data_export_weekly:' || p_week_id::text
       AND o.tenant_id = current_setting('app.tenant_id')::uuid
$$;
REVOKE ALL ON FUNCTION app_data_export_weekly_email_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_data_export_weekly_email_status(uuid) TO bms_app;

-- The nightly expiry of report files (ADR-038 decision 7) used to walk the active tenants only, so the files of a suspended
-- business, or of one whose subscription has ended, were never deleted. Now it walks the tenants that have a file due,
-- whatever their status. Ids only, executable by bms_app only.
CREATE FUNCTION app_tenants_with_expired_report_files(p_now timestamptz) RETURNS SETOF uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT DISTINCT r.tenant_id FROM report_runs r
     WHERE r.status = 'ready' AND r.files_deleted_at IS NULL AND r.expires_at <= p_now
     ORDER BY r.tenant_id
$$;
REVOKE ALL ON FUNCTION app_tenants_with_expired_report_files(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_tenants_with_expired_report_files(timestamptz) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Permission (chapter 8 section 8.3.2, ADR-074). A read of the whole business across every branch, so the administrators
-- (the Owner among them, ADR-062) hold it and nobody else; PermissionMatrixIT compares the seeded rows with the matrix.
-- ---------------------------------------------------------------------------------------------

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('core.data_export.run', 'core', 'Business data: download the whole business as a workbook and see the weekly email', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'core.data_export.run');
