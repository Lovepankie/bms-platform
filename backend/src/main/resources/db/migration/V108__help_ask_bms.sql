-- V108: Ask BMS (issue #20, owner decision D52, ADR-096), directly after V107 (#597). Expand only, safe on a populated
-- database: four new tenant tables and two SECURITY DEFINER functions. No existing table, column, constraint or function is
-- touched and no permission is added (asking is open to every signed-in member of staff, like How do I...?).
--
-- 1. `help_ask_questions`: one row per question asked, the log of what happened. It holds the SHA-256 of the scrubbed
--    question and never its text, the outcome, which provider answered (or none), the providers that failed before it as
--    closed words, token counts and the estimated cost. Append-only for the application role.
-- 2. `help_ask_tickets`: the limits, written BEFORE the work they count, under a per-business advisory lock, so parallel
--    questions cannot all pass a check that none has recorded yet: `ask` for every question (the per-person hourly limit)
--    and `provider` for every question that goes on to the providers (the daily question cap of the business).
-- 3. `help_ask_spend`: one row per provider call, written before the call with the most it can cost (a reservation, under one
--    platform-wide advisory lock for a paid provider) and settled after it with what the provider reported. The spend is
--    attributed to the provider called, whatever the question's outcome, so a paid answer that is refused still counts.
-- 4. `help_ask_answers`: the answer cache. One row per cache key, the SHA-256 of everything that produced the answer (the
--    normalised scrubbed question, the asker's roles and permission set, the modules, the locale, the corpus revision, the
--    permission matrix version, the prompt version and the provider and model chain). A new revision or matrix version is
--    a new key, so an old row is never read again; `expires_at` is a safety net only. The answer text comes from the help
--    articles alone (ADR-096), so it holds no business data.
-- 5. `help_ask_platform_spend`: the reserved and settled cost of one provider in one currency across every business since a
--    moment, for the monthly DeepSeek cap. A sum only, so a business learns nothing about another. bms_owner has BYPASSRLS.
--
-- 6. `help_erase_personal_data`: the removal of a closed business (ADR-090) deletes its cached answers and limit tickets;
--    the question log and the spend rows hold a user id, hashes and counts only, and the purge deletes them.
--
-- Locks, said plainly. The tables are created empty; the only lock cost is SHARE ROW EXCLUSIVE on `tenants` and `users`
-- (the foreign keys) until the commit, milliseconds, reads go on. lock_timeout is five seconds as in V26 and V107.

SET LOCAL lock_timeout = '5s';

CREATE TABLE help_ask_questions (
    id              uuid        NOT NULL PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    user_id         uuid        NOT NULL,
    asked_at        timestamptz NOT NULL,
    question_hash   char(64)    NOT NULL CHECK (question_hash ~ '^[0-9a-f]{64}$'),
    locale          varchar(10) NOT NULL,
    -- model: a provider phrased it; cache: an earlier answer; articles: the matching articles, no provider answered (none
    -- configured, all failed, the cap, or the model found no answer in them); other_role: the best match is an article the
    -- asker may not read; no_match: no article matches.
    outcome         text        NOT NULL CHECK (outcome IN ('model', 'cache', 'articles', 'other_role', 'no_match')),
    -- Why a question that reached the providers was answered with the articles: closed words, never provider text.
    fallback_reason text        CHECK (fallback_reason IN ('no_provider', 'all_failed', 'daily_cap', 'not_in_articles')),
    -- The provider that answered and billed the call (for `articles`, the one that found no answer in them), or null.
    provider        text        CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model           varchar(100),
    -- The providers tried before the one that answered, as `name:reason` (for example `gemini:rate_limited`).
    failures        text[]      NOT NULL DEFAULT '{}',
    -- Whether it went to the providers (an answer from the cache, a deterministic answer and a question stopped by the cap
    -- did not); the cap itself counts `help_ask_tickets`.
    counted         boolean     NOT NULL,
    cited_article   varchar(80),
    input_tokens    integer     NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens   integer     NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_micro      bigint      NOT NULL DEFAULT 0 CHECK (cost_micro >= 0),
    cost_currency   char(3)     NOT NULL,
    latency_ms      integer     NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    CHECK (outcome <> 'model' OR provider IS NOT NULL),
    CHECK (outcome IN ('model', 'articles') OR provider IS NULL),
    CHECK ((outcome = 'articles') = (fallback_reason IS NOT NULL)),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
-- The newest questions of a business.
CREATE INDEX help_ask_questions_by_time ON help_ask_questions (tenant_id, asked_at);
SELECT bms_apply_tenant_rls('help_ask_questions');
SELECT bms_grant_app('help_ask_questions', 'SELECT, INSERT');

CREATE TABLE help_ask_tickets (
    id        uuid        NOT NULL PRIMARY KEY,
    tenant_id uuid        NOT NULL REFERENCES tenants (id),
    user_id   uuid        NOT NULL,
    at        timestamptz NOT NULL,
    kind      text        NOT NULL CHECK (kind IN ('ask', 'provider')),
    FOREIGN KEY (tenant_id, user_id) REFERENCES users (tenant_id, id)
);
-- The person's hour and the business's day.
CREATE INDEX help_ask_tickets_by_user ON help_ask_tickets (tenant_id, user_id, at) WHERE kind = 'ask';
CREATE INDEX help_ask_tickets_by_day ON help_ask_tickets (tenant_id, at) WHERE kind = 'provider';
SELECT bms_apply_tenant_rls('help_ask_tickets');
SELECT bms_grant_app('help_ask_tickets', 'SELECT, INSERT');

CREATE TABLE help_ask_spend (
    id            uuid         NOT NULL PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    provider      text         NOT NULL CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model         varchar(100) NOT NULL,
    reserved_at   timestamptz  NOT NULL,
    -- Null while the call is in flight: the row then holds its reservation, the most the call can cost.
    settled_at    timestamptz,
    input_tokens  integer      NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens integer      NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    cost_micro    bigint       NOT NULL CHECK (cost_micro >= 0),
    cost_currency char(3)      NOT NULL
);
CREATE INDEX help_ask_spend_by_provider ON help_ask_spend (provider, reserved_at);
SELECT bms_apply_tenant_rls('help_ask_spend');
-- UPDATE only to settle a reservation with what the provider reported.
SELECT bms_grant_app('help_ask_spend', 'SELECT, INSERT, UPDATE');

CREATE TABLE help_ask_answers (
    tenant_id     uuid         NOT NULL REFERENCES tenants (id),
    cache_key     char(64)     NOT NULL CHECK (cache_key ~ '^[0-9a-f]{64}$'),
    answer        text         NOT NULL CHECK (length(answer) BETWEEN 1 AND 2000),
    cited_article varchar(80)  NOT NULL,
    provider      text         NOT NULL CHECK (provider IN ('gemini', 'groq', 'deepseek')),
    model         varchar(100) NOT NULL,
    revision      varchar(16)  NOT NULL,
    created_at    timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (tenant_id, cache_key)
);
SELECT bms_apply_tenant_rls('help_ask_answers');
-- UPDATE only to replace a row past its expiry under the same key.
SELECT bms_grant_app('help_ask_answers', 'SELECT, INSERT, UPDATE');

-- In one currency only, the one the cap is in: a change of BMS_HELP_ASK_COST_CURRENCY mid-month never adds two units.
CREATE FUNCTION help_ask_platform_spend(p_provider text, p_currency char(3), p_from timestamptz)
RETURNS bigint
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp AS $$
    SELECT coalesce(sum(s.cost_micro), 0)::bigint
      FROM help_ask_spend s
     WHERE s.provider = p_provider AND s.cost_currency = p_currency AND s.reserved_at >= p_from
$$;
REVOKE ALL ON FUNCTION help_ask_platform_spend(text, char, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION help_ask_platform_spend(text, char, timestamptz) TO bms_app;

-- The Ask BMS part of removing a closed business (ADR-090), called by the help module's PersonalDataEraser in the removal's
-- transaction: the cached answers and the limit tickets (which name the person who asked) are deleted. The question log and
-- the spend rows hold a user id, hashes and counts only, the user is anonymised by the core, and the purge deletes them with
-- the business's other rows.
CREATE FUNCTION help_erase_personal_data() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_tenant uuid := bms_removing_tenant();
    v_rows   integer := 0;
    v_n      integer;
BEGIN
    DELETE FROM help_ask_answers WHERE tenant_id = v_tenant; GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    DELETE FROM help_ask_tickets WHERE tenant_id = v_tenant; GET DIAGNOSTICS v_n = ROW_COUNT; v_rows := v_rows + v_n;
    RETURN v_rows;
END
$$;
REVOKE ALL ON FUNCTION help_erase_personal_data() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION help_erase_personal_data() TO bms_app;
