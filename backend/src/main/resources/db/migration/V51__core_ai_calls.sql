-- V51: core.ai call log: one row per call to the language model gateway, with token and cost accounting
-- (ADR-037 and its amendment of 2026-10-14, issue #220; docs/specs/client-extras.md sections 5.7, 5.9 and 5.10).
--
-- Expand only, safe on a populated database. It adds: one tenant table (`ai_calls`, with three indexes and the
-- standard row-level security), one trigger function `ai_calls_guard` used by two triggers on that table (one on
-- UPDATE, one on DELETE), and two SECURITY DEFINER functions (`ai_platform_usage`, the platform-wide sum, and
-- `ai_calls_housekeeping`, the nightly retention and cleanup across every tenant). Nothing else. No existing table,
-- column, constraint or function is touched. No permission is added (the two advisor permissions arrive with the slice
-- that has routes to protect, #226) and no tenant is switched on: the tenant switch is a key of the tenant settings
-- group `ai` in the existing jsonb column, absent meaning off, and the platform allow-list is an environment value, so
-- nothing here changes what any tenant can do.
--
-- Locks, said plainly. The table is created empty, so the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and
-- `users` (the foreign keys) until the commit, which blocks writes to those two tables for the milliseconds the file
-- runs and does not block reads; no row is scanned and no index is built on existing data. Flyway runs the file in one
-- transaction. Flyway runs with outOfOrder off, so the number is assigned at merge time above the highest one on main.
--
-- PRIVACY, stated once and the same in ADR-037, spec 5.10 and chapter 6: the table stores NO prompt text, NO response
-- text and NO provider error text, in any column. A row holds counts, a model name, a closed-vocabulary reason, an HTTP
-- status number, a latency and the SHA-256 of the request, which shows that two calls were the same request and nothing
-- more. The reason is that a lender's figures and any name that slips into a question are personal or confidential data
-- in a table nobody needs to read; what a call said is reproducible from the hash and the code that built it. If the
-- slice that builds the prompts later wants aliased text, it adds the columns by its own expand migration and an
-- amendment of ADR-037, after review.

CREATE TABLE ai_calls (
    id                   uuid         NOT NULL PRIMARY KEY,
    tenant_id            uuid         NOT NULL REFERENCES tenants (id),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    -- Null while the call is pending (the row is written before the network call and holds its reservation).
    finished_at          timestamptz,
    -- What the call was for: brief, question, evaluation. A closed vocabulary of lower case words, checked by shape so
    -- a later slice adds a purpose without a migration.
    purpose              varchar(40)  NOT NULL CHECK (purpose ~ '^[a-z][a-z_]{1,39}$'),
    -- The staff user who asked, never sent to the provider. Null for the automatic daily brief.
    user_id              uuid,
    model                varchar(100) NOT NULL,
    -- SHA-256 of the request messages, hex. Not the text.
    request_hash         char(64)     NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    -- pending: reserved, the network call is in flight. ok: an answer came back. failed: a network call was made and
    -- did not give an answer. refused: no network call was made (a cap, the breaker, a request over the per-call cap).
    outcome              text         NOT NULL DEFAULT 'pending' CHECK (outcome IN ('pending', 'ok', 'failed', 'refused')),
    -- The closed vocabulary of why a call was refused or failed (core.ai AiGateway.Reason); null when ok.
    reason               varchar(40)  CHECK (reason ~ '^[a-z][a-z_]{1,39}$'),
    http_status          smallint     CHECK (http_status BETWEEN 100 AND 599),
    attempts             smallint     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 10),
    -- What this row holds against the daily caps: the reservation while pending, the tokens the provider reported once
    -- finished, nothing for a refusal. The caps sum these columns, so a call in flight counts before it returns.
    reserved_tokens      integer      NOT NULL DEFAULT 0 CHECK (reserved_tokens >= 0),
    input_tokens         integer      CHECK (input_tokens >= 0),
    output_tokens        integer      CHECK (output_tokens >= 0),
    tokens_counted       integer      NOT NULL DEFAULT 0 CHECK (tokens_counted >= 0),
    -- Estimated cost in micro-units of `cost_currency`, from prices read from operations configuration (spec 5.9).
    estimated_cost_micro bigint       CHECK (estimated_cost_micro >= 0),
    cost_counted_micro   bigint       NOT NULL DEFAULT 0 CHECK (cost_counted_micro >= 0),
    cost_currency        char(3)      NOT NULL DEFAULT 'USD' CHECK (cost_currency ~ '^[A-Z]{3}$'),
    latency_ms           integer      CHECK (latency_ms >= 0),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id),
    CHECK ((outcome = 'pending') = (finished_at IS NULL)),
    CHECK (outcome NOT IN ('failed', 'refused') OR reason IS NOT NULL)
);
CREATE INDEX ai_calls_by_tenant_day ON ai_calls (tenant_id, created_at DESC);
CREATE INDEX ai_calls_by_day ON ai_calls (created_at);
CREATE INDEX ai_calls_pending ON ai_calls (created_at) WHERE outcome = 'pending';

