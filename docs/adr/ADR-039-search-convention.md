# ADR-039: One search convention: a shared kernel matcher, folded text and phone digits in the database, no search index under row-level security, and a live search hook in the PWA

## Status

Proposed (issue #241; spec `docs/specs/search.md`). Builds on ADR-003 (row-level security and branch
scope stay the only authority over which rows exist), ADR-009 (the PWA) and ADR-028 (indexes are
measured and added in expand-only migrations). Chapter 7 section 7.6.1 holds the contract.

## Context

The owner requires search on all screens to be robust, comprehensive and realtime. The audit
(`docs/specs/search.md` section 1) found ten different ways to match: a phrase `ILIKE` here, a prefix
there, a phone suffix on members only, a backslash not escaped in one query and the percent sign deleted
in another, no accent folding, no multi-word search, no ranking. On the screens, half submitted with a
button, half sent a request per keystroke with no pause and no cancel, one debounced at 300 ms, and none
announced its result count or said "Searching". Three open decisions follow from fixing this: where the
matching lives, how a ranked order pages, and how a search stays inside what the caller may read.

## Decision

1. **One matcher in the shared kernel.** `kernel.Search` parses the text once (trim, at most 100
   characters and 6 terms) and, given the columns of an entity, returns a SQL condition, a rank
   expression and their named parameters. A repository appends the condition to its own query with
   `AND`, next to the branch predicate and its other filters; it never replaces either. Row-level
   security and branch scope are therefore untouched: a search can only narrow what the caller could
   already list.
2. **Matching rules, the same for every entity.** Case and accent insensitive; the text is split on white
   space and every term must match somewhere (AND) in a name, code, number or reference column; `%`, `_`
   and the backslash are literal; a term of four or more digits (and a whole text that looks like a phone
   number) also matches the digits of a phone column, as typed and with a leading `256` or `0` removed (the length
   is checked before anything is removed, so "0772", "0700" and "+2567" are searches), so "0700 000 111",
   "+256700000111" and "700000111" are one number.
3. **Folding in the database, not in Java.** Two immutable SQL functions (migration V40): `bms_fold(text)`
   lower-cases and removes Latin accents with a fixed `translate` table written in both cases (a text of one byte
   per character is only lower-cased); `bms_digits(text)` keeps the digits. Both the column and the text go
   through `bms_fold` in the same statement, so the two sides can never disagree. They are written as plain
   expressions without `STRICT`, so the planner inlines them and a folded column costs about what `lower()` costs.
   The `unaccent` extension is not used: it is not immutable, needs a second extension, and its table is far
   larger than East African names need.
4. **Ranking.** The rank is 0 when a name or code starts with the whole text (or a phone starts with the
   digits), 1 when the whole text starts a word in it, 2 when every term starts a word in it (several terms), 3
   for any other match. So "buyer b" puts "Test Buyer B" first and "john k" puts "John Kato" first, with the other
   rows that match every term after them. A searched list orders by
   rank, then by the list's usual key (member number, code, name, creation, newest first for investments).
   The sales history keeps its time order and uses the condition only.
5. **The rank travels in the cursor.** The lists page by keyset, so a ranked order needs the rank in the
   keyset: `(rank, usual key) > (cursor rank, cursor key)`. A searched list's cursor is the usual cursor behind
   a marker and the rank (`Search.wrap` adds them, `Search.unwrap` removes them), and the repository selects
   `search_rank` so the service can read the last row's rank. The cursor also carries a short hash of the
   normalised text, so a cursor can only continue the search it came from; one issued for another text, or of the
   other kind (an unsearched cursor with a search, a searched one without), is 400 `malformed_request` with the
   advice to restart the list, never a silently wrong page. The PWA cannot send one: the term is in the query
   key, and the Load more button is disabled while the list on screen is the previous answer. A searched page
   is a subquery that selects `search_rank`, with the keyset condition and the order outside it, so the rank is
   worked out once per row.
6. **Columns the list does not show are guarded.** The loan, savings and investment lists match the member's
   phone and national id only for a caller who holds `lending.members.read` at the member's branch (a `guarded`
   column group with the caller's member scope as its predicate). A caller with only `lending.loans.read` cannot
   use a loan search to confirm that a phone or id exists. No search matches cost, profit, balances or any other
   column behind a permission.
