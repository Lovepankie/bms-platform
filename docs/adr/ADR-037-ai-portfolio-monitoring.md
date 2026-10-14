# ADR-037: AI portfolio monitoring: an advisory brief and question box over aggregates and masked identifiers, an OpenAI-compatible provider behind a port, numbers validated in code, off by default

## Status

Proposed (issue #210, design only; spec `docs/specs/client-extras.md` section 5). A first test of the
idea, not a commitment to a model or a vendor. Builds on ADR-003 (row-level security), ADR-008
(jobs), ADR-030 (the insights read model and its metrics dictionary) and ADR-036 (the same
fallback and cost-recording habits). Migrations are numbered when each build slice merges.

## Context

The insights page (ADR-030) already computes the owner's morning brief, PAR, collections, revenue and
member activity, with a documented formula for every number. The product owner wants to test whether
a cheap language model adds value on top: a plain-language daily brief and a box to ask questions
about the portfolio. The first provider is DeepSeek through its OpenAI-compatible chat API, using
its cheapest chat model. Risks that shape the design:

1. **Personal data leaving the platform.** The provider is outside Uganda; members' names, phones,
   ID numbers and addresses must never reach it (Data Protection and Privacy Act considerations,
   NFR-DP-07). Aggregates over very small groups (one officer, a branch with two loans) can identify a
   person, so they are treated as personal data and suppressed.
2. **Invented numbers.** A model may restate a figure wrongly, round it, or compute a new one. In a
   lender's brief a wrong PAR figure is worse than none.
3. **Only aggregates and aliases leave the platform, with small groups suppressed.**
   `lending.insights` publishes `PortfolioFacts`: a list of `{key, label, value, display, kind,
   polarity, delta}` taken from the metrics dictionary (the `brief.*`, `portfolio.*`, `revenue.*` and
   `members.*` keys), including precomputed week-on-week and month-on-month deltas so the model never
   needs arithmetic. Branches and products are replaced by aliases (`Branch 1`, `Product B`), mapped
   back only in the response shown to the user. **The officer dimension is dropped from the first
   release**: an officer is a named staff member and an officer's portfolio is personal data. Any
   group or figure computed over fewer than five loans or members is left out and replaced by the
   fact "fewer than 5". No member name, phone, ID number, address, loan number, staff name or email is
   ever in a prompt. Loan level rows are excluded; a later option adds loan tokens (a keyed hash that
   changes daily, mapped back locally) behind a second switch and a new ADR. This is data
   minimisation, not a claim that nothing identifying can exist: the acknowledgement and the leak
   tests are the controls.

4. **The question box is scrubbed, and questions use the caller's scope.** A question is at most 300
   characters. Before any check, spaces, dots, dashes and brackets between digits are removed, so
   `+256 700 000 001` and `0700-000-001` are seen as a run of digits. It is refused if it holds a run
   of six or more digits, an email address, an ID number pattern or a loan or receipt number of the
   tenant's series (prefix and digits, however short). Names are checked in the database, not in
   memory: the question's words (at least three letters, not in a short list of common English words)
   are matched against member first and last names with an indexed query, and a question is refused
   when a word matches a member's name and is not a common word, or when two adjacent words match a
   first and last name; a common word that happens to be a surname does not refuse a question. What is
   logged and sent is the scrubbed text. The facts for a question are computed under the caller's
   branch scope, never the tenant-wide figures, and an answer is never cached or shared. Residual risk
   (a typed name no check recognises) is stated on the screen and in the tenant acknowledgement.

5. **The model may only restate supplied numbers, and code enforces it.** The prompt asks for JSON:
   `{summary, points: [{text, facts: [fact keys]}]}`. The validator extracts every numeric token from
   every text (number words are converted first), and each must equal the display form or the plain
   form of a fact the same point cites; aliases, the as-of date and list ordinals are excluded before
   the check. **Direction words are checked against the sign of the delta:** a point citing a delta
   fact may say up, rose, higher, down, fell, lower and so on only in the direction of the delta's
   sign, and "better", "improved", "worse", "worsened" only according to the metric's polarity (a
   falling PAR is better, a falling collection rate is worse); a point with a contradicting or both
   directions fails. A response that fails parsing or validation is retried once with a stricter
   reminder; a second failure falls back. Validation is a pure class with a property test; words
   outside the lexicon are a residual risk the evaluation measures.

6. **Fallback never blocks.** If the provider is down, slow (30 second total budget), over the cap,
   switched off, or fails validation twice, the page shows the deterministic brief that ADR-030
   already builds, labelled "automatic summary". A circuit breaker opens after 5 consecutive
   failures for 60 seconds (chapter 12 section 12.1).
