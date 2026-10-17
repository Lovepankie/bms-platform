# Runbook: the AI assistant gateway (`core.ai`, ADR-037)

**Scope:** what an operator does to configure, switch on, watch, swap, rotate and stop the language model gateway. It is
advisory only: no record is changed by it, and what it sends is aggregates and aliases only (spec
`docs/specs/client-extras.md` section 5.4). The gateway is built in issue #220; the brief, the question box and their screens
arrive with the slices after it, so until then switching a tenant on has no visible effect beyond the settings and the call log.
**Nothing here is a real key, URL, model price or tenant: every value below is a placeholder.**

## 1. The four independent switches

A call reaches a provider only when all four are true. Any one of them stops it, and each is checked on every call.

| Switch | Where | Off means |
|---|---|---|
| The provider is configured | `BMS_AI_PROVIDER=openai_compatible` with `BMS_AI_BASE_URL`, `BMS_AI_API_KEY` and `BMS_AI_MODEL` in the host environment | Every call is answered with a fallback `provider_off`; nothing fails and nothing is sent. A half-set provider, a key with a stray space or line break, or a base URL that is not `https` logs one warning at start and is treated as off |
| The tenant is on the platform allow-list | `BMS_AI_ENABLED_TENANTS`: tenant **ids** (UUIDs, never slugs: a slug is a name an administrator can choose), comma separated; an entry that is not a UUID names nobody | The tenant cannot switch the assistant on (403 `advisor_not_available`), and a tenant removed from the list stops at once (fallback `not_allowed`) |
| The tenant has switched it on | Tenant settings group `ai`, `enabled`, after the administrator acknowledges what is sent and where | Fallback `not_enabled`; no row is logged |
| The caps are not spent | Tenant and platform daily token and cost caps | Fallback `tenant_token_cap`, `tenant_cost_cap`, `platform_token_cap` or `platform_cost_cap`, refused before any network call |

Staging and production start with the provider `off`. A host that has no key never calls out. The `recording` provider is a test
stub: the application refuses to start with it outside the dev and test profiles.

## 2. Secrets handling

- The key lives in `/opt/bms/.env` (mode 600, owned by the deploy or bms user), as `BMS_AI_API_KEY`, and nowhere else: not in the
  repository, not in `.env.example` (placeholders only), not in a fixture, not in a log, not in an error message, not in an
  API response. The application never prints it; its configuration object prints only whether a key is set.
- The compose files pass the variables through to the API container only. After editing `/opt/bms/.env`, restart the API
  (`docs/runbooks/deploy.md`); the adapter reads its values once at start.
- Rotate the key yearly, on any staff change, after any suspected exposure and when the provider requires: create the new key at
  the provider, put it in `.env`, restart, check section 5, then revoke the old key. A rejected key shows as `auth_error` rows in
  the call log and, after five in a row, as an open breaker.
- Set a spend limit at the provider if it offers one; the gateway's own caps are the second line, not the first.
- The call log holds no prompt, no response and no provider error text, so reading it is safe; it still lives behind row-level security.
- The gateway must be called outside a database transaction (it refuses with an error otherwise): a caller inside one would hold
  that connection and its locks through up to 30 seconds of network, and take a second connection for the log. Jobs use
  `TenantJobs.callAsTenant`, never `forEachActiveTenant`.

## 3. First setup (staging, with the provider's sandbox or a cheap model)

1. Before any non-fabricated figure is sent: the dev lead obtains and files the provider's written terms on data retention and
   on use of API data for training, and the cross-border position (spec 5.11). A provider that cannot state them is not used for
   real data. Staging uses fabricated data only (`seed-lending --insights-demo`).
2. In `/opt/bms/.env` set, with your own values:
   ```
   BMS_AI_PROVIDER=openai_compatible
   BMS_AI_BASE_URL=https://api.deepseek.com
   BMS_AI_MODEL=<the provider's cheapest chat model name>
   BMS_AI_API_KEY=<the key>
   BMS_AI_PRICE_INPUT_MICRO_PER_MILLION=<micro-units of the currency per million input tokens>
   BMS_AI_PRICE_OUTPUT_MICRO_PER_MILLION=<the same for output tokens>
   BMS_AI_PRICE_CURRENCY=USD
   ```
   Prices come from the provider's price list, read at the time, and are never committed. With the prices unset, estimated cost
   is zero and only the token caps bound the spend.
