# Shop words: plan

**Status:** Plan (issue #683; decision in ADR-113, Proposed) · **Build:** after the go-live fixes, #733 wave 14 · **Reads with:**
`frontend/src/app/glossary.ts`, ADR-095, ADR-096, ADR-109

A business can rename the words it reads in menus, tabs, page titles, breadcrumbs and table headers: "My stock" as "Store",
"Selling price" as "Price to customer". This file is the plan: what is on main today, which labels still need a key, the size of
each step and the order to build them. ADR-113 is the decision; this file does not repeat it.

## 1. What main has today

- `frontend/src/app/glossary.ts` (issue #547, D24): `WORDS`, one keyed map of 24 shop words (Buy stock, Past purchases, Count
  stock, Customers who owe, Single unit, Package, Buying price, Selling price and others), read by 34 screen files. Router crumbs
  read it for 7 retail routes.
- `frontend/src/app/glossary.test.ts`: fails when a banned or retired word shows in the copy. It is the model for the guard of
  ADR-113 decision 2.
- `frontend/src/words/` (ADR-109): JSON word lists read by the PWA and, through the `words` build context, by the server. The
  new `labels.json` goes here.
- Nothing per business: no table, no API, no Words page, no words revision.

## 2. Labels not yet in `WORDS`

Counted on main at the 2 Nov 2026 triage; the build pull request recounts and lists each key it adds.

"Places" is how many spots in the code draw such a label (each one is edited once); "keys" is how many distinct keys they need,
since a crumb and its page title share one and headers such as Item, Quantity, Branch and Date repeat.

| Area | Where it is typed today | Places | Keys (about) | Notes |
|---|---|---|---|---|
| Menu entries and sections | `areas/staff/menu.tsx` (the grouped menu of ADR-086: Shop, Lending and their entries such as Loans, Members, Savings) | 28 | 28 | Shared by phone and laptop, so one key each |
| Retail screen list | `areas/staff/retail/permissions.ts` (`label` and `hint` of each retail screen: Record a sale, Credit sales, All sales and the rest) | 102 (51 labels, 51 hints) | 102 | The hints are words too (area "word") |
| Tabs | `areas/staff/tabs.tsx`: the five tabs Sell, My stock, Buy, Money and More, and the two lending entries of the Money list (Member savings, Receive payments) | 7 (5 tabs, 2 entries) | 7 | Tightest limit: 12 characters, set by the layout suite (ADR-113 decision 5) |
| Breadcrumbs | `app/router.tsx`: `retailScreen(path, crumb)` and `staticData: { crumb }` | about 140 (105 retail, 37 other) | about 140 | 7 already read `WORDS` |
| Page titles | `<Gate title=...>` and page headings across `areas/staff` | about 230 | about 40 | Most equal a crumb and reuse its key |
| Table headers | `<th>` in 42 screen files under `areas/staff` | about 290 | about 60 | Most repeat |
| Feature search | `areas/staff/feature-search-state.ts` | 11 | none new | Reads the menu keys |
| Business set-up | `areas/staff/setup.tsx` | 7 | 7 | |
| Lending forms and lists | `areas/staff/lending/*-state.ts` and screens | about 50 | about 40 | Lending words follow once the retail ones are done (step 5) |
| **Total** | | **about 860** | **about 420** | |

Out of scope on purpose: form field labels and helper text inside a form (they are sentences, not names), error and toast text,
the operator area (`areas/platform`), codes and numbers, and stored data such as item and category names, which a business
already chooses.

## 3. Steps, size and order

Each step is one pull request, built only after the go-live fixes. Sizes: S up to a day, M a few days, L a week or more.

| Step | What | Size | Migration | Depends on |
|---|---|---|---|---|
| 1a | `frontend/src/words/labels.json` (default, area, maximum length per key); `WORDS` read from it; `useLabel` and `label`; the menu, tabs, retail screen list, feature search and Business set-up drawn through them (about 150 places, 140 keys); the guard of ADR-113 decision 2 for those areas; the tab layout check that fixes the tab limit. No word a person sees changes | M | no | none |
| 1b | Crumbs, page titles and table headers moved to keys (about 660 places, 240 keys), area by area, the guard extended to each area as it moves. No word a person sees changes | L | no | step 1a |
| 2 | `tenant_words` table with row level security; `GET`, `PUT` (create with `If-None-Match: *`, change with `If-Match`) and `DELETE` (with `If-Match`) `/api/v1/words`; the normalising and limits of decision 5 with the per-tenant lock; audit; the words revision on `/me`; the server reads `labels.json` for the keys and lengths | M | yes (one) | step 1a |
| 3 | The Words page in Business set-up: areas, search, default beside each box, Save, Reset; its page tour; the PWA keeps the last words for offline use; the layout suite checks the longest allowed label on the tabs and the menu at 360 and 412 pixels | M | no | step 2 |
| 4 | Help articles given marked references `{{word:key}}` and drawn in the shop's words, Ask BMS the same (decision 6), with the words revision in the answer cache key; report and document footers and the Words sheet in Download my data, with formula escaping (decision 7) | M | no | step 2 |
| 5 | Lending labels moved to keys and shown on the Words page | S to M | no | step 1a |

Total: L, about three to four weeks for one builder. Step 1b and step 2 can go in parallel after 1a; steps 3, 4 and 5 once step
2 has merged. Only step 2 adds a migration, at the next free number when it merges (`AGENTS.md`). The Words page lists only the
keys whose areas have moved, so it can ship before 1b ends.

## 4. Tests the build adds

- `glossary.test.ts` grows the guard: no bare string in a menu, tab, crumb or header of an area that has moved to keys; every key
  in `labels.json` is used; no two keys of one area share a default.
- A words IT:
  - the limits of decision 5: empty, too long, a line break, markup, a Cc control and a Cf format character (U+200B, U+202E)
    each refused; NFC applied before the length and duplicate checks;
  - a label starting with `=`, `+`, `-`, `@`, tab or carriage return refused;
  - the duplicate rule case-insensitive after trimming ("store " against "Store"), and against another key's default;
  - two concurrent saves of the same label to two keys of one area: exactly one passes;
  - create with `If-None-Match: *` (412 when a row exists), change with `If-Match` (412 when stale), reset with `If-Match` (412
    when stale), 428 without the header;
  - the audit row, the tenant isolation of `tenant_words`, the revision changing on every save.
- Export: the Words sheet writes labels as text cells and prefixes an apostrophe to a label starting with a formula character
  (a row put straight into the table, past the API, proves the second guard). A PDF footer lists only the changed keys drawn in
  that document, at most five and then "and N more".
- DOM tests: a renamed tab, menu entry, crumb and header show the shop's word; a reset shows the default again; the Words page
  refuses an empty or too long label in plain words.
- Help: a marked reference `{{word:tabs.more}}` shows the shop's word; the same word in free prose ("for more detail") is left as
  written; an unknown key in an article fails the corpus build.
- Ask BMS: a cached answer is not served after a word changes; the article text the model sees has only marked references replaced.
- Layout suite: five tabs of the maximum length in their widest letters fit at 360 pixels and the largest font scale; the longest
  menu label fits without overlap.

## 5. Open points for the owner

- Should a renamed word also rename the help pictures' captions? The plan says no: the pictures keep the default words, and
  the article text around them uses the shop's words.
- Should sellers see the Words page read only? The plan says no: only `core.settings.manage` sees it.