7. **Off by default, per tenant, with a recorded acknowledgement and a platform allow-list.** A tenant
   admin (`core.settings.manage`) switches `ai.enabled` on after reading what is sent and where, and
   only if the tenant is named in the platform's `BMS_AI_ENABLED_TENANTS` list (an environment value
   set by the operator after the written provider terms are on file); otherwise the route answers 403
   `advisor_not_available`. The acknowledgement (who, when, text version) is stored and audited. A
   separate permission `lending.advisor.use` allows reading and asking. Switching off stops all calls
   at once.
   **Scope of the daily brief.** The automatic brief is one per tenant and day over all branches, so
   only users whose branch scope covers every branch and who hold `lending.insights.all_officers`
   may read it; branch managers and loan officers get the deterministic brief for their scope and the
   question box, whose facts are computed under their own scope.

8. **Cost caps.** A per tenant daily limit on tokens (input plus output, as reported by the
   provider), a per user daily limit on questions, one automatic brief per tenant per day (idempotent
   by tenant and date), and a platform wide daily ceiling. Estimated cost is recorded in
   micro-units of the provider's currency from a price table kept in operations configuration, not
   in the repository.
9. **Logging without personal data.** `ai_calls` (row-level security) stores tenant, purpose, the user
   who asked (staff, not sent), model, a hash of the request, token counts, latency, outcome and a closed
   reason. **It stores no prompt text and no response text** (this decision first said it would store
   aliased text; see the amendment of 2026-10-14, which settles it: none). 90 day retention. A test plants fabricated names in the data and asserts they appear in no
   outgoing request and no log row.
10. **Evaluation before pilot use.** A golden evaluation set on the fabricated insights demo data
    (spec section 5.8) compares answers with the golden metrics: numeric fidelity, citation
    correctness, coverage of the biggest movements, behaviour when data is missing, and the planted
    name test. The same suite runs against any candidate model before a swap.
11. **Provider terms are a go-live condition.** Before any non-fabricated data is sent, the dev lead
    confirms in writing the provider's data retention and no-training terms and records the
    cross-border position (NFR-DP-07); the tenant's acknowledgement refers to it.

## Consequences

**Better:**

- Personal data is minimised by construction, not by hope: the prompt is built from a typed list of
  facts with small groups suppressed, not from free rows.
- A wrong figure cannot reach the screen: a number the model invented fails validation and the user
  sees the deterministic brief instead.
- The vendor and model are configuration; the evaluation suite makes a swap a measured decision.
- Cost is bounded per tenant per day and visible.

**Worse:**

- Aggregates still describe a tenant's business; they leave the platform to a provider outside
  Uganda. The acknowledgement, small-count suppression and the contract check are the controls, not a
  technical guarantee.
- Without the officer dimension the brief cannot say which officer is weakest; that stays on the
  insights page for people allowed to see it.
- Validation checks that a number exists among the cited facts, not that the sentence around it is
  wise; the advisory label and the evaluation suite carry that risk.
- Aliases make answers slightly less natural ("Branch 2 is weakest").
- A scrubbed free-text question can still miss a name; the box is therefore the second slice, after
  the brief.

**Watch for:**

- A provider pricing or model change; read the model name and prices from configuration, never code.
- Prompt injection through the question text: the model has no tools and its output is shown as
  plain text (no markdown links, no HTML), so injection can at worst produce a rejected answer.
- Someone adding loan level detail to a prompt "for usefulness": that needs a new ADR and the
  second switch.

## Amendment of 2026-10-14 (issue #220): the gateway, as built

Decisions 6, 7, 8 and 9 are built in slice C1 (migration V51; `docs/specs/client-extras.md` section 5.3). The choices
they left open, and one change:

1. **The call log stores no text at all (a change to decision 9).** `ai_calls` holds counts, the model name, the
   purpose, a fixed vocabulary reason, the HTTP status number, the latency and the SHA-256 of the request messages. It
   has no prompt column, no response column and nothing that could hold a provider error body, and a test pins the
   column list so a new column is a review decision. The slice that builds the prompts (C2 or C3) may add aliased text
   columns by its own expand migration and an amendment here if the evaluation needs them; until then the log cannot
   leak what it never held.
