-- V80: feedback sent from inside the app (issue #447, ADR-065). The next number after V79 on main; Flyway runs with
-- outOfOrder off, so the open pull requests that claimed V80 to V85 take the numbers after this one.
--
-- Expand only, safe on a populated database. One new tenant table, feedback_items (an idea, a problem or a question a staff
-- user sent, with the page they were on, the app version and the device), and three SECURITY DEFINER functions the platform
-- operator reads and answers it through (ADR-016): the list across businesses, the business of one item (so the operator's
-- screenshot read can bind that tenant), the status change with the operator's optional reply, and the link to the issue
-- the operator opened for it.
--
-- Locks, said plainly. Flyway runs this file in one transaction. CREATE TABLE with foreign keys takes SHARE ROW EXCLUSIVE on
-- tenants, users, documents and platform_users until the commit (reads go on; the new table is empty, so the cost is the
-- wait for the lock). lock_timeout is five seconds as in V26 and V68: if a lock cannot be had at once the migration fails
-- and is retried, rather than every writer queueing behind it.

SET LOCAL lock_timeout = '5s';

-- ---------------------------------------------------------------------------------------------
-- feedback_items (ADR-065). Written by the sender's request in the sender's tenant (INSERT only for the application role);
-- the sender reads their own rows (the service filters on sender_user_id; RLS keeps every other business out). The status
-- and the reply change only through platform_feedback_set_status, which the operator calls on the platform host with no
-- tenant bound: the application role has no UPDATE and no DELETE. The status moves forward only (new, seen, done).
-- message is what the sender typed, at most 2000 characters; page_path the path of the page they came from (no host, no
-- query); app_version the running build (version and git SHA, from the server); user_agent the browser's own description,
-- cut to 400 characters by the service. screenshot_document_id is an optional core document of subject type core.feedback.
-- linked_issue_number is the number of the issue the operator opened for the item, set or cleared through
-- platform_feedback_set_linked_issue.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE feedback_items (
    id                     uuid         NOT NULL PRIMARY KEY,
    tenant_id              uuid         NOT NULL REFERENCES tenants (id),
    sender_user_id         uuid         NOT NULL,
    kind                   text         NOT NULL CHECK (kind IN ('idea', 'problem', 'question')),
    message                text         NOT NULL CHECK (length(btrim(message)) BETWEEN 1 AND 2000),
    page_path              varchar(300) CHECK (page_path LIKE '/%'),
    app_version            varchar(100) NOT NULL,
    user_agent             varchar(400),
    screenshot_document_id uuid,
    status                 text         NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'seen', 'done')),
    reply_note             text         CHECK (length(reply_note) BETWEEN 1 AND 2000),
    status_changed_at      timestamptz,
    status_changed_by      uuid         REFERENCES platform_users (id),
    linked_issue_number    integer      CHECK (linked_issue_number > 0),
    created_at             timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, sender_user_id) REFERENCES users (tenant_id, id),
    FOREIGN KEY (tenant_id, screenshot_document_id) REFERENCES documents (tenant_id, id)
);
-- The sender's own list and the hourly limit (newest first per sender).
CREATE INDEX feedback_items_by_sender ON feedback_items (tenant_id, sender_user_id, created_at DESC);
-- The operator's list across businesses, newest first, by status.
CREATE INDEX feedback_items_by_status ON feedback_items (status, created_at DESC);
SELECT bms_apply_tenant_rls('feedback_items');
SELECT bms_grant_app('feedback_items', 'SELECT, INSERT');

-- ---------------------------------------------------------------------------------------------
-- The operator's reads and the one write (ADR-016). Each refuses a transaction bound to a tenant, so a tenant request can
-- never call them to read another business; the platform API runs them with no tenant bound.
-- ---------------------------------------------------------------------------------------------

