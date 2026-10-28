# ADR-102: The setup helper sends a business's list to free tier models only where the platform names it

## Status

Proposed (issue #626, owner decision D62 of 27 Oct 2026), accepted on merge. Step 2 of the setup helper, after Set up your shop
(ADR-100, #628). Builds on ADR-096 (Ask BMS: its provider chain, keys, never-Claude guard and spend log are reused), ADR-037 (the
gateway's OpenAI-compatible adapter, which gains a picture on a user message), the item import (#146, ADR-081) and the existing
loans import (ADR-071). Migration `V112`. Endpoints in chapter 7: `GET /help/setup` (section 7.11.28),
`POST /retail/products/import/draft` and `POST /lending/existing-loans/import/draft`. No permission added.

## Context

A new owner has a list already: a stock book, a supplier invoice, a price list in Excel, or for a lender a loan register. Typing
it into the item import, or into the existing loans import's CSV, is the step where testers gave up; for the first lender it was
done by hand on 23 and 24 Oct. The owner agreed (D62) that AI may help with this one feature, for four named businesses first,
with Ask BMS's rules: free tiers first (Gemini, then Groq, then DeepSeek), never Claude, and spend tracked and capped.

Unlike an Ask BMS question, which is scrubbed of names and figures before it leaves (ADR-096 decision 3), a list is business data
by its nature: item names and prices, and for a lender borrowers' names, phones and amounts. Scrubbing would leave nothing to
read. So what is sent, what is kept and who it is sent for has to be decided, and said.

## Decision

1. **The AI only drafts.** The model's answer is read deterministically (`ItemDraftReader`, `LoanRegisterReader`) into rows of the
   existing import, which the owner changes or removes on screen. The rows become the import's own CSV text, so the import's check
   (codes, duplicates by name and unit, switched-off categories and units, the pack rules, the go-live date, product limits, the
   approval of every loan) is the one review, and its own button (Add, or Record and send for approval) is the one tap that saves.
   The draft endpoints write nothing but their log row and the spend.
2. **On per business, off by default.** The helper reads a list only for a tenant id in `BMS_SETUP_AI_TENANTS` (the platform
   names HATS, Modern Electronics, Dodo's and Yasad first). For any other business `GET /help/setup` says `enabled: false`, the
   box is not drawn, and both draft endpoints answer 403 `setup_ai_off` before anything of the business is read. The switch is
   separate from Ask BMS's, since a business may want help answered without sending its records.
3. **What is sent.** To the one provider being tried, over https to its allow-listed host: the fixed instructions; the list as
   typed, or the text of the file (a CSV or text file as UTF-8, the cells of the first sheet of an Excel workbook, the text of a
   PDF), or one picture (a photo, or the first page of a scanned PDF) drawn again on the server as a JPEG at most 2000 pixels on its
   longest side, so its metadata, a phone's location among it, never leaves (its size is read from its header first: more than 40 megapixels is refused, at most two pictures are decoded and written at once, a WebP is held to 16 megapixels since its reader may ignore subsampling, which also bounds the coefficient buffers of a progressive JPEG, and it is decoded skipping rows and columns, so no file can make the server hold a huge picture; a scanned page is drawn no larger than 2000 pixels whatever size it declares); and a short context. For a shop the context is the
   kind of shop, its active category and unit names with their usual packs, and up to 300 item names with their units, so names
   are matched and not drafted twice. For a lender it is the currency and today's date only: the member list is never sent, and a
   drafted borrower is matched to a member by phone on the server (`MemberLookup.findByPhone`), and named only to someone who may bring loans into that member's branch. Buying prices are neither asked for
   nor returned for someone without `retail.profit.read`.
4. **What is kept.** Nothing of the list, the file, the picture, the context or the draft: they live in the request and its
   answer only, and no log line holds them. `help_setup_drafts` (V112, forced RLS) keeps one row per draft: who, when, retail or
   lending, typed, photo or file, the outcome, the provider and model, the providers that failed before it as `name:reason`, how
   many rows came back, tokens and the estimated cost. Every provider call reserves and settles its cost in Ask BMS's
   `help_ask_spend`, so the DeepSeek monthly cap (`BMS_HELP_ASK_DEEPSEEK_MONTHLY_COST_MICRO`) counts both features together. The
   removal of a closed business (ADR-090) deletes them explicitly with the module's other help rows
   (`help_erase_personal_data`, replaced in `V112`); the purge after it is generic, deleting every row of every table that has a
   `tenant_id`, so it would reach them too. There is no erasure of one person apart from the business: users are anonymised,
   never deleted, and a row names its user by id only.
5. **The chain is Ask BMS's.** The same providers, keys (`BMS_HELP_ASK_*_API_KEY`, environment only), prices, allow-list and
   never-Claude checks (`AskProviders`), built a second time with the helper's own budget (25 seconds a provider by default, at
   most 30, `BMS_SETUP_AI_BUDGET_SECONDS`) and output allowance (8000 tokens). A photo goes to a chain of its own: Gemini (its Ask
   BMS model, or `BMS_SETUP_AI_GEMINI_PHOTO_MODEL`) and Groq only with a vision model in `BMS_SETUP_AI_GROQ_PHOTO_MODEL`; DeepSeek
   reads no pictures and is never sent one. A picture is only ever a `data:` URL of a JPEG, PNG or WebP on a user message
   (`LlmClient.Message`), so a provider is never asked to fetch a link. `NeverClaudeTest` covers both chains.
6. **Limits before the work.** A person may ask for `BMS_SETUP_AI_USER_HOURLY_DRAFTS` (10) drafts an hour (429
   `setup_ai_rate_limited`) and a business may send `BMS_SETUP_AI_DAILY_DRAFTS` (30) a day to the providers; both are counted on
   `help_setup_drafts` under the business's advisory lock, the row written before the file is decoded or a provider asked, and
   always finished, so a refused file or a failure never holds a place in the day's cap.
7. **A plain fallback.** No provider set, every provider failing or answering with something that is not the JSON asked for, the
   day's drafts used up, or a file with nothing to read is never an error: the response carries `reason` (`no_provider`,
   `all_failed`, `daily_cap`, `unreadable`) and no rows, and the screen says in one line why and that the owner can type the list
   or import a file, which work as before. A file of the wrong kind or size is 422 `setup_file_not_read` in plain words.
8. **A register brings its members.** The existing loans import gains two optional columns, `member_name` and `member_phone`,
   for a loan row with no `member_no`: the one member with that phone is used when the caller may bring loans into that member's branch (a different name is a warning on the row, and the helper's screen asks the owner to tick that it is the same person before it checks; a file typed or pasted by hand gets the row's warning only, as every import warning is read before Record); otherwise, with no holder, two, or one the caller may not see, the check says word for word that a new member comes in, so it tells nothing about phones held elsewhere, and a commit that cannot give the phone records nothing (`import_member_phone_taken`, naming no one); the commit itself still differs (a phone nobody holds commits, a held one is refused), which is accepted: it names no one, every try is a whole checked and committed file in the audit log (`lending.loan.existing_imported` on success), and the member form already tells who holds a phone to anyone who may add members; with none, the check says a new member comes in
   with the loan and the commit adds the member (name and phone, no ID, KYC later) through the member form's rules
   (`MemberIntake`, the caller's `lending.members.create` scope, the phone, the plan's member limit, which the check applies too, so a check never says Ready where the commit would refuse) in the same transaction, in
   the branch in use (`branch_id`) or the only branch the caller may add members in. A monthly rate and a term with no total
   written are turned into a flat total by the reader, marked so for the owner to check; the import decides the rest.

## Consequences

**Better:**

- An owner with a stock book, an invoice, a spreadsheet or a register gets a filled import in one step, and still checks every
  row and taps once to add, with every rule of the import applied as for a typed file.
- Losing the providers, a key or the day's allowance costs nothing but the typing: the import works as before.
- One spend log and one DeepSeek cap for every AI feature of the help module.

**Worse:**

- A business's item names, prices and, for a lender, borrowers' names, phones and amounts go to a third party for the businesses
  named. The operator checks each provider's free tier terms on data use before naming a business, and names only those whose
  owner agreed.
- Free tiers rate-limit; a large list can need DeepSeek, which costs money (capped), or be split.
- A drafted price or date can be wrong. The review shows every field, the import's check catches what breaks a rule, and a
  worked-out loan total says so; nothing can catch a plausible wrong number, which is why the owner checks.
- The opening stock is not drafted: the item import has no stock column. The owner counts the stock after the items are in.
- The existing loans import can now add members, so a holder of `lending.loans.bring_in` without `lending.members.create` sees a
  row refused where the file names a new member.

**Watch for:**

- The vision model names: Groq retires models often; an unset `BMS_SETUP_AI_GROQ_PHOTO_MODEL` simply leaves Groq out of photos.
- A provider whose free tier starts training on inputs: take the business out of `BMS_SETUP_AI_TENANTS` until it is replaced.
- `help_setup_drafts` has no retention beyond the removal of the business; a nightly job may delete rows older than a day once the counts are not
  wanted for the review of spend.