3. Set the platform ceilings you accept for one business day, all tenants together: `BMS_AI_PLATFORM_DAILY_TOKENS` (default
   200,000) and `BMS_AI_PLATFORM_DAILY_COST_MICRO` (default 2,000,000, which is 2.00 of the currency). Optional: the default tenant
   caps `BMS_AI_TENANT_DAILY_TOKENS` (20,000) and `BMS_AI_TENANT_DAILY_COST_MICRO` (500,000), the per call limits
   `BMS_AI_MAX_TOKENS_PER_CALL` (800) and `BMS_AI_MAX_INPUT_TOKENS_PER_CALL` (8,000), and `BMS_AI_BUDGET_SECONDS` (30, never more).
4. Restart the API. The start log must not contain "AI provider off"; if it does, one of the three values or the key's shape is wrong.

## 4. Switching a tenant on

1. Name the tenant in `BMS_AI_ENABLED_TENANTS` by its id (the `id` of its row in `tenants`), restart the API, and tell the tenant's administrator.
2. The administrator switches the assistant on in the settings. There is no route or screen for it yet: they arrive with slice C3
   (`GET` and `PUT /lending/advisor/settings`), so until then nothing can switch a tenant on from outside the code. The switch
   records who, when and which text version they acknowledged, and is audited as `core.ai.settings.changed`.
3. To change a tenant's caps: the tenant administrator or the operator sets `daily_token_cap` and `daily_cost_cap_micro`; both
   must be at most the platform ceiling, and the lower of the two always applies. A cap of **0 means stop**: every call is refused
   before the network. A negative cap, or a zero or negative per call limit or budget in the environment, fails startup instead of
   quietly becoming a default.

## 5. Watching it

All of these are read-only queries as the owner role or through the audit and the insights of slice C3:

```sql
-- Today's calls, outcomes and what they held against the caps, per tenant.
SELECT tenant_id, outcome, reason, count(*) AS calls, sum(tokens_counted) AS tokens, sum(cost_counted_micro) AS cost_micro
  FROM ai_calls WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'Africa/Kampala') AT TIME ZONE 'Africa/Kampala'
 GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
```

A call that failed **after its request was sent** keeps its reservation against the day (or the usage the provider reported),
because a timeout or an unusable answer is still billed; only a call that never left (a refusal) holds nothing. So a flapping
provider spends the cap and then stops, instead of probing round it every minute.

Alert on: any `auth_error` (the key is wrong or revoked); five `provider_error` or `timeout` rows in a row (the breaker is open for
60 seconds, then probes once); `refused` rows with a cap reason (a cap is too low, or a loop calls too often); tokens or cost near
the platform ceiling; and a pending row older than a few minutes (the nightly job closes it as `abandoned` after an hour).

## 5a. Incident steps

| Sign | Likely cause | Do |
|---|---|---|
| `auth_error` rows, breaker opening every minute | Key wrong, expired or revoked | Put the new key in `.env`, restart; revoke the old one at the provider; confirm the next call logs `ok` |
| `provider_error` or `timeout` rows, then `breaker_open` refusals | Provider outage or slowness | Nothing to do: pages show the automatic summary. If it lasts, stop it (section 7, step 3) so the probes stop; check the provider's status page; start it again afterwards |
| `rate_limited` rows | Over the provider's rate limit | Lower the per call or platform caps, or raise the limit with the provider |
| `input_refused` rows | A caller built a prompt with digits, an email, a phone or an ID shape | A bug in the slice that builds prompts: nothing was sent; report it to the dev lead with the purpose and time (never the text, which is not stored) |
| `tenant_*_cap` or `platform_*_cap` refusals | A cap is spent | Expected at the end of a busy day; raise the cap only after reading the day's calls above |
| `internal_error` (a fallback with no row) | Our database or a bug, not the provider's | Check the API log for `ai call failed internally` (the error type is logged, never a message); it never feeds the breaker |
| A suspected leak | Anything about what was sent | Stop it at once (section 7, step 3), then ask the dev lead for the leak test of spec 5.8; the call log cannot show what was sent, only that the request hash differs |

## 6. Swapping the model or the provider

The model name is `BMS_AI_MODEL` alone: change it, restart, and the next call uses it (the call log records the model per row). A
different OpenAI-compatible provider is `BMS_AI_BASE_URL`, `BMS_AI_API_KEY`, `BMS_AI_MODEL` and the prices. Before a swap on real
data, rerun the evaluation suite of spec 5.8 (the harness is built, section 9; the run against a provider is slice C5) and re-check the provider's written terms.

