# ADR-096: Ask BMS answers from the help corpus only, free tier providers first, never Claude

## Status

Proposed (issue #20, owner decision D52 of 26 Oct 2026), accepted on merge. Step 2 of in-app help, on top of ADR-095 (the help
corpus and its content-hash revision). Builds on ADR-037 (the language model gateway: its OpenAI-compatible adapter is reused,
its advisor switch and caps are not) and ADR-003 (tenant isolation). Migration `V108`. Endpoint `/help/ask` (chapter 7 section
7.11.28). No permission added.

## Context

People ask how to do things ("how do I invite a cashier?", "why can't I sell at the store?"). How do I...? (ADR-095) answers
by search, but only when the person types the article's own words. The owner wants a question in the person's own words
answered, as the sister apps do, with three rules (#20 and the D52 comment): the answer comes from the guide and nothing else,
the cheapest providers are used first (free tiers, then a cheap paid one), and Claude is never used. It is a help feature, not
a data feature: it must never read or send a business's records or personal data. The review on #20 added that a cached
answer must die the moment anything that produced it changes (an article, a role's permissions, the model or the prompt).

The evaluation asked for on #20 of TypeSafe Jev (a choice model to pick the article) is not adopted here: early access is not
confirmed for our account and the full-text search below already picks the article deterministically at this corpus size. It
can replace the retrieval step later without changing the rest.

## Decision

1. **The corpus is the only knowledge.** The server reads the very Markdown files the app bundles
   (`frontend/src/help/articles`): Maven copies them onto the classpath and the image build passes the folder as the `help`
   build context. The server computes the same revision as the Vite plugin (a shared fixture value is asserted on both sides).
   No business table is read: the module depends only on the kernel, `core.tenancy` and `core.ai`.
2. **Retrieval is PostgreSQL full-text search, filtered to the asker.** The articles of the asker's locale (English today;
   any other locale falls back to it) are passed to one query as arrays, weighted title A, search words B, text C, and
   matched by an OR of the question's lexemes; ranked by how many of the question's words an article has, then by
   `ts_rank`. The articles the model may read are those the asker's permissions and modules allow, by the tour step rule of
   ADR-095 decision 2. When the best match is an article the asker may not read, and no readable one matches as well, the
   answer says from its title that it is another role's job and to ask the administrator; nothing is sent anywhere. No
   corpus table: the files are the one copy.
3. **No business data leaves the server.** The question is scrubbed before anything else: emails, UUIDs, national ID shapes,
   phone numbers, any figure of four or more digits and every capitalised word that is neither a word of the articles nor a
   question word become `[email]`, `[id]`, `[phone]`, `[number]`, `[name]`, after NFKC normalisation so shapes cannot be dressed
   up. A name typed in lower case is not found, which is why the text of a question is never stored (only the SHA-256 of the
   scrubbed, normalised question) and why the prompt holds nothing but the scrubbed question and up to three articles.
4. **Provider chain, cheapest first; never Claude.** Gemini (a Flash or Flash-Lite model on the free tier, through Google's
   OpenAI-compatible endpoint; `gemini-3.5-flash-lite` by default, sent `reasoning_effort` `minimal` so its thinking fits
   the budget, #601), then Groq (a small free-tier model), then DeepSeek (its cheapest chat model, paid, last resort).
   All three go through the gateway's one OpenAI-compatible adapter (`core.ai.LlmClientFactory`): https only, no redirects,
   retries on 429 and 5xx inside a budget (10 seconds per provider by default), temperature 0, JSON answers, and no provider
   text kept. Claude is excluded three ways: only the names `gemini`, `groq` and `deepseek` exist (the code, and a CHECK in
   `V108`); each may call one host only, from an allow-list that is a constant in the code (`AskProviders.ALLOWED`), so no
   property or environment value can point a provider at another host (a proxy included), and a base URL off that host
   stops startup; and anything naming Anthropic or Claude in a base URL or a model stops startup too. `NeverClaudeTest`
   proves all three, that an allow-list given as a property is ignored, that no Anthropic endpoint, SDK or model id is in
   anything Ask BMS reaches (its module, configuration block, screens, client, articles and deploy environment), and that
   `core.help` depends on nothing of `core.ai` but its neutral port (`LlmClient`, `LlmClientFactory`, `LlmException`) and on
   no class naming Anthropic or Claude; `core.ai` itself is not scanned, since it holds the advisor's own provider switch
   (ADR-037), except the two files that make Ask BMS's calls (`OpenAiCompatibleFactory`, the `LlmClientFactory` bean, and
   `OpenAiCompatibleClient`), and an integration test asserts the real bean returns that client calling the base URL it was
   given. Model text is kept only when it cites a given article and holds no link, email or long
   number, none of which the articles hold.
5. **Grounded answers only.** The model is told to answer from the given articles only, in at most five short sentences, and
   to reply `{"answer", "article"}`. An answer that is not that JSON, is too long, or cites an article it was not given is
   refused and the next provider is tried. An empty answer (the articles do not answer it) shows the matching articles and
   stops the chain, since every model reads the same articles. The cited article is linked to its How do I...? page, so the
   pictures are one tap away.
6. **The cache key is everything that produced the answer.** SHA-256 of the prompt version, the provider and model chain, the
   corpus revision, the permission matrix version (the first 16 hex digits of the SHA-256 of every role and permission
   pair), the locale, the asker's roles, their permission set, their modules and the normalised scrubbed question. A change
   to any is a new key on the very next question; nothing is flushed. Only a model answer to a confident match (half or more
   of the question's words in the best article) is cached; a weak match, the article list and every deterministic answer are
   not. Rows live per business (`help_ask_answers`, forced RLS) with a 30 day expiry as a safety net only.
7. **Off by default, demo businesses first.** Ask BMS is on only for the tenant ids in `BMS_HELP_ASK_TENANTS` (the operator
   names the demo businesses first, D52); for every other business the box is not drawn and the endpoint answers 403
   `ask_bms_off`. Keys are environment values only (`BMS_HELP_ASK_GEMINI_API_KEY`, `..._GROQ_...`, `..._DEEPSEEK_...`,
   `docs/runbooks/ask-bms.md`); a provider without a key is skipped.
8. **Caps and the spend log, recorded before the work.** Each person may ask `BMS_HELP_ASK_USER_HOURLY_QUESTIONS` (30)
   questions an hour on every path (429 `ask_rate_limited`), and each business may send `BMS_HELP_ASK_DAILY_QUESTIONS` (100)
   a day to the providers; past the daily cap, and with no provider configured or all failing, the best matching articles
   are shown, so help never stops. Both are tickets (`help_ask_tickets`) taken under the business's advisory lock before the
   work they count, so questions sent at once are counted one after the other and cannot all pass. Every provider call
   reserves the most it can cost in `help_ask_spend` before it is made and settles with what the provider reported after;
   the spend belongs to the provider called, whatever the question's outcome, so a paid answer that is refused still
   counts. An answer that reports no usage is settled at its reservation, and a failed call keeps its reservation unless
   it was refused outright at its only attempt (a rate limit, a rejected key, a bad request); the question's log counts
   the same amounts. DeepSeek's reservation is taken under one platform-wide lock and refused once every business together would pass
   `BMS_HELP_ASK_DEEPSEEK_MONTHLY_COST_MICRO` this calendar month (a SECURITY DEFINER sum, `help_ask_platform_spend`, in the
   cap's currency only, so a change of `BMS_HELP_ASK_COST_CURRENCY` never adds two units), and
   DeepSeek is skipped altogether until both of its prices are set, since with a price of 0 no cap could hold. Every question
   is a row of `help_ask_questions`: who asked, when, the hash, the outcome, the provider and model, the providers that
   failed before it as `name:reason`, tokens and the estimated cost. Never the text. Removing a closed business (ADR-090) deletes its
   cached answers and limit tickets through the module's `PersonalDataEraser` (`help_erase_personal_data`); the question log
   and the spend rows hold a user id, hashes and counts only, and its purge deletes them.

## Consequences

**Better:**

- A question in the person's own words is answered from the guide, with the article linked, at no cost on the free tiers;
  repeat questions are free and give the same text.
- The corpus, the permissions and the model each retire stale answers on their own, with no flush.
- Losing every provider, a key or the budget costs nothing but the phrasing: the articles are still shown.

**Worse:**

- The image build needs a second build context (`help`), so building the API image by hand needs
  `--build-context help=frontend/src/help/articles` (the Makefile and compose do it).
- Free tiers rate-limit and may change terms; the chain falls through to DeepSeek, which costs money (capped).
- The name scrub is a heuristic. It is backed by never storing the text, but a lower case name, or a name that is also a
  word of the articles ("Grace", as in a grace period), can still reach a provider.
- The thumbs up and down feedback and the "show me" tour launch of #20's first sketch are not built here.

**Watch for:**

- Follow-up: `help_ask_tickets` has no retention yet and grows by one or two rows a question; a nightly job should delete
  rows older than a day (the limits read the last hour and the current day only).

- A provider's terms for free-tier data use: the operator checks them before naming a business.
- A new locale: a folder of articles in that language, its full-text configuration, and the locale already in the key.
- A model id change: it is part of the cache key, so the first questions after it each call a provider.
- Answers citing the right article with wrong steps: compare a few cached answers with their articles after each corpus
  change.

## Amendment: no answer is said plainly (issue #609)

When the help has no answer (`source` `no_match`, or `articles` with the new `reason` `not_in_articles` or `all_failed`), the box
says first, in one line, "I could not find this in the help yet. Tap Ask
us and it comes to the team.", with the Ask us button right under it carrying the question. Only then does it list the
articles the server matched, and only those whose title or search words share a word of the question that is not a stop word
("how", "do", "I", "this", "app" and the like; `relatedArticles` in `corpus.ts`). A word found only in an article's text is not
enough, so a question no article answers lists none rather than unrelated ones, and the page drops its own "Not answered? Ask
us" while that line shows, so there is one. With `reason` `no_provider` or `daily_cap` no model was asked, so the box does not
say the help has none: it lists the matching articles as before. The response gains `reason` (null unless `source` is
`articles`), the same value as the log's `fallback_reason`; an older app ignores it.