-- Newest first, every business, optionally one status, one item or only what came after p_since. The sender's name and the
-- business's name and slug come with each row; nothing else of the sender (no email, no phone).
CREATE FUNCTION platform_feedback_list(
    p_status text, p_id uuid, p_since timestamptz, p_limit integer, p_offset integer) RETURNS TABLE (
    id uuid, tenant_id uuid, tenant_slug text, tenant_name text, sender_name text, kind text, message text,
    page_path text, app_version text, user_agent text, has_screenshot boolean, status text, reply_note text,
    status_changed_at timestamptz, linked_issue_number integer, created_at timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'feedback is read across businesses by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN QUERY
    SELECT f.id, f.tenant_id, t.slug::text, t.name::text, u.full_name::text, f.kind, f.message, f.page_path::text,
           f.app_version::text, f.user_agent::text, f.screenshot_document_id IS NOT NULL, f.status, f.reply_note,
           f.status_changed_at, f.linked_issue_number, f.created_at
      FROM feedback_items f
      JOIN tenants t ON t.id = f.tenant_id
      JOIN users u ON u.tenant_id = f.tenant_id AND u.id = f.sender_user_id
     WHERE (p_status IS NULL OR f.status = p_status) AND (p_id IS NULL OR f.id = p_id)
       AND (p_since IS NULL OR f.created_at > p_since)
     ORDER BY f.created_at DESC, f.id
     LIMIT least(greatest(p_limit, 1), 200) OFFSET greatest(p_offset, 0);
END
$$;

-- The business one item belongs to, or null: the operator's screenshot read binds it before reading the document.
CREATE FUNCTION platform_feedback_tenant(p_id uuid) RETURNS uuid
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'feedback is read across businesses by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN (SELECT f.tenant_id FROM feedback_items f WHERE f.id = p_id);
END
$$;

-- Moves one item forward (new to seen or done, seen to done; the same status again keeps it) and sets or keeps the reply:
-- a null reply keeps the one there, an empty one (or only white space) clears it. The status date and the operator change
-- only when the status does, so saving a reply alone keeps them. Answers 'not_found', 'backwards' or the tenant id as text.
CREATE FUNCTION platform_feedback_set_status(
    p_id uuid, p_status text, p_reply text, p_platform_user_id uuid, p_now timestamptz) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid;
    v_status text;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'feedback is answered by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_status NOT IN ('new', 'seen', 'done') THEN
        RAISE EXCEPTION 'unknown feedback status' USING ERRCODE = 'check_violation';
    END IF;
    SELECT f.tenant_id, f.status INTO v_tenant, v_status FROM feedback_items f WHERE f.id = p_id FOR UPDATE;
    IF v_tenant IS NULL THEN
        RETURN 'not_found';
    END IF;
    IF array_position(ARRAY['new', 'seen', 'done'], p_status) < array_position(ARRAY['new', 'seen', 'done'], v_status) THEN
        RETURN 'backwards';
    END IF;
    UPDATE feedback_items
       SET status = p_status,
           reply_note = CASE WHEN p_reply IS NULL THEN reply_note ELSE nullif(btrim(p_reply, E' \t\r\n'), '') END,
           status_changed_at = CASE WHEN p_status = v_status THEN status_changed_at ELSE p_now END,
           status_changed_by = CASE WHEN p_status = v_status THEN status_changed_by ELSE p_platform_user_id END
     WHERE id = p_id;
    RETURN v_tenant::text;
END
$$;

-- Sets the issue the operator opened for one item, or clears it with a null. Answers 'not_found' or the tenant id as text.
CREATE FUNCTION platform_feedback_set_linked_issue(p_id uuid, p_issue integer) RETURNS text
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid;
BEGIN
    IF nullif(current_setting('app.tenant_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'feedback is answered by the platform operator only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    UPDATE feedback_items SET linked_issue_number = p_issue WHERE id = p_id RETURNING tenant_id INTO v_tenant;
    RETURN coalesce(v_tenant::text, 'not_found');
END
$$;

REVOKE ALL ON FUNCTION platform_feedback_list(text, uuid, timestamptz, integer, integer) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_feedback_tenant(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_feedback_set_status(uuid, text, text, uuid, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION platform_feedback_set_linked_issue(uuid, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_feedback_list(text, uuid, timestamptz, integer, integer) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_feedback_tenant(uuid) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_feedback_set_status(uuid, text, text, uuid, timestamptz) TO bms_app;
GRANT EXECUTE ON FUNCTION platform_feedback_set_linked_issue(uuid, integer) TO bms_app;
