-- V125: the retail AI brief (issue #422; ADR-037 as amended for retail). Numbered V125 in the merge order the coordinator set
-- (AGENTS.md); Flyway runs with outOfOrder off, so the merger renumbers at merge time if the order changes.
--
-- Expand only, safe on a populated database: one new empty tenant table with the standard row-level security and two
-- permission rows with their default grants. No existing object is touched and no tenant is switched on: the assistant
-- stays off until a business administrator switches it on (settings group `ai`) and the platform has opened it.
--
-- WHAT IS STORED, said once. `retail_ai_briefs` holds the brief as OUR code wrote it from the model's structured choice and the
-- reviewed catalogue (retail-advisor/catalogue.json), or the facts only brief when the model was not used or its answer was
-- refused. It never holds anything the model wrote: there is no column for a prompt, an answer or a provider error. One row per
-- tenant and business date (the primary key), over ALL branches, every seller, cost and profit; the API returns it only to a
-- user who holds retail.advisor.use, retail.sale.all_sellers and retail.profit.read over every branch (the owner and the
-- administrators). Append-only: the job inserts a day once (ON CONFLICT DO NOTHING) and the application role cannot update or
-- delete a row.
--
-- The grants (ADR-037 amendment of #422, decision 6): both permissions to the administrators only, so switching the assistant
-- on shows the brief to nobody else. Issue #422 names no other role; another role holds retail.advisor.use only when a business
-- grants it on purpose, once roles are editable by a business (they are not yet), and then sees the automatic summary of what it
-- may see (ADR-055).

INSERT INTO permissions (key, module, description, is_money_moving) VALUES
    ('retail.advisor.use', 'retail', 'AI assistant: see the daily shop brief', false),
    ('retail.advisor.configure', 'retail', 'AI assistant: switch, caps and use for a shop', false);

INSERT INTO role_permissions (role_key, permission_key) VALUES
    ('tenant_admin', 'retail.advisor.use'),
    ('tenant_admin', 'retail.advisor.configure')
ON CONFLICT DO NOTHING;

CREATE TABLE retail_ai_briefs (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    -- The Africa/Kampala business date the brief was queued for; its figures stand at the end of the day before.
    brief_date    date         NOT NULL,
    source        text         NOT NULL CHECK (source IN ('model', 'fallback')),
    -- Why the fallback was stored (a closed word); null when source is model.
    reason        varchar(40)  CHECK (reason ~ '^[a-z][a-z_]{1,39}$'),
    summary       text         NOT NULL CHECK (length(summary) <= 1000),
    -- [{"text": "...", "facts": ["sales.yesterday"]}], text written by our code.
    points        jsonb        NOT NULL,
    -- [{"key": "sales.yesterday", "label": "Sales yesterday", "display": "UGX 120,000", "metric": "sales.yesterday"}]
    chips         jsonb        NOT NULL,
    facts_as_of   date         NOT NULL,
    generated_at  timestamptz  NOT NULL,
    -- The model's name and the ai_calls row, for the cost trail; null for a fallback that made no call.
    model         varchar(100),
    call_id       uuid,
    PRIMARY KEY (tenant_id, brief_date),
    CHECK ((source = 'model') = (reason IS NULL))
);

SELECT bms_apply_tenant_rls('retail_ai_briefs');
SELECT bms_grant_app('retail_ai_briefs', 'SELECT, INSERT');
