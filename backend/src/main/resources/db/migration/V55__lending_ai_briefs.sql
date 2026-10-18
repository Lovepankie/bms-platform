-- V55: the stored daily AI brief and the two advisor permissions (extras slice C3, issue #226, ADR-037;
-- docs/specs/client-extras.md sections 5.2, 5.7 and 5.13).
--
-- Expand only, safe on a populated database: one new empty tenant table with the standard row-level security
-- and two permission rows with their default grants. No existing object is touched and no tenant is switched on:
-- the assistant stays off until a tenant administrator switches it on (settings group `ai`, V51's allow-list).
-- Flyway runs with outOfOrder off, so the number is taken above the highest on main and on every open pull request
-- branch and is renumbered by the coordinator at merge.
--
-- WHAT IS STORED, said once. `lending_ai_briefs` holds the brief as OUR code rendered it from the model's structured
-- choice and the reviewed catalogue (ADR-037 as amended in #223), or the deterministic facts-only brief when the model
-- was not used or its answer was refused. It never holds anything the model wrote: there is no column for a prompt, an
-- answer or a provider error. One row per tenant and business date (the primary key), over ALL branches; the API
-- returns it only to a user whose branch scope covers every branch and who holds lending.insights.all_officers.
-- The table is append-only: the job inserts a day once (ON CONFLICT DO NOTHING makes the job idempotent) and nothing
-- updates or deletes a row through the application role.

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('lending.advisor.use', 'lending', 'AI assistant: see the daily brief and ask', false),
    ('lending.advisor.configure', 'lending', 'AI assistant: caps and call usage', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'lending.advisor.use'),
    ('branch_manager', 'lending.advisor.use'),
    ('accountant', 'lending.advisor.use'),
    ('auditor', 'lending.advisor.use'),
    ('tenant_admin', 'lending.advisor.configure')
ON CONFLICT DO NOTHING;

CREATE TABLE lending_ai_briefs (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    -- The Africa/Kampala business date the brief was queued for, not the date it ran on.
    brief_date    date         NOT NULL,
    -- model: rendered from an accepted structured answer. fallback: the deterministic facts-only brief.
    source        text         NOT NULL CHECK (source IN ('model', 'fallback')),
    -- Why the fallback was stored (a closed word: validation, or an AiGateway reason such as timeout or tenant_token_cap);
    -- null when source is model.
    reason        varchar(40)  CHECK (reason ~ '^[a-z][a-z_]{1,39}$'),
    summary       text         NOT NULL CHECK (length(summary) <= 1000),
    -- [{"text": "...", "facts": ["portfolio.par30"]}], text written by our renderer.
    points        jsonb        NOT NULL,
    -- [{"key": "portfolio.par30", "label": "PAR 30", "display": "12.5 percent", "metric": "portfolio.par30"}]
    chips         jsonb        NOT NULL,
    -- The date the facts stand at, and when the brief was made.
    facts_as_of   date         NOT NULL,
    generated_at  timestamptz  NOT NULL,
    -- The model's name and the ai_calls row, for the cost trail; null for a fallback that made no call.
    model         varchar(100),
    call_id       uuid,
    PRIMARY KEY (tenant_id, brief_date),
    CHECK ((source = 'model') = (reason IS NULL))
);

SELECT bms_apply_tenant_rls('lending_ai_briefs');
SELECT bms_grant_app('lending_ai_briefs', 'SELECT, INSERT');
