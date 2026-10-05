# Review of the retail stack: #57, #59, #61, #62, #58

Date: 2026-10-05. Method: static read of each PR's own diff (`git diff <base>...<head>`), no test run.
Findings were produced by one reviewer per PR; the oversell finding was also checked by me directly in
`JdbcStockLedger` on every branch.

## Context that changes how to read this

`origin/main` already contains `integration/retail-vertical` (merged as #72), which includes these
branches plus V13 (price floor, oversell setting removed), V14 (review fixes from #68) and V20. The
#57 head is an ancestor of main, so its own diff against main is empty. Findings below are true AT
THE PR HEADS. Where main already fixes one it is tagged `[fixed on main]`; those PRs cannot merge
as they stand without that fix, or must be closed as superseded by #72.

## Verdicts

| PR | Verdict |
|---|---|
| #57 docs: ADR-020, scope, data dictionary | CHANGES REQUESTED (text fixes only) |
| #59 R1 catalogue, V10 | CHANGES REQUESTED |
| #61 R2 stock and sales, V11 | CHANGES REQUESTED |
| #62 R3 purchasing and usage, V12 | CHANGES REQUESTED |
| #58 phone-first PWA screens | CHANGES REQUESTED |

## Known decision: overselling is always refused

- #57: ADR-020 decision 4 and the scope are rewritten correctly. No leftover "setting to allow negative stock" text in the docs tree.
- #59: no stock code yet.
- #61 and #62: NOT implemented. `JdbcStockLedger.record` reads `settings.retailAllowNegativeStock()` and refuses only when false; the default is `true` (`SettingsService.java:199`, `getOrDefault("retail_allow_negative_stock", true)`). A fresh tenant sells 5 units of a product with balance 0 and gets 201. `RetailSalesIT` asserts the negative balance after a default sale. Usage and damage go through the same ledger, so they also write a negative balance and a shrinkage journal for stock that does not exist. The guard also lacks `m.qty().signum() < 0`. `[fixed on main]`: guard unconditional, setting ignored.
- Concurrency: sound once the guard is on. `lockBalance` does INSERT ON CONFLICT DO NOTHING then SELECT FOR UPDATE; `record` sorts by (branch, product, index) so lock order is consistent; two sales (or a sale and a usage) of the last unit serialize on the balance row and the loser is refused. Only the setting defeats this.
- Database: NOT enforced there. `retail_stock_balances` (V11 lines 54 to 66) has no `CHECK (qty >= 0)`, only a partial index for negatives, and main has not added one. Adjustment and legacy_balance may legitimately go negative, so a CHECK needs a carve-out; otherwise state the service-only enforcement in the ADR.

---

## #57 docs (head docs/50-retail-vertical-adr-and-scope)

Blocking
1. Stale body text contradicts the ADR. `docs/sdd/05-architecture.md:17` ("lending first, retail later") and `:82` ("(future)"), `README.md:8`, and ADR-001 lines 33 and 88 ("not built now", retail before lending is "drift"). Fix: edit these lines in the same change (CLAUDE.md docs rule).
2. The retail rounding rule is not stated. Neither the ADR, scope nor contract says line total = qty (3 decimals) x unit minor, rounded half up once per line (R-ROUND), sale total = sum of lines. UI and backend agree by chance. Fix: one sentence in decision 7 or the contract conventions.
3. The API contract draft has gaps the UI depends on: (a) `retail.price.below_cost` missing from the permission list though decisions 5 and 10 use it; (b) no error catalogue (`insufficient_stock`, below-cost refusal, `idempotency_key_reused`, replay header); (c) no rule that sales staff are limited to their branch and to today although the body takes `branchId` and `saleDate`; (d) `GET /sales/{id}` and stock-take shapes are assumed but unspecified.

Non-blocking
4. Decision 5 refuses a price "not above" cost (equal refused) but the parity baseline (`retail-pilot-app-behaviour.md:24,72`) says "cannot be lower than" (equal allowed) and marks it "Kept". Pick one.
5. Naming: ADR-020 line 10 and the scope line 7 describe the tenant as "a multi-shop electrical retailer". Reword to "the retail pilot tenant". "The pilot" in retail docs clashes with "the pilot tenant" (the lender) in AGENTS.md. The parity baseline records a real business rule ("daily savings default to half of the profit"), which is a real figure under the content boundary: drop it.
6. Chapter 3 section 3.28, data model, endpoint catalogue, permission matrix and `workspace.dsl` are deferred to R1. Acceptable only if R1 lands them; #61 and #62 touch SDD 6, 7, 15 but I found no 3.28 landing.
7. ADR Status line ("Proposed, accepted on merge") differs from the repo's "Accepted (date)" form. `adr_citation_guard.py` passes.

## #59 R1 catalogue (V10)

Verified: RLS and composite FKs on all four tables; price history append-only with SELECT, INSERT grants; tenant_id from current_setting; PATCH uses a locked read and If-Match; V10 follows V8, no collision; fixtures fabricated.

Blocking
1. Cost leaks through the audit log. `CatalogueService.java:137-144` puts `cost_minor` in the create `after` payload; `:266-274` puts it in the was/now diff. Nothing under `core/audit` redacts. A user with `core.audit.read` and no `retail.profit.read` reads every cost and cost change through audit search or CSV export, defeating decision 10. Fix: keep cost out of the audit payload, or gate and strip it; add a test.
2. `POST /products/{id}/prices` has no If-Match (`CatalogueController.java:118-122`, `CatalogueService.java:230-242`). The lock serializes but a stale view is not detected and the body is absolute: user A (read sell 4000) sends 4500 after B changed price; A silently overwrites B and history "old" is B's. Fix: require If-Match and compare version; 428 or 409; tests.
3. No upper bound on money. `CatalogueApi.java:50-51, 65-66` have only `@PositiveOrZero`; DB only `>= 0` (V10 lines 91-92, 118-121). Values up to 9.2e18 accepted; downstream qty x price overflows. `[fixed on main]` by V14 F7 (10^13 CHECK, `MAX_AMOUNT_MINOR`).
4. `price_unchanged` is a cost oracle. `editPrices` (`CatalogueService.java:236-240`) compares against the hidden cost and returns 422 `price_unchanged`. The route needs only `retail.price.edit`, separable from `retail.profit.read`. A caller sends sell=current, cost=guess; 422 means the guess is the cost. Fix: couple the permissions, or return one generic outcome.

Non-blocking
5. History ordered by `created_at DEFAULT now()` (transaction start) in `CatalogueRepository.java:196`; A starts first, blocks on the lock, commits second with the earlier timestamp, so history shows the wrong order. Use `clock_timestamp()` or a per-product version column. There is no `effective_from`, so "price as at date X" is unsupported. (#62 uses `clock_timestamp()` for its own rows.)
6. Code and name normalisation is Java `trim()` only (`CatalogueService.java:72, 85, 105, 192`): NBSP, zero-width and full-width variants are distinct codes. `[fixed on main]` V14 F9.
7. V10 chart seed (lines 171-174) skips an account if the code OR the system_key exists. A tenant already using 4100 or 5100 never gets `sales_revenue` or `cost_of_goods_sold`, the seed reports success, and a later posting by system_key fails. Skip only on a system_key match; raise on a code collision.
8. Categories and units have no rename or deactivate; say so in the docs.
9. Missing negative tests: PATCH without If-Match (428) or stale (409); negative or non-integer price; blank code; history read by a sales user asserting no cost; cost absent from audit rows; concurrent price edits; duplicate category or unit; price edit on missing or foreign-tenant product (404).
10. The chapter 8 matrix change rewrites the whole table (about 80 lines of noise) when only columns were added.

## #61 R2 stock and sales (V11)

Verified: RLS on all 7 tables with composite FKs and minimal grants; movements and sale lines append-only; sale journal balanced (method account or trade_debtors Dr / sales_revenue Cr; COGS Dr / inventory Cr); void reverses movements at the original cost snapshot and reverses both journals by exact line swaps, under FOR UPDATE with a status check plus UNIQUE (tenant, reverses_movement_id); void refused when a credit sale has payments; void permission is admin only and checked per branch; `RetailBranchContext.resolve` blocks selling from an out-of-scope branch; nightly reconciliation reports without auto-correcting; idempotency key unique per (tenant, principal, key) with a body hash and 422 on reuse.

Blocking
1. Oversell permitted by default (see the decision section). `JdbcStockLedger.java:51,58`, `SettingsService` default true, `TenantSettings.java:211`, patchable via `SettingsController`; `RetailSalesIT:68` asserts it. Fix: delete the setting, make the guard unconditional for sale, usage, damage. `[fixed on main]`
2. Non-negative stock is service-only (no CHECK on balances). See the decision section.
3. Cost visible across branches. `StockService.mayReadCost` (`:308`) and `SalesService.visible` (`:368`) use `hasPermission` on any branch; a user with `profit.read` at branch A sees cost and profit of branch B rows. Fix: decide per row branch. `[fixed on main]` (4cea6c8)
4. Audit leaks cost and PII. `SalesService.java:247-250` audits `cost_total_minor`; `StockService.java:272-274` audits `loss_minor` and `gain_minor` (at cost); `SalesService.java:346-347` audits the customer name; the void reason is free text (`:336`). Fix: drop cost fields, keep the customer id only, document the reason as free text.
5. Stock-take adjustment is sized against the live balance, not `expected_qty` (`StockService.java:235-236`). Count 10 on balance 10, sell 2, commit: variance +2, the sold stock is resurrected as a gain and a false shrinkage credit is posted. Fix: variance = counted - expected, applied to the live balance. `[fixed on main]` (ac3d614)
6. Amount bounds missing. `SaleLineRequest.unitPriceMinor` is `@PositiveOrZero Long` with no max (`SalesApi:38`); qty up to 99,999,999,999.999; `Quantities.value` (`Quantities.java:19-22`) uses `longValueExact`, and `Math.addExact` at `SalesService.java:173-174`, so qty x price throws `ArithmeticException`, a 500 unless mapped. `[fixed on main]` V14 F7 and `amount_out_of_range`.

Non-blocking
7. Rounding (half up once per line, `Quantities.java:6-9`) is stated but untested: no x.5 case, no overflow case, no test that more than 3 decimal places is rejected.
8. `JdbcRetailIdempotency.java:54` sets `lock_timeout='5s'` for the rest of the transaction but only the key-insert catch maps 55P03; a balance lock wait over 5 s inside `work.get()` becomes a 500 instead of a retryable 409.
9. Void has no If-Match and no Idempotency-Key; a retried void returns 409 rather than the original result. The reversal is dated today, not the sale date; the period policy is undocumented.
10. `JdbcLedgerPosting.reverse` (`:104-127`) is check-then-insert and relies on a unique key on `reverses_entry_id`; confirm that index exists in V1 (the sale-row lock masks the race here).
11. FR-RET-03 and ADR-020 on this branch still describe the setting. The `openapi.json` diff is large (+2236/-1179); confirm it is regeneration churn only.
12. Missing negative tests: concurrent void, oversell refused by default, overflow, qty over 3 dp, inactive or foreign-currency product, stock-take racing a sale, cross-branch cost visibility.

## #62 R3 purchasing and usage (V12)

Verified: purchase is one `@Transactional` (purchase, lines, price change, history, audit, movements, per-branch journals); history uses `clock_timestamp()`; V12 has RLS, composite FKs, append-only tables and SELECT, INSERT grants, additive, inside the retail range V10 to V19; payments run under FOR UPDATE with `payment_exceeds_balance` checked and a DB CHECK `paid_minor <= total_minor`, journal Dr method account / Cr trade_debtors with the sale subledger; void after payment refused; idempotency enforced on payments and usage; lock order safe (purchases lock products by id, then balances by branch and product; sales and usage lock balances only; void locks the sale first).

Blocking
1. Oversell is a setting, default true (see the decision section), including usage and damage (`UsageService.java:138` goes through `stock.record`). ADR-020 decision 4 and FR-RET-03 on this branch still say "allowed by default". `[fixed on main]`
2. Branch-scoped caller sees other branches' data. `PurchasingRepository.java:135-141` filters purchases that touched an in-scope branch but returns all lines, totals, costs and per-branch quantities. `StockService.java:103, 143` and `UsageService.java:181` call `mayReadCost()` with no branch. `[fixed on main]` (F11, `withLines(p, branchIds)`, `mayReadCost(branch)`)
3. Stock movements have no business date. `PurchasingService.java:236` and `UsageService.java:138` stamp `occurred_at = now` while the journal uses the business date; a backdated restock or usage falls in the wrong period for as-of valuation and stock and ledger disagree by date. `[fixed on main]` (`stock.record(date, ...)`, `business_date` in V14).

Non-blocking
4. A backdated restock overwrites a newer price: `PurchasingService.java:221` into `CatalogueLookup.applyPurchasePrices` (`:67-73`) always sets the current price. A 1 Oct restock at 5000 entered after a 20 Oct restock at 6000 reverts cost and sell to 5000. Apply prices only when `purchased_on` is on or after the newest purchase-sourced price date.
5. Cost basis is last cost, not weighted average (documented, pending ADR-021). Inventory is debited at purchase cost and credited at current product cost, so the inventory account drifts from qty x cost with no revaluation entry. State on the valuation report that it uses last cost.
6. Usage and sales read product cost or price without a lock; a concurrent restock can change it between snapshot and movement. Internally consistent (snapshot and journal use the same stale value), so acceptable, but document it.
7. Unbounded money inputs (`PurchasingApi`, `SalesApi`: `@Positive` only; `Math.addExact` at `PurchasingService.java:197, 199, 204` and `UsageService.java:118`): overflow is a 500; qty summed over 50 branches can exceed `numeric(14,3)`. A sell price below cost is accepted on a purchase. Add `@Max` and map `ArithmeticException` to a validation problem.
8. A restock changes the global sell price with only `retail.purchase.create` (admin only per the matrix, per decision 5). Say so in chapter 8.
9. Credit purchases credit `trade_creditors` (`PurchasingService.java:239`) but there is no supplier payment endpoint or payables query, so the balance only grows; suppliers cannot be edited or deactivated. Confirm this is a later increment.
10. Missing negative tests: overpayment; payment on a voided or debit sale; payment outside branch scope; void after payment; double-submit race on payments with distinct keys; replay and key reuse for usage and payment; usage racing a sale for the last unit; oversell refused on usage and damage; a purchase with a branch outside scope; credit purchase without a supplier; two-line purchase atomicity; cost redaction on usage.

## #58 PWA screens

Verified: no parseFloat in the forms; `parseQty`, `parseMinor`, `lineTotalMinor` use integers and BigInt; half-up rounding matches `Quantities.value`; cost, profit, valuation and profit screens are gated on `retail.profit.read` (static-markup tests prove it); no secrets, no `X-Dev` headers, no vite config change beyond the test glob; mock data is fabricated; inputs have labels, `inputMode`, 44 px targets, `role="alert"`, `aria-live` on the total. No allow-negative setting appears in the UI.

Blocking
1. The idempotency key is not persisted. `retail/idempotency.ts:18-22` keeps it in a `useRef`; `sale.tsx:68` mounts one key per form. The POST succeeds, the response is lost on a flaky phone connection, the cashier reloads, gets a new key and the sale posts twice (stock down twice, two journals). Fix: persist key and draft body in `sessionStorage` (try/catch) until success. Same for restock and usage.
2. The key is reused after the draft is edited. `sale.tsx:82` sends the same key with a rebuilt body; the backend answers 422 `idempotency_key_reused` (`JdbcRetailIdempotency.java:80-82`). After a lost response the cashier fixes a quantity, retries, and is stuck with the raw 422; the only way out is a reload, which loses the cart and triggers finding 1. Fix: key tied to a body hash; keep key and body frozen after a network error and disable editing until it resolves; mint a new key only after a definitive 4xx.
3. Oversell is not prevented in the UI and the mock hides it. `sale.tsx:93-94, 117` offer every product including out of stock; `:138` lets qty exceed stock with no warning; Save (`:205`) checks only format (`sale-state.ts:41`); `usage.tsx:38` is the same. The server will return 422 `insufficient_stock`, shown raw by `Problem`; on the #62 branch that message contains a raw product UUID and "tenant does not allow negative stock" (`JdbcStockLedger.java:60-64`), which contradicts decision 4. `retail-mock.ts:141,175` never refuses an oversell and starts at -2 (`:77`). Fix: show branch qty on the line and block Save when qty exceeds it; map `insufficient_stock` to a fixed message; make the mock refuse and add a test; drop the UUID and the "tenant does not allow" wording on the backend.

Non-blocking
4. `retail.ts:2` and `staff/route.tsx:4` import `retail-mock` statically, so the mock data and `mockMe` probably stay in the production bundle (inferred, not built). With `VITE_RETAIL_MOCK=1` in a staging build, `route.tsx:24-37` skips authentication and runs a fake admin session. Use a literal `import.meta.env.DEV` guard with a dynamic import, and add a build check that dist has no "Test Admin 01".
5. `retail.ts:222` refreshes the token only on GET; a 401 on a sale POST shows "The request failed." A network failure shows raw `TypeError: Failed to fetch` with no timeout. Show "Not sent. Check your connection and tap Save again; it will not be saved twice." and refresh once before retrying a POST with the same key.
6. A sales user can edit the unit price (`sale.tsx:141-142`) though that needs `retail.price.edit`; make it read-only without that permission. The below-cost refusal is never mapped to a message.
7. `permissions.ts:17` requires `retail.stocktake.commit` to open the count screen; the contract does not restrict counting to commit holders. Reconcile with the matrix.
8. `parseMinor` accepts "0", so a zero-price sale is possible in the UI; require positive. Only the mock uses float maths (`toMilli`, `retail-mock.ts:68`), and the UI notes' claim "no float touches an amount" is untrue for the mock.
9. Accessibility gaps: `aria-invalid` without `aria-describedby` text; radio inputs 24 px high; "Saving", "Loading", "Add" status text has no live region. Void, pay-credit and price-edit screens are absent; the scope table lists void under R2 and FR-RET-04, so say explicitly that R6 excludes them.

---

## Cross-cutting checks

- Dashes: a Python scan of the ADDED lines of all five diffs for U+2014 and U+2013 found none.
- Flyway: V10, V11, V12 are consecutive, additive and inside the retail range V10 to V19 on these branches. They need lending's V9 on main first (V9 is on main now). V13, V14 and V20 exist only on main.
- Tenant isolation: RLS and composite FKs are present on every new table in V10, V11, V12. No gap found.
- Real data: no real names, phones or figures found in code or fixtures. Naming and the "half of the profit" rule are in the docs (see #57 findings 5).
- Limits of this review: static read only; the PWA bundle claims are inferred; I did not confirm that main's V13, V14 and V20 close every item tagged `[fixed on main]` beyond what the reviewers cited.
