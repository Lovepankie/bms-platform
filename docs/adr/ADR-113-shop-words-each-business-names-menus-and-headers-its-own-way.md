# ADR-113: Shop words: each business names its menus, titles and headers its own way

## Status

Proposed (issue #683, owner request of 29 Oct 2026). Plan only: nothing here is built yet. The plan, the label inventory, the
size and the build order are in `docs/specs/shop-words-plan.md`. The build is one later task with one migration (#733, wave
14). Builds on the glossary of issue #547 (`frontend/src/app/glossary.ts`, owner decision D24), ADR-095 (the help corpus and its
revision), ADR-096 (Ask BMS and its answer cache), ADR-074 (Download my data), ADR-086 (one grouped menu) and ADR-109 (one word
list read by the PWA and the server).

## Context

Shops want to call things their own way: "My stock" as "Store", "Selling price" as "Price to customer". Today every word a
person sees is fixed in the app. The glossary of #547 gave the most used shop words one keyed map, `WORDS`, read by 34 screen
files and guarded by `glossary.test.ts`, so a word has a stable key to rename. Most other labels are still typed where they are
drawn: menu entries, tabs, page titles, breadcrumbs and table headers. There is no per-business word table, no page to change a
word and nothing that tells help, Ask BMS, reports or exports which words a business uses.

## Decision

1. **One list of keys, shipped with the app.** Every renamable word gets a stable key in one file,
   `frontend/src/words/labels.json`, read by the PWA and by the server the way ADR-109's `unit-words.json` is (the `words` build
   context). Each entry has the key, the default English label, the area (menu, tab, title, header or word) and a maximum length.
   `WORDS` becomes a typed view of that file, so the 34 files that read it keep working. A key never changes meaning and is never
   reused; a retired key stays in the file marked retired, so a stored label for it is ignored, not misapplied.
2. **One label function.** Every menu entry, tab, page title, breadcrumb and table header is drawn through `useLabel(key)` (and
   `label(words, key)` outside React), which returns the business's word if it set one, else the default. Routes, permission
   names, API fields, codes and stored data never change: only words a person reads do. A design guard fails a menu, tab, crumb
   or header typed as a bare string once its area has moved to keys.
3. **A per-business table holds only the changes.** `tenant_words (tenant_id, key, label, version, updated_at, updated_by)`, one
   row per key the business changed, under the tenant row level security of every tenant table. No row means the default. A new
   key added to the app is therefore the default for every business with no backfill. The `words revision` of a business is a hash
   of its rows, sent with `/me` so the PWA refetches the words only when they change and works offline with the last copy.
4. **The owner changes words on a Words page in Business set-up** (`/staff/setup/words`), under the existing
   `core.settings.manage` permission; no new permission. The page lists the keys by area, each with its default shown beside the
   box, a search, Save per row and Reset to the default (which deletes the row). Every change is audited with the key, the old and
   the new label. API: `GET /api/v1/words` (any signed-in staff user: the changed rows, each with its version, and the
   revision); `PUT /api/v1/words/{key}` with an `Idempotency-Key` and either `If-None-Match: *` to create the first row of a key
   (refused with 412 if a row already exists, so two first renames never race) or `If-Match` on the row's version to change it;
   `DELETE /api/v1/words/{key}` (Reset) with `If-Match` on the row's version, refused with 412 if someone changed it since, so a
   reset never silently undoes another owner's save. A `PUT` or `DELETE` without the matching header is refused with 428.
5. **Limits, checked by the server, after normalising.** The server first applies Unicode NFC and trims, then checks. A label is
   plain text: not empty; at most the key's maximum length in characters (12 for the bottom tabs, 24 for menu entries, 40 for
   titles and headers, 60 for other words); no line breaks; no markup characters (`<`, `>`, `{`, `}`); no control characters of
   Unicode category Cc and no format characters of category Cf (zero width and bidirectional marks such as U+200B and U+202E,
   which could make two labels look the same or show text reversed); and no leading `=`, `+`, `-`, `@`, tab or carriage return,
   so a label can never start a spreadsheet formula. The tab limit is set by measurement, not taste: the layout suite draws five
   tabs, each holding a label of the maximum length in its widest letters, at 360 pixels and the largest font scale, and fails
   if one wraps past two lines or overflows; the limit is lowered if it does. A label may not equal another key's label in the
   same area, compared case-insensitively after the same normalising and trimming, nor that key's default when it has no row
   (two menu entries named "Store" would be a dead end). Saves of one business are serialised (a transaction-scoped advisory
   lock on the tenant) so two concurrent saves in one area cannot both pass the duplicate check. An unknown or retired key is
   404. A refusal names the rule in plain words.
6. **Help articles and Ask BMS use the shop's words, only where an article marks them.** Free prose is never rewritten: "for
   more detail" must not become "for extras detail" when the More tab is renamed. An article names a screen word with a marked
   reference, `{{word:tabs.more}}`, which the build checks against `labels.json` (an unknown key fails the help corpus build,
   ADR-095). When an article is drawn, the PWA replaces each marked reference with the business's word, or the default when it
   has none; everything else stays as written. Ask BMS does the same to the article text it retrieves before the model sees it,
   tells the model the business's changed words, and adds the words revision to its answer cache key (ADR-096), so a cached
   answer in the old words is never served. Search matches both the default and the shop's word. The existing articles are
   given marked references in the build step that brings this in.
7. **Reports and exports say which words were used.** A PDF report or document drawn with changed words carries one footer line
   listing only the changed keys drawn in that document, "Words used: Store for My stock, Price to customer for Selling price",
   at most five, then "and N more, see Words in Business set-up", so a business with many renames never gets a footer that
   overflows an invoice; nothing when none of its words changed. Download my data (ADR-074) keeps its column headers in the default words, so a
   file stays readable by the import and by anyone the business sends it to, and gains a Words sheet listing every changed key,
   its default and the business's label. Every label written to a sheet is written as a text cell and, as a second guard behind
   decision 5, a label starting with `=`, `+`, `-`, `@`, tab or carriage return is prefixed with an apostrophe, so no export can
   carry a formula.
8. **Translations later use the same keys.** A language is a second file of the same keys (`labels.lg.json` for Luganda,
   `labels.sw.json` for Swahili) chosen per person. The label function then reads, in order: the business's own word, the
   language file, the default. Building a language is not part of this decision; the keys are made so it needs no second pass over
   the screens.
9. **Order of the build.** The keys and the label function come first and change no word a person sees; the table, the API and
   the Words page come next; help, Ask BMS, reports and exports last. Each step is its own pull request after the go-live fixes.

## Consequences

Better: a business sees its own words in every menu, title and header without a code change; a new screen starts with keys
because the guard asks for them; help, Ask BMS and documents agree with the screens; the same keys carry a later translation.

Worse: every typed label has to move to a key once (about 860 places to edit, but about 420 keys, since most crumbs and titles
share a key and most headers repeat; `docs/specs/shop-words-plan.md` section 2); a label longer than the
default can wrap where the default did not, so the maximum lengths are tight and the layout suite checks the longest allowed
label on the phone screens; a tour or help picture still shows the default words; a support call has to ask which words the
business uses (the Words sheet and the footer answer it).

To watch: one more migration (the table), added in the build pull request at the next free number; keys that drift from what a
screen draws (the guard of decision 2); a business that renames a word to another key's default, which the duplicate rule of
decision 5 refuses only within one area.