## 7. Stopping it

From the finest to the coarsest, each taking effect at once or at the restart:

1. A tenant: its administrator switches the assistant off (effective on the next call), or the operator removes it from
   `BMS_AI_ENABLED_TENANTS` and restarts.
2. The caps: lower `BMS_AI_PLATFORM_DAILY_TOKENS` to 1 and restart; every call is refused before the network.
3. Everything: set `BMS_AI_PROVIDER=off` (or empty the key) and restart. The page never depends on the model; every card shows the
   deterministic "Automatic summary".

## 8. What the gateway never does

It never changes a record, never sends a message to a member, has no tools, never stores or logs what it sent or what came back,
never follows a redirect (so the key cannot be forwarded), never retries a request the provider rejected as wrong, and never lets
a provider problem become an error on the page.

## 9. Running the evaluation (offline, no model, no cost)

The evaluation harness (`AdvisorEvaluation`, issue #223, spec `docs/specs/client-extras.md` sections 5.8 and 5.13) scores what a
model's answer would meet on its way to a screen. With the recorded answers it calls no provider, spends nothing and takes a
second, so it runs in every build. Fabricated data only: nothing here reads a tenant.

1. **Run it.** `cd backend && mvn -B test -Dtest=AdvisorEvaluationTest`. The report is written to
   `backend/target/advisor-evaluation-report.txt` (not tracked): one `PASS` or `FAIL` line per case with its category, whether the
   answer was accepted or refused, and the violation codes; then the score of each category; then the tokens (counted, not
   billed) and the line `Cost: none`. The report holds codes and counts only, never a sentence of an answer or a name.
2. **The gate is 100 percent in every category** (clean answers accepted; invented, derived and mis-formatted numbers, wrong
   directions and signs, injection, personal data, small groups, missing data, scope, shape, enum and binding and a failing provider each refused
   with the expected code). A `FAIL` on an adversarial case is a hole in the validator or the prompt, never a model having a bad
   day: fix the code, not the case. A `REQUEST NOT CLEAN` means the request built for that case would have carried a real name, a
   planted string, a phone, email, ID or UUID shape, a run of six digits or a figure for a group under five loans; it was not
   sent. That is a bug in the facts or the prompt builder.
3. **The canary at the gateway.** `cd backend && mvn -B verify -Dit.test=AdvisorCanaryIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`
   (needs Docker): a seeded book whose branches and products are named with canaries goes through the real gateway with the
   recording client; the test fails if a canary is in the request the client received, in an `ai_calls` row or in the facts.
4. **The prompt contract and the catalogue.** The system prompt is the reviewed resource
   `backend/src/main/resources/advisor/system-prompt.txt`, filled with the topics and actions of the reviewed catalogue
   `backend/src/main/resources/advisor/catalogue.json` (every sentence the person reads is a template or an action text in that
   file). The model is told to return one JSON object of fact keys, a topic, an action code and a priority and no text of its own;
   `PromptContractTest` fails if the prompt stops listing the shape, a topic, an action or a rule, and `CatalogueTest` fails if a
   catalogue text holds a digit, markup or a placeholder other than `{{where}}`. Change either in a pull request like code, and
   rerun section 9, step 1. A new kind of advice is a new catalogue action: give it a topic, a text with no figure, and a golden case.
5. **Mutation check.** `ValidatorMutationTest` switches each rule of `StructuredValidator` off in turn (strict fields, topic,
   action and priority enums, action belongs to the topic, a calm action only at low priority beside no worse change, fact exists, fact not hidden,
   fact fits the topic, no duplicates, limits) and requires the golden set to fail with the cases that rule exists for accepted; a separate switch corrupts the
   phrase table to prove the second line (the number re-derivation) catches it. A rule that can be removed with the harness still
   green is untested.
6. **Adding a case.** Add it to `GoldenCases` with the answer a model might give and the code a refusal must produce; a new
   topic, action or fact family needs a case that fails without its binding. Cases use invented
   names, figures and phones only.
7. **Against a real provider.** The same cases go to a client with `AdvisorEvaluation.with(client)`; the command that runs them
   against the configured provider, records tokens and cost per brief and compares two models is slice C5 (#232). Run it on
   fabricated data (`seed-lending --insights-demo`) and never on a tenant's own figures before the provider's written terms are on
   file (section 3, step 1).
