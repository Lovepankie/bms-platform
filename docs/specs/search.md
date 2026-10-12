# Search on every screen

**Status:** Draft · **Task:** #241 · **ADR:** ADR-039 · **Scope:** the staff PWA and the lending and retail list endpoints

The owner requires search on all screens to be robust, comprehensive and realtime. This spec records
the audit that started the work (part 1), then the convention the build follows (parts 2 to 4).

## 1. Audit (before this change)

### 1.1 Screens

"Trigger" is what makes the request go out. "Cancel" is what happens to a slower, older answer.

| Screen (file under `frontend/src/areas/staff/`) | Box | Trigger | Backend call | Paging when the text changes | Cancel and flicker |
|---|---|---|---|---|---|
| `lending/loans.tsx` | Loan number, member number or name | Submit button or Enter | `GET /lending/loans?q=` | Cursor list keyed on `q`, so page 1 | Key change shows the spinner state, list vanishes |
| `lending/savings.tsx` | Account number, member number or name | Submit | `GET /lending/savings-accounts?q=` | As above | As above |
| `lending/investments.tsx` | Account number, member number or name | Submit | `GET /lending/investments?q=` | As above | As above |
| `lending/investment-new.tsx` (member picker) | Member number or name | Submit, then only when 2 or more characters | `GET /lending/members?q=&limit=10` | No paging, first 10 | Result replaced on arrival |
| `retail/sales.tsx` credit buyer | Buyer | Every keystroke | `GET /retail/sales?buyer=` | Keyed filter, page 1 | No debounce: one request per key; list vanishes between keys |
| `retail/sales.tsx` all sales, buyer | Buyer | Every keystroke | `GET /retail/sales?buyer=` | As above | As above |
| `retail/sales.tsx` all sales, item | Item (name or code) | Every keystroke | `GET /retail/products?query=` (first 50, no paging) | Not paged | No debounce |
| `retail/stock.tsx` (also the All branches view, the by-location view of #193) | Name, code or category | Every keystroke | `GET /retail/stock?query=` and `/stock/all-branches?query=` | Keyed, page 1 | No debounce. Converted in #242 and its follow-up |
| `retail/stocktake.tsx` | Name, code or category | Every keystroke, in the browser over the full stock list | none (filters `GET /retail/stock` rows in memory) | Not paged | Instant, but `toLowerCase().includes`: no accents, no multi-term |
| `retail/usage.tsx` | Item | Every keystroke | `GET /retail/products?query=&branch_id=` (first 50) | Not paged | No debounce |
| `retail/restock.tsx` | Item | Every keystroke | `GET /retail/products?query=` (first 50) | Not paged | No debounce |
| `retail/ui.tsx` `ProductPicker` (sale, transfer) | Item | Every keystroke | `GET /retail/products?query=&branch_id=` (first 50, 20 shown) | Not paged | No debounce |
| `retail/catalogue-people.tsx` (credit buyers, cash parties) | Name | Debounced 300 ms (local `useDebounced`) | `GET /retail/customers?query=`, `GET /retail/cash-parties?query=` | Cursor list keyed on text, page 1 | Debounce only; list vanishes while the next arrives |
| `retail/catalogue-products.tsx` (Items) | Name, code or category | Every keystroke | `GET /retail/products?query=` | Keyed, page 1 | No debounce. Converted in #242 and its follow-up |
| `insights/page.tsx` | none | none | none | n/a | No search box today (filters are dropdowns) |
| `platform/application.tsx` | none | none | none | n/a | A sign-up form, no search |
| `platform/applications.tsx` | none (status filter only) | n/a | `applications?status=` | n/a | n/a |

No screen announced its result count to assistive technology, none had a clear button or an Escape
key, none showed "Searching", and the empty result was a bare "No ... matches" or nothing.

### 1.2 Backend queries

Every list is keyset paged on a cursor (`sortKey|id`, base64url). Branch scope is a separate
`branch_id IN (...)` predicate, ANDed with the search, and RLS applies on top.

| Endpoint (param) | Repository | Fields matched | How | Order |
|---|---|---|---|---|
| `GET /lending/members` (`q`) | `MemberRepository.page` | `member_no` and `national_id` exact (NIN upper-cased), `full_name` substring, `phone_e164` suffix when 4 or more digits | `=` and `ILIKE` with a backslash escape; the whole text is one phrase | `member_no` |
| `GET /lending/loans` (`q`) | `LoanRepository.page` | `loan_no` and `member_no` prefix, member `full_name` substring | `ILIKE`; escape of `\ % _`; one phrase | `created_at, id` |
| `GET /lending/savings-accounts` (`q`) | `SavingsRepository.page` | `account_no` and `member_no` prefix, member `full_name` substring | as loans | `account_no` |
| `GET /lending/investments` (`q`) | `InvestmentRepository.page` | `account_no`, `member_no`, member `full_name`, all substring | `ILIKE`; escapes `% _` but **not the backslash** | `account_no` descending |
| `GET /retail/products` (`query`) | `CatalogueRepository.page` | `code`, `description`, category name, substring | `ILIKE`; escape of `\ % _`; one phrase | `code, id` |
| `GET /retail/stock` (`query`) | `StockRepository.page` | as products | as products | `code, id` |
| `GET /retail/stock/all-branches` (`query`) | `StockRepository.allBranches` | as products | as products | `code, id` |
| `GET /retail/customers` (`query`) | `SalesRepository.customers` | `name` substring | `ILIKE`; escape of `\ % _` | `lower(name), id` |
| `GET /retail/sales` (`buyer`) | `SalesRepository.page` | `buyer_name` and the customer's `name`, substring | `ILIKE`; escape of `\ % _` | `created_at, id` (newest or oldest first) |
| `GET /retail/cash-parties` (`query`) | `CashbookRepository.parties` | `name` substring | `lower(name) LIKE`; **strips** `%` and `_` instead of escaping them, so "a_b" finds "ab" | `created_at, id` |
| `GET /core/users` (cursor on `lower(full_name)`) | `UserService` | no search parameter | n/a | `lower(full_name)` |

Gaps the audit found:

* **Multi-word queries are one phrase.** "john doe" fails against "Doe, John"; "led bulb" fails against
  "LED screw bulb".
* **Phone numbers** match only on members, and only as a suffix of the stored `+256...` form, so
  "0700 000 011" (spaces) and "+256 700 000 011" never match. Credit buyers' and parties' `contact`, and the
  member behind a loan, savings account or investment, cannot be found by phone at all.
* **National ID** only on members, and only exactly.
* **Sale number** is not searchable; the sales box means "buyer" only.
* **Accents** are not folded anywhere (`ILIKE` is case-insensitive only).
* **Escaping** is inconsistent: the investments query does not escape the backslash, the cash parties
  query deletes the metacharacters.
* **Ranking:** none. Order is by number or date, so an exact code can sit below fifty substring hits.
* **Indexes:** a trigram index exists on `lending_members.full_name` (V1) and on
  `retail_products.description` (V10). Measured as `bms_app` with row-level security on, the planner does
  not use them: no text operator (`textlike`, `texticlike`, `lower`, `translate`, trigram similarity) is
  leakproof, so a search is a filter over the tenant's rows (ADR-028 found the same). More indexes would not
  help the application role; the cost to watch is the cost per row.
* **Cancellation:** none of the API functions pass an `AbortSignal`; a slow, old answer can only be ignored
  because its query key differs.

## 2. Frontend convention

One hook and one box, in `frontend/src/app/ui/` (`useLiveSearch.ts`, `SearchBox.tsx`, `search.css`).

| Rule | How |
|---|---|
| Realtime | The text is live; the **term** the screen queries with follows 250 ms after the last key. Enter settles at once. A screen with a list already in the browser (stock-take) filters on the live text. |
| Trim | The term is trimmed with each run of white space made one space; trailing space is not a new search and keeps the page. |
| Minimum length | `minLength` per screen (default 1; the member picker, which lists nothing without a search, uses 2). Shorter text queries nothing and the box says "Type at least N characters to search." |
| Paging | A changed term is a new query key, so cursor lists start at page 1; `page` and `setPage` serve an offset list and reset to 1 when the term changes. |
| Stale answers | The term is in the query key and the query function passes React Query's `signal` to the request, so a request whose key lost its observer is aborted, and an old answer never replaces a newer one. |
| No flicker | `placeholderData: keepPreviousData`: the list stays, dimmed and `aria-busy`, until the new answer arrives. |
| Searching | While the debounce runs or an answer is due the status line reads "Searching" (a small spinner, still under `prefers-reduced-motion`). |
| Announcements | The status line is `role="status"` `aria-live="polite"`: "12 results", "1 result", "25 results so far, more below", `No results for "x"`. |
| Clear | A Clear button when there is text; Escape does the same, both at once (text and term). |
| URL | The option `urlParam` mirrors the term in the address bar (`replaceState`, other parameters kept) and reads it on load. No screen uses it: a name, a phone or a national id would reach the browser history, shared links and the Referer header, so it is for a screen that searches things, never people. |
| Empty answer | `NoResults`: `No loans for "x".` and a Clear search button. |
| Highlight | `Highlight` marks the matched words (case and accent blind) in the names and numbers of the loan, savings, investment, sale and member picker rows. |

Screens converted: the members list on the staff home, loans, savings accounts, investments, the investment member picker, credit sales,
all sales (buyer and item), credit buyers, the item pickers (sale and transfer through `ProductPicker`),
usage, restock, the stock-take sheet, stock (with its All branches view), stock by location and the items
list. Their category groups open for a search and keep their default while the previous list is still shown.
The insights page and the platform sign-up form have no search box.

## 3. Backend convention

`kernel.Search` (ADR-039), used by every repository below. A repository calls `Search.parse(text)`, builds its
`Search.Fields`, appends `match.where()` with `AND`, and (for a ranked list) selects `match.rank()` as
`search_rank`, orders by it first and carries it in the cursor.

| Rule | Detail |
|---|---|
| Fold | `bms_fold(column) LIKE bms_fold(pattern)`: lower case, Latin accents removed (V40). |
| Terms | Split on white space, at most 6, text at most 100 characters; every term must match in some name or code column (AND). |
| Phone | A term of four or more typed digits (digits, spaces, `+`, `-`, brackets only), and a whole text that looks like a phone number, match `bms_digits(phone)`: the typed digits anywhere in the number, and, when the text starts with `256` or `0`, the rest at the start of the number written with `256`, with `0` or with neither (so a contact typed as `0772123456`, `+256 772 123456` or `772123456` is found by `+2567`, `0772` or `256772`). The length is checked before anything is removed, so `0772`, `0700` and `+2567` are searches. The rest is never matched in the middle of a number: `0123` does not match every contact with `123` in it. Spaces in a phone-like text are separators (`07 00` is 0700). |
| Literals | `%`, `_` and the backslash are escaped. |
| Rank | 0: a name or code starts with the whole text ("buyer b"), or a phone starts with its digits. 1: the whole text starts a word in it. 2: every term starts a word in it (several terms only). 3: any other match. |
| Order | rank, then the list's usual key: member number, product code, credit buyer name, creation time, account number (investments: newest first). The sales history keeps its time order and takes the condition only. |
| Cursor | `Search.wrap` puts the rank and a fingerprint of the normalised text in front of the usual cursor. A cursor of the other kind, or one issued for another text, is 400 `malformed_request` ("restart the list"). The PWA cannot send one: the term is in the query key and Load more is disabled while the list shown is the previous answer. |
| Scope | The condition is ANDed with the branch predicate and the other filters; RLS is untouched. |
| Guarded columns | A member's phone and national id are matched on the loan, savings and investment lists only where the caller holds `lending.members.read` at the member's branch. The members list itself shows both masked, yet it matches them whole or in part for a caller who may read members: that is the same permission that opens the member, so a partial national id or phone is a way to find a member, not a way past a permission. It is noted here because a masked column that can be searched is still disclosure by search for anyone who lacks that permission, and there the search does not match it. |

What each endpoint matches:

| Endpoint (param) | Name | Code, number, reference | Phone |
|---|---|---|---|
| `GET /lending/members` (`q`) | full name | member number, national id | phone, second phone |
| `GET /lending/loans` (`q`) | member name | loan number, member number; guarded: national id | guarded: member phone, second phone |
| `GET /lending/savings-accounts` (`q`) | member name | account number, member number; guarded: national id | guarded: member phone, second phone |
| `GET /lending/investments` (`q`) | member name | account number, member number; guarded: national id | guarded: member phone, second phone |
| `GET /retail/products`, `/stock`, `/stock/all-branches` (`query`) | description, category name | code | none |
| `GET /retail/customers` (`query`) | name | none | contact |
| `GET /retail/sales` (`buyer`) | buyer name, linked credit buyer name | sale number | buyer contact, linked credit buyer contact |
| `GET /retail/cash-parties` (`query`) | name | none | contact |

Indexes: none. V40 (expand only) adds the two functions `bms_fold` and `bms_digits`. A trigram index was
measured out: the application reads as `bms_app` under row-level security and no text operator is leakproof, so
the planner never uses such an index for it (ADR-039 decision 7). The search is a filter over the tenant's rows.
Measured through the API on a laptop (`SearchScaleIT`, 25,000 sales, 12,000 members, 6,000 products, 800 credit
buyers, medians of five calls): a search that matches nothing and so reads every row takes 11 to 20 ms; a phone
search 25 to 60 ms; every other search 3 to 22 ms. The credit buyer behind a sale is reached through a subquery
that runs once per statement (the plan shows one execution of the subplan for 25,000 sales), and the rank is
worked out once per row, not again in the paging condition. The CI bound is 400 ms, to catch a query that reads
rows more than once per row.

## 4. Decisions, tests and what is left

ADR-039 records the decisions. Tests: `SearchTest` (parsing, escaping, phone digits, cursor rules),
`SearchLendingIT` and `SearchRetailIT` (phone formats, several terms, accents, literals, ranking and paging, scope,
guarded columns, cursors tied to their text, the size of a busy tenant in `SearchScaleIT`), `useLiveSearch.test.tsx` (debounce, restart, trim,
minimum length, paging reset, flush, clear, URL, status words, highlight, markup), `useLiveSearch.dom.test.tsx` (the hook in React: Escape, Enter, unmount clean-up, the URL mirror, a request that has moved on is aborted and its late answer never shown, the previous list stays) and `fold.test.ts` (the browser's folding table equals the one in V40).

Left for follow-ups: a data-volume pass over the loan, savings and investment
registers (ADR-028) to decide whether they need an index; the cash party screens have no search box.
