# Runbook: Ask BMS (`core.help`, ADR-096)

**Scope:** what an operator does to switch Ask BMS on for a business, configure its providers, watch its spend and stop it. Ask
BMS answers How do I questions from the help articles (ADR-095) only; it never reads a business's records and sends a provider
nothing but the scrubbed question and up to three articles. **Nothing here is a real key, tenant or price: every value below is
a placeholder.**

## 1. What has to be true for a question to reach a provider

| Switch | Where | Off means |
|---|---|---|
| The business is named | `BMS_HELP_ASK_TENANTS`: tenant **ids** (UUIDs, never slugs), comma separated; an entry that is not a UUID names nobody | The Ask BMS box is not drawn and `POST /help/ask` answers 403 `ask_bms_off`. How do I...? works as before |
| A provider has a key | `BMS_HELP_ASK_GEMINI_API_KEY`, `BMS_HELP_ASK_GROQ_API_KEY`, `BMS_HELP_ASK_DEEPSEEK_API_KEY` | That provider is skipped. With none at all every question is answered with the matching articles (`fallback_reason` `no_provider`) |
| The base URL is on the provider's host | A constant in the code (`AskProviders.ALLOWED`: `generativelanguage.googleapis.com`, `api.groq.com`, `api.deepseek.com`); no setting changes it | A base URL on any other host (a proxy too), or anything naming Anthropic or Claude, stops startup |
| DeepSeek has both prices | `BMS_HELP_ASK_DEEPSEEK_PRICE_INPUT_MICRO_PER_MILLION` and `..._OUTPUT_...` above 0 | DeepSeek is skipped with a warning at start: a paid provider with no price has no spend cap |
| The person's hour is not spent | `BMS_HELP_ASK_USER_HOURLY_QUESTIONS` (default 30), every question counted | 429 `ask_rate_limited`; How do I...? still answers |
| The business's daily cap is not spent | `BMS_HELP_ASK_DAILY_QUESTIONS` (default 100; 0 stops the providers) | The matching articles are shown (`daily_cap`). Cached answers still answer |
| DeepSeek's monthly spend is not spent | `BMS_HELP_ASK_DEEPSEEK_MONTHLY_COST_MICRO` (default 5000000 micro-units of the cost currency, every business together, per calendar month in UTC; calls in flight count at their reservation) | DeepSeek is skipped (`deepseek:spend_cap` in `failures`) |

**Claude is never used.** There is no setting that adds a provider: the three names are in the code and in a CHECK of the
database, and `NeverClaudeTest` fails the build if an Anthropic endpoint, SDK or model id appears in this feature.

## 2. Environment names

