# ADR-089: Add a branch from the app as a shop, a store or a head office

## Status

Proposed (2026-10-25, issue #581; the owner and the pilot tenant asked for it on 25 October 2026, the ADR is not yet accepted).
Amends ADR-041 and ADR-048. No migration.

## Context

The pilot tenant wants many stores and many shops. The server has created branches since FR-BR-01 (`POST /branches`,
`core.branches.manage`, the plan's `max_branches` checked first), and ADR-048 let that call make a Store with `sells_goods`
false. No screen called it, so a business could not add a branch without the operator. Two gaps stood in the way of a plain
form: the code was required (2 to 10 capital letters or digits), which a shopkeeper should not have to invent, and a branch
that keeps no goods (ADR-041, a head office) could only be made as a shop and then switched, in two calls.

## Decision

- **One form on the Branches screen**, "Add a branch", for whoever holds `core.branches.manage`: the name (needed), What is it? as one segmented control with Shop (sells), Store (keeps stock, does not sell) and Head office (keeps
  no goods), and where it is (the existing `location`). The API takes no phone, so the form asks for none.
- **The kind is sent as the two flags.** Shop is `sells_goods` true and `stocks_goods` true; Store is `sells_goods` false and
  `stocks_goods` true; Head office is `stocks_goods` false. `POST /branches` now takes `stocks_goods` beside `sells_goods`, both
  optional and true when omitted, and refuses both false with 422 `store_must_stock` before writing, as the PATCH does. The words
  "head office" in the form name a branch that keeps no goods; the one `is_head_office` branch of each business is unchanged.
- **The code is made on the server when none is sent**: the letters and digits of the name in capitals, at most six, and a
  number after them when that code is taken in the business (Kasubi Store is KASUBI, a second Kasubi branch KASUBI2). A name with
  fewer than two such characters starts from BR. **The form asks for no code**, by the owner's rule of #562 that the app makes
  every code and nobody types one; it shows the code it expects under the name ("Code KASUBI, made by the app from the name"),
  worked out from the branches it lists. The API still takes a typed `code` (2 to 10 capital letters or digits), because the
  internal `BranchProvisioning` port and existing callers send one; a typed code that is taken is refused with 409
  `duplicate_branch_code` as before. The insert uses `ON CONFLICT (tenant_id, code) DO NOTHING` for that typed code only: a made
  code cannot meet it, because the lock below serialises the adds.
- **The plan limit holds under concurrent adds.** The create and the reopen take a transaction advisory lock per business
  (`pg_advisory_xact_lock` on the tenant id and `core.branch.open`) before they count the open branches, so two adds or reopens
  at the same moment queue, and the second counts the first's branch once it commits (read committed: the count is its own
  statement after the lock). Two adds of one name at the same moment therefore also get two codes (KASUBI and KASUBI2), never
  a refusal; `ON CONFLICT` answers only a typed code that is taken, and is no substitute for the lock. `AddBranchIT` proves it without leaning on thread timing: the
  test holds the same lock on its own connection, sends two adds (or two reopens, or three adds of one name) and waits until
  `pg_locks` shows every request blocked on it, checks that none has answered 1.5 seconds later, then lets go. With room for one, exactly one wins and the other gets
  `plan_limit_reached`. With the lock taken out of `BranchService` no request ever waits, so all three tests fail every time. A reopen also
  updates only an inactive row at the version it read, after taking the branch row `FOR UPDATE`, so the same branch reopened
  twice at once opens once, with one audit row; the second gets a plain 409 (`reopeningOneBranchTwiceOpensItOnce`).
  **Why an advisory lock and not `SELECT ... FROM tenants ... FOR UPDATE`:** a row lock on the tenant needs the application role
  to hold UPDATE on `tenants` (it reads its own row under RLS and changes nothing there), and it would queue every other writer
  of that row, such as a settings or plan change, behind a branch add. The advisory lock names only this count, is released at
  commit or rollback, and follows the per-business keys already used for the cash day and item names
  (`hashtextextended(current_setting('app.tenant_id') || ':...')`).
- **`ON CONFLICT (tenant_id, code)` has its index.** `V1__foundation.sql:151` declares `UNIQUE (tenant_id, code)` on `branches`, a
  plain table constraint on exactly those columns, which is the arbiter the clause names; every create in the suite goes
  through it.
- **The rules apply from creation.** A Store refuses a sale with `branch_does_not_sell` and a branch that keeps no goods with
  `branch_not_stocking` from the moment it exists, through the existing server checks (ADR-041, ADR-048); nothing new is
  enforced. A store becomes a shop and a store again through `PATCH /branches/{id}` as before.
- **The plan limit is said in plain words.** On `plan_limit_reached` the form says the plan has no room for another branch,
  with a link to Plan and billing for whoever holds `core.billing.read`, and "Ask the owner to change the plan" for others. The
  server's detail, which names `max_branches`, is never shown.
- **Every list follows at once.** A dark toast names the branch and what it is ("Gayaza Store added. It is a store: it keeps
  stock and does not sell."), and the screen reads `/me`, the branch list and the retail queries again, so the branch picker,
  Move stock and the stock lists carry it without a reload.
- **Permission: the existing `core.branches.manage`.** No permission is added.

## Consequences

**Better:**
- An owner adds a shop, a store or a head office in one step, with no code to invent, and the kind cannot be left half set.
- The code printed in document and credit note numbers (`INV-HQ-000001`) reads like the branch's name.
- A store or a head office never exists, even for a moment, as a shop that sells.

**Worse:**
- A made code can be less telling than a chosen one (two branches of one town are KASUBI and KASUBI2), and the code never
  changes after creation.
- The code shown on the form can differ from the one made when a branch the caller cannot see holds it; the list shows the real one.
- The form's "Head office" is a branch that keeps no goods, not the one head office of the business; the list keeps the Head
  office badge for that one only.

**Watch for:**
- A plan raised by the operator is read on the next add; nothing caches the limit.
- Many branches of one name make longer codes; the suffix stays within the 10 characters of the check.
