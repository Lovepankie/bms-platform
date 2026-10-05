# Adversarial review: retail R1 to R4 (PRs #59, #61, #62, #63)

Reviewed: the four stacked branches read as one diff, `origin/docs/50-retail-vertical-adr-and-scope`
to `origin/feat/54-retail-r4-valuation-profit` (head `d55bf0f`), against ADR-003, ADR-004, ADR-015,
ADR-017, ADR-020 and the retail specs. Out of scope as already known: the
`retail_allow_negative_stock` default, the missing price floor (#64), and camelCase against
snake_case in the contract draft.

Method: code reading, then a throwaway integration test (`AdversarialProbeIT`, run with
`mvn verify -Dtest=...` against the Testcontainers database, not committed) and raw two-connection
SQL as `bms_app` for the locking finding. Every figure below is fabricated.

## Verdict

**Do not merge as is.** Tenant isolation, append-only enforcement, idempotency, void handling and
the sale and purchase journals all held up. One HIGH defect corrupts stock and posts a false
inventory gain during ordinary trading. A lock-order defect aborts concurrent restocks and stock
events with a 500. The valuation report's reconciliation figure is wrong in two ways that hide
real differences.

| Severity | Count |
|---|---|
| HIGH | 1 |
| MEDIUM | 5 |
| LOW | 6 |

## Findings

### F1. HIGH, CONFIRMED: a stock-take commit erases every movement made between count and commit

- Where: `backend/src/main/java/com/rincoltech/bms/retail/stock/internal/StockService.java:235-236`
  (`variance = counted - balance at commit`). The stock-take stores `expected_qty` when it is
  created but ignores it at commit.
- Scenario (reproduced): balance 10 at the head office. Staff count 10 and save the draft. A
  cashier sells 3 (balance 7). The admin commits. Result: `committed_variance_qty` is `+3.000`,
  the balance goes back to `10.000` while 7 are on the shelf, and the entry posts a gain of
  3 x 1,000 (debit `inventory` 3,000, credit `stock_shrinkage` 3,000). A sale, purchase, usage or
  void recorded while a count is in progress is reversed without any message. A shop that counts in
  the morning and commits in the evening resets the day's trading.
- Note: FR-RET-08 and chapter 7 ("against the balance at commit") specify this behaviour, so the
  spec is wrong as well as the code.
- Fix: commit should apply `counted - expected_qty` as the adjustment, which is the shortfall
  measured when the count was taken. Movements recorded after the draft then stay in the balance.
  If you want to keep "against the balance now", refuse the commit (409 `stock_moved_since_count`)
  when the locked balance differs from `expected_qty`, and make the user recount those lines.
  Update FR-RET-08 and section 7.11.20, and add a test that sells between draft and commit.

### F2. MEDIUM, CONFIRMED: restocks deadlock against usage, voids, stock-takes and multi-line sales

- Where: `catalogue/internal/CatalogueRepository.java:92` (`FOR UPDATE OF p`), called by
  `purchasing/internal/PurchasingService.java:184` before the balance locks. The other side is
  `stock/internal/JdbcStockLedger.java`, which locks the balance row and then inserts a
  movement. That insert's foreign key check takes `FOR KEY SHARE` on `retail_products`, and
  `FOR UPDATE` conflicts with `FOR KEY SHARE`.
- Scenario (reproduced with two `bms_app` connections): the usage transaction locks the balance
  (head office, P). The purchase transaction locks product P `FOR UPDATE`. The usage transaction
  inserts its movement and blocks on P. The purchase transaction asks for the balance lock. Result:
  PostgreSQL raises `ERROR: deadlock detected` and aborts the usage transaction.
  `ApiExceptionHandler` has no handler for deadlocks, so the client gets a 500. The same cycle
  occurs with a void or a stock-take commit on the same product and branch. It also occurs with a
  sale whose lines name P2 then P1 while a restock locks P1 then P2: the sale's line inserts take
  `KEY SHARE` in request order (`SalesService.java:208`). Chapter 6 section 6.11.2 documents the
  product lock that causes this.
- Fix: lock products with `FOR NO KEY UPDATE`, which does not conflict with foreign key checks, in
  `CatalogueRepository.lock`. As a backstop, map SQLSTATE `40P01` and `40001` to 409 or 503 with a
  retry hint. The idempotency key rolls back with the transaction, so a retry is safe. Add a mixed
  concurrency test: race a restock against a usage report on one product.

### F3. MEDIUM, CONFIRMED: valuation drops branches with no stock left, hiding the inventory account balance

- Where: `reports/internal/ReportsService.java:84-97`. Branch totals are built only from
  `byBranch`, which holds only branches with a non-zero holding. The `inventory` balance from
  `balanceByBranch` is looked up per branch in that map, so a branch with no holding is never
  reported.
- Scenario (reproduced): restock 10 at cost 100 (inventory 1,000). Manual cost edit to 50. Sell
  10, so the cost of sale is relieved at 500. Result: `/reports/valuation` returns
  `"branches": []` and `value_at_cost_minor: 0`, while the `inventory` account still holds 500.
  The revaluation difference that ADR-020 decision 8 says the report shows is exactly the case it
  hides. The same thing happens to a branch whose holdings net to zero.
- Fix: build the branch list from the union of holding branches and `inventory` balance branches,
  restricted to the caller's filter. Report value at cost 0 and difference `-account` for the
  extra branches. Add a test.

### F4. MEDIUM, CONFIRMED: `as_of` valuation uses recording time, while the journals use business dates

- Where: `reports/internal/ReportsRepository.java:48` filters movements by `occurred_at`, which is
  always `clock.now()` (`stock/internal/JdbcStockLedger.java:53,74`). The `inventory` balance it
  is compared with uses `entry_date`, which is the purchase's `purchased_on`, the sale's
  `sale_date` or the usage's `occurred_on`. All of these can be backdated.
- Scenario (reproduced): today is 5 October. A restock of 10 at 100 dated 2 October. Result:
  `/reports/valuation?as_of=<4 October>` returns no rows and value 0, but the ledger has inventory
  1,000 on that date. A backdated sale has the opposite effect. The revaluation difference then
  mixes timing differences with real revaluation, and quantities on past dates are wrong.
- Fix: stamp `occurred_at` (or a new `business_date` column) from the event's business date
  (`purchased_on`, `sale_date`, `occurred_on`, the void and stock-take date). Filter `as_of` on
  that column. Alternatively, document that `as_of` is "as recorded by" and compare it with
  journals by `created_at`.

### F5. MEDIUM, PLAUSIBLE: cost visibility ignores the branch scope of `retail.profit.read` (ADR-017)

- Where: `CatalogueService.java:299`, `StockService.java:309`, `SalesService.java:456` and
  `ReportsService.java:61` all use `principal.hasPermission("retail.profit.read")`, which is true
  if the permission is held in any branch.
- Scenario: a user holds `retail.stock.read` and `retail.sale.read` at all branches and
  `retail.profit.read` at branch A only, which ADR-017 per-permission scope allows. Result: on
  `/stock?branch_id=B`, `/stock/movements`, `GET /sales/{id}` for a sale at B, and
  `/reports/valuation?branch_id=B`, they get unit cost, cost totals, profit per sale and B's
  `inventory` account balance. Products are tenant-wide, so catalogue cost is arguably fine.
  Branch-bound rows are not.
- Not reproduced: the development principal (`X-Dev-Branch-Ids`) applies one scope to every
  permission, so the existing harness cannot build this principal. That is also a test gap (F12).
- Fix: on branch-bound rows, decide per row with `principal.may("retail.profit.read", row.branchId())`.

### F6. MEDIUM, PLAUSIBLE: V10 to V12 leave a hole at V9 that breaks deploys if #47 merges later

- Where: `db/migration/V10__retail_catalogue.sql` and the following files. Open PR #47 adds
  `V9__loan_appraisals.sql`. #48 has no migration. `DatabaseMigrator` uses Flyway defaults
  (`outOfOrder` false, validate on migrate).
- Scenario: retail merges and deploys first, so staging is at V12. Then #47 merges. Result: on the
  next deploy Flyway finds resolved migration 9 below the applied version, validation fails
  ("resolved migration not applied to database"), and the deploy stops. The V10 header says it
  "takes the next number free on main and the open lending pull requests", which assumes a merge
  order that nothing enforces.
- Fix: agree a merge order (#47 first) and record it on both PRs. Or renumber whichever merges
  second. Do not enable `outOfOrder` on a ledger schema.

### F7. LOW, CONFIRMED: amount overflow returns 500

- Where: `stock/Quantities.java:21` (`longValueExact`) and the `Math.addExact` totals. Prices have
  `@PositiveOrZero` but no upper bound.
- Scenario (reproduced): a product with `sell_minor` 4,000,000,000,000,000,000. Selling 3 returns
  500 `internal_error` (an `ArithmeticException`). The same applies to purchases and valuation
  totals. Valuation is worse: one bad product makes `/reports/valuation` fail with 500 for
  everyone.
- Fix: put `@Max` on every `*_minor` input (for example 10^13) and a matching database CHECK.
  Map `ArithmeticException` to 422 `amount_out_of_range`.

### F8. LOW, CONFIRMED: a malformed cursor returns 500

- Where: `SalesService.java:268`, `PurchasingService.java:270` and `StockService.java:135`. They
  index `split` results and call `Instant.parse` and `UUID.fromString` without catching errors.
  `CatalogueService.list` does catch them.
- Scenario (reproduced): `GET /sales?cursor=Zm9v`, `/purchases?cursor=Zm9v` and
  `/stock/movements?cursor=Zm9vfGJhcg` each return 500.
- Fix: one shared cursor parser that throws 400 `malformed_request`.

### F9. LOW, CONFIRMED: product codes that differ only by Unicode whitespace are both accepted

- Where: `CatalogueService.java:105,198` use `String.trim()`, which strips only characters up to
  U+0020. `V10` checks `code = btrim(code)`, which strips only spaces, and the unique index is
  `lower(code)`.
- Scenario (reproduced): `NB-1` and `NB-1` followed by U+00A0 (no-break space, common when codes
  are pasted from a spreadsheet) are both created with 201. Data dictionary rule 1 says codes are
  unique ignoring spaces at the ends. The R5 import will hit this.
- Fix: use `strip()` and reject or normalise (NFKC) codes containing control characters or Unicode
  spaces. Make the database CHECK `code ~ '^\S(.*\S)?$'`.

### F10. LOW, PLAUSIBLE: profit reads the mutable sale header, not the append-only lines

- Where: `reports/internal/ReportsRepository.java:101` sums `retail_sales.total_minor` and
  `cost_total_minor`. `bms_app` has `UPDATE` on every column of `retail_sales` (`V11`), and
  `retail_sale_lines` is append-only precisely so that the snapshots cannot change.
  `retail_stocktake_lines` also keeps `UPDATE` after commit.
- Scenario: any future code path, or a bug that updates `retail_sales.cost_total_minor`, changes
  past profit, and the append-only lines do not prevent it. FR-RET-10 says "sale line totals less
  their cost snapshots".
- Fix: compute profit from `retail_sale_lines` joined to non-voided sales. Alternatively, add a
  trigger that allows `UPDATE` on `retail_sales` only for `paid_minor`, the void columns,
  `updated_at`, `version` and the entry ids (once, from null), and rejects changes to committed
  stock-take lines.

### F11. LOW, PLAUSIBLE: the purchase list shows other branches' quantities to a branch-scoped buyer

- Where: `purchasing/internal/PurchasingRepository.java:121-167`. The branch filter decides which
  purchases are listed, but `withLines` returns `qty_total`, `line_total_minor` and the purchase
  `total_minor` across all branches.
- Scenario: a purchase splits 10 to branch A and 40 to branch B. A user with
  `retail.purchase.create` at A only sees qty 50 and the full value.
- Fix: for a scoped caller, restrict lines and totals to the scoped branches using the movements,
  or document that purchases are tenant-wide documents.

### F12. LOW, CONFIRMED (by reading): tests miss the cases above

- `RetailStockIT.aStocktakeAdjustsToTheCountAndPostsTheVariance` never moves stock between draft
  and commit, so F1 cannot fail it.
- The concurrency tests race only like against like: two sales (`RetailSalesIT`) or six restocks
  (`RetailPurchasingIT`). Nothing races a restock against a usage report, void or stock-take,
  which is where F2 is.
- No test builds a principal with different scopes per permission, so F5 cannot be caught. The
  development header applies one branch list to every permission. A `X-Dev-Scopes` header or a
  real session fixture is needed.
- No negative tests for malformed cursors, out-of-range amounts or Unicode codes (F7 to F9).
- Valuation tests cover only branches that still hold stock (F3) and never backdate (F4).

## Checked and found sound

- Every one of the 17 retail tables calls `bms_apply_tenant_rls` (forced RLS). Inserts take
  `tenant_id` from `current_setting('app.tenant_id')`, matching the lending tables (no column
  default is used anywhere in the schema). Composite foreign keys are on `(tenant_id, x_id)`.
- `bms_seed_retail_chart` is not `SECURITY DEFINER`. Its `EXECUTE` is revoked from `PUBLIC`, and
  it is reachable only through the two platform functions, which keep a fixed `search_path`. Its
  codes do not collide with the lending chart. It skips any code or key that already exists.
- Movements, price history, sale lines, purchases, usage and payments: `bms_app` has only
  `SELECT, INSERT`, plus the `reject_mutation` trigger.
- Void: the status check runs under the sale row lock, and so does the payment's check, so a void
  cannot run twice or race a payment. A paid credit sale is refused. `UNIQUE (tenant_id,
  reverses_entry_id)` and `UNIQUE (tenant_id, reverses_movement_id)` close the remaining races.
- Idempotency keys are scoped to tenant and principal, hash method, path and body, and roll back
  with a failed transaction. Journal idempotency keys contain random event ids and fit in 100
  characters.
- Balances are written only under `SELECT ... FOR UPDATE`, in (branch, product) order. Two sales
  and two restocks serialise. The price history chain under concurrent restocks is intact.
- The sales role gets 403 on profit. Cost fields are nulled on stock, movements, sales, stock-takes,
  usage, valuation and history. Audit entries with cost are not readable by the sales role.
- Dynamic SQL binds every value. `ILIKE` input is escaped. List limits are clamped. Request lists
  are bounded (`@Size`).