| Name | Default | Notes |
|---|---|---|
| `BMS_HELP_ASK_TENANTS` | empty | Turn on the demo businesses first (owner decision D52), then others one at a time |
| `BMS_HELP_ASK_GEMINI_API_KEY` | empty | Google AI Studio key on the free tier. Secret |
| `BMS_HELP_ASK_GEMINI_MODEL` | `gemini-3.5-flash-lite` | The cheapest Flash or Flash-Lite model the free tier offers to new keys (`gemini-2.5-flash-lite` is refused for them, #601); a swap is this name only |
| `BMS_HELP_ASK_GEMINI_REASONING` | `minimal` | Gemini only: the `reasoning_effort` sent, `minimal`, `low`, `medium` or `high` (anything else stops startup). Gemini 3 models cannot turn thinking off and the OpenAI-compatible endpoint refuses `none` with 400, so `minimal`, the lowest level Google documents, is sent to keep a grounded answer inside the 10 second budget (#601). Empty means the default. Groq and DeepSeek are never sent it |
| `BMS_HELP_ASK_GROQ_API_KEY` | empty | Groq key on the free tier. Secret |
| `BMS_HELP_ASK_GROQ_MODEL` | `llama-3.1-8b-instant` | A small free-tier model |
| `BMS_HELP_ASK_DEEPSEEK_API_KEY` | empty | Paid, last resort. Secret |
| `BMS_HELP_ASK_DEEPSEEK_MODEL` | `deepseek-chat` | The cheapest chat model |
| `BMS_HELP_ASK_DEEPSEEK_PRICE_INPUT_MICRO_PER_MILLION`, `..._OUTPUT_...` | 0 | Micro-units per million tokens, from the provider's price page. Both are needed: while either is 0 DeepSeek is skipped |
| `BMS_HELP_ASK_GEMINI_PRICE_...`, `BMS_HELP_ASK_GROQ_PRICE_...` | 0 | Only if a free tier ends |
| `BMS_HELP_ASK_DAILY_QUESTIONS` | 100 | Questions a business may send to the providers a day, in its own time zone |
| `BMS_HELP_ASK_USER_HOURLY_QUESTIONS` | 30 | Questions one person may ask in any hour, on every path |
| `BMS_HELP_ASK_DEEPSEEK_MONTHLY_COST_MICRO` | 5000000 | Every business together |
| `BMS_HELP_ASK_BUDGET_SECONDS` | 10 | One provider's time with its retries, at most 15, before the next is tried |
| `BMS_HELP_ASK_MAX_TOKENS`, `BMS_HELP_ASK_CACHE_DAYS`, `BMS_HELP_ASK_COST_CURRENCY` | 400, 30, `USD` | Rarely changed |
| `BMS_HELP_ASK_GEMINI_BASE_URL`, `..._GROQ_...`, `..._DEEPSEEK_...` | the providers' OpenAI-compatible endpoints | Must stay on the provider's host (startup refuses another); not in the compose files on purpose |

Keys live in `/opt/bms/.env` (mode 600) and nowhere else: not in the repository, a fixture, a log or an error. The compose files
pass them to the API container only. After a change, restart the API (`docs/runbooks/deploy.md`); values are read at start.

## 3. Switch a business on

1. Read the provider's current terms for free-tier data use; Ask BMS sends only a scrubbed question and article text, but the
   operator decides.
2. Put at least the Gemini key in `/opt/bms/.env`, and the Groq key as the second.
3. Add the business's tenant id to `BMS_HELP_ASK_TENANTS` and restart the API. The start log says
   `Ask BMS providers: [gemini, groq, ...]`.
4. Sign in to the business as an administrator, open Help and feedback, How do I...?, and ask "How do I invite a cashier?".
   The answer names "How do I invite someone to use BMS?" and links it. Ask again: the same text comes back from the cache.

## 4. Watch it

The question log is `help_ask_questions` (one row a question; never its text) and the spend is `help_ask_spend` (one row a
provider call, reserved before it and settled after). On the database host, as `bms_owner`:

```sql
-- Today's questions by outcome and provider, every business.
SELECT outcome, fallback_reason, provider, count(*), sum(cost_micro) AS cost_micro
  FROM help_ask_questions WHERE asked_at >= date_trunc('day', now())
 GROUP BY 1, 2, 3 ORDER BY 4 DESC;

-- This month's spend by provider, every business (calls in flight at their reservation).
SELECT provider, count(*), sum(cost_micro) AS cost_micro, count(*) FILTER (WHERE settled_at IS NULL) AS unsettled
  FROM help_ask_spend WHERE reserved_at >= date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
 GROUP BY 1;

-- Which providers failed and why, this week.
SELECT f AS failure, count(*) FROM help_ask_questions, unnest(failures) AS f
 WHERE asked_at >= now() - interval '7 days' GROUP BY 1 ORDER BY 2 DESC;
```

Many `gemini:rate_limited` rows mean the free tier's limit is reached: the chain falls to Groq and then DeepSeek, which costs
money. Many `auth_error` rows mean a key is wrong or revoked. Many `articles` / `all_failed` rows mean every provider is down;
people still get the articles.

An `unsettled` spend row is a call still in flight, or one that may have been billed but reported nothing (a timeout, a 5xx,
a broken connection, a retried call, a failure of ours after sending, or a process that stopped mid-call). It counts against
DeepSeek's month at its reservation, the most the call could have cost, and stays so: nothing settles it later. That is the
safe side; compare with the provider's own usage page when the month's sum looks high, and a high count of unsettled rows
with no matching provider usage means calls that never reached it.

## 5. Stop it

- One business: remove its id from `BMS_HELP_ASK_TENANTS` and restart. The box disappears on the next page load.
- One provider: remove its key and restart; the chain skips it.
- Everything: empty `BMS_HELP_ASK_TENANTS`. Nothing is deleted; cached answers expire after `BMS_HELP_ASK_CACHE_DAYS`.

## 6. Rotate a key

Create the new key at the provider, put it in `.env`, restart, ask a question (section 3 step 4), then revoke the old key.

## 7. The setup helper (ADR-102, issue #626)

The setup helper uses the same providers, keys, allow-list and spend log to read a business's own list (typed, a photo of a
stock book, invoice or loan register, or a CSV, Excel or PDF file) into draft rows of the item import or the existing loans
import. **This is business data** (item names and prices; for a lender borrowers' names, phones and amounts), so it has its own
switch, and the owner's agreement comes first (owner decision D62). It saves nothing: the owner checks the rows in the import and
taps once to add them.

| Name | Default | Notes |
|---|---|---|
| `BMS_SETUP_AI_TENANTS` | empty | Tenant **ids**, comma separated. Separate from `BMS_HELP_ASK_TENANTS`. Name HATS, Modern Electronics, Dodo's and Yasad first (D62), each only once its owner has agreed |
| `BMS_SETUP_AI_GEMINI_PHOTO_MODEL` | empty | The Gemini model that reads a photo; empty is `BMS_HELP_ASK_GEMINI_MODEL`, which reads pictures |
| `BMS_SETUP_AI_GROQ_PHOTO_MODEL` | empty | A Groq vision model; empty leaves Groq out of photos. DeepSeek reads no pictures and is never sent one |
| `BMS_SETUP_AI_DAILY_DRAFTS` | 30 | Drafts one business may send to the providers a day |
| `BMS_SETUP_AI_USER_HOURLY_DRAFTS` | 10 | Drafts one person may ask for in any hour (429 `setup_ai_rate_limited` past it) |
| `BMS_SETUP_AI_BUDGET_SECONDS` | 25 | One provider's time with its retries, at most 30 |
| `BMS_SETUP_AI_MAX_TOKENS`, `BMS_SETUP_AI_MAX_TEXT_CHARS` | 8000, 30000 | The draft's output allowance and the longest list sent |

To switch a business on: read each provider's free-tier terms on data use (a provider that trains on inputs is not used for
this), add the tenant id to `BMS_SETUP_AI_TENANTS` and restart. The start log says `Setup helper providers: [gemini, ...]` and
`Setup helper photos providers: [gemini]`. Sign in as an administrator, open Items, Import items, and type two fabricated items
into Let the app read your list: the rows come back and nothing is added until Add.

Watch it on the database host, as `bms_owner` (the draft log never holds the list; the spend is in `help_ask_spend` with Ask
BMS's, and DeepSeek's monthly cap counts both):

```sql
-- Today's drafts by kind, how the list was given, outcome and provider, every business.
SELECT kind, source, outcome, provider, count(*), sum(rows_drafted) AS rows, sum(cost_micro) AS cost_micro
  FROM help_setup_drafts WHERE asked_at >= date_trunc('day', now())
 GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;
```

To stop it for one business, remove its id from `BMS_SETUP_AI_TENANTS` and restart: the box disappears and the imports work as
before. Nothing of a list is kept to delete.