2. **One entry point that returns a fallback instead of throwing at the caller, with a second screen on its input.** `AiGateway.complete` answers with the model's text or with a
   fallback carrying a closed reason (`not_enabled`, `not_allowed`, `provider_off`, `input_too_large`,
   `tenant_token_cap`, `tenant_cost_cap`, `platform_token_cap`, `platform_cost_cap`, `breaker_open`, `timeout`,
   `network`, `rate_limited`, `provider_error`, `auth_error`, `bad_response`, `bad_request`). The order of refusals is the
   order of cost: the tenant switch, the allow-list and an unconfigured provider leave no row; then one reservation
   transaction applies the per call cap and the four daily caps and writes the row; only then the breaker and the network.
   Between the allow-list and the reservation sits an input screen: a request whose text holds a run of six or more digits, an
   email address, a Uganda or international phone number in any spacing, or a national ID shape is refused (`input_refused`)
   with no call, because the port accepts any text and the prompt builders are not the only line of defence. The text is
   normalised first (Unicode NFKC, every decimal digit of any script as ASCII, invisible format characters such as zero-width
   spaces removed; separators are any space, dot, slash, underscore, dash or bracket), so no-break spaces, full-width digits and
   a full-width at sign do not get past it; dates (`2026-10-14`, `20261014`, `07-10-2026`), UUIDs and masked identifiers
   (`****1234`) are set aside before the check, and a real phone number beside them is still refused. It cannot see a
   name; the question box checks names in the database (slice C4). Facts therefore go in their grouped display form
   (`1,250,000`). Our own failures (the database, a bug) are a fallback `internal_error` that never feeds the breaker; a
   failure to write the log after the provider answered does not throw the answer away; and the gateway refuses to run inside
   a transaction, an exception, since that is a bug in the caller.
3. **Caps count a call before it returns.** The row is written pending with a reservation (the input estimate, three
   characters a token and so on the high side, plus the output cap) and finished with the tokens the provider reported.
   The daily sums read those columns under one advisory lock, so two calls at once cannot both take the last of a cap, and
   the platform ceiling, a sum across tenants, is read through a `SECURITY DEFINER` function that returns two numbers and
   no row. Caps are in tokens and in estimated cost; the owner asked for a cost cap per tenant as well as a platform
   ceiling. A call that fails after its request was sent keeps its reservation (or the usage the provider reported), since a
   timeout or an unusable 200 is still billed, so a breaker probing every minute cannot walk round a cap. A cap of zero means
   stop. Cost is `tokens x price` rounded up in micro-units, with prices from the environment; with no prices set the
   cost caps are inert and the token caps carry the bound. A tenant cap above the platform ceiling is refused when set and
   the lower of the two applies on every call. The day is the tenant's own for the tenant cap and Kampala's for the platform.
4. **The switch is a settings group, guarded where it is written.** The tenant's switch, caps and acknowledgement are the
   tenant settings group `ai` (`enabled`, `daily_token_cap`, `daily_cost_cap_micro`, `acknowledged_by`, `acknowledged_at`,
   `acknowledged_text_version`), stored in the existing settings row with its version and audit. The allow-list and the
   acknowledgement are enforced in the group's own validation, so no write path can switch the assistant on for a tenant
   outside `BMS_AI_ENABLED_TENANTS`, which holds tenant ids and never slugs (403 `advisor_not_available`) or without the acknowledgement (422
   `acknowledgement_required`). The gateway asks the allow-list again on every call, so removing a tenant stops it at once.
   No permission is added here: the routes and the two advisor permissions arrive with C3.
5. **Not sending is the default and is tested.** Provider `off` is the default; `openai_compatible` with a missing base
   URL, key or model, a key with whitespace, or a URL that is not https (a loopback address in the dev and test profiles
   excepted) is off with a logged warning, never a startup failure; `recording`, the stub, is refused at startup outside
   the dev and test profiles. The gateway integration test selects the stub and every other test leaves the provider off, so no test can reach a network.
6. **Time.** One call has a total budget of 30 seconds at most that its retries and pauses must fit in; a 429 or a 5xx is
   retried up to three attempts with pauses of 0.5 and 1 second (or a `Retry-After` of up to 10 seconds); a read timeout, a
   4xx other than 429, and a redirect (never followed) are not retried. Five failures in a row open the process-wide breaker
   for 60 seconds, then one probe decides; a request the provider found fault with does not count. Each call holds a permit of
   the breaker's generation, so a late result from a call that began before the breaker opened cannot close it or free the
   probe's slot, a probe has a ticket of its own so an older probe settling late cannot free or decide a newer probe's slot, and the
   slot is released in a `finally`, an `Error` included. A bug of ours in the adapter is an `internal_error` fallback that gives the
   permit back and does not count against the breaker. Pauses carry random jitter (up to a quarter),
   never fall under 250 ms, and a `Retry-After` is honoured up to 10 seconds. A call that dies between reserving and finishing
   is closed as failed after an hour by the nightly housekeeping, a database function that also deletes rows past 90 days for
   every tenant, suspended ones included.

The first provider is configured as DeepSeek's OpenAI-compatible API (`BMS_AI_BASE_URL=https://api.deepseek.com`) with its
cheapest chat model, the model name being `BMS_AI_MODEL` only; no key, price or model is in the repository.