SELECT bms_apply_tenant_rls('ai_calls');
SELECT bms_grant_app('ai_calls', 'SELECT, INSERT, DELETE');
-- A row is finished once, from pending, and only these columns change; nothing else about a call is ever rewritten.
GRANT UPDATE (finished_at, outcome, reason, http_status, attempts, input_tokens, output_tokens, tokens_counted,
              estimated_cost_micro, cost_counted_micro, latency_ms) ON ai_calls TO bms_app;

CREATE FUNCTION ai_calls_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF session_user = 'bms_owner' AND coalesce(current_setting('bms.allow_mutation', true), '') = 'on' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- Retention is 90 days (spec 5.10): the purge job deletes older rows, nothing deletes a younger one.
        IF OLD.created_at > now() - interval '90 days' THEN
            RAISE EXCEPTION 'ai_calls: a row is kept 90 days' USING ERRCODE = 'insufficient_privilege';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.outcome <> 'pending' OR NEW.outcome = 'pending'
       OR (NEW.id, NEW.tenant_id, NEW.created_at, NEW.purpose, NEW.user_id, NEW.model, NEW.request_hash,
           NEW.reserved_tokens, NEW.cost_currency)
          IS DISTINCT FROM
          (OLD.id, OLD.tenant_id, OLD.created_at, OLD.purpose, OLD.user_id, OLD.model, OLD.request_hash,
           OLD.reserved_tokens, OLD.cost_currency)
    THEN
        RAISE EXCEPTION 'ai_calls: a call is finished once and its request columns never change'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER ai_calls_guard_update BEFORE UPDATE ON ai_calls
    FOR EACH ROW EXECUTE FUNCTION ai_calls_guard();
CREATE TRIGGER ai_calls_guard_delete BEFORE DELETE ON ai_calls
    FOR EACH ROW EXECUTE FUNCTION ai_calls_guard();

-- ---------------------------------------------------------------------------------------------
-- The platform-wide ceiling (spec 5.7) sums every tenant's usage for a window. A tenant connection cannot read other
-- tenants' rows, so the sum is a SECURITY DEFINER function owned by bms_owner (ADR-016) that returns two numbers and no
-- row. It takes the window from the caller (the kernel clock decides the business day, never now()).
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION ai_platform_usage(p_from timestamptz, p_to timestamptz)
RETURNS TABLE (tokens bigint, cost_micro bigint)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT coalesce(sum(c.tokens_counted), 0)::bigint, coalesce(sum(c.cost_counted_micro), 0)::bigint
      FROM ai_calls c
     WHERE c.created_at >= p_from AND c.created_at < p_to
$$;
REVOKE ALL ON FUNCTION ai_platform_usage(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_platform_usage(timestamptz, timestamptz) TO bms_app;

-- ---------------------------------------------------------------------------------------------
-- Nightly housekeeping for every tenant, suspended and closed ones included (retention is a duty, not a service): rows
-- past 90 days are deleted (the guard trigger allows exactly that), and a call left pending for an hour, whose process
-- died between reserving and finishing, is closed as failed/abandoned keeping its reservation against that day. It runs
-- as bms_owner (SECURITY DEFINER), which is why no per-tenant loop is needed; the guard trigger still applies, since
-- the session user is the application role. Returns the rows deleted.
-- ---------------------------------------------------------------------------------------------

CREATE FUNCTION ai_calls_housekeeping() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    deleted integer;
BEGIN
    UPDATE ai_calls SET outcome = 'failed', reason = 'abandoned', finished_at = now()
     WHERE outcome = 'pending' AND created_at < now() - interval '1 hour';
    DELETE FROM ai_calls WHERE created_at < now() - interval '90 days';
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RETURN deleted;
END
$$;
REVOKE ALL ON FUNCTION ai_calls_housekeeping() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_calls_housekeeping() TO bms_app;