7. **No search index.** A trigram index was the plan and was measured out. The application reads every table as
   `bms_app` under forced row-level security, and the planner may use a user qual as an index condition only when
   it is leakproof. No text search operator is (`textlike`, `texticlike`, `lower`, `translate`, the trigram
   similarity operators are all marked not leakproof; only equality and `starts_with` are), so a trigram index,
   the two that V1 and V10 created included, is used by the owner role and never by the application. The
   search is a filter over the tenant's rows, as ADR-028 already found for `ILIKE`. Measured through the API on a
   laptop with `SearchScaleIT` (25,000 sales, 12,000 members, 6,000 products, 800 credit buyers; medians of five
   calls): a search that matches nothing, and so reads every row, takes 11 to 20 ms, a phone search 25 to 60 ms, any
   other 3 to 22 ms. What can be kept small is the cost per row: `bms_fold` is inlined with an ASCII fast path
   (decision 3), a column in another table (the credit buyer behind a sale) is reached through an uncorrelated
   subquery that runs once per statement and not once per row, and the rank is worked out once per row (decision
   5). The old indexes are unused write cost and can be dropped by a contract migration. If a measurement ever
   shows a real problem, the indexes the application role can use are the ones on leakproof operators, which means
   equality and `starts_with`; see the last point of Consequences.
8. **One hook in the PWA.** `app/ui/useLiveSearch` owns the typed text and the settled term: 250 ms pause
   (Enter settles at once), trimmed and collapsed, a minimum length per screen, paging back to page 1 when the
   term changes, an optional mirroring in a URL parameter that no screen uses (a name or a phone must not reach the history or the Referer header), Escape and a Clear button. `SearchBox` adds the label,
   the clear button and a polite live status ("Searching", "12 results", `No results for "x"`); `SearchResults`
   keeps the previous list in place, dimmed and marked busy, until the new answer arrives (`placeholderData:
   keepPreviousData`, so no flicker); `NoResults` names the text and offers Clear; `Highlight` marks the matched
   words. The term is part of every query key, and every search request receives React Query's `signal`, so a
   request whose key lost its observer is aborted and a stale answer can never replace a newer one.
9. **Parameter names stay.** `q` on the lending lists, `query` on the retail lists, `buyer` on the sales list,
   as the client and the contract already have them. Chapter 7 section 7.6.1 records the convention; renaming
   is not worth a breaking change.

## Consequences

**Better:** every screen finds what a person types the way they wrote it (a phone with spaces, a name without its
accent, words in any order); the order puts the likely row first; one place to change the rules; no screen can
send a request per key or show an older answer over a newer one; a person using a screen reader hears the
result count; the cost of search is bounded (100 characters, 6 terms, a few milliseconds per thousand rows).

**Worse:** several words are now an AND of terms, not a phrase, so a short word such as "b" matches widely
(the narrowing is the point, and the status line shows the count); a ranked cursor is longer and tied to the
text, and a one-letter word such as "b" is a substring match, so "buyer b" still returns every buyer (the
whole phrase comes first; the original expectation of a single row cannot hold under an AND of terms);
`bms_fold` covers Latin accents only, not Greek or Cyrillic, and not `ss` for the German sharp s; every
search is a filter over the tenant's rows (decision 7), so a tenant with hundreds of thousands of products or
members will feel it.

**Watch for:** a new list must use `Search` and its `Fields`, never a new `ILIKE`; a new searched column that is
not shown on the list needs a guard (the members list matches a national id or phone in part for a caller who may
read members, the same permission that opens the member; a caller without it does not match them); a change to the accent table of `bms_fold` is a new function, not an edit of
V40, because an index built on the old one would go stale; the stock by location screen of #193 and the category
groups screens (`stock.tsx`, `catalogue-products.tsx`) adopt the hook in a follow-up; if a table outgrows the
filter scan, the ways out are a stored folded key searched with `starts_with` (the one leakproof pattern operator)
for the prefix case, or reading through a role that may use the index with the tenant predicate written into the
query, which is a decision of its own (ADR-003).
